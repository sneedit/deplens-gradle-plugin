/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.updates;

import com.sneedit.deplens.inventory.InventoryEntry;
import com.sneedit.deplens.inventory.InventoryEntry.Resolution;
import com.sneedit.deplens.inventory.InventoryEntry.Status;
import com.sneedit.deplens.inventory.InventoryEntry.Type;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;

import static com.sneedit.deplens.updates.ModuleVersionQueries.diagnostic;
import static com.sneedit.deplens.updates.ModuleVersionQueries.selectors;
import static com.sneedit.deplens.updates.UpdateResults.*;

/**
 * Checks one module against each recorded consumer configuration and records its verdict.
 */
final class ModuleUpdateChecker {
    private final UpdateResolutionContexts contexts;
    private final VersionQueryCache queries;
    private final boolean checkBuildContext;

    ModuleUpdateChecker(UpdateResolutionContexts contexts, VersionQueryCache queries, boolean checkBuildContext) {
        this.contexts = contexts;
        this.queries = queries;
        this.checkBuildContext = checkBuildContext;
    }

    /**
     * Checks a module in each original configuration so Gradle gets the final say on compatibility.
     */
    void check(InventoryEntry e, RulePolicy policy) {
        e.fastCheck = !checkBuildContext;
        var context = e.type == Type.PLUGIN ? (e.project.equals("<settings>") ? contexts.settingsContext() : contexts.scriptContext()) : contexts.projectContext();
        String coordinate = e.type == Type.PLUGIN ? e.coordinate + ":" + e.coordinate + ".gradle.plugin" : e.coordinate;
        List<Resolution> resolutions = new ArrayList<>(e.resolutions);
        if (resolutions.isEmpty()) {
            unchecked(e, "No resolved current version; update comparison is unavailable");
            return;
        }
        boolean missingContext = false;
        for (Resolution resolution : resolutions) {
            try {
                String name = e.type == Type.PLUGIN ? "classpath" : resolution.configuration().replaceFirst("^buildscript:", "");
                var scoped = (resolution.configuration().startsWith("buildscript:") ? contexts.scriptContext() : context).forConfiguration(name);
                Configuration source = scoped.find(name);
                if (source == null) {
                    missingContext = true;
                    unchecked(e, "Configuration context unavailable: " + resolution.configuration());
                    continue;
                }
                String current = resolution.version();
                Consumer<UnresolvedDependencyResult> recordFailure = edge -> {
                    var failure = ResolutionFailureDetails.describe(resolution.configuration(), edge);
                    if (!e.resolutionFailures.contains(failure)) e.resolutionFailures.add(failure);
                };
                List<String> all = queries.list(scoped, coordinate, "+", recordFailure);
                if (all.isEmpty()) {
                    if (e.resolutionFailures.stream().noneMatch(failure -> failure.configuration().equals(resolution.configuration())))
                        throw new IllegalStateException("No listing available");
                    failed(e);
                    continue;
                }
                List<String> accepted = all.stream()
                        .filter(v -> policy.preReleases || !policy.preRelease(v))
                        .filter(v -> !policy.isIgnored(v)).sorted((a, b) -> policy.compare(b, a)).toList();
                if (all.stream().anyMatch(policy::isIgnored)) e.statuses.add(Status.IGNORED);
                String within = null;
                Set<String> matching = new HashSet<>();
                boolean importedBom = e.type == Type.PLATFORM && resolution.reasons().contains(Resolution.IMPORTED_BOM);
                Set<String> selectors = importedBom ? e.declaredSelectors : selectors(e, source, coordinate);
                // Gradle interprets ranges and latest.release; the original text is never rewritten or frozen.
                for (String selector : selectors) {
                    String version = queries.within(scoped, coordinate, selector, e.type == Type.PLATFORM, policy, current);
                    if (version != null && (within == null || policy.compare(version, within) > 0)) within = version;
                    matching.addAll(queries.list(scoped, coordinate, selector, recordFailure));
                }
                List<String> outside = selectors.isEmpty() ? List.of() : accepted.stream()
                        .filter(v -> policy.compare(v, current) > 0 && !matching.contains(v)).toList();
                String buildAllowed = checkBuildContext ? queries.allowed(scoped, name, coordinate, e.type == Type.PLATFORM,
                        importedBom, policy, current, accepted,
                        queryContext -> {
                            var failures = new ArrayList<UnresolvedDependencyResult>();
                            Configuration querySource = Objects.requireNonNull(queryContext.find(name), "Missing consumer configuration");
                            String selected = BuildContextResolver.resolve(queryContext, querySource, coordinate, e.type == Type.PLATFORM,
                                    importedBom, policy, current, accepted, failures::add);
                            return new RepositoryBackoff.Attempt<@Nullable String>(selected, failures);
                        }, recordFailure) : null;
                if (checkBuildContext && buildAllowed == null && !accepted.isEmpty()) failed(e);
                if (!e.platforms.isEmpty() || e.versionConstraints.stream().anyMatch(c -> c.strictly() != null && !c.strictly().isBlank())
                        || (checkBuildContext && !accepted.isEmpty() && !accepted.getFirst().equals(buildAllowed)))
                    e.statuses.add(Status.CONSTRAINED);
                addContext(e, resolution.configuration(), current, within, buildAllowed, outside, accepted, policy);
            } catch (Exception failure) {
                if (Thread.currentThread().isInterrupted())
                    throw new IllegalStateException("Update check interrupted", failure);
                diagnostic(resolution.configuration(), failure);
                var detail = ResolutionFailureDetails.describe(resolution.configuration(), coordinate, failure);
                if (!e.resolutionFailures.contains(detail)) e.resolutionFailures.add(detail);
                failed(e);
            }
        }
        e.candidates.sort((a, b) -> policy.compare(b, a));
        if (missingContext) {
            e.statuses.add(Status.NOT_CHECKED);
            e.statuses.remove(Status.UP_TO_DATE);
        }
    }


}
