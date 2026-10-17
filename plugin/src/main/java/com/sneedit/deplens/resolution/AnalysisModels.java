/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.resolution;

import com.google.gson.Gson;
import com.sneedit.deplens.DepLensExtension;
import org.gradle.api.Project;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.internal.build.BuildState;
import org.gradle.internal.build.BuildStateRegistry;
import org.gradle.tooling.provider.model.internal.IntermediateToolingModelProvider;

import java.util.*;

/**
 * Native intermediate model requests preserve Gradle's project isolation and configuration-cache tracking.
 */
public final class AnalysisModels {
    /**
     * Keeps this utility class static-only.
     */
    private AnalysisModels() {
    }

    /**
     * Collects project and separate-build analysis models with the requested policy defaults.
     *
     * @param requester project requesting analysis models
     * @param extension configured DepLens extension
     * @param cached    whether this request reuses cached configuration
     * @return captured analysis models for the requested projects and builds
     */
    public static List<AnalysisModel> collect(Project requester, DepLensExtension extension, boolean cached) {
        var internal = (ProjectInternal) requester;
        var services = internal.getServices();
        var provider = GradleModelProvider.forProject(internal);
        var registry = services.get(BuildStateRegistry.class);
        BuildState owner = internal.getOwner().getOwner();
        Set<BuildState> builds = new LinkedHashSet<>();
        builds.add(owner);
        var defaults = AnalysisDefaults.capture(extension);
        if (defaults.included) for (var build : registry.getIncludedBuilds()) {
            if (!com.sneedit.deplens.inventory.NestedInventorySupport.isToolingBuild(build.getBuildRootDir().toPath()))
                builds.add(build);
        }
        if (defaults.buildSrc) for (var build : new ArrayList<>(builds)) {
            var source = registry.getBuildSrcNestedBuild(build);
            if (source != null) builds.add(source);
        }
        List<AnalysisModel> result = new ArrayList<>();
        var request = new AnalysisModelPlugin.Request(new Gson().toJson(defaults));
        for (var build : builds) {
            if (!build.isProjectsLoaded()) build.ensureProjectsLoaded();
            var states = new ArrayList<>(build.getProjects().getAllProjects());
            states.sort(Comparator.comparing(state -> state.getProjectPath().toString()));
            if (cached) {
                result.addAll(requestModels(provider, internal, states, request));
            } else {
                if (build != owner) build.ensureProjectsConfigured();
                for (var state : states)
                    result.add(state.fromMutableState(p -> AnalysisModelPlugin.capture(p, defaults)));
            }
        }
        return result;
    }

    /**
     * Requests isolated project models through Gradle's intermediate tooling model provider.
     */
    @SuppressWarnings("unchecked")
    private static List<AnalysisModel> requestModels(IntermediateToolingModelProvider provider, ProjectInternal requester,
                                                     List<? extends org.gradle.api.internal.project.ProjectState> states, AnalysisModelPlugin.Request request) {
        try {
            var apply = Arrays.stream(IntermediateToolingModelProvider.class.getMethods()).filter(m -> m.getName().equals("applyPlugin")).findFirst().orElseThrow();
            boolean stateApi = apply.getParameterTypes()[0].isInstance(requester.getOwner());
            Object source = stateApi ? requester.getOwner() : requester;
            Object targets = stateApi ? states : states.stream().map(org.gradle.api.internal.project.ProjectState::getMutableModel).toList();
            apply.invoke(provider, source, targets, AnalysisModelPlugin.class);
            var models = Arrays.stream(IntermediateToolingModelProvider.class.getMethods()).filter(m -> m.getName().equals("getModels") && m.getParameterCount() == 5).findFirst().orElseThrow();
            return (List<AnalysisModel>) models.invoke(provider, source, targets, AnalysisModelPlugin.NAME, AnalysisModel.class, request);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw new IllegalStateException("Native project model request failed", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported Gradle project model API", failure);
        }
    }
}
