/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.resolution;

import com.sneedit.deplens.inventory.BuildInventory;
import com.sneedit.deplens.inventory.InventoryEntry;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.sneedit.deplens.inventory.InventoryEntry.*;

/**
 * Refresh changing selectors and previous failures; fixed declarations retain their captured graph.
 */
public final class CurrentInventory {
    /**
     * Keeps this utility class static-only.
     */
    private CurrentInventory() {
    }

    /**
     * Checks whether unresolved or dynamic current versions need fresh resolution.
     *
     * @param model          captured project analysis
     * @param report         inventory to read or update
     * @param specifications captured configuration specifications
     * @return true when current versions need fresh resolution
     */
    public static boolean needsRefresh(AnalysisModel model, BuildInventory report, Map<String, ConfigurationSpec> specifications) {
        return specifications.values().stream().filter(s -> s.resolvable).anyMatch(s -> s.dependencies.stream().anyMatch(d -> dynamic(d.version().required()) || dynamic(d.version().strict()) || dynamic(d.version().preferred())))
                || report.warnings.stream().anyMatch(w -> w.startsWith(model.build + " " + model.project + " ") && (w.contains("dependency graph is incomplete") || w.contains("resolution failed")))
                || report.entries.stream().anyMatch(e -> e.build.equals(model.build) && e.project.equals(model.project) && e.type != Type.PLUGIN && e.type != Type.GRADLE && e.statuses.contains(Status.FAILED));
    }

    /**
     * Refreshes recorded current versions using reconstructed configuration contexts.
     *
     * @param model          captured project analysis
     * @param report         inventory to read or update
     * @param context        diagnostic context label
     * @param specifications captured configuration specifications
     * @param prefix         configuration prefix distinguishing buildscript entries
     */
    public static void refresh(AnalysisModel model, BuildInventory report, ResolutionContext context,
                               Map<String, ConfigurationSpec> specifications, String prefix) {
        var entries = report.entries.stream().filter(e -> e.build.equals(model.build) && e.project.equals(model.project))
                .filter(e -> e.type != Type.PLUGIN && e.type != Type.GRADLE).toList();
        Set<InventoryEntry> retried = new HashSet<>(), failed = new HashSet<>();
        for (var spec : specifications.values()) {
            if (!spec.resolvable) continue;
            String name = prefix + spec.name;
            var members = entries.stream().filter(e -> e.resolutions.stream().anyMatch(r -> r.configuration().equals(name))
                    || spec.dependencies.stream().anyMatch(d -> e.coordinate.equals(d.group() + ":" + d.name()))).toList();
            boolean changing = spec.dependencies.stream().anyMatch(d -> dynamic(d.version().required()) || dynamic(d.version().strict()) || dynamic(d.version().preferred()));
            String warningPrefix = model.build + " " + model.project + " " + name + ":";
            if (!changing && members.stream().noneMatch(e -> e.statuses.contains(Status.FAILED)) && report.warnings.stream().noneMatch(w -> w.startsWith(warningPrefix)))
                continue;
            report.warnings.removeIf(w -> w.startsWith(warningPrefix));
            members.forEach(entry -> entry.resolutionFailures.removeIf(failure -> failure.configuration().equals(name)));
            try {
                var graph = Objects.requireNonNull(context.forConfiguration(spec.name).find(spec.name), "Missing captured configuration").getIncoming().getResolutionResult();
                if (graph.getAllDependencies().stream().anyMatch(UnresolvedDependencyResult.class::isInstance))
                    report.incomplete(warningPrefix + " the dependency graph is incomplete");
                for (var edge : graph.getRootComponent().get().getDependencies()) {
                    if (!(edge.getRequested() instanceof ModuleComponentSelector requested)) continue;
                    String coordinate = requested.getGroup() + ":" + requested.getModule();
                    for (var entry : members) {
                        if (!entry.coordinate.equals(coordinate)) continue;
                        retried.add(entry);
                        entry.resolutions.removeIf(r -> r.configuration().equals(name) && !r.reasons().contains(Resolution.IMPORTED_BOM));
                        entry.warnings.remove("Resolution failed in " + name + "; repository credentials and exception details are omitted.");
                        if (edge instanceof ResolvedDependencyResult resolved && resolved.getSelected().getModuleVersion() != null) {
                            var reasons = resolved.getSelected().getSelectionReason().getDescriptions().stream().map(r -> r.getCause().name()).distinct().toList();
                            entry.resolutions.add(new Resolution(name, resolved.getSelected().getModuleVersion().getVersion(), reasons));
                        } else {
                            failed.add(entry);
                            if (edge instanceof UnresolvedDependencyResult unresolved) {
                                var detail = com.sneedit.deplens.updates.ResolutionFailureDetails.describe(name, unresolved);
                                if (!entry.resolutionFailures.contains(detail)) entry.resolutionFailures.add(detail);
                            }
                            entry.warnings.add("Resolution failed in " + name + "; repository credentials and exception details are omitted.");
                        }
                    }
                }
            } catch (Exception failure) {
                if (Thread.currentThread().isInterrupted())
                    throw new IllegalStateException("Inventory refresh interrupted", failure);
                failed.addAll(members);
                for (var entry : members) {
                    var detail = com.sneedit.deplens.updates.ResolutionFailureDetails.describe(name, entry.coordinate, failure);
                    if (!entry.resolutionFailures.contains(detail)) entry.resolutionFailures.add(detail);
                }
                report.incomplete(warningPrefix + " resolution failed; details are omitted to protect credentials");
            }
        }
        for (var entry : retried) {
            if (failed.contains(entry)) entry.statuses.add(Status.FAILED);
            else {
                entry.statuses.remove(Status.FAILED);
                if (!entry.resolutions.isEmpty()) {
                    entry.warnings.remove("No resolved version is available in a resolvable configuration.");
                    report.warnings.remove(model.build + " " + model.project + " " + entry.coordinate + ": no resolved version");
                }
            }
        }
    }

    /**
     * Recognizes selectors whose chosen version can change without editing the declaration.
     */
    private static boolean dynamic(@Nullable String version) {
        return version != null && (version.contains("+") || version.contains("[") || version.contains("]")
                || version.contains("(") || version.contains(")") || version.startsWith("latest."));
    }
}
