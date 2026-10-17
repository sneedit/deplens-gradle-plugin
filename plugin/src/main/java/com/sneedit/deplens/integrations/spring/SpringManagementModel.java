/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.integrations.spring;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.*;

/**
 * Spring's public DSL exposes effective versions but not origins. Isolate the read-only
 * internal model bridge here and let Spring resolve imports, properties and nested BOMs.
 */
final class SpringManagementModel {
    /**
     * A managed module version and its BOM, explicit override or aligned plugin origin.
     */
    record Managed(@Nullable String coordinate, @Nullable String version, boolean override,
                   @Nullable String alignedPlugin) {
        /**
         * Returns the BOM coordinate, failing if this record represents only an explicit override.
         */
        String bomCoordinate() {
            return Objects.requireNonNull(coordinate, "Missing Spring BOM coordinate");
        }

        /**
         * Returns the managed version, failing if no BOM version was recorded.
         */
        String managedVersion() {
            return Objects.requireNonNull(version, "Missing Spring managed version");
        }
    }

    private final Object extension;
    private final Class<?> api;
    private final Object global;
    private final Map<?, ?> scoped;
    private final Object resolver;
    private final Object projectProperties;
    private final Object emptyProperties;
    private final Object withoutKotlinProperties;
    private final @Nullable SpringKotlinAlignment kotlin;
    private final Class<?> propertyApi;
    private final Map<Object, Map<String, Managed>> scopes = new IdentityHashMap<>();

    /**
     * Connects the read-only bridge to Spring's management containers and property sources.
     */
    SpringManagementModel(Project project) throws ReflectiveOperationException {
        extension = project.getExtensions().getByName("dependencyManagement");
        ClassLoader loader = extension.getClass().getClassLoader();
        api = loader.loadClass("io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension");
        Object container = field(extension, "dependencyManagementContainer");
        global = requiredCall(container, "getGlobalDependencyManagement");
        scoped = (Map<?, ?>) field(container, "configurationDependencyManagement");
        resolver = field(container, "pomResolver");
        propertyApi = loader.loadClass("io.spring.gradle.dependencymanagement.internal.properties.PropertySource");
        projectProperties = loader.loadClass("io.spring.gradle.dependencymanagement.internal.properties.ProjectPropertySource")
                .getConstructor(Project.class).newInstance(project);
        emptyProperties = loader.loadClass("io.spring.gradle.dependencymanagement.internal.properties.MapPropertySource")
                .getConstructor(Map.class).newInstance(Map.of());
        kotlin = SpringKotlinAlignment.inspect(project);
        withoutKotlinProperties = Proxy.newProxyInstance(loader, new Class<?>[]{propertyApi}, (proxy, method, arguments) -> {
            if (method.getName().equals("getProperty") && "kotlin.version".equals(arguments[0])) return null;
            return method.invoke(projectProperties, arguments);
        });
    }

    /**
     * Combines global and inherited management origins, keeping only effective modules.
     */
    @SuppressWarnings("unchecked")
    Map<String, Managed> forConfiguration(Configuration configuration) throws ReflectiveOperationException {
        // Also realizes any configuration-specific management before inspecting its origins.
        Map<String, String> effective = (Map<String, String>) api.getMethod("getManagedVersionsForConfigurationHierarchy", Configuration.class)
                .invoke(extension, configuration);
        Map<String, Managed> result = new HashMap<>(scope(global));
        List<Configuration> hierarchy = new ArrayList<>(configuration.getHierarchy());
        Collections.reverse(hierarchy);
        for (Configuration parent : hierarchy) {
            Object management = scoped.get(parent);
            if (management != null) result.putAll(scope(management));
        }
        result.keySet().retainAll(effective.keySet());
        return result;
    }

    /**
     * Collects imported BOM coordinates and versions across a configuration's hierarchy.
     */
    Map<String, String> imports(Configuration configuration) throws ReflectiveOperationException {
        Map<String, String> result = new LinkedHashMap<>();
        List<Object> management = new ArrayList<>();
        management.add(global);
        List<Configuration> hierarchy = new ArrayList<>(configuration.getHierarchy());
        Collections.reverse(hierarchy);
        for (Configuration parent : hierarchy) if (scoped.get(parent) != null) management.add(scoped.get(parent));
        for (Object scope : management)
            for (Object reference : (List<?>) field(scope, "importedBoms")) {
                Object coordinates = requiredCall(reference, "getCoordinates");
                result.put((String) requiredCall(coordinates, "getGroupAndArtifactId"), (String) requiredCall(coordinates, "getVersion"));
            }
        return result;
    }

    /**
     * Resolves a management scope and compares BOM defaults with property and explicit overrides.
     */
    private Map<String, Managed> scope(Object management) throws ReflectiveOperationException {
        Map<String, Managed> cached = scopes.get(management);
        if (cached != null) return cached;
        Map<String, Managed> result = new HashMap<>();
        for (Object reference : (List<?>) field(management, "importedBoms")) {
            Object coordinates = requiredCall(reference, "getCoordinates");
            String owner = (String) requiredCall(coordinates, "getGroupAndArtifactId");
            // Compare against the published BOM to retain property-based explicit overrides.
            Object plainReference = reference.getClass().getConstructor(coordinates.getClass()).newInstance(coordinates);
            Map<String, String> baseline = versions(resolve(plainReference, emptyProperties));
            Map<String, String> withoutKotlin = kotlin == null ? baseline : versions(resolve(reference, withoutKotlinProperties));
            for (Map.Entry<String, String> dependency : versions(resolve(reference, projectProperties)).entrySet()) {
                String coordinate = dependency.getKey();
                String version = dependency.getValue();
                boolean kotlinMember = kotlin != null && coordinate.startsWith("org.jetbrains.kotlin:")
                        && !Objects.equals(version, withoutKotlin.get(coordinate));
                boolean automatic = kotlin != null && kotlinMember && kotlin.automatic() && kotlin.pluginVersion().equals(version)
                        && Objects.equals(baseline.get(coordinate), withoutKotlin.get(coordinate));
                boolean explicitKotlin = kotlin != null && !kotlin.automatic() && coordinate.startsWith("org.jetbrains.kotlin:")
                        && kotlin.propertyVersion().equals(version);
                boolean override = !automatic && (explicitKotlin || !Objects.equals(baseline.get(coordinate), version));
                result.put(coordinate, new Managed(owner, version, override,
                        automatic ? "org.jetbrains.kotlin.jvm" : null));
            }
        }
        // Explicit management declarations win even if they happen to equal the BOM version.
        for (Object dependency : (List<?>) requiredCall(management, "getManagedDependencies")) {
            Object coordinates = requiredCall(dependency, "getCoordinates");
            result.put((String) requiredCall(coordinates, "getGroupAndArtifactId"), new Managed(null, null, true, null));
        }
        scopes.put(management, result);
        return result;
    }

    /**
     * Asks Spring to resolve a BOM reference with the supplied property source.
     */
    private List<?> resolve(Object reference, Object properties) throws ReflectiveOperationException {
        return (List<?>) resolver.getClass().getMethod("resolvePoms", List.class, propertyApi)
                .invoke(resolver, List.of(reference), properties);
    }

    /**
     * Extracts unclassified managed dependency versions from resolved BOM models.
     */
    private static Map<String, String> versions(List<?> poms) throws ReflectiveOperationException {
        Map<String, String> result = new HashMap<>();
        for (Object pom : poms)
            for (Object dependency : (List<?>) requiredCall(pom, "getManagedDependencies")) {
                Object coordinates = requiredCall(dependency, "getCoordinates");
                Object classifier = optionalCall(dependency, "getClassifier");
                if (classifier != null && !classifier.toString().isEmpty()) continue;
                String version = (String) optionalCall(coordinates, "getVersion");
                if (version != null && !version.isBlank())
                    result.put((String) requiredCall(coordinates, "getGroupAndArtifactId"), version);
            }
        return result;
    }

    /**
     * Invokes a Spring model getter whose result may be null.
     */
    private static @Nullable Object optionalCall(Object target, String method) throws ReflectiveOperationException {
        return target.getClass().getMethod(method).invoke(target);
    }

    /**
     * Invokes a Spring model getter and rejects missing values.
     */
    private static Object requiredCall(Object target, String method) throws ReflectiveOperationException {
        return Objects.requireNonNull(optionalCall(target, method), "Missing Spring model value: " + method);
    }

    /**
     * Reads a non-null Spring model field, searching superclasses when needed.
     */
    private static Object field(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return Objects.requireNonNull(field.get(target), "Missing Spring model field: " + name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }
}
