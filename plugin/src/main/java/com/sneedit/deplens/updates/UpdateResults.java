/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.updates;

import com.sneedit.deplens.inventory.InventoryEntry;
import org.gradle.util.GradleVersion;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.sneedit.deplens.inventory.InventoryEntry.*;

/**
 * Turns version candidates into report contexts, status flags and update recommendations.
 */
final class UpdateResults {
    public static final String VERSION_SELECTION_WARNING = "Version selection warning in ";

    /**
     * Keeps this utility class static-only.
     */
    private UpdateResults() {
    }

    /**
     * Marks a failed query and adds a credential-safe warning to the entry.
     */
    static void failed(InventoryEntry e) {
        e.statuses.remove(Status.UP_TO_DATE);
        e.statuses.add(Status.FAILED);
        e.statuses.add(Status.NOT_CHECKED);
        e.warnings.add("Update query failed; check repository access, metadata and selection rules. Details are intentionally omitted to protect credentials.");
    }

    /**
     * Keeps an entry visibly unchecked and records why it was skipped.
     */
    static void unchecked(InventoryEntry e, String reason) {
        e.statuses.add(Status.NOT_CHECKED);
        e.warnings.add(reason);
    }

    /**
     * Classifies candidates and merges one configuration's update verdict into the entry.
     */
    static void addContext(InventoryEntry e, String configuration, String current, @Nullable String within, @Nullable String buildAllowed,
                           List<String> outside, List<String> versions, RulePolicy policy) {
        String latest = versions.isEmpty() ? null : versions.getFirst();
        if (e.type != Type.GRADLE) {
            if (latest != null && policy.compare(latest, current) < 0)
                selectionWarning(e, configuration, current, latest, "highest candidate accepted by the version policy");
            if (buildAllowed != null && !buildAllowed.equals(latest) && policy.compare(buildAllowed, current) < 0)
                selectionWarning(e, configuration, current, buildAllowed, "highest candidate allowed by the build context");
        }
        List<String> later = versions.stream().filter(v -> (e.type == Type.GRADLE ? GradleVersion.version(v).compareTo(GradleVersion.version(current)) : policy.compare(v, current)) > 0).toList();
        Map<String, Versions.Tier> tiers = new LinkedHashMap<>();
        later.forEach(v -> tiers.put(v, policy.classify(current, v)));
        List<String> unknown = later.stream().filter(v -> tiers.get(v) == Versions.Tier.UNKNOWN).toList();
        String patch = later.stream().filter(v -> tiers.get(v) == Versions.Tier.PATCH).findFirst().orElse(null);
        String minor = later.stream().filter(v -> tiers.get(v) == Versions.Tier.MINOR).findFirst().orElse(null);
        String major = later.stream().filter(v -> tiers.get(v) == Versions.Tier.MAJOR).findFirst().orElse(null);
        List<String> candidates = later.stream().filter(v -> policy.allows(Objects.requireNonNull(tiers.get(v), "Missing candidate tier"))).toList();
        e.updateContexts.add(new UpdateContext(configuration, current, within, versions.isEmpty() ? null : versions.getFirst(), buildAllowed,
                patch, minor, major, List.copyOf(outside), candidates, unknown,
                policy.versionPattern == null ? null : policy.versionPattern.pattern(),
                policy.preReleasePattern == null ? null : policy.preReleasePattern.pattern(), versions.isEmpty()));
        if (versions.isEmpty()) e.statuses.add(Status.NO_MATCHING_CANDIDATES);
        candidates.forEach(v -> {
            if (!e.candidates.contains(v)) e.candidates.add(v);
        });
        if (!candidates.isEmpty()) {
            e.statuses.add(Status.UPDATE_AVAILABLE);
            // Selector/range updates require an explicit choice in phase 3; never silently freeze them.
            boolean dynamic = e.declaredSelectors.stream().anyMatch(s -> s.contains("+") || s.contains("[") || s.contains("(") || s.contains(",") || s.startsWith("latest."));
            String recommendation = e.fastCheck ? candidates.getFirst()
                    : buildAllowed == null ? null : candidates.stream().filter(v -> policy.compare(v, buildAllowed) <= 0).findFirst().orElse(null);
            if (!dynamic && recommendation != null && (e.recommendedVersion == null || policy.compare(recommendation, e.recommendedVersion) > 0))
                e.recommendedVersion = recommendation;
        }
        if (!e.statuses.contains(Status.FAILED)) e.statuses.remove(Status.NOT_CHECKED);
        if (e.candidates.isEmpty() && !e.statuses.contains(Status.NO_MATCHING_CANDIDATES) && !e.statuses.contains(Status.IGNORED) && !e.statuses.contains(Status.FAILED) && !e.statuses.contains(Status.NOT_CHECKED))
            e.statuses.add(Status.UP_TO_DATE);
        else e.statuses.remove(Status.UP_TO_DATE);
    }

    private static void selectionWarning(InventoryEntry entry, String configuration, String current,
                                         String selected, String scope) {
        String warning = VERSION_SELECTION_WARNING + configuration + ": current " + current
                + " ranks above " + selected + " (" + scope + ")."
                + " Selection rules or version ordering may explain this; no downgrade is recommended.";
        if (!entry.warnings.contains(warning)) entry.warnings.add(warning);
    }
}
