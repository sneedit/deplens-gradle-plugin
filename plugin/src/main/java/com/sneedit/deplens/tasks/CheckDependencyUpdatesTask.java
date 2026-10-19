/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.tasks;

import com.sneedit.deplens.inventory.InventoryEntry;
import org.gradle.work.DisableCachingByDefault;

/**
 * Checks updates for dependencies, platforms and version constraints.
 */
@DisableCachingByDefault(because = "Update checks observe live repository state")
public abstract class CheckDependencyUpdatesTask extends DepLensCheckTask {
    /** Creates a dependency, platform and constraint update-check task. */
    public CheckDependencyUpdatesTask() {
    }

    /**
     * Selects dependencies, platforms and version constraints for this check.
     *
     * @return dependency, platform and constraint types
     */
    @Override
    protected java.util.Set<InventoryEntry.Type> types() {
        return java.util.EnumSet.of(InventoryEntry.Type.DEPENDENCY, InventoryEntry.Type.PLATFORM, InventoryEntry.Type.CONSTRAINT);
    }
}
