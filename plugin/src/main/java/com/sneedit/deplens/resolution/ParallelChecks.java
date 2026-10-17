/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */
package com.sneedit.deplens.resolution;

import com.sneedit.deplens.inventory.InventoryEntry;
import com.sneedit.deplens.tasks.PartialDepLensCheckTask;
import com.sneedit.deplens.updates.CheckQueryService;
import org.gradle.api.Project;
import org.gradle.api.file.RegularFile;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.provider.Provider;
import org.gradle.internal.build.BuildState;
import org.gradle.internal.build.BuildStateRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Registers project-owned producers under Gradle's project-state boundary, before task scheduling.
 */
public final class ParallelChecks {
    private ParallelChecks() {
    }

    public static List<Provider<RegularFile>> prepare(Project requester, String aggregate,
                                                      List<AnalysisModel> models, Set<InventoryEntry.Type> types,
                                                      boolean fast, Provider<CheckQueryService> queries) {
        var registry = ((ProjectInternal) requester).getServices().get(BuildStateRegistry.class);
        var requestingState = ((ProjectInternal) requester).getOwner();
        var requestingBuild = requestingState.getOwner();
        Map<String, BuildState> builds = new LinkedHashMap<>();
        registry.visitBuilds(build -> builds.put(build.getBuildRootDir().getAbsolutePath(), build));
        String name = "partialDepLensCheck_" + identifier(requester.getRootDir().getAbsolutePath()
                + "|" + requester.getPath() + "|" + aggregate);
        List<Provider<RegularFile>> results = new ArrayList<>();
        var captured = com.sneedit.deplens.inventory.CapturedInventory.merge(models);
        var encoder = new com.google.gson.GsonBuilder().serializeNulls().create();
        for (var model : models) {
            var build = Objects.requireNonNull(builds.get(model.build), "DepLens analysis build is unavailable");
            var modelState = build.getProjects().getAllProjects().stream()
                    .filter(project -> project.getProjectPath().toString().equals(model.project)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("DepLens analysis project is unavailable: " + model.project));
            // Foreign models use the same detached engine, hosted in the requesting build.
            // This avoids adding foreign project vertices to a consumer's task-graph analysis.
            boolean local = build == requestingBuild;
            var state = local ? modelState : requestingState;
            String producerName = local ? name : name + "_" + identifier(model.build + "|" + model.project);
            var producer = state.fromMutableState(project -> {
                if (project.getTasks().getNames().contains(producerName))
                    return project.getTasks().named(producerName, PartialDepLensCheckTask.class);
                var inventory = new com.sneedit.deplens.inventory.BuildInventory();
                captured.entries.stream().filter(entry -> entry.build.equals(model.build)
                                && (entry.project.equals(model.project) || (model.project.equals(":") && entry.project.equals("<settings>"))))
                        .forEach(inventory.entries::add);
                inventory.warnings.addAll(captured.warnings);
                inventory.builds.addAll(captured.builds);
                String snapshot = encoder.toJson(inventory);
                return project.getTasks().register(producerName, PartialDepLensCheckTask.class, task -> {
                    task.setGroup("deplens");
                    task.setDescription("Check updates for " + model.project + " in " + model.build);
                    task.getAnalysisModels().set(List.of(model));
                    task.getOwnModel().set(model);
                    ResolutionRules.configureTask(task, List.of(model));
                    // Retain this project's serialized input, not the complete merged inventory.
                    task.getCapturedInventory().set(snapshot);
                    task.getCheckTypes().set(types);
                    task.getFastCheck().set(fast);
                    task.getQueryService().set(queries);
                    task.usesService(queries);
                    task.getReportFile().set(project.getLayout().getBuildDirectory()
                            .file("reports/deplens/partials/" + producerName + ".json"));
                });
            });
            results.add(producer.flatMap(PartialDepLensCheckTask::getReportFile));
        }
        return results;
    }

    private static String identifier(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
