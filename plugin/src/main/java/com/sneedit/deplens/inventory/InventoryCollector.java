/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.inventory;

import com.sneedit.deplens.integrations.DependencyManagementIntegrations;
import com.sneedit.deplens.inventory.InventoryEntry.*;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.VersionCatalogsExtension;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.plugin.devel.GradlePluginDevelopmentExtension;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.*;

/**
 * Reads declarations and Gradle's actual resolution graph. Never enumerates transitives as entries.
 */
public final class InventoryCollector {
    private final Project root;
    private final Project project;
    private final BuildInventory report = new BuildInventory();
    private final Map<String, InventoryEntry> entries = new LinkedHashMap<>();
    private final SourceIndex sources;
    private final String build;
    private final java.util.function.Predicate<String> selectedConfiguration;
    private final ConfigurationInventoryCollector configurations;

    /**
     * Creates a collector for one project, anchored at its root build directory.
     *
     * @param project Gradle project being inspected
     */
    public InventoryCollector(Project project) {
        this(project, name -> true, false);
    }

    /**
     * Name filters apply to project configurations; plugin and script classpaths remain checked.
     *
     * @param project project whose declarations and resolutions are collected
     * @param selectedConfiguration predicate selecting resolvable configurations to inspect
     * @param filtering whether an explicit resolvable-configuration filter is active
     */
    public InventoryCollector(Project project, java.util.function.Predicate<String> selectedConfiguration, boolean filtering) {
        this.selectedConfiguration = selectedConfiguration;
        this.project = project;
        root = project.getRootProject();
        build = root.getProjectDir().getAbsolutePath();
        sources = new SourceIndex(root.getProjectDir().toPath());
        configurations = new ConfigurationInventoryCollector(build, entries, sources, report, selectedConfiguration, filtering);
    }

    /**
     * Builds the inventory from declarations, direct resolution edges and management metadata.
     *
     * @return inventory with source evidence, resolutions and completeness warnings
     */
    public BuildInventory collect() {
        report.builds.add(build);
        Path directory = root.getProjectDir().toPath();
        for (Project project : List.of(this.project)) {
            sources.addScript(project.getBuildFile().toPath());
            sources.addProperties(project.getProjectDir().toPath().resolve("gradle.properties"));
        }
        for (String name : List.of("build.gradle", "build.gradle.kts")) sources.addScript(directory.resolve(name));
        if (!project.getPath().equals(":")) sources.addProperties(directory.resolve("gradle.properties"));
        for (String name : List.of("settings.gradle", "settings.gradle.kts"))
            sources.addScript(directory.resolve(name));
        sources.addCatalog("libs", directory.resolve("gradle/libs.versions.toml"));
        BuildFilesInventory.discoverCatalogs(directory, sources, report);
        for (Project project : List.of(this.project)) {
            VersionCatalogsExtension catalogs = project.getExtensions().findByType(VersionCatalogsExtension.class);
            if (catalogs != null) for (var catalog : catalogs) sources.addCatalogModel(catalog);
        }
        collectProject(project);
        if (project == root) {
            collectSettingsPlugins(directory);
            if (root.getExtensions().getExtraProperties().has(com.sneedit.deplens.DepLensSettingsPlugin.CONTEXT)) {
                var model = Objects.requireNonNull((com.sneedit.deplens.resolution.SettingsModel) root.getExtensions().getExtraProperties().get(com.sneedit.deplens.DepLensSettingsPlugin.CONTEXT), "Missing DepLens settings model");
                model.pluginVersions.forEach((id, version) -> {
                    InventoryEntry candidate = new InventoryEntry(build, "<settings>", Type.PLUGIN, id);
                    var plugin = entries.computeIfAbsent(candidate.id(), ignored -> candidate);
                    plugin.resolutions.add(new Resolution("pluginClasspath", version, List.of()));
                    plugin.pluginStates.add("EXTERNAL");
                });
            }
            BuildFilesInventory.collectGradle(directory, entry(root, Type.GRADLE, "gradle"), report);
            BuildFilesInventory.discoverBuilds(root, report);
        }
        // Gradle may contribute dormant security constraints. They are not direct declarations or effective constraints.
        entries.values().removeIf(item -> item.type == Type.CONSTRAINT && item.resolutions.isEmpty()
                && item.versionSources.stream().allMatch(source -> source.kind() == SourceKind.UNKNOWN));
        for (InventoryEntry item : entries.values()) {
            if (item.type != Type.DEPENDENCY || !item.declaredSelectors.isEmpty() || !item.platforms.isEmpty())
                continue;
            InventoryEntry constraint = entries.get(build + "|" + item.project + "|CONSTRAINT|" + item.coordinate);
            if (constraint != null) {
                item.versionSources.addAll(constraint.versionSources);
                item.statuses.add(Status.CONSTRAINED);
            }
        }
        for (InventoryEntry item : entries.values()) {
            if (item.type == Type.PLUGIN || item.type == Type.GRADLE || !item.resolutions.isEmpty()) continue;
            item.warnings.add("No resolved version is available in a resolvable configuration.");
            report.incomplete(build + " " + item.project + " " + item.coordinate + ": no resolved version");
        }
        for (String warning : sources.warnings()) report.incomplete(warning);
        report.entries.addAll(entries.values().stream().sorted(Comparator.comparing(InventoryEntry::id)).toList());
        return report;
    }

    /**
     * Finds or creates an entry keyed by build, project, type and coordinate.
     */
    private InventoryEntry entry(Project project, Type type, String coordinate) {
        return configurations.entry(project, type, coordinate);
    }

    /**
     * Collects project and buildscript declarations, plugin state and management contributions.
     */
    private void collectProject(Project project) {
        configurations.collect(project, project.getConfigurations(), "", project.getBuildFile().toPath());
        configurations.collect(project, project.getBuildscript().getConfigurations(), "buildscript:", project.getBuildFile().toPath());
        for (SourceIndex.PluginDeclaration plugin : sources.plugins(project.getBuildFile().toPath())) {
            // Core Gradle plugins do not have independently updateable versions.
            if (plugin.selector() == null && (plugin.id().equals("com.sneedit.deplens") || corePlugin(plugin.id())))
                continue;
            InventoryEntry item = entry(project, Type.PLUGIN, plugin.id());
            item.pluginStates.add(plugin.state());
            item.pluginStates.add(project.getPluginManager().hasPlugin(plugin.id()) ? "APPLIED" : "NOT_APPLIED");
            item.pluginStates.add(plugin.selector() == null ? "LOCAL_OR_UNVERSIONED" : "EXTERNAL");
            if (plugin.selector() != null) item.declaredSelectors.add(plugin.selector());
            item.declarations.add(plugin.declaration());
            item.versionSources.add(plugin.source());
            sources.attachPluginCatalogs(item);
        }
        DependencyManagementIntegrations.collect(project, entries.values(), (coordinate, configuration, version) -> {
            InventoryEntry bom = entry(project, Type.PLATFORM, coordinate);
            configurations.declare(bom, project.getBuildFile().toPath(), configuration, version);
            return bom;
        }, report, selectedConfiguration);
        attachPluginResolutions(project);
        GradlePluginDevelopmentExtension development = project.getExtensions().findByType(GradlePluginDevelopmentExtension.class);
        if (development != null) development.getPlugins().forEach(definition -> {
            if (definition.getId() == null) return;
            InventoryEntry plugin = entry(project, Type.PLUGIN, definition.getId());
            plugin.pluginStates.add("LOCAL_CONVENTION");
            plugin.pluginStates.add("PLUGIN_DEFINITION");
            plugin.declarations.add(new Declaration(project.getBuildFile().getAbsolutePath(), 0, "gradlePlugin", null));
        });
    }

    /**
     * Attaches plugin marker versions from the project's buildscript classpath.
     */
    private void attachPluginResolutions(Project project) {
        Configuration classpath = project.getBuildscript().getConfigurations().findByName("classpath");
        attachPluginClasspath(project, classpath, project.getPath());
    }

    /**
     * Reads resolved plugin markers and records external or local implementation state.
     */
    private void attachPluginClasspath(Project project, @Nullable Configuration classpath, String projectPath) {
        if (classpath == null) return;
        try {
            ResolvedComponentResult graph = classpath.getIncoming().getResolutionResult().getRootComponent().get();
            for (DependencyResult edge : graph.getDependencies()) {
                if (!(edge instanceof ResolvedDependencyResult resolved) || !(edge.getRequested() instanceof ModuleComponentSelector requested))
                    continue;
                if (!requested.getModule().endsWith(".gradle.plugin")) continue;
                String id = requested.getModule().substring(0, requested.getModule().length() - ".gradle.plugin".length());
                InventoryEntry candidate = new InventoryEntry(build, projectPath, Type.PLUGIN, id);
                InventoryEntry plugin = entries.computeIfAbsent(candidate.id(), ignored -> candidate);
                ConfigurationInventoryCollector.addResolution(plugin, "pluginClasspath", resolved);
                plugin.pluginStates.add(resolved.getSelected().getId() instanceof ProjectComponentIdentifier ? "LOCAL_CONVENTION" : "EXTERNAL");
            }
        } catch (Exception failure) {
            report.incomplete(build + " " + projectPath + ": plugin resolution unavailable");
        }
    }

    /**
     * Fills inherited plugin versions from settings and root records, without resolving another project.
     *
     * @param report inventory whose plugin records are updated in place
     */
    public static void inheritPluginManagementVersions(BuildInventory report) {
        PluginVersionInheritance.inherit(report);
    }

    /**
     * Collects settings plugin declarations and plugin management defaults from source files.
     */
    private void collectSettingsPlugins(Path directory) {
        for (String name : List.of("build.gradle", "build.gradle.kts")) sources.addScript(directory.resolve(name));
        if (!project.getPath().equals(":")) sources.addProperties(directory.resolve("gradle.properties"));
        for (String name : List.of("settings.gradle", "settings.gradle.kts")) {
            for (SourceIndex.PluginDeclaration plugin : sources.plugins(directory.resolve(name))) {
                if (corePlugin(plugin.id())) continue;
                InventoryEntry item = new InventoryEntry(build, "<settings>", Type.PLUGIN, plugin.id());
                item = entries.computeIfAbsent(item.id(), ignored -> new InventoryEntry(build, "<settings>", Type.PLUGIN, plugin.id()));
                item.pluginStates.add(plugin.state().equals("PLUGIN_MANAGEMENT") ? "PLUGIN_MANAGEMENT" : "SETTINGS_PLUGIN");
                if (plugin.selector() != null) item.declaredSelectors.add(plugin.selector());
                item.declarations.add(plugin.declaration());
                item.versionSources.add(plugin.source());
                sources.attachPluginCatalogs(item);
            }
        }
    }

    /**
     * Recognizes Gradle's bundled plugins, which have no independent version to update.
     */
    private static boolean corePlugin(String id) {
        return id.startsWith("org.gradle.") || Set.of("java", "java-library", "java-platform", "application", "base", "groovy", "scala", "war", "ear",
                "jacoco", "checkstyle", "pmd", "maven-publish", "ivy-publish", "signing", "idea", "eclipse", "java-gradle-plugin", "version-catalog").contains(id);
    }
}
