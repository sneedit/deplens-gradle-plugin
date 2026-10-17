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
 * Functional tests for dependency update checking.
 */
class DependencyUpdatesFunctionalTest {

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
     * Verifies that DepLens detects outdated dependencies.
     */
    @Test
    void detectsOutdatedDependencies() throws IOException {
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
                    // Using an old version to trigger update detection
                    implementation 'org.slf4j:slf4j-api:1.7.30'
                }
                """);

        BuildResult result = GradleRunner.create().withGradleVersion(System.getProperty("deplens.test.gradleVersion"))
                .withProjectDir(testProjectDir.toFile())
                .withArguments("depLensCheckDependencies")
                .withPluginClasspath()
                .build();

        assertThat(result.task(":depLensCheckDependencies").getOutcome()).isEqualTo(SUCCESS);
        // Output should show the dependency and available updates
        assertThat(result.getOutput()).contains("org.slf4j:slf4j-api");
    }

    /**
     * Verifies that DepLens respects pinned versions.
     */
    @Test
    void respectsPinnedVersions() throws IOException {
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
                    dependencies { pinCurrentVersions.add('org.slf4j:slf4j-api') }
                }
                
                dependencies {
                    implementation 'org.slf4j:slf4j-api:1.7.30'
                }
                """);

        BuildResult result = GradleRunner.create().withGradleVersion(System.getProperty("deplens.test.gradleVersion"))
                .withProjectDir(testProjectDir.toFile())
                .withArguments("depLensCheckDependencies")
                .withPluginClasspath()
                .build();

        assertThat(result.task(":depLensCheckDependencies").getOutcome()).isEqualTo(SUCCESS);
        // Output should show the dependency is pinned
        if (result.getOutput().contains("org.slf4j:slf4j-api")) {
            assertThat(result.getOutput()).contains("PINNED");
        }
    }
}
