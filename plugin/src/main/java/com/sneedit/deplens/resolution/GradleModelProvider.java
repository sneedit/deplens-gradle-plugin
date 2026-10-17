/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.resolution;

import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.internal.buildtree.BuildModelParameters;
import org.gradle.internal.buildtree.IntermediateBuildActionRunner;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.tooling.provider.model.internal.IntermediateToolingModelProvider;
import org.gradle.tooling.provider.model.internal.ToolingModelParameterCarrier;
import org.gradle.tooling.provider.model.internal.ToolingModelProjectDependencyListener;
import org.gradle.util.GradleVersion;

/**
 * Selects a native model provider compatible with the runtime's build lifecycle locks.
 */
final class GradleModelProvider {
    private GradleModelProvider() {
    }

    static IntermediateToolingModelProvider forProject(ProjectInternal project) {
        var services = project.getServices();
        var original = services.get(IntermediateToolingModelProvider.class);
        if (GradleVersion.current().getBaseVersion().compareTo(GradleVersion.version("9.0")) >= 0) {
            return original;
        }

        // Gradle 8's task-graph discovery holds the build lifecycle lock. Its parallel
        // model workers call configureProjects() and wait for that same lock while
        // the caller waits for the workers. Keep these requests on the owning thread.
        // Use the native provider to retain dependency reporting, parameter isolation
        // and the configuration-cache-aware tooling model scopes.
        var actions = new CallingThreadModelActions(services.get(BuildOperationExecutor.class),
                services.get(BuildModelParameters.class));
        try {
            // Gradle 9 changes this constructor; only link the Gradle 8 signature here.
            Class<?> provider = Class.forName("org.gradle.tooling.provider.model.internal.DefaultIntermediateToolingModelProvider");
            var parameters = provider.getDeclaredField("parameterCarrierFactory");
            var dependencies = provider.getDeclaredField("projectDependencyListener");
            parameters.setAccessible(true);
            dependencies.setAccessible(true);
            return (IntermediateToolingModelProvider) provider.getConstructor(IntermediateBuildActionRunner.class,
                            ToolingModelParameterCarrier.Factory.class, ToolingModelProjectDependencyListener.class)
                    .newInstance(actions, parameters.get(original), dependencies.get(original));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot create the Gradle 8 native analysis model provider", failure);
        }
    }

    /**
     * Serializes only DepLens model requests; project check tasks retain their parallel scheduling.
     */
    private static final class CallingThreadModelActions extends IntermediateBuildActionRunner {
        private CallingThreadModelActions(BuildOperationExecutor operations, BuildModelParameters parameters) {
            super(operations, parameters, "DepLens analysis model");
        }

        @Override
        public boolean isParallel() {
            return false;
        }
    }
}
