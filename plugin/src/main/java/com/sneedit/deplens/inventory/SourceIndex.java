/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.inventory;

import org.gradle.api.artifacts.VersionCatalog;
import org.jspecify.annotations.Nullable;
import org.tomlj.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.sneedit.deplens.inventory.InventoryEntry.*;
import static com.sneedit.deplens.inventory.SourceText.*;

/**
 * Conservative source attribution: ambiguous or computed sources remain explicitly unknown.
 */
public final class SourceIndex {
    /**
     * A plugin declaration with its selector, source location and application state.
     *
     * @param id          plugin ID or operation identifier
     * @param selector    declared version selector, when available
     * @param source      original configuration or recorded version source
     * @param state       plugin declaration state
     * @param declaration plugin declaration location and configuration
     */
    public record PluginDeclaration(String id, @Nullable String selector, Source source, Declaration declaration,
                                    String state) {
    }

    /**
     * An indexed library or plugin alias with its version source and catalog reference.
     */
    private record CatalogItem(String coordinate, boolean plugin, @Nullable String version, Source source,
                               CatalogReference reference) {
    }

    private final Path root;
    private final Map<Path, String> scripts = new LinkedHashMap<>();
    private final Map<String, List<Source>> properties = new LinkedHashMap<>();
    private final List<CatalogItem> catalogItems = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    /**
     * Creates a source index rooted at the build directory.
     *
     * @param root root build project or active operation
     */
    public SourceIndex(Path root) {
        this.root = root;
    }

    /**
     * Returns source-reading warnings collected so far.
     *
     * @return collected source warnings
     */
    public List<String> warnings() {
        return warnings;
    }

    /**
     * Caches a build script with comments masked while preserving source positions.
     *
     * @param file source or output file
     */
    public void addScript(Path file) {
        if (!Files.isRegularFile(file) || scripts.containsKey(file)) return;
        try {
            scripts.put(file, withoutComments(Files.readString(file)));
        } catch (IOException e) {
            warnings.add("Cannot read build script: " + file);
        }
    }

    /**
     * Indexes simple property assignments and their original file locations.
     *
     * @param file source or output file
     */
    public void addProperties(Path file) {
        if (!Files.isRegularFile(file)) return;
        try {
            String text = Files.readString(file);
            Matcher m = Pattern.compile("(?m)^\\s*([\\w.-]+)\\s*[=:]\\s*([^\\r\\n]+)").matcher(text);
            while (m.find()) {
                Source source = new Source(file.toString(), line(text, m.start(2)), SourceKind.PROPERTY, m.group(1), m.group(2).trim());
                properties.computeIfAbsent(m.group(1), key -> new ArrayList<>()).add(source);
            }
        } catch (IOException e) {
            warnings.add("Cannot read properties: " + file);
        }
    }

    /**
     * Indexes TOML libraries, plugins, shared version keys and bundle membership.
     *
     * @param name version catalog name
     * @param file source or output file
     */
    public void addCatalog(String name, Path file) {
        if (!Files.isRegularFile(file)) return;
        try {
            TomlParseResult result = Toml.parse(file);
            if (result.hasErrors()) {
                warnings.add("Cannot parse catalog: " + file);
                return;
            }
            Map<String, List<String>> bundles = new LinkedHashMap<>();
            TomlTable bundleTable = result.getTable("bundles");
            if (bundleTable != null) for (String key : bundleTable.keySet()) {
                TomlArray members = bundleTable.getArray(List.of(key));
                if (members != null) for (int i = 0; i < members.size(); i++) {
                    bundles.computeIfAbsent(members.getString(i), ignored -> new ArrayList<>()).add(key);
                }
            }
            for (String section : List.of("libraries", "plugins")) {
                TomlTable table = result.getTable(section);
                if (table == null) continue;
                for (String alias : table.keySet()) {
                    Object item = table.get(List.of(alias));
                    String coordinate = null, version = null, versionKey = null;
                    boolean plugin = section.equals("plugins");
                    if (item instanceof String shorthand && !plugin) {
                        String[] parts = shorthand.split(":", 3);
                        if (parts.length >= 2) coordinate = parts[0] + ":" + parts[1];
                        if (parts.length == 3) version = parts[2];
                    } else if (item instanceof TomlTable detail) {
                        coordinate = plugin ? detail.getString("id") : detail.getString("module");
                        if (!plugin && coordinate == null && detail.getString("group") != null) {
                            coordinate = detail.getString("group") + ":" + detail.getString("name");
                        }
                        Object v = detail.get("version");
                        if (v instanceof String literal) version = literal;
                        else if (v instanceof TomlTable rich) {
                            versionKey = rich.getString("ref");
                            version = richVersion(rich);
                        }
                    }
                    TomlPosition position = table.inputPositionOf(List.of(alias));
                    String sourceKey = section + "." + alias;
                    if (versionKey != null) {
                        TomlTable versions = result.getTable("versions");
                        Object value = versions == null ? null : versions.get(List.of(versionKey));
                        version = value instanceof String literal ? literal : value instanceof TomlTable rich ? richVersion(rich) : null;
                        position = versions == null ? position : versions.inputPositionOf(List.of(versionKey));
                        sourceKey = "versions." + versionKey;
                    }
                    if (coordinate != null) catalogItems.add(new CatalogItem(coordinate, plugin, version,
                            new Source(file.toString(), position == null ? 1 : position.line(), SourceKind.CATALOG, sourceKey, version),
                            new CatalogReference(name, alias, versionKey, List.copyOf(bundles.getOrDefault(alias, List.of())))));
                }
            }
        } catch (IOException | RuntimeException e) {
            warnings.add("Cannot read catalog: " + file);
        }
    }

    /**
     * Fills catalog entries missing from files using Gradle's live catalog model.
     *
     * @param catalog Gradle version catalog
     */
    public void addCatalogModel(VersionCatalog catalog) {
        for (String alias : catalog.getLibraryAliases()) {
            if (catalogItems.stream().anyMatch(item -> !item.plugin && item.reference.catalog().equals(catalog.getName()) && normalizeAlias(item.reference.alias()).equals(normalizeAlias(alias))))
                continue;
            var dependency = catalog.findLibrary(alias).orElseThrow().get();
            var version = dependency.getVersionConstraint();
            String selector = !version.getStrictVersion().isEmpty() ? version.getStrictVersion() : !version.getRequiredVersion().isEmpty() ? version.getRequiredVersion() : version.getPreferredVersion();
            List<String> bundles = catalog.getBundleAliases().stream().filter(bundle -> catalog.findBundle(bundle).orElseThrow().get().stream()
                    .anyMatch(member -> member.getModule().equals(dependency.getModule()))).toList();
            catalogItems.add(new CatalogItem(dependency.getModule().toString(), false, selector,
                    new Source("<catalog:" + catalog.getName() + ">", 0, SourceKind.UNKNOWN, alias, selector),
                    new CatalogReference(catalog.getName(), alias, null, bundles)));
        }
        for (String alias : catalog.getPluginAliases()) {
            if (catalogItems.stream().anyMatch(item -> item.plugin && item.reference.catalog().equals(catalog.getName()) && normalizeAlias(item.reference.alias()).equals(normalizeAlias(alias))))
                continue;
            var plugin = catalog.findPlugin(alias).orElseThrow().get();
            String selector = plugin.getVersion().getRequiredVersion();
            catalogItems.add(new CatalogItem(plugin.getPluginId(), true, selector,
                    new Source("<catalog:" + catalog.getName() + ">", 0, SourceKind.UNKNOWN, alias, selector),
                    new CatalogReference(catalog.getName(), alias, null, List.of())));
        }
    }

    /**
     * Maps dash and underscore separators to Gradle's dotted accessor form.
     */
    private static String normalizeAlias(String alias) {
        return alias.replaceAll("[-_]", ".");
    }

    /**
     * Reads the first available strict, required or preferred TOML version.
     */
    private static @Nullable String richVersion(TomlTable version) {
        for (String key : List.of("strictly", "require", "prefer")) {
            String value = version.getString(key);
            if (value != null) return value;
        }
        return null;
    }

    /**
     * Matches declarations to source locations without guessing computed version expressions.
     *
     * @param entry         component being inspected or configured
     * @param script        build or settings script path
     * @param configuration configuration containing the declaration
     * @param selector      declared version selector, when available
     */
    public void attach(InventoryEntry entry, Path script, String configuration, @Nullable String selector) {
        String text = scripts.getOrDefault(script, "");
        boolean found = false;
        Matcher m = Pattern.compile("(['\"])" + Pattern.quote(entry.coordinate) + "(?::([^'\"\\r\\n]+))?\\1").matcher(text);
        while (m.find()) {
            // Only declarations with a matching version are attributed; unrelated literals are not edited.
            String expression = m.group(2);
            Source source = expression == null ? null : resolve(script, text, expression, m.start(2));
            if (source != null && selector != null && source.value() != null && !selector.equals(source.value()))
                continue;
            entry.declarations.add(new Declaration(script.toString(), line(text, m.start()), configuration, selector));
            if (source != null) entry.versionSources.add(source);
            found = true;
        }
        String[] coordinate = entry.coordinate.split(":", 2);
        if (coordinate.length == 2) {
            Matcher map = Pattern.compile("\\bgroup\\s*[:=]\\s*['\"]" + Pattern.quote(coordinate[0])
                    + "['\"]\\s*,\\s*name\\s*[:=]\\s*['\"]" + Pattern.quote(coordinate[1])
                    + "['\"](?:\\s*,\\s*version\\s*[:=]\\s*(?:(['\"])([^'\"\\r\\n]+)\\1|([\\w.]+)))?").matcher(text);
            while (map.find()) {
                Source source = map.group(2) != null ? resolve(script, text, map.group(2), map.start(2))
                        : map.group(3) != null ? resolve(script, text, "$" + map.group(3), map.start(3)) : null;
                if (source != null && source.value() != null && selector != null && !source.value().equals(selector))
                    continue;
                entry.declarations.add(new Declaration(script.toString(), line(text, map.start()), configuration, selector));
                if (source != null) entry.versionSources.add(source);
                found = true;
            }
        }
        for (CatalogItem item : catalogItems) {
            if (item.plugin || !item.coordinate.equals(entry.coordinate)) continue;
            // A catalog existing is not evidence that its alias was used by this declaration.
            String accessor = item.reference.catalog() + "." + normalizeAlias(item.reference.alias());
            boolean used = references(text, accessor);
            for (String bundle : item.reference.bundles()) {
                used |= references(text, item.reference.catalog() + ".bundles." + normalizeAlias(bundle));
            }
            if (!used || (selector != null && item.version != null && !selector.equals(item.version))) continue;
            entry.catalogs.add(item.reference);
            entry.versionSources.add(item.source);
            entry.declarations.add(new Declaration(script.toString(), referenceLine(text, accessor), configuration, selector));
            found = true;
        }
        if (!found) {
            entry.declarations.add(new Declaration(script.toString(), 0, configuration, selector));
            entry.versionSources.add(new Source(script.toString(), 0, SourceKind.UNKNOWN, null, selector));
        }
    }

    /**
     * Finds literal and catalog plugin declarations and their application state.
     *
     * @param script build or settings script path
     * @return plugin declarations found in the script
     */
    public List<PluginDeclaration> plugins(Path script) {
        String text = scripts.getOrDefault(script, "");
        List<PluginDeclaration> result = new ArrayList<>();
        Matcher m = Pattern.compile("\\bid\\s*(?:\\(\\s*)?(['\"])([^'\"]+)\\1\\s*\\)?\\s*(?:version\\s*(?:\\(\\s*)?(['\"])([^'\"]+)\\3\\s*\\)?)?((?:\\s*apply\\s*(?:\\(\\s*)?false\\s*\\)?)?)").matcher(text);
        while (m.find()) {
            if (!insideBlock(text, m.start(), "plugins")) continue;
            String expression = m.group(4);
            Source source = expression == null ? new Source(script.toString(), line(text, m.start()), SourceKind.UNKNOWN, null, null)
                    : resolve(script, text, expression, m.start(4));
            String state = m.group(5).contains("apply") ? "DECLARED_APPLY_FALSE" : "DECLARED";
            if (insideBlock(text, m.start(), "pluginManagement")) state = "PLUGIN_MANAGEMENT";
            result.add(new PluginDeclaration(m.group(2), source.value(), source,
                    new Declaration(script.toString(), line(text, m.start()), "plugins", expression), state));
        }
        Matcher aliases = Pattern.compile("\\balias\\s*(?:\\(\\s*)?([\\w.]+)\\.plugins\\.([\\w.]+)\\s*\\)?((?:\\s*apply\\s*(?:\\(\\s*)?false\\s*\\)?)?)").matcher(text);
        while (aliases.find()) {
            if (!insideBlock(text, aliases.start(), "plugins")) continue;
            for (CatalogItem item : catalogItems) {
                if (!item.plugin || !item.reference.catalog().equals(aliases.group(1)) ||
                        !item.reference.alias().replaceAll("[-_]", ".").equals(aliases.group(2))) continue;
                result.add(new PluginDeclaration(item.coordinate, item.version, item.source,
                        new Declaration(script.toString(), line(text, aliases.start()), "plugins", item.version),
                        aliases.group(3).contains("apply") ? "DECLARED_APPLY_FALSE" : "DECLARED"));
            }
        }
        return result;
    }

    /**
     * Attaches catalog references matching a plugin entry.
     *
     * @param entry component being inspected or configured
     */
    public void attachPluginCatalogs(InventoryEntry entry) {
        for (CatalogItem item : catalogItems)
            if (item.plugin && item.coordinate.equals(entry.coordinate)
                    && entry.versionSources.contains(item.source)) entry.catalogs.add(item.reference);
    }

    /**
     * Resolves a literal, property or simple variable expression to its version source.
     */
    private Source resolve(Path script, String text, String expression, int offset) {
        SourceKind kind = script.getFileName().toString().startsWith("settings.") ? SourceKind.SETTINGS : SourceKind.BUILD_SCRIPT;
        Matcher variable = Pattern.compile("^\\$\\{?([\\w.]+)}?$").matcher(expression);
        if (!variable.matches()) {
            if (expression.contains("$"))
                return new Source(script.toString(), line(text, offset), SourceKind.UNKNOWN, null, null);
            return new Source(script.toString(), line(text, offset), kind, null, expression);
        }
        boolean rootQualified = variable.group(1).startsWith("rootProject.");
        String key = variable.group(1).replaceFirst("^(?:(?:project|rootProject|ext)\\.)+", "");
        // A local literal assignment wins over gradle.properties. Shared root literals are also indexed.
        List<Path> search = rootQualified ? List.of(root.resolve("build.gradle"), root.resolve("build.gradle.kts"))
                : script.equals(root.resolve("build.gradle")) || script.equals(root.resolve("build.gradle.kts"))
                ? List.of(script) : List.of(script, root.resolve("build.gradle"), root.resolve("build.gradle.kts"));
        for (Path file : search) {
            String content = scripts.getOrDefault(file, "");
            Matcher assignment = Pattern.compile("(?m)(?:^|[\\s;{])(?:def\\s+|val\\s+|var\\s+)?(?:ext\\.)?" + Pattern.quote(key)
                    + "\\s*=\\s*(['\"])([^'\"$\\r\\n]+)\\1").matcher(content);
            while (assignment.find()) {
                if (blockDepth(content, assignment.start()) != 0 && !insideBlock(content, assignment.start(), "ext"))
                    continue;
                return new Source(file.toString(), line(content, assignment.start(2)), SourceKind.VARIABLE, key, assignment.group(2));
            }
        }
        List<Source> values = properties.getOrDefault(key, List.of());
        List<Path> propertyFiles = rootQualified ? List.of(root.resolve("gradle.properties"))
                : List.of(script.toAbsolutePath().resolveSibling("gradle.properties"), root.resolve("gradle.properties"));
        for (Path file : propertyFiles) {
            List<Source> matching = values.stream().filter(source -> Path.of(source.file()).equals(file)).toList();
            if (matching.size() == 1) return matching.getFirst();
        }
        return new Source(script.toString(), line(text, offset), SourceKind.UNKNOWN, key, null);
    }

    /**
     * Checks for a complete catalog accessor token rather than a substring hit.
     */
    private static boolean references(String text, String accessor) {
        return Pattern.compile("(?<![\\w.])" + Pattern.quote(accessor) + "(?![\\w])").matcher(text).find();
    }


    /**
     * Masks comments without shifting source offsets; quoted strings keep their bytes.
     *
     * @param text script text to scan
     * @return text with comments replaced by spaces, with newlines intact
     */
    public static String withoutComments(String text) {
        return SourceText.withoutComments(text);
    }
}
