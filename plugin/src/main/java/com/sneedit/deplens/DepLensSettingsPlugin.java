/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens;

import org.gradle.api.IsolatedAction;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.initialization.Settings;

/**
 * Settings entry point; the isolated callback captures no live settings or project model.
 */
public final class DepLensSettingsPlugin implements Plugin<Settings> {
    public static final String CONTEXT = "deplens.settingsSnapshot";

    /**
     * Captures the settings context and arranges project plugin application.
     *
     * @param settings Gradle settings model
     */
    @Override
    public void apply(Settings settings) {
        settings.getBuildscript().getConfigurations().configureEach(com.sneedit.deplens.resolution.AnalysisModel::retainRules);
        settings.getGradle().getLifecycle().beforeProject(new ApplyProjectPlugin());
    }

    /**
     * An isolated lifecycle action that applies DepLens to a project.
     */
    private static final class ApplyProjectPlugin implements IsolatedAction<Project> {
        /**
         * Applies DepLens to the project through Gradle's isolated lifecycle callback.
         *
         * @param project Gradle project being inspected
         */
        @Override
        public void execute(Project project) {
            if (project.getPath().equals(":")) project.getPluginManager().apply(DepLensPlugin.class);
        }
    }
}
