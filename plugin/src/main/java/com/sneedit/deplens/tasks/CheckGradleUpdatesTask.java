/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.tasks;

import com.sneedit.deplens.inventory.InventoryEntry;
import org.gradle.work.DisableCachingByDefault;

/**
 * Checks updates for the Gradle wrapper.
 */
@DisableCachingByDefault(because = "Update checks observe live repository state")
public abstract class CheckGradleUpdatesTask extends DepLensCheckTask {
    /**
     * Selects the Gradle wrapper for this check.
     *
     * @return the Gradle wrapper type
     */
    @Override
    protected java.util.Set<InventoryEntry.Type> types() {
        return java.util.EnumSet.of(InventoryEntry.Type.GRADLE);
    }
}
