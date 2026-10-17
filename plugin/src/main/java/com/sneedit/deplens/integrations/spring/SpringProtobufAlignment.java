/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.integrations.spring;

import com.sneedit.deplens.inventory.InventoryEntry;
import com.sneedit.deplens.inventory.InventoryEntry.*;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.function.Predicate;

/**
 * Attributes supported Spring Boot protobuf tool alignment without guessing version ownership.
 */
final class SpringProtobufAlignment {
    private SpringProtobufAlignment() {
    }

    private static final Map<String, String> PROTOBUF_ALIGNMENT = Map.of(
            "com.google.protobuf:protoc", "com.google.protobuf:protobuf-java",
            "io.grpc:protoc-gen-grpc-java", "io.grpc:grpc-util");

    /**
     * Links automatically aligned protobuf tools to their runtime version owner.
     */
    static void collect(Project project, Collection<InventoryEntry> entries, SpringManagementModel spring,
                        Predicate<String> selectedConfiguration)
            throws ReflectiveOperationException {
        if (!project.getPluginManager().hasPlugin("org.springframework.boot")
                || !project.getPluginManager().hasPlugin("com.google.protobuf")) return;
        // This Boot integration uses the literal version "null" as its unset-tool sentinel.
        // Verify that this alignment contract exists; older Boot releases must not be guessed.
        ClassLoader loader = project.getPlugins().getPlugin("org.springframework.boot").getClass().getClassLoader();
        try {
            loader.loadClass("org.springframework.boot.gradle.plugin.ProtobufPluginAction").getDeclaredField("versionAlignment");
        } catch (ClassNotFoundException | NoSuchFieldException unsupported) {
            return;
        }
        Configuration runtime = project.getConfigurations().findByName("runtimeClasspath");
        if (runtime == null || !runtime.isCanBeResolved() || !selectedConfiguration.test(runtime.getName())) {
            collectResolvedProtobufToolAlignment(project, entries);
            return;
        }
        Map<String, String> runtimeVersions = new HashMap<>();
        runtime.getIncoming().getResolutionResult().getAllComponents().forEach(component -> {
            if (component.getId() instanceof ModuleComponentIdentifier id)
                runtimeVersions.put(id.getGroup() + ":" + id.getModule(), id.getVersion());
        });
        Map<String, SpringManagementModel.Managed> runtimeManagement = spring.forConfiguration(runtime);
        for (InventoryEntry tool : entries) {
            String source = PROTOBUF_ALIGNMENT.get(tool.coordinate);
            if (source == null || !tool.project.equals(project.getPath()) || tool.type != Type.DEPENDENCY
                    || !tool.declaredSelectors.equals(Set.of("null")) || !tool.managementOverrides.isEmpty()
                    || tool.resolutions.isEmpty()) continue;
            String sourceVersion = runtimeVersions.get(source);
            // Preserve real versions (including an explicit version equal to the managed one).
            // Require evidence of Boot's rule and the resulting runtime version in every context.
            if (sourceVersion == null || tool.resolutions.stream().anyMatch(r ->
                    !r.configuration().startsWith("protobufToolsLocator_") || !sourceVersion.equals(r.version())
                            || !r.reasons().contains("SELECTED_BY_RULE"))) continue;
            normalizeProtobufSentinel(tool);
            SpringManagementModel.Managed owner = runtimeManagement.get(source);
            boolean sourceOverride = runtime.getAllDependencies().stream().anyMatch(d ->
                    source.equals(d.getGroup() + ":" + d.getName()) && explicitVersion(d.getVersion()))
                    || runtime.getAllDependencyConstraints().stream().anyMatch(c -> source.equals(c.getGroup() + ":" + c.getName())
                    && (explicitVersion(c.getVersion()) || explicitVersion(c.getVersionConstraint().getStrictVersion())
                    || explicitVersion(c.getVersionConstraint().getPreferredVersion())))
                    || runtime.getResolutionStrategy().getForcedModules().stream().anyMatch(f -> source.equals(f.getGroup() + ":" + f.getName()));
            tool.platforms.clear();
            for (Resolution r : tool.resolutions) {
                tool.versionAlignments.put(r.configuration(), source);
                // A runtime override owns the aligned tool as well; it is not a BOM default.
                tool.bomOwners.remove(r.configuration());
                if (owner != null && !owner.override() && !sourceOverride && sourceVersion.equals(owner.version())) {
                    tool.bomOwners.computeIfAbsent(r.configuration(), ignored -> new TreeSet<>()).add(owner.bomCoordinate());
                    tool.platforms.add(owner.bomCoordinate());
                }
            }
            tool.statuses.add(Status.CONSTRAINED);
            tool.versionSources.add(new Source("<spring-boot-alignment>", 0, SourceKind.GENERATED,
                    "runtimeClasspath:" + source, sourceVersion));
        }
    }

    /**
     * Boot's supported unset-tool contract plus an already resolved, rule-selected tool is enough
     * to identify automatic alignment. The excluded runtime graph must not be resolved just to
     * attribute BOM ownership; leave that ownership unknown and record only version alignment.
     */
    private static void collectResolvedProtobufToolAlignment(Project project, Collection<InventoryEntry> entries) {
        for (InventoryEntry tool : entries) {
            String source = PROTOBUF_ALIGNMENT.get(tool.coordinate);
            if (source == null || !tool.project.equals(project.getPath()) || tool.type != Type.DEPENDENCY
                    || !tool.declaredSelectors.equals(Set.of("null")) || !tool.managementOverrides.isEmpty()
                    || tool.resolutions.isEmpty() || tool.statuses.contains(Status.FAILED)) continue;
            if (tool.resolutions.stream().anyMatch(r -> !r.configuration().startsWith("protobufToolsLocator_")
                    || !explicitVersion(r.version()) || !r.reasons().contains("SELECTED_BY_RULE"))) continue;
            normalizeProtobufSentinel(tool);
            tool.platforms.clear();
            for (Resolution resolution : tool.resolutions) {
                tool.versionAlignments.put(resolution.configuration(), source);
                tool.bomOwners.remove(resolution.configuration());
                tool.versionSources.add(new Source("<spring-boot-alignment>", 0, SourceKind.GENERATED,
                        resolution.configuration() + ":" + source, resolution.version()));
            }
            tool.statuses.add(Status.CONSTRAINED);
        }
    }

    /**
     * Removes the supported Boot unset-version sentinel only after alignment has been verified.
     */
    private static void normalizeProtobufSentinel(InventoryEntry tool) {
        tool.declaredSelectors.remove("null");
        tool.versionSources.removeIf(v -> "null".equals(v.value()));
        Set<Declaration> declarations = new LinkedHashSet<>();
        for (Declaration d : tool.declarations)
            declarations.add(new Declaration(d.file(), d.line(), d.configuration(),
                    "null".equals(d.selector()) ? null : d.selector()));
        tool.declarations.clear();
        tool.declarations.addAll(declarations);
        Set<VersionConstraintInfo> constraints = new LinkedHashSet<>();
        for (VersionConstraintInfo c : tool.versionConstraints)
            constraints.add(new VersionConstraintInfo(c.configuration(),
                    "null".equals(c.required()) ? "" : c.required(), c.strictly(), c.preferred(), c.rejected()));
        tool.versionConstraints.clear();
        tool.versionConstraints.addAll(constraints);
    }

    /**
     * Checks for a real nonblank version, excluding the protobuf sentinel string null.
     */
    private static boolean explicitVersion(@Nullable String version) {
        return version != null && !version.isBlank() && !version.equals("null");
    }

}
