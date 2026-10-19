/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.resolution;

import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.internal.artifacts.DependencyResolutionServices;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A resolver owned by one analysis invocation, with Gradle's transport, cache and selection engine.
 */
public final class ResolutionContext {
    /**
     * Detached configuration container used to construct native resolution queries.
     */
    public final ConfigurationContainer configurations;
    /**
     * Dependency handler belonging to the detached resolver.
     */
    public final DependencyHandler dependencies;
    private final Map<String, ConfigurationSpec> specifications;
    private final org.gradle.api.model.ObjectFactory objects;
    private final Map<String, Configuration> templates = new HashMap<>();
    private final Map<String, ResolutionContext> scoped = new HashMap<>();
    private final NativeResolverFactory factory;
    private final List<RepositorySpec> repositories;
    private final @Nullable SchemaSpec schema;
    private final List<?> queryIdentity;
    private final List<?> repositoryIdentity;
    private final DependencyResolutionServices resolver;
    private final Map<String, Optional<List<?>>> graphIdentities = new HashMap<>();

    /**
     * Rebuilds repositories, configurations and attribute rules in a detached resolver.
     *
     * @param factory        factory for detached Gradle resolvers
     * @param repositories   repositories in declaration order
     * @param specifications captured configuration specifications
     * @param schema         Gradle attribute schema or its captured rules
     */
    public ResolutionContext(NativeResolverFactory factory, List<RepositorySpec> repositories, Map<String, ConfigurationSpec> specifications, @Nullable SchemaSpec schema) {
        this.factory = factory;
        this.repositories = repositories;
        this.schema = schema;
        repositoryIdentity = repositories.stream().map(RepositorySpec::queryIdentity).toList();
        queryIdentity = java.util.Collections.unmodifiableList(java.util.Arrays.asList(
                repositoryIdentity,
                schema == null ? List.of() : List.copyOf(schema.precedence),
                schema == null ? Map.of() : new java.util.LinkedHashMap<>(schema.attributes)));
        resolver = factory.configured(queryIdentity, repositories, schema);
        objects = resolver.getObjectFactory();
        configurations = resolver.getConfigurationContainer();
        dependencies = resolver.getDependencyHandler();
        this.specifications = specifications;
    }

    /**
     * Identifies the effective repositories and schema used by detached module listings.
     * Configuration-specific filters are applied by {@link #forConfiguration(String)} first.
     *
     * @return invocation-local identity; never log or persist it because it includes credentials
     */
    public List<?> queryIdentity() {
        return queryIdentity;
    }

    /**
     * Candidate walks reject versions before variant matching, so their identity needs no schema.
     *
     * @return invocation-local repository identity containing sensitive authentication values
     */
    public List<?> repositoryIdentity() {
        return repositoryIdentity;
    }

    /**
     * Reconstructs the same query context after a temporary repository failure.
     *
     * @return replacement context with a fresh metadata session
     */
    public ResolutionContext fresh() {
        factory.invalidate(queryIdentity);
        return new ResolutionContext(factory, repositories, specifications, schema);
    }

    /**
     * Uses the replacement metadata session after another query recovered from a rate limit.
     *
     * @return this context or its replacement using the current metadata session
     */
    public ResolutionContext current() {
        return factory.configured(queryIdentity, repositories, schema) == resolver ? this
                : new ResolutionContext(factory, repositories, specifications, schema);
    }

    /**
     * Identifies all captured inputs to a reconstructed consumer graph.
     *
     * @param name original configuration name
     * @return comparable graph inputs, or null for unknown configurations or opaque user rules
     */
    public @Nullable List<?> graphIdentity(String name) {
        return graphIdentities.computeIfAbsent(name, n -> {
            ConfigurationSpec specification = specifications.get(n);
            return Optional.ofNullable(specification == null ? null : specification.graphIdentity());
        }).orElse(null);
    }

    /**
     * Returns the resolver scope with repositories filtered for this configuration.
     *
     * @param name original configuration name
     * @return resolution context with configuration-specific repository filters
     */
    public ResolutionContext forConfiguration(String name) {
        if (repositories.stream().noneMatch(r -> !r.includedConfigurations.isEmpty() || !r.excludedConfigurations.isEmpty() || !r.requiredAttributes.isEmpty()))
            return this;
        return scoped.computeIfAbsent(name, n -> new ResolutionContext(factory,
                repositories.stream().filter(r -> r.accepts(specifications.get(n))).toList(), specifications, schema));
    }

    /**
     * Finds a reconstructed configuration by its original name.
     *
     * @param name original configuration name
     * @return reconstructed configuration, or null when no specification exists
     */
    public @Nullable Configuration find(String name) {
        ConfigurationSpec specification = specifications.get(name);
        if (specification == null) return null;
        return templates.computeIfAbsent(name, n -> specification.create(configurations, dependencies, objects));
    }
}
