/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.inventory;

import org.gradle.api.Project;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared build-scope discovery and filtering for isolated analysis models.
 */
public final class NestedInventorySupport {
    /**
     * Invocation property controlling inventory traversal of included builds.
     */
    public static final String CHECK_INCLUDED_BUILDS = "deplens.inventory.checkIncludedBuilds";
    /**
     * Invocation property controlling inventory traversal of buildSrc.
     */
    public static final String CHECK_BUILD_SRC = "deplens.inventory.checkBuildSrc";

    /**
     * Keeps this utility class static-only.
     */
    private NestedInventorySupport() {
    }

    /**
     * Shared by scheduling and discovery so intentionally excluded builds are not reported as incomplete.
     */
    static Set<File> selectedBuilds(Project project) {
        var extension = project.getExtensions().findByType(com.sneedit.deplens.DepLensExtension.class);
        boolean included = extension == null || extension.getCheckIncludedBuilds().get();
        boolean buildSrc = extension == null || extension.getCheckBuildSrc().get();
        File source = new File(project.getRootDir(), "buildSrc");
        Set<File> builds = new LinkedHashSet<>();
        if (included) {
            project.getGradle().getIncludedBuilds().forEach(build -> builds.add(build.getProjectDir()));
        }
        // Gradle versions may expose buildSrc among included builds. Its switch is independent.
        builds.removeIf(file -> file.toPath().toAbsolutePath().normalize().equals(source.toPath().toAbsolutePath().normalize()));
        if (buildSrc && source.isDirectory()) builds.add(source);
        builds.removeIf(file -> isDepLensDevelopmentBuild(file.toPath()));
        return builds;
    }

    /**
     * Recognizes Gradle's tooling builds so they do not become consumer update targets.
     *
     * @param directory build directory to inspect
     * @return true for a build used as Gradle tooling rather than as a consumer
     */
    public static boolean isToolingBuild(Path directory) {
        return isDepLensDevelopmentBuild(directory);
    }

    /**
     * An includeBuild used to load this plugin is tooling, not a consumer build.
     */
    static boolean isDepLensDevelopmentBuild(Path directory) {
        Path implementation = directory.resolve("plugin/src/main/java/com/sneedit/deplens/DepLensPlugin.java");
        for (String script : List.of("plugin/build.gradle", "plugin/build.gradle.kts")) {
            try {
                if (Files.isRegularFile(implementation) && Files.readString(directory.resolve(script))
                        .contains("com.sneedit.deplens.DepLensPlugin")) return true;
            } catch (java.io.IOException ignored) { /* Not this plugin's development checkout. */ }
        }
        return false;
    }

}
