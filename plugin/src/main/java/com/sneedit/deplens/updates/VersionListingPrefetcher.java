/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.updates;

import com.sneedit.deplens.inventory.BuildInventory;
import com.sneedit.deplens.inventory.InventoryEntry.Status;
import com.sneedit.deplens.inventory.InventoryEntry.Type;
import com.sneedit.deplens.resolution.AnalysisModel;
import com.sneedit.deplens.resolution.ResolutionContext;

import java.util.*;

/**
 * Groups independent version listings by effective repository context before entry checks.
 */
final class VersionListingPrefetcher {
    private final AnalysisModel model;
    private final Set<Type> types;
    private final UpdateResolutionContexts contexts;
    private final VersionQueryCache queries;

    VersionListingPrefetcher(AnalysisModel model, Set<Type> types, UpdateResolutionContexts contexts, VersionQueryCache queries) {
        this.model = model;
        this.types = types;
        this.contexts = contexts;
        this.queries = queries;
    }

    /**
     * Groups only independent listings with identical effective repository settings.
     */
    void prefetch(BuildInventory report) {
        record Batch(ResolutionContext context, Set<String> coordinates) {
        }
        Map<List<?>, Batch> batches = new LinkedHashMap<>();
        for (var entry : report.entries) {
            if (!types.contains(entry.type) || entry.type == Type.GRADLE || !entry.build.equals(model.build)
                    || (!entry.project.equals(model.project) && !(entry.project.equals("<settings>") && model.project.equals(":"))))
                continue;
            var policy = model.policies.get(entry.id());
            if (policy == null || policy.pinned || entry.isBomManaged() || entry.isVersionAligned()
                    || !entry.hasUpdateTarget() || entry.statuses.contains(Status.FAILED)) continue;
            for (var resolution : entry.resolutions) {
                try {
                    String name = entry.type == Type.PLUGIN ? "classpath"
                            : resolution.configuration().replaceFirst("^buildscript:", "");
                    var context = resolution.configuration().startsWith("buildscript:") ? contexts.scriptContext()
                            : entry.type == Type.PLUGIN ? (entry.project.equals("<settings>") ? contexts.settingsContext() : contexts.scriptContext())
                            : contexts.projectContext();
                    var scoped = context.forConfiguration(name);
                    if (scoped.find(name) == null) continue;
                    String coordinate = entry.type == Type.PLUGIN
                            ? entry.coordinate + ":" + entry.coordinate + ".gradle.plugin" : entry.coordinate;
                    batches.computeIfAbsent(scoped.repositoryIdentity(), ignored -> new Batch(scoped, new LinkedHashSet<>()))
                            .coordinates().add(coordinate);
                } catch (RuntimeException failure) {
                    if (Thread.currentThread().isInterrupted()) throw failure;
                    // Preserve the ordinary entry-specific error reporting if context preparation fails.
                }
            }
        }
        batches.values().forEach(batch -> queries.prefetch(batch.context(), batch.coordinates()));
    }

}
