/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.resolution;

import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.initialization.Settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Settings values shared with isolated project callbacks; contains no live settings model.
 */
public final class SettingsModel {
    /**
     * Dependency and plugin repositories captured from settings, in declaration order.
     */
    public List<RepositorySpec> repositories, pluginRepositories;
    /**
     * Settings dependency configurations indexed by name.
     */
    public Map<String, ConfigurationSpec> configurations = new LinkedHashMap<>();
    /**
     * Plugin management default versions indexed by plugin ID.
     */
    public Map<String, String> pluginVersions = new LinkedHashMap<>();
    /**
     * Dependency repository preference mode selected in settings.
     */
    public String repositoryMode;

    /**
     * Captures settings repositories, plugin versions and classpath configurations.
     */
    private SettingsModel(Settings settings) {
        repositoryMode = settings.getDependencyResolutionManagement().getRepositoriesMode().get().name();
        repositories = RepositorySpec.capture(settings.getDependencyResolutionManagement().getRepositories());
        pluginRepositories = RepositorySpec.capture(settings.getPluginManagement().getRepositories());
        if (pluginRepositories.isEmpty()) pluginRepositories.add(RepositorySpec.implicitPluginPortal());
    }

    /**
     * Returns the stored settings snapshot or captures it when first requested.
     *
     * @param settings Gradle settings model
     * @return shared detached settings snapshot
     */
    public static SettingsModel capture(Settings settings) {
        synchronized (settings) {
            var values = settings.getExtensions().getExtraProperties();
            String key = "com.sneedit.deplens.nativeSettingsModel";
            if (values.has(key))
                return Objects.requireNonNull((SettingsModel) values.get(key), "Missing DepLens settings model");
            var model = captureOnce(settings);
            values.set(key, model);
            return model;
        }
    }

    /**
     * Stores one settings snapshot for reuse by project analysis.
     */
    private static SettingsModel captureOnce(Settings settings) {
        SettingsModel m = new SettingsModel(settings);
        var scope = ResolutionRules.forSettings(settings.getGradle());
        settings.getBuildscript().getConfigurations().forEach(c -> m.configurations.put(c.getName(), ConfigurationSpec.capture(c, scope)));
        var classpath = settings.getBuildscript().getConfigurations().findByName("classpath");
        if (classpath != null)
            for (var edge : classpath.getIncoming().getResolutionResult().getRootComponent().get().getDependencies()) {
                if (edge instanceof ResolvedDependencyResult resolved && edge.getRequested() instanceof ModuleComponentSelector selector
                        && selector.getModule().endsWith(".gradle.plugin") && resolved.getSelected().getModuleVersion() != null)
                    m.pluginVersions.put(selector.getModule().replaceFirst("\\.gradle\\.plugin$", ""), resolved.getSelected().getModuleVersion().getVersion());
            }
        return m;
    }
}
