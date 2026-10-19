/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens;

import org.gradle.api.Action;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Nested;

import javax.inject.Inject;

/**
 * DepLens build scope and dependency, plugin and Gradle update policies.
 * Inventory collection is read-only and does not apply update rules.
 */
public abstract class DepLensExtension {

    /**
     * Console presentation: standard includes project paths; compact hides Projects lines.
     *
     * @return console mode property accepting standard or compact
     */
    public abstract Property<String> getConsoleMode();

    /**
     * Controls full consumer-graph checks for update candidates; enabled by default.
     *
     * @return whether update candidates are checked in the full consumer graph
     */
    public abstract Property<Boolean> getCheckBuildContext();

    /**
     * Fails check tasks after reporting when their selected checks or inventory are incomplete.
     * Successful policy results without matching candidates remain complete.
     *
     * @return opt-in CI failure policy, defaulting to false
     */
    public abstract Property<Boolean> getFailIfChecksIncomplete();

    /**
     * Configures whether selected included builds participate in analysis.
     *
     * @return included-build participation property, defaulting to true
     */
    public abstract Property<Boolean> getCheckIncludedBuilds();

    /**
     * Configures whether buildSrc participates in analysis.
     *
     * @return buildSrc participation property, defaulting to true
     */
    public abstract Property<Boolean> getCheckBuildSrc();

    private final com.sneedit.deplens.updates.UpdateRules rules;
    private final ConfigurationFilter checkResolvableConfigurationFilter;
    private final ConfigurationFilter outputDeclaredConfigurationFilter;
    private final GradleSection gradle;
    private final PluginSection plugins;
    private final DependencySection dependencies;

    private final Provider<Boolean> failIfUpdatesFound;

    /**
     * Creates the dependency, plugin, wrapper and scoped-rule configuration sections.
     *
     * @param objects Gradle object factory
     */
    @Inject
    public DepLensExtension(ObjectFactory objects) {
        getCheckIncludedBuilds().convention(true);
        getCheckBuildSrc().convention(true);
        getCheckBuildContext().convention(true);
        getFailIfChecksIncomplete().convention(false);
        getConsoleMode().convention("compact");

        this.checkResolvableConfigurationFilter = objects.newInstance(ConfigurationFilter.class);
        this.outputDeclaredConfigurationFilter = objects.newInstance(ConfigurationFilter.class);
        this.rules = objects.newInstance(com.sneedit.deplens.updates.UpdateRules.class);
        this.gradle = objects.newInstance(GradleSection.class);
        this.plugins = objects.newInstance(PluginSection.class);
        this.dependencies = objects.newInstance(DependencySection.class);

        this.failIfUpdatesFound =
                gradle.getFailIfUpdatesFound()
                        .zip(plugins.getFailIfUpdatesFound(), (g, p) ->
                                Boolean.TRUE.equals(g) || Boolean.TRUE.equals(p)
                        )
                        .zip(dependencies.getFailIfUpdatesFound(), (gp, d) ->
                                Boolean.TRUE.equals(gp) || Boolean.TRUE.equals(d)
                        );
    }

    /**
     * Selects project configurations before inventory collection and update discovery.
     *
     * @return filter selecting resolvable configurations for analysis
     */
    @Nested
    public ConfigurationFilter getCheckResolvableConfigurationFilter() {
        return checkResolvableConfigurationFilter;
    }

    /**
     * Configures which resolvable configurations are analyzed.
     *
     * @param action configuration action for the analysis filter
     */
    public void checkResolvableConfigurationFilter(Action<? super ConfigurationFilter> action) {
        action.execute(checkResolvableConfigurationFilter);
    }

    /**
     * Selects report entries by declaration configuration, without changing checks.
     *
     * @return filter selecting declared configurations for presentation
     */
    @Nested
    public ConfigurationFilter getOutputDeclaredConfigurationFilter() {
        return outputDeclaredConfigurationFilter;
    }

    /**
     * Configures which declared configurations appear in reports and interactive selection.
     *
     * @param action configuration action for the presentation filter
     */
    public void outputDeclaredConfigurationFilter(Action<? super ConfigurationFilter> action) {
        action.execute(outputDeclaredConfigurationFilter);
    }

    /**
     * Provides scoped update rules.
     *
     * @return scoped update rules
     */
    @Nested
    public com.sneedit.deplens.updates.UpdateRules getRules() {
        return rules;
    }

    /**
     * Configures scoped update rules through the Gradle DSL.
     *
     * @param action configuration callback or captured rule action
     */
    public void rules(Action<? super com.sneedit.deplens.updates.UpdateRules> action) {
        action.execute(rules);
    }

    /**
     * Provides wrapper update settings.
     *
     * @return wrapper update settings
     */
    @Nested
    public GradleSection getGradle() {
        return gradle;
    }

    /**
     * Configures wrapper updates through the Gradle DSL.
     *
     * @param action configuration callback or captured rule action
     */
    public void gradle(Action<? super GradleSection> action) {
        action.execute(gradle);
    }

    /**
     * Provides plugin update settings.
     *
     * @return plugin update settings
     */
    @Nested
    public PluginSection getPlugins() {
        return plugins;
    }

    /**
     * Configures plugin updates through the Gradle DSL.
     *
     * @param action configuration callback or captured rule action
     */
    public void plugins(Action<? super PluginSection> action) {
        action.execute(plugins);
    }

    /**
     * Provides dependency update settings.
     *
     * @return dependency update settings
     */
    @Nested
    public DependencySection getDependencies() {
        return dependencies;
    }

    /**
     * Configures dependency updates through the Gradle DSL.
     *
     * @param action configuration callback or captured rule action
     */
    public void dependencies(Action<? super DependencySection> action) {
        action.execute(dependencies);
    }

    /**
     * Combines section failure flags into the overall update-check failure policy.
     *
     * @return provider that is true when any section enables failure on updates
     */
    public Provider<Boolean> getFailIfUpdatesFound() {
        return failIfUpdatesFound;
    }
}
