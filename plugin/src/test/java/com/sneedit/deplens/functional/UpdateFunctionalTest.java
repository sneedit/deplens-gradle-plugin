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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static com.sneedit.deplens.functional.ConsoleOutput.plain;

/**
 * Tests update functional.
 */
class UpdateFunctionalTest {
    @TempDir
    Path dir;

    /**
     * Creates a fresh project fixture for each test.
     */
    @BeforeEach
    void setup() throws Exception {
        write("settings.gradle", "rootProject.name = 'updates'\n");
    }

    /** Scope selection skips resolution and queries, preserving inherited declarations and constraints. */
    @Test
    void checkResolvableConfigurationFilterSkipsExcludedGraphsAcrossProjectsAndBuildsWithCache() throws Exception {
        modules("org.test", "lib", "1.0.0", "1.1.0", "2.0.0");
        modules("org.test", "tool", "1.0.0", "2.0.0");
        write("settings.gradle", "rootProject.name = 'filter-scope'\ninclude 'app'\nincludeBuild 'other'\n");
        write("app/build.gradle", "plugins { id 'com.sneedit.deplens' }\n");
        write("other/settings.gradle", "rootProject.name = 'other'\n");
        String fixture = """
                configurations {
                    shared { canBeResolved = false; canBeConsumed = false }
                    checked { canBeResolved = true; canBeConsumed = false; extendsFrom shared }
                    ignored { canBeResolved = true; canBeConsumed = false }
                }
                repositories { maven { url = uri('%s') } }
                dependencies {
                    shared 'org.test:lib:1.0.0'
                    constraints { shared('org.test:lib') { version { strictly '[1.0,2.0[' } } }
                    checked 'org.test:tool:1.0.0'
                    ignored 'org.missing:never-query:1.+'
                }
                configurations.ignored.incoming.beforeResolve { throw new GradleException('EXCLUDED GRAPH WAS RESOLVED') }
                """.formatted(dir.resolve("repo").toUri());
        write("build.gradle", """
                plugins { id 'com.sneedit.deplens' }
                %s
                depLens {
                    checkResolvableConfigurationFilter { includes = ['checked|ignored']; excludes = ['ignored'] }
                    outputDeclaredConfigurationFilter { excludes = ['checked'] }
                }
                """.formatted(fixture));
        write("app/build.gradle", "plugins { id 'com.sneedit.deplens' }\n" + fixture);
        write("other/build.gradle", "plugins { id 'com.sneedit.deplens' }\n" + fixture);
        JsonObject first = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            var output = plain(run("depLensCheckDependencies", "--parallel", "--configuration-cache",
                    "--configuration-cache-problems=fail", "-Dorg.gradle.unsafe.isolated-projects=true"));
            assertThat(output).contains(attempt == 0 ? "Configuration cache entry stored" : "Reusing configuration cache");
            assertThat(output).contains("org.test:lib 1.0.0 → 1.1.0", "Output declared configuration filter: 3 entries hidden")
                    .doesNotContain("EXCLUDED GRAPH WAS RESOLVED", "never-query");
            var result = report("depLensCheckDependencies");
            assertThat(result.get("complete").getAsBoolean()).isTrue();
            assertThat(result.get("checksComplete").getAsBoolean()).isTrue();
            assertThat(result.get("hiddenEntries").getAsInt()).isEqualTo(3);
            assertThat(result.getAsJsonArray("entries").asList()).noneMatch(e -> e.getAsJsonObject().get("coordinate").getAsString().contains("never-query"));
            var libraries = result.getAsJsonArray("entries").asList().stream().map(JsonElement::getAsJsonObject)
                    .filter(e -> e.get("type").getAsString().equals("DEPENDENCY") && e.get("coordinate").getAsString().equals("org.test:lib")).toList();
            assertThat(libraries).hasSize(3);
            for (var library : libraries) {
                assertThat(library.get("recommendedVersion").getAsString()).isEqualTo("1.1.0");
                assertThat(library.getAsJsonArray("updateContexts").asList()).allMatch(c -> c.getAsJsonObject().get("configuration").getAsString().equals("checked"));
            }
            if (first == null) first = result;
            else assertThat(result).isEqualTo(first);
        }
    }

    /** Report filtering hides entries in console and JSON, without dropping peer constraints or update checks. */
    @Test
    void outputDeclaredConfigurationFilterKeepsGraphChecksAndMultipleOrigins() throws Exception {
        for (String module : List.of("target", "peer", "both", "tool")) modules("org.test", module, "1.0.0", "2.0.0");
        write("repo/org/test/target/2.0.0/target-2.0.0.pom", """
                <project><modelVersion>4.0.0</modelVersion><groupId>org.test</groupId><artifactId>target</artifactId><version>2.0.0</version>
                <dependencies><dependency><groupId>org.test</groupId><artifactId>peer</artifactId><version>2.0.0</version></dependency></dependencies></project>
                """);
        write("build.gradle", """
                plugins { id 'com.sneedit.deplens' }
                repositories { maven { url = uri('repo') } }
                configurations {
                    main { canBeResolved = false; canBeConsumed = false }
                    tooling { canBeResolved = false; canBeConsumed = false }
                    checked { canBeResolved = true; canBeConsumed = false; extendsFrom main, tooling }
                }
                dependencies {
                    main 'org.test:target:1.0.0'
                    main 'org.test:both:1.0.0'
                    tooling 'org.test:both:1.0.0'
                    tooling 'org.test:peer:1.0.0'
                    tooling 'org.test:tool:1.0.0'
                    constraints {
                        tooling('org.test:peer') { version { strictly '1.0.0' } }
                        tooling('org.test:target') { version { strictly '1.0.0' } }
                    }
                }
                depLens { outputDeclaredConfigurationFilter { excludes = ['tooling'] } }
                """);
        var inventoryOutput = plain(run("depLensInventory"));
        assertThat(inventoryOutput).contains("Output declared configuration filter: 4 entries hidden");
        var inventory = report("inventory");
        assertThat(inventory.get("complete").getAsBoolean()).isTrue();
        assertThat(inventory.get("hiddenEntries").getAsInt()).isEqualTo(4);
        for (int attempt = 0; attempt < 2; attempt++) {
            var output = plain(run("depLensCheckDependencies", "--configuration-cache", "--configuration-cache-problems=fail"));
            assertThat(output).contains("org.test:both 1.0.0 → 2.0.0", "Output declared configuration filter: 4 entries hidden")
                    .doesNotContain("org.test:tool 1.0.0 → 2.0.0");
            var result = report("depLensCheckDependencies");
            assertThat(result.get("checksComplete").getAsBoolean()).isTrue();
            assertThat(result.get("hiddenEntries").getAsInt()).isEqualTo(4);
            assertThat(result.getAsJsonArray("entries").asList()).noneMatch(e -> List.of("org.test:peer", "org.test:tool").contains(e.getAsJsonObject().get("coordinate").getAsString()));
            var target = entry(result, "org.test:target", ":").getAsJsonArray("updateContexts").get(0).getAsJsonObject();
            assertThat(target.get("latestAvailable").getAsString()).isEqualTo("2.0.0");
            assertThat(target.get("latestAllowedByBuild").getAsString()).isEqualTo("1.0.0");
        }
        // A report-only filter cannot turn off the configured exit policy for hidden updates.
        Files.writeString(dir.resolve("build.gradle"), "\ndepLens { dependencies { failIfUpdatesFound = true }; outputDeclaredConfigurationFilter { includes = ['nothing'] } }\n", StandardOpenOption.APPEND);
        var failed = GradleRunner.create().withGradleVersion(System.getProperty("deplens.test.gradleVersion"))
                .withProjectDir(dir.toFile()).withPluginClasspath().withArguments("depLensCheckDependencies", "--offline").buildAndFail();
        assertThat(plain(failed)).contains("Updates available. Report written before failing as configured.");
        assertThat(report("depLensCheckDependencies").getAsJsonArray("entries").asList())
                .noneMatch(e -> e.getAsJsonObject().get("type").getAsString().equals("DEPENDENCY"));
    }

    /** Compact presentation hides project rows while preserving warnings, updates and every JSON context. */
    @Test
    void compactConsoleModeSupportsDslCliAndConfigurationCache() throws Exception {
        modules("org.test", "lib", "1.0.0", "2.0.0");
        modules("org.test", "warning", "1.0.0", "2.0.0");
        write("settings.gradle", "rootProject.name = 'console-mode'\ninclude 'app'\n");
        write("app/build.gradle", "");
        String build = """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                allprojects {
                    apply plugin: 'java'
                    repositories { maven { url = rootProject.uri('repo') } }
                    dependencies {
                        implementation 'org.test:lib:1.0.0'
                        implementation 'org.test:warning:2.0.0'
                    }
                }
                depLens { dependencies { ignoredVersions = ['org.test:warning': ['2.0.0']] } }
                """;
        write("build.gradle", build);
        var compact = plain(run("depLensCheckDependencies"));
        assertThat(compact).contains("org.test:lib 1.0.0 → 2.0.0", "Version selection warnings:", "org.test:warning 2.0.0 > 1.0.0", "DepLens:")
                .doesNotContain("Projects:");
        var original = report("depLensCheckDependencies");

        write("build.gradle", build + "\ndepLens { consoleMode = 'standard' }\n");
        assertThat(plain(run("depLensCheckDependencies"))).contains("Projects: :, :app");
        assertThat(report("depLensCheckDependencies")).isEqualTo(original);
        assertThat(plain(run("depLensCheckDependencies", "--console-mode", "compact"))).doesNotContain("Projects:");
        assertThat(report("depLensCheckDependencies")).isEqualTo(original);

        // The CLI option must override the default, including on a cache hit.
        write("build.gradle", build);
        JsonObject first = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            var output = plain(GradleRunner.create().withGradleVersion(System.getProperty("deplens.test.gradleVersion"))
                    .withProjectDir(dir.toFile()).withPluginClasspath()
                    .withArguments("depLensCheck", "--console-mode", "standard", "--offline",
                            "--configuration-cache", "--configuration-cache-problems=fail").build());
            assertThat(output).contains("org.test:lib 1.0.0 → 2.0.0", "Version selection warnings:").contains("Projects: :, :app");
            assertThat(output).contains(attempt == 0 ? "Configuration cache entry stored" : "Reusing configuration cache");
            var report = report("depLensCheck");
            if (first == null) first = report;
            else assertThat(report).isEqualTo(first);
            for (String project : List.of(":", ":app")) {
                assertThat(entry(report, "org.test:lib", project).getAsJsonArray("updateContexts")).isNotEmpty();
            }
        }
    }

    /** Unsupported presentation modes fail with the accepted values. */
    @Test
    void invalidConsoleModeFailsClearly() throws Exception {
        write("build.gradle", "plugins { id 'com.sneedit.deplens' }\n");
        var failed = GradleRunner.create().withGradleVersion(System.getProperty("deplens.test.gradleVersion"))
                .withProjectDir(dir.toFile()).withPluginClasspath()
                .withArguments("depLensCheck", "--console-mode", "dense", "--offline").buildAndFail();
        assertThat(plain(failed)).contains("consoleMode must be standard or compact");
    }

    /** Exclusions apply independently to dependencies, plugins, BOMs and wrapper releases across builds. */
    @Test
    void independentExclusionsSurviveIncludedBuildsAndConfigurationCache() throws Exception {
        String id = "org.test.exclusions";
        List<String> versions = List.of("1.0.0", "2.0.0-beta1", "3.0.0-redhat-00001", "4.0.0");
        modules(id, id + ".gradle.plugin", versions.toArray(String[]::new));
        modules("org.test", "exclusion-implementation", "1.0.0");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(dir.resolve("repo/org/test/exclusion-implementation/1.0.0/exclusion-implementation-1.0.0.jar")))) {
            out.putNextEntry(new JarEntry("META-INF/gradle-plugins/" + id + ".properties"));
            out.write("implementation-class=org.gradle.api.plugins.JavaPlugin\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        for (String v : versions) {
            write("repo/org/test/exclusions/" + id + ".gradle.plugin/" + v + "/" + id + ".gradle.plugin-" + v + ".pom",
                    "<project><modelVersion>4.0.0</modelVersion><groupId>" + id + "</groupId><artifactId>" + id + ".gradle.plugin</artifactId><version>" + v + "</version><packaging>pom</packaging><dependencies><dependency><groupId>org.test</groupId><artifactId>exclusion-implementation</artifactId><version>1.0.0</version></dependency></dependencies></project>");
        }
        modules("org.test", "lib", versions.toArray(String[]::new));
        modules("org.test", "bom", versions.toArray(String[]::new));
        for (String v : versions)
            write("repo/org/test/bom/" + v + "/bom-" + v + ".pom",
                    "<project><modelVersion>4.0.0</modelVersion><groupId>org.test</groupId><artifactId>bom</artifactId><version>" + v + "</version><packaging>pom</packaging></project>");
        write("settings.gradle", "pluginManagement { repositories { maven { url = uri('repo') } } }\nrootProject.name = 'exclusions'\ninclude 'clear'\nincludeBuild 'nested'\n");
        write("nested/settings.gradle", "rootProject.name = 'nested'\n");
        write("nested/build.gradle", "plugins { id 'java' }\nrepositories { maven { url = uri('../repo') } }\ndependencies { implementation 'org.test:lib:1.0.0' }\n");
        write("clear/build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                repositories { maven { url = uri('../repo') } }
                dependencies { implementation 'org.test:lib:1.0.0' }
                depLens { rules { dependency('org.test:lib') { ignoredVersionPatterns = [] } } }
                """);
        write("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.8-bin.zip\n");
        write("releases.json", "[{\"version\":\"8.8\"},{\"version\":\"8.9-rc-1\"},{\"version\":\"9.0\"}]");
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens'; id 'org.test.exclusions' version '1.0.0' apply false }
                repositories { maven { url = uri('repo') } }
                dependencies { implementation 'org.test:lib:1.0.0'; implementation platform('org.test:bom:1.0.0') }
                depLens {
                    gradle { versionsUrl = file('releases.json').toURI().toString() }
                    rules {
                        global {
                            includePreReleases = true
                            ignoredVersions = ['4.0.0']
                            ignoredVersionPatterns = ['(?i)-redhat', '^9[.]', '^1[.]']
                        }
                        dependency('org.test:lib') {
                            versionNormalizationPattern = '-redhat.*$'
                            versionNormalizationReplacement = ''
                        }
                    }
                }
                """);
        for (int attempt = 0; attempt < 2; attempt++) {
            var output = plain(GradleRunner.create().withGradleVersion(System.getProperty("deplens.test.gradleVersion"))
                    .withProjectDir(dir.toFile()).withPluginClasspath()
                    .withArguments("depLensCheck", "--offline", "--configuration-cache", "--configuration-cache-problems=fail", "--stacktrace").build());
            if (attempt == 1) assertThat(output).contains("Reusing configuration cache");
            var report = report("depLensCheck");
            for (String coordinate : List.of("org.test:lib", "org.test:bom", id)) {
                var e = entry(report, coordinate, ":");
                assertThat(e.get("recommendedVersion").getAsString()).as(coordinate).isEqualTo("2.0.0-beta1");
                assertThat(e.getAsJsonArray("candidates").toString()).isEqualTo("[\"2.0.0-beta1\"]");
                assertThat(e.getAsJsonArray("statuses").toString()).contains("IGNORED").doesNotContain("FAILED");
                for (JsonElement item : e.getAsJsonArray("updateContexts")) {
                    var context = item.getAsJsonObject();
                    assertThat(context.get("latestAvailable").getAsString()).isEqualTo("2.0.0-beta1");
                    assertThat(context.get("latestAllowedByBuild").getAsString()).isEqualTo("2.0.0-beta1");
                    assertThat(context.get("latestWithinSelector").getAsString()).isEqualTo("1.0.0");
                }
            }
            assertThat(entry(report, "org.test:lib", ":clear").get("recommendedVersion").getAsString()).isEqualTo("3.0.0-redhat-00001");
            var wrapper = report.getAsJsonArray("entries").asList().stream().map(JsonElement::getAsJsonObject)
                    .filter(e -> e.get("coordinate").getAsString().equals("gradle"))
                    .filter(e -> !e.getAsJsonArray("declaredSelectors").isEmpty()).findFirst().orElseThrow();
            assertThat(wrapper.get("recommendedVersion").getAsString()).isEqualTo("8.9-rc-1");
            assertThat(report.getAsJsonArray("entries").asList().stream().map(JsonElement::getAsJsonObject)
                    .filter(e -> e.get("coordinate").getAsString().equals("org.test:lib"))
                    .filter(e -> e.get("build").getAsString().endsWith("nested"))
                    .map(e -> e.get("recommendedVersion").getAsString()).toList()).containsExactly("2.0.0-beta1");
            assertThat(report.get("checksComplete").getAsBoolean()).isTrue();
        }
    }

    /** Excluding all candidates keeps the baseline resolvable and does not claim up-to-date. */
    @Test
    void ignoredVersionPatternsKeepBaselineResolvableWhenEveryVersionIsExcluded() throws Exception {
        modules("org.test", "lib", "1.0.0", "2.0.0");
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                repositories { maven { url = uri('repo') } }
                dependencies { implementation 'org.test:lib:1.0.0' }
                depLens { rules { global { ignoredVersionPatterns = ['.*'] } } }
                """);
        run("depLensCheckDependencies");
        var report = report("depLensCheckDependencies");
        var e = entry(report, "org.test:lib", ":");
        assertThat(e.getAsJsonArray("statuses").toString()).contains("IGNORED").doesNotContain("FAILED", "UP_TO_DATE", "NOT_CHECKED");
        assertThat(e.getAsJsonArray("candidates")).isEmpty();
        assertThat(e.get("recommendedVersion").isJsonNull()).isTrue();
        assertThat(e.getAsJsonArray("updateContexts").get(0).getAsJsonObject().get("latestWithinSelector").getAsString()).isEqualTo("1.0.0");
        assertThat(report.get("checksComplete").getAsBoolean()).isTrue();
    }

    /**
     * Verifies that explicit dependency and plugin patterns override detection and survive included builds.
     */
    @Test
    void explicitDependencyAndPluginPatternsOverrideDetectionAndSurviveIncludedBuilds() throws Exception {
        String id = "org.test.patterns";
        List<String> versions = List.of("1.0.0", "2.0.0", "3.0.0-rc1", "4.0.0-eap1");
        modules(id, id + ".gradle.plugin", versions.toArray(String[]::new));
        modules("org.test", "pattern-implementation", "1.0.0");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(dir.resolve("repo/org/test/pattern-implementation/1.0.0/pattern-implementation-1.0.0.jar")))) {
            out.putNextEntry(new JarEntry("META-INF/gradle-plugins/" + id + ".properties"));
            out.write("implementation-class=org.gradle.api.plugins.JavaPlugin\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        for (String v : versions)
            write("repo/org/test/patterns/" + id + ".gradle.plugin/" + v + "/" + id + ".gradle.plugin-" + v + ".pom",
                    "<project><modelVersion>4.0.0</modelVersion><groupId>" + id + "</groupId><artifactId>" + id + ".gradle.plugin</artifactId><version>" + v + "</version><packaging>pom</packaging><dependencies><dependency><groupId>org.test</groupId><artifactId>pattern-implementation</artifactId><version>1.0.0</version></dependency></dependencies></project>");
        modules("org.json", "json", "20260814", "20260815", "20260901", "20270101", "20270201-eap1", "20270301-beta1");
        write("settings.gradle", "pluginManagement { repositories { maven { url = uri('repo') } } }\nrootProject.name = 'patterns'\nincludeBuild 'nested'\n");
        write("nested/settings.gradle", "rootProject.name = 'nested'\n");
        write("nested/build.gradle", "plugins { id 'java' }\nrepositories { maven { url = uri('../repo') } }\ndependencies { implementation 'org.json:json:20260814' }\n");
        String template = """
                plugins { id 'java'; id 'com.sneedit.deplens'; id 'org.test.patterns' version '1.0.0' apply false }
                repositories { maven { url = uri('repo') } }
                dependencies { implementation 'org.json:json:20260814' }
                depLens { rules {
                    dependency('org.json:json') {
                        allowedUpdates = '%s'
                        versionPattern = /(?<major>[0-9]{4})(?<minor>[0-9]{2})(?<patch>[0-9]{2})(?:-[a-z]+[0-9]+)?/
                        preReleasePattern = /-eap[0-9]+$/
                    }
                    plugin('org.test.patterns') {
                        allowedUpdates = 'patch'
                        versionPattern = /(?<patch>[0-9]+)[.](?<major>[0-9]+)[.](?<minor>[0-9]+)(?:-[a-z]+[0-9]+)?/
                        preReleasePattern = /-eap[0-9]+$/
                    }
                } }
                """;
        Map<String, String> expected = Map.of("patch", "20260815", "minor", "20260901", "major", "20270301-beta1");
        for (String tier : List.of("patch", "minor", "major")) {
            write("build.gradle", template.formatted(tier));
            var output = plain(run("depLensCheck"));
            var report = report("depLensCheck");
            for (JsonElement item : report.getAsJsonArray("entries")) {
                var e = item.getAsJsonObject();
                if (!e.get("coordinate").getAsString().equals("org.json:json")) continue;
                assertThat(e.get("recommendedVersion").getAsString()).isEqualTo(expected.get(tier));
                assertThat(e.getAsJsonArray("candidates").toString()).doesNotContain("eap");
                for (JsonElement context : e.getAsJsonArray("updateContexts")) {
                    assertThat(context.getAsJsonObject().getAsJsonArray("unknownCompatibility")).isEmpty();
                    assertThat(context.getAsJsonObject().get("versionPattern").getAsString()).contains("?<major>");
                }
            }
            assertThat(entry(report, "org.json:json", ":").get("recommendedVersion").getAsString()).isEqualTo(expected.get(tier));
            assertThat(report.getAsJsonArray("entries").toString()).contains("nested");
            assertThat(entry(report, id, ":").get("recommendedVersion").getAsString()).isEqualTo("3.0.0-rc1");
            assertThat(output).doesNotContain("compatibility unknown");
            assertThat(report.get("checksComplete").getAsBoolean()).isTrue();
        }
        // A pattern matching the baseline but not later dates must not fall back to automatic tiers.
        write("build.gradle", template.formatted("major").replace("(?<minor>[0-9]{2})", "(?<minor>08)"));
        var output = plain(run("depLensCheck"));
        var json = entry(report("depLensCheck"), "org.json:json", ":");
        assertThat(json.getAsJsonArray("updateContexts").toString()).contains("unknownCompatibility", "20270301-beta1");
        assertThat(output).contains("compatibility unknown: version pattern mismatch");
        // includePreReleases still permits versions detected by the custom regex.
        write("build.gradle", template.formatted("major").replace("allowedUpdates = 'patch'", "allowedUpdates = 'patch'; includePreReleases = true"));
        run("depLensCheckPlugins");
        assertThat(entry(report("depLensCheckPlugins"), id, ":").get("recommendedVersion").getAsString()).isEqualTo("4.0.0-eap1");
    }

    /**
     * Verifies that SonarQube and JSON use Gradle ordering and separate unknown compatibility.
     */
    @Test
    void sonarQubeAndJsonUseGradleOrderingAndSeparateUnknownCompatibility() throws Exception {
        String id = "org.sonarqube";
        List<String> sonarVersions = List.of("7.5.0.8588", "7.5.0.9000", "7.5.1.9010", "7.6.0.9100", "8.0.0.9200");
        modules(id, id + ".gradle.plugin", sonarVersions.toArray(String[]::new));
        modules("org.test", "sonar-implementation", "1.0.0");
        Path jar = dir.resolve("repo/org/test/sonar-implementation/1.0.0/sonar-implementation-1.0.0.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/gradle-plugins/" + id + ".properties"));
            out.write("implementation-class=org.gradle.api.plugins.JavaPlugin\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        for (String v : sonarVersions)
            write("repo/org/sonarqube/" + id + ".gradle.plugin/" + v + "/" + id + ".gradle.plugin-" + v + ".pom",
                    "<project><modelVersion>4.0.0</modelVersion><groupId>" + id + "</groupId><artifactId>" + id + ".gradle.plugin</artifactId><version>" + v + "</version><packaging>pom</packaging><dependencies><dependency><groupId>org.test</groupId><artifactId>sonar-implementation</artifactId><version>1.0.0</version></dependency></dependencies></project>");
        modules("org.json", "json", "20260814", "20260901");
        write("settings.gradle", "pluginManagement { repositories { maven { url = uri('repo') } } }\nrootProject.name = 'version-schemes'\n");
        Map<String, String> targets = Map.of("patch", "7.5.1.9010", "minor", "7.6.0.9100", "major", "8.0.0.9200");
        for (String tier : List.of("patch", "minor", "major")) {
            write("build.gradle", """
                    plugins { id 'java'; id 'com.sneedit.deplens'; id 'org.sonarqube' version '7.5.0.8588' apply false }
                    repositories { maven { url = uri('repo') } }
                    dependencies { implementation 'org.json:json:20260814' }
                    depLens {
                        dependencies { allowedUpdates = '%s' }
                        plugins { allowedUpdates = '%s' }
                    }
                    """.formatted(tier, tier));
            var result = run("depLensCheck");
            JsonObject report = report("depLensCheck");
            JsonObject sonar = entry(report, id, ":");
            assertThat(sonar.get("recommendedVersion").getAsString()).isEqualTo(targets.get(tier));
            JsonObject sonarContext = sonar.getAsJsonArray("updateContexts").get(0).getAsJsonObject();
            assertThat(sonarContext.get("patch").getAsString()).isEqualTo("7.5.1.9010");
            assertThat(sonarContext.get("minor").getAsString()).isEqualTo("7.6.0.9100");
            assertThat(sonarContext.get("major").getAsString()).isEqualTo("8.0.0.9200");
            assertThat(sonarContext.getAsJsonArray("unknownCompatibility")).isEmpty();
            JsonObject json = entry(report, "org.json:json", ":");
            JsonObject jsonContext = json.getAsJsonArray("updateContexts").get(0).getAsJsonObject();
            assertThat(jsonContext.getAsJsonArray("unknownCompatibility").toString()).isEqualTo("[\"20260901\"]");
            for (String field : List.of("patch", "minor", "major"))
                assertThat(jsonContext.get(field).isJsonNull()).isTrue();
            if (tier.equals("major")) {
                assertThat(json.get("recommendedVersion").getAsString()).isEqualTo("20260901");
                assertThat(plain(result)).contains("org.json:json 20260814 → 20260901 (compatibility unknown: date version)");
            } else {
                assertThat(json.getAsJsonArray("candidates")).isEmpty();
                assertThat(plain(result)).doesNotContain("org.json:json 20260814 →");
            }
            assertThat(report.get("checksComplete").getAsBoolean()).isTrue();
        }
        var info = GradleRunner.create().withGradleVersion(System.getProperty("deplens.test.gradleVersion"))
                .withProjectDir(dir.toFile()).withPluginClasspath().withArguments("depLensCheck", "--offline", "--info").build();
        assertThat(plain(info)).contains("compatibility unknown (date versions): [20260901]");
    }

    /**
     * Verifies that section update tiers filter recommendations and specific rules override them.
     */
    @Test
    void sectionUpdateTiersFilterRecommendationsAndSpecificRulesOverrideThem() throws Exception {
        modules("org.test", "lib", "1.0.0", "1.0.1", "1.2.0", "2.0.0");
        modules("org.test", "override", "1.0.0", "1.0.1", "1.2.0", "2.0.0");
        Map<String, String> targets = Map.of("patch", "1.0.1", "minor", "1.2.0", "major", "2.0.0");
        for (String tier : List.of("patch", "minor", "major")) {
            write("build.gradle", """
                    plugins { id 'java'; id 'com.sneedit.deplens' }
                    repositories { maven { url = uri('repo') } }
                    dependencies {
                        implementation 'org.test:lib:1.0.0'
                        implementation 'org.test:override:1.0.0'
                    }
                    depLens {
                        dependencies { allowedUpdates = '%s' }
                        rules { dependency('org.test:override') { allowedUpdates = 'minor' } }
                    }
                    """.formatted(tier));
            run("depLensCheckDependencies");
            JsonObject result = report("depLensCheckDependencies");
            assertThat(entry(result, "org.test:lib", ":").get("recommendedVersion").getAsString()).isEqualTo(targets.get(tier));
            assertThat(entry(result, "org.test:override", ":").get("recommendedVersion").getAsString()).isEqualTo("1.2.0");
        }
    }

    /**
     * Verifies that tiers ignores pins and dynamic selectors use actual resolved baseline.
     */
    @Test
    void tiersIgnoresPinsAndDynamicSelectorsUseActualResolvedBaseline() throws Exception {
        for (String module : List.of("lib", "dynamic", "range", "latest", "pin"))
            modules("org.test", module, "1.0.0", "1.0.1", "1.2.0", "2.0.0", "3.0.0-rc1");
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                repositories { maven { url = uri('repo') } }
                dependencies {
                    implementation 'org.test:lib:1.0.0'
                    implementation 'org.test:dynamic:1.+'
                    implementation 'org.test:range:[1.0,2.0)'
                    implementation 'org.test:latest:latest.release'
                    implementation 'org.test:pin:1.0.0'
                }
                depLens {
                    rules {
                        global { includePreReleases = false }
                        dependency('org.test:lib') { ignoredVersions = ['2.0.0'] }
                        dependency('org.test:pin') { pinned = true }
                    }
                }
                """);
        BuildResult result = run("depLensCheckDependencies");
        JsonObject lib = entry(report("depLensCheckDependencies"), "org.test:lib", ":");
        assertThat(lib.getAsJsonArray("candidates").toString()).contains("1.2.0", "1.0.1").doesNotContain("2.0.0", "rc1");
        assertThat(lib.get("recommendedVersion").getAsString()).isEqualTo("1.2.0");
        JsonObject context = lib.getAsJsonArray("updateContexts").get(0).getAsJsonObject();
        assertThat(context.get("patch").getAsString()).isEqualTo("1.0.1");
        assertThat(context.get("minor").getAsString()).isEqualTo("1.2.0");
        for (String name : List.of("dynamic", "range")) {
            JsonObject row = entry(report("depLensCheckDependencies"), "org.test:" + name, ":");
            assertThat(row.getAsJsonArray("candidates").toString()).contains("2.0.0");
            assertThat(row.get("recommendedVersion").isJsonNull()).isTrue();
            assertThat(row.getAsJsonArray("updateContexts").toString()).contains("1.2.0", "outsideSelector", "2.0.0");
        }
        JsonObject pin = entry(report("depLensCheckDependencies"), "org.test:pin", ":");
        assertThat(pin.getAsJsonArray("statuses").toString()).contains("PINNED");
        assertThat(pin.getAsJsonArray("candidates")).isEmpty();
        assertThat(plain(result)).doesNotContain("DEPENDENCY org.test:pin");
        assertThat(report("depLensCheckDependencies").get("checksComplete").getAsBoolean()).isTrue();
    }

    /**
     * Verifies that rule precedence is per field and project contexts remain distinct.
     */
    @Test
    void rulePrecedenceIsPerFieldAndProjectContextsRemainDistinct() throws Exception {
        modules("org.test", "lib", "1.0.0", "1.0.1", "1.1.0", "2.0.0");
        write("settings.gradle", "rootProject.name = 'rules'\ninclude 'sub'\n");
        write("sub/build.gradle", "");
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                allprojects {
                    apply plugin: 'java'
                    repositories { maven { url = rootProject.uri('repo') } }
                    dependencies { implementation 'org.test:lib:1.0.0' }
                }
                depLens { rules {
                    global { allowedUpdates = 'patch'; pinned = true }
                    project(':sub') { allowedUpdates = 'minor' }
                    group('org.test') { pinned = false }
                    dependency('org.test:lib') { ignoredVersions = ['1.1.0'] }
                } }
                """);
        run("depLensCheckDependencies");
        JsonObject report = report("depLensCheckDependencies");
        for (String path : List.of(":", ":sub")) {
            JsonObject lib = entry(report, "org.test:lib", path);
            assertThat(lib.getAsJsonArray("candidates").toString()).isEqualTo("[\"1.0.1\"]");
            assertThat(lib.get("appliedRule").getAsString()).contains("global", "group", "dependency");
        }
        assertThat(entry(report, "org.test:lib", ":sub").get("appliedRule").getAsString()).contains("project::sub");
    }

    /**
     * Verifies that identical updates are grouped without losing project contexts or distinct recommendations.
     */
    @Test
    void identicalUpdatesAreGroupedWithoutLosingProjectContextsOrDistinctRecommendations() throws Exception {
        modules("org.test", "lib", "0.9.0", "1.0.0", "1.1.0", "2.0.0");
        write("settings.gradle", "rootProject.name = 'compact'\ninclude 'first', 'second', 'old', 'restricted'\nincludeBuild 'build-logic'\n");
        for (String name : List.of("first", "second", "old", "restricted")) write(name + "/build.gradle", "");
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                allprojects {
                    apply plugin: 'java'
                    repositories { maven { url = rootProject.uri('repo') } }
                    dependencies { implementation "org.test:lib:${project.name == 'old' ? '0.9.0' : '1.0.0'}" }
                }
                depLens { rules { project(':restricted') { allowedUpdates = 'minor' } } }
                """);
        write("build-logic/settings.gradle", "rootProject.name = 'logic'\n");
        write("build-logic/build.gradle", """
                plugins { id 'java' }
                repositories { maven { url = uri('../repo') } }
                dependencies { implementation 'org.test:lib:1.0.0' }
                """);
        String output = plain(run("depLensCheckDependencies", "--console-mode", "standard"));
        assertThat(output.lines().filter(line -> line.startsWith("  • org.test:lib ")).toList()).hasSize(4);
        assertThat(output).contains("• org.test:lib 1.0.0 → 2.0.0",
                "• org.test:lib 0.9.0 → 2.0.0", "Projects: :old",
                "• org.test:lib 1.0.0 → 1.1.0", "Projects: :restricted",
                "Projects: :, :first, :second", "Build: build-logic",
                "DepLens: 3 recommended updates, 0 available only");
        JsonObject report = report("depLensCheckDependencies");
        long contexts = java.util.stream.StreamSupport.stream(report.getAsJsonArray("entries").spliterator(), false)
                .map(JsonElement::getAsJsonObject).filter(e -> e.get("coordinate").getAsString().equals("org.test:lib")).count();
        assertThat(contexts).isEqualTo(6);
        for (String project : List.of(":", ":first", ":second", ":old", ":restricted")) {
            JsonObject consumer = entry(report, "org.test:lib", project);
            assertThat(consumer.getAsJsonArray("resolutions")).isNotEmpty();
            assertThat(consumer.getAsJsonArray("updateContexts")).isNotEmpty();
        }
        String info = plain(GradleRunner.create().withGradleVersion(System.getProperty("deplens.test.gradleVersion"))
                .withProjectDir(dir.toFile()).withPluginClasspath()
                .withArguments("depLensCheckDependencies", "--offline", "--info", "--stacktrace").build());
        for (String project : List.of(":first", ":second", ":old", ":restricted"))
            assertThat(info).contains(project + " DEPENDENCY org.test:lib");
    }

    /**
     * Verifies that newer release requiring higher JVM keeps latest visible and recommends compatible version.
     */
    @Test
    void newerReleaseRequiringHigherJvmKeepsLatestVisibleAndRecommendsCompatibleVersion() throws Exception {
        modules("org.test", "jvm-lib", "1.0.0", "1.1.0", "2.0.0");
        for (String version : List.of("1.0.0", "1.1.0", "2.0.0")) {
            int jvm = version.startsWith("2") ? 17 : 11;
            write("repo/org/test/jvm-lib/" + version + "/jvm-lib-" + version + ".module", """
                    {"formatVersion":"1.1","component":{"group":"org.test","module":"jvm-lib","version":"%s"},
                     "variants":[
                      {"name":"apiElements","attributes":{"org.gradle.usage":"java-api","org.gradle.category":"library","org.gradle.libraryelements":"jar","org.gradle.jvm.version":%d}},
                      {"name":"runtimeElements","attributes":{"org.gradle.usage":"java-runtime","org.gradle.category":"library","org.gradle.libraryelements":"jar","org.gradle.jvm.version":%d}}
                     ]}
                    """.formatted(version, jvm, jvm));
        }
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                repositories { maven { url = uri('repo'); metadataSources { gradleMetadata() } } }
                java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
                dependencies { implementation 'org.test:jvm-lib:1.0.0' }
                """);
        run("depLensCheckDependencies");
        JsonObject report = report("depLensCheckDependencies");
        JsonObject lib = entry(report, "org.test:jvm-lib", ":");
        assertThat(lib.get("recommendedVersion").getAsString()).isEqualTo("1.1.0");
        assertThat(lib.getAsJsonArray("statuses").toString()).contains("CONSTRAINED", "UPDATE_AVAILABLE").doesNotContain("FAILED", "NOT_CHECKED");
        for (JsonElement context : lib.getAsJsonArray("updateContexts")) {
            assertThat(context.getAsJsonObject().get("latestAvailable").getAsString()).isEqualTo("2.0.0");
            assertThat(context.getAsJsonObject().get("latestAllowedByBuild").getAsString()).isEqualTo("1.1.0");
        }
        assertThat(report.get("checksComplete").getAsBoolean()).withFailMessage("%s", report).isTrue();
    }

    /** A plugin update's transitive requirements may conflict with the existing classpath constraints. */
    @Test
    void pluginUpdateFallsBackAfterTransitiveConstraintConflict() throws Exception {
        constrainedPluginFixture();
        for (int attempt = 0; attempt < 2; attempt++) {
            var output = plain(GradleRunner.create().withGradleVersion(System.getProperty("deplens.test.gradleVersion"))
                    .withProjectDir(dir.toFile()).withPluginClasspath()
                    .withArguments("depLensCheckPlugins", "--offline", "--configuration-cache", "--configuration-cache-problems=fail").build());
            if (attempt == 1) assertThat(output).contains("Reusing configuration cache");
            var report = report("depLensCheckPlugins");
            var plugin = entry(report, "org.test.constrained", ":");
            assertThat(plugin.get("recommendedVersion").getAsString()).withFailMessage("%s", plugin).isEqualTo("1.1.0");
            assertThat(plugin.getAsJsonArray("statuses").toString()).contains("CONSTRAINED", "UPDATE_AVAILABLE")
                    .doesNotContain("FAILED", "NOT_CHECKED");
            assertThat(plugin.getAsJsonArray("resolutionFailures")).isEmpty();
            for (var item : plugin.getAsJsonArray("updateContexts")) {
                var context = item.getAsJsonObject();
                assertThat(context.get("latestAvailable").getAsString()).isEqualTo("2.0.0");
                assertThat(context.get("latestAllowedByBuild").getAsString()).isEqualTo("1.1.0");
            }
            assertThat(report.get("checksComplete").getAsBoolean()).isTrue();
        }
    }

    /** If every newer candidate conflicts, the installed version remains the build-compatible baseline. */
    @Test
    void transitiveConstraintConflictCanLeaveOnlyTheInstalledPluginCompatible() throws Exception {
        constrainedPluginFixture();
        write("repo/org/test/implementation/1.1.0/implementation-1.1.0.pom",
                Files.readString(dir.resolve("repo/org/test/implementation/2.0.0/implementation-2.0.0.pom"))
                        .replace("<artifactId>implementation</artifactId><version>2.0.0</version>",
                                "<artifactId>implementation</artifactId><version>1.1.0</version>"));
        var output = plain(GradleRunner.create().withGradleVersion(System.getProperty("deplens.test.gradleVersion"))
                .withProjectDir(dir.toFile()).withPluginClasspath()
                .withArguments("depLensCheckPlugins", "--offline", "--info").build());
        var report = report("depLensCheckPlugins");
        var plugin = entry(report, "org.test.constrained", ":");
        assertThat(plugin.get("recommendedVersion").isJsonNull()).isTrue();
        assertThat(plugin.getAsJsonArray("statuses").toString()).withFailMessage("%s", output).contains("CONSTRAINED", "UPDATE_AVAILABLE")
                .doesNotContain("FAILED", "NOT_CHECKED");
        assertThat(plugin.getAsJsonArray("updateContexts").asList()).isNotEmpty().allSatisfy(item ->
                assertThat(item.getAsJsonObject().get("latestAllowedByBuild").getAsString()).isEqualTo("1.0.0"));
        assertThat(report.get("checksComplete").getAsBoolean()).isTrue();
    }

    /** Missing metadata must remain a failure rather than being concealed by an older candidate. */
    @Test
    void missingTransitiveModuleStillFailsPluginUpdateCheck() throws Exception {
        constrainedPluginFixture();
        write("repo/org/test/implementation/2.0.0/implementation-2.0.0.pom",
                Files.readString(dir.resolve("repo/org/test/implementation/2.0.0/implementation-2.0.0.pom"))
                        .replace("<artifactId>annotations</artifactId>", "<artifactId>missing</artifactId>"));
        run("depLensCheckPlugins");
        var report = report("depLensCheckPlugins");
        var plugin = entry(report, "org.test.constrained", ":");
        assertThat(plugin.getAsJsonArray("statuses").toString()).contains("FAILED");
        assertThat(plugin.get("recommendedVersion").isJsonNull()).isTrue();
        assertThat(plugin.getAsJsonArray("resolutionFailures").toString()).contains("org.test:missing:2.0.0", "module/version not found");
        assertThat(report.get("checksComplete").getAsBoolean()).isFalse();
    }

    /** Publishes a plugin whose newest implementation requires a version rejected by the consumer. */
    private void constrainedPluginFixture() throws Exception {
        String id = "org.test.constrained";
        List<String> versions = List.of("1.0.0", "1.1.0", "2.0.0");
        modules("org.test", "annotations", "1.0.0", "2.0.0");
        modules("org.test", "peer", "1.0.0");
        write("repo/org/test/peer/1.0.0/peer-1.0.0.pom",
                Files.readString(dir.resolve("repo/org/test/peer/1.0.0/peer-1.0.0.pom"))
                        .replace("</project>", "<!-- do_not_remove: published-with-gradle-metadata --></project>"));
        write("repo/org/test/peer/1.0.0/peer-1.0.0.module", """
                {"formatVersion":"1.1","component":{"group":"org.test","module":"peer","version":"1.0.0"},
                 "variants":[{"name":"runtime","attributes":{"org.gradle.usage":"java-runtime","org.gradle.category":"library","org.gradle.libraryelements":"jar"},
                    "dependencies":[{"group":"org.test","module":"annotations","version":{"requires":"1.0.0","strictly":"1.0.0"}}],
                    "files":[{"name":"peer-1.0.0.jar","url":"peer-1.0.0.jar"}]}]}
                """);
        modules(id, id + ".gradle.plugin", versions.toArray(String[]::new));
        modules("org.test", "implementation", versions.toArray(String[]::new));
        for (String v : versions) {
            try (var jar = new JarOutputStream(Files.newOutputStream(dir.resolve("repo/org/test/implementation/" + v + "/implementation-" + v + ".jar")))) {
                jar.putNextEntry(new JarEntry("META-INF/gradle-plugins/" + id + ".properties"));
                jar.write("implementation-class=org.gradle.api.plugins.JavaPlugin\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                jar.closeEntry();
            }
            write("repo/org/test/constrained/" + id + ".gradle.plugin/" + v + "/" + id + ".gradle.plugin-" + v + ".pom",
                    "<project><modelVersion>4.0.0</modelVersion><groupId>" + id + "</groupId><artifactId>" + id + ".gradle.plugin</artifactId><version>" + v + "</version><packaging>pom</packaging><dependencies><dependency><groupId>org.test</groupId><artifactId>implementation</artifactId><version>" + v + "</version></dependency></dependencies></project>");
            String annotations = v.equals("2.0.0") ? "2.0.0" : "1.0.0";
            write("repo/org/test/implementation/" + v + "/implementation-" + v + ".pom",
                    "<project><modelVersion>4.0.0</modelVersion><groupId>org.test</groupId><artifactId>implementation</artifactId><version>" + v + "</version><dependencies><dependency><groupId>org.test</groupId><artifactId>annotations</artifactId><version>" + annotations + "</version></dependency></dependencies></project>");
        }
        write("settings.gradle", "pluginManagement { repositories { maven { url = uri('repo') } } }\nrootProject.name = 'constrained-plugin'\n");
        write("build.gradle", """
                buildscript {
                    repositories { maven { url = uri('repo') } }
                    dependencies { classpath 'org.test:peer:1.0.0' }
                }
                plugins { id 'com.sneedit.deplens'; id 'org.test.constrained' version '1.0.0' apply false }
                """);
    }

    /**
     * Verifies that subproject models are captured under their own locks with isolation and parallel execution.
     */
    @Test
    void subprojectModelsAreCapturedUnderTheirOwnLocksWithIsolationAndParallelExecution() throws Exception {
        modules("org.test", "lib", "1.0.0", "1.1.0");
        write("settings.gradle", "rootProject.name = 'project-locks'\ninclude 'first', 'second'\n");
        write("gradle.properties", "org.gradle.parallel=true\n");
        write("build.gradle", "plugins { id 'com.sneedit.deplens' }\n");
        for (String name : List.of("first", "second"))
            write(name + "/build.gradle", """
                    plugins { id 'java' }
                    repositories { maven { url = uri('../repo') } }
                    dependencies { implementation 'org.test:lib:1.0.0' }
                    configurations.configureEach {
                        incoming.beforeResolve {
                            assert ((org.gradle.api.internal.project.ProjectInternal) project).owner.hasMutableState() :
                                'Inventory must be captured under the owning project model lock'
                        }
                    }
                    """);
        String isolation = org.gradle.util.GradleVersion.version(System.getProperty("deplens.test.gradleVersion"))
                .compareTo(org.gradle.util.GradleVersion.version("9.7")) >= 0
                ? "-Dorg.gradle.isolated-projects=true" : "-Dorg.gradle.unsafe.isolated-projects=true";
        var runner = GradleRunner.create().withGradleVersion(System.getProperty("deplens.test.gradleVersion"))
                .withProjectDir(dir.toFile()).withPluginClasspath().withArguments("depLensCheckDependencies", "--offline",
                        "--parallel", "--configuration-cache", "--configuration-cache-problems=fail", isolation, "--stacktrace");
        BuildResult result = runner.build();
        assertThat(plain(result)).contains("Configuration cache entry stored");
        assertThat(plain(runner.build())).contains("Reusing configuration cache");
        JsonObject report = report("depLensCheckDependencies");
        assertThat(report.get("checksComplete").getAsBoolean()).withFailMessage("%s", report).isTrue();
        for (String name : List.of("first", "second")) {
            JsonObject lib = entry(report, "org.test:lib", ":" + name);
            assertThat(lib.get("recommendedVersion").getAsString()).isEqualTo("1.1.0");
            assertThat(lib.getAsJsonArray("statuses").toString()).doesNotContain("FAILED", "NOT_CHECKED");
        }
        assertThat(plain(result).split("DepLens:", -1)).hasSize(2);
    }

    /**
     * Verifies that enforced platform managed dependencies are not independent update targets.
     */
    @Test
    void enforcedPlatformManagedDependenciesAreNotIndependentUpdateTargets() throws Exception {
        modules("org.test", "managed", "1.0.0", "1.0.1", "2.0.0");
        modules("org.test", "bom", "1.0.0", "2.0.0");
        for (String version : List.of("1.0.0", "2.0.0"))
            write("repo/org/test/bom/" + version + "/bom-" + version + ".pom", "<project><modelVersion>4.0.0</modelVersion><groupId>org.test</groupId><artifactId>bom</artifactId><version>" + version + "</version><packaging>pom</packaging><dependencyManagement><dependencies><dependency><groupId>org.test</groupId><artifactId>managed</artifactId><version>1.0.0</version></dependency></dependencies></dependencyManagement></project>");
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                repositories { maven { url = uri('repo') } }
                dependencies { implementation enforcedPlatform('org.test:bom:1.0.0'); implementation 'org.test:managed' }
                depLens { rules { platform('org.test:bom') { allowedUpdates = 'major' } } }
                """);
        run("depLensCheckDependencies");
        JsonObject managed = entry(report("depLensCheckDependencies"), "org.test:managed", ":");
        assertThat(managed.getAsJsonArray("statuses").toString()).contains("CONSTRAINED", "BOM_MANAGED").doesNotContain("FAILED", "UPDATE_AVAILABLE");
        assertThat(managed.getAsJsonArray("updateContexts")).isEmpty();
        assertThat(managed.get("recommendedVersion").isJsonNull()).isTrue();
    }

    /**
     * Verifies that failed and unchecked entries do not produce an all current verdict.
     */
    @Test
    void failedAndUncheckedEntriesDoNotProduceAnAllCurrentVerdict() throws Exception {
        modules("org.test", "unlisted", "1.0.0");
        Files.delete(dir.resolve("repo/org/test/unlisted/maven-metadata.xml"));
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                repositories { maven { url = uri('repo') } }
                configurations { declarationOnly { canBeResolved = false } }
                dependencies { implementation 'org.test:missing:1.0.0'; implementation 'org.test:unlisted:1.0.0'; declarationOnly 'org.test:unknown:1.0.0' }
                """);
        BuildResult result = run("depLensCheckDependencies");
        JsonObject report = report("depLensCheckDependencies");
        assertThat(report.get("checksComplete").getAsBoolean()).isFalse();
        assertThat(entry(report, "org.test:unlisted", ":").getAsJsonArray("statuses").toString()).contains("FAILED", "NOT_CHECKED");
        assertThat(entry(report, "org.test:unknown", ":").getAsJsonArray("statuses").toString()).contains("NOT_CHECKED");
        assertThat(plain(result)).contains("checks incomplete").doesNotContain("All dependencies are up to date");
    }

    /**
     * Verifies that Gradle feed uses wrapper baseline and filters RC and ignored versions.
     */
    @Test
    void gradleFeedUsesWrapperBaselineAndFiltersRcAndIgnoredVersions() throws Exception {
        write("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.8-bin.zip\n");
        write("releases.json", "[{\"version\":\"8.8\"},{\"version\":\"8.8.1\"},{\"version\":\"8.9\"},{\"version\":\"9.0.0\"},{\"version\":\"9.1.0-rc-1\",\"rcFor\":\"9.1.0\"}]");
        write("build.gradle", """
                plugins { id 'com.sneedit.deplens' }
                depLens {
                    gradle { versionsUrl = file('releases.json').toURI().toString() }
                    rules { gradle { ignoredVersions = ['9.0.0'] } }
                }
                """);
        run("depLensCheckGradle");
        JsonObject gradle = entry(report("depLensCheckGradle"), "gradle", ":");
        assertThat(gradle.getAsJsonArray("candidates").toString()).contains("8.8.1", "8.9").doesNotContain("9.0.0", "rc-1");
        assertThat(gradle.getAsJsonArray("updateContexts").toString()).contains("\"currentVersion\":\"8.8\"");
    }

    /**
     * Verifies that plugin markers use plugin management repositories and keep aliases.
     */
    @Test
    void pluginMarkersUsePluginManagementRepositoriesAndKeepAliases() throws Exception {
        String id = "org.test.sample";
        modules(id, id + ".gradle.plugin", "1.0.0", "1.0.1", "2.0.0");
        modules("org.test", "implementation", "1.0.0");
        Path jar = dir.resolve("repo/org/test/implementation/1.0.0/implementation-1.0.0.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/gradle-plugins/" + id + ".properties"));
            out.write("implementation-class=org.gradle.api.plugins.JavaPlugin\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        for (String v : List.of("1.0.0", "1.0.1", "2.0.0"))
            write("repo/" + id.replace('.', '/') + "/" + id + ".gradle.plugin/" + v + "/" + id + ".gradle.plugin-" + v + ".pom",
                    "<project><modelVersion>4.0.0</modelVersion><groupId>" + id + "</groupId><artifactId>" + id + ".gradle.plugin</artifactId><version>" + v + "</version><packaging>pom</packaging><dependencies><dependency><groupId>org.test</groupId><artifactId>implementation</artifactId><version>1.0.0</version></dependency></dependencies></project>");
        write("settings.gradle", "pluginManagement { repositories { maven { url = uri('repo') } } }\nplugins { id 'com.sneedit.deplens.settings' }\nrootProject.name = 'plugins'\n");
        write("gradle/libs.versions.toml", "[plugins]\nsample = { id = '" + id + "', version = '1.0.0' }\n");
        write("build.gradle", "plugins { alias(libs.plugins.sample) apply false }\n");
        run("depLensCheckPlugins");
        JsonObject plugin = entry(report("depLensCheckPlugins"), id, ":");
        assertThat(plugin.getAsJsonArray("statuses").toString()).contains("UPDATE_AVAILABLE").doesNotContain("FAILED");
        assertThat(plugin.getAsJsonArray("candidates").toString()).contains("1.0.1", "2.0.0");
        assertThat(plugin.getAsJsonArray("catalogs").toString()).contains("sample");
    }

    /**
     * Verifies that included builds and build src can be checked independently.
     */
    @Test
    void includedBuildsAndBuildSrcCanBeCheckedIndependently() throws Exception {
        for (String name : List.of("root-lib", "sub-lib", "included-lib", "root-source-lib", "included-source-lib"))
            modules("org.test", name, "1.0.0", "2.0.0");
        write("settings.gradle", "rootProject.name = 'scope'\ninclude 'sub'\nincludeBuild 'build-logic'\n");
        write("sub/build.gradle", """
                plugins { id 'java' }
                repositories { maven { url = rootProject.uri('repo') } }
                dependencies { implementation 'org.test:sub-lib:1.0.0' }
                """);
        write("build-logic/settings.gradle", "rootProject.name = 'logic'\n");
        write("build-logic/build.gradle", """
                plugins { id 'java' }
                repositories { maven { url = uri('../repo') } }
                dependencies { implementation 'org.test:included-lib:1.0.0' }
                """);
        write("buildSrc/build.gradle", """
                plugins { id 'java' }
                repositories { maven { url = uri('../repo') } }
                dependencies { implementation 'org.test:root-source-lib:1.0.0' }
                """);
        write("build-logic/buildSrc/build.gradle", """
                plugins { id 'java' }
                repositories { maven { url = uri('../../repo') } }
                dependencies { implementation 'org.test:included-source-lib:1.0.0' }
                """);
        for (boolean included : List.of(false, true))
            for (boolean source : List.of(false, true)) {
                write("build.gradle", """
                        plugins { id 'java'; id 'com.sneedit.deplens' }
                        repositories { maven { url = uri('repo') } }
                        dependencies { implementation 'org.test:root-lib:1.0.0' }
                        depLens { checkIncludedBuilds = %s; checkBuildSrc = %s }
                        """.formatted(included, source));
                String output = plain(run("depLensCheckDependencies"));
                JsonObject report = report("depLensCheckDependencies");
                var coordinates = new java.util.HashSet<String>();
                for (JsonElement element : report.getAsJsonArray("entries"))
                    coordinates.add(element.getAsJsonObject().get("coordinate").getAsString());
                assertThat(coordinates).contains("org.test:root-lib", "org.test:sub-lib");
                assertThat(coordinates.contains("org.test:included-lib")).isEqualTo(included);
                assertThat(coordinates.contains("org.test:root-source-lib")).isEqualTo(source);
                assertThat(coordinates.contains("org.test:included-source-lib")).isEqualTo(included && source);
                assertThat(report.get("checksComplete").getAsBoolean()).withFailMessage("%s", report).isTrue();
                assertThat(output).doesNotContain(" failed", " not checked");
                assertThat(report.getAsJsonArray("builds")).hasSize(1 + (included ? 1 : 0) + (source ? 1 : 0) + (included && source ? 1 : 0));
            }
        // Inventory uses the same scope; deliberately excluded builds do not make it incomplete.
        Files.writeString(dir.resolve("build.gradle"), "\ndepLens { checkIncludedBuilds = false; checkBuildSrc = false }\n", StandardOpenOption.APPEND);
        run("depLensInventory");
        JsonObject inventory = report("inventory");
        assertThat(inventory.getAsJsonArray("builds")).hasSize(1);
        assertThat(inventory.get("complete").getAsBoolean()).isTrue();
    }

    /**
     * Verifies that nested builds keep their repository context and inherit root rules.
     */
    @Test
    void nestedBuildsKeepTheirRepositoryContextAndInheritRootRules() throws Exception {
        modules("org.test", "lib", "1.0.0", "1.1.0", "2.0.0");
        write("settings.gradle", "rootProject.name = 'parent'\nincludeBuild 'build-logic'\n");
        write("build.gradle", """
                plugins { id 'com.sneedit.deplens' }
                depLens { rules { dependency('org.test:lib') { ignoredVersions = ['2.0.0'] } } }
                """);
        write("build-logic/settings.gradle", "rootProject.name = 'logic'\n");
        write("build-logic/build.gradle", """
                plugins { id 'java' }
                repositories { maven { url = uri('../repo') } }
                dependencies { implementation 'org.test:lib:1.0.0' }
                """);
        String output = plain(run("depLensCheckDependencies"));
        assertThat(output.lines().filter(line -> line.startsWith("  • org.test:lib ")).count()).isEqualTo(1);
        JsonObject report = report("depLensCheckDependencies");
        JsonObject child = entry(report, "org.test:lib", ":");
        assertThat(child.get("build").getAsString()).endsWith("build-logic");
        assertThat(child.getAsJsonArray("candidates").toString()).isEqualTo("[\"1.1.0\"]");
        assertThat(report.getAsJsonArray("builds")).hasSize(2);
        assertThat(report.get("checksComplete").getAsBoolean()).isTrue();
    }

    /**
     * Verifies that Kotlin rules inherit ignored versions and preserve separate current versions.
     */
    @Test
    void kotlinRulesInheritIgnoredVersionsAndPreserveSeparateCurrentVersions() throws Exception {
        modules("org.test", "lib", "1.0.0", "1.0.1", "2.0.0", "2.0.1", "3.0.0");
        write("build.gradle.kts", """
                plugins { java; id("com.sneedit.deplens") }
                repositories { maven { url = uri("repo") } }
                val tool by configurations.creating
                dependencies { implementation("org.test:lib:1.0.0"); add("tool", "org.test:lib:2.0.0") }
                depLens { rules {
                    global { ignoredVersions.set(listOf("3.0.0")) }
                    dependency("org.test:lib") { allowedUpdates.set("patch") }
                } }
                """);
        run("depLensCheckDependencies");
        JsonObject lib = entry(report("depLensCheckDependencies"), "org.test:lib", ":");
        assertThat(lib.getAsJsonArray("candidates").toString()).contains("1.0.1", "2.0.1").doesNotContain("3.0.0");
        assertThat(lib.getAsJsonArray("updateContexts").toString()).contains("\"currentVersion\":\"1.0.0\"", "\"currentVersion\":\"2.0.0\"");
        assertThat(lib.getAsJsonArray("statuses").toString()).contains("IGNORED").doesNotContain("FAILED");
    }

    /**
     * Verifies that all ignored versions are checked without claiming up to date.
     */
    @Test
    void allIgnoredVersionsAreCheckedWithoutClaimingUpToDate() throws Exception {
        modules("org.test", "lib", "1.0.0", "2.0.0");
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                repositories { maven { url = uri('repo') } }
                dependencies { implementation 'org.test:lib:1.0.0' }
                depLens { rules { dependency('org.test:lib') { ignoredVersions = ['1.0.0', '2.0.0'] } } }
                """);
        run("depLensCheckDependencies");
        JsonObject lib = entry(report("depLensCheckDependencies"), "org.test:lib", ":");
        assertThat(lib.getAsJsonArray("statuses").toString()).contains("IGNORED").doesNotContain("FAILED", "UP_TO_DATE", "NOT_CHECKED");
        assertThat(lib.getAsJsonArray("candidates")).isEmpty();
    }

    /**
     * Verifies that Gradle offline and feed errors remain visible.
     */
    @Test
    void gradleOfflineAndFeedErrorsRemainVisible() throws Exception {
        write("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.8-bin.zip\n");
        write("build.gradle", "plugins { id 'com.sneedit.deplens' }\n");
        run("depLensCheckGradle");
        assertThat(entry(report("depLensCheckGradle"), "gradle", ":").getAsJsonArray("statuses").toString()).contains("NOT_CHECKED").doesNotContain("UP_TO_DATE");
        write("bad-feed.json", "broken");
        write("build.gradle", "plugins { id 'com.sneedit.deplens' }\ndepLens { gradle { versionsUrl = file('bad-feed.json').toURI().toString() } }\n");
        run("depLensCheckGradle");
        assertThat(entry(report("depLensCheckGradle"), "gradle", ":").getAsJsonArray("statuses").toString()).contains("FAILED", "NOT_CHECKED").doesNotContain("UP_TO_DATE");
    }

    /**
     * Verifies that authenticated repositories keep Gradle repository order.
     */
    @Test
    void authenticatedRepositoriesKeepGradleRepositoryOrder() throws Exception {
        modules("org.test", "lib", "1.0.0", "1.0.1", "2.0.0");
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        Set<String> requests = java.util.concurrent.ConcurrentHashMap.newKeySet();
        String expected = "Basic " + Base64.getEncoder().encodeToString("fixture:fixture-password".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        server.createContext("/", exchange -> {
            try {
                if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=fixture");
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
                String path = exchange.getRequestURI().getPath();
                requests.add(path);
                Path file = dir.resolve("repo/" + path.replaceFirst("^/(first|second)/", ""));
                if (!Files.isRegularFile(file)) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] bytes = Files.readAllBytes(file);
                if (path.startsWith("/second/") && path.endsWith(".pom"))
                    bytes = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                            .replace("</project>", "<dependencies><dependency><groupId>org.test</groupId><artifactId>wrong-repository-metadata</artifactId><version>1.0</version></dependency></dependencies></project>")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (exchange.getRequestMethod().equals("HEAD")) exchange.sendResponseHeaders(200, -1);
                else {
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            write("build.gradle", """
                    plugins { id 'java'; id 'com.sneedit.deplens' }
                    repositories {
                        maven { url = uri('%s/first'); allowInsecureProtocol = true; credentials { username = 'fixture'; password = 'fixture-password' } }
                        maven { url = uri('%s/second'); allowInsecureProtocol = true; credentials { username = 'fixture'; password = 'fixture-password' } }
                    }
                    dependencies { implementation 'org.test:lib:1.0.0' }
                    """.formatted(base, base));
            GradleRunner.create().withGradleVersion(System.getProperty("deplens.test.gradleVersion")).withProjectDir(dir.toFile()).withPluginClasspath()
                    .withArguments("depLensCheckDependencies", "--stacktrace").build();
            JsonObject report = report("depLensCheckDependencies");
            assertThat(report.get("checksComplete").getAsBoolean()).isTrue();
            assertThat(entry(report, "org.test:lib", ":").getAsJsonArray("candidates").toString()).contains("1.0.1", "2.0.0");
            assertThat(requests).anyMatch(path -> path.startsWith("/first/") && path.endsWith("2.0.0.pom"));
            assertThat(entry(report, "org.test:lib", ":").getAsJsonArray("updateContexts").toString()).contains("\"latestAllowedByBuild\":\"2.0.0\"");
            assertThat(report.toString()).doesNotContain("fixture-password", expected);
        } finally {
            server.stop(0);
        }
    }

    /**
     * Verifies that DepLens merges candidate listings across repositories in version order.
     */
    @Test
    void mergesCandidateListingsAcrossRepositoriesInVersionOrder() throws Exception {
        modules("org.test", "lib", "1.0.0", "1.1.0");
        Files.move(dir.resolve("repo"), dir.resolve("first"));
        modules("org.test", "lib", "1.0.0", "2.0.0");
        Files.move(dir.resolve("repo"), dir.resolve("second"));
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                repositories { maven { url = uri('first') }; maven { url = uri('second') } }
                dependencies { implementation 'org.test:lib:1.0.0' }
                """);
        run("depLensCheckDependencies");
        JsonObject lib = entry(report("depLensCheckDependencies"), "org.test:lib", ":");
        assertThat(lib.getAsJsonArray("candidates").toString()).isEqualTo("[\"2.0.0\",\"1.1.0\"]");
        assertThat(lib.getAsJsonArray("updateContexts").toString()).contains("\"latestAvailable\":\"2.0.0\"");
    }

    /**
     * Verifies that strict versions and build forces remain visible beside raw candidates.
     */
    @Test
    void strictVersionsAndBuildForcesRemainVisibleBesideRawCandidates() throws Exception {
        modules("org.test", "lib", "1.0.0", "1.0.1", "2.0.0");
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                repositories { maven { url = uri('repo') } }
                configurations { tool { resolutionStrategy.force 'org.test:lib:1.0.0' } }
                dependencies {
                    implementation('org.test:lib') { version { strictly '1.0.0' } }
                    tool 'org.test:lib:1.0.0'
                }
                """);
        assertThat(plain(run("depLensCheckDependencies")))
                .contains("current 1.0.0 | available only: 2.0.0", "allowed by build: 1.0.0",
                        "0 recommended updates, 1 available only")
                .doesNotContain("1.0.0 → 2.0.0");
        JsonObject lib = entry(report("depLensCheckDependencies"), "org.test:lib", ":");
        assertThat(lib.getAsJsonArray("statuses").toString()).contains("CONSTRAINED", "UPDATE_AVAILABLE").doesNotContain("FAILED");
        for (JsonElement value : lib.getAsJsonArray("updateContexts"))
            assertThat(value.getAsJsonObject().get("latestAllowedByBuild").getAsString()).isEqualTo("1.0.0");
        assertThat(lib.getAsJsonArray("candidates").toString()).contains("2.0.0", "1.0.1");
        assertThat(lib.get("recommendedVersion").isJsonNull()).isTrue();
    }

    /** The opt-in policy uses the full report, even when failed entries are hidden. */
    @Test
    void incompleteChecksFailOnlyWhenEnabledAfterWritingFilteredReportWithCache() throws Exception {
        modules("org.test", "lib", "1.0.0");
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                repositories { maven { url = uri('repo') } }
                dependencies { implementation 'org.missing:missing:1.0.0' }
                depLens {
                    failIfChecksIncomplete = providers.gradleProperty('strictCi').map { it.toBoolean() }.orElse(false)
                    outputDeclaredConfigurationFilter { excludes = ['implementation'] }
                }
                """);
        run("depLensCheckDependencies");
        assertThat(report("depLensCheckDependencies").get("checksComplete").getAsBoolean()).isFalse();
        for (int attempt = 0; attempt < 2; attempt++) {
            var result = GradleRunner.create().withGradleVersion(System.getProperty("deplens.test.gradleVersion"))
                    .withProjectDir(dir.toFile()).withPluginClasspath().withArguments("depLensCheckDependencies",
                            "--offline", "-PstrictCi=true", "--configuration-cache", "--configuration-cache-problems=fail")
                    .buildAndFail();
            assertThat(plain(result)).contains("DepLens checks incomplete. Report written before failing as configured by failIfChecksIncomplete.");
            if (attempt == 1) assertThat(plain(result)).contains("Reusing configuration cache");
            JsonObject report = report("depLensCheckDependencies");
            assertThat(report.get("checksComplete").getAsBoolean()).isFalse();
            assertThat(report.get("hiddenFailedEntries").getAsInt()).isGreaterThan(0);
            assertThat(report.getAsJsonArray("entries").asList()).noneMatch(e ->
                    e.getAsJsonObject().get("coordinate").getAsString().equals("org.missing:missing"));
        }
    }

    /** Deliberately unselected component types do not count as incomplete selected checks. */
    @Test
    void incompleteCheckPolicyRespectsTaskScopeAndFailsUncheckedOfflineWrapper() throws Exception {
        write("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.8-bin.zip\n");
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                depLens { failIfChecksIncomplete = true }
                """);
        run("depLensCheckDependencies");
        assertThat(report("depLensCheckDependencies").get("checksComplete").getAsBoolean()).isTrue();
        var result = GradleRunner.create().withGradleVersion(System.getProperty("deplens.test.gradleVersion"))
                .withProjectDir(dir.toFile()).withPluginClasspath()
                .withArguments("depLensCheckGradle", "--offline").buildAndFail();
        assertThat(plain(result)).contains("configured by failIfChecksIncomplete");
        JsonObject report = report("depLensCheckGradle");
        assertThat(report.get("complete").getAsBoolean()).isTrue();
        assertThat(report.get("checksComplete").getAsBoolean()).isFalse();
        assertThat(entry(report, "gradle", ":").getAsJsonArray("statuses").toString())
                .contains("NOT_CHECKED").doesNotContain("FAILED", "NO_MATCHING_CANDIDATES");
    }

    /** The requesting task's policy covers unchecked included builds under isolation and cache reuse. */
    @Test
    @org.junit.jupiter.api.Timeout(120)
    void incompleteIncludedBuildFailsAggregatedCheckWithIsolatedCache() throws Exception {
        write("settings.gradle", "rootProject.name='ci-policy'\nincludeBuild 'nested'\n");
        write("build.gradle", "plugins { id 'com.sneedit.deplens' }\ndepLens { failIfChecksIncomplete = true }\n");
        write("nested/settings.gradle", "rootProject.name='nested'\n");
        write("nested/build.gradle", "plugins { id 'java' }\n");
        write("nested/gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.8-bin.zip\n");
        String isolation = org.gradle.util.GradleVersion.version(System.getProperty("deplens.test.gradleVersion"))
                .compareTo(org.gradle.util.GradleVersion.version("9.7")) >= 0
                ? "-Dorg.gradle.isolated-projects=true" : "-Dorg.gradle.unsafe.isolated-projects=true";
        for (int attempt = 0; attempt < 2; attempt++) {
            var result = GradleRunner.create().withGradleVersion(System.getProperty("deplens.test.gradleVersion"))
                    .withProjectDir(dir.toFile()).withPluginClasspath().withArguments("depLensCheck", "--offline",
                            "--configuration-cache", "--configuration-cache-problems=fail", isolation).buildAndFail();
            assertThat(plain(result)).contains("configured by failIfChecksIncomplete");
            if (attempt == 1) assertThat(plain(result)).contains("Reusing configuration cache");
            JsonObject report = report("depLensCheck");
            assertThat(report.get("checksComplete").getAsBoolean()).isFalse();
            assertThat(report.getAsJsonArray("entries").asList()).anySatisfy(value -> {
                var entry = value.getAsJsonObject();
                assertThat(entry.get("build").getAsString()).endsWith("nested");
                assertThat(entry.getAsJsonArray("statuses").toString()).contains("NOT_CHECKED");
                assertThat(entry.get("coordinate").getAsString()).isEqualTo("gradle");
            });
        }
    }

    /** Completeness describes the selected fast mode; discovering updates alone is not a failure. */
    @Test
    void completedFastCheckSucceedsWithIncompleteCheckPolicy() throws Exception {
        modules("org.test", "lib", "1.0.0", "2.0.0");
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                repositories { maven { url = uri('repo') } }
                dependencies { implementation 'org.test:lib:1.0.0' }
                depLens { failIfChecksIncomplete = true }
                """);
        run("depLensCheck", "--fast");
        JsonObject report = report("depLensCheck");
        assertThat(report.get("fastCheck").getAsBoolean()).isTrue();
        assertThat(report.get("checksComplete").getAsBoolean()).isTrue();
        assertThat(entry(report, "org.test:lib", ":").getAsJsonArray("statuses").toString()).contains("UPDATE_AVAILABLE");
    }

    /** Kafka-style forced Scala and alpha-only metadata must have different successful verdicts. */
    @Test
    void kafkaComparisonDistinguishesAvailableOnlyAndMissingCandidatesWithCache() throws Exception {
        modules("org.scala-lang", "scala-library", "2.13.18", "3.10.0");
        modules("io.opentelemetry.proto", "opentelemetry-proto", "1.3.2-alpha", "1.4.0-alpha");
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                depLens { failIfChecksIncomplete = true }
                repositories { maven { url = uri('repo') } }
                configurations.configureEach { resolutionStrategy.force 'org.scala-lang:scala-library:2.13.18' }
                dependencies {
                    implementation 'org.scala-lang:scala-library:2.13.18'
                    implementation 'io.opentelemetry.proto:opentelemetry-proto:1.3.2-alpha'
                }
                """);
        for (int attempt = 0; attempt < 2; attempt++) {
            String output = plain(run("depLensCheckDependencies", "--configuration-cache", "--configuration-cache-problems=fail"));
            assertThat(output).contains("scala-library current 2.13.18 | available only: 3.10.0",
                    "allowed by build: 2.13.18; build restrictions must be changed; no recommendation",
                    "No matching candidates:", "io.opentelemetry.proto:opentelemetry-proto",
                    "0 recommended updates, 1 available only, 1 with no matching candidates")
                    .doesNotContain("2.13.18 → 3.10.0", "checks incomplete", " failed");
            if (attempt == 1) assertThat(output).contains("Reusing configuration cache");
            JsonObject report = report("depLensCheckDependencies");
            assertThat(report.get("checksComplete").getAsBoolean()).isTrue();
            JsonObject scala = entry(report, "org.scala-lang:scala-library", ":");
            assertThat(scala.get("recommendedVersion").isJsonNull()).isTrue();
            JsonObject proto = entry(report, "io.opentelemetry.proto:opentelemetry-proto", ":");
            assertThat(proto.getAsJsonArray("statuses").toString()).contains("NO_MATCHING_CANDIDATES")
                    .doesNotContain("UP_TO_DATE", "FAILED", "NOT_CHECKED");
            for (var context : proto.getAsJsonArray("updateContexts"))
                assertThat(context.getAsJsonObject().get("noMatchingCandidates").getAsBoolean()).isTrue();
        }
    }

    /** A nonempty feed rejected by policy is different from a broken or empty feed. */
    @Test
    void gradleFeedDistinguishesNoMatchingVersionsFromFailure() throws Exception {
        write("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.8-bin.zip\n");
        write("build.gradle", """
                plugins { id 'com.sneedit.deplens' }
                depLens { gradle { versionsUrl = file('releases.json').toURI().toString() } }
                """);
        write("releases.json", "[{\"version\":\"9.1.0-rc-1\",\"rcFor\":\"9.1.0\"}]");
        assertThat(plain(run("depLensCheckGradle"))).contains("No matching candidates:", "1 with no matching candidates");
        assertThat(entry(report("depLensCheckGradle"), "gradle", ":").getAsJsonArray("statuses").toString())
                .contains("NO_MATCHING_CANDIDATES").doesNotContain("FAILED", "UP_TO_DATE", "NOT_CHECKED");
        write("releases.json", "[]");
        run("depLensCheckGradle");
        assertThat(entry(report("depLensCheckGradle"), "gradle", ":").getAsJsonArray("statuses").toString())
                .contains("FAILED", "NOT_CHECKED").doesNotContain("NO_MATCHING_CANDIDATES", "UP_TO_DATE");
    }

    /**
     * Verifies that Gradle prerelease opt in includes RC and nightly.
     */
    @Test
    void gradlePrereleaseOptInIncludesRcAndNightly() throws Exception {
        write("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.8-bin.zip\n");
        write("releases.json", "[{\"version\":\"8.8\"},{\"version\":\"9.0.0\"},{\"version\":\"9.1.0-rc-1\",\"rcFor\":\"9.1.0\"},{\"version\":\"9.2.0-20261010000000+0000\",\"snapshot\":true,\"nightly\":true}]");
        write("build.gradle", """
                plugins { id 'com.sneedit.deplens' }
                depLens { gradle { versionsUrl = file('releases.json').toURI().toString(); includePreReleases = true } }
                """);
        run("depLensCheckGradle");
        assertThat(entry(report("depLensCheckGradle"), "gradle", ":").getAsJsonArray("candidates").toString()).contains("9.1.0-rc-1", "9.2.0-20261010000000+0000");
    }

    /**
     * Verifies that canonical task writes full report before configured failure without editing sources.
     */
    @Test
    void canonicalTaskWritesFullReportBeforeConfiguredFailureWithoutEditingSources() throws Exception {
        modules("org.test", "lib", "1.0.0", "2.0.0");
        String build = """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                repositories { maven { url = uri('repo') } }
                dependencies { implementation 'org.test:lib:1.0.0' }
                depLens {
                    dependencies { failIfUpdatesFound = true }
                    rules { gradle { pinned = true } }
                }
                """;
        write("build.gradle", build);
        BuildResult result = GradleRunner.create().withGradleVersion(System.getProperty("deplens.test.gradleVersion")).withProjectDir(dir.toFile()).withPluginClasspath()
                .withArguments("depLensCheck", "--offline").buildAndFail();
        assertThat(plain(result)).contains("Updates available. Report written before failing as configured.");
        JsonObject report = report("depLensCheck");
        assertThat(entry(report, "org.test:lib", ":").getAsJsonArray("candidates").toString()).contains("2.0.0");
        assertThat(entry(report, "gradle", ":").getAsJsonArray("statuses").toString()).contains("PINNED");
        assertThat(Files.readString(dir.resolve("build.gradle"))).isEqualTo(build);
    }


    /**
     * Verifies that default check is compact while info and JSON retain configuration details.
     */
    @Test
    void defaultCheckIsCompactWhileInfoAndJsonRetainConfigurationDetails() throws Exception {
        modules("org.test", "lib", "1.0.0", "1.0.1", "2.0.0");
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                repositories { maven { url = uri('repo') } }
                dependencies { implementation 'org.test:lib:1.0.0' }
                depLens { rules { gradle { pinned = true } } }
                """);
        String output = plain(run("depLensCheck"));
        assertThat(output).contains("• org.test:lib 1.0.0 → 2.0.0")
                .doesNotContain("Details: --info | JSON report:")
                .doesNotContain("  declared:", "  source:", "  rule:", "  latest available:", "outside selector (explicit version/selector change)");
        assertThat(output.lines().filter(line -> line.startsWith("  • org.test:lib ")).count()).isEqualTo(1);
        JsonObject row = entry(report("depLensCheck"), "org.test:lib", ":");
        assertThat(row.getAsJsonArray("versionSources")).isNotEmpty();
        assertThat(row.getAsJsonArray("updateContexts").toString()).contains("compileClasspath", "runtimeClasspath", "1.0.1", "2.0.0");
        var detailed = GradleRunner.create().withGradleVersion(System.getProperty("deplens.test.gradleVersion"))
                .withProjectDir(dir.toFile()).withPluginClasspath()
                .withArguments("depLensCheck", "--offline", "--info").build();
        assertThat(plain(detailed)).contains("  declared:", "  source:", "  rule:", "  latest available:");
    }

    /**
     * Verifies that Spring managed versionless peers remain resolvable in update queries.
     */
    @Test
    void springManagedVersionlessPeersRemainResolvableInUpdateQueries() throws Exception {
        modules("org.test", "lib", "1.0.0", "2.0.0");
        modules("org.test", "peer", "1.0.0", "1.1.0");
        String springJar = Path.of(io.spring.gradle.dependencymanagement.DependencyManagementPlugin.class
                .getProtectionDomain().getCodeSource().getLocation().toURI()).toString().replace("\\", "\\\\").replace("'", "\\'");
        write("build.gradle", """
                buildscript { dependencies { classpath files('%s') } }
                plugins { id 'java'; id 'com.sneedit.deplens' }
                apply plugin: 'io.spring.dependency-management'
                repositories { maven { url = uri('repo') } }
                dependencyManagement {
                    dependencies { dependency 'org.test:lib:1.0.0'; dependency 'org.test:peer:1.0.0' }
                    runtimeClasspath { dependencies { dependency 'org.test:peer:1.1.0' } }
                }
                dependencies { implementation 'org.test:lib'; implementation 'org.test:peer' }
                depLens { rules { gradle { pinned = true } } }
                """.formatted(springJar));
        String output = plain(run("depLensCheck"));
        JsonObject report = report("depLensCheck");
        assertThat(report.get("checksComplete").getAsBoolean()).isTrue();
        JsonObject lib = entry(report, "org.test:lib", ":");
        assertThat(lib.getAsJsonArray("statuses").toString()).doesNotContain("FAILED", "NOT_CHECKED");
        assertThat(lib.get("recommendedVersion").getAsString()).isEqualTo("2.0.0");
        assertThat(entry(report, "org.test:peer", ":").getAsJsonArray("resolutions").toString()).contains("1.0.0", "1.1.0");
        assertThat(output).doesNotContain(" failed", " not checked").doesNotContain("Update query failed");
    }

    /**
     * Verifies that default output omits current entries and aggregates problems.
     */
    @Test
    void defaultOutputOmitsCurrentEntriesAndAggregatesProblems() throws Exception {
        modules("org.test", "current", "1.0.0");
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                repositories { maven { url = uri('repo') } }
                configurations { declarations { canBeResolved = false } }
                dependencies {
                    implementation 'org.test:current:1.0.0'
                    (1..12).each { declarations "org.test:unknown${it}:1.0.0" }
                }
                depLens { rules { gradle { pinned = true } } }
                """);
        String output = plain(run("depLensCheck"));
        assertThat(output).contains("0 recommended updates, 0 available only, 12 not checked", "checks incomplete")
                .doesNotContain("org.test:current", "no resolved version", "No resolved", "Update query failed", "Checking for");
        assertThat(report("depLensCheck").getAsJsonArray("warnings")).hasSize(12);
    }

    /**
     * Verifies that local plugin and wrapperless build are expected skips.
     */
    @Test
    void localPluginAndWrapperlessBuildAreExpectedSkips() throws Exception {
        write("build.gradle", """
                plugins { id 'java-gradle-plugin'; id 'com.sneedit.deplens' }
                gradlePlugin { plugins { local { id = 'test.local'; implementationClass = 'LocalPlugin' } } }
                """);
        String output = plain(run("depLensCheck"));
        JsonObject report = report("depLensCheck");
        assertThat(report.get("checksComplete").getAsBoolean()).isTrue();
        assertThat(output).contains("0 recommended updates, 0 available only").doesNotContain("test.local", "No Gradle wrapper", "Local component");
        assertThat(entry(report, "test.local", ":").getAsJsonArray("statuses").toString()).contains("NOT_CHECKED");
    }

    /**
     * Verifies that plugin development include build is not checked as aconsumer.
     */
    @Test
    void pluginDevelopmentIncludeBuildIsNotCheckedAsAConsumer() throws Exception {
        write("settings.gradle", "rootProject.name = 'consumer'\nincludeBuild 'tooling'\n");
        write("build.gradle", "plugins { id 'com.sneedit.deplens' }\n");
        write("tooling/settings.gradle", "rootProject.name = 'plugin-source'\ninclude 'plugin'\n");
        write("tooling/build.gradle", "");
        write("tooling/plugin/build.gradle", """
                plugins { id 'java-gradle-plugin' }
                gradlePlugin { plugins { depLens { id = 'com.sneedit.deplens'; implementationClass = 'com.sneedit.deplens.DepLensPlugin' } } }
                """);
        write("tooling/plugin/src/main/java/com/sneedit/deplens/DepLensPlugin.java", "package com.sneedit.deplens; public class DepLensPlugin {}\n");
        String output = plain(run("depLensCheck"));
        JsonObject report = report("depLensCheck");
        assertThat(report.get("checksComplete").getAsBoolean()).isTrue();
        assertThat(report.getAsJsonArray("builds")).hasSize(1);
        assertThat(output).doesNotContain("depLensBuild", "Separate build requires");
    }

    /**
     * Verifies that tooling api hides check downloads but keeps other downloads and diagnostic mode.
     */
    @Test
    void toolingApiHidesCheckDownloadsButKeepsOtherDownloadsAndDiagnosticMode() throws Exception {
        modules("org.test.fixture", "org.test.fixture.gradle.plugin", "1.0.0", "2.0.0");
        modules("org.test", "after", "1.0.0", "2.0.0");
        for (String version : List.of("1.0.0", "2.0.0")) {
            Path jar = dir.resolve("repo/org/test/fixture/org.test.fixture.gradle.plugin/" + version + "/org.test.fixture.gradle.plugin-" + version + ".jar");
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
                out.putNextEntry(new JarEntry("META-INF/gradle-plugins/org.test.fixture.properties"));
                out.write("implementation-class=org.gradle.api.plugins.JavaPlugin\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        Set<String> requests = java.util.concurrent.ConcurrentHashMap.newKeySet();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                requests.add(path);
                Path file = dir.resolve("repo" + path);
                if (!Files.isRegularFile(file)) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] body = Files.readAllBytes(file);
                if (exchange.getRequestMethod().equals("HEAD")) exchange.sendResponseHeaders(200, -1);
                else {
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            String repository = "http://127.0.0.1:" + server.getAddress().getPort();
            write("settings.gradle", "pluginManagement { repositories { maven { url = uri('" + repository + "'); allowInsecureProtocol = true } } }\nrootProject.name = 'progress'\n");
            write("build.gradle", """
                    plugins { id 'org.test.fixture' version '1.0.0' }
                    depLens { plugins { failIfUpdatesFound = project.hasProperty('failCheck') } }
                    repositories { maven { url = uri('%s'); allowInsecureProtocol = true } }
                    tasks.register('resolveOther') {
                        mustRunAfter 'depLensCheck', 'depLensCheckPlugins'
                        doLast {
                            def query = configurations.detachedConfiguration(dependencies.create('org.test:after:+'))
                            query.transitive = false
                            query.incoming.resolutionResult.root
                        }
                    }
                    """.formatted(repository));
            Properties metadata = new Properties();
            try (var input = getClass().getClassLoader().getResourceAsStream("plugin-under-test-metadata.properties")) {
                metadata.load(input);
            }
            String classpath = Arrays.stream(metadata.getProperty("implementation-classpath").split(java.io.File.pathSeparator))
                    .map(path -> "'" + path.replace("\\", "\\\\").replace("'", "\\'") + "'").collect(java.util.stream.Collectors.joining(","));
            write("progress.init.gradle", "initscript { dependencies { classpath files(" + classpath + ") } }\ngradle.beforeProject { p -> p.pluginManager.apply(com.sneedit.deplens.DepLensPlugin) }\n");
            var connector = org.gradle.tooling.GradleConnector.newConnector()
                    .useGradleVersion(System.getProperty("deplens.test.gradleVersion")).forProjectDirectory(dir.toFile());
            try (var connection = connector.connect()) {
                connection.newBuild().forTasks("help").withArguments("--init-script", "progress.init.gradle").run();
                for (String task : List.of("depLensCheckPlugins", "depLensCheck")) {
                    List<String> downloads = Collections.synchronizedList(new ArrayList<>());
                    List<String> tasks = Collections.synchronizedList(new ArrayList<>());
                    var output = new java.io.ByteArrayOutputStream();
                    // Refresh also forces downloads for resolveOther, demonstrating that the filter is scoped and restored.
                    connection.newBuild().forTasks(task, "resolveOther")
                            .withArguments("--init-script", "progress.init.gradle", "--refresh-dependencies")
                            .setStandardOutput(output).setStandardError(output)
                            .addProgressListener(event -> {
                                if (event instanceof org.gradle.tooling.events.download.FileDownloadProgressEvent download)
                                    downloads.add(download.getDescriptor().getUri().toString());
                                if (event instanceof org.gradle.tooling.events.task.TaskFinishEvent finish)
                                    tasks.add(finish.getDescriptor().getTaskPath());
                            }, org.gradle.tooling.events.OperationType.FILE_DOWNLOAD, org.gradle.tooling.events.OperationType.TASK).run();
                    assertThat(requests).anyMatch(path -> path.endsWith("org.test.fixture.gradle.plugin/maven-metadata.xml"));
                    assertThat(downloads).anyMatch(url -> url.contains("org/test/after/maven-metadata.xml"))
                            .noneMatch(url -> url.contains("org.test.fixture.gradle.plugin/maven-metadata.xml"));
                    assertThat(tasks).contains(":" + task, ":resolveOther");
                    assertThat(plain(output.toString(java.nio.charset.StandardCharsets.UTF_8))).contains("org.test.fixture 1.0.0 → 2.0.0").doesNotContain(" failed", " not checked")
                            .doesNotContain("Details: --info");
                }
                List<String> afterFailure = Collections.synchronizedList(new ArrayList<>());
                var failing = connection.newBuild().forTasks("depLensCheckPlugins", "resolveOther")
                        .withArguments("--init-script", "progress.init.gradle", "--refresh-dependencies", "--continue", "-PfailCheck=true")
                        .addProgressListener(event -> {
                            if (event instanceof org.gradle.tooling.events.download.FileDownloadProgressEvent download)
                                afterFailure.add(download.getDescriptor().getUri().toString());
                        }, org.gradle.tooling.events.OperationType.FILE_DOWNLOAD);
                org.assertj.core.api.Assertions.assertThatThrownBy(failing::run).isInstanceOf(org.gradle.tooling.BuildException.class);
                assertThat(afterFailure).anyMatch(url -> url.contains("org/test/after/maven-metadata.xml"))
                        .noneMatch(url -> url.contains("org.test.fixture.gradle.plugin/maven-metadata.xml"));
                List<String> detailed = Collections.synchronizedList(new ArrayList<>());
                connection.newBuild().forTasks("depLensCheckPlugins")
                        .withArguments("--init-script", "progress.init.gradle", "--refresh-dependencies", "--info")
                        .addProgressListener(event -> {
                            if (event instanceof org.gradle.tooling.events.download.FileDownloadProgressEvent download)
                                detailed.add(download.getDescriptor().getUri().toString());
                        }, org.gradle.tooling.events.OperationType.FILE_DOWNLOAD).run();
                assertThat(detailed).anyMatch(url -> url.contains("org.test.fixture.gradle.plugin/maven-metadata.xml"));
            }
        } finally {
            server.stop(0);
        }
    }

    /**
     * Verifies that combined check separates dependencies platforms plugins and wrapper.
     */
    @Test
    void combinedCheckSeparatesDependenciesPlatformsPluginsAndWrapper() throws Exception {
        String id = "org.test.sample";
        modules(id, id + ".gradle.plugin", "1.0.0", "2.0.0");
        for (String version : List.of("1.0.0", "2.0.0")) {
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(dir.resolve("repo/org/test/sample/" + id + ".gradle.plugin/" + version + "/" + id + ".gradle.plugin-" + version + ".jar")))) {
                out.putNextEntry(new JarEntry("META-INF/gradle-plugins/" + id + ".properties"));
                out.write("implementation-class=org.gradle.api.plugins.JavaPlugin\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        modules("org.test", "lib", "1.0.0", "2.0.0");
        modules("org.test", "bom", "1.0.0", "2.0.0");
        for (String version : List.of("1.0.0", "2.0.0"))
            write("repo/org/test/bom/" + version + "/bom-" + version + ".pom",
                    "<project><modelVersion>4.0.0</modelVersion><groupId>org.test</groupId><artifactId>bom</artifactId><version>" + version + "</version><packaging>pom</packaging></project>");
        write("settings.gradle", "pluginManagement { repositories { maven { url = uri('repo') } } }\nplugins { id 'com.sneedit.deplens.settings' }\nrootProject.name = 'grouped'\n");
        write("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.8-bin.zip\n");
        write("releases.json", "[{\"version\":\"8.8\"},{\"version\":\"8.9\"}]");
        write("build.gradle", """
                plugins { id 'java'; id 'org.test.sample' version '1.0.0' apply false }
                repositories { maven { url = uri('repo') } }
                dependencies { implementation 'org.test:lib:1.0.0'; implementation platform('org.test:bom:1.0.0') }
                depLens { gradle { versionsUrl = file('releases.json').toURI().toString() } }
                """);
        for (String task : List.of("depLensCheck")) {
            String output = plain(run(task));
            assertThat(output).containsSubsequence("Dependencies:", "org.test:lib 1.0.0 → 2.0.0",
                    "Platforms / BOMs:", "org.test:bom 1.0.0 → 2.0.0",
                    "Gradle plugins:", "org.test.sample 1.0.0 → 2.0.0",
                    "Gradle wrapper:", "gradle 8.8 → 8.9", "DepLens: 4 recommended updates, 0 available only");
            assertThat(output.lines().filter(line -> line.startsWith("DepLens:")).count()).isEqualTo(1);
            assertThat(output).doesNotContain("Version constraints:", "Details: --info");
        }
    }

    /**
     * Verifies that BOM managed dependencies have no independent recommendations even with pinned or current BOM.
     */
    @Test
    void bomManagedDependenciesHaveNoIndependentRecommendationsEvenWithPinnedOrCurrentBom() throws Exception {
        for (String module : List.of("managed", "fixed", "bom")) modules("org.test", module, "1.0.0", "2.0.0");
        for (String version : List.of("1.0.0", "2.0.0"))
            write("repo/org/test/bom/" + version + "/bom-" + version + ".pom",
                    "<project><modelVersion>4.0.0</modelVersion><groupId>org.test</groupId><artifactId>bom</artifactId><version>" + version + "</version><packaging>pom</packaging><dependencyManagement><dependencies>"
                            + "<dependency><groupId>org.test</groupId><artifactId>managed</artifactId><version>" + version + "</version></dependency>"
                            + "<dependency><groupId>org.test</groupId><artifactId>fixed</artifactId><version>" + version + "</version></dependency>"
                            + "</dependencies></dependencyManagement></project>");
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                repositories { maven { url = uri('repo') } }
                dependencies {
                    implementation platform('org.test:bom:1.0.0')
                    implementation 'org.test:managed'
                    implementation 'org.test:fixed:1.0.0'
                }
                depLens { rules { gradle { pinned = true } } }
                """);
        String output = plain(run("depLensCheck"));
        assertThat(output).contains("org.test:bom 1.0.0 → 2.0.0 (1 managed dependency)", "org.test:fixed 1.0.0 → 2.0.0",
                "DepLens: 2 recommended updates, 0 available only, 1 BOM-managed").doesNotContain("• org.test:managed ");
        JsonObject managed = entry(report("depLensCheck"), "org.test:managed", ":");
        assertThat(managed.getAsJsonArray("candidates")).isEmpty();
        assertThat(managed.get("recommendedVersion").isJsonNull()).isTrue();
        assertThat(managed.getAsJsonArray("statuses").toString()).contains("BOM_MANAGED").doesNotContain("UPDATE_AVAILABLE", "NOT_CHECKED");
        assertThat(managed.getAsJsonArray("platforms").toString()).contains("org.test:bom");
        Files.writeString(dir.resolve("build.gradle"), "\ndepLens { rules { platform('org.test:bom') { pinned = true } } }\n", StandardOpenOption.APPEND);
        output = plain(run("depLensCheck"));
        assertThat(output).contains("org.test:fixed 1.0.0 → 2.0.0", "DepLens: 1 recommended updates, 0 available only, 1 BOM-managed")
                .doesNotContain("• org.test:managed ", "• org.test:bom ");
        Files.writeString(dir.resolve("build.gradle"), "\ndependencies { implementation platform('org.test:bom:2.0.0') }\n", StandardOpenOption.APPEND);
        output = plain(run("depLensCheck"));
        assertThat(output).contains("1 BOM-managed").doesNotContain("• org.test:managed ");
    }

    /**
     * Verifies that Spring BOM ownership includes nested imports and processor configurations but retains overrides.
     */
    @Test
    void springBomOwnershipIncludesNestedImportsAndProcessorConfigurationsButRetainsOverrides() throws Exception {
        for (String module : List.of("managed", "processor", "fixed", "explicit", "property", "scoped", "child-bom", "boot-bom"))
            modules("org.test", module, "1.0.0", "2.0.0");
        write("repo/org/test/child-bom/1.0.0/child-bom-1.0.0.pom", """
                <project><modelVersion>4.0.0</modelVersion><groupId>org.test</groupId><artifactId>child-bom</artifactId><version>1.0.0</version><packaging>pom</packaging>
                <properties><property.version>1.0.0</property.version></properties><dependencyManagement><dependencies>
                <dependency><groupId>org.test</groupId><artifactId>managed</artifactId><version>1.0.0</version></dependency>
                <dependency><groupId>org.test</groupId><artifactId>processor</artifactId><version>1.0.0</version></dependency>
                <dependency><groupId>org.test</groupId><artifactId>fixed</artifactId><version>1.0.0</version></dependency>
                <dependency><groupId>org.test</groupId><artifactId>explicit</artifactId><version>1.0.0</version></dependency>
                <dependency><groupId>org.test</groupId><artifactId>property</artifactId><version>${property.version}</version></dependency>
                <dependency><groupId>org.test</groupId><artifactId>scoped</artifactId><version>1.0.0</version></dependency>
                </dependencies></dependencyManagement></project>
                """);
        write("repo/org/test/boot-bom/1.0.0/boot-bom-1.0.0.pom", """
                <project><modelVersion>4.0.0</modelVersion><groupId>org.test</groupId><artifactId>boot-bom</artifactId><version>1.0.0</version><packaging>pom</packaging>
                <dependencyManagement><dependencies><dependency><groupId>org.test</groupId><artifactId>child-bom</artifactId><version>1.0.0</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement></project>
                """);
        String springJar = Path.of(io.spring.gradle.dependencymanagement.DependencyManagementPlugin.class
                .getProtectionDomain().getCodeSource().getLocation().toURI()).toString().replace("\\", "\\\\").replace("'", "\\'");
        write("gradle/libs.versions.toml", """
                [libraries]
                managed = { module = "org.test:managed" }
                fixed = { module = "org.test:fixed", version = "1.0.0" }
                """);
        write("build.gradle", """
                buildscript { dependencies { classpath files('%s') } }
                plugins { id 'java'; id 'com.sneedit.deplens' }
                apply plugin: 'io.spring.dependency-management'
                repositories { maven { url = uri('repo') } }
                ext['property.version'] = '1.0.1'
                dependencyManagement {
                    imports { mavenBom 'org.test:boot-bom:1.0.0' }
                    dependencies { dependency 'org.test:explicit:1.0.0' }
                    runtimeClasspath { dependencies { dependency 'org.test:scoped:1.0.0' } }
                }
                dependencies {
                    implementation libs.managed
                    implementation libs.fixed
                    annotationProcessor 'org.test:processor'
                    compileOnly 'org.test:processor'
                    implementation 'org.test:explicit'
                    implementation 'org.test:property'
                    implementation 'org.test:scoped'
                }
                depLens { rules { gradle { pinned = true }; platform('org.test:boot-bom') { pinned = true } } }
                """.formatted(springJar));
        modules("org.test", "property", "1.0.0", "1.0.1", "2.0.0");
        String output = plain(run("depLensCheck"));
        JsonObject report = report("depLensCheck");
        assertThat(report.get("checksComplete").getAsBoolean()).isTrue();
        for (String module : List.of("managed", "processor")) {
            JsonObject row = entry(report, "org.test:" + module, ":");
            assertThat(row.getAsJsonArray("statuses").toString()).contains("BOM_MANAGED").doesNotContain("UPDATE_AVAILABLE", "NOT_CHECKED");
            assertThat(row.getAsJsonObject("bomOwners").toString()).contains("org.test:boot-bom");
            assertThat(row.get("recommendedVersion").isJsonNull()).isTrue();
            assertThat(output).doesNotContain("• org.test:" + module + " ");
        }
        for (String module : List.of("fixed", "explicit", "property", "scoped")) {
            JsonObject row = entry(report, "org.test:" + module, ":");
            assertThat(row.getAsJsonArray("statuses").toString()).contains("UPDATE_AVAILABLE").doesNotContain("BOM_MANAGED");
            assertThat(output).contains("• org.test:" + module + " ");
        }
        assertThat(output).contains("2 BOM-managed");
        assertThat(entry(report, "org.test:boot-bom", ":").getAsJsonArray("statuses").toString()).contains("PINNED");
        Files.writeString(dir.resolve("build.gradle"), "\ndepLens { rules { platform('org.test:boot-bom') { pinned = false } } }\n", StandardOpenOption.APPEND);
        output = plain(run("depLensCheck"));
        assertThat(output).contains("org.test:boot-bom 1.0.0 → 2.0.0", "2 BOM-managed")
                .doesNotContain("• org.test:managed ", "• org.test:processor ");
        assertThat(report("depLensCheck").get("checksComplete").getAsBoolean()).isTrue();
    }

    /**
     * Verifies that native BOM ownership retains constraints and mixed configuration versions.
     */
    @Test
    void nativeBomOwnershipRetainsConstraintsAndMixedConfigurationVersions() throws Exception {
        for (String module : List.of("managed", "constrained", "mixed", "forced", "bom"))
            modules("org.test", module, "1.0.0", "2.0.0");
        write("repo/org/test/bom/1.0.0/bom-1.0.0.pom", """
                <project><modelVersion>4.0.0</modelVersion><groupId>org.test</groupId><artifactId>bom</artifactId><version>1.0.0</version><packaging>pom</packaging>
                <dependencyManagement><dependencies>
                <dependency><groupId>org.test</groupId><artifactId>managed</artifactId><version>1.0.0</version></dependency>
                <dependency><groupId>org.test</groupId><artifactId>constrained</artifactId><version>1.0.0</version></dependency>
                <dependency><groupId>org.test</groupId><artifactId>mixed</artifactId><version>1.0.0</version></dependency>
                <dependency><groupId>org.test</groupId><artifactId>forced</artifactId><version>1.0.0</version></dependency>
                </dependencies></dependencyManagement></project>
                """);
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                repositories { maven { url = uri('repo') } }
                configurations { separate { canBeResolved = true } }
                configurations.configureEach { resolutionStrategy.force 'org.test:forced:1.0.0' }
                dependencies {
                    implementation platform('org.test:bom:1.0.0')
                    implementation 'org.test:managed'
                    implementation 'org.test:constrained'
                    implementation 'org.test:forced'
                    constraints { implementation 'org.test:constrained:1.0.0' }
                    implementation 'org.test:mixed'
                    separate 'org.test:mixed:1.0.0'
                }
                depLens { rules { gradle { pinned = true }; platform('org.test:bom') { pinned = true } } }
                """);
        String output = plain(run("depLensCheckDependencies"));
        JsonObject report = report("depLensCheckDependencies");
        assertThat(entry(report, "org.test:managed", ":").getAsJsonArray("statuses").toString()).contains("BOM_MANAGED");
        for (String module : List.of("constrained", "mixed", "forced")) {
            assertThat(entry(report, "org.test:" + module, ":").getAsJsonArray("statuses").toString()).contains("UPDATE_AVAILABLE").doesNotContain("BOM_MANAGED");
            assertThat(output).contains("• org.test:" + module + " ");
        }
        assertThat(output).doesNotContain("• org.test:managed ").contains("1 BOM-managed");
    }

    /**
     * Verifies that Spring boot aligns protobuf tools but explicit tool versions remain independent.
     */
    @Test
    void springBootAlignsProtobufToolsButExplicitToolVersionsRemainIndependent() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(org.gradle.util.GradleVersion.version(System.getProperty("deplens.test.gradleVersion"))
                .compareTo(org.gradle.util.GradleVersion.version("8.14")) >= 0, "Spring Boot 4.1 requires Gradle 8.14+");
        for (String coordinate : List.of("com.google.protobuf:protobuf-java", "com.google.protobuf:protoc",
                "io.grpc:grpc-util", "io.grpc:protoc-gen-grpc-java")) {
            String[] parts = coordinate.split(":");
            modules(parts[0], parts[1], "1.0.0", "1.1.0", "2.0.0");
        }
        modules("org.springframework.boot", "spring-boot-dependencies", "4.1.1");
        write("repo/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom", """
                <project><modelVersion>4.0.0</modelVersion><groupId>org.springframework.boot</groupId><artifactId>spring-boot-dependencies</artifactId><version>4.1.1</version><packaging>pom</packaging>
                <dependencyManagement><dependencies>
                <dependency><groupId>com.google.protobuf</groupId><artifactId>protobuf-java</artifactId><version>1.0.0</version></dependency>
                <dependency><groupId>io.grpc</groupId><artifactId>grpc-util</artifactId><version>1.0.0</version></dependency>
                <dependency><groupId>io.grpc</groupId><artifactId>protoc-gen-grpc-java</artifactId><version>1.0.0</version></dependency>
                </dependencies></dependencyManagement></project>
                """);
        String classpath = Arrays.stream(System.getProperty("deplens.test.springProtobufClasspath").split(java.io.File.pathSeparator))
                .map(path -> "'" + path.replace("\\", "\\\\").replace("'", "\\'") + "'")
                .collect(java.util.stream.Collectors.joining(", "));
        write("build.gradle", """
                buildscript { dependencies { classpath files(%s) } }
                plugins { id 'java'; id 'com.sneedit.deplens' }
                apply plugin: 'org.springframework.boot'
                apply plugin: 'io.spring.dependency-management'
                apply plugin: 'com.google.protobuf'
                repositories { maven { url = uri('repo') } }
                protobuf { plugins { grpc {} } }
                dependencies { implementation 'com.google.protobuf:protobuf-java'; implementation 'io.grpc:grpc-util' }
                depLens { rules { gradle { pinned = true } } }
                """.formatted(classpath));
        String output = plain(run("depLensCheck"));
        JsonObject report = report("depLensCheck");
        assertThat(report.get("checksComplete").getAsBoolean()).isTrue();
        for (String coordinate : List.of("com.google.protobuf:protoc", "io.grpc:protoc-gen-grpc-java")) {
            JsonObject tool = entry(report, coordinate, ":");
            assertThat(tool.getAsJsonArray("statuses").toString()).contains("BOM_MANAGED").doesNotContain("UPDATE_AVAILABLE", "NOT_CHECKED");
            assertThat(tool.getAsJsonArray("declaredSelectors")).isEmpty();
            assertThat(tool.getAsJsonArray("declarations").toString()).doesNotContain("\"selector\":\"null\"");
            assertThat(tool.getAsJsonObject("versionAlignments").size()).isEqualTo(1);
            assertThat(tool.get("recommendedVersion").isJsonNull()).isTrue();
            assertThat(output).doesNotContain("• " + coordinate + " ");
        }
        // Explicit runtime overrides still control the automatic tools, without inventing BOM ownership.
        Files.writeString(dir.resolve("build.gradle"), "\ndependencies { implementation 'com.google.protobuf:protobuf-java:1.1.0'; implementation 'io.grpc:grpc-util:1.1.0' }\n", StandardOpenOption.APPEND);
        output = plain(run("depLensCheck"));
        report = report("depLensCheck");
        for (String coordinate : List.of("com.google.protobuf:protoc", "io.grpc:protoc-gen-grpc-java")) {
            JsonObject tool = entry(report, coordinate, ":");
            assertThat(tool.getAsJsonArray("statuses").toString()).contains("VERSION_ALIGNED").doesNotContain("UPDATE_AVAILABLE", "NOT_CHECKED");
            assertThat(tool.getAsJsonArray("resolutions").toString()).contains("1.1.0");
            assertThat(tool.getAsJsonObject("bomOwners").size()).isZero();
            assertThat(output).doesNotContain("• " + coordinate + " ");
        }
        assertThat(output).contains("2 version-aligned");
        // Even explicitly setting the same version as the managed default keeps tools independently checked.
        Files.writeString(dir.resolve("build.gradle"), """
                protobuf {
                    protoc { artifact = 'com.google.protobuf:protoc:1.0.0' }
                    plugins { grpc { artifact = 'io.grpc:protoc-gen-grpc-java:1.0.0' } }
                }
                """, StandardOpenOption.APPEND);
        output = plain(run("depLensCheck"));
        report = report("depLensCheck");
        assertThat(report.get("checksComplete").getAsBoolean()).isTrue();
        for (String coordinate : List.of("com.google.protobuf:protoc", "io.grpc:protoc-gen-grpc-java")) {
            JsonObject tool = entry(report, coordinate, ":");
            assertThat(tool.getAsJsonArray("statuses").toString()).contains("UPDATE_AVAILABLE").doesNotContain("BOM_MANAGED", "VERSION_ALIGNED");
            assertThat(tool.getAsJsonArray("declaredSelectors").toString()).contains("1.0.0");
            assertThat(tool.getAsJsonObject("versionAlignments").size()).isZero();
            assertThat(output).contains("• " + coordinate + " 1.0.0 → 2.0.0");
        }
    }

    /**
     * Verifies that Spring boot Kotlin alignment preserves overrides and plugin updates.
     */
    @Test
    void springBootKotlinAlignmentPreservesOverridesAndPluginUpdates() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(org.gradle.util.GradleVersion.version(System.getProperty("deplens.test.gradleVersion"))
                .compareTo(org.gradle.util.GradleVersion.version("8.14")) >= 0, "Spring Boot 4.1 requires Gradle 8.14+");
        for (String module : List.of("kotlin-stdlib", "kotlin-reflect", "explicit"))
            modules("org.jetbrains.kotlin", module, "2.1.0", "2.2.0", "2.3.0");
        modules("org.springframework.boot", "spring-boot-dependencies", "4.1.1");
        modules("org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin.jvm.gradle.plugin", "2.2.0", "2.3.0");
        write("repo/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom", """
                <project><modelVersion>4.0.0</modelVersion><groupId>org.springframework.boot</groupId><artifactId>spring-boot-dependencies</artifactId><version>4.1.1</version><packaging>pom</packaging>
                <properties><kotlin.version>2.1.0</kotlin.version></properties><dependencyManagement><dependencies>
                <dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-stdlib</artifactId><version>${kotlin.version}</version></dependency>
                <dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-reflect</artifactId><version>${kotlin.version}</version></dependency>
                <dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>explicit</artifactId><version>${kotlin.version}</version></dependency>
                </dependencies></dependencyManagement></project>
                """);
        String classpath = Arrays.stream(System.getProperty("deplens.test.springKotlinClasspath").split(java.io.File.pathSeparator))
                .map(path -> "'" + path.replace("\\", "\\\\").replace("'", "\\'") + "'").collect(java.util.stream.Collectors.joining(", "));
        write("build.gradle", """
                buildscript {
                    repositories { maven { url = uri('repo') } }
                    dependencies { classpath files(%s); classpath 'org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:2.2.0' }
                }
                plugins { id 'java'; id 'com.sneedit.deplens' }
                apply plugin: 'org.springframework.boot'
                apply plugin: 'io.spring.dependency-management'
                apply plugin: 'org.jetbrains.kotlin.jvm'
                repositories { maven { url = uri('repo') } }
                configurations.configureEach {
                    if (name.startsWith('kotlin') && canBeResolved) {
                        canBeResolved = false
                    }
                }
                dependencies {
                    implementation 'org.jetbrains.kotlin:kotlin-reflect'
                    implementation 'org.jetbrains.kotlin:explicit:2.2.0'
                }
                depLens { rules { gradle { pinned = true } } }
                """.formatted(classpath));
        String output = plain(run("depLensCheck"));
        JsonObject report = report("depLensCheck");
        // Compiler tool configurations are intentionally unresolved in this metadata-only fixture.
        assertThat(report.getAsJsonArray("warnings").toString()).withFailMessage("%s", output).doesNotContain("Spring BOM provenance unavailable", "Update query failed");
        JsonObject reflect = entry(report, "org.jetbrains.kotlin:kotlin-reflect", ":");
        assertThat(reflect.getAsJsonArray("statuses").toString()).contains("VERSION_ALIGNED").doesNotContain("UPDATE_AVAILABLE", "NOT_CHECKED");
        assertThat(reflect.getAsJsonObject("versionAlignments").toString()).contains("plugin:org.jetbrains.kotlin.jvm");
        assertThat(output).doesNotContain("• org.jetbrains.kotlin:kotlin-reflect ");
        assertThat(output).contains("• org.jetbrains.kotlin:explicit 2.2.0 → 2.3.0", "• org.jetbrains.kotlin.jvm 2.2.0 → 2.3.0");
        assertThat(entry(report, "org.jetbrains.kotlin.jvm", ":").getAsJsonArray("statuses").toString()).contains("UPDATE_AVAILABLE");
        assertThat(entry(report, "org.jetbrains.kotlin:kotlin-stdlib", ":").getAsJsonArray("statuses").toString())
                .contains("VERSION_ALIGNED").doesNotContain("UPDATE_AVAILABLE", "NOT_CHECKED");
        assertThat(output).doesNotContain("• org.jetbrains.kotlin:kotlin-stdlib ");
        String automaticBuild = Files.readString(dir.resolve("build.gradle"));
        // An explicit constraint equal to the plugin version must remain independently checked.
        Files.writeString(dir.resolve("build.gradle"), automaticBuild + "\ndependencies { constraints { implementation 'org.jetbrains.kotlin:kotlin-reflect:2.2.0' } }\n");
        output = plain(run("depLensCheck"));
        assertThat(output).contains("• org.jetbrains.kotlin:kotlin-reflect 2.2.0 → 2.3.0", "• org.jetbrains.kotlin.jvm 2.2.0 → 2.3.0");
        for (JsonElement element : report("depLensCheck").getAsJsonArray("entries")) {
            JsonObject row = element.getAsJsonObject();
            if (row.get("coordinate").getAsString().equals("org.jetbrains.kotlin:kotlin-reflect"))
                assertThat(row.getAsJsonArray("statuses").toString()).contains("UPDATE_AVAILABLE").doesNotContain("VERSION_ALIGNED");
        }
        Files.writeString(dir.resolve("build.gradle"), automaticBuild);
        // An explicit property remains an override even if its value equals the Kotlin plugin version.
        Files.writeString(dir.resolve("build.gradle"), "\next['kotlin.version'] = '2.2.0'\n", StandardOpenOption.APPEND);
        output = plain(run("depLensCheck"));
        report = report("depLensCheck");
        assertThat(entry(report, "org.jetbrains.kotlin:kotlin-reflect", ":").getAsJsonArray("statuses").toString())
                .contains("UPDATE_AVAILABLE").doesNotContain("VERSION_ALIGNED", "BOM_MANAGED");
        assertThat(output).contains("• org.jetbrains.kotlin:kotlin-reflect 2.2.0 → 2.3.0", "• org.jetbrains.kotlin.jvm 2.2.0 → 2.3.0");
        Files.writeString(dir.resolve("build.gradle"), automaticBuild);
        write("gradle.properties", "kotlin.version=2.2.0\n");
        output = plain(run("depLensCheck"));
        assertThat(output).contains("• org.jetbrains.kotlin:kotlin-reflect 2.2.0 → 2.3.0", "• org.jetbrains.kotlin.jvm 2.2.0 → 2.3.0");
        // An override equal to the published BOM default is still explicit.
        write("gradle.properties", "kotlin.version=2.1.0\n");
        run("depLensCheck");
        for (JsonElement element : report("depLensCheck").getAsJsonArray("entries")) {
            JsonObject row = element.getAsJsonObject();
            if (row.get("coordinate").getAsString().equals("org.jetbrains.kotlin:kotlin-reflect"))
                assertThat(row.getAsJsonArray("statuses").toString()).contains("UPDATE_AVAILABLE").doesNotContain("VERSION_ALIGNED", "BOM_MANAGED");
        }
    }

    /**
     * Runs the requested task against the fixture project.
     */
    private BuildResult run(String task, String... options) {
        var arguments = new ArrayList<>(List.of(task, "--offline", "--stacktrace"));
        arguments.addAll(List.of(options));
        return GradleRunner.create().withGradleVersion(System.getProperty("deplens.test.gradleVersion"))
                .withProjectDir(dir.toFile()).withPluginClasspath().withArguments(arguments).build();
    }

    /**
     * Reads the JSON report written by the fixture build.
     */
    private JsonObject report(String task) throws Exception {
        return JsonParser.parseString(Files.readString(dir.resolve("build/reports/deplens/" + task + ".json"))).getAsJsonObject();
    }

    /**
     * Finds or creates the component record used by the test.
     */
    private JsonObject entry(JsonObject report, String coordinate, String project) {
        for (JsonElement element : report.getAsJsonArray("entries")) {
            JsonObject row = element.getAsJsonObject();
            if (coordinate.equals(row.get("coordinate").getAsString()) && project.equals(row.get("project").getAsString()))
                return row;
        }
        throw new AssertionError("Missing " + coordinate + " " + project);
    }

    /**
     * Publishes the requested versions into the local test repository.
     */
    private void modules(String group, String name, String... versions) throws Exception {
        String base = "repo/" + group.replace('.', '/') + "/" + name + "/";
        for (String version : versions) {
            String stem = base + version + "/" + name + "-" + version;
            write(stem + ".pom", "<project><modelVersion>4.0.0</modelVersion><groupId>" + group + "</groupId><artifactId>" + name + "</artifactId><version>" + version + "</version></project>");
            try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(dir.resolve(stem + ".jar")))) {
            }
        }
        write(base + "maven-metadata.xml", "<metadata><groupId>" + group + "</groupId><artifactId>" + name + "</artifactId><versioning><versions>" + String.join("", Arrays.stream(versions).map(v -> "<version>" + v + "</version>").toList()) + "</versions></versioning></metadata>");
    }

    /**
     * Writes fixture text under the temporary project directory.
     */
    private void write(String path, String text) throws Exception {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
