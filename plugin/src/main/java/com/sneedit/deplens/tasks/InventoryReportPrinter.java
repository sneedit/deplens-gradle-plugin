/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.tasks;

import com.sneedit.deplens.inventory.BuildInventory;
import com.sneedit.deplens.inventory.InventoryEntry;

import org.gradle.api.logging.Logger;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Prints inventory details and grouped update rows while leaving the JSON entries untouched.
 */
final class InventoryReportPrinter {
    private final Logger logger;
    private final boolean updates;
    private final Predicate<InventoryEntry> visible;
    private final String rootBuild;
    private final boolean compact;
    private boolean showProjectPaths;

    private record ErrorKey(String build, String reason) {
    }

    private final Map<ErrorKey, String> errorIds = new LinkedHashMap<>();

    /**
     * Connects the printer to the task's logger, visibility filter and root build path.
     */
    InventoryReportPrinter(Logger logger, boolean updates, Predicate<InventoryEntry> visible, String rootBuild, boolean compact) {
        this.logger = logger;
        this.updates = updates;
        this.visible = visible;
        this.rootBuild = rootBuild;
        this.compact = compact;
    }

    /**
     * Prints grouped updates, per-entry details and completion totals using the task's logging mode.
     */
    void print(BuildInventory report, Path file) {
        showProjectPaths = !compact && report.entries.stream().anyMatch(entry -> !entry.project.equals(":"));
        if (updates) {
            errorIds.clear();
            report.entries.stream().filter(visible).flatMap(entry -> entry.resolutionFailures.stream()
                            .map(failure -> new ErrorKey(entry.build, failure.reason())))
                    .distinct().sorted(Comparator.comparing(ErrorKey::build).thenComparing(ErrorKey::reason))
                    .forEach(key -> errorIds.put(key, "E" + (errorIds.size() + 1)));
        }
        var bomMembers = updates ? bomMembers(report) : Map.<InventoryEntry, List<InventoryEntry>>of();
        var managed = report.entries.stream().filter(visible).filter(InventoryEntry::isBomManaged).collect(Collectors.toSet());
        var rows = updates ? updateRows(report) : Map.<UpdateRow, List<InventoryEntry>>of();
        if (updates) {
            printUpdates(rows, bomMembers);
            printNoMatchingCandidates(report);
            printErrors(report);
            printVersionSelectionWarnings(report);
        }
        String currentBuild = null;
        for (InventoryEntry entry : report.entries) {
            if (updates && (entry.statuses.contains(InventoryEntry.Status.PINNED) || !visible.test(entry))) continue;
            if (!updates) {
                if (!entry.build.equals(currentBuild)) {
                    currentBuild = entry.build;
                    result("\nBuild: {}", currentBuild);
                }
                result("{} {} {} {}", entry.project, entry.type, entry.coordinate,
                        entry.resolutions.stream().map(InventoryEntry.Resolution::version).distinct().toList());
            }
            if (updates) detail("{} {} {} {}", entry.build, entry.project, entry.type, entry.coordinate);
            detail("  declared: {} | configurations: {}", entry.declaredSelectors, entry.configurations);
            if (entry.resolutions.stream().map(InventoryEntry.Resolution::version).distinct().count() > 1) {
                for (InventoryEntry.Resolution resolution : entry.resolutions) {
                    detail("  resolved: {} → {}", resolution.configuration(), resolution.version());
                }
            }
            for (InventoryEntry.Source source : entry.versionSources) {
                detail("  source: {} {}:{}{}", source.kind(), source.file(), source.line(), source.key() == null ? "" : " (" + source.key() + ")");
            }
            if (!entry.platforms.isEmpty()) detail("  platforms: {}", entry.platforms);
            if (!entry.versionAlignments.isEmpty()) detail("  version alignment: {}", entry.versionAlignments);
            if (!entry.catalogs.isEmpty()) detail("  catalogs: {}", entry.catalogs);
            if (!entry.pluginStates.isEmpty()) detail("  plugin: {}", entry.pluginStates);
            detail("  status: {}", entry.statuses);
            if (updates) {
                detail("  rule: {} | candidates: {}", entry.appliedRule, entry.candidates);
                if (entry.fastCheck) detail("  build-context check: skipped (fast check)");
                if (entry.versionNormalizationPattern != null)
                    detail("  comparison normalization: {} -> {}", entry.versionNormalizationPattern, entry.versionNormalizationReplacement);
                if (entry.recommendedVersion != null) detail("  recommended: {}", entry.recommendedVersion);
            }
            if (updates) for (InventoryEntry.UpdateContext context : entry.updateContexts) {
                detail("  {}: current {} | patch {} | minor {} | major {}", context.configuration(), context.currentVersion(), context.patch(), context.minor(), context.major());
                if (context.versionPattern() != null) detail("  version pattern: {}", context.versionPattern());
                if (context.preReleasePattern() != null)
                    detail("  prerelease pattern: {}", context.preReleasePattern());
                if (!context.unknownCompatibility().isEmpty()) detail("  compatibility unknown ({}): {}",
                        context.versionPattern() == null ? "date versions" : "version pattern mismatch", context.unknownCompatibility());
                detail("  latest available: {} | within selector: {} | allowed by build: {}", context.latestAvailable(), context.latestWithinSelector(), context.latestAllowedByBuild());
                if (!context.outsideSelector().isEmpty())
                    detail("  outside selector (explicit version/selector change): {}", context.outsideSelector());
            }
            for (InventoryEntry.ResolutionFailure failure : entry.resolutionFailures)
                detail("  {} blocked by {}: {}", failure.configuration(), failure.dependency(), failure.reason());
            for (String warning : entry.warnings) {
                if (updates) detail("  {}", warning);
                else logger.warn("  {}", warning);
            }
        }
        for (String warning : report.warnings) {
            if (updates) detail("DepLens: {}", warning);
            else logger.warn("DepLens: {}", warning);
        }
        if (updates) {
            long failed = report.entries.stream().filter(visible).filter(e -> e.statuses.contains(InventoryEntry.Status.FAILED)).count();
            long unchecked = report.entries.stream().filter(visible).filter(InventoryEntry::hasUpdateTarget).filter(e -> e.statuses.contains(InventoryEntry.Status.NOT_CHECKED) && !e.statuses.contains(InventoryEntry.Status.FAILED)).count();
            long recommended = rows.keySet().stream().filter(UpdateRow::recommended).count();
            long availableOnly = rows.size() - recommended;
            long noMatching = report.entries.stream().filter(visible).filter(InventoryEntry::hasUpdateTarget)
                    .filter(e -> e.statuses.contains(InventoryEntry.Status.NO_MATCHING_CANDIDATES)).count();
            long aligned = report.entries.stream().filter(visible).filter(InventoryEntry::isVersionAligned).filter(e -> !e.isBomManaged()).count();
            String completeness = report.complete ? "" : "inventory incomplete";
            if (!Boolean.TRUE.equals(report.checksComplete))
                completeness += (completeness.isEmpty() ? "" : ", ") + "checks incomplete";
            if (report.fastCheck)
                completeness += (completeness.isEmpty() ? "" : ", ") + "full build-context checks skipped (fast check)";
            result("\n\u001B[1mDepLens:\u001B[22m {} recommended updates, {} available only{}{}{}{}{}{}.", recommended, availableOnly,
                    noMatching == 0 ? "" : ", " + noMatching + " with no matching candidates",
                    managed.isEmpty() ? "" : ", " + managed.size() + " BOM-managed", aligned == 0 ? "" : ", " + aligned + " version-aligned",
                    failed == 0 ? "" : ", " + failed + " failed", unchecked == 0 ? "" : ", " + unchecked + " not checked",
                    completeness.isEmpty() ? "" : "; " + completeness);
        } else result("Inventory: {} entries, {} builds, resolution {}. Update status: NOT_CHECKED.",
                report.entries.size(), report.builds.size(), report.complete ? "complete" : "incomplete");
        if (report.hiddenEntries > 0)
            result("Output declared configuration filter: {} entries hidden ({} failed); checks and failure policies are unchanged.",
                    report.hiddenEntries, report.hiddenFailedEntries);
        if (updates) detail("Details: --info | JSON report: {}", file);
        else result("DepLens report: {}", file);
    }

    /**
     * Only presentation is grouped. Every consumer and resolution remains in the report.
     */
    private record UpdateRow(InventoryEntry.Type type, String coordinate, List<String> current,
                             String target, boolean recommended, String qualifier, String alternatives) {
    }

    /**
     * Groups identical update choices for display while retaining every consumer behind each row.
     */
    private Map<UpdateRow, List<InventoryEntry>> updateRows(BuildInventory report) {
        var rows = new LinkedHashMap<UpdateRow, List<InventoryEntry>>();
        report.entries.stream().filter(visible).filter(InventoryEntry::hasUpdateTarget)
                .filter(e -> !e.statuses.contains(InventoryEntry.Status.PINNED))
                .filter(e -> e.statuses.contains(InventoryEntry.Status.UPDATE_AVAILABLE))
                .sorted(Comparator.comparing((InventoryEntry e) -> e.type)
                        .thenComparing(e -> e.coordinate).thenComparing(InventoryEntry::id))
                .forEach(entry -> {
                    var current = entry.updateContexts.stream().map(InventoryEntry.UpdateContext::currentVersion)
                            .distinct().sorted(entry.versionComparator()).toList();
                    var choices = entry.updateOptions();
                    String target = entry.hasRecommendedUpdate() ? Objects.requireNonNull(entry.recommendedVersion)
                            : entry.candidates.stream().max(entry.versionComparator()).orElse("?");
                    String qualifier = entry.hasRecommendedUpdate() ? "" : " (" + UpdateAvailability.explanation(entry) + ")";
                    if (entry.statuses.contains(InventoryEntry.Status.FAILED)) qualifier += failureQualifier(entry);
                    if (entry.hasRecommendedUpdate()) {
                        String latest = entry.candidates.stream().max(entry.versionComparator()).orElse(target);
                        if (entry.versionComparator().compare(latest, target) > 0)
                            qualifier += " (available: " + latest + "; above recommendation)";
                    }
                    if (entry.fastCheck) qualifier += " (build context not checked)";
                    if (entry.updateContexts.stream().anyMatch(c -> c.unknownCompatibility().contains(target))) {
                        boolean mismatch = entry.updateContexts.stream().anyMatch(c -> c.unknownCompatibility().contains(target) && c.versionPattern() != null);
                        qualifier += mismatch ? " (compatibility unknown: version pattern mismatch)" : " (compatibility unknown: date version)";
                    }
                    String alternatives = choices.size() < 2 ? "" : " [" + String.join(", ", choices.subList(0, choices.size() - 1)) + "]";
                    var row = new UpdateRow(entry.type, entry.coordinate, current, target, entry.hasRecommendedUpdate(), qualifier, alternatives);
                    rows.computeIfAbsent(row, ignored -> new ArrayList<>()).add(entry);
                });
        return rows;
    }

    /**
     * Shows the actual unresolved modules rather than blaming the dependency being checked.
     */
    private String failureQualifier(InventoryEntry entry) {
        String blockers = entry.resolutionFailures.stream()
                .map(failure -> errorIds.get(new ErrorKey(entry.build, failure.reason())))
                .filter(Objects::nonNull)
                .distinct().sorted().collect(Collectors.joining("; "));
        return blockers.isEmpty() ? " (update query failed; see --info)" : " (build context blocked: " + blockers + ")";
    }

    /** Successful queries with no policy-accepted versions are distinct from failures and up-to-date results. */
    private void printNoMatchingCandidates(BuildInventory report) {
        var entries = report.entries.stream().filter(visible).filter(InventoryEntry::hasUpdateTarget)
                .filter(e -> !e.statuses.contains(InventoryEntry.Status.PINNED))
                .filter(e -> e.statuses.contains(InventoryEntry.Status.NO_MATCHING_CANDIDATES))
                .sorted(Comparator.comparing((InventoryEntry e) -> e.build).thenComparing(e -> e.coordinate)
                        .thenComparing(InventoryEntry::id)).toList();
        if (entries.isEmpty()) return;
        result("\n\u001B[1mNo matching candidates:\u001B[22m");
        result("  Version queries succeeded, but no versions matched the prerelease/ignore policy. Review the policy; this does not mean up to date.");
        for (var entry : entries) {
            String contexts = entry.updateContexts.stream().filter(InventoryEntry.UpdateContext::noMatchingCandidates)
                    .map(c -> c.configuration() + " (current " + c.currentVersion() + ")")
                    .collect(Collectors.joining(", "));
            result("  • {} | build {} | {}", entry.coordinate, buildLabel(entry.build), contexts);
            printProjectPaths(List.of(entry));
        }
    }

    private record SelectionWarningRow(String build, InventoryEntry.Type type, String coordinate,
                                       String current, String candidate, String scope) {
    }

    /**
     * Groups lower query results like update rows, retaining configuration details in --info and JSON.
     */
    private void printVersionSelectionWarnings(BuildInventory report) {
        Map<SelectionWarningRow, List<InventoryEntry>> rows = new LinkedHashMap<>();
        report.entries.stream().filter(visible).filter(entry -> entry.type != InventoryEntry.Type.GRADLE)
                .sorted(Comparator.comparing((InventoryEntry entry) -> !buildLabel(entry.build).equals("."))
                        .thenComparing(entry -> buildLabel(entry.build)).thenComparing(entry -> entry.coordinate)
                        .thenComparing(InventoryEntry::id))
                .forEach(entry -> {
                    for (var context : entry.updateContexts) {
                        addSelectionWarning(rows, entry, context.currentVersion(), context.latestAvailable(), "version policy");
                        if (!Objects.equals(context.latestAllowedByBuild(), context.latestAvailable()))
                            addSelectionWarning(rows, entry, context.currentVersion(), context.latestAllowedByBuild(), "build context");
                    }
                });
        if (rows.isEmpty()) return;
        result("\n\u001B[1mVersion selection warnings:\u001B[22m");
        result("  Current version ranks above the query result; selection rules or version ordering may explain this. No downgrade recommended.");
        String currentBuild = null;
        for (var group : rows.entrySet()) {
            var row = group.getKey();
            if (!row.build.equals(currentBuild)) {
                currentBuild = row.build;
                if (!buildLabel(row.build).equals(".")) result("\n\u001B[1mBuild: {}\u001B[22m", buildLabel(row.build));
            }
            result("  • {} \u001B[1m{} > {}\u001B[22m ({})", row.coordinate, row.current, row.candidate, row.scope);
            printProjectPaths(group.getValue());
        }
    }

    private static void addSelectionWarning(Map<SelectionWarningRow, List<InventoryEntry>> rows,
                                            InventoryEntry entry, String current, @Nullable String candidate,
                                            String scope) {
        if (candidate == null || entry.versionComparator().compare(candidate, current) >= 0) return;
        var key = new SelectionWarningRow(entry.build, entry.type, entry.coordinate, current, candidate, scope);
        var consumers = rows.computeIfAbsent(key, ignored -> new ArrayList<>());
        if (!consumers.contains(entry)) consumers.add(entry);
    }

    /**
     * Prints shared failures once, keeping long dependency lists out of every update row.
     */
    private void printErrors(BuildInventory report) {
        if (errorIds.isEmpty()) return;
        result("\n\u001B[1mErrors:\u001B[22m");
        errorIds.forEach((key, id) -> {
            result("  [{}] {}", id, key.reason());
            result("    Build: {}", buildLabel(key.build()));
            Set<String> dependencies = new TreeSet<>();
            Map<String, Set<String>> contexts = new TreeMap<>();
            report.entries.stream().filter(visible).filter(entry -> entry.build.equals(key.build())).forEach(entry -> {
                for (var failure : entry.resolutionFailures) {
                    if (!failure.reason().equals(key.reason())) continue;
                    dependencies.add(failure.dependency());
                    contexts.computeIfAbsent(entry.project, ignored -> new TreeSet<>()).add(failure.configuration());
                }
            });
            result("    Blocking dependencies ({}):", dependencies.size());
            dependencies.forEach(dependency -> result("      • {}", dependency));
            result("    Affected contexts:");
            contexts.forEach((project, configurations) -> result("      {}: {}", project, String.join(", ", configurations)));
        });
    }

    /**
     * Prints update rows by component type and build, including managed dependency counts.
     */
    private void printUpdates(Map<UpdateRow, List<InventoryEntry>> rows,
                              Map<InventoryEntry, List<InventoryEntry>> bomMembers) {
        // Group identical updates within each build and retain every owning project path.
        var displayRows = rows.entrySet().stream().flatMap(group -> group.getValue().stream()
                        .collect(Collectors.groupingBy(entry -> entry.build)).values().stream()
                        .map(consumers -> Map.entry(group.getKey(), consumers)))
                .sorted(Comparator.comparing((Map.Entry<UpdateRow, List<InventoryEntry>> group) -> group.getKey().type)
                        .thenComparing(group -> !buildLabel(group.getValue().getFirst().build).equals("."))
                        .thenComparing(group -> buildLabel(group.getValue().getFirst().build))
                        .thenComparing(group -> group.getKey().coordinate))
                .toList();
        InventoryEntry.Type currentType = null;
        String currentBuild = null;
        for (var group : displayRows) {
            UpdateRow row = group.getKey();
            var consumers = group.getValue();
            if (row.type != currentType) {
                currentType = row.type;
                currentBuild = null;
                result("\n\u001B[1m{}:\u001B[22m", switch (row.type) {
                    case DEPENDENCY -> "Dependencies";
                    case PLATFORM -> "Platforms / BOMs";
                    case CONSTRAINT -> "Version constraints";
                    case PLUGIN -> "Gradle plugins";
                    case GRADLE -> "Gradle wrapper";
                });
            }
            String builds = String.join(", ", consumers.stream().map(e -> buildLabel(e.build)).distinct().sorted().toList());
            if (!builds.equals(currentBuild)) {
                currentBuild = builds;
                if (!builds.equals(".")) result("\n\u001B[1mBuild: {}\u001B[22m", builds);
            }
            long managed = consumers.stream().flatMap(e -> bomMembers.getOrDefault(e, List.of()).stream())
                    .map(e -> e.coordinate).distinct().count();
            String notes = (row.qualifier + (managed == 0 ? "" : " (" + managed + " managed dependenc" + (managed == 1 ? "y" : "ies") + ")")).stripLeading();
            if (row.recommended)
                result("  • {} \u001B[1m{} → {}\u001B[22m{}{}", row.coordinate, String.join(", ", row.current),
                        row.target, row.alternatives, notes.isEmpty() ? "" : " " + notes);
            else
                result("  • {} current {} | available only: {}{}{}", row.coordinate, String.join(", ", row.current),
                        row.target, row.alternatives, notes.isEmpty() ? "" : " " + notes);
            printProjectPaths(consumers);
        }
    }

    /**
     * Prints all owning projects below the update, wrapping long lists without splitting paths.
     */
    private void printProjectPaths(List<InventoryEntry> consumers) {
        if (!showProjectPaths) return;
        String prefix = "      Projects: ";
        String continuation = " ".repeat(prefix.length());
        StringBuilder paths = new StringBuilder();
        for (String project : consumers.stream().map(entry -> entry.project).distinct().sorted().toList()) {
            if (!paths.isEmpty() && prefix.length() + paths.length() + 2 + project.length() > 120) {
                result("\u001B[90m{}{},\u001B[39m", prefix, paths);
                prefix = continuation;
                paths.setLength(0);
            }
            if (!paths.isEmpty()) paths.append(", ");
            paths.append(project);
        }
        if (!paths.isEmpty()) result("\u001B[90m{}{}\u001B[39m", prefix, paths);
    }

    /**
     * Ownership does not depend on whether an owner has a newer version or is pinned.
     */
    private Map<InventoryEntry, List<InventoryEntry>> bomMembers(BuildInventory report) {
        var members = new LinkedHashMap<InventoryEntry, List<InventoryEntry>>();
        for (InventoryEntry owner : report.entries) {
            if (owner.type != InventoryEntry.Type.PLATFORM) continue;
            for (InventoryEntry entry : report.entries) {
                if (entry.isBomManaged() && visible.test(entry) && entry.build.equals(owner.build) && entry.project.equals(owner.project)
                        && entry.bomOwners.values().stream().anyMatch(owners -> owners.contains(owner.coordinate)))
                    members.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(entry);
            }
        }
        return members;
    }

    /**
     * Makes a build path relative to the root build; the root itself is shown as a dot.
     */
    private String buildLabel(String build) {
        Path directory = Path.of(rootBuild).toAbsolutePath().normalize();
        String relative = directory.relativize(Path.of(build)).toString();
        return relative.isEmpty() ? "." : relative;
    }

    /**
     * Writes a main result line at Gradle's lifecycle level.
     */
    private void result(String message, @Nullable Object... arguments) {
        logger.lifecycle(message, arguments);
    }

    /**
     * Writes detail lines at info level for checks and lifecycle level for inventory-only runs.
     */
    private void detail(String message, @Nullable Object... arguments) {
        if (updates) logger.info(message, arguments);
        else logger.lifecycle(message, arguments);
    }

}
