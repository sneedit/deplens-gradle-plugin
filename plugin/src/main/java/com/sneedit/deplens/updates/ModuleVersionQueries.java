/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.updates;

import com.sneedit.deplens.inventory.InventoryEntry;
import com.sneedit.deplens.resolution.ConfigurationSpec;
import org.gradle.api.artifacts.*;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;
import org.jspecify.annotations.Nullable;

import java.util.*;

import static com.sneedit.deplens.inventory.InventoryEntry.Type;

/**
 * Runs Gradle module queries: selectors, candidate walks and variant-compatible resolution.
 */
final class ModuleVersionQueries {
    /**
     * Keeps this utility class static-only.
     */
    private ModuleVersionQueries() {
    }

    /**
     * Collects effective version selectors from dependencies and constraints, with a plugin fallback.
     */
    static Set<String> selectors(InventoryEntry e, Configuration source, String coordinate) {
        Set<String> result = new LinkedHashSet<>();
        source.getAllDependencies().forEach(d -> {
            if (d instanceof ExternalModuleDependency m && coordinate.equals(d.getGroup() + ":" + d.getName()))
                addSelector(result, m.getVersionConstraint());
        });
        source.getAllDependencyConstraints().forEach(c -> {
            if (coordinate.equals(c.getGroup() + ":" + c.getName())) addSelector(result, c.getVersionConstraint());
        });
        if (result.isEmpty() && e.type == Type.PLUGIN) result.addAll(e.declaredSelectors);
        return result;
    }

    /**
     * Adds the strongest nonblank selector: strict, then required, then preferred.
     */
    static void addSelector(Set<String> result, VersionConstraint v) {
        String selector = !v.getStrictVersion().isBlank() ? v.getStrictVersion() : !v.getRequiredVersion().isBlank() ? v.getRequiredVersion() : v.getPreferredVersion();
        if (!selector.isBlank()) result.add(selector);
    }

    /**
     * Creates a detached, nontransitive query for one coordinate and selector.
     */
    static Configuration query(ConfigurationContainer container, DependencyHandler dependencies, String coordinate, String selector) {
        Configuration c = container.detachedConfiguration(dependencies.create(coordinate + ":" + selector));
        c.setTransitive(false);
        return c;
    }

    /**
     * Walks Gradle's candidates by rejecting each one, then returns versions newest first.
     */
    static List<String> list(ConfigurationContainer container, DependencyHandler dependencies, String coordinate) {
        return list(container, dependencies, coordinate, "+");
    }

    /**
     * Walks Gradle's candidates by rejecting each one, then returns versions newest first.
     */
    static List<String> list(ConfigurationContainer container, DependencyHandler dependencies, String coordinate, String selector) {
        return list(container, dependencies, coordinate, selector, edge -> {
        });
    }

    /**
     * Also exposes terminal failures so callers can distinguish deliberate rejection from HTTP 429.
     */
    static List<String> list(ConfigurationContainer container, DependencyHandler dependencies, String coordinate, String selector,
                             java.util.function.Consumer<UnresolvedDependencyResult> failures) {
        Configuration c = query(container, dependencies, coordinate, selector);
        Set<String> result = new LinkedHashSet<>();
        c.getResolutionStrategy().componentSelection(rules -> rules.all(selection -> {
            result.add(selection.getCandidate().getVersion());
            selection.reject("DepLens records the full candidate walk");
        }));
        c.getIncoming().getResolutionResult().getRootComponent().get(); // Rejection is deliberate, not a resolution verdict.
        c.getIncoming().getResolutionResult().getAllDependencies().stream()
                .filter(UnresolvedDependencyResult.class::isInstance).map(UnresolvedDependencyResult.class::cast).forEach(failures);
        return result.stream().sorted((a, b) -> Versions.compare(b, a)).toList();
    }

    /**
     * Bundles independent candidate walks so Gradle can schedule their metadata work together.
     * No consumer graph or policy is combined: every module still rejects and records its own candidates.
     */
    static Map<String, List<String>> listBatch(ConfigurationContainer container, DependencyHandler dependencies,
                                               Collection<String> coordinates,
                                               java.util.function.Consumer<UnresolvedDependencyResult> failures) {
        Map<String, Set<String>> versions = new java.util.concurrent.ConcurrentHashMap<>();
        coordinates.forEach(coordinate -> versions.put(coordinate, Collections.synchronizedSet(new LinkedHashSet<>())));
        Dependency[] queries = coordinates.stream().map(coordinate -> dependencies.create(coordinate + ":+"))
                .toArray(Dependency[]::new);
        Configuration configuration = container.detachedConfiguration(queries);
        configuration.setTransitive(false);
        configuration.getResolutionStrategy().componentSelection(rules -> rules.all(selection -> {
            var candidate = selection.getCandidate();
            var recorded = versions.get(candidate.getGroup() + ":" + candidate.getModule());
            if (recorded != null) {
                recorded.add(candidate.getVersion());
                selection.reject("DepLens records the full candidate walk");
            }
        }));
        var graph = configuration.getIncoming().getResolutionResult();
        graph.getRootComponent().get();
        graph.getAllDependencies().stream().filter(UnresolvedDependencyResult.class::isInstance)
                .map(UnresolvedDependencyResult.class::cast).forEach(failures);
        Map<String, List<String>> result = new LinkedHashMap<>();
        coordinates.forEach(coordinate -> {
            var recorded = Objects.requireNonNull(versions.get(coordinate));
            synchronized (recorded) {
                result.put(coordinate, recorded.stream().sorted((a, b) -> Versions.compare(b, a)).toList());
            }
        });
        return result;
    }

    /**
     * Installs the update policy as a selection rule while keeping the current version resolvable.
     */
    static void filter(Configuration c, RulePolicy p, String coordinate, String current) {
        c.getResolutionStrategy().componentSelection(rules -> rules.all(selection -> {
            if (!coordinate.equals(selection.getCandidate().getGroup() + ":" + selection.getCandidate().getModule()))
                return;
            String v = selection.getCandidate().getVersion();
            if (v.equals(current)) return; // Ignoring an update must not make the installed baseline unresolvable.
            if ((!p.preReleases && p.preRelease(v)) || p.isIgnored(v)) selection.reject("DepLens update policy");
        }));
    }

    /**
     * Returns a selected direct module version, or null when the graph cannot be resolved.
     */
    static @Nullable String resolve(Configuration c) {
        return resolve(c, null);
    }

    /**
     * A newer release may require a higher JVM or incompatible consumer attributes.
     * Keep it visible as latestAvailable, but find the newest version the build can consume.
     * Candidate-induced transitive version rejections retry only after the installed graph resolves.
     * Repository failures, broken metadata and variant failures of other modules still fail the check.
     */
    static @Nullable String resolveAllowed(Configuration template, String coordinate, List<String> versions) {
        return resolveAllowed(template, coordinate, versions, edge -> {
        });
    }

    /**
     * Resolves the consumer graph and reports its terminal unresolved edges to the caller.
     */
    static @Nullable String resolveAllowed(Configuration template, String coordinate, List<String> versions,
                                           java.util.function.Consumer<UnresolvedDependencyResult> failures) {
        return resolveAllowed(template, coordinate, versions, failures, false, null);
    }

    /**
     * Uses explicitly ordered original candidates when a custom normalization overrides Gradle ordering.
     */
    static @Nullable String resolveAllowed(Configuration template, String coordinate, List<String> versions,
                                           java.util.function.Consumer<UnresolvedDependencyResult> failures, boolean normalizedOrder, @Nullable String current) {
        // A failed newest candidate resolves the template before we copy it for older candidates.
        ConfigurationSpec.retainResolutionRules(template);
        Configuration attempt = template;
        int candidate = 0;
        Set<String> tried = new HashSet<>();
        boolean baselineVerified = false;
        if (normalizedOrder && !versions.isEmpty()) {
            String version = versions.get(candidate++);
            tried.add(version);
            attempt = fixedCandidate(template, coordinate, version);
        }
        while (true) {
            var graph = attempt.getIncoming().getResolutionResult();
            var missing = graph.getAllDependencies().stream().filter(UnresolvedDependencyResult.class::isInstance)
                    .map(UnresolvedDependencyResult.class::cast).toList();
            if (missing.isEmpty()) return resolve(attempt, coordinate);
            if (missing.stream().anyMatch(edge -> !targetVariantIncompatible(edge, coordinate))) {
                // A new target can introduce incompatible transitive version requirements.
                // Only treat known rejections as compatibility failures, and prove that the
                // same graph resolves with the installed target before trying older versions.
                boolean rejected = current != null && missing.stream().allMatch(edge ->
                        targetVariantIncompatible(edge, coordinate) || versionRejected(edge.getFailure()));
                if (rejected && current != null && !baselineVerified) {
                    var baselineFailures = new ArrayList<UnresolvedDependencyResult>();
                    baselineVerified = current.equals(resolve(fixedCandidate(template, coordinate, current), coordinate, baselineFailures::add));
                    if (!baselineVerified) baselineFailures.forEach(failures);
                }
                if (!rejected || !baselineVerified) {
                    missing.forEach(failures);
                    missing.forEach(edge -> diagnostic(template.getName() + " " + edge.getAttempted().getDisplayName(), edge.getFailure()));
                    return null;
                }
            }
            // The target may be resolved even when one of its transitive edges is rejected.
            for (var edge : graph.getRootComponent().get().getDependencies()) {
                if (edge instanceof ResolvedDependencyResult selected && selected.getSelected().getModuleVersion() != null) {
                    var module = selected.getSelected().getModuleVersion();
                    if (coordinate.equals(module.getGroup() + ":" + module.getName())) tried.add(module.getVersion());
                }
            }
            // Dynamic selection can differ from list ordering because of build rules and constraints.
            // Skip only versions proven to have failed, never the presumed first '+' candidate.
            for (var edge : missing) {
                for (Throwable cause : RepositoryBackoff.causes(List.of(edge.getFailure()))) {
                    Object failure = RepositoryBackoff.invoke(cause, "getFailure");
                    Object target = failure == null ? null : RepositoryBackoff.invoke(failure, "getTargetComponent");
                    if (target instanceof org.gradle.api.artifacts.component.ModuleComponentIdentifier module
                            && coordinate.equals(module.getGroup() + ":" + module.getModule()))
                        tried.add(module.getVersion());
                }
            }
            // The first query uses '+'. Retry fixed candidates on fresh configurations,
            // preserving constraints, peers, attributes and component selection rules.
            while (candidate < versions.size() && tried.contains(versions.get(candidate))) candidate++;
            if (candidate >= versions.size()) {
                if (baselineVerified) return current;
                missing.forEach(failures);
                return null;
            }
            String version = versions.get(candidate++);
            tried.add(version);
            attempt = fixedCandidate(template, coordinate, version);
        }
    }

    private static Configuration fixedCandidate(Configuration template, String coordinate, String version) {
        Configuration attempt = template.copyRecursive();
        attempt.getResolutionStrategy().eachDependency(details -> {
            if (coordinate.equals(details.getRequested().getGroup() + ":" + details.getRequested().getName()))
                details.useVersion(version);
        });
        return attempt;
    }

    /**
     * Recognizes deterministic module rejections without exposing exception messages.
     */
    static boolean versionRejected(Throwable failure) {
        var causes = RepositoryBackoff.causes(List.of(failure));
        if (causes.stream().anyMatch(cause -> cause instanceof java.io.IOException
                || cause instanceof InterruptedException || cause instanceof java.util.concurrent.CancellationException
                || cause instanceof org.gradle.api.BuildCancelledException || RepositoryBackoff.invoke(cause, "getStatusCode") != null))
            return false;
        for (var cause : causes) {
            Object detail = RepositoryBackoff.invoke(cause, "getFailure");
            if (detail != null && detail.getClass().getSimpleName().equals("ModuleRejectedFailure")) return true;
            // Gradle 8 exposes this fixed rejection diagnostic without a structured failure.
            // Match only its known header and selector, never print its credential-bearing body.
            if (cause.getClass().getName().equals("org.gradle.internal.resolve.ModuleVersionResolveException")
                    && RepositoryBackoff.invoke(cause, "getSelector") instanceof ModuleComponentSelector module) {
                String header = "Cannot find a version of '" + module.getGroup() + ":" + module.getModule()
                        + "' that satisfies the version constraints:\n";
                for (var nested : RepositoryBackoff.causes(List.of(cause))) {
                    if (nested != cause && nested.getClass() != org.gradle.api.GradleException.class) continue;
                    String message = nested.getMessage();
                    if (message != null && message.startsWith(header)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Only a variant mismatch on the queried module can justify trying its older versions.
     * An unrelated unresolved edge cannot be repaired by changing this module's version.
     */
    private static boolean targetVariantIncompatible(UnresolvedDependencyResult edge, String coordinate) {
        return edge.getAttempted() instanceof ModuleComponentSelector module
                && coordinate.equals(module.getGroup() + ":" + module.getModule())
                && variantIncompatible(edge.getFailure());
    }

    /**
     * Recognizes Gradle variant mismatches through the exception chain without looping on cycles.
     */
    static boolean variantIncompatible(Throwable failure) {
        Set<Throwable> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause.getClass().getSimpleName().equals("VariantSelectionByAttributesException")) return true;
            if (cause.getClass().getSimpleName().equals("VariantSelectionException")) {
                try {
                    Object detail = cause.getClass().getMethod("getFailure").invoke(cause);
                    if (Set.of("IncompatibleGraphVariantFailure", "IncompatibleResolutionFailure").contains(detail.getClass().getSimpleName()))
                        return true;
                } catch (ReflectiveOperationException ignored) { /* An unknown failure remains a failed check. */ }
            }
        }
        return false;
    }

    /**
     * Returns a selected direct module version, or null when the graph cannot be resolved.
     */
    static @Nullable String resolve(Configuration c, @Nullable String coordinate) {
        return resolve(c, coordinate, edge -> {
        });
    }

    /**
     * Exposes unresolved edges for rate-limit handling while preserving normal resolution diagnostics.
     */
    static @Nullable String resolve(Configuration c, @Nullable String coordinate,
                                    java.util.function.Consumer<UnresolvedDependencyResult> failures) {
        var graph = c.getIncoming().getResolutionResult();
        boolean unresolved = false;
        for (DependencyResult edge : graph.getAllDependencies()) {
            if (edge instanceof UnresolvedDependencyResult missing) {
                failures.accept(missing);
                diagnostic(c.getName() + " " + missing.getAttempted().getDisplayName(), missing.getFailure());
                unresolved = true;
            }
        }
        if (unresolved) return null;
        for (DependencyResult edge : graph.getRootComponent().get().getDependencies()) {
            if (edge instanceof ResolvedDependencyResult r && r.getSelected().getModuleVersion() != null) {
                var v = r.getSelected().getModuleVersion();
                if (coordinate == null || coordinate.equals(v.getGroup() + ":" + v.getName())) return v.getVersion();
            }
        }
        return null;
    }

    /**
     * Logs exception type names and structured failure types, keeping credential-bearing messages out.
     */
    static void diagnostic(String context, Throwable failure) {

        Set<String> causes = new LinkedHashSet<>();
        for (Throwable cause = failure; cause != null && causes.add(cause.getClass().getName()); cause = cause.getCause()) {
            try {
                Object detail = cause.getClass().getMethod("getFailure").invoke(cause);
                if (detail != null) causes.add(detail.getClass().getName());
            } catch (ReflectiveOperationException ignored) { /* Most exceptions have no structured Gradle failure. */ }
        }
        org.gradle.api.logging.Logging.getLogger(UpdateEngine.class).info("DepLens query {}: {}", context, causes);
    }

}
