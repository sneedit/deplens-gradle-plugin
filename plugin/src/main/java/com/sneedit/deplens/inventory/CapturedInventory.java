/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */
package com.sneedit.deplens.inventory;

import com.google.gson.Gson;
import com.sneedit.deplens.resolution.AnalysisModel;

import java.util.*;

/**
 * Normalizes captured inventories consistently for aggregate and project check tasks.
 */
public final class CapturedInventory {
    private CapturedInventory() {
    }

    public static BuildInventory merge(List<AnalysisModel> models) {
        BuildInventory report = Objects.requireNonNull(
                new Gson().fromJson(models.getFirst().inventory, BuildInventory.class), "Missing DepLens inventory");
        Gson decoder = new Gson();
        for (var model : models.subList(1, models.size())) {
            BuildInventory child = Objects.requireNonNull(
                    decoder.fromJson(model.inventory, BuildInventory.class), "Missing nested DepLens inventory");
            report.entries.addAll(child.entries);
            report.builds.addAll(child.builds);
            report.warnings.addAll(child.warnings);
        }
        report.builds.forEach(build -> report.warnings.remove("Separate build requires its own depLensInventory invocation: " + build));
        Map<String, InventoryEntry> unique = new LinkedHashMap<>();
        for (InventoryEntry entry : report.entries) {
            InventoryEntry existing = unique.putIfAbsent(entry.id(), entry);
            if (existing != null) existing.merge(entry);
        }
        report.entries.clear();
        report.entries.addAll(unique.values());
        Set<String> localPlugins = new HashSet<>();
        report.entries.stream().filter(entry -> entry.pluginStates.contains("PLUGIN_DEFINITION")).forEach(entry -> localPlugins.add(entry.coordinate));
        for (InventoryEntry entry : report.entries) {
            if (entry.type == InventoryEntry.Type.PLUGIN && entry.resolutions.isEmpty() && localPlugins.contains(entry.coordinate)) {
                entry.pluginStates.add("LOCAL_CONVENTION");
                entry.pluginStates.remove("EXTERNAL");
                entry.pluginStates.remove("LOCAL_OR_UNVERSIONED");
                entry.warnings.remove("Resolved plugin version is unavailable through the public Gradle model; the declaration is preserved.");
            }
        }
        return report;
    }
}
