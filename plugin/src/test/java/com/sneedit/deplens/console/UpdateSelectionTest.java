/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.console;

import com.sneedit.deplens.inventory.BuildInventory;
import com.sneedit.deplens.inventory.InventoryEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.sneedit.deplens.inventory.InventoryEntry.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests update selection.
 */
class UpdateSelectionTest {
    @TempDir
    Path root;

    /**
     * Adds a consumer of a version source to the fixture report.
     */
    private InventoryEntry consumer(BuildInventory report, Path source, Type type, String coordinate, String value, String... candidates) {
        InventoryEntry e = new InventoryEntry(root.toString(), ":", type, coordinate);
        e.versionSources.add(new Source(source.toString(), 1, SourceKind.PROPERTY, "shared", value));
        e.statuses.clear();
        e.statuses.add(Status.UPDATE_AVAILABLE);
        e.candidates.addAll(List.of(candidates));
        e.updateContexts.add(new UpdateContext("runtimeClasspath", "1.0", "1.0", "3.0", "2.0", null, "2.0", "3.0", List.of("3.0"), List.of(candidates), List.of(), null, null));
        report.entries.add(e);
        return e;
    }

    @Test
    void consoleAlignsOptionsAcrossDifferentNamesAndVersionLengths() throws Exception {
        Path first = root.resolve("first.properties"), second = root.resolve("second.properties");
        Files.writeString(first, "shared=1.0\n");
        Files.writeString(second, "shared=13.5.0\n");
        var report = new BuildInventory();
        consumer(report, first, Type.DEPENDENCY, "example:short", "1.0", "1.1", "2.0");
        consumer(report, second, Type.DEPENDENCY, "example:much-longer-name", "13.5.0", "13.6.0", "13.7.0");
        var output = new StringWriter();
        new InteractiveConsole(new UpdateSelection(report), new ByteArrayInputStream(new byte[]{13}),
                new PrintWriter(output), 100, 24).select();
        String plain = output.toString().replaceAll("\\x1b\\[[0-9;?]*[A-Za-z]", "");
        String a = plain.lines().filter(line -> line.contains("short") && line.contains("●")).findFirst().orElseThrow();
        String b = plain.lines().filter(line -> line.contains("much-longer-name") && line.contains("●")).findFirst().orElseThrow();
        assertThat(a.indexOf('●')).isEqualTo(b.indexOf('●'));
        assertThat(a.indexOf('○')).isEqualTo(b.indexOf('○'));
        assertThat(a.lastIndexOf('○')).isEqualTo(b.lastIndexOf('○'));
        assertThat(plain).doesNotContain("null", "[]", "Resolved:", "Rules:");
        assertThat(plain.lines().toList()).allSatisfy(line -> assertThat(line.length()).isLessThan(100));
    }

    @Test
    void interactiveOptionsMatchCheckTargetAndLatestTierAlternatives() throws Exception {
        Path file = root.resolve("gradle.properties");
        Files.writeString(file, "shared=1.0\n");
        var report = new BuildInventory();
        var entry = consumer(report, file, Type.DEPENDENCY, "example:library", "1.0",
                "1.1", "1.2", "2.0", "2.1", "3.0");
        entry.updateContexts.clear();
        entry.updateContexts.add(new UpdateContext("runtimeClasspath", "1.0", "1.2", "3.0", "3.0",
                "1.2", "2.1", "3.0", List.of(), List.copyOf(entry.candidates), List.of(), null, null));
        assertThat(entry.updateOptions()).containsExactly("1.2", "2.1", "3.0");
        assertThat(new UpdateSelection(report).rows.getFirst().options).containsExactly("1.0", "1.2", "2.1", "3.0");
        entry.recommendedVersion = "2.0";
        assertThat(entry.updateOptions()).containsExactly("1.2", "2.0");
        assertThat(new UpdateSelection(report).rows.getFirst().options).containsExactly("1.0", "1.2", "2.0");
    }

    @Test
    void consoleKeepsLastCandidateVisibleInANarrowTerminal() throws Exception {
        Path file = root.resolve("gradle.properties");
        Files.writeString(file, "shared=1.0\n");
        var report = new BuildInventory();
        consumer(report, file, Type.DEPENDENCY, "example:long-dependency-name", "1.0", "1.1", "1.2", "1.3", "1.4", "2.0", "3.0");
        var model = new UpdateSelection(report);
        String keys = "\033[C".repeat(model.rows.getFirst().options.size() - 1) + "\r";
        var output = new StringWriter();
        assertThat(new InteractiveConsole(model, new ByteArrayInputStream(keys.getBytes(StandardCharsets.UTF_8)),
                new PrintWriter(output), 40, 12).select()).isTrue();
        assertThat(model.rows.getFirst().target()).isEqualTo("3.0");
        String last = output.toString().substring(output.toString().lastIndexOf("\033[H\033[2J"))
                .replaceAll("\\x1b\\[[0-9;?]*[A-Za-z]", "");
        assertThat(last).contains("● 3.0");
        assertThat(last.lines().toList()).hasSizeLessThanOrEqualTo(12)
                .allSatisfy(line -> assertThat(line.length()).isLessThan(40));
    }

    @Test
    void outputFilterHidesSourcesWithoutChangingExcludedFiles() throws Exception {
        Path shown = root.resolve("shown.properties"), hidden = root.resolve("hidden.properties");
        Files.writeString(shown, "shared=1.0\n");
        Files.writeString(hidden, "shared=1.0\n");
        var report = new BuildInventory();
        consumer(report, shown, Type.DEPENDENCY, "example:shown", "1.0", "2.0", "3.0").configurations.add("implementation");
        consumer(report, hidden, Type.DEPENDENCY, "example:hidden", "1.0", "2.0", "3.0").configurations.add("tooling");
        var view = com.sneedit.deplens.inventory.OutputDeclaredConfigurationFilter.apply(report, List.of(), List.of("tooling"));
        var model = new UpdateSelection(report, view.entries);
        assertThat(model.rows).hasSize(1);
        assertThat(model.rows.getFirst().location.file()).isEqualTo(shown.toRealPath());
        model.bulk('l');
        model.apply();
        assertThat(Files.readString(shown)).isEqualTo("shared=3.0\n");
        assertThat(Files.readString(hidden)).isEqualTo("shared=1.0\n");
    }

    @Test
    void hiddenConsumerStillProtectsSharedSourceAndCannotAddOptions() throws Exception {
        Path source = root.resolve("gradle.properties");
        Files.writeString(source, "shared=1.0\n");
        var report = new BuildInventory();
        var shown = consumer(report, source, Type.DEPENDENCY, "example:shown", "1.0", "2.0", "3.0");
        shown.configurations.add("implementation");
        shown.recommendedVersion = "2.0";
        var hidden = consumer(report, source, Type.DEPENDENCY, "example:hidden", "1.0", "2.0", "3.0");
        hidden.configurations.add("tooling");
        var view = com.sneedit.deplens.inventory.OutputDeclaredConfigurationFilter.apply(report, List.of(), List.of("tooling"));
        assertThat(new UpdateSelection(report, view.entries).rows.getFirst().options).containsExactly("1.0", "2.0");
        hidden.statuses.add(Status.PINNED);
        var protectedModel = new UpdateSelection(report, view.entries);
        assertThat(protectedModel.rows).isEmpty();
        assertThat(protectedModel.notices).anyMatch(message -> message.contains("pinned consumer"));
        hidden.statuses.remove(Status.PINNED);
        hidden.updateContexts.clear();
        hidden.updateContexts.add(new UpdateContext("tooling", "1.0", "1.0", "1.1", "1.1",
                "1.1", null, null, List.of(), List.of("1.1"), List.of(), null, null));
        assertThat(new UpdateSelection(report, view.entries).rows).isEmpty();
    }

    /**
     * Verifies that shared key has one decision intersection and updates all consumers.
     */
    @Test
    void sharedKeyHasOneDecisionIntersectionAndUpdatesAllConsumers() throws Exception {
        Path file = root.resolve("gradle.properties");
        Files.writeString(file, "shared=1.0\n");
        BuildInventory report = new BuildInventory();
        InventoryEntry a = consumer(report, file, Type.DEPENDENCY, "g:a", "1.0", "1.1", "2.0", "3.0");
        InventoryEntry b = consumer(report, file, Type.PLUGIN, "plugin.b", "1.0", "2.0", "3.0");
        UpdateSelection model = new UpdateSelection(report);
        assertThat(model.rows).hasSize(1);
        assertThat(model.rows.getFirst().options).containsExactly("1.0", "2.0", "3.0");
        model.bulk('r');
        assertThat(model.rows.getFirst().target()).isEqualTo("2.0");
        model.bulk('l');
        assertThat(model.rows.getFirst().target()).isEqualTo("3.0");
        model.bulk('c');
        assertThat(model.rows.getFirst().target()).isEqualTo("1.0");
        model.bulk('r');
        model.apply();
        assertThat(Files.readString(file)).isEqualTo("shared=2.0\n");
        assertThat(a.selectedVersion).isEqualTo("2.0");
        assertThat(b.selectedVersion).isEqualTo("2.0");
    }

    /**
     * Verifies that already resolved shared consumer allows same target but restrictive context blocks it.
     */
    @Test
    void alreadyResolvedSharedConsumerAllowsSameTargetButRestrictiveContextBlocksIt() throws Exception {
        Path file = root.resolve("gradle.properties");
        Files.writeString(file, "shared=1.0\n");
        BuildInventory report = new BuildInventory();
        consumer(report, file, Type.DEPENDENCY, "g:a", "1.0", "2.0");
        var b = consumer(report, file, Type.PLUGIN, "plugin.b", "1.0");
        b.updateContexts.clear();
        b.updateContexts.add(new UpdateContext("classpath", "2.0", "2.0", "2.0", "2.0", null, null, null, List.of(), List.of(), List.of(), null, null));
        assertThat(new UpdateSelection(report).rows).hasSize(1);
        b.updateContexts.add(new UpdateContext("other", "1.0", "1.0", "2.0", "1.0", null, null, null, List.of(), List.of(), List.of(), null, null));
        assertThat(new UpdateSelection(report).rows).isEmpty();
    }

    /**
     * Verifies that pinned failed and unchecked consumers protect shared sources.
     */
    @Test
    void pinnedFailedAndUncheckedConsumersProtectSharedSources() throws Exception {
        Path file = root.resolve("gradle.properties");
        Files.writeString(file, "shared=1.0\n");
        for (Status blocked : List.of(Status.PINNED, Status.FAILED, Status.NOT_CHECKED)) {
            BuildInventory report = new BuildInventory();
            consumer(report, file, Type.DEPENDENCY, "g:a", "1.0", "2.0");
            var b = consumer(report, file, Type.PLUGIN, "plugin.b", "1.0", "2.0");
            b.statuses.add(blocked);
            var model = new UpdateSelection(report);
            assertThat(model.rows).isEmpty();
            assertThat(model.notices).isNotEmpty();
        }
    }

    /**
     * Verifies that dynamic selectors stay current on bulk and arrow selection is explicit.
     */
    @Test
    void dynamicSelectorsStayCurrentOnBulkAndArrowSelectionIsExplicit() throws Exception {
        Path file = root.resolve("gradle.properties");
        Files.writeString(file, "shared=1.+\n");
        BuildInventory report = new BuildInventory();
        consumer(report, file, Type.DEPENDENCY, "g:a", "1.+", "2.0", "3.0");
        UpdateSelection model = new UpdateSelection(report);
        model.bulk('l');
        assertThat(model.rows.getFirst().target()).isEqualTo("1.+");
        StringWriter output = new StringWriter();
        assertThat(new InteractiveConsole(model, input("\033[C\r"), new PrintWriter(output), 140, 30).select()).isTrue();
        assertThat(output.toString()).contains("FIXED / selector change");
        model.apply();
        assertThat(Files.readString(file)).isEqualTo("shared=2.0\n");
    }

    /**
     * Verifies that escape EOF and ignored space never apply and platform is selectable.
     */
    @Test
    void escapeEofAndIgnoredSpaceNeverApplyAndPlatformIsSelectable() throws Exception {
        Path file = root.resolve("gradle.properties");
        Files.writeString(file, "shared=1.0\n");
        BuildInventory report = new BuildInventory();
        consumer(report, file, Type.PLATFORM, "g:bom", "1.0", "2.0");
        for (String keys : List.of("\033", "\033[C", " \033")) {
            var model = new UpdateSelection(report);
            assertThat(new InteractiveConsole(model, input(keys), new PrintWriter(new StringWriter()), 100, 30).select()).isFalse();
            assertThat(Files.readString(file)).isEqualTo("shared=1.0\n");
        }
        var model = new UpdateSelection(report);
        assertThat(new InteractiveConsole(model, input("l\r"), new PrintWriter(new StringWriter()), 100, 30).select()).isTrue();
        assertThat(model.rows.getFirst().label()).contains("PLATFORM");
        model.apply();
        assertThat(Files.readString(file)).isEqualTo("shared=2.0\n");
    }

    /**
     * Builds a terminal input stream from the simulated key sequence.
     */
    private InputStream input(String keys) {
        return new ByteArrayInputStream(keys.getBytes(StandardCharsets.UTF_8));
    }
}
