/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.resolution;

import org.gradle.api.internal.artifacts.DependencyManagementServices;
import org.gradle.api.internal.artifacts.DependencyResolutionServices;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.file.FileResolver;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Version adapter for Gradle's detached resolver services (8.8 through 9.x).
 */
public final class NativeResolverFactory {
    private final DependencyManagementServices services;
    private final FileResolver files;
    private final FileCollectionFactory collections;
    private final Map<List<?>, DependencyResolutionServices> contexts = new HashMap<>();

    /**
     * Shares Gradle's metadata/cache session for identical repository and schema settings.
     */
    DependencyResolutionServices configured(List<?> identity, List<RepositorySpec> repositories, @Nullable SchemaSpec schema) {
        return contexts.computeIfAbsent(identity, key -> {
            var resolver = create();
            if (schema != null && !schema.attributes.isEmpty())
                schema.install(resolver.getAttributesSchema(), resolver.getObjectFactory());
            else configureJavaSchema(resolver);
            repositories.forEach(r -> r.install(resolver.getResolveRepositoryHandler()));
            return resolver;
        });
    }

    /**
     * A retry needs a fresh metadata session, not Gradle's cached failed resolution.
     */
    void invalidate(List<?> identity) {
        contexts.remove(identity);
    }

    /**
     * Stores the Gradle service factories needed to create detached resolvers.
     *
     * @param services    Gradle resolution service factory
     * @param files       Gradle file resolver
     * @param collections Gradle file collection factory
     */
    public NativeResolverFactory(DependencyManagementServices services, FileResolver files, FileCollectionFactory collections) {
        this.services = services;
        this.files = files;
        this.collections = collections;
    }

    /**
     * Creates dependency resolution services without retaining another project's live model.
     *
     * @return fresh detached Gradle resolution services
     */
    public DependencyResolutionServices create() {
        try {
            try {
                Class<?> parameters = Class.forName("org.gradle.api.internal.artifacts.DependencyManagementParameters");
                Object options = parameters.getConstructor(org.gradle.internal.DisplayName.class, String.class, boolean.class, boolean.class, boolean.class)
                        .newInstance(org.gradle.internal.Describables.of("DepLens analysis"), null, true, true, true);
                return (DependencyResolutionServices) DependencyManagementServices.class.getMethod("newDetachedResolver", parameters).invoke(services, options);
            } catch (ClassNotFoundException | NoSuchMethodException beforeParametersApi) { /* Gradle 8 and early 9. */ }
            try {
                Class<?> contextType = Class.forName("org.gradle.api.internal.initialization.StandaloneDomainObjectContext");
                Object context = contextType.getField("ANONYMOUS").get(null);
                return (DependencyResolutionServices) DependencyManagementServices.class.getMethod("newDetachedResolver", org.gradle.api.internal.DomainObjectContext.class).invoke(services, context);
            } catch (ClassNotFoundException | NoSuchMethodException oldGradle) {
                Class<?> contextType = Class.forName("org.gradle.api.internal.initialization.RootScriptDomainObjectContext");
                Object context = contextType.getField("INSTANCE").get(null);
                Class<?> metadataType = Class.forName("org.gradle.api.internal.artifacts.configurations.DependencyMetaDataProvider");
                Object module = Class.forName("org.gradle.api.internal.artifacts.DefaultModule").getConstructor(String.class, String.class, String.class)
                        .newInstance("com.sneedit.deplens", "analysis", "unspecified");
                Object metadata = Proxy.newProxyInstance(metadataType.getClassLoader(), new Class<?>[]{metadataType}, (proxy, method, args) -> module);
                Class<?> finderType = Class.forName("org.gradle.api.internal.artifacts.dsl.dependencies.ProjectFinder");
                Object finder = Proxy.newProxyInstance(finderType.getClassLoader(), new Class<?>[]{finderType}, (proxy, method, args) -> {
                    throw new IllegalStateException("Analysis queries contain external module metadata, not live project dependencies");
                });
                Method create = Arrays.stream(DependencyManagementServices.class.getMethods()).filter(m -> m.getName().equals("create") && m.getParameterCount() == 5).findFirst().orElseThrow();
                return (DependencyResolutionServices) create.invoke(services, files, collections, metadata, finder, context);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported Gradle native resolver API", failure);
        }
    }

    /**
     * Installs Gradle's Java attribute matching rules in a detached resolver.
     *
     * @param resolver detached Gradle resolver
     */
    public static void configureJavaSchema(DependencyResolutionServices resolver) {
        try {
            Class<?> support = Class.forName("org.gradle.api.internal.artifacts.JavaEcosystemSupport");
            for (Method method : support.getMethods()) {
                if (method.getName().equals("configureSchema") && method.getParameterCount() == 2) {
                    method.invoke(null, resolver.getAttributesSchema(), resolver.getObjectFactory());
                    return;
                }
                if (method.getName().equals("configureServices") && method.getParameterCount() == 3) {
                    Object describers = DependencyResolutionServices.class.getMethod("getAttributeDescribers").invoke(resolver);
                    method.invoke(null, resolver.getAttributesSchema(), describers, resolver.getObjectFactory());
                    return;
                }
            }
            throw new IllegalStateException("Unsupported Gradle Java attribute schema API");
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot configure native Java attribute schema", failure);
        }
    }
}
