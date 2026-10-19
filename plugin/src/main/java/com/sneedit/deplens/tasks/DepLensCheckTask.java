/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.tasks;

import com.sneedit.deplens.inventory.BuildInventory;
import com.sneedit.deplens.inventory.InventoryEntry;
import com.sneedit.deplens.updates.CheckQueryService;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

import java.util.Set;

/**
 * Runs update checks and writes the report before enforcing configured CI failure policies.
 */
@DisableCachingByDefault(because = "Update checks observe live repository state")
public abstract class DepLensCheckTask extends DepLensInventoryTask {
    /**
     * Provides reports produced by independent per-project checks.
     *
     * @return partial JSON report files to merge
     */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getPartialReports();

    /**
     * Provides the query cache service shared by the participating checks.
     *
     * @return invocation-local query service property
     */
    @Internal
    public abstract Property<CheckQueryService> getQueryService();

    /**
     * Controls whether update checks skip full consumer-graph validation.
     *
     * @return fast-check mode property
     */
    @Input
    public abstract Property<Boolean> getFastCheck();

    /**
     * Opt-in failure policy for the full aggregated check report, before output filtering.
     *
     * @return whether an incomplete aggregated check should fail the task
     */
    @Input
    public abstract Property<Boolean> getFailIfChecksIncomplete();

    /**
     * Initializes full graph checking and leaves incomplete-check failure opt-in.
     */
    public DepLensCheckTask() {
        getFastCheck().convention(false);
        getFailIfChecksIncomplete().convention(false);
    }

    /**
     * Skips candidate graph checks for this invocation, including included builds.
     *
     * @param fast true to skip consumer-graph candidate checks
     */
    @Option(option = "fast", description = "Discover updates without full build-context checks")
    public void setFast(boolean fast) {
        getFastCheck().set(fast);
    }

    /**
     * Enables update-style reporting for this task.
     *
     * @return true to enable update reporting
     */
    @Override
    protected boolean updates() {
        return true;
    }

    /**
     * Selects the component types checked by this task.
     *
     * @return component types checked by this task
     */
    protected abstract Set<InventoryEntry.Type> types();

    /**
     * Exposes this task's selected component types.
     *
     * @return component types checked by this task
     */
    @Internal
    public Set<InventoryEntry.Type> getCheckTypes() {
        return types();
    }

    /**
     * Includes entries whose type is selected by this task.
     *
     * @param entry component being inspected or configured
     * @return true when this task checks the entry's component type
     */
    @Override
    protected boolean visible(InventoryEntry entry) {
        return types().contains(entry.type);
    }

    /**
     * Checks each captured project using its own repositories and resolution rules.
     *
     * @param report inventory to read or update
     */
    @Override
    protected void process(BuildInventory report) {
        getLogger().info("Checking for {} updates...", types().contains(InventoryEntry.Type.DEPENDENCY) && types().size() == 3 ? "dependency" : types().size() == 1 ? types().iterator().next().name().toLowerCase() : "all");
        report.fastCheck = getFastCheck().get() || getAnalysisModels().get().stream().anyMatch(model -> !model.checkBuildContext);
        PartialReportMerger.merge(report, getAnalysisModels().get(), getPartialReports().getFiles());
        getQueryService().get().queries().logStatistics(getLogger());
    }

    /**
     * Enforces incomplete-check and available-update policies after the report has been written.
     *
     * @param report inventory to read or update
     */
    @Override
    protected void finished(BuildInventory report) {
        if (getFailIfChecksIncomplete().get() && (!report.complete || !Boolean.TRUE.equals(report.checksComplete)))
            throw new GradleException("DepLens checks incomplete. Report written before failing as configured by failIfChecksIncomplete.");
        boolean fail = report.entries.stream().filter(this::visible).anyMatch(e -> e.statuses.contains(InventoryEntry.Status.UPDATE_AVAILABLE)
                && (getAnalysisModels().get().getFirst().failTypes.contains(e.type) || getAnalysisModels().get().stream().anyMatch(m ->
                m.build.equals(e.build) && (m.project.equals(e.project) || e.project.equals("<settings>") && m.project.equals(":")) && m.failTypes.contains(e.type))));
        if (fail)
            throw new GradleException("Updates available. Report written before failing as configured.");
    }
}
