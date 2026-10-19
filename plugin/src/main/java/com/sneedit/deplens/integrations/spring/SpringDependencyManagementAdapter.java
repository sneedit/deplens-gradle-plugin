/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.integrations.spring;

import com.sneedit.deplens.integrations.DependencyManagementIntegration;
import com.sneedit.deplens.inventory.BuildInventory;
import com.sneedit.deplens.inventory.InventoryEntry;
import com.sneedit.deplens.inventory.InventoryEntry.*;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;

import java.util.*;
import java.util.function.Predicate;

/**
 * Optional Spring integration; no Spring classes are linked or loaded unless its plugin is applied.
 */
public final class SpringDependencyManagementAdapter implements DependencyManagementIntegration {
    /** Creates an adapter for the optional Spring dependency-management plugin. */
    public SpringDependencyManagementAdapter() {
    }

    /**
     * Checks whether Spring's dependency management plugin is applied.
     *
     * @param project Gradle project being inspected
     * @return true when Spring dependency management is applied
     */
    @Override
    public boolean isAvailable(Project project) {
        return project.getPluginManager().hasPlugin("io.spring.dependency-management");
    }

    /**
     * Adds Spring BOM ownership and automatic Kotlin and protobuf alignment evidence.
     *
     * @param project   Gradle project being inspected
     * @param entries   inventory entries receiving management evidence
     * @param platforms callback for registering platform entries
     * @param report    inventory to read or update
     */
    @Override
    public void collect(Project project, Collection<InventoryEntry> entries,
                        PlatformRegistrar platforms, BuildInventory report) {
        collect(project, entries, platforms, report, name -> true);
    }

    @Override
    public void collect(Project project, Collection<InventoryEntry> entries, PlatformRegistrar platforms,
                        BuildInventory report, Predicate<String> selectedConfiguration) {
        try {
            SpringManagementModel spring = new SpringManagementModel(project);
            // Take a snapshot: Spring's POM resolver creates its own detached configurations.
            for (Configuration config : new ArrayList<>(project.getConfigurations())) {
                if (!config.isCanBeResolved() || !selectedConfiguration.test(config.getName()) || entries.stream().noneMatch(item -> item.project.equals(project.getPath())
                        && item.resolutions.stream().anyMatch(r -> r.configuration().equals(config.getName()))))
                    continue;
                Map<String, SpringManagementModel.Managed> managed = spring.forConfiguration(config);
                attributeKotlinDefaultConstraints(project, entries, config, managed);
                for (Map.Entry<String, String> imported : spring.imports(config).entrySet()) {
                    InventoryEntry bom = platforms.register(imported.getKey(), config.getName(), imported.getValue());
                    if (bom.resolutions.stream().noneMatch(r -> r.configuration().equals(config.getName()) && r.version().equals(imported.getValue())))
                        bom.resolutions.add(new Resolution(config.getName(), imported.getValue(), List.of(Resolution.IMPORTED_BOM)));
                }
                for (InventoryEntry item : new ArrayList<>(entries)) {
                    if (!item.project.equals(project.getPath()) || (item.type != Type.DEPENDENCY && item.type != Type.CONSTRAINT)
                            || item.resolutions.stream().noneMatch(r -> r.configuration().equals(config.getName())))
                        continue;
                    SpringManagementModel.Managed origin = managed.get(item.coordinate);
                    if (origin == null) continue;
                    if (item.type == Type.CONSTRAINT && !generatedKotlinEntry(item)) continue;
                    // Spring's effective management takes precedence over native platform provenance.
                    item.bomOwners.remove(config.getName());
                    if (origin.alignedPlugin() != null) {
                        if (item.type == Type.DEPENDENCY && entries.stream().anyMatch(constraint ->
                                constraint.type == Type.CONSTRAINT && constraint.project.equals(item.project)
                                        && constraint.coordinate.equals(item.coordinate) && constraint.configurations.contains(config.getName())
                                        && !constraint.declaredSelectors.isEmpty() && !generatedKotlinEntry(constraint)))
                            item.managementOverrides.add(config.getName());
                        if (generatedKotlinEntry(item) && item.declaredSelectors.equals(Set.of(origin.managedVersion()))) {
                            // Plugin-generated defaults and constraints are not user-specified versions.
                            item.declaredSelectors.clear();
                        }
                        if (item.declaredSelectors.isEmpty() && !item.managementOverrides.contains(config.getName())) {
                            item.versionAlignments.put(config.getName(), "plugin:" + origin.alignedPlugin());
                            item.statuses.add(Status.CONSTRAINED);
                            item.versionSources.removeIf(source -> source.kind() == SourceKind.UNKNOWN);
                            item.versionSources.add(new Source("<spring-boot-alignment>", 0, SourceKind.GENERATED,
                                    "plugin:" + origin.alignedPlugin(), origin.managedVersion()));
                        }
                        continue;
                    }
                    if (origin.override()) {
                        item.managementOverrides.add(config.getName());
                        continue;
                    }
                    item.bomOwners.computeIfAbsent(config.getName(), ignored -> new TreeSet<>()).add(origin.bomCoordinate());
                    item.platforms.add(origin.bomCoordinate());
                    item.statuses.add(Status.CONSTRAINED);
                    if (item.declaredSelectors.isEmpty()) {
                        item.versionSources.removeIf(source -> source.kind() == SourceKind.UNKNOWN);
                        item.versionSources.add(new Source("<spring-bom:" + origin.bomCoordinate() + ">", 0, SourceKind.PLATFORM, origin.bomCoordinate(), null));
                    }
                }
            }
            SpringProtobufAlignment.collect(project, entries, spring, selectedConfiguration);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            // Fail visibly rather than guessing ownership when Spring changes its model.
            report.incomplete(project.getRootDir() + " " + project.getPath() + " dependencyManagement: Spring BOM provenance unavailable");
            project.getLogger().info("DepLens: Spring BOM provenance unavailable ({})", failure.getClass().getSimpleName());
        }
    }

    /**
     * Links generated Kotlin constraints to the plugin or BOM that supplies their version.
     */
    private static void attributeKotlinDefaultConstraints(Project project, Collection<InventoryEntry> entries,
                                                          Configuration config, Map<String, SpringManagementModel.Managed> managed) {
        for (InventoryEntry constraint : entries) {
            SpringManagementModel.Managed origin = managed.get(constraint.coordinate);
            if (constraint.type != Type.CONSTRAINT || !constraint.project.equals(project.getPath())
                    || origin == null || origin.alignedPlugin() == null || !constraint.configurations.contains(config.getName())
                    || !constraint.versionSources.isEmpty() || !constraint.declaredSelectors.equals(Set.of(origin.managedVersion()))
                    || constraint.declarations.isEmpty() || constraint.declarations.stream().anyMatch(d -> d.line() <= 0))
                continue;
            // KGP supplies default constraints for explicitly versionless Kotlin library declarations.
            // A user constraint with its own version source (or unknown programmatic origin) is preserved.
            boolean versionlessLibrary = entries.stream().anyMatch(library -> library.type == Type.DEPENDENCY
                    && library.project.equals(constraint.project) && library.coordinate.equals(constraint.coordinate)
                    && library.declaredSelectors.isEmpty() && library.configurations.contains(config.getName()));
            if (versionlessLibrary) constraint.versionSources.add(new Source("<Kotlin default constraint>", 0,
                    SourceKind.GENERATED, "plugin:org.jetbrains.kotlin.jvm", origin.managedVersion()));
        }
    }

    /**
     * Recognizes Kotlin entries whose source was generated by Gradle defaults.
     */
    private static boolean generatedKotlinEntry(InventoryEntry item) {
        return item.coordinate.startsWith("org.jetbrains.kotlin:") && !item.versionSources.isEmpty()
                && item.versionSources.stream().allMatch(source -> source.kind() == SourceKind.GENERATED
                && (source.file().equals("<Gradle defaultDependencies>") || source.file().equals("<Kotlin default constraint>")
                || source.file().equals("<spring-boot-alignment>")))
                && item.versionSources.stream().anyMatch(source -> source.file().equals("<Gradle defaultDependencies>")
                || source.file().equals("<Kotlin default constraint>"));
    }

    /**
     * Recognizes rules installed by Spring's dependency management plugin.
     *
     * @param rule configured or captured rule
     * @return true when this integration captures the rule's effect separately
     */
    @Override
    public boolean ownsResolutionRule(Action<?> rule) {
        return rule.getClass().getName().startsWith("io.spring.gradle.dependencymanagement.");
    }

    /**
     * Reads effective versions through Spring's public configuration hierarchy API.
     *
     * @param project Gradle project being inspected
     * @param source  original configuration or recorded version source
     * @return effective managed versions keyed by module coordinate
     * @throws ReflectiveOperationException if the expected Gradle or integration model API cannot be accessed
     */
    @Override
    public Map<String, String> managedVersions(Project project, Configuration source) throws ReflectiveOperationException {
        Object extension = project.getExtensions().getByName("dependencyManagement");
        Class<?> api = extension.getClass().getClassLoader().loadClass("io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension");
        @SuppressWarnings("unchecked")
        Map<String, String> managed = (Map<String, String>) api.getMethod("getManagedVersionsForConfigurationHierarchy", Configuration.class).invoke(extension, source);
        return new LinkedHashMap<>(managed);
    }

}
