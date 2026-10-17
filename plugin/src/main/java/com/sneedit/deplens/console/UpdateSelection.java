/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.console;

import com.sneedit.deplens.inventory.BuildInventory;
import com.sneedit.deplens.inventory.InventoryEntry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static com.sneedit.deplens.inventory.InventoryEntry.*;

/**
 * One decision per physical version value, across every build and consumer.
 */
public final class UpdateSelection {
    /**
     * One version-source decision shared by all consumers of the same physical literal.
     */
    public static final class Row {
        public final SourceEditor.Location location;
        public final Source source;
        public final List<InventoryEntry> consumers = new ArrayList<>();
        public final List<String> options = new ArrayList<>();
        public int selected;
        public int recommended;
        public final boolean dynamic;
        public final List<String> catalogMembers = new ArrayList<>();

        /**
         * Starts one shared-source decision and records catalog members affected by the same version key.
         */
        Row(SourceEditor.Location location, Source source) {
            this.location = location;
            this.source = source;
            String value = Objects.requireNonNull(source.value(), "Editable sources require a version");
            dynamic = value.contains("+") || value.contains("[") || value.contains("(")
                    || value.startsWith("latest.");
            options.add(value);
            if (source.kind() == SourceKind.CATALOG && source.key() != null && source.key().startsWith("versions.")) {
                try {
                    var catalog = org.tomlj.Toml.parse(location.snapshot());
                    String versionKey = source.key().substring("versions.".length());
                    for (String section : List.of("libraries", "plugins")) {
                        var aliases = catalog.getTable(section);
                        if (aliases == null) continue;
                        for (String alias : aliases.keySet()) {
                            Object item = aliases.get(List.of(alias));
                            if (item instanceof org.tomlj.TomlTable table && versionKey.equals(table.getString("version.ref"))) {
                                String coordinate = section.equals("plugins") ? table.getString("id") : table.getString("module");
                                if (coordinate == null && section.equals("libraries"))
                                    coordinate = table.getString("group") + ":" + table.getString("name");
                                catalogMembers.add(section + "." + alias + " = " + coordinate);
                            }
                        }
                    }
                } catch (RuntimeException ignored) { /* Inventory consumers still remain visible. */ }
            }
        }

        /**
         * Returns the currently selected version option.
         *
         * @return version at the selected option index
         */
        public String target() {
            return options.get(selected);
        }

        /**
         * Builds a display label from the shared source key or the first consumer.
         *
         * @return shared source key or consumer label
         */
        public String label() {
            if (source.kind() == SourceKind.CATALOG || consumers.size() > 1)
                return source.key() == null ? consumers.getFirst().coordinate : source.key();
            return consumers.getFirst().type + " " + consumers.getFirst().coordinate;
        }
    }

    public final List<Row> rows = new ArrayList<>();
    public final List<String> notices = new ArrayList<>();

    /**
     * Groups editable sources and intersects candidates across every consumer sharing a literal.
     *
     * @param report inventory to read or update
     */
    public UpdateSelection(BuildInventory report) {
        this(report, report.entries);
    }

    /**
     * Limits selectable sources to the report view while protecting every shared-source consumer.
     */
    public UpdateSelection(BuildInventory report, Collection<InventoryEntry> visibleEntries) {
        Set<InventoryEntry> visible = Set.copyOf(visibleEntries);
        Map<String, Row> groups = new LinkedHashMap<>();
        for (InventoryEntry entry : report.entries) {
            boolean found = false;
            for (Source source : entry.versionSources) {
                try {
                    SourceEditor.Location location = SourceEditor.locate(entry, source);
                    if (location == null) continue;
                    found = true;
                    Row row = groups.computeIfAbsent(location.identity(), ignored -> new Row(location, source));
                    if (!row.consumers.contains(entry)) row.consumers.add(entry);
                } catch (IOException | RuntimeException ignored) { /* Unsupported sources stay read-only. */ }
            }
            if (visible.contains(entry) && !found && !entry.candidates.isEmpty() && !entry.statuses.contains(Status.PINNED))
                notices.add(entry.type + " " + entry.coordinate + ": no unambiguous editable literal source (read-only).");
        }
        for (Row row : groups.values()) {
            if (row.consumers.stream().noneMatch(visible::contains)) continue;
            if (row.consumers.stream().anyMatch(e -> e.statuses.contains(Status.PINNED))) {
                notices.add(row.source.file() + ":" + row.source.line() + ": shared source protected by a pinned consumer.");
                continue;
            }
            if (row.consumers.stream().anyMatch(e -> e.statuses.contains(Status.FAILED) || e.statuses.contains(Status.NOT_CHECKED) || e.updateContexts.isEmpty())) {
                notices.add(row.label() + ": shared source has an incomplete check (read-only).");
                continue;
            }
            Set<String> candidates = new TreeSet<>(row.consumers.getFirst().versionComparator());
            row.consumers.stream().filter(visible::contains).forEach(e -> candidates.addAll(e.updateOptions()));
            candidates.removeIf(v -> row.consumers.stream().anyMatch(e ->
                    (!e.candidates.contains(v) && e.updateContexts.stream().anyMatch(c -> !v.equals(c.currentVersion()))) || e.updateContexts.stream().anyMatch(c ->
                            !c.candidates().contains(v) && !v.equals(c.currentVersion()))));
            candidates.remove(row.source.value());
            row.options.addAll(candidates);
            if (row.options.size() == 1) {
                if (row.consumers.stream().anyMatch(e -> !e.candidates.isEmpty()))
                    notices.add(row.label() + ": no common candidate allowed for all consumers.");
                continue;
            }
            if (!row.dynamic) for (int i = 1; i < row.options.size(); i++) {
                String v = row.options.get(i);
                if (row.consumers.stream().allMatch(e -> e.updateContexts.stream().allMatch(c ->
                        e.fastCheck || (c.latestAllowedByBuild() != null && e.versionComparator().compare(v, c.latestAllowedByBuild()) <= 0))))
                    row.recommended = i;
            }
            if (row.source.kind() == SourceKind.WRAPPER && java.util.regex.Pattern.compile("(?m)^\\s*distributionSha256Sum\\s*=").matcher(row.location.snapshot()).find()) {
                notices.add("Wrapper checksum will be updated for the selected distribution before any sources are changed.");
            }
            rows.add(row);
        }
        rows.sort(Comparator.comparing((Row r) -> r.location.file().toString()).thenComparingInt(r -> r.location.start()));
    }

    /**
     * Selects current, recommended or latest options; dynamic selectors stay current for bulk actions.
     *
     * @param key lookup key or keyboard command
     */
    public void bulk(char key) {
        for (Row row : rows)
            row.selected = key == 'c' || row.dynamic ? 0 : key == 'r' ? row.recommended : row.options.size() - 1;
    }

    /**
     * Writes chosen literals and records a selected version when all edits for an entry agree.
     *
     * @return paths of files changed by the selected updates
     * @throws IOException if a source, report or terminal operation fails
     */
    public List<Path> apply() throws IOException {
        return apply(new WrapperChecksumUpdater(false));
    }

    /** Prepares wrapper checksum edits before applying any selected source changes. */
    public List<Path> apply(WrapperChecksumUpdater checksums) throws IOException {
        List<SourceEditor.Change> changes = new ArrayList<>();
        for (Row row : rows) if (row.selected != 0) {
            changes.add(new SourceEditor.Change(row.location, row.target()));
            if (row.source.kind() == SourceKind.WRAPPER) changes.addAll(checksums.changes(row.location, row.target()));
        }
        List<Path> files = SourceEditor.apply(changes);
        Map<InventoryEntry, Set<String>> targets = new IdentityHashMap<>();
        for (Row row : rows)
            for (InventoryEntry e : row.consumers)
                targets.computeIfAbsent(e, ignored -> new HashSet<>()).add(row.target());
        targets.forEach((entry, versions) -> {
            if (versions.size() == 1 && rows.stream().anyMatch(r -> r.selected != 0 && r.consumers.contains(entry)))
                entry.selectedVersion = versions.iterator().next();
        });
        return files;
    }
}
