/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */
package com.sneedit.deplens.updates;

import com.google.gson.Gson;
import com.sneedit.deplens.inventory.InventoryEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.sneedit.deplens.inventory.InventoryEntry.Status.*;
import static org.assertj.core.api.Assertions.assertThat;

class UpdateResultsTest {
    private final InventoryEntry entry = new InventoryEntry("/build", ":", InventoryEntry.Type.DEPENDENCY, "example:lib");
    private final RulePolicy policy = new RulePolicy();

    private void context(List<String> versions, String allowed) {
        UpdateResults.addContext(entry, "runtimeClasspath", "1.0.0", "1.0.0", allowed, List.of(), versions, policy);
    }

    @Test
    void emptyPolicyResultsAreSuccessfulButDoNotProveUpToDate() {
        context(List.of(), "1.0.0");
        assertThat(entry.statuses).containsExactly(NO_MATCHING_CANDIDATES);
        assertThat(entry.updateContexts.getFirst().noMatchingCandidates()).isTrue();
        var copy = new Gson().fromJson(new Gson().toJson(entry.updateContexts.getFirst()), InventoryEntry.UpdateContext.class);
        assertThat(copy.noMatchingCandidates()).isTrue();
        assertThat(entry.hasRecommendedUpdate()).isFalse();
    }

    @Test
    void acceptedCurrentVersionProvesUpToDate() {
        context(List.of("1.0.0"), "1.0.0");
        assertThat(entry.statuses).containsExactly(UP_TO_DATE);
        assertThat(entry.updateContexts.getFirst().noMatchingCandidates()).isFalse();
    }

    @Test
    void tierFilteredUpdatesDoNotCountAsMissingPolicyMatches() {
        policy.allowed = "patch";
        context(List.of("2.0.0", "1.0.0"), "2.0.0");
        assertThat(entry.statuses).containsExactly(UP_TO_DATE);
    }

    @Test
    void forcedCurrentVersionLeavesNewerVersionsAvailableOnly() {
        context(List.of("2.0.0", "1.0.1", "1.0.0"), "1.0.0");
        assertThat(entry.statuses).containsExactly(UPDATE_AVAILABLE);
        assertThat(entry.candidates).containsExactly("2.0.0", "1.0.1");
        assertThat(entry.recommendedVersion).isNull();
        assertThat(entry.hasRecommendedUpdate()).isFalse();
    }

    @Test
    void buildAcceptedPatchIsRecommendedBelowAvailableMajor() {
        context(List.of("2.0.0", "1.0.1", "1.0.0"), "1.0.1");
        assertThat(entry.recommendedVersion).isEqualTo("1.0.1");
        assertThat(entry.hasRecommendedUpdate()).isTrue();
        assertThat(entry.candidates).contains("2.0.0");
    }

    @Test
    void fastCheckRetainsPolicyRecommendationWithoutClaimingBuildVerification() {
        entry.fastCheck = true;
        context(List.of("2.0.0", "1.0.0"), "1.0.0");
        assertThat(entry.recommendedVersion).isEqualTo("2.0.0");
        assertThat(entry.hasRecommendedUpdate()).isTrue();
    }

    @Test
    void dynamicSelectorRequiresExplicitChoiceEvenWhenBuildAcceptsUpdate() {
        entry.declaredSelectors.add("1.+");
        context(List.of("2.0.0", "1.0.0"), "2.0.0");
        assertThat(entry.hasRecommendedUpdate()).isFalse();
        assertThat(entry.recommendedVersion).isNull();
    }

    @Test
    void failedContextCannotTurnDiscoveredVersionsIntoRecommendation() {
        context(List.of("2.0.0", "1.0.0"), "2.0.0");
        UpdateResults.failed(entry);
        assertThat(entry.hasRecommendedUpdate()).isFalse();
        assertThat(entry.statuses).contains(FAILED, NOT_CHECKED, UPDATE_AVAILABLE).doesNotContain(NO_MATCHING_CANDIDATES);
    }

    @Test
    void missingPolicyMatchesRemainVisibleWhenAnotherContextIsUpToDate() {
        context(List.of(), "1.0.0");
        context(List.of("1.0.0"), "1.0.0");
        assertThat(entry.statuses).containsExactly(NO_MATCHING_CANDIDATES);
        assertThat(entry.updateContexts).extracting(InventoryEntry.UpdateContext::noMatchingCandidates).containsExactly(true, false);
    }
}
