/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens;

import com.sneedit.deplens.tasks.*;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;

/**
 * Registers the public task names and their report destinations.
 */
final class DepLensTaskRegistrar {
    private DepLensTaskRegistrar() {
    }

    static void registerTasks(Project project) {
        register(project, "depLensInventory", DepLensInventoryTask.class, "Inventory dependencies, platforms, plugins and version sources across the build", "inventory");
        register(project, "depLensCheckDependencies", CheckDependencyUpdatesTask.class, "Check for available dependency updates", "depLensCheckDependencies");
        register(project, "depLensCheckPlugins", CheckPluginUpdatesTask.class, "Check for available Gradle plugin updates", "depLensCheckPlugins");
        register(project, "depLensCheckGradle", CheckGradleUpdatesTask.class, "Check for available Gradle updates", "depLensCheckGradle");
        register(project, "depLensCheck", CheckUpdatesTask.class, "Check for all available updates (dependencies, plugins, Gradle)", "depLensCheck");
        if (project.getPath().equals(":")) {
            var update = register(project, "depLensInteractiveUpdate", DepLensUpdateTask.class, "Interactively select updates and edit their version sources", "depLensInteractiveUpdate");
            update.configure(t -> t.getTerminal().convention(project.getProviders().environmentVariable("DEPLENS_TTY")));
        }
    }

    /**
     * Registers a task with its description, analysis models and JSON report destination.
     */
    private static <T extends DepLensInventoryTask> TaskProvider<T> register(Project p, String name, Class<T> type, String description, String report) {
        return p.getTasks().register(name, type, t -> {
            t.setGroup("deplens");
            t.setDescription(description);
            t.getReportFile().convention(p.getLayout().getBuildDirectory().file("reports/deplens/" + report + ".json"));
        });
    }
}
