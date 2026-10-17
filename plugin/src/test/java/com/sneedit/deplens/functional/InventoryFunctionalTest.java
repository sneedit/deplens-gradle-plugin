/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.functional;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end inventories use a private file repository: no Maven Central or plugin portal.
 */
class InventoryFunctionalTest {
    @TempDir
    Path directory;

    /**
     * Verifies that DepLens inventories platforms catalogs tools projects and separate builds.
     */
    @Test
    void inventoriesPlatformsCatalogsToolsProjectsAndSeparateBuilds() throws Exception {
        module("lib", "1.0", "<dependencies><dependency><groupId>org.test</groupId><artifactId>transitive</artifactId><version>1.0</version></dependency></dependencies>");
        module("lib", "2.0", "");
        module("org.jacoco", "org.jacoco.agent", "1.0", "");
        module("org.jacoco", "org.jacoco.ant", "1.0", "");
        for (String name : List.of("util", "managed", "constrained", "transitive", "tool", "build-tool"))
            module(name, "1.0", "");
        module("dynamic", "1.0", "");
        module("dynamic", "1.9", "");
        module("bom", "1.0", "<dependencyManagement><dependencies><dependency><groupId>org.test</groupId><artifactId>managed</artifactId><version>1.0</version></dependency></dependencies></dependencyManagement>");
        write("repo/org/test/dynamic/maven-metadata.xml", "<metadata><groupId>org.test</groupId><artifactId>dynamic</artifactId><versioning><versions><version>1.0</version><version>1.9</version></versions></versioning></metadata>");
        write("settings.gradle", "pluginManagement { repositories { maven { url = uri('repo') } } }\nrootProject.name = 'inventory-fixture'\ninclude 'sub'\nincludeBuild 'build-logic'\n");
        write("gradle/libs.versions.toml", """
                [versions]
                common = "1.0"
                [libraries]
                lib = { module = "org.test:lib", version.ref = "common" }
                util = { module = "org.test:util", version.ref = "common" }
                [bundles]
                shared = ["lib", "util"]
                """);
        write("gradle.properties", "toolVersion=1.0\n");
        write("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.8-bin.zip\n");
        write("build.gradle", """
                plugins {
                    id 'java'
                    id 'jacoco'
                    id 'com.sneedit.deplens'
                    id 'sample.convention'
                }
                repositories { maven { url = uri('repo') } }
                jacoco { toolVersion = '1.0' }
                configurations { jaxb; customTool }
                dependencies {
                    implementation platform('org.test:bom:1.0')
                    implementation 'org.test:managed'
                    implementation libs.bundles.shared
                    compileOnly libs.lib
                    annotationProcessor libs.lib
                    testCompileOnly libs.lib
                    testAnnotationProcessor libs.lib
                    runtimeOnly 'org.test:dynamic:1.+'
                    constraints { implementation 'org.test:constrained:1.0' }
                    implementation 'org.test:constrained'
                    jaxb "org.test:tool:$toolVersion"
                    customTool 'org.test:tool:1.0'
                    customTool 'org.test:lib:2.0'
                }
                """);
        write("sub/build.gradle", """
                plugins { id 'java' }
                repositories { maven { url = uri('../repo') } }
                dependencies { implementation 'org.test:lib:1.0' }
                """);
        write("build-logic/settings.gradle", "rootProject.name = 'conventions'\n");
        write("build-logic/build.gradle", """
                plugins { id 'java-gradle-plugin' }
                repositories { maven { url = uri('../repo') } }
                dependencies { implementation 'org.test:lib:1.0' }
                gradlePlugin { plugins { convention { id = 'sample.convention'; implementationClass = 'SamplePlugin' } } }
                """);
        write("build-logic/src/main/java/SamplePlugin.java", "public class SamplePlugin implements org.gradle.api.Plugin<org.gradle.api.Project> { public void apply(org.gradle.api.Project p) {} }");
        write("buildSrc/build.gradle", "plugins { id 'java' }\nrepositories { maven { url = uri('../repo') } }\ndependencies { implementation 'org.test:build-tool:1.0' }\n");

        BuildResult result = run("depLensInventory");
        JsonObject report = report();
        assertThat(result.getOutput()).contains("Update status: NOT_CHECKED");
        assertThat(report.get("complete").getAsBoolean()).withFailMessage("%s", report.get("warnings")).isTrue();
        assertThat(report.getAsJsonArray("builds")).hasSize(3);
        JsonObject lib = find(report, directory, ":", "DEPENDENCY", "org.test:lib");
        assertThat(strings(lib, "configurations")).contains("implementation", "compileOnly", "annotationProcessor", "testCompileOnly", "testAnnotationProcessor");
        assertThat(lib.getAsJsonArray("catalogs").toString()).contains("common", "shared");
        assertThat(lib.getAsJsonArray("versionSources").toString()).contains("versions.common");
        assertThat(strings(lib, "declaredSelectors")).containsExactly("1.0", "2.0");
        assertThat(lib.getAsJsonArray("resolutions").toString()).contains("1.0", "2.0");
        JsonObject jacoco = find(report, directory, ":", "DEPENDENCY", "org.jacoco:org.jacoco.agent");
        assertThat(strings(jacoco, "configurations")).contains("jacocoAgent");
        assertThat(jacoco.getAsJsonArray("versionSources").toString()).contains("GENERATED");
        assertThat(find(report, directory, ":", "DEPENDENCY", "org.test:managed").getAsJsonArray("platforms").toString()).contains("org.test:bom");
        assertThat(find(report, directory, ":", "DEPENDENCY", "org.test:constrained").getAsJsonArray("platforms")).isEmpty();
        assertThat(find(report, directory, ":", "PLATFORM", "org.test:bom")).isNotNull();
        assertThat(find(report, directory, ":", "CONSTRAINT", "org.test:constrained")).isNotNull();
        JsonObject dynamic = find(report, directory, ":", "DEPENDENCY", "org.test:dynamic");
        assertThat(strings(dynamic, "declaredSelectors")).containsExactly("1.+");
        assertThat(dynamic.getAsJsonArray("resolutions").toString()).contains("1.9");
        JsonObject tool = find(report, directory, ":", "DEPENDENCY", "org.test:tool");
        assertThat(strings(tool, "configurations")).contains("jaxb", "customTool");
        assertThat(tool.getAsJsonArray("versionSources").toString()).contains("PROPERTY", "toolVersion");
        assertThat(find(report, directory, ":sub", "DEPENDENCY", "org.test:lib")).isNotNull();
        assertThat(find(report, directory.resolve("build-logic"), ":", "DEPENDENCY", "org.test:lib")).isNotNull();
        assertThat(find(report, directory.resolve("buildSrc"), ":", "DEPENDENCY", "org.test:build-tool")).isNotNull();
        assertThat(strings(find(report, directory, ":", "PLUGIN", "sample.convention"), "pluginStates")).contains("LOCAL_CONVENTION");
        assertThat(report.getAsJsonArray("entries").toString()).doesNotContain("org.test:transitive");
        JsonObject gradle = find(report, directory, ":", "GRADLE", "gradle");
        assertThat(strings(gradle, "declaredSelectors")).containsExactly("8.8");
        assertThat(gradle.getAsJsonArray("resolutions").toString()).contains("runtime");
    }

    /**
     * Verifies that Kotlin dsl and custom catalog keep rich selectors and sources.
     */
    @Test
    void kotlinDslAndCustomCatalogKeepRichSelectorsAndSources() throws Exception {
        module("lib", "1.0", "");
        write("settings.gradle.kts", """
                rootProject.name = "kotlin-inventory"
                dependencyResolutionManagement {
                    versionCatalogs { create("tools") { from(files("versions.toml")) } }
                }
                """);
        write("versions.toml", """
                [versions]
                common = { strictly = "[1.0,2.0[", prefer = "1.0" }
                [libraries]
                lib = { module = "org.test:lib", version.ref = "common" }
                """);
        write("repo/org/test/lib/maven-metadata.xml", "<metadata><versioning><versions><version>1.0</version></versions></versioning></metadata>");
        write("build.gradle.kts", """
                plugins { java; id("com.sneedit.deplens") }
                repositories { maven { url = uri("repo") } }
                dependencies { implementation(tools.lib) }
                """);
        run("depLensInventory");
        JsonObject lib = find(report(), directory, ":", "DEPENDENCY", "org.test:lib");
        assertThat(strings(lib, "declaredSelectors")).containsExactly("[1.0,2.0[");
        assertThat(lib.getAsJsonArray("versionSources").toString()).contains("versions.toml", "versions.common");
        assertThat(lib.getAsJsonArray("resolutions").toString()).contains("1.0");
        assertThat(lib.getAsJsonArray("versionConstraints").toString()).contains("strictly", "[1.0,2.0[");
    }

    /**
     * Verifies that DepLens reports partial failure and retains unresolvable declarations.
     */
    @Test
    void reportsPartialFailureAndRetainsUnresolvableDeclarations() throws Exception {
        module("lib", "1.0", "");
        write("settings.gradle", "rootProject.name = 'partial'\n");
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                repositories { maven { url = uri('repo') } }
                configurations { declarationOnly { canBeResolved = false; canBeConsumed = false } }
                dependencies {
                    implementation 'org.test:missing:1.0'
                    declarationOnly 'org.test:lib:1.0'
                }
                """);
        BuildResult result = run("depLensInventory");
        assertThat(report().get("complete").getAsBoolean()).isFalse();
        assertThat(strings(find(report(), directory, ":", "DEPENDENCY", "org.test:missing"), "statuses")).contains("FAILED");
        assertThat(find(report(), directory, ":", "DEPENDENCY", "org.test:lib")).isNotNull();
        assertThat(result.getOutput()).contains("resolution incomplete").doesNotContain("up to date");
    }

    /**
     * Verifies that DepLens captures plugin aliases apply false and plugin management through settings entry point.
     */
    @Test
    void capturesPluginAliasesApplyFalseAndPluginManagementThroughSettingsEntryPoint() throws Exception {
        module("plugin-impl", "1.0", "");
        Path jar = directory.resolve("repo/org/test/plugin-impl/1.0/plugin-impl-1.0.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String id : List.of("org.test.plugin", "org.test.managed")) {
                output.putNextEntry(new JarEntry("META-INF/gradle-plugins/" + id + ".properties"));
                output.write("implementation-class=org.gradle.api.plugins.JavaPlugin\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        for (String id : List.of("org.test.plugin", "org.test.managed")) {
            write("repo/" + id.replace('.', '/') + "/" + id + ".gradle.plugin/1.0/" + id + ".gradle.plugin-1.0.pom",
                    "<project><modelVersion>4.0.0</modelVersion><groupId>" + id + "</groupId><artifactId>" + id + ".gradle.plugin</artifactId><version>1.0</version><packaging>pom</packaging>"
                            + "<dependencies><dependency><groupId>org.test</groupId><artifactId>plugin-impl</artifactId><version>1.0</version></dependency></dependencies></project>");
        }
        write("settings.gradle", """
                pluginManagement {
                    repositories { maven { url = uri('repo') } }
                    plugins { id 'org.test.managed' version '1.0' }
                }
                plugins { id 'com.sneedit.deplens.settings' }
                rootProject.name = 'plugin-inventory'
                include 'sub'
                """);
        write("gradle/libs.versions.toml", """
                [versions]
                common = "1.0"
                [plugins]
                sample = { id = "org.test.plugin", version.ref = "common" }
                """);
        write("build.gradle", """
                plugins {
                    alias(libs.plugins.sample) apply false
                    id 'org.test.managed' apply false
                }
                """);
        write("sub/build.gradle", "plugins { id 'org.test.plugin' }\n");
        run("depLensInventory");
        JsonObject alias = find(report(), directory, ":", "PLUGIN", "org.test.plugin");
        assertThat(strings(alias, "pluginStates")).contains("DECLARED_APPLY_FALSE", "EXTERNAL");
        assertThat(alias.getAsJsonArray("versionSources").toString()).contains("versions.common");
        assertThat(alias.getAsJsonArray("resolutions").toString()).contains("1.0");
        JsonObject managed = find(report(), directory, ":", "PLUGIN", "org.test.managed");
        assertThat(strings(managed, "pluginStates")).contains("VERSION_FROM_PLUGIN_MANAGEMENT", "DECLARED_APPLY_FALSE");
        assertThat(strings(managed, "declaredSelectors")).containsExactly("1.0");
        assertThat(managed.getAsJsonArray("versionSources").toString()).contains("settings.gradle");
        assertThat(managed.getAsJsonArray("resolutions").toString()).contains("1.0");
        assertThat(strings(find(report(), directory, "<settings>", "PLUGIN", "org.test.managed"), "pluginStates")).contains("PLUGIN_MANAGEMENT");
        JsonObject inherited = find(report(), directory, ":sub", "PLUGIN", "org.test.plugin");
        assertThat(strings(inherited, "pluginStates")).contains("APPLIED", "VERSION_FROM_ROOT_CLASSPATH");
        assertThat(inherited.getAsJsonArray("resolutions").toString()).contains("1.0");
    }

    /**
     * Verifies that DepLens attributes transitive platforms from Gradle metadata without exposing transitive modules.
     */
    @Test
    void attributesTransitivePlatformsFromGradleMetadataWithoutExposingTransitiveModules() throws Exception {
        module("managed", "1.0", "");
        module("bom", "1.0", "<dependencyManagement><dependencies><dependency><groupId>org.test</groupId><artifactId>managed</artifactId><version>1.0</version></dependency></dependencies></dependencyManagement>");
        module("carrier", "1.0", "<!-- do_not_remove: published-with-gradle-metadata -->");
        write("repo/org/test/carrier/1.0/carrier-1.0.module", """
                {
                  "formatVersion": "1.1",
                  "component": { "group": "org.test", "module": "carrier", "version": "1.0" },
                  "variants": [
                    {
                      "name": "apiElements",
                      "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-api" },
                      "dependencies": [{ "group": "org.test", "module": "bom", "version": { "requires": "1.0" },
                                         "attributes": { "org.gradle.category": "platform" }, "endorseStrictVersions": true }]
                    },
                    {
                      "name": "runtimeElements",
                      "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-runtime" },
                      "dependencies": [{ "group": "org.test", "module": "bom", "version": { "requires": "1.0" },
                                         "attributes": { "org.gradle.category": "platform" }, "endorseStrictVersions": true }]
                    }
                  ]
                }
                """);
        write("settings.gradle", "rootProject.name = 'transitive-platform'\n");
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                repositories { maven { url = uri('repo') } }
                dependencies {
                    implementation 'org.test:carrier:1.0'
                    implementation 'org.test:managed'
                }
                """);
        run("depLensInventory");
        JsonObject managed = find(report(), directory, ":", "DEPENDENCY", "org.test:managed");
        assertThat(strings(managed, "platforms")).withFailMessage("%s", report()).contains("org.test:bom");
        assertThat(managed.getAsJsonArray("versionSources").toString()).contains("PLATFORM");
        assertThat(report().getAsJsonArray("entries")).hasSize(3); // Two declared modules and Gradle.
    }

    /**
     * Verifies that transitive resolution failures make the inventory incomplete.
     */
    @Test
    void transitiveResolutionFailuresMakeTheInventoryIncomplete() throws Exception {
        module("carrier", "1.0", "<dependencies><dependency><groupId>org.test</groupId><artifactId>transitive-missing</artifactId><version>1.0</version></dependency></dependencies>");
        write("settings.gradle", "rootProject.name = 'transitive-failure'\n");
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                repositories { maven { url = uri('repo') } }
                dependencies { implementation 'org.test:carrier:1.0' }
                """);
        run("depLensInventory");
        assertThat(report().get("complete").getAsBoolean()).isFalse();
        assertThat(strings(report(), "warnings")).anyMatch(message -> message.contains("dependency graph is incomplete"));
        assertThat(report().getAsJsonArray("entries").toString()).doesNotContain("org.test:transitive-missing");
    }

    /**
     * Runs the requested task against the fixture project.
     */
    private BuildResult run(String task) {
        return GradleRunner.create().withGradleVersion(System.getProperty("deplens.test.gradleVersion")).withProjectDir(directory.toFile()).withPluginClasspath()
                .withArguments(task, "--offline", "--stacktrace").build();
    }

    /**
     * Reads the JSON report written by the fixture build.
     */
    private JsonObject report() throws IOException {
        return JsonParser.parseString(Files.readString(directory.resolve("build/reports/deplens/inventory.json"))).getAsJsonObject();
    }

    /**
     * Finds the expected inventory entry by its full component context.
     */
    private static JsonObject find(JsonObject report, Path build, String project, String type, String coordinate) {
        try {
            build = build.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (JsonElement element : report.getAsJsonArray("entries")) {
            JsonObject row = element.getAsJsonObject();
            if (row.get("build").getAsString().equals(build.toString()) && row.get("project").getAsString().equals(project)
                    && row.get("type").getAsString().equals(type) && row.get("coordinate").getAsString().equals(coordinate))
                return row;
        }
        throw new AssertionError("Missing entry: " + build + " " + project + " " + type + " " + coordinate);
    }

    /**
     * Reads a JSON string array into assertion-friendly values.
     */
    private static List<String> strings(JsonObject object, String name) {
        List<String> values = new ArrayList<>();
        object.getAsJsonArray(name).forEach(item -> values.add(item.getAsString()));
        return values;
    }

    /**
     * Publishes a module fixture into the local test repository.
     */
    private void module(String name, String version, String content) throws IOException {
        module("org.test", name, version, content);
    }

    /**
     * Publishes a module fixture into the local test repository.
     */
    private void module(String group, String name, String version, String content) throws IOException {
        String path = "repo/" + group.replace('.', '/') + "/" + name + "/" + version + "/" + name + "-" + version;
        write(path + ".pom", "<project><modelVersion>4.0.0</modelVersion><groupId>" + group + "</groupId><artifactId>" + name + "</artifactId><version>" + version + "</version>"
                + (name.equals("bom") ? "<packaging>pom</packaging>" : "") + content + "</project>");
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(directory.resolve(path + ".jar")))) {
        }
    }

    /**
     * Writes fixture text under the temporary project directory.
     */
    private void write(String name, String content) throws IOException {
        Path file = directory.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
