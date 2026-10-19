/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.tasks;

import com.sneedit.deplens.inventory.InventoryEntry;
import org.gradle.work.DisableCachingByDefault;

/**
 * Checks updates for dependencies, platforms, constraints, plugins and the wrapper.
 */
@DisableCachingByDefault(because = "Update checks observe live repository state")
public abstract class CheckUpdatesTask extends DepLensCheckTask {
    /** Creates an update-check task covering every supported component type. */
    public CheckUpdatesTask() {
    }

    /**
     * Selects dependencies, platforms, constraints, plugins and the wrapper for this check.
     *
     * @return all supported component types
     */
    @Override
    protected java.util.Set<InventoryEntry.Type> types() {
        return java.util.EnumSet.allOf(InventoryEntry.Type.class);
    }
}
