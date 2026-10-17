/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens;

import com.sneedit.deplens.resolution.AnalysisModel;
import com.sneedit.deplens.resolution.AnalysisModels;
import com.sneedit.deplens.resolution.ParallelChecks;
import com.sneedit.deplens.resolution.ResolutionRules;
import com.sneedit.deplens.tasks.DepLensCheckTask;
import com.sneedit.deplens.tasks.DepLensInventoryTask;
import com.sneedit.deplens.tasks.PartialDepLensCheckTask;
import com.sneedit.deplens.updates.CheckQueryService;
import com.sneedit.deplens.updates.UpdateRules;

import org.gradle.api.Project;
import org.gradle.api.configuration.BuildFeatures;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wires lazy analysis inputs, shared services and project report producers during configuration.
 */
final class DepLensTaskConfigurer {
    private DepLensTaskConfigurer() {
    }

    static void configure(Project project, DepLensExtension extension, BuildFeatures features) {
        boolean configurationCacheActive = features.getConfigurationCache().getActive().get();
        if (!configurationCacheActive) {
            configureLiveRulesBeforeExecution(project);
        }
        project.afterEvaluate(ignored -> validateExtension(extension));

        Provider<List<AnalysisModel>> models = project.getProviders().provider(
                () -> AnalysisModels.collect(project, extension, configurationCacheActive));
        Provider<CheckQueryService> queries = project.getGradle().getSharedServices().registerIfAbsent(
                "depLensCheckQueries", CheckQueryService.class, service -> {
                });
        project.getTasks().withType(DepLensInventoryTask.class).configureEach(task -> {
            if (task instanceof PartialDepLensCheckTask) {
                return;
            }
            task.getAnalysisModels().convention(models);
            task.getOutputDeclaredConfigurationIncludes().convention(extension.getOutputDeclaredConfigurationFilter().getIncludes());
            task.getOutputDeclaredConfigurationExcludes().convention(extension.getOutputDeclaredConfigurationFilter().getExcludes());
            task.getConsoleMode().convention(extension.getConsoleMode().map(DepLensInventoryTask::validateConsoleMode));
            if (task instanceof DepLensCheckTask checkTask) {
                checkTask.getFailIfChecksIncomplete().convention(extension.getFailIfChecksIncomplete());
                configureCheckReports(project, checkTask, queries);
            }
            if (configurationCacheActive && !(task instanceof DepLensCheckTask)) {
                configureInventoryRulesBeforeCacheStorage(project, task);
            }
        });
    }

    /**
     * Opaque rules must retain their original context when configuration caching is inactive.
     */
    private static void configureLiveRulesBeforeExecution(Project project) {
        project.getGradle().getTaskGraph().whenReady(graph -> {
            for (DepLensInventoryTask task : new ArrayList<>(project.getTasks().withType(DepLensInventoryTask.class))) {
                if (graph.hasTask(task.getPath())) {
                    task.getAnalysisModels().finalizeValue();
                    ResolutionRules.configureTask(task, task.getAnalysisModels().get());
                }
            }
        });
    }

    /**
     * Validate after the build script has configured all extension sections.
     */
    private static void validateExtension(DepLensExtension extension) {
        for (ConfigurationFilter filter : List.of(extension.getCheckResolvableConfigurationFilter(),
                extension.getOutputDeclaredConfigurationFilter())) {
            String name = filter == extension.getCheckResolvableConfigurationFilter()
                    ? "checkResolvableConfigurationFilter" : "outputDeclaredConfigurationFilter";
            ConfigurationFilter.predicate(name, filter.getIncludes().get(), filter.getExcludes().get());
        }
        UpdateRules.validateAllowedUpdates(extension.getDependencies().getAllowedUpdates().get());
        UpdateRules.validateAllowedUpdates(extension.getPlugins().getAllowedUpdates().get());
        UpdateRules.validateAllowedUpdates(extension.getGradle().getAllowedUpdates().get());
    }

    /**
     * The file providers carry producer task dependencies; preparation happens only once.
     */
    private static void configureCheckReports(Project project, DepLensCheckTask task,
                                              Provider<CheckQueryService> queries) {
        task.getQueryService().set(queries);
        task.usesService(queries);
        AtomicReference<List<Provider<RegularFile>>> prepared = new AtomicReference<>();
        task.getPartialReports().from(project.getProviders().provider(() -> {
            synchronized (prepared) {
                List<Provider<RegularFile>> outputs = prepared.get();
                if (outputs == null) {
                    // Dependency discovery can read this provider repeatedly before whenReady runs.
                    task.getAnalysisModels().finalizeValue();
                    ResolutionRules.configureTask(task, task.getAnalysisModels().get());
                    outputs = ParallelChecks.prepare(project, task.getName(), task.getAnalysisModels().get(),
                            task.getCheckTypes(), task.getFastCheck().get(), queries);
                    prepared.set(outputs);
                }
                return outputs;
            }
        }));
    }

    /**
     * Select any live-rule fallback before Gradle attempts to store the configuration.
     */
    private static void configureInventoryRulesBeforeCacheStorage(Project project, DepLensInventoryTask task) {
        task.dependsOn(project.getProviders().provider(() -> {
            task.getAnalysisModels().finalizeValue();
            ResolutionRules.configureTask(task, task.getAnalysisModels().get());
            return List.of();
        }));
    }
}
