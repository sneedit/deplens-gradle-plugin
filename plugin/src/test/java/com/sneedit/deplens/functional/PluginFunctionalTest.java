/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.functional;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;

/**
 * Functional tests using Gradle TestKit.
 * Tests the plugin in a real Gradle build.
 */
class PluginFunctionalTest {

    @TempDir
    Path testProjectDir;

    private Path buildFile;
    private Path settingsFile;

    /**
     * Creates a fresh project fixture for each test.
     */
    @BeforeEach
    void setup() {
        buildFile = testProjectDir.resolve("build.gradle");
        settingsFile = testProjectDir.resolve("settings.gradle");
    }

    /**
     * Verifies that can apply plugin.
     */
    @Test
    void canApplyPlugin() throws IOException {
        Files.writeString(settingsFile, "rootProject.name = 'test-project'");

        Files.writeString(buildFile, """
                plugins {
                    id 'java'
                    id 'com.sneedit.deplens'
                }
                
                repositories {
                    mavenCentral()
                }
                
                dependencies {
                    implementation 'com.google.guava:guava:30.0-jre'
                }
                """);

        BuildResult result = GradleRunner.create().withGradleVersion(System.getProperty("deplens.test.gradleVersion"))
                .withProjectDir(testProjectDir.toFile())
                .withArguments("tasks", "--group", "deplens")
                .withPluginClasspath()
                .build();

        assertThat(result.getOutput()).contains("depLensCheckDependencies");
        assertThat(result.getOutput()).contains("depLensCheckPlugins");
        assertThat(result.getOutput()).contains("depLensCheckGradle");
        assertThat(result.getOutput()).contains("depLensCheck").doesNotContain("checkUpdates");
    }

    /**
     * Verifies that can configure extension.
     */
    @Test
    void canConfigureExtension() throws IOException {
        Files.writeString(settingsFile, "rootProject.name = 'test-project'");

        Files.writeString(buildFile, """
                plugins {
                    id 'java'
                    id 'com.sneedit.deplens'
                }
                
                repositories {
                    mavenCentral()
                }
                
                depLens {
                    dependencies {
                        includePreReleases = true
                        allowedUpdates = 'minor'
                    }
                }
                
                dependencies {
                    implementation 'com.google.guava:guava:30.0-jre'
                }
                """);

        BuildResult result = GradleRunner.create().withGradleVersion(System.getProperty("deplens.test.gradleVersion"))
                .withProjectDir(testProjectDir.toFile())
                .withArguments("tasks")
                .withPluginClasspath()
                .build();

        assertThat(result.task(":tasks").getOutcome()).isEqualTo(SUCCESS);
    }

    /**
     * Verifies that check dependency updates task runs.
     */
    @Test
    void depLensCheckDependenciesTaskRuns() throws IOException {
        Files.writeString(settingsFile, "rootProject.name = 'test-project'");

        Files.writeString(buildFile, """
                plugins {
                    id 'java'
                    id 'com.sneedit.deplens'
                }
                
                repositories {
                    mavenCentral()
                }
                
                dependencies {
                    implementation 'com.google.guava:guava:30.0-jre'
                }
                """);

        BuildResult result = GradleRunner.create().withGradleVersion(System.getProperty("deplens.test.gradleVersion"))
                .withProjectDir(testProjectDir.toFile())
                .withArguments("depLensCheckDependencies", "--stacktrace")
                .withPluginClasspath()
                .build();

        assertThat(result.task(":depLensCheckDependencies").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.getOutput()).contains("DepLens:");
    }
}
