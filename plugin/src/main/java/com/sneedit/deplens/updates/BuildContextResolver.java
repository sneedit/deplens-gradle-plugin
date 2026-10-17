/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.updates;

import com.sneedit.deplens.resolution.ResolutionContext;

import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.sneedit.deplens.updates.ModuleVersionQueries.filter;
import static com.sneedit.deplens.updates.ModuleVersionQueries.resolveAllowed;

/**
 * Builds the candidate consumer graph while preserving peers, constraints and resolution rules.
 */
final class BuildContextResolver {
    private BuildContextResolver() {
    }

    /**
     * Builds and resolves the complete consumer graph on a cache miss, preserving peers and rules.
     */
    static @Nullable String resolve(ResolutionContext scoped, Configuration source,
                                    String coordinate, boolean platform, boolean importedBom,
                                    RulePolicy policy, String current, List<String> accepted,
                                    Consumer<UnresolvedDependencyResult> failures) {
        ConfigurationContainer contextContainer = scoped.configurations;
        var dependencies = scoped.dependencies;
        // An imported BOM is management metadata, not a dependency in the consuming
        // configuration. Resolve it independently of processor/tool variant attributes.
        Configuration allowed = importedBom ? contextContainer.detachedConfiguration() : source.copyRecursive();
        allowed.setCanBeResolved(true);
        allowed.setCanBeConsumed(false);
        // Keep peer declarations and transitives: BOM constraints only participate in a transitive graph.
        allowed.setTransitive(true);
        String target = coordinate;
        ExternalModuleDependency original = source.getAllDependencies().stream()
                .filter(d -> d instanceof ExternalModuleDependency && target.equals(d.getGroup() + ":" + d.getName()))
                .map(d -> (ExternalModuleDependency) d).findFirst().orElse(null);
        ExternalModuleDependency query = original == null ? (ExternalModuleDependency) dependencies.create(coordinate + ":+") : original.copy();
        query.version(v -> {
            v.require("+");
            if (original != null) {
                if (!original.getVersionConstraint().getStrictVersion().isBlank())
                    v.strictly(original.getVersionConstraint().getStrictVersion());
                v.reject(original.getVersionConstraint().getRejectedVersions().toArray(String[]::new));
            }
        });
        // Rebuild the copied dependency set: iterator removal can leave Gradle 9's composite set stale.
        List<Dependency> peers = (importedBom ? Stream.<Dependency>empty() : source.getAllDependencies().stream())
                .filter(d -> !target.equals(d.getGroup() + ":" + d.getName())).map(Dependency::copy).toList();
        allowed.getDependencies().clear();
        allowed.getDependencies().addAll(peers);
        allowed.getDependencies().add(platform && original == null ? dependencies.platform(query) : query);
        allowed.getResolutionStrategy().deactivateDependencyLocking();
        filter(allowed, policy, coordinate, current);
        boolean strictOrForced = !query.getVersionConstraint().getStrictVersion().isBlank()
                || allowed.getAllDependencyConstraints().stream().anyMatch(c -> coordinate.equals(c.getGroup() + ":" + c.getName())
                && !c.getVersionConstraint().getStrictVersion().isBlank())
                || allowed.getResolutionStrategy().getForcedModules().stream()
                .anyMatch(m -> coordinate.equals(m.getGroup() + ":" + m.getName()));
        return resolveAllowed(allowed, coordinate, accepted, failures, policy.normalization != null && !strictOrForced, current);
    }
}
