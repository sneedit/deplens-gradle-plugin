/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.resolution;

import com.google.gson.Gson;
import com.sneedit.deplens.DepLensExtension;
import com.sneedit.deplens.DepLensSettingsPlugin;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.tooling.provider.model.ParameterizedToolingModelBuilder;
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry;

import javax.inject.Inject;

/**
 * Captures only the project whose model Gradle is currently building, under its own isolation boundary.
 */
public final class AnalysisModelPlugin implements Plugin<Project> {
    /**
     * Tooling model name used to retrieve captured DepLens analysis models from nested builds.
     */
    public static final String NAME = "com.sneedit.deplens.AnalysisModel";
    private final ToolingModelBuilderRegistry builders;

    /**
     * Stores the injected tooling model builder registry.
     *
     * @param builders tooling model builder registry
     */
    @Inject
    public AnalysisModelPlugin(ToolingModelBuilderRegistry builders) {
        this.builders = builders;
    }

    /**
     * Registers the parameterized builder used for isolated project analysis.
     *
     * @param project Gradle project being inspected
     */
    @Override
    public void apply(Project project) {
        builders.register(new Builder());
    }

    /**
     * The defaults accepted by the isolated analysis model builder.
     */
    public interface Parameters {
        /**
         * Supplies inherited policy defaults as JSON to the isolated model request.
         *
         * @return serialized policy defaults
         */
        String getDefaultsJson();
    }

    /**
     * A tooling model request carrying serialized policy defaults.
     */
    public static final class Request {
        private final String defaultsJson;

        /**
         * Stores serialized defaults for the tooling model request.
         *
         * @param defaultsJson serialized analysis defaults
         */
        public Request(String defaultsJson) {
            this.defaultsJson = defaultsJson;
        }

        /**
         * Returns the serialized defaults sent to the model builder.
         *
         * @return serialized policy defaults
         */
        public String getDefaultsJson() {
            return defaultsJson;
        }
    }

    /**
     * Captures one project's analysis using inherited defaults and its settings context.
     *
     * @param project  Gradle project being inspected
     * @param defaults inherited analysis defaults
     * @return captured project analysis with inherited defaults
     */
    public static AnalysisModel capture(Project project, AnalysisDefaults defaults) {
        var extension = project.getExtensions().findByType(DepLensExtension.class);
        if (extension == null) {
            extension = project.getObjects().newInstance(DepLensExtension.class);
            defaults.apply(extension);
            project.getExtensions().add("depLensAnalysisDefaults", extension);
        }
        defaults.inheritCheckResolvableConfigurationFilter(extension);
        var settings = SettingsModel.capture(((org.gradle.api.internal.GradleInternal) project.getGradle()).getSettings());
        project.getExtensions().getExtraProperties().set(DepLensSettingsPlugin.CONTEXT, settings);
        return AnalysisModel.capture(project, extension, defaults.rules);
    }

    /**
     * Builds DepLens analysis models behind Gradle's project isolation boundary.
     */
    private static final class Builder implements ParameterizedToolingModelBuilder<Parameters> {
        /**
         * Checks whether Gradle is requesting the DepLens analysis model.
         *
         * @param name requested tooling model name
         * @return true for the DepLens analysis model name
         */
        @Override
        public boolean canBuild(String name) {
            return name.equals(NAME);
        }

        /**
         * Declares the request parameter interface expected by this builder.
         *
         * @return analysis request parameter interface
         */
        @Override
        public Class<Parameters> getParameterType() {
            return Parameters.class;
        }

        /**
         * Builds an isolated analysis model when defaults are supplied; parameterless requests fail.
         *
         * @param name    requested tooling model name
         * @param project Gradle project being inspected
         * @return captured project analysis when request parameters are provided
         */
        @Override
        public Object buildAll(String name, Project project) {
            throw new IllegalArgumentException("DepLens model requires policy defaults");
        }

        /**
         * Builds an isolated analysis model when defaults are supplied; parameterless requests fail.
         *
         * @param name      requested tooling model name
         * @param parameter tooling model parameters
         * @param project   Gradle project being inspected
         * @return captured project analysis when request parameters are provided
         */
        @Override
        public Object buildAll(String name, Parameters parameter, Project project) {
            return capture(project, java.util.Objects.requireNonNull(
                    new Gson().fromJson(parameter.getDefaultsJson(), AnalysisDefaults.class), "Missing DepLens analysis defaults"));
        }
    }
}
