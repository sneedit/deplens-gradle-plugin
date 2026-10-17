/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.integrations.spring;

import org.gradle.api.Project;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Kotlin plugin and property versions, with a flag for automatic alignment.
 */
record SpringKotlinAlignment(String pluginVersion, String propertyVersion, boolean automatic) {
    /**
     * Reads Kotlin plugin and property versions and distinguishes automatic from explicit alignment.
     */
    static @Nullable SpringKotlinAlignment inspect(Project project) throws ReflectiveOperationException {
        if (!project.getPluginManager().hasPlugin("org.springframework.boot")) return null;
        Object plugin = project.getPlugins().findPlugin("org.jetbrains.kotlin.jvm");
        if (plugin == null) plugin = project.getPlugins().findPlugin("kotlin");
        if (plugin == null) return null;
        Class<?> api = plugin.getClass().getClassLoader().loadClass("org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapperKt");
        String version = (String) api.getMethod("getKotlinPluginVersion", Project.class).invoke(null, project);
        Object property = project.findProperty("kotlin.version");
        if (property == null) return null;
        return new SpringKotlinAlignment(version, property.toString(), version.equals(property.toString()) && !explicitKotlinProperty(project));
    }

    /**
     * Checks source files for an explicit Kotlin version property override.
     */
    private static boolean explicitKotlinProperty(Project project) {
        if (project.getGradle().getStartParameter().getProjectProperties().containsKey("kotlin.version")
                || System.getProperty("org.gradle.project.kotlin.version") != null
                || System.getenv("ORG_GRADLE_PROJECT_kotlin.version") != null) return true;
        // Boot's extra property has no origin metadata. Preserve user overrides conservatively
        // whenever build inputs mention the property, even if its value equals the plugin version.
        Set<Path> inputs = new LinkedHashSet<>();
        for (Project current = project; current != null; current = current.getParent()) {
            inputs.add(current.getBuildFile().toPath());
            inputs.add(current.getProjectDir().toPath().resolve("gradle.properties"));
        }
        inputs.add(project.getRootDir().toPath().resolve("settings.gradle"));
        inputs.add(project.getRootDir().toPath().resolve("settings.gradle.kts"));
        inputs.add(project.getGradle().getGradleUserHomeDir().toPath().resolve("gradle.properties"));
        try {
            for (Path input : inputs)
                if (Files.isRegularFile(input) && Files.readString(input).contains("kotlin.version")) return true;
        } catch (IOException unreadable) {
            return true;
        }
        return false;
    }
}

