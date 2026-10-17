/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.tasks;

import com.google.gson.Gson;
import com.sneedit.deplens.inventory.BuildInventory;
import com.sneedit.deplens.inventory.InventoryEntry;
import com.sneedit.deplens.inventory.PartialCheckReport;
import com.sneedit.deplens.resolution.AnalysisModel;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PartialReportMergerTest {
    @TempDir
    Path directory;

    @Test
    void mergesInModelOrderAndKeepsSettingsAndUntouchedEntries() throws IOException {
        BuildInventory aggregate = new BuildInventory();
        InventoryEntry original = entry(":", "example:root");
        InventoryEntry untouched = entry(":app", "example:untouched");
        aggregate.entries.addAll(List.of(original, untouched));
        aggregate.warnings.add("Recovered resolution failure");

        BuildInventory root = new BuildInventory();
        InventoryEntry updated = entry(":", "example:root");
        updated.recommendedVersion = "2.0.0";
        InventoryEntry settings = entry("<settings>", "example.settings");
        root.entries.addAll(List.of(updated, settings));
        root.builds.add("/included-build");
        root.fastCheck = true;
        File rootFile = write("root", ":", root, List.of("Recovered resolution failure"), List.of("Root warning"));
        File appFile = write("app", ":app", new BuildInventory(), List.of(), List.of("App warning", "Root warning"));

        PartialReportMerger.merge(aggregate, List.of(model(":"), model(":app")),
                new LinkedHashSet<>(List.of(appFile, rootFile)));

        assertThat(aggregate.entries).extracting(InventoryEntry::id)
                .containsExactly(updated.id(), untouched.id(), settings.id());
        assertThat(aggregate.entries.getFirst().recommendedVersion).isEqualTo("2.0.0");
        assertThat(aggregate.warnings).containsExactly("Root warning", "App warning");
        assertThat(aggregate.builds).contains("/included-build");
        assertThat(aggregate.fastCheck).isTrue();
    }

    @Test
    void rejectsMissingProducerReports() {
        assertThatThrownBy(() -> PartialReportMerger.merge(new BuildInventory(), List.of(model(":")), Set.of()))
                .isInstanceOf(GradleException.class)
                .hasMessage("DepLens partial report count does not match the analyzed projects");
    }

    @Test
    void rejectsDuplicateProjectReports() throws IOException {
        File first = write("first", ":", new BuildInventory(), List.of(), List.of());
        File duplicate = write("duplicate", ":", new BuildInventory(), List.of(), List.of());

        assertThatThrownBy(() -> PartialReportMerger.merge(new BuildInventory(), List.of(model(":"), model(":app")),
                Set.of(first, duplicate)))
                .isInstanceOf(GradleException.class).hasMessage("Duplicate DepLens project report");
    }

    @Test
    void rejectsEntriesFromAnotherProjectOrBuild() throws IOException {
        for (InventoryEntry foreign : List.of(entry(":other", "example:other"),
                new InventoryEntry("/other-build", ":", InventoryEntry.Type.DEPENDENCY, "example:other"))) {
            BuildInventory partial = new BuildInventory();
            partial.entries.add(foreign);
            File file = write("foreign", ":", partial, List.of(), List.of());

            assertThatThrownBy(() -> PartialReportMerger.merge(new BuildInventory(), List.of(model(":")), Set.of(file)))
                    .isInstanceOf(GradleException.class)
                    .hasMessage("DepLens partial report contains a different project's entries");
        }
    }

    @Test
    void preservesTheCauseWhenAReportCannotBeRead() {
        File missing = directory.resolve("missing.json").toFile();

        assertThatThrownBy(() -> PartialReportMerger.merge(new BuildInventory(), List.of(model(":")), Set.of(missing)))
                .isInstanceOf(GradleException.class).hasMessage("Cannot read DepLens partial report")
                .hasCauseInstanceOf(IOException.class);
    }

    private static InventoryEntry entry(String project, String coordinate) {
        return new InventoryEntry("/build", project, InventoryEntry.Type.DEPENDENCY, coordinate);
    }

    private static AnalysisModel model(String project) {
        AnalysisModel model = mock(AnalysisModel.class);
        model.build = "/build";
        model.project = project;
        return model;
    }

    private File write(String name, String project, BuildInventory report,
                       List<String> removedWarnings, List<String> addedWarnings) throws IOException {
        Path file = directory.resolve(name + ".json");
        Files.writeString(file, new Gson().toJson(new PartialCheckReport("/build", project, report,
                removedWarnings, addedWarnings)));
        return file.toFile();
    }
}
