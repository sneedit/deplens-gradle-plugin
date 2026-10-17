/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.tasks;

import com.sneedit.deplens.inventory.InventoryEntry;

import java.util.Objects;
import java.util.stream.Collectors;

/** Explains discovered versions that cannot be recommended automatically. */
final class UpdateAvailability {
    private UpdateAvailability() {
    }

    static String explanation(InventoryEntry entry) {
        if (entry.statuses.contains(InventoryEntry.Status.FAILED)
                || entry.statuses.contains(InventoryEntry.Status.NOT_CHECKED))
            return "check incomplete; no recommendation";
        String allowed = entry.updateContexts.stream().map(InventoryEntry.UpdateContext::latestAllowedByBuild)
                .filter(Objects::nonNull).distinct().sorted(entry.versionComparator())
                .collect(Collectors.joining(", "));
        String reason = entry.declaredSelectors.stream().anyMatch(UpdateAvailability::dynamicSelector)
                ? "explicit selector change required" : "build restrictions must be changed";
        return (allowed.isEmpty() ? "" : "allowed by build: " + allowed + "; ") + reason + "; no recommendation";
    }

    private static boolean dynamicSelector(String selector) {
        return selector.contains("+") || selector.contains("[") || selector.contains("(")
                || selector.contains(",") || selector.startsWith("latest.");
    }
}
