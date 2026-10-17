/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.console;

import com.sneedit.deplens.inventory.InventoryEntry;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.sneedit.deplens.inventory.InventoryEntry.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests source editor.
 */
class SourceEditorTest {
    @TempDir
    Path root;

    /**
     * Creates a temporary source file for an edit test.
     */
    private Path file(String name, String text) throws Exception {
        Path path = root.resolve(name);
        Files.writeString(path, text);
        return path;
    }

    /**
     * Finds or creates the component record used by the test.
     */
    private InventoryEntry entry(Type type, String coordinate) {
        return new InventoryEntry(root.toString(), ":", type, coordinate);
    }

    /**
     * Locates the fixture version token through the production source editor.
     */
    private SourceEditor.@Nullable Location locate(Path path, Type type, String coordinate, int line, SourceKind kind, @Nullable String key) throws Exception {
        return SourceEditor.locate(entry(type, coordinate), new Source(path.toString(), line, kind, key, "1.0"));
    }

    /**
     * Verifies that incomplete property variable and catalog sources remain read only.
     */
    @Test
    void incompletePropertyVariableAndCatalogSourcesRemainReadOnly() throws Exception {
        Path input = file("sources.gradle", "version = '1.0'\n");
        for (SourceKind kind : List.of(SourceKind.PROPERTY, SourceKind.VARIABLE, SourceKind.CATALOG)) {
            var source = new Source(input.toString(), 1, kind, null, "1.0");
            assertThat(SourceEditor.locate(entry(Type.DEPENDENCY, "g:a"), source)).isNull();
        }
        var absentVersion = new Source(input.toString(), 1, SourceKind.VARIABLE, "version", null);
        assertThat(SourceEditor.locate(entry(Type.DEPENDENCY, "g:a"), absentVersion)).isNull();
        assertThat(Files.readString(input)).isEqualTo("version = '1.0'\n");
    }

    /**
     * Verifies that DepLens edits only selected literals in Groovy Kotlin settings properties and wrapper.
     */
    @Test
    void editsOnlySelectedLiteralsInGroovyKotlinSettingsPropertiesAndWrapper() throws Exception {
        Path groovy = file("build.gradle", "// keep 1.0\r\ndef libVersion = '1.0' // keep\r\ndependencies { implementation 'g:a:1.0'; implementation 'g:b:1.0' }\r\n");
        Path kotlin = file("build.gradle.kts", "dependencies { implementation(group = \"g\", name = \"a\", version = \"1.0\") }\n");
        Path settings = file("settings.gradle.kts", "plugins { id(\"plugin.a\") version(\"1.0\"); id(\"plugin.b\") version \"1.0\" }\n");
        Path props = file("gradle.properties", "# keep\nlib.version = 1.0\nother=1.0\n");
        Path wrapper = file("wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-1.0-all.zip\ndistributionSha256Sum=keep\n");
        List<SourceEditor.Location> locations = List.of(
                locate(groovy, Type.DEPENDENCY, "g:a", 3, SourceKind.BUILD_SCRIPT, null),
                locate(groovy, Type.DEPENDENCY, "g:a", 2, SourceKind.VARIABLE, "libVersion"),
                locate(kotlin, Type.DEPENDENCY, "g:a", 1, SourceKind.BUILD_SCRIPT, null),
                locate(settings, Type.PLUGIN, "plugin.a", 1, SourceKind.SETTINGS, null),
                locate(props, Type.DEPENDENCY, "g:a", 2, SourceKind.PROPERTY, "lib.version"),
                locate(wrapper, Type.GRADLE, "gradle", 1, SourceKind.WRAPPER, "distributionUrl"));
        assertThat(locations).doesNotContainNull();
        SourceEditor.apply(locations.stream().map(l -> new SourceEditor.Change(l, "2.0")).toList());
        assertThat(Files.readString(groovy)).isEqualTo("// keep 1.0\r\ndef libVersion = '2.0' // keep\r\ndependencies { implementation 'g:a:2.0'; implementation 'g:b:1.0' }\r\n");
        assertThat(Files.readString(settings)).contains("id(\"plugin.a\") version(\"2.0\")", "id(\"plugin.b\") version \"1.0\"");
        assertThat(Files.readString(wrapper)).contains("gradle-2.0-all.zip", "distributionSha256Sum=keep");
    }

    /**
     * Verifies that catalog keys rich versions inline versions and shorthand preserve formatting.
     */
    @Test
    void catalogKeysRichVersionsInlineVersionsAndShorthandPreserveFormatting() throws Exception {
        Path catalog = file("libs.versions.toml", """
                [versions]
                "shared.key" = "1.0" # keep
                rich = { strictly = "1.0", reject = ["9.0"] }
                [libraries]
                inline = { module = "g:a", version = "1.0" }
                short = "g:a:1.0"
                [plugins]
                tool = { id = "plugin.a", version = "1.0" }
                """);
        List<SourceEditor.Change> changes = new ArrayList<>();
        for (String key : List.of("versions.shared.key", "versions.rich", "libraries.inline", "libraries.short", "plugins.tool")) {
            var l = locate(catalog, Type.DEPENDENCY, "g:a", 1, SourceKind.CATALOG, key);
            assertThat(l).as(key).isNotNull();
            changes.add(new SourceEditor.Change(l, "2.0"));
        }
        SourceEditor.apply(changes);
        assertThat(Files.readString(catalog)).contains("\"shared.key\" = \"2.0\" # keep", "strictly = \"2.0\", reject = [\"9.0\"]", "version = \"2.0\"", "short = \"g:a:2.0\"");
    }

    /**
     * Verifies that write failure names failed file and preserves already applied changes without rollback.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC, org.junit.jupiter.api.condition.OS.LINUX})
    void writeFailureNamesFailedFileAndPreservesAlreadyAppliedChangesWithoutRollback() throws Exception {
        Path a = file("first.gradle", "implementation 'g:a:1.0'\n");
        Path b = file("readonly.gradle", "implementation 'g:a:1.0'\n");
        var first = locate(a, Type.DEPENDENCY, "g:a", 1, SourceKind.BUILD_SCRIPT, null);
        var second = locate(b, Type.DEPENDENCY, "g:a", 1, SourceKind.BUILD_SCRIPT, null);
        var permissions = Files.getPosixFilePermissions(b);
        try {
            Files.setPosixFilePermissions(b, java.nio.file.attribute.PosixFilePermissions.fromString("r--r--r--"));
            Assumptions.assumeFalse(Files.isWritable(b), "Privileged runtime can write read-only files");
            assertThatThrownBy(() -> SourceEditor.apply(List.of(new SourceEditor.Change(first, "2.0"), new SourceEditor.Change(second, "2.0"))))
                    .hasMessageContaining(second.file().toString()).hasMessageContaining(first.file().toString()).hasMessageContaining("No rollback");
            assertThat(Files.readString(a)).contains("g:a:2.0");
            assertThat(Files.readString(b)).contains("g:a:1.0");
        } finally {
            Files.setPosixFilePermissions(b, permissions);
        }
    }

    /**
     * Verifies that DepLens refuses stale ambiguous and overlapping sources before writing any file.
     */
    @Test
    void refusesStaleAmbiguousAndOverlappingSourcesBeforeWritingAnyFile() throws Exception {
        Path a = file("a.gradle", "implementation 'g:a:1.0'\n");
        Path b = file("b.gradle", "implementation 'g:a:1.0'\n");
        var la = locate(a, Type.DEPENDENCY, "g:a", 1, SourceKind.BUILD_SCRIPT, null);
        var lb = locate(b, Type.DEPENDENCY, "g:a", 1, SourceKind.BUILD_SCRIPT, null);
        Files.writeString(b, "// user edit\nimplementation 'g:a:1.0'\n");
        assertThatThrownBy(() -> SourceEditor.apply(List.of(new SourceEditor.Change(la, "2.0"), new SourceEditor.Change(lb, "2.0")))).hasMessageContaining("Source changed");
        assertThat(Files.readString(a)).contains("1.0");
        assertThatThrownBy(() -> SourceEditor.apply(List.of(new SourceEditor.Change(la, "2.0"), new SourceEditor.Change(la, "3.0")))).hasMessageContaining("Overlapping");
        Path ambiguous = file("same.gradle", "implementation 'g:a:1.0'; implementation 'g:a:1.0'\n");
        assertThat(locate(ambiguous, Type.DEPENDENCY, "g:a", 1, SourceKind.BUILD_SCRIPT, null)).isNull();
    }
}
