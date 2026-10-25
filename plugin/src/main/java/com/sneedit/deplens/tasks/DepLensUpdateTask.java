/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.tasks;

import com.google.gson.GsonBuilder;
import com.sneedit.deplens.console.InteractiveConsole;
import com.sneedit.deplens.console.PosixTerminal;
import com.sneedit.deplens.console.UpdateSelection;
import com.sneedit.deplens.inventory.BuildInventory;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.work.DisableCachingByDefault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Interactive local source updates, with no post-apply build or rollback.
 */
@DisableCachingByDefault(because = "Interactive decisions and source mutations must always run")
public abstract class DepLensUpdateTask extends CheckUpdatesTask {
    /** Creates an interactive update task using the aggregated check report. */
    public DepLensUpdateTask() {
    }

    /**
     * Optionally overrides the automatically detected terminal device.
     *
     * @return optional POSIX terminal device override
     */
    @Internal
    public abstract Property<String> getTerminal();

    /**
     * Provides Gradle request metadata for interactive client detection.
     *
     * @return injected metadata for the current build request
     */
    @javax.inject.Inject
    protected abstract org.gradle.initialization.BuildRequestMetaData getBuildRequest();

    /**
     * Provides Gradle output events used while the interactive console owns the terminal.
     *
     * @return injected output event listener
     */
    @javax.inject.Inject
    protected abstract org.gradle.internal.logging.events.OutputEventListener getOutputEvents();

    /**
     * Checks all component types before asking the user to pick updates.
     *
     * @param report inventory to read or update
     */
    @Override
    protected void process(BuildInventory report) {
        if (!getTerminal().isPresent() && !isInteractiveTerminal()) throw new GradleException(
                "A terminal is required. Run ./gradlew depLensInteractiveUpdate from a POSIX terminal.");
        super.process(report);
    }

    private boolean isInteractiveTerminal() {
        try {
            java.lang.reflect.Method method;
            try {
                method = org.gradle.initialization.BuildRequestMetaData.class.getMethod("isInteractiveConsole");
            } catch (NoSuchMethodException olderGradle) {
                method = org.gradle.initialization.BuildRequestMetaData.class.getMethod("isInteractive");
            }
            return Boolean.TRUE.equals(method.invoke(getBuildRequest()));
        } catch (ReflectiveOperationException failure) {
            throw new GradleException("Cannot determine whether Gradle was started from a terminal", failure);
        }
    }

    /**
     * Keeps the full check listing available with --info without duplicating the selector.
     */
    @Override
    protected void printConsoleReport(BuildInventory report, Path file, boolean compact) {
        if (getLogger().isInfoEnabled()) super.printConsoleReport(report, file, compact);
        if (!report.complete || !Boolean.TRUE.equals(report.checksComplete))
            getLogger().warn("DepLens: checks incomplete. See {} for diagnostics.", displayPath(file));
    }

    /**
     * Opens the interactive selector and applies confirmed source edits after reporting.
     *
     * @param report inventory to read or update
     */
    @Override
    protected void finished(BuildInventory report) {
        UpdateSelection selection = new UpdateSelection(report, reportForOutput(report).entries);
        selection.notices.forEach(message -> getLogger().warn("DepLens: {}", message.replace(
                getAnalysisModels().get().getFirst().build + "/", "")));
        // Retain complete consumer details for troubleshooting without flooding normal output.
        for (var row : selection.rows)
            getLogger().info("Selection {} at {}:{} affects {}", row.label(),
                    row.source.file(), row.source.line(), row.consumers.stream().map(e -> e.type + " " + e.coordinate + " " + e.project).toList() + " Catalog aliases: " + row.catalogMembers);
        if (selection.rows.isEmpty()) {
            getLogger().lifecycle("No safely editable updates.");
            return;
        }
        getOutputEvents().onOutput(new org.gradle.internal.logging.events.UserInputRequestEvent());
        try (PosixTerminal terminal = new PosixTerminal(getTerminal().isPresent() ? getTerminal().get() : PosixTerminal.discover())) {
            boolean apply = new InteractiveConsole(selection, terminal.input(), terminal.output(), terminal.width, terminal.height).select();
            if (!apply) {
                getLogger().lifecycle("Update selection cancelled; no sources changed.");
                return;
            }
            var files = selection.apply(new com.sneedit.deplens.console.WrapperChecksumUpdater(
                    getAnalysisModels().get().getFirst().offline));
            for (var row : selection.rows)
                if (row.selected != 0)
                    report.selectedSources.add(new BuildInventory.SelectedSource(row.source, row.target(),
                            row.consumers.stream().map(com.sneedit.deplens.inventory.InventoryEntry::id).toList(), row.catalogMembers));
            Files.writeString(getReportFile().get().getAsFile().toPath(),
                    new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(reportForOutput(report)));
            printApplied(selection, files);
        } catch (IOException failure) {
            throw new GradleException(java.util.Objects.toString(failure.getMessage(), "Failed to apply update selections"), failure);
        } finally {
            getOutputEvents().onOutput(new org.gradle.internal.logging.events.UserInputResumeEvent(System.currentTimeMillis()));
        }
    }

    private void printApplied(UpdateSelection selection, java.util.List<Path> files) {
        long changed = selection.rows.stream().filter(row -> row.selected != 0).count();
        if (changed == 0) {
            getLogger().lifecycle("No updates selected; no sources changed.");
            return;
        }
        getLogger().lifecycle("\nDepLens: applied {} version update(s) in {} file(s).", changed, files.size());
        for (Path file : files) {
            getLogger().lifecycle("\n  {}", displayPath(file));
            for (var row : selection.rows)
                if (row.selected != 0 && row.location.file().equals(file))
                    getLogger().lifecycle("    {}: {} → {}", row.label(), row.options.getFirst(), row.target());
        }
        getLogger().lifecycle("\nNo build or tests run after applying.");
        getLogger().lifecycle("Report: {}", displayPath(getReportFile().get().getAsFile().toPath()));
    }

    private String displayPath(Path file) {
        Path root = Path.of(getAnalysisModels().get().getFirst().build).toAbsolutePath().normalize();
        return root.relativize(file.toAbsolutePath().normalize()).toString();
    }
}
