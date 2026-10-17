/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.sneedit.deplens.inventory.InventoryEntry.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests source index.
 */
class SourceIndexTest {
    @TempDir
    Path root;

    /**
     * Verifies that DepLens preserves shared version keys plugin states and declaration lines.
     */
    @Test
    void preservesSharedVersionKeysPluginStatesAndDeclarationLines() throws Exception {
        Path catalog = write("gradle/libs.versions.toml", """
                [versions]
                shared = "1.2.3"
                [libraries]
                lib = { module = "org.test:lib", version.ref = "shared" }
                [plugins]
                example-plugin = { id = "org.test.plugin", version.ref = "shared" }
                [bundles]
                all = ["lib"]
                """);
        Path build = write("build.gradle.kts", """
                plugins {
                    // id("not.a.plugin") version "99"
                    alias(libs.plugins.example.plugin) apply false
                    id("other.plugin") version "2.0" apply false
                    id("third.plugin") version "3.0"
                }
                dependencies { implementation(libs.bundles.all) }
                """);
        SourceIndex sources = new SourceIndex(root);
        sources.addCatalog("libs", catalog);
        sources.addScript(build);
        InventoryEntry item = new InventoryEntry(root.toString(), ":", Type.DEPENDENCY, "org.test:lib");
        sources.attach(item, build, "implementation", "1.2.3");
        assertThat(item.versionSources).containsExactly(new Source(catalog.toString(), 2, SourceKind.CATALOG, "versions.shared", "1.2.3"));
        assertThat(item.catalogs).containsExactly(new CatalogReference("libs", "lib", "shared", List.of("all")));
        assertThat(sources.plugins(build)).extracting(SourceIndex.PluginDeclaration::id).containsExactly("other.plugin", "third.plugin", "org.test.plugin");
        assertThat(sources.plugins(build).stream().filter(plugin -> plugin.id().equals("other.plugin")).findFirst().orElseThrow().state()).isEqualTo("DECLARED_APPLY_FALSE");
        Path groovy = write("build.gradle", "plugins { alias libs.plugins.example.plugin apply false }\n");
        sources.addScript(groovy);
        assertThat(sources.plugins(groovy)).extracting(SourceIndex.PluginDeclaration::id).containsExactly("org.test.plugin");
        assertThat(sources.plugins(groovy).getFirst().state()).isEqualTo("DECLARED_APPLY_FALSE");
    }

    /**
     * Verifies that DepLens resolves properties shared root variables and map notation without guessing computed sources.
     */
    @Test
    void resolvesPropertiesSharedRootVariablesAndMapNotationWithoutGuessingComputedSources() throws Exception {
        Path build = write("build.gradle", "def sharedVersion = '1.2.3'\n");
        Path child = write("child/build.gradle.kts", """
                dependencies {
                    implementation("org.test:lib:$sharedVersion")
                    implementation(group = "org.test", name = "tool", version = toolVersion)
                    implementation("org.test:computed:${calculateVersion()}")
                }
                """);
        Path properties = write("gradle.properties", "toolVersion=2.0\n");
        SourceIndex sources = new SourceIndex(root);
        sources.addScript(build);
        sources.addScript(child);
        sources.addProperties(properties);
        InventoryEntry lib = new InventoryEntry(root.toString(), ":child", Type.DEPENDENCY, "org.test:lib");
        sources.attach(lib, child, "implementation", "1.2.3");
        assertThat(lib.versionSources).containsExactly(new Source(build.toString(), 1, SourceKind.VARIABLE, "sharedVersion", "1.2.3"));
        InventoryEntry tool = new InventoryEntry(root.toString(), ":child", Type.DEPENDENCY, "org.test:tool");
        sources.attach(tool, child, "implementation", "2.0");
        assertThat(tool.versionSources).containsExactly(new Source(properties.toString(), 1, SourceKind.PROPERTY, "toolVersion", "2.0"));
        InventoryEntry computed = new InventoryEntry(root.toString(), ":child", Type.DEPENDENCY, "org.test:computed");
        sources.attach(computed, child, "implementation", "4.0");
        assertThat(computed.versionSources).allMatch(source -> source.kind() == SourceKind.UNKNOWN);
        Path shadow = write("shadow/build.gradle", "def sharedVersion = '1.2.3'\ndependencies { implementation \"org.test:lib:${rootProject.ext.sharedVersion}\" }\n");
        sources.addScript(shadow);
        InventoryEntry rootQualified = new InventoryEntry(root.toString(), ":shadow", Type.DEPENDENCY, "org.test:lib");
        sources.attach(rootQualified, shadow, "implementation", "1.2.3");
        assertThat(rootQualified.versionSources).containsExactly(new Source(build.toString(), 1, SourceKind.VARIABLE, "sharedVersion", "1.2.3"));
    }

    /**
     * Verifies that DepLens distinguishes settings plugins from plugin management and ignores comments.
     */
    @Test
    void distinguishesSettingsPluginsFromPluginManagementAndIgnoresComments() throws Exception {
        Path settings = write("settings.gradle", """
                pluginManagement {
                    plugins { id 'org.test.managed' version '1.0' }
                }
                plugins { id('org.test.settings') version('2.0') }
                /* plugins { id 'ignored' version '100' } */
                """);
        SourceIndex sources = new SourceIndex(root);
        sources.addScript(settings);
        assertThat(sources.plugins(settings)).extracting(SourceIndex.PluginDeclaration::state).containsExactly("PLUGIN_MANAGEMENT", "DECLARED");
        assertThat(sources.plugins(settings)).extracting(SourceIndex.PluginDeclaration::id).containsExactly("org.test.managed", "org.test.settings");
    }

    /**
     * Writes fixture text under the temporary project directory.
     */
    private Path write(String name, String content) throws Exception {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
}
