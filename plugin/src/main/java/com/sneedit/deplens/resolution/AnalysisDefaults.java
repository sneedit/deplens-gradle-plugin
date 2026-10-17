/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.resolution;

import com.sneedit.deplens.DepLensExtension;
import com.sneedit.deplens.updates.UpdateRules;

import java.util.List;
import java.util.Map;

/**
 * Values inherited by projects that have no explicit DepLens extension.
 */
public final class AnalysisDefaults {
    public String dependencyAllowed, pluginAllowed, gradleAllowed, versionsUrl;
    public boolean dependencyPre, pluginPre, gradlePre, dependencyFail, pluginFail, gradleFail, gradlePin, included, buildSrc;
    public List<String> dependencyPins, pluginPins;
    public Map<String, List<String>> dependencyIgnores, pluginIgnores;
    public List<UpdateRules.Spec> rules;
    public boolean checkBuildContext = true;
    public List<String> configurationIncludes, configurationExcludes;

    /**
     * Copies extension settings and rules into detached defaults.
     */
    private AnalysisDefaults(DepLensExtension e) {
        this.dependencyAllowed = e.getDependencies().getAllowedUpdates().get();
        this.pluginAllowed = e.getPlugins().getAllowedUpdates().get();
        this.gradleAllowed = e.getGradle().getAllowedUpdates().get();
        this.dependencyPre = e.getDependencies().getIncludePreReleases().get();
        this.pluginPre = e.getPlugins().getIncludePreReleases().get();
        this.gradlePre = e.getGradle().getIncludePreReleases().get();
        this.dependencyFail = e.getDependencies().getFailIfUpdatesFound().get();
        this.pluginFail = e.getPlugins().getFailIfUpdatesFound().get();
        this.gradleFail = e.getGradle().getFailIfUpdatesFound().get();
        this.gradlePin = e.getGradle().getPinCurrentVersion().get();
        this.versionsUrl = e.getGradle().getVersionsUrl().get();
        this.dependencyPins = e.getDependencies().getPinCurrentVersions().get();
        this.pluginPins = e.getPlugins().getPinCurrentVersions().get();
        this.dependencyIgnores = e.getDependencies().getIgnoredVersions().get();
        this.pluginIgnores = e.getPlugins().getIgnoredVersions().get();
        this.included = e.getCheckIncludedBuilds().get();
        this.buildSrc = e.getCheckBuildSrc().get();
        this.rules = e.getRules().snapshot();
        this.configurationIncludes = e.getCheckResolvableConfigurationFilter().getIncludes().get();
        this.configurationExcludes = e.getCheckResolvableConfigurationFilter().getExcludes().get();
        this.checkBuildContext = e.getCheckBuildContext().get();
    }

    /**
     * Explicit project settings override inherited lists, including an explicit empty list.
     */
    public void inheritCheckResolvableConfigurationFilter(DepLensExtension e) {
        e.getCheckResolvableConfigurationFilter().getIncludes().convention(configurationIncludes);
        e.getCheckResolvableConfigurationFilter().getExcludes().convention(configurationExcludes);
    }

    /**
     * Captures root settings for projects and separate builds that inherit defaults.
     *
     * @param e component being inspected or configured
     * @return detached policy defaults
     */
    public static AnalysisDefaults capture(DepLensExtension e) {
        return new AnalysisDefaults(e);
    }

    /**
     * Applies captured defaults to an analysis extension.
     *
     * @param e component being inspected or configured
     */
    public void apply(DepLensExtension e) {
        e.getDependencies().getAllowedUpdates().set(dependencyAllowed);
        e.getPlugins().getAllowedUpdates().set(pluginAllowed);
        e.getGradle().getAllowedUpdates().set(gradleAllowed);
        e.getDependencies().getIncludePreReleases().set(dependencyPre);
        e.getPlugins().getIncludePreReleases().set(pluginPre);
        e.getGradle().getIncludePreReleases().set(gradlePre);
        e.getDependencies().getFailIfUpdatesFound().set(dependencyFail);
        e.getPlugins().getFailIfUpdatesFound().set(pluginFail);
        e.getGradle().getFailIfUpdatesFound().set(gradleFail);
        e.getGradle().getPinCurrentVersion().set(gradlePin);
        e.getGradle().getVersionsUrl().set(versionsUrl);
        e.getDependencies().getPinCurrentVersions().set(dependencyPins);
        e.getPlugins().getPinCurrentVersions().set(pluginPins);
        e.getDependencies().getIgnoredVersions().set(dependencyIgnores);
        e.getPlugins().getIgnoredVersions().set(pluginIgnores);
        e.getCheckIncludedBuilds().set(included);
        e.getCheckBuildSrc().set(buildSrc);
        inheritCheckResolvableConfigurationFilter(e);
        e.getCheckBuildContext().set(checkBuildContext);
    }
}
