/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for DepLensPlugin.
 */
class DepLensPluginTest {

    private Project project;

    /**
     * Creates a fresh project fixture for each test.
     */
    @BeforeEach
    void setup() {
        project = ProjectBuilder.builder().build();
        project.getPlugins().apply("com.sneedit.deplens");
    }

    /**
     * Verifies that plugin registers extension.
     */
    @Test
    void pluginRegistersExtension() {
        assertThat(project.getExtensions().findByName("depLens"))
                .isNotNull()
                .isInstanceOf(DepLensExtension.class);
    }

    /**
     * Verifies that plugin registers tasks.
     */
    @Test
    void pluginRegistersTasks() {
        assertThat(project.getTasks().findByName("depLensCheckDependencies")).isNotNull();
        assertThat(project.getTasks().findByName("depLensCheckPlugins")).isNotNull();
        assertThat(project.getTasks().findByName("depLensCheckGradle")).isNotNull();
        assertThat(project.getTasks().findByName("depLensCheck")).isInstanceOf(com.sneedit.deplens.tasks.CheckUpdatesTask.class);
        assertThat(project.getTasks().findByName("depLensInventory")).isNotNull();
        assertThat(project.getTasks().getNames()).doesNotContain("checkUpdates", "checkDependencyUpdates",
                "checkPluginUpdates", "checkGradleUpdates", "depLensUpdate");
    }

    /**
     * Verifies that extension has default values.
     */
    @Test
    void extensionHasDefaultValues() {
        DepLensExtension ext = project.getExtensions()
                .getByType(DepLensExtension.class);

        assertThat(ext.getPlugins().getIncludePreReleases().get()).isFalse();
        assertThat(ext.getPlugins().getAllowedUpdates().get()).isEqualTo("major");
        assertThat(ext.getDependencies().getAllowedUpdates().get()).isEqualTo("major");
        assertThat(ext.getGradle().getAllowedUpdates().get()).isEqualTo("major");
        assertThat(ext.getFailIfUpdatesFound().get()).isFalse();
        assertThat(ext.getCheckIncludedBuilds().get()).isTrue();
        assertThat(ext.getCheckBuildSrc().get()).isTrue();
        assertThat(ext.getConsoleMode().get()).isEqualTo("compact");
    }

    /**
     * Verifies that tasks have correct group.
     */
    @Test
    void tasksHaveCorrectGroup() {
        assertThat(project.getTasks().getByName("depLensCheckDependencies").getGroup())
                .isEqualTo("deplens");
        assertThat(project.getTasks().getByName("depLensCheckPlugins").getGroup())
                .isEqualTo("deplens");
        assertThat(project.getTasks().getByName("depLensCheckGradle").getGroup())
                .isEqualTo("deplens");
        assertThat(project.getTasks().getByName("depLensCheck").getGroup())
                .isEqualTo("deplens");
    }

    /**
     * Verifies that the overall check executes directly without an alias.
     */
    @Test
    void depLensCheckExecutesDirectly() {
        var depLensCheckTask = project.getTasks().getByName("depLensCheck");
        var dependencies = depLensCheckTask.getDependsOn();

        // Partial producer dependencies are wired lazily through report inputs.
        assertThat(dependencies).isEmpty();
    }
}
