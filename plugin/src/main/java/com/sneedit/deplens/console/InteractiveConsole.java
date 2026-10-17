/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.console;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Terminal-independent selection loop; EOF and Escape never apply changes.
 */
public final class InteractiveConsole {
    private final UpdateSelection model;
    private final InputStream input;
    private final PrintWriter output;
    private final int width, height;
    private int focus;

    /**
     * Connects the selection model to terminal streams and display dimensions.
     *
     * @param model  captured project analysis
     * @param input  terminal input stream
     * @param output terminal output writer
     * @param width  terminal width in columns
     * @param height terminal height in rows
     */
    public InteractiveConsole(UpdateSelection model, InputStream input, PrintWriter output, int width, int height) {
        this.model = model;
        this.input = input;
        this.output = output;
        this.width = Math.max(40, width);
        this.height = Math.max(12, height);
    }

    /**
     * Runs the key loop and returns true only when the user confirms applying updates.
     *
     * @return true when the user confirms applying the selection; false on cancel or EOF
     * @throws IOException if a source, report or terminal operation fails
     */
    public boolean select() throws IOException {
        if (model.rows.isEmpty()) {
            output.println("No safely editable updates. See the check report and source warnings.");
            output.flush();
            return false;
        }
        output.print("\033[?1049h\033[?25l");
        try {
            while (true) {
                render();
                int key;
                do {
                    key = input.read();
                } while (key == 0);
                if (key < 0 || key == 3 || key == 4) return false;
                if (key == '\r' || key == '\n') return true;
                if (key == 'c' || key == 'r' || key == 'l') model.bulk((char) key);
                if (key == 27) {
                    // The raw terminal uses a bounded read: a bare Escape times out, arrow sequences do not.
                    int next = input.read();
                    if (next != '[' && next != 'O') return false;
                    int direction = input.read();
                    var row = model.rows.get(focus);
                    switch (direction) {
                        case 'A' -> focus = Math.max(0, focus - 1);
                        case 'B' -> focus = Math.min(model.rows.size() - 1, focus + 1);
                        case 'C' -> row.selected = Math.min(row.options.size() - 1, row.selected + 1);
                        case 'D' -> row.selected = Math.max(0, row.selected - 1);
                        default -> {
                        }
                    }
                }
            }
        } finally {
            output.print("\033[?25h\033[?1049l");
            output.flush();
        }
    }

    /**
     * Redraws the selection table, shared-source details and keyboard hints.
     */
    private void render() {
        output.print("\033[H\033[2J");
        line("DepLens · Choose updates to apply  " + (focus + 1) + " / " + model.rows.size(), "\033[1m");
        line("");
        int columns = Math.min(4, Math.max(2, (width - 18) / 12));
        int versionWidth = Math.max(9, model.rows.stream().flatMap(row -> row.options.stream())
                .mapToInt(String::length).max().orElse(7) + 2);
        versionWidth = Math.min(Math.min(24, versionWidth), (width - 18) / columns);
        int nameWidth = Math.min(48, model.rows.stream().mapToInt(row -> label(row).length()).max().orElse(16));
        nameWidth = Math.min(Math.max(16, nameWidth), width - columns * (versionWidth + 2) - 4);
        line("  " + cell("Dependency", nameWidth) + "  " + cell("Current", versionWidth)
                + "  Available versions", "\033[2m");
        line("─".repeat(Math.min(width - 1, nameWidth + columns * (versionWidth + 2) + 4)), "\033[2m");
        var row = model.rows.get(focus);
        var details = details(row);
        int detailLimit = Math.max(2, height - 10);
        if (details.size() > detailLimit) details = details.subList(0, detailLimit);
        int window = Math.max(1, height - details.size() - 8);
        int first = Math.max(0, Math.min(focus - window / 2, model.rows.size() - window));
        for (int i = first; i < Math.min(model.rows.size(), first + window); i++) {
            var item = model.rows.get(i);
            StringBuilder text = new StringBuilder(i == focus ? "› " : "  ");
            text.append(cell(label(item), nameWidth)).append("  ");
            text.append(cell((item.selected == 0 ? "● " : "○ ") + item.options.getFirst(), versionWidth));
            int from = Math.max(1, Math.min(Math.max(1, item.selected - (columns - 2) / 2), item.options.size() - columns + 1));
            for (int column = 1; column < columns; column++) {
                int option = from + column - 1;
                String value = option < item.options.size()
                        ? (item.selected == option ? "● " : "○ ") + item.options.get(option) : "";
                text.append("  ").append(cell(value, versionWidth));
            }
            line(text.toString(), i == focus ? "\033[1;36m" : "");
        }
        line("");
        for (String detail : details) line(detail);
        line("");
        line("↑↓ Navigate  ←→ Version  c Current  r Range  l Latest", "\033[2m");
        line("Enter Apply  Esc Cancel", "\033[2m");
        output.flush();
    }

    private static String label(UpdateSelection.Row row) {
        if (row.source.key() != null && (row.consumers.size() > 1
                || row.source.kind() == com.sneedit.deplens.inventory.InventoryEntry.SourceKind.CATALOG))
            return row.source.key().replaceFirst("^versions\\.", "");
        String coordinate = row.consumers.getFirst().coordinate;
        return coordinate.substring(coordinate.lastIndexOf(':') + 1);
    }

    private static List<String> details(UpdateSelection.Row row) {
        var details = new ArrayList<String>();
        if (row.dynamic) details.add(row.selected == 0
                ? "Dynamic selector: bulk shortcuts keep Current."
                : "FIXED / selector change: replaces the dynamic selector.");
        else if (row.consumers.stream().flatMap(entry -> entry.updateContexts.stream())
                .anyMatch(context -> context.outsideSelector().contains(row.target())))
            details.add("Outside current selector · explicit selection");
        var warnings = row.consumers.stream().flatMap(entry -> entry.warnings.stream()).distinct().toList();
        if (!warnings.isEmpty()) details.add("Warning: " + String.join(" · ", warnings));
        Path build = Path.of(row.consumers.getFirst().build).toAbsolutePath().normalize();
        try {
            build = build.toRealPath();
        } catch (IOException ignored) {
            // Keep the recorded build path if it is no longer accessible.
        }
        details.add("Source: " + build.relativize(row.location.file()) + ":" + row.source.line());
        details.add("Selected: " + row.target() + "   Recommended: " + (row.recommended == 0 ? "none" : row.options.get(row.recommended))
                + "   Available: " + row.options.getLast());
        details.add(String.join(" · ", row.consumers.stream().map(entry -> entry.coordinate).distinct().toList()));
        if (!row.catalogMembers.isEmpty()) details.add("Shared aliases: " + String.join(", ", row.catalogMembers));
        var platforms = row.consumers.stream().flatMap(entry -> entry.platforms.stream()).distinct().toList();
        if (!platforms.isEmpty()) details.add("Platforms: " + String.join(", ", platforms));
        return details;
    }

    private static String cell(String text, int width) {
        String safe = text.replaceAll("[\\p{Cntrl}\\u007f-\\u009f]", " ");
        if (safe.length() > width) return safe.substring(0, width - 1) + "…";
        return safe + " ".repeat(width - safe.length());
    }

    private void line(String text, String style) {
        output.print(style);
        line(text);
        if (!style.isEmpty()) output.print("\033[0m");
    }

    /**
     * Writes one display line clipped to the terminal width.
     */
    private void line(String text) {
        // Strip terminal control characters from repository-provided strings and avoid wrapped redraws.
        String safe = text.replaceAll("[\\p{Cntrl}\\u007f-\\u009f]", " ");
        output.print(safe.length() > width - 1 ? safe.substring(0, width - 2) + "…" : safe);
        output.print("\r\n");
    }
}
