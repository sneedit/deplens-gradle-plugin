/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.integrations;

import com.sneedit.deplens.integrations.spring.SpringDependencyManagementAdapter;
import com.sneedit.deplens.inventory.BuildInventory;
import com.sneedit.deplens.inventory.InventoryEntry;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;

import java.util.Collection;
import java.util.List;

/**
 * Composition point: core inventory and update logic only consume integration-neutral data.
 */
public final class DependencyManagementIntegrations {
    private static final List<DependencyManagementIntegration> ADAPTERS = List.of(new SpringDependencyManagementAdapter());

    /**
     * Keeps this utility class static-only.
     */
    private DependencyManagementIntegrations() {
    }

    /**
     * Lets available integrations add management evidence and platform entries.
     *
     * @param project   Gradle project being inspected
     * @param entries   inventory entries receiving management evidence
     * @param platforms callback for registering platform entries
     * @param report    inventory to read or update
     */
    public static void collect(Project project, Collection<InventoryEntry> entries,
                               DependencyManagementIntegration.PlatformRegistrar platforms, BuildInventory report) {
        collect(project, entries, platforms, report, name -> true);
    }

    /**
     * Passes the checked scope to integrations that may otherwise resolve additional configurations.
     */
    public static void collect(Project project, Collection<InventoryEntry> entries,
                               DependencyManagementIntegration.PlatformRegistrar platforms, BuildInventory report,
                               java.util.function.Predicate<String> selectedConfiguration) {
        for (var adapter : ADAPTERS)
            if (adapter.isAvailable(project))
                adapter.collect(project, entries, platforms, report, selectedConfiguration);
    }

    /**
     * Checks whether an integration handles this dependency resolution rule separately.
     *
     * @param project Gradle project being inspected
     * @param rule    configured or captured rule
     * @return true when this integration captures the rule's effect separately
     */
    public static boolean ownsResolutionRule(Project project, org.gradle.api.Action<?> rule) {
        return ADAPTERS.stream().anyMatch(a -> a.isAvailable(project) && a.ownsResolutionRule(rule));
    }

    /**
     * Combines effective managed versions from available integrations for a configuration.
     *
     * @param project Gradle project being inspected
     * @param source  original configuration or recorded version source
     * @return effective managed versions keyed by module coordinate
     */
    public static java.util.Map<String, String> managedVersions(Project project, Configuration source) {
        java.util.Map<String, String> versions = new java.util.LinkedHashMap<>();
        for (var adapter : ADAPTERS)
            if (adapter.isAvailable(project)) {
                try {
                    versions.putAll(adapter.managedVersions(project, source));
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Cannot snapshot dependency management for " + source.getName(), failure);
                }
            }
        return versions;
    }
}
