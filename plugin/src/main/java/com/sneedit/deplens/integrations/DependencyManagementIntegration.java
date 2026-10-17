/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.integrations;

import com.sneedit.deplens.inventory.BuildInventory;
import com.sneedit.deplens.inventory.InventoryEntry;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;

import java.util.Collection;

/**
 * Optional contributions to inventory and detached native resolution data.
 */
public interface DependencyManagementIntegration {
    /**
     * Callback for adding integration-discovered BOMs to the inventory.
     */
    @FunctionalInterface
    interface PlatformRegistrar {
        /**
         * Finds or creates a platform entry and records its configuration and version.
         *
         * @param coordinate    module coordinate or plugin ID
         * @param configuration configuration importing the platform
         * @param version       version or constraint being inspected
         * @return platform entry receiving this management evidence
         */
        InventoryEntry register(String coordinate, String configuration, String version);
    }

    /**
     * Checks whether the integration's plugin or model is present in this project.
     *
     * @param project Gradle project being inspected
     * @return true when this integration is available in the project
     */
    boolean isAvailable(Project project);

    /**
     * Contributes management origins, overrides and platform entries to the inventory.
     *
     * @param project   Gradle project being inspected
     * @param entries   inventory entries receiving management evidence
     * @param platforms callback for registering platform entries
     * @param report    inventory to read or update
     */
    void collect(Project project, Collection<InventoryEntry> entries, PlatformRegistrar platforms, BuildInventory report);

    /**
     * Contributes evidence without resolving configurations outside the selected scope.
     */
    default void collect(Project project, Collection<InventoryEntry> entries, PlatformRegistrar platforms,
                         BuildInventory report, java.util.function.Predicate<String> selectedConfiguration) {
        collect(project, entries, platforms, report);
    }

    /**
     * Identifies rules whose effects are captured through this integration's managed versions.
     *
     * @param rule configured or captured rule
     * @return true when this integration captures the rule effects separately
     */
    default boolean ownsResolutionRule(org.gradle.api.Action<?> rule) {
        return false;
    }

    /**
     * Returns effective managed module versions for the source configuration.
     *
     * @param project Gradle project being inspected
     * @param source  original configuration or recorded version source
     * @return effective managed versions keyed by module coordinate
     * @throws ReflectiveOperationException if the expected Gradle or integration model API cannot be accessed
     */
    java.util.Map<String, String> managedVersions(Project project, Configuration source) throws ReflectiveOperationException;
}
