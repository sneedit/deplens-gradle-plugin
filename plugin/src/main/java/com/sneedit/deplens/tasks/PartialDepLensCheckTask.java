/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */
package com.sneedit.deplens.tasks;

import com.google.gson.GsonBuilder;
import com.sneedit.deplens.inventory.InventoryEntry;
import com.sneedit.deplens.logging.DownloadProgressScope;
import com.sneedit.deplens.resolution.AnalysisModel;
import com.sneedit.deplens.updates.CheckQueryService;
import com.sneedit.deplens.updates.UpdateEngine;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.IOException;
import java.nio.file.Files;

/**
 * Checks one project's captured contexts; only the aggregate prints or enforces update policy.
 */
@DisableCachingByDefault(because = "Update checks observe live repository state")
public abstract class PartialDepLensCheckTask extends DepLensInventoryTask {
    @Internal
    public abstract Property<AnalysisModel> getOwnModel();

    @Internal
    public abstract Property<String> getCapturedInventory();

    @Input
    public abstract SetProperty<InventoryEntry.Type> getCheckTypes();

    @Input
    public abstract Property<Boolean> getFastCheck();

    @Internal
    public abstract Property<CheckQueryService> getQueryService();

    @Override
    @TaskAction
    public void inventory() throws IOException {
        if (!getLogger().isInfoEnabled()) {
            try (var ignored = DownloadProgressScope.open(getOperationListeners(), getLogger())) {
                checkProject();
            }
        } else checkProject();
    }

    private void checkProject() throws IOException {
        var report = java.util.Objects.requireNonNull(new com.google.gson.Gson().fromJson(getCapturedInventory().get(),
                com.sneedit.deplens.inventory.BuildInventory.class), "Missing captured project inventory");
        var originalWarnings = java.util.List.copyOf(report.warnings);
        var model = getOwnModel().get();
        report.fastCheck = getFastCheck().get() || !model.checkBuildContext;
        getLogger().info("DepLens: checking project {}", model.project);
        new UpdateEngine(model, resolverFactory(), getCheckTypes().get(), getQueryService().get().queries(),
                !report.fastCheck).check(report);
        report.entries.removeIf(entry -> !entry.build.equals(model.build)
                || (!entry.project.equals(model.project) && !(model.project.equals(":") && entry.project.equals("<settings>"))));
        var file = getReportFile().get().getAsFile().toPath();
        Files.createDirectories(file.getParent());
        var partial = new com.sneedit.deplens.inventory.PartialCheckReport(model.build, model.project, report,
                originalWarnings.stream().filter(warning -> !report.warnings.contains(warning)).toList(),
                report.warnings.stream().filter(warning -> !originalWarnings.contains(warning)).toList());
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(partial));
    }
}
