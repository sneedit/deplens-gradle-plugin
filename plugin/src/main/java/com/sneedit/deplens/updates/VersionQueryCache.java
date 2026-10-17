/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.updates;

import com.sneedit.deplens.resolution.ResolutionContext;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Reuses version queries across project tasks during one invocation only.
 * Consumer graphs are shared only when all captured graph inputs and selection policies match.
 */
public final class VersionQueryCache {
    private record Key(List<?> context, String coordinate, String selector) {
    }

    private final Map<Key, List<String>> listings = new ConcurrentHashMap<>();
    private final RepositoryBackoff backoff = new RepositoryBackoff();

    private record SelectionKey(Key query, boolean platform, String current, boolean preReleases,
                                @Nullable String preReleasePattern, int patternFlags, List<String> ignored,
                                List<String> ignoredVersionPatterns,
                                @Nullable String normalizationPattern, @Nullable String normalizationReplacement) {
    }

    private final Map<SelectionKey, String> selections = new ConcurrentHashMap<>();

    private record GraphKey(SelectionKey selection, List<?> configuration, boolean importedBom,
                            List<String> candidates) {
    }

    private final Map<GraphKey, RepositoryBackoff.Attempt<@Nullable String>> graphs = new ConcurrentHashMap<>();
    private final AtomicLong graphRequests = new AtomicLong();
    private final AtomicLong graphHits = new AtomicLong();
    private final AtomicLong graphFailureHits = new AtomicLong();
    private final AtomicLong graphUncacheable = new AtomicLong();
    private final AtomicLong graphNanos = new AtomicLong();
    private final AtomicLong listingBatches = new AtomicLong();
    private final AtomicLong batchedListings = new AtomicLong();
    private final Map<Object, ReentrantLock> queryLocks = new ConcurrentHashMap<>();

    /**
     * Resolves each equivalent consumer graph once, retaining every caller's own report context.
     * Known permanent failures replay their original edges for each caller's diagnostics and incomplete status.
     */
    @Nullable String allowed(ResolutionContext context, String configuration, String coordinate,
                             boolean platform, boolean importedBom, RulePolicy policy, String current,
                             List<String> candidates, Function<ResolutionContext, RepositoryBackoff.Attempt<@Nullable String>> resolve,
                             Consumer<UnresolvedDependencyResult> failures) {
        graphRequests.incrementAndGet();
        List<?> identity = context.graphIdentity(configuration);
        GraphKey key = identity == null ? null : new GraphKey(
                selectionKey(context, coordinate, "+", platform, policy, current), identity, importedBom, List.copyOf(candidates));
        return locked(key, () -> {
            if (key != null) {
                var cached = graphs.get(key);
                if (cached != null) {
                    graphHits.incrementAndGet();
                    if (cached.value() == null) graphFailureHits.incrementAndGet();
                    cached.failures().forEach(failures);
                    return cached.value();
                }
            } else graphUncacheable.incrementAndGet();
            long start = System.nanoTime();
            RepositoryBackoff.Attempt<@Nullable String> result;
            try {
                result = backoff.execute(context, resolve);
                result.failures().forEach(failures);
            } finally {
                graphNanos.addAndGet(System.nanoTime() - start);
            }
            if (key != null && (result.value() != null || (!result.failures().isEmpty()
                    && result.failures().stream().allMatch(ResolutionFailureDetails::permanent))))
                graphs.put(key, new RepositoryBackoff.Attempt<>(result.value(), List.copyOf(result.failures())));
            return result.value();
        });
    }

    /**
     * Logs aggregate timing and reuse counts without including repository identities or credentials.
     */
    public void logStatistics(org.gradle.api.logging.Logger logger) {
        logger.info("DepLens graph queries: {} requested, {} reused ({} permanent failures), {} with opaque rules; resolution {} ms",
                graphRequests.get(), graphHits.get(), graphFailureHits.get(), graphUncacheable.get(), graphNanos.get() / 1_000_000);
        logger.info("DepLens version listing batches: {} batches, {} module listings prefetched",
                listingBatches.get(), batchedListings.get());
    }

    /**
     * Prefetches uncached '+' listings in bounded groups using Gradle's own resolution scheduler.
     * Ambiguous failures leave the individual query path intact, including per-consumer diagnostics.
     */
    void prefetch(ResolutionContext context, java.util.Collection<String> coordinates) {
        var pending = coordinates.stream().distinct()
                .filter(coordinate -> !listings.containsKey(new Key(context.repositoryIdentity(), coordinate, "+"))).toList();
        for (int offset = 0; offset < pending.size(); offset += 32) {
            var batch = new ArrayList<String>();
            var held = new ArrayList<ReentrantLock>();
            for (String coordinate : pending.subList(offset, Math.min(offset + 32, pending.size()))) {
                var key = new Key(context.repositoryIdentity(), coordinate, "+");
                var lock = queryLocks.computeIfAbsent(key, ignored -> new ReentrantLock());
                // Never wait while holding other batch locks: overlapping project batches cannot deadlock.
                if (!lock.tryLock()) continue;
                if (listings.containsKey(key)) lock.unlock();
                else {
                    held.add(lock);
                    batch.add(coordinate);
                }
            }
            try {
                if (batch.size() < 2) continue;
                listingBatches.incrementAndGet();
                var result = backoff.execute(context, scope -> {
                    var failures = new ArrayList<UnresolvedDependencyResult>();
                    var values = ModuleVersionQueries.listBatch(scope.configurations, scope.dependencies, batch, failures::add);
                    return new RepositoryBackoff.Attempt<>(values, failures);
                });
                // Reject-all walks normally end in ModuleVersionNotFoundException. Any other
                // failure may have interrupted a walk; let the ordinary query establish its result.
                boolean completeWalks = result.failures().stream().allMatch(edge ->
                        ResolutionFailureDetails.permanent(edge) && RepositoryBackoff.causes(List.of(edge.getFailure())).stream()
                                .anyMatch(cause -> cause.getClass().getSimpleName().equals("ModuleVersionNotFoundException")));
                if (!completeWalks) continue;
                result.value().forEach((coordinate, versions) -> {
                    if (!versions.isEmpty()) {
                        listings.put(new Key(context.repositoryIdentity(), coordinate, "+"), versions);
                        batchedListings.incrementAndGet();
                    }
                });
            } catch (RuntimeException failure) {
                if (Thread.currentThread().isInterrupted()) throw failure;
                // Prefetch is optional; regular per-module queries retain the original failure handling.
                org.gradle.api.logging.Logging.getLogger(VersionQueryCache.class)
                        .info("DepLens: version listing batch unavailable; falling back to individual queries.");
            } finally {
                held.forEach(ReentrantLock::unlock);
            }
        }
    }

    /**
     * Lists candidates once per effective resolver context, coordinate and exact selector.
     * Failed or empty walks are not cached so later consumers retain their own diagnostics.
     */
    List<String> list(ResolutionContext context, String coordinate, String selector) {
        return list(context, coordinate, selector, failure -> {
        });
    }

    /**
     * Also returns terminal repository failures to the owning report entry.
     */
    List<String> list(ResolutionContext context, String coordinate, String selector, Consumer<UnresolvedDependencyResult> failures) {
        Key key = new Key(context.repositoryIdentity(), coordinate, selector);
        return locked(key, () -> {
            List<String> versions = listings.get(key);
            if (versions != null) return versions;
            var result = backoff.execute(context, scope -> {
                var unresolved = new ArrayList<UnresolvedDependencyResult>();
                var values = ModuleVersionQueries.list(scope.configurations, scope.dependencies, coordinate, selector, unresolved::add);
                return new RepositoryBackoff.Attempt<>(values, unresolved);
            });
            versions = result.value();
            boolean limited = RepositoryBackoff.rateLimited(result.failures().stream().map(edge -> (Throwable) edge.getFailure()).toList());
            if (limited || versions.isEmpty()) result.failures().forEach(failures);
            if (limited) {
                var failure = result.failures().stream()
                        .filter(edge -> RepositoryBackoff.rateLimited(List.of(edge.getFailure()))).findFirst().orElseThrow();
                throw new IllegalStateException("Repository rate limit persisted after 3 attempts; version listing is incomplete", failure.getFailure());
            }
            if (!versions.isEmpty() && !limited) listings.put(key, versions);
            return versions;
        });
    }

    /**
     * Reuses an independent selector query only when its complete selection policy matches.
     * This does not cache the separate resolution against a consumer's dependency graph.
     */
    @Nullable String within(ResolutionContext context, String coordinate, String selector,
                            boolean platform, RulePolicy policy, String current) {
        SelectionKey key = selectionKey(context, coordinate, selector, platform, policy, current);
        return locked(key, () -> {
            String version = selections.get(key);
            if (version != null) return version;
            var normalization = policy.normalization;
            String fixedSelector = normalization == null ? selector : list(context, coordinate, selector).stream()
                    .filter(v -> v.equals(current) || ((policy.preReleases || !policy.preRelease(v)) && !policy.isIgnored(v)))
                    .max(policy::compare).orElse(null);
            if (fixedSelector == null) return null;
            var result = backoff.execute(context, scope -> {
                var failures = new ArrayList<UnresolvedDependencyResult>();
                var configuration = ModuleVersionQueries.query(scope.configurations, scope.dependencies, coordinate, fixedSelector);
                if (platform) {
                    configuration.getDependencies().clear();
                    configuration.getDependencies().add(scope.dependencies.platform(coordinate + ":" + fixedSelector));
                }
                ModuleVersionQueries.filter(configuration, policy, coordinate, current);
                String selected = ModuleVersionQueries.resolve(configuration, null, failures::add);
                return new RepositoryBackoff.Attempt<@Nullable String>(selected, failures);
            });
            version = result.value();
            if (version != null) selections.put(key, version);
            return version;
        });
    }


    /**
     * Waits interruptibly only for an identical query; independent keys may resolve concurrently.
     */
    private <T extends @Nullable Object> T locked(@Nullable Object key, Supplier<T> query) {
        if (key == null) return query.get();
        var lock = queryLocks.computeIfAbsent(key, ignored -> new ReentrantLock());
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Update query interrupted", interrupted);
        }
        try {
            return query.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Captures every policy field used by module selection, including the preserved baseline.
     */
    private static SelectionKey selectionKey(ResolutionContext context, String coordinate, String selector,
                                             boolean platform, RulePolicy policy, String current) {
        var pattern = policy.preReleasePattern;
        var normalization = policy.normalization;
        return new SelectionKey(new Key(context.queryIdentity(), coordinate, selector),
                platform, current, policy.preReleases, pattern == null ? null : pattern.pattern(),
                pattern == null ? 0 : pattern.flags(), List.copyOf(policy.ignored),
                policy.ignoredVersionPatterns.stream().map(java.util.regex.Pattern::pattern).toList(),
                normalization == null ? null : normalization.pattern().pattern(),
                normalization == null ? null : normalization.replacement());
    }
}
