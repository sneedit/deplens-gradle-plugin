/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.tasks;

import com.sneedit.deplens.inventory.InventoryEntry;
import org.gradle.work.DisableCachingByDefault;

/**
 * Checks updates for Gradle plugins.
 */
@DisableCachingByDefault(because = "Update checks observe live repository state")
public abstract class CheckPluginUpdatesTask extends DepLensCheckTask {
    /**
     * Selects Gradle plugins for this check.
     *
     * @return the plugin type
     */
    @Override
    protected java.util.Set<InventoryEntry.Type> types() {
        return java.util.EnumSet.of(InventoryEntry.Type.PLUGIN);
    }
}
