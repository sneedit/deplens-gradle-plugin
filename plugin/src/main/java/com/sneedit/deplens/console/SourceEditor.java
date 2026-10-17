/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.console;

import com.sneedit.deplens.inventory.InventoryEntry;
import com.sneedit.deplens.inventory.InventoryEntry.Source;
import org.jspecify.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlPosition;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Locates literal values conservatively and preserves all surrounding source text.
 */
public final class SourceEditor {
    /**
     * An editable literal span plus the source snapshot used to detect stale edits.
     *
     * @param file     canonical source file path
     * @param start    inclusive character offset of the version token
     * @param end      exclusive character offset of the version token
     * @param original original version token
     * @param snapshot complete source text captured when the token was located
     */
    public record Location(Path file, int start, int end, String original, String snapshot) {
        /**
         * Returns the physical file-and-offset key used to group shared version sources.
         *
         * @return stable file-and-offset identity for the literal
         */
        public String identity() {
            return file + ":" + start + ":" + end;
        }
    }

    /**
     * A chosen replacement version for one literal source span.
     *
     * @param location editable source span and original snapshot
     * @param target   chosen replacement version
     */
    public record Change(Location location, String target) {
    }

    /**
     * Keeps this utility class static-only.
     */
    private SourceEditor() {
    }

    /**
     * Finds one unambiguous editable literal, returning null for unsupported or ambiguous sources.
     *
     * @param entry  component being inspected or configured
     * @param source original configuration or recorded version source
     * @return editable source span, or null when it cannot be identified safely
     * @throws IOException if a source, report or terminal operation fails
     */
    public static @Nullable Location locate(InventoryEntry entry, Source source) throws IOException {
        if (source.value() == null || source.line() < 1 || source.file().startsWith("<")) return null;
        if ((source.kind() == InventoryEntry.SourceKind.PROPERTY || source.kind() == InventoryEntry.SourceKind.VARIABLE)
                && source.key() == null) return null;
        Path path = Path.of(source.file()).toRealPath();
        String text = Files.readString(path);
        int start = lineStart(text, source.line()), end = text.indexOf('\n', start);
        if (end < 0) end = text.length();
        String masked = switch (source.kind()) {
            case BUILD_SCRIPT, SETTINGS, VARIABLE -> com.sneedit.deplens.inventory.SourceIndex.withoutComments(text);
            default -> text;
        };
        String line = masked.substring(start, end);
        String value = Pattern.quote(source.value());
        String pattern;
        int group = 1;
        switch (source.kind()) {
            case PROPERTY -> pattern = "^\\s*" + Pattern.quote(source.key()) + "\\s*[=:]\\s*(" + value + ")(?=\\s*$)";
            case VARIABLE ->
                    pattern = "(?<![\\w.])(?:ext\\.)?" + Pattern.quote(source.key()) + "\\s*=\\s*['\"](" + value + ")['\"]";
            case WRAPPER -> pattern = "^\\s*distributionUrl\\s*=.*?gradle-(" + value + ")-(?:bin|all)\\.zip";
            case CATALOG -> {
                return catalog(path, text, entry, source);
            }
            case BUILD_SCRIPT, SETTINGS -> {
                if (entry.type == InventoryEntry.Type.PLUGIN) {
                    pattern = "\\bid\\s*\\(?\\s*['\"]" + Pattern.quote(entry.coordinate)
                            + "['\"]\\s*\\)?\\s*\\.?version\\s*\\(?\\s*['\"](" + value + ")['\"]";
                } else {
                    pattern = "['\"]" + Pattern.quote(entry.coordinate) + ":(" + value + ")['\"]";
                    // Gradle's map notation is editable only when group/name/version are literal and unambiguous.
                    if (!Pattern.compile(pattern).matcher(line).find()) {
                        String[] coordinate = entry.coordinate.split(":", 2);
                        if (coordinate.length != 2) return null;
                        pattern = "\\bgroup\\s*[:=]\\s*['\"]" + Pattern.quote(coordinate[0])
                                + "['\"]\\s*,\\s*name\\s*[:=]\\s*['\"]" + Pattern.quote(coordinate[1])
                                + "['\"]\\s*,\\s*version\\s*[:=]\\s*['\"](" + value + ")['\"]";
                    }
                }
            }
            default -> {
                return null;
            }
        }
        Matcher matcher = Pattern.compile(pattern).matcher(line);
        if (!matcher.find()) return null;
        int a = start + matcher.start(group), b = start + matcher.end(group);
        if (matcher.find()) return null;
        return new Location(path, a, b, source.value(), text);
    }

    /**
     * Locates a TOML version token using parsed keys while preserving surrounding formatting.
     */
    private static @Nullable Location catalog(Path path, String text, InventoryEntry entry, Source source) {
        TomlParseResult toml = Toml.parse(text);
        if (toml.hasErrors() || source.key() == null || source.value() == null) return null;
        int dot = source.key().indexOf('.');
        if (dot < 0) return null;
        List<String> key = new ArrayList<>(List.of(source.key().substring(0, dot), source.key().substring(dot + 1)));
        Object node = toml.get(key);
        String prefix = "";
        if (node instanceof TomlTable table && !key.getFirst().equals("versions")) {
            key.add("version");
            node = table.get("version");
        } else if (node instanceof String s && key.getFirst().equals("libraries")) {
            prefix = entry.coordinate + ":";
            if (!s.equals(prefix + source.value())) return null;
        }
        if (node instanceof TomlTable table) {
            String field = null;
            for (String name : List.of("strictly", "require", "prefer"))
                if (table.getString(name) != null) {
                    field = name;
                    break;
                }
            if (field == null) return null;
            key.add(field);
            node = table.getString(field);
        }
        if (!(node instanceof String s) || !s.equals(prefix + source.value())) return null;
        TomlPosition position = toml.inputPositionOf(key);
        if (position == null) return null;
        int offset = lineStart(text, position.line()) + position.column() - 1;
        // Tomlj positions identify the key. Skip that assignment, retaining exact value offsets.
        Matcher assignment = Pattern.compile("(?:\"[^\"]*\"|'[^']*'|[\\w.-]+)\\s*=\\s*").matcher(text);
        assignment.region(offset, text.length());
        if (!assignment.lookingAt()) return null;
        offset = assignment.end();
        Matcher token = Pattern.compile("['\"](" + Pattern.quote(prefix) + ")(" + Pattern.quote(source.value()) + ")['\"]").matcher(text);
        token.region(offset, text.length());
        if (!token.lookingAt()) return null;
        return new Location(path, token.start(2), token.end(2), source.value(), text);
    }

    /**
     * Returns a one-based line's starting offset, or the text end if the line is absent.
     */
    private static int lineStart(String text, int line) {
        int offset = 0;
        for (int i = 1; i < line; i++) {
            int newline = text.indexOf('\n', offset);
            if (newline < 0) return text.length();
            offset = newline + 1;
        }
        return offset;
    }

    /**
     * Preflight every file before the first write. No build, validation or rollback follows writes.
     *
     * @param changes chosen literal replacements to validate and write
     * @return paths successfully written by the selected edits
     * @throws IOException if a source, report or terminal operation fails
     */
    public static List<Path> apply(List<Change> changes) throws IOException {
        Map<Path, List<Change>> files = new LinkedHashMap<>();
        for (Change change : changes) {
            if (!change.target().matches("[A-Za-z0-9._+\\-]+")) throw new IOException("Unsupported target version");
            files.computeIfAbsent(change.location().file(), ignored -> new ArrayList<>()).add(change);
        }
        Map<Path, String> replacements = new LinkedHashMap<>();
        for (var file : files.entrySet()) {
            String text = Files.readString(file.getKey());
            List<Change> edits = file.getValue();
            edits.sort(Comparator.comparingInt((Change c) -> c.location().start()).reversed());
            int boundary = text.length();
            StringBuilder result = new StringBuilder(text);
            for (Change edit : edits) {
                Location l = edit.location();
                if (!text.equals(l.snapshot()))
                    throw new IOException("Source changed during selection: " + file.getKey());
                if (l.end() > boundary) throw new IOException("Overlapping edits: " + file.getKey());
                result.replace(l.start(), l.end(), edit.target());
                boundary = l.start();
            }
            replacements.put(file.getKey(), result.toString());
        }
        List<Path> written = new ArrayList<>();
        for (var file : replacements.entrySet()) {
            try {
                Files.writeString(file.getKey(), file.getValue());
                written.add(file.getKey());
            } catch (IOException failure) {
                throw new IOException("Write failed at " + file.getKey() + "; that file may be partially written. Files already changed: "
                        + written + ". No rollback performed.", failure);
            }
        }
        return written;
    }
}
