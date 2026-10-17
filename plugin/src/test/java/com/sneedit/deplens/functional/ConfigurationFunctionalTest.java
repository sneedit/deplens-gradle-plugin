/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.functional;

import com.google.gson.JsonParser;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static com.sneedit.deplens.functional.ConsoleOutput.plain;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;

/**
 * Public DSL examples and migration errors, without external repositories.
 */
class ConfigurationFunctionalTest {
    @TempDir
    Path directory;

    /**
     * Creates a Gradle TestKit runner for the fixture project.
     */
    private GradleRunner runner(String... arguments) {
        return GradleRunner.create().withGradleVersion(System.getProperty("deplens.test.gradleVersion"))
                .withProjectDir(directory.toFile()).withPluginClasspath().withArguments(arguments);
    }

    /**
     * Verifies that Kotlin dsl configuration.
     */
    @Test
    void kotlinDslConfiguration() throws IOException {
        Files.writeString(directory.resolve("settings.gradle.kts"), "rootProject.name = \"configuration\"");
        Files.writeString(directory.resolve("build.gradle.kts"), """
                plugins { java; id("com.sneedit.deplens") }
                depLens {
                    consoleMode.set("compact")
                    checkResolvableConfigurationFilter { includes.set(listOf(".*Classpath")); excludes.set(listOf("test.*")) }
                    outputDeclaredConfigurationFilter { includes.set(listOf(".*")); excludes.set(listOf("tooling.*")) }
                    failIfChecksIncomplete.set(true)
                    checkIncludedBuilds.set(false)
                    checkBuildSrc.set(false)
                    dependencies {
                        includePreReleases.set(false)
                        allowedUpdates.set("minor")
                        failIfUpdatesFound.set(false)
                        pinCurrentVersions.set(listOf("org.example:legacy"))
                        ignoredVersions.set(mapOf("org.example:lib" to listOf("2.0.0")))
                    }
                    plugins {
                        includePreReleases.set(false)
                        allowedUpdates.set("minor")
                        failIfUpdatesFound.set(false)
                        pinCurrentVersions.set(listOf("org.example.plugin"))
                        ignoredVersions.set(mapOf("org.example.plugin" to listOf("2.0.0")))
                    }
                    gradle {
                        pinCurrentVersion.set(true)
                        includePreReleases.set(false)
                        allowedUpdates.set("minor")
                        failIfUpdatesFound.set(false)
                        versionsUrl.set("https://services.gradle.org/versions/all")
                    }
                    rules {
                        global { includePreReleases.set(false); ignoredVersionPatterns.set(listOf("(?i)-redhat")) }
                        project(":app") { allowedUpdates.set("minor") }
                        group("org.example") { allowedUpdates.set("patch") }
                        dependency("org.example:lib") {
                            ignoredVersions.set(listOf("2.0.0"))
                            ignoredVersionPatterns.set(emptyList())
                            versionPattern.set("(?<major>[0-9]+)[.](?<minor>[0-9]+)[.](?<patch>[0-9]+)")
                            preReleasePattern.set("(?i)-eap[0-9]+$")
                        }
                        platform("org.example:bom") { pinned.set(true) }
                        plugin("org.example.plugin") {
                            pinned.set(true)
                            versionPattern.set("(?<major>[0-9]+)[.](?<minor>[0-9]+)[.](?<patch>[0-9]+)")
                            preReleasePattern.set("(?i)-eap[0-9]+$")
                        }
                        gradle { allowedUpdates.set("minor") }
                    }
                }
                """);
        var result = runner("depLensCheckDependencies", "--offline", "--stacktrace").build();
        assertThat(result.task(":depLensCheckDependencies").getOutcome()).isEqualTo(SUCCESS);
        var report = JsonParser.parseString(Files.readString(directory.resolve("build/reports/deplens/depLensCheckDependencies.json"))).getAsJsonObject();
        assertThat(report.get("checksComplete").getAsBoolean()).isTrue();
    }

    /**
     * Verifies that always writes JSON report without an output format setting.
     */
    @Test
    void alwaysWritesJsonReportWithoutAnOutputFormatSetting() throws IOException {
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name = 'configuration'");
        Files.writeString(directory.resolve("build.gradle"), """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                depLens { checkIncludedBuilds = false; checkBuildSrc = false }
                """);
        var result = runner("depLensCheckDependencies", "--offline", "--stacktrace").build();
        assertThat(plain(result)).contains("DepLens: 0 recommended updates, 0 available only");
        var report = JsonParser.parseString(Files.readString(directory.resolve("build/reports/deplens/depLensCheckDependencies.json"))).getAsJsonObject();
        assertThat(report.getAsJsonArray("entries")).isNotNull();
        assertThat(report.get("checksComplete").getAsBoolean()).isTrue();
    }

    /**
     * Verifies that invalid update tiers fail for every section.
     */
    @Test
    void invalidUpdateTiersFailForEverySection() throws IOException {
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name = 'invalid-tier'");
        for (String section : List.of("dependencies", "plugins", "gradle")) {
            Files.writeString(directory.resolve("build.gradle"),
                    "plugins { id 'com.sneedit.deplens' }\ndepLens { " + section + " { allowedUpdates = 'banana' } }\n");
            assertThat(plain(runner("help", "--offline").buildAndFail()))
                    .contains("allowedUpdates must be patch, minor or major", "banana");
        }
    }

    /** Both filters validate their patterns before an analysis task is requested. */
    @Test
    void invalidConfigurationFiltersFailBuildConfiguration() throws IOException {
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name = 'bad-filter'");
        for (String filter : List.of("checkResolvableConfigurationFilter", "outputDeclaredConfigurationFilter")) {
            for (String field : List.of("includes", "excludes")) {
                Files.writeString(directory.resolve("build.gradle"), "plugins { id 'com.sneedit.deplens' }\ndepLens { " + filter + " { " + field + " = ['['] } }\n");
                assertThat(plain(runner("help", "--offline").buildAndFail())).contains("Invalid " + filter + "." + field + " regex");
            }
        }
    }

    /** Malformed exclusion regexes fail configuration with an actionable field name. */
    @Test
    void invalidIgnoredVersionPatternsFailBuildConfiguration() throws IOException {
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name = 'bad-exclusions'");
        Files.writeString(directory.resolve("build.gradle"), "plugins { id 'com.sneedit.deplens' }\ndepLens { rules { global { ignoredVersionPatterns = ['['] } } }");
        assertThat(plain(runner("help", "--offline").buildAndFail())).contains("Invalid ignoredVersionPatterns");
    }

    /**
     * Verifies that invalid patterns fail build configuration.
     */
    @Test
    void invalidPatternsFailBuildConfiguration() throws IOException {
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name = 'bad-patterns'");
        for (String scope : List.of("dependency('org.example:lib')", "plugin('org.example.plugin')")) {
            for (String field : List.of("versionPattern", "preReleasePattern")) {
                Files.writeString(directory.resolve("build.gradle"), "plugins { id 'com.sneedit.deplens' }\ndepLens { rules { " + scope + " { " + field + " = '[' } } }");
                assertThat(plain(runner("help", "--offline").buildAndFail())).contains("Invalid " + field);
            }
        }
        Files.writeString(directory.resolve("build.gradle"), "plugins { id 'com.sneedit.deplens' }\ndepLens { rules { dependency('org.example:lib') { versionPattern = '(.*)' } } }");
        assertThat(plain(runner("help", "--offline").buildAndFail())).contains("requires named groups major, minor and patch");
    }

    /**
     * Verifies that removed options fail configuration instead of being silently ignored.
     */
    @Test
    void removedOptionsFailConfigurationInsteadOfBeingSilentlyIgnored() throws IOException {
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name = 'migration'");
        for (String assignment : List.of("showPinnedUpdates = true", "outputFormat = 'json'",
                "dependencies { showRepositoryUrls = true }", "dependencies { maxParallelRequests = 5 }",
                "dependencies { requestDelayMs = 50 }", "dependencies { additionalRepositories = [] }",
                "plugins { showRepositoryUrls = true }", "plugins { requestDelayMs = 50 }",
                "dependencies { minorOnly = true }", "plugins { minorOnly = true }", "gradle { minorOnly = true }")) {
            Files.writeString(directory.resolve("build.gradle"),
                    "plugins { id 'com.sneedit.deplens' }\ndepLens { " + assignment + " }\n");
            var result = runner("help", "--offline").buildAndFail();
            String property = assignment.replaceFirst("^(dependencies|plugins|gradle) \\{ ", "").split(" =")[0];
            assertThat(plain(result)).contains("unknown property", property);
        }
    }
}
