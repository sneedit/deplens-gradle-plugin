/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.tasks;

import com.google.gson.GsonBuilder;
import com.sneedit.deplens.inventory.BuildInventory;
import com.sneedit.deplens.inventory.InventoryCollector;
import com.sneedit.deplens.inventory.InventoryEntry;
import com.sneedit.deplens.logging.DownloadProgressScope;
import com.sneedit.deplens.resolution.AnalysisModel;
import com.sneedit.deplens.resolution.NativeResolverFactory;
import com.sneedit.deplens.updates.UpdateEngine;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.internal.artifacts.DependencyManagementServices;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.internal.operations.BuildOperationListenerManager;
import org.gradle.work.DisableCachingByDefault;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Shared read-only collection, aggregation and reporting; update tasks decorate the inventory.
 */
@DisableCachingByDefault(because = "Inventory observes live Gradle resolution and build configuration")
public abstract class DepLensInventoryTask extends DefaultTask {
    /**
     * Declaration-name report filters, detached from the resolution model.
     *
     * @return include patterns matching declaration configuration names
     */
    @org.gradle.api.tasks.Input
    public abstract ListProperty<String> getOutputDeclaredConfigurationIncludes();

    /**
     * Provides declaration-name patterns to hide from report presentation.
     *
     * @return exclusion patterns for declaration configuration names
     */
    @org.gradle.api.tasks.Input
    public abstract ListProperty<String> getOutputDeclaredConfigurationExcludes();

    /**
     * Presentation only; update discovery and JSON reports do not depend on this mode.
     *
     * @return console presentation mode property
     */
    @org.gradle.api.tasks.Input
    public abstract org.gradle.api.provider.Property<String> getConsoleMode();

    /**
     * Sets the console presentation mode from the command line.
     *
     * @param mode standard for full output or compact to hide project lines
     * @throws IllegalArgumentException if the mode is unsupported
     */
    @org.gradle.api.tasks.options.Option(option = "console-mode", description = "Console presentation: standard or compact (hide Projects lines)")
    public void setConsoleModeOption(String mode) {
        getConsoleMode().set(validateConsoleMode(mode));
    }

    /**
     * Validates a console presentation mode without altering analysis settings.
     *
     * @param mode requested presentation mode
     * @return the validated mode
     * @throws IllegalArgumentException if the mode is neither standard nor compact
     */
    public static String validateConsoleMode(String mode) {
        if (!mode.equals("standard") && !mode.equals("compact"))
            throw new IllegalArgumentException("consoleMode must be standard or compact; got: " + mode);
        return mode;
    }

    /**
     * Provides captured project models, with the primary inventory first.
     *
     * @return analysis model list property
     */
    @Internal
    public abstract ListProperty<AnalysisModel> getAnalysisModels();

    /**
     * Provides Gradle's injected dependency management service factory.
     *
     * @return injected dependency management services
     */
    @Inject
    protected abstract DependencyManagementServices getResolutionServices();

    /**
     * Provides Gradle's injected file resolver for detached resolution services.
     *
     * @return injected file resolver
     */
    @Inject
    protected abstract FileResolver getFileResolver();

    /**
     * Provides Gradle's injected file collection factory.
     *
     * @return injected file collection factory
     */
    @Inject
    protected abstract FileCollectionFactory getFileCollections();

    /**
     * Wraps injected Gradle services in the factory used by update queries.
     *
     * @return factory for detached resolution services
     */
    protected NativeResolverFactory resolverFactory() {
        return new NativeResolverFactory(getResolutionServices(), getFileResolver(), getFileCollections());
    }

    /**
     * Provides the build operation listeners used to scope IDE download events.
     *
     * @return injected operation listener manager
     */
    @Inject
    protected abstract BuildOperationListenerManager getOperationListeners();

    /**
     * Disables up-to-date skipping because an inventory observes live resolution state.
     */
    public DepLensInventoryTask() {
        getOutputs().upToDateWhen(task -> false);
        getConsoleMode().convention("compact");
        getOutputDeclaredConfigurationIncludes().convention(List.of());
        getOutputDeclaredConfigurationExcludes().convention(List.of());
    }

    /**
     * Configures the JSON report destination.
     *
     * @return JSON report file property
     */
    @OutputFile
    public abstract RegularFileProperty getReportFile();

    /**
     * Runs collection and reporting, scoping IDE download noise during normal update checks.
     *
     * @throws IOException if a source, report or terminal operation fails
     */
    @TaskAction
    public void inventory() throws IOException {
        if (updates() && !getLogger().isInfoEnabled()) {
            try (var ignored = DownloadProgressScope.open(getOperationListeners(), getLogger())) {
                collectAndReport();
            }
        } else collectAndReport();
    }

    /**
     * Merges and normalizes captured inventories before inventory or update processing.
     *
     * @return merged inventory for every captured project model
     */
    protected BuildInventory capturedInventory() {
        return com.sneedit.deplens.inventory.CapturedInventory.merge(getAnalysisModels().get());
    }

    private void collectAndReport() throws IOException {
        boolean compact = validateConsoleMode(getConsoleMode().get()).equals("compact");
        BuildInventory report = capturedInventory();
        var gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
        process(report);
        InventoryCollector.inheritPluginManagementVersions(report);
        report.entries.sort(Comparator.comparing(InventoryEntry::id));
        report.complete = report.warnings.isEmpty() && report.entries.stream().noneMatch(e -> e.statuses.contains(InventoryEntry.Status.FAILED));
        if (updates())
            report.checksComplete = report.complete && report.entries.stream().filter(this::visible).filter(InventoryEntry::hasUpdateTarget).noneMatch(e -> e.statuses.contains(InventoryEntry.Status.NOT_CHECKED) || e.statuses.contains(InventoryEntry.Status.FAILED));
        Path file = getReportFile().get().getAsFile().toPath();
        Files.createDirectories(file.getParent());
        BuildInventory output = reportForOutput(report);
        Files.writeString(file, gson.toJson(output));
        printConsoleReport(output, file, compact);
        finished(report);
    }

    /**
     * Prints the saved report; interactive tasks can provide their own completion summary.
     *
     * @param report filtered report to display
     * @param file saved JSON report path
     * @param compact whether compact console output is requested
     */
    protected void printConsoleReport(BuildInventory report, Path file, boolean compact) {
        new InventoryReportPrinter(getLogger(), updates(), this::visible,
                getAnalysisModels().get().getFirst().build, compact).print(report, file);
    }

    /**
     * Filters report and interactive visibility; failure policies and shared-source safety use the full report.
     *
     * @param report complete report before presentation filtering
     * @return report view with hidden entry counts and filtered visible entries
     */
    protected BuildInventory reportForOutput(BuildInventory report) {
        return com.sneedit.deplens.inventory.OutputDeclaredConfigurationFilter.apply(report,
                getOutputDeclaredConfigurationIncludes().get(), getOutputDeclaredConfigurationExcludes().get());
    }

    /**
     * Refreshes current versions before an inventory-only report is written.
     *
     * @param report inventory to read or update
     */
    protected void process(BuildInventory report) {
        for (var model : getAnalysisModels().get())
            new UpdateEngine(model, resolverFactory(), Set.of()).refresh(report);
    }

    /**
     * Tells the reporting pipeline whether this task performs update checks.
     *
     * @return false for an inventory-only task
     */
    protected boolean updates() {
        return false;
    }

    /**
     * Decides whether an entry belongs in this task's console output and check totals.
     *
     * @param entry component being inspected or configured
     * @return true when the entry participates in this task's output
     */
    protected boolean visible(InventoryEntry entry) {
        return true;
    }

    /**
     * Runs after reporting; subclasses can enforce failure policy or start interactive selection here.
     *
     * @param report inventory to read or update
     */
    protected void finished(BuildInventory report) {
    }
}
