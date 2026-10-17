/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.inventory;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One report shared by the console and JSON writer. No network lookup status is inferred.
 */
public final class BuildInventory {
    public final int schemaVersion = 1;
    public final List<InventoryEntry> entries = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    public final Set<String> builds = new LinkedHashSet<>();
    public boolean complete = true;
    /**
     * Null for inventories; independent of whether the current build resolved completely.
     */
    public @Nullable Boolean checksComplete;
    /**
     * At least one analyzed scope opted out of full update-candidate graph checks.
     */
    public boolean fastCheck;
    /**
     * Entries omitted from this view only; completeness still describes the complete checked scope.
     */
    public int hiddenEntries;
    public int hiddenFailedEntries;

    /**
     * Physical source decisions; empty for checks and cancelled interactive sessions.
     *
     * @param source         original configuration or recorded version source
     * @param target         object or attribute container to update
     * @param consumers      component identities sharing this version source
     * @param catalogAliases catalog aliases affected by this selected source
     */
    public record SelectedSource(InventoryEntry.Source source, String target, List<String> consumers,
                                 List<String> catalogAliases) {
    }

    public final List<SelectedSource> selectedSources = new ArrayList<>();

    /**
     * Marks the inventory incomplete and records the reason as a report warning.
     *
     * @param message message template or warning text
     */
    public void incomplete(String message) {
        complete = false;
        if (!warnings.contains(message)) warnings.add(message);
    }
}
