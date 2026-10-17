/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.inventory;

import java.util.HashMap;
import java.util.Map;

import static com.sneedit.deplens.inventory.InventoryEntry.*;

/**
 * Reconciles plugin versions across serialized settings, root and child project records.
 */
final class PluginVersionInheritance {
    /**
     * Keeps this utility class static-only.
     */
    private PluginVersionInheritance() {
    }

    /**
     * Reconcile serialized plugin records without resolving another project's classpath.
     */
    static void inherit(BuildInventory report) {
        Map<String, InventoryEntry> entries = new HashMap<>();
        for (InventoryEntry entry : report.entries) entries.put(entry.id(), entry);
        for (InventoryEntry plugin : report.entries) {
            if (plugin.type != Type.PLUGIN || plugin.project.equals("<settings>")) continue;
            InventoryEntry managed = entries.get(plugin.build + "|<settings>|PLUGIN|" + plugin.coordinate);
            if (plugin.versionSources.stream().allMatch(source -> source.kind() == SourceKind.UNKNOWN)
                    && managed != null && managed.pluginStates.contains("PLUGIN_MANAGEMENT")) {
                plugin.declaredSelectors.addAll(managed.declaredSelectors);
                plugin.versionSources.removeIf(source -> source.kind() == SourceKind.UNKNOWN);
                plugin.versionSources.addAll(managed.versionSources);
                plugin.pluginStates.add("VERSION_FROM_PLUGIN_MANAGEMENT");
            }
            InventoryEntry rootPlugin = entries.get(plugin.build + "|:|PLUGIN|" + plugin.coordinate);
            if (!plugin.project.equals(":") && rootPlugin != null && plugin.resolutions.isEmpty()
                    && (plugin.declaredSelectors.isEmpty() || plugin.declaredSelectors.equals(rootPlugin.declaredSelectors))
                    && !rootPlugin.resolutions.isEmpty() && plugin.pluginStates.contains("APPLIED")) {
                plugin.declaredSelectors.addAll(rootPlugin.declaredSelectors);
                plugin.versionSources.removeIf(source -> source.kind() == SourceKind.UNKNOWN);
                plugin.versionSources.addAll(rootPlugin.versionSources);
                plugin.catalogs.addAll(rootPlugin.catalogs);
                for (Resolution resolution : rootPlugin.resolutions)
                    plugin.resolutions.add(new Resolution("rootPluginClasspath", resolution.version(), resolution.reasons()));
                plugin.pluginStates.add("VERSION_FROM_ROOT_CLASSPATH");
                plugin.statuses.clear();
                plugin.statuses.addAll(rootPlugin.statuses);
                plugin.candidates.addAll(rootPlugin.candidates);
                plugin.updateContexts.addAll(rootPlugin.updateContexts);
                plugin.recommendedVersion = rootPlugin.recommendedVersion;
                plugin.appliedRule = rootPlugin.appliedRule;
                plugin.warnings.remove("No resolved current version; update comparison is unavailable");
                plugin.warnings.remove("Resolved plugin version is unavailable through the public Gradle model; the declaration is preserved.");
            }
            if (plugin.resolutions.isEmpty() && !plugin.pluginStates.contains("LOCAL_CONVENTION"))
                plugin.warnings.add("Resolved plugin version is unavailable through the public Gradle model; the declaration is preserved.");
        }
    }

}
