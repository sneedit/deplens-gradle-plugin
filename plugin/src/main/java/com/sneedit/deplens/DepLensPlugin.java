/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens;

import com.sneedit.deplens.resolution.AnalysisModel;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.configuration.BuildFeatures;
import org.gradle.util.GradleVersion;

import javax.inject.Inject;

import static com.sneedit.deplens.inventory.NestedInventorySupport.CHECK_BUILD_SRC;
import static com.sneedit.deplens.inventory.NestedInventorySupport.CHECK_INCLUDED_BUILDS;

/**
 * Registers model-free analysis tasks. Gradle obtains each project's input through its native model boundary.
 */
public final class DepLensPlugin implements Plugin<Project> {
    private final BuildFeatures features;

    /**
     * Stores Gradle's build feature service for plugin setup.
     *
     * @param features Gradle build feature service
     */
    @Inject
    public DepLensPlugin(BuildFeatures features) {
        this.features = features;
    }

    /**
     * Registers the extension and inventory, check and interactive update tasks.
     *
     * @param project Gradle project being inspected
     */
    @Override
    public void apply(Project project) {
        if (GradleVersion.current().compareTo(GradleVersion.version("8.8")) < 0)
            throw new GradleException("DepLens requires Gradle 8.8 or newer.");
        project.getConfigurations().configureEach(AnalysisModel::retainRules);
        project.getBuildscript().getConfigurations().configureEach(AnalysisModel::retainRules);
        var extension = project.getExtensions().create("depLens", DepLensExtension.class);
        extension.getCheckIncludedBuilds().convention(project.getProviders().gradleProperty(CHECK_INCLUDED_BUILDS).map(Boolean::parseBoolean).orElse(true));
        extension.getCheckBuildSrc().convention(project.getProviders().gradleProperty(CHECK_BUILD_SRC).map(Boolean::parseBoolean).orElse(true));
        DepLensTaskConfigurer.configure(project, extension, features);
        DepLensTaskRegistrar.registerTasks(project);
    }
}
