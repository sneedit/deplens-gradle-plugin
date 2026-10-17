/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.inventory;

import com.sneedit.deplens.inventory.InventoryEntry.*;
import org.gradle.api.Project;
import org.gradle.api.artifacts.*;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Category;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.*;

/**
 * Collects declarations and graph evidence from one project's configuration containers.
 */
final class ConfigurationInventoryCollector {
    private final String build;
    private final Map<String, InventoryEntry> entries;
    private final SourceIndex sources;
    private final BuildInventory report;
    private final java.util.function.Predicate<String> selectedConfiguration;
    private final boolean filtering;

    ConfigurationInventoryCollector(String build, Map<String, InventoryEntry> entries, SourceIndex sources,
                                    BuildInventory report, java.util.function.Predicate<String> selectedConfiguration,
                                    boolean filtering) {
        this.build = build;
        this.entries = entries;
        this.sources = sources;
        this.report = report;
        this.selectedConfiguration = selectedConfiguration;
        this.filtering = filtering;
    }

    /**
     * Finds or creates an entry in the shared project inventory.
     */
    InventoryEntry entry(Project project, Type type, String coordinate) {
        InventoryEntry candidate = new InventoryEntry(build, project.getPath(), type, coordinate);
        return entries.computeIfAbsent(candidate.id(), key -> candidate);
    }

    /**
     * Resolves eligible configurations and attaches declarations, constraints and graph evidence.
     */
    void collect(Project project, ConfigurationContainer configurations, String prefix, Path script) {
        Map<Configuration, ResolvedComponentResult> graphs = new LinkedHashMap<>();
        Map<Configuration, Set<Dependency>> initial = new LinkedHashMap<>();
        for (Configuration config : configurations.stream().toList())
            initial.put(config, new HashSet<>(config.getDependencies()));
        Set<Configuration> selected = new LinkedHashSet<>();
        Set<Configuration> declarations = new LinkedHashSet<>();
        for (Configuration config : configurations.stream().toList()) {
            if (config.isCanBeResolved() && (!prefix.isEmpty() || selectedConfiguration.test(config.getName()))) {
                selected.add(config);
                declarations.addAll(config.getHierarchy());
            } else if (config.isCanBeResolved() && filtering) {
                project.getLogger().info("DepLens checkResolvableConfigurationFilter skipped {}", context(project, config.getName()));
            }
        }
        // Resolve metadata only. No artifact downloads, tasks or application code are run here.
        for (Configuration config : configurations.stream().toList()) {
            if (!selected.contains(config)) continue;
            try {
                ResolvedComponentResult graph = config.getIncoming().getResolutionResult().getRootComponent().get();
                graphs.put(config, graph);
            } catch (Exception failure) {
                Set<String> causes = new LinkedHashSet<>();
                for (Throwable cause = failure; cause != null && causes.add(cause.getClass().getName()); cause = cause.getCause()) {
                }
                project.getLogger().info("DepLens resolution {}: {}", context(project, prefix + config.getName()), causes);
                report.incomplete(context(project, prefix + config.getName()) + ": resolution failed (" + String.join(" -> ", causes) + ")");
            }
        }
        // Resolution may realize plugin defaultDependencies (JaCoCo, Checkstyle, etc.).
        for (Configuration config : configurations.stream().toList()) {
            if (prefix.isEmpty() && filtering && !declarations.contains(config)) continue;
            String name = prefix + config.getName();
            for (Dependency dependency : config.getDependencies()) {
                if (!(dependency instanceof ExternalModuleDependency module) || module.getGroup() == null) continue;
                Type type = isPlatform(module) ? Type.PLATFORM : Type.DEPENDENCY;
                String selector = selector(module.getVersionConstraint(), module.getVersion());
                if (prefix.equals("buildscript:") && module.getName().endsWith(".gradle.plugin")) {
                    String id = module.getName().substring(0, module.getName().length() - ".gradle.plugin".length());
                    InventoryEntry plugin = entry(project, Type.PLUGIN, id);
                    InventoryEntry marker = new InventoryEntry(build, project.getPath(), Type.PLUGIN, module.getGroup() + ":" + module.getName());
                    sources.attach(marker, script, name, selector);
                    if (marker.declarations.stream().anyMatch(declaration -> declaration.line() > 0)) {
                        plugin.declarations.addAll(marker.declarations);
                        plugin.versionSources.addAll(marker.versionSources);
                        if (selector != null) plugin.declaredSelectors.add(selector);
                        plugin.pluginStates.add("BUILDSCRIPT_CLASSPATH");
                    }
                    plugin.configurations.add(name);
                    continue;
                }
                InventoryEntry item = entry(project, type, module.getGroup() + ":" + module.getName());
                declare(item, script, name, selector);
                addConstraintInfo(item, name, module.getVersionConstraint());
                if (!initial.getOrDefault(config, Set.of()).contains(module)) {
                    item.versionSources.removeIf(source -> source.kind() == SourceKind.UNKNOWN);
                    item.versionSources.add(new Source("<Gradle defaultDependencies>", 0, SourceKind.GENERATED, name, selector));
                }
            }
            for (DependencyConstraint constraint : config.getDependencyConstraints()) {
                if (constraint.getGroup() == null) continue;
                InventoryEntry item = entry(project, Type.CONSTRAINT, constraint.getGroup() + ":" + constraint.getName());
                declare(item, script, name, selector(constraint.getVersionConstraint(), constraint.getVersion()));
                addConstraintInfo(item, name, constraint.getVersionConstraint());
                item.statuses.add(Status.CONSTRAINED);
            }
        }
        for (Map.Entry<Configuration, ResolvedComponentResult> graph : graphs.entrySet()) {
            Configuration config = graph.getKey();
            String name = prefix + config.getName();
            Set<String> declared = new HashSet<>();
            config.getAllDependencies().forEach(d -> {
                if (d instanceof ExternalModuleDependency) declared.add(d.getGroup() + ":" + d.getName());
            });
            config.getAllDependencyConstraints().forEach(d -> declared.add(d.getGroup() + ":" + d.getName()));
            // Direct edges include platform variants (which the old ResolvedDependency API loses).
            for (DependencyResult edge : graph.getValue().getDependencies()) {
                if (!(edge.getRequested() instanceof ModuleComponentSelector requested)) continue;
                String coordinate = requested.getGroup() + ":" + requested.getModule();
                if (!declared.contains(coordinate)) continue;
                String entryCoordinate = prefix.equals("buildscript:") && requested.getModule().endsWith(".gradle.plugin")
                        ? requested.getModule().substring(0, requested.getModule().length() - ".gradle.plugin".length()) : coordinate;
                List<InventoryEntry> matching = projectEntries(project, entryCoordinate);
                for (InventoryEntry item : matching) {
                    item.configurations.add(name);
                    if (item.type == Type.DEPENDENCY && config.getResolutionStrategy().getForcedModules().stream()
                            .anyMatch(forced -> coordinate.equals(forced.getGroup() + ":" + forced.getName())))
                        item.managementOverrides.add(name);
                    if (edge instanceof ResolvedDependencyResult resolved) addResolution(item, name, resolved);
                    else if (edge instanceof UnresolvedDependencyResult unresolved) {
                        item.statuses.add(Status.FAILED);
                        var detail = com.sneedit.deplens.updates.ResolutionFailureDetails.describe(name, unresolved);
                        if (!item.resolutionFailures.contains(detail)) item.resolutionFailures.add(detail);
                        item.warnings.add("Resolution failed in " + name + "; repository credentials and exception details are omitted.");
                        report.incomplete(context(project, name) + ": could not resolve " + coordinate);
                    }
                }
            }
            for (DependencyResult edge : graph.getValue().getDependencies()) {
                if (!(edge instanceof ResolvedDependencyResult resolved) || !(edge.getRequested() instanceof ModuleComponentSelector requested))
                    continue;
                String coordinate = requested.getGroup() + ":" + requested.getModule();
                if (projectEntries(project, coordinate).stream().anyMatch(e -> e.type == Type.PLATFORM)) {
                    attachPlatform(project, coordinate, resolved.getSelected(), name, new HashSet<>());
                }
            }
            inspectGraph(project, graph.getValue(), name);
        }
    }

    /**
     * Attaches a configuration, selector and source evidence to an entry.
     */
    void declare(InventoryEntry item, Path script, String configuration, @Nullable String selector) {
        item.configurations.add(configuration);
        if (selector != null && !selector.isBlank()) item.declaredSelectors.add(selector);
        sources.attach(item, script, configuration, selector);
    }

    /**
     * Copies rich version constraints into the report without flattening their meaning.
     */
    private static void addConstraintInfo(InventoryEntry item, String configuration, VersionConstraint constraint) {
        item.versionConstraints.add(new VersionConstraintInfo(configuration, constraint.getRequiredVersion(),
                constraint.getStrictVersion(), constraint.getPreferredVersion(), List.copyOf(constraint.getRejectedVersions())));
    }

    /**
     * Records an external module's selected version and Gradle cause codes.
     */
    static void addResolution(InventoryEntry item, String configuration, ResolvedDependencyResult result) {
        if (!(result.getSelected().getId() instanceof ModuleComponentIdentifier selected)) {
            item.warnings.add("Dependency is substituted by a local project in " + configuration);
            return;
        }
        List<String> reasons = result.getSelected().getSelectionReason().getDescriptions().stream()
                // Public cause codes carry no user-provided text or credentials.
                .map(reason -> reason.getCause().name()).distinct().toList();
        item.resolutions.add(new Resolution(configuration, selected.getVersion(), reasons));
        if (result.getSelected().getSelectionReason().isConstrained() || result.getSelected().getSelectionReason().isForced()) {
            item.statuses.add(Status.CONSTRAINED);
        }
    }

    /**
     * Walks platform edges to assign BOM ownership while retaining explicit overrides.
     */
    private void attachPlatform(Project project, String owner, ResolvedComponentResult platform, String configuration, Set<ComponentIdentifier> visited) {
        if (!visited.add(platform.getId())) return;
        for (DependencyResult edge : platform.getDependencies()) {
            if (!(edge instanceof ResolvedDependencyResult resolved) || !(edge.getRequested() instanceof ModuleComponentSelector requested))
                continue;
            String coordinate = requested.getGroup() + ":" + requested.getModule();
            if (edge.isConstraint()) {
                for (InventoryEntry item : projectEntries(project, coordinate)) {
                    if (!item.configurations.contains(configuration)) continue;
                    item.platforms.add(owner);
                    item.bomOwners.computeIfAbsent(configuration, ignored -> new TreeSet<>()).add(owner);
                    Configuration nativeConfig = project.getConfigurations().findByName(configuration);
                    if (nativeConfig != null && nativeConfig.getAllDependencyConstraints().stream().anyMatch(c ->
                            coordinate.equals(c.getGroup() + ":" + c.getName()) && selector(c.getVersionConstraint(), c.getVersion()) != null))
                        item.managementOverrides.add(configuration);
                    item.statuses.add(Status.CONSTRAINED);
                    if (item.type == Type.DEPENDENCY && item.declaredSelectors.isEmpty()) {
                        item.versionSources.removeIf(source -> source.kind() == SourceKind.UNKNOWN);
                        item.versionSources.add(new Source("<platform:" + owner + ">", 0, SourceKind.PLATFORM, owner, null));
                    }
                }
            } else {
                if (isPlatformVariant(resolved.getResolvedVariant().getAttributes())) {
                    attachPlatform(project, owner, resolved.getSelected(), configuration, visited);
                }
            }
        }
    }

    /**
     * Walks the graph for transitive platforms and unresolved edges without listing transitives as entries.
     */
    private void inspectGraph(Project project, ResolvedComponentResult rootComponent, String configuration) {
        Set<ComponentIdentifier> visited = new HashSet<>();
        Deque<ResolvedComponentResult> pending = new ArrayDeque<>();
        pending.add(rootComponent);
        while (!pending.isEmpty()) {
            ResolvedComponentResult component = pending.removeFirst();
            if (!visited.add(component.getId())) continue;
            for (DependencyResult edge : component.getDependencies()) {
                if (edge instanceof UnresolvedDependencyResult) {
                    report.incomplete(context(project, configuration) + ": the dependency graph is incomplete");
                } else if (edge instanceof ResolvedDependencyResult resolved) {
                    if (isPlatformVariant(resolved.getResolvedVariant().getAttributes())
                            && resolved.getSelected().getId() instanceof ModuleComponentIdentifier module) {
                        attachPlatform(project, module.getGroup() + ":" + module.getModule(), resolved.getSelected(), configuration, new HashSet<>());
                    }
                    pending.add(resolved.getSelected());
                }
            }
        }
    }

    /**
     * Finds entries for a coordinate in this project's inventory.
     */
    private List<InventoryEntry> projectEntries(Project project, String coordinate) {
        return entries.values().stream().filter(e -> e.project.equals(project.getPath()) && e.coordinate.equals(coordinate)).toList();
    }

    /**
     * Builds a diagnostic label from the build, project and configuration.
     */
    private String context(Project project, String configuration) {
        return build + " " + project.getPath() + " " + configuration;
    }

    /**
     * Chooses the strict, required or preferred selector, falling back to the supplied version.
     */
    private static @Nullable String selector(VersionConstraint version, @Nullable String fallback) {
        if (!version.getStrictVersion().isEmpty()) return version.getStrictVersion();
        if (!version.getRequiredVersion().isEmpty()) return version.getRequiredVersion();
        if (!version.getPreferredVersion().isEmpty()) return version.getPreferredVersion();
        return fallback;
    }

    /**
     * Checks whether a dependency requests a regular or enforced platform category.
     */
    private static boolean isPlatform(ExternalModuleDependency dependency) {
        Category category = dependency.getAttributes().getAttribute(Category.CATEGORY_ATTRIBUTE);
        return category != null && (Category.REGULAR_PLATFORM.equals(category.getName()) || Category.ENFORCED_PLATFORM.equals(category.getName()));
    }

    /**
     * Recognizes platform categories in typed and desugared string attributes.
     */
    private static boolean isPlatformVariant(AttributeContainer attributes) {
        // Published Gradle module metadata can retain the desugared String form of Category.
        for (Attribute<?> attribute : attributes.keySet()) {
            if (!attribute.getName().equals(Category.CATEGORY_ATTRIBUTE.getName())) continue;
            Object value = attributes.getAttribute(attribute);
            String name = value instanceof Category category ? category.getName() : String.valueOf(value);
            return Category.REGULAR_PLATFORM.equals(name) || Category.ENFORCED_PLATFORM.equals(name);
        }
        return false;
    }

}
