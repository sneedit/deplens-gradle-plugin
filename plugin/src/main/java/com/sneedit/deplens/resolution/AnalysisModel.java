/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.resolution;

import com.google.gson.Gson;
import com.sneedit.deplens.DepLensExtension;
import com.sneedit.deplens.inventory.BuildInventory;
import com.sneedit.deplens.inventory.InventoryCollector;
import com.sneedit.deplens.inventory.InventoryEntry;
import com.sneedit.deplens.updates.RulePolicy;
import com.sneedit.deplens.updates.UpdateRules;
import org.gradle.api.Project;

import java.util.*;

/**
 * Configuration-phase values. Stateful resolution rules are referenced through an invocation-local service.
 */
public final class AnalysisModel {
    public String build, project, inventory, versionsUrl;
    public boolean offline;
    public boolean checkBuildContext = true;
    public SchemaSpec schema, scriptSchema;
    public Set<InventoryEntry.Type> failTypes = new LinkedHashSet<>();
    public Map<String, RulePolicy> policies = new LinkedHashMap<>();
    public List<RepositorySpec> repositories = new ArrayList<>(), scriptRepositories = new ArrayList<>(), settingsRepositories = new ArrayList<>();
    public Map<String, ConfigurationSpec> configurations = new LinkedHashMap<>(), scriptConfigurations = new LinkedHashMap<>(), settingsConfigurations = new LinkedHashMap<>();

    /**
     * Captures project resolution settings alongside its serialized inventory.
     */
    private AnalysisModel(Project project, DepLensExtension extension, BuildInventory inventory) {
        build = project.getRootDir().getAbsolutePath();
        this.project = project.getPath();
        checkBuildContext = extension.getCheckBuildContext().get();
        versionsUrl = extension.getGradle().getVersionsUrl().get();
        this.inventory = new Gson().toJson(inventory);
        schema = SchemaSpec.capture(project.getDependencies().getAttributesSchema());
        scriptSchema = SchemaSpec.capture(project.getBuildscript().getDependencies().getAttributesSchema());
    }

    /**
     * Collects one project's inventory and policies into a detached analysis model.
     *
     * @param project   Gradle project being inspected
     * @param extension configured DepLens extension
     * @return detached project inventory and resolution settings
     */
    public static AnalysisModel capture(Project project, DepLensExtension extension) {
        return capture(project, extension, List.of());
    }

    /**
     * Collects one project's inventory and policies into a detached analysis model.
     *
     * @param project        Gradle project being inspected
     * @param extension      configured DepLens extension
     * @param inheritedRules rules inherited from the requesting build
     * @return detached project inventory and resolution settings
     */
    public static AnalysisModel capture(Project project, DepLensExtension extension, List<UpdateRules.Spec> inheritedRules) {
        project.getConfigurations().configureEach(AnalysisModel::retainRules);
        project.getBuildscript().getConfigurations().configureEach(AnalysisModel::retainRules);
        var filter = extension.getCheckResolvableConfigurationFilter();
        var selected = com.sneedit.deplens.ConfigurationFilter.predicate("checkResolvableConfigurationFilter",
                filter.getIncludes().get(), filter.getExcludes().get());
        boolean filtering = !filter.getIncludes().get().isEmpty() || !filter.getExcludes().get().isEmpty();
        BuildInventory inventory = new InventoryCollector(project, selected, filtering).collect();
        AnalysisModel m = new AnalysisModel(project, extension, inventory);
        m.offline = project.getGradle().getStartParameter().isOffline();
        if (extension.getGradle().getFailIfUpdatesFound().get()) m.failTypes.add(InventoryEntry.Type.GRADLE);
        if (extension.getPlugins().getFailIfUpdatesFound().get()) m.failTypes.add(InventoryEntry.Type.PLUGIN);
        if (extension.getDependencies().getFailIfUpdatesFound().get())
            m.failTypes.addAll(Set.of(InventoryEntry.Type.DEPENDENCY, InventoryEntry.Type.CONSTRAINT, InventoryEntry.Type.PLATFORM));
        List<UpdateRules.Spec> rules = new ArrayList<>(inheritedRules);
        rules.addAll(extension.getRules().snapshot());
        for (var entry : inventory.entries) {
            var policy = RulePolicy.resolve(entry, extension, rules);
            policy.ignored = new ArrayList<>(policy.ignored);
            policy.ignoredVersionPatterns = new ArrayList<>(policy.ignoredVersionPatterns);
            m.policies.put(entry.id(), policy);
        }
        m.repositories = RepositorySpec.capture(project.getRepositories());
        m.scriptRepositories = RepositorySpec.capture(project.getBuildscript().getRepositories());
        var scope = ResolutionRules.forProject(project);
        project.getConfigurations().forEach(c -> {
            if (filtering && c.isCanBeResolved() && !selected.test(c.getName())) return;
            var specification = ConfigurationSpec.capture(c, rule -> com.sneedit.deplens.integrations.DependencyManagementIntegrations.ownsResolutionRule(project, rule), scope);
            if (c.isCanBeResolved())
                specification.integrationManaged = com.sneedit.deplens.integrations.DependencyManagementIntegrations.managedVersions(project, c);
            m.configurations.put(c.getName(), specification);
        });
        project.getBuildscript().getConfigurations().forEach(c -> m.scriptConfigurations.put(c.getName(), ConfigurationSpec.capture(c, scope)));
        if (project.getExtensions().getExtraProperties().has(com.sneedit.deplens.DepLensSettingsPlugin.CONTEXT)) {
            var context = Objects.requireNonNull((SettingsModel) project.getExtensions().getExtraProperties().get(com.sneedit.deplens.DepLensSettingsPlugin.CONTEXT), "Missing DepLens settings model");
            m.settingsRepositories = context.pluginRepositories;
            m.settingsConfigurations = context.configurations;
            if (context.repositoryMode.equals("PREFER_SETTINGS") || m.repositories.isEmpty())
                m.repositories = context.repositories;
            if (m.scriptRepositories.isEmpty()) m.scriptRepositories = context.pluginRepositories;
        }
        for (var entry : inventory.entries)
            for (var resolution : entry.resolutions) {
                var configuration = m.configurations.get(resolution.configuration());
                if (configuration != null) configuration.managed.put(entry.coordinate, resolution.version());
            }
        return m;
    }

    /**
     * Keeps resolution rules needed when rebuilding this configuration for update checks.
     *
     * @param configuration Gradle configuration to inspect
     */
    public static void retainRules(org.gradle.api.artifacts.Configuration configuration) {
        ConfigurationSpec.retainResolutionRules(configuration);
    }
}
