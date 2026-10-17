/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.tasks;

import com.sneedit.deplens.DepLensExtension;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for CheckDependencyUpdatesTask.
 */
class CheckDependencyUpdatesTaskTest {

    private Project project;
    private CheckDependencyUpdatesTask task;

    /**
     * Creates a fresh project fixture for each test.
     */
    @BeforeEach
    void setup() {
        project = ProjectBuilder.builder().build();
        project.getPlugins().apply("com.sneedit.deplens");
        task = (CheckDependencyUpdatesTask) project.getTasks()
                .getByName("depLensCheckDependencies");
    }

    /**
     * Verifies that task is configured.
     */
    @Test
    void taskIsConfigured() {
        assertThat(task).isNotNull();
        assertThat(task.getGroup()).isEqualTo("deplens");
        assertThat(task.getDescription()).contains("dependency updates");
    }

    /**
     * Verifies that analysis defaults detach configured pre releases.
     */
    @Test
    void analysisDefaultsDetachConfiguredPreReleases() {
        DepLensExtension extension = project.getExtensions()
                .getByType(DepLensExtension.class);
        extension.getDependencies().getIncludePreReleases().set(true);

        var snapshot = com.sneedit.deplens.resolution.AnalysisDefaults.capture(extension);
        extension.getDependencies().getIncludePreReleases().set(false);
        assertThat(snapshot.dependencyPre).isTrue();
    }

    /**
     * Verifies that analysis defaults detach configured pins.
     */
    @Test
    void analysisDefaultsDetachConfiguredPins() {
        DepLensExtension extension = project.getExtensions()
                .getByType(DepLensExtension.class);

        extension.getDependencies().getAllowedUpdates().set("minor");
        extension.getDependencies().getPinCurrentVersions().set(java.util.List.of("org.example:lib"));

        var snapshot = com.sneedit.deplens.resolution.AnalysisDefaults.capture(extension);
        extension.getDependencies().getAllowedUpdates().set("major");
        extension.getDependencies().getPinCurrentVersions().set(java.util.List.of());
        assertThat(snapshot.dependencyAllowed).isEqualTo("minor");
        assertThat(snapshot.dependencyPins).containsExactly("org.example:lib");
    }
}
