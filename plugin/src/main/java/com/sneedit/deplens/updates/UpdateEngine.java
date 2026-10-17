/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.updates;

import com.sneedit.deplens.inventory.BuildInventory;
import com.sneedit.deplens.inventory.InventoryEntry;
import com.sneedit.deplens.inventory.InventoryEntry.Status;
import com.sneedit.deplens.inventory.InventoryEntry.Type;
import com.sneedit.deplens.resolution.AnalysisModel;
import com.sneedit.deplens.resolution.CurrentInventory;
import com.sneedit.deplens.resolution.NativeResolverFactory;

import java.util.Set;

import static com.sneedit.deplens.updates.UpdateResults.failed;
import static com.sneedit.deplens.updates.UpdateResults.unchecked;

/**
 * Coordinates one project's update checks using its original repositories and configurations.
 * ModuleVersionQueries handles Gradle's candidate walk, GradleReleaseChecker handles the
 * wrapper feed, and UpdateResults records the verdict. Gradle stays the source of truth.
 */
public final class UpdateEngine {
    private final AnalysisModel model;
    private final Set<Type> types;
    private final UpdateResolutionContexts contexts;
    private final ModuleUpdateChecker modules;
    private final VersionListingPrefetcher listings;

    /**
     * Creates an update checker for one captured project and the requested component types.
     *
     * @param model    captured project analysis
     * @param services Gradle resolution service factory
     * @param types    component types to check
     */
    public UpdateEngine(AnalysisModel model,
                        NativeResolverFactory services, Set<Type> types) {
        this(model, services, types, new VersionQueryCache());
    }

    /**
     * Creates a checker sharing candidate listings with other models in the same invocation.
     *
     * @param model    captured project analysis
     * @param services Gradle resolution service factory
     * @param types    component types to check
     * @param queries  invocation-local cache of policy-independent module listings
     */
    public UpdateEngine(AnalysisModel model,
                        NativeResolverFactory services, Set<Type> types, VersionQueryCache queries) {
        this(model, services, types, queries, model.checkBuildContext);
    }

    /**
     * Creates a checker with an invocation-specific graph-check override.
     */
    public UpdateEngine(AnalysisModel model, NativeResolverFactory services, Set<Type> types,
                        VersionQueryCache queries, boolean checkBuildContext) {
        this.model = model;
        this.types = types;
        this.contexts = new UpdateResolutionContexts(model, services);
        this.modules = new ModuleUpdateChecker(contexts, queries, checkBuildContext);
        this.listings = new VersionListingPrefetcher(model, types, contexts, queries);
    }

    /**
     * Refreshes current versions that may have changed since the model was captured.
     *
     * @param report inventory to read or update
     */
    public void refresh(BuildInventory report) {
        if (CurrentInventory.needsRefresh(model, report, model.configurations))
            CurrentInventory.refresh(model, report, contexts.projectContext(), model.configurations, "");
        if (CurrentInventory.needsRefresh(model, report, model.scriptConfigurations))
            CurrentInventory.refresh(model, report, contexts.scriptContext(), model.scriptConfigurations, "buildscript:");
    }

    /**
     * Checks this project's entries and records skips, policy decisions and failures in the report.
     *
     * @param report inventory to read or update
     */
    public void check(BuildInventory report) {
        refresh(report);
        listings.prefetch(report);
        for (InventoryEntry e : report.entries) {
            if (!types.contains(e.type) || !e.build.equals(model.build)) continue;
            if (!e.project.equals(model.project) && !(e.project.equals("<settings>") && model.project.equals(":")))
                continue;
            RulePolicy policy = model.policies.get(e.id());
            if (policy == null) continue;
            e.appliedRule = policy.description;
            var normalization = policy.normalization;
            e.versionNormalizationPattern = normalization == null ? null : normalization.pattern().pattern();
            e.versionNormalizationReplacement = normalization == null ? null : normalization.replacement();
            if (policy.pinned) {
                e.statuses.remove(Status.NOT_CHECKED);
                e.statuses.add(Status.PINNED);
                continue;
            }
            if (e.isBomManaged() || e.isVersionAligned()) {
                e.statuses.remove(Status.NOT_CHECKED);
                e.statuses.remove(Status.UPDATE_AVAILABLE);
                e.statuses.add(e.isBomManaged() ? Status.BOM_MANAGED : Status.VERSION_ALIGNED);
                e.recommendedVersion = null;
                continue;
            }
            if (!e.hasUpdateTarget()) {
                unchecked(e, "Local component has no independently versioned update target");
                continue;
            }
            if (e.statuses.contains(Status.FAILED)) {
                report.incomplete("Current resolution failed: " + e.id());
                continue;
            }
            try {
                if (e.type == Type.GRADLE) GradleReleaseChecker.check(model, e, policy);
                else modules.check(e, policy);
                if (e.statuses.contains(Status.FAILED)) report.incomplete("Update query failed: " + e.id());
            } catch (Exception failure) {
                if (Thread.currentThread().isInterrupted())
                    throw new IllegalStateException("Update check interrupted", failure);
                failed(e);
                report.incomplete("Update query failed: " + e.id());
            }
        }
    }

}
