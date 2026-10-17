/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.inventory;

import org.gradle.api.Project;
import org.gradle.util.GradleVersion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.sneedit.deplens.inventory.InventoryEntry.*;

/**
 * Reads wrapper and catalog files and records separate builds that need their own inventory.
 */
final class BuildFilesInventory {
    /**
     * Keeps this utility class static-only.
     */
    private BuildFilesInventory() {
    }

    /**
     * Records the running Gradle version and wrapper declaration, warning about mismatches.
     */
    static void collectGradle(Path directory, InventoryEntry gradle, BuildInventory report) {
        gradle.resolutions.add(new Resolution("runtime", GradleVersion.current().getVersion(), List.of()));
        Path wrapper = directory.resolve("gradle/wrapper/gradle-wrapper.properties");
        if (!Files.isRegularFile(wrapper)) {
            gradle.warnings.add("No Gradle wrapper found.");
            return;
        }
        try {
            String text = Files.readString(wrapper);
            Matcher m = Pattern.compile("(?m)^distributionUrl=.*gradle-([^/\\r\\n]+)-(?:bin|all)\\.zip\\s*$").matcher(text);
            if (m.find()) {
                gradle.declaredSelectors.add(m.group(1));
                int line = 1 + (int) text.substring(0, m.start()).chars().filter(c -> c == '\n').count();
                gradle.versionSources.add(new Source(wrapper.toString(), line, SourceKind.WRAPPER, "distributionUrl", m.group(1)));
                if (!m.group(1).equals(GradleVersion.current().getVersion()))
                    gradle.warnings.add("Wrapper version differs from the running Gradle version.");
            } else {
                gradle.warnings.add("Wrapper URL has no recognizable Gradle version.");
                report.incomplete("Unknown wrapper version: " + wrapper);
            }
        } catch (IOException e) {
            report.incomplete("Cannot read wrapper: " + wrapper);
        }
    }

    /**
     * Finds catalog file imports in settings scripts and adds them to the source index.
     */
    static void discoverCatalogs(Path directory, SourceIndex sources, BuildInventory report) {
        // Catalogs imported from files in settings; custom names remain distinct.
        for (String settings : List.of("settings.gradle", "settings.gradle.kts")) {
            Path file = directory.resolve(settings);
            if (!Files.isRegularFile(file)) continue;
            try {
                String text = SourceIndex.withoutComments(Files.readString(file));
                Matcher m = Pattern.compile("create\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)\\s*\\{[^}]*?from\\s*\\(\\s*files\\s*\\(\\s*['\"]([^'\"]+)['\"]", Pattern.DOTALL).matcher(text);
                while (m.find()) sources.addCatalog(m.group(1), directory.resolve(m.group(2)).normalize());
            } catch (IOException e) {
                report.incomplete("Cannot read settings: " + file);
            }
        }
    }

    /**
     * Records selected separate builds that still need their own inventory invocation.
     */
    static void discoverBuilds(Project root, BuildInventory report) {
        Set<Path> separate = new LinkedHashSet<>();
        NestedInventorySupport.selectedBuilds(root).forEach(file -> separate.add(file.toPath()));
        for (Path path : separate) {
            if (NestedInventorySupport.isDepLensDevelopmentBuild(path)) continue;
            report.builds.add(path.toAbsolutePath().normalize().toString());
            report.incomplete("Separate build requires its own depLensInventory invocation: " + path.toAbsolutePath().normalize());
        }
    }

}
