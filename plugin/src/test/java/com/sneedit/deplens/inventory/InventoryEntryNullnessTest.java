/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.inventory;

import com.google.gson.Gson;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests inventory entry nullness.
 */
class InventoryEntryNullnessTest {
    /**
     * Verifies that omitted compatibility list in older JSON becomes empty instead of null.
     */
    @Test
    void omittedCompatibilityListInOlderJsonBecomesEmptyInsteadOfNull() {
        var context = new Gson().fromJson("""
                {"configuration":"compileClasspath", "currentVersion":"1.0",
                 "outsideSelector":[], "candidates":[]}
                """, InventoryEntry.UpdateContext.class);
        assertThat(context.unknownCompatibility()).isEmpty();
        assertThat(context.latestAvailable()).isNull();
        assertThat(context.noMatchingCandidates()).isFalse();
    }

    /**
     * Verifies that compatibility list accepts absent input and copies present input.
     */
    @Test
    void compatibilityListAcceptsAbsentInputAndCopiesPresentInput() {
        var absent = context(null);
        assertThat(absent.unknownCompatibility()).isEmpty();
        var original = new java.util.ArrayList<>(List.of("20261013"));
        var present = context(original);
        original.clear();
        assertThat(present.unknownCompatibility()).containsExactly("20261013");
        assertThatThrownBy(() -> present.unknownCompatibility().add("other"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * Creates an update context with the supplied compatibility list.
     */
    private static InventoryEntry.UpdateContext context(@Nullable List<String> unknown) {
        return new InventoryEntry.UpdateContext("compileClasspath", "1.0", null, null, null,
                null, null, null, List.of(), List.of(), unknown, null, null);
    }
}
