/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */
package com.sneedit.deplens.console;

import com.sneedit.deplens.inventory.BuildInventory;
import com.sneedit.deplens.inventory.InventoryEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.sneedit.deplens.inventory.InventoryEntry.*;
import static org.assertj.core.api.Assertions.*;

class WrapperChecksumUpdaterTest {
    @TempDir Path root;
    private static final String OLD = "0".repeat(64);
    private static final String HASH = "ab".repeat(32);

    private InventoryEntry wrapper(BuildInventory report, Path file, String text) throws Exception {
        Files.writeString(file, text);
        var entry = new InventoryEntry(root.toString(), ":", Type.GRADLE, "gradle");
        entry.versionSources.add(new Source(file.toString(), 2, SourceKind.WRAPPER, "distributionUrl", "8.8"));
        entry.statuses.clear();
        entry.statuses.add(Status.UPDATE_AVAILABLE);
        entry.candidates.add("9.0.0");
        entry.recommendedVersion = "9.0.0";
        entry.updateContexts.add(new UpdateContext("wrapper", "8.8", "8.8", "9.0.0", "9.0.0",
                null, null, "9.0.0", List.of(), List.of("9.0.0"), List.of(), null, null));
        report.entries.add(entry);
        return entry;
    }

    private String text(String type) {
        return "# wrapper\r\ndistributionUrl=https\\://mirror.example/dist/gradle-8.8-" + type + ".zip\r\n"
                + "distributionSha256Sum :  " + OLD + "  \r\n# keep this comment\r\nnetworkTimeout=10000\r\n";
    }

    @Test
    void binAndAllUseExactMirrorSidecarsAndPreserveFormatting() throws Exception {
        for (String type : List.of("bin", "all")) {
            Path file = root.resolve(type + ".properties");
            var report = new BuildInventory();
            var entry = wrapper(report, file, text(type));
            var selection = new UpdateSelection(report);
            selection.bulk('l');
            assertThat(selection.apply(new WrapperChecksumUpdater(uri -> {
                assertThat(uri.toString()).isEqualTo("https://mirror.example/dist/gradle-9.0.0-" + type + ".zip.sha256");
                return HASH.toUpperCase() + "\n";
            }))).containsExactly(file.toRealPath());
            assertThat(Files.readString(file)).isEqualTo(text(type).replace("8.8", "9.0.0").replace(OLD, HASH));
            assertThat(entry.selectedVersion).isEqualTo("9.0.0");
        }
    }

    @Test
    void currentChoiceDoesNotReadChecksumOrChangeSources() throws Exception {
        var report = new BuildInventory();
        Path file = root.resolve("wrapper.properties");
        wrapper(report, file, text("bin"));
        assertThat(new UpdateSelection(report).apply(new WrapperChecksumUpdater(uri -> {
            throw new AssertionError("Current must not fetch a checksum");
        }))).isEmpty();
        assertThat(Files.readString(file)).isEqualTo(text("bin"));
    }

    @Test
    void absentChecksumDoesNotAddOneOrRequireNetwork() throws Exception {
        var report = new BuildInventory();
        Path file = root.resolve("wrapper.properties");
        String text = "# wrapper\ndistributionUrl=https\\://mirror.example/gradle-8.8-all.zip\n";
        wrapper(report, file, text);
        var selection = new UpdateSelection(report);
        selection.bulk('l');
        selection.apply(new WrapperChecksumUpdater(uri -> { throw new AssertionError("No checksum to update"); }));
        assertThat(Files.readString(file)).isEqualTo(text.replace("8.8", "9.0.0"));
    }

    @Test
    void invalidOrMissingChecksumPreventsEverySelectedEdit() throws Exception {
        for (String result : List.of("", "a".repeat(63), "g".repeat(64), HASH + " filename.zip", HASH + " ".repeat(500), "x".repeat(500))) {
            var report = new BuildInventory();
            Path file = root.resolve("wrapper.properties");
            var entry = wrapper(report, file, text("bin"));
            Path dependency = root.resolve("dependency.properties");
            Files.writeString(dependency, "shared=8.8\n");
            entry.versionSources.add(new Source(dependency.toString(), 1, SourceKind.PROPERTY, "shared", "8.8"));
            var selection = new UpdateSelection(report);
            selection.bulk('l');
            assertThatIOException().isThrownBy(() -> selection.apply(new WrapperChecksumUpdater(uri -> result)))
                    .withMessageContaining("Invalid wrapper distribution checksum");
            assertThat(Files.readString(file)).isEqualTo(text("bin"));
            assertThat(Files.readString(dependency)).isEqualTo("shared=8.8\n");
            assertThat(entry.selectedVersion).isNull();
        }
    }

    @Test
    void duplicateEscapedOrContinuedPropertiesAreRejectedBeforeFetching() throws Exception {
        for (String suffix : List.of("distributionSha256Sum=" + OLD + "\n",
                "distribution\\u0053ha256Sum=" + OLD + "\n", "distributionUrl=https://other.example/gradle-8.8-bin.zip\n")) {
            var report = new BuildInventory();
            Path file = root.resolve("wrapper.properties");
            wrapper(report, file, text("bin") + suffix);
            var selection = new UpdateSelection(report);
            selection.bulk('l');
            assertThatIOException().isThrownBy(() -> selection.apply(new WrapperChecksumUpdater(uri -> {
                throw new AssertionError("Ambiguous properties must not fetch metadata");
            })));
            assertThat(Files.readString(file)).isEqualTo(text("bin") + suffix);
        }
        var report = new BuildInventory();
        Path file = root.resolve("continued.properties");
        String text = text("bin").replace(OLD, "aa\\\r\nbb");
        wrapper(report, file, text);
        var selection = new UpdateSelection(report);
        selection.bulk('l');
        assertThatIOException().isThrownBy(() -> selection.apply(new WrapperChecksumUpdater(uri -> HASH)));
        assertThat(Files.readString(file)).isEqualTo(text);
    }

    @Test
    void localSidecarsWorkOfflineButRemoteChecksumUpdatesFailBeforeWrites() throws Exception {
        Path sidecar = root.resolve("gradle-9.0.0-all.zip.sha256");
        Files.writeString(sidecar, HASH + "\n");
        var report = new BuildInventory();
        Path file = root.resolve("wrapper.properties");
        String local = text("all").replace("https\\://mirror.example/dist/", root.toUri().toString());
        wrapper(report, file, local);
        var selection = new UpdateSelection(report);
        selection.bulk('l');
        selection.apply(new WrapperChecksumUpdater(true));
        assertThat(Files.readString(file)).isEqualTo(local.replace("8.8", "9.0.0").replace(OLD, HASH));
        wrapper(report = new BuildInventory(), file, text("all"));
        var remote = new UpdateSelection(report);
        remote.bulk('l');
        assertThatIOException().isThrownBy(() -> remote.apply(new WrapperChecksumUpdater(true)))
                .withMessageContaining("offline mode");
        assertThat(Files.readString(file)).isEqualTo(text("all"));
        Files.delete(sidecar);
        wrapper(report = new BuildInventory(), file, local);
        var missing = new UpdateSelection(report);
        missing.bulk('l');
        assertThatIOException().isThrownBy(() -> missing.apply(new WrapperChecksumUpdater(true)));
        assertThat(Files.readString(file)).isEqualTo(local);
    }

    @Test
    void concurrentSourceChangeDuringChecksumFetchPreventsAllWrites() throws Exception {
        var report = new BuildInventory();
        Path file = root.resolve("wrapper.properties");
        wrapper(report, file, text("bin"));
        var selection = new UpdateSelection(report);
        selection.bulk('l');
        assertThatIOException().isThrownBy(() -> selection.apply(new WrapperChecksumUpdater(uri -> {
            Files.writeString(file, text("bin") + "# edited concurrently\n");
            return HASH;
        }))).withMessageContaining("Source changed during selection");
        assertThat(Files.readString(file)).isEqualTo(text("bin") + "# edited concurrently\n");
    }
}
