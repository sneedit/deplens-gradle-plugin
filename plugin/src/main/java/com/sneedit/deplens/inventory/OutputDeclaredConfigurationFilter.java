/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */
package com.sneedit.deplens.inventory;

import com.sneedit.deplens.ConfigurationFilter;

import java.util.List;
import java.util.function.Predicate;

/**
 * Creates a report view without modifying the analyzed entries or check results.
 */
public final class OutputDeclaredConfigurationFilter {
    private OutputDeclaredConfigurationFilter() {
    }

    /**
     * Builds a presentation view without discarding full-scope completeness information.
     *
     * @param source complete inventory or check report
     * @param includes regexes selecting declared configuration names
     * @param excludes regexes hiding declared configuration names
     * @return the source report when unfiltered, otherwise its filtered view
     */
    public static BuildInventory apply(BuildInventory source, List<String> includes, List<String> excludes) {
        Predicate<String> selected = ConfigurationFilter.predicate("outputDeclaredConfigurationFilter", includes, excludes);
        if (includes.isEmpty() && excludes.isEmpty()) return source;
        BuildInventory view = new BuildInventory();
        view.builds.addAll(source.builds);
        view.warnings.addAll(source.warnings);
        view.selectedSources.addAll(source.selectedSources);
        view.complete = source.complete;
        view.checksComplete = source.checksComplete;
        view.fastCheck = source.fastCheck;
        view.hiddenEntries = source.hiddenEntries;
        view.hiddenFailedEntries = source.hiddenFailedEntries;
        for (InventoryEntry entry : source.entries) {
            if (visible(entry, selected)) view.entries.add(entry);
            else {
                view.hiddenEntries++;
                if (entry.statuses.contains(InventoryEntry.Status.FAILED)) view.hiddenFailedEntries++;
            }
        }
        return view;
    }

    private static boolean visible(InventoryEntry entry, Predicate<String> selected) {
        if (entry.type == InventoryEntry.Type.PLUGIN || entry.type == InventoryEntry.Type.GRADLE) return true;
        var names = entry.declarations.isEmpty() ? entry.configurations.stream()
                : entry.declarations.stream().map(InventoryEntry.Declaration::configuration);
        List<String> origins = names.distinct().toList();
        return origins.isEmpty() || origins.stream().anyMatch(name -> name.startsWith("buildscript:") || selected.test(name));
    }
}
