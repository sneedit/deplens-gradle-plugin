/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.updates;

import org.gradle.api.Action;
import org.gradle.api.model.ObjectFactory;
import org.jspecify.annotations.Nullable;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Rules apply to the whole build, including separate builds.
 */
public class UpdateRules {
    private final ObjectFactory objects;
    private final List<Binding> bindings = new ArrayList<>();

    /**
     * A configured update rule tied to a scope and lookup key.
     *
     * @param scope rule scope
     * @param key   scope-specific lookup key
     * @param rule  configured update rule
     */
    public record Binding(String scope, String key, UpdateRule rule) {
    }

    /**
     * A detached rule snapshot whose unset fields can inherit broader defaults.
     *
     * @param scope                  rule scope
     * @param key                    scope-specific lookup key
     * @param pinned                 optional override protecting the current version
     * @param preReleases            optional prerelease opt-in override
     * @param allowedUpdates         optional highest allowed update tier
     * @param ignored                optional exact versions to ignore
     * @param versionPattern         custom numeric version pattern, or null
     * @param preReleasePattern      custom prerelease detector, or null
     * @param ignoredVersionPatterns optional version exclusion regexes
     */
    public record Spec(String scope, String key, @Nullable Boolean pinned, @Nullable Boolean preReleases,
                       @Nullable String allowedUpdates, @Nullable List<String> ignored, @Nullable String versionPattern,
                       @Nullable String preReleasePattern, @Nullable String versionNormalizationPattern,
                       @Nullable String versionNormalizationReplacement,
                       @Nullable List<String> ignoredVersionPatterns) {
        public Spec(String scope, String key, @Nullable Boolean pinned, @Nullable Boolean preReleases,
                    @Nullable String allowedUpdates, @Nullable List<String> ignored, @Nullable String versionPattern,
                    @Nullable String preReleasePattern, @Nullable String versionNormalizationPattern,
                    @Nullable String versionNormalizationReplacement) {
            this(scope, key, pinned, preReleases, allowedUpdates, ignored, versionPattern, preReleasePattern,
                    versionNormalizationPattern, versionNormalizationReplacement, null);
        }

        public Spec(String scope, String key, @Nullable Boolean pinned, @Nullable Boolean preReleases,
                    @Nullable String allowedUpdates, @Nullable List<String> ignored, @Nullable String versionPattern,
                    @Nullable String preReleasePattern) {
            this(scope, key, pinned, preReleases, allowedUpdates, ignored, versionPattern, preReleasePattern, null, null);
        }
    }

    /**
     * Uses Gradle's object factory to create configurable rule objects.
     *
     * @param objects Gradle object factory
     */
    @Inject
    public UpdateRules(ObjectFactory objects) {
        this.objects = objects;
    }

    /**
     * Configures and validates a rule before binding it to a scope and key.
     */
    private void add(String scope, String key, Action<? super UpdateRule> action) {
        UpdateRule rule = objects.newInstance(UpdateRule.class);
        action.execute(rule);
        if (rule.getAllowedUpdates().isPresent()) validateAllowedUpdates(rule.getAllowedUpdates().get());
        validatePatterns(scope, rule);
        bindings.add(new Binding(scope, key, rule));
    }

    /**
     * Rejects patterns in unsupported scopes and validates supported regular expressions.
     */
    private static void validatePatterns(String scope, UpdateRule rule) {
        if ((rule.getVersionPattern().isPresent() || rule.getPreReleasePattern().isPresent())
                && !Set.of("dependency", "plugin").contains(scope))
            throw new IllegalArgumentException("versionPattern and preReleasePattern are supported only in dependency and plugin rules");
        if (rule.getVersionPattern().isPresent())
            VersionPatterns.compile("versionPattern", rule.getVersionPattern().get());
        if (rule.getPreReleasePattern().isPresent())
            VersionPatterns.compile("preReleasePattern", rule.getPreReleasePattern().get());
        if (rule.getIgnoredVersionPatterns().isPresent())
            rule.getIgnoredVersionPatterns().get().forEach(expression -> VersionPatterns.compile("ignoredVersionPatterns", expression));
        boolean normalization = rule.getVersionNormalizationPattern().isPresent();
        if (normalization != rule.getVersionNormalizationReplacement().isPresent())
            throw new IllegalArgumentException("versionNormalizationPattern and versionNormalizationReplacement must be configured together");
        if (normalization) {
            if (!Set.of("dependency", "plugin").contains(scope))
                throw new IllegalArgumentException("Version normalization is supported only in dependency and plugin rules");
            VersionNormalization.compile(rule.getVersionNormalizationPattern().get(), rule.getVersionNormalizationReplacement().get());
        }
    }

    /**
     * Accepts patch, minor or major and rejects any other update tier.
     *
     * @param value value to inspect or validate
     * @return validated update tier name
     */
    public static String validateAllowedUpdates(String value) {
        if (!Set.of("patch", "minor", "major").contains(value))
            throw new IllegalArgumentException("allowedUpdates must be patch, minor or major; got: " + value);
        return value;
    }

    /**
     * Adds defaults that apply to every component.
     *
     * @param action configuration callback or captured rule action
     */
    public void global(Action<? super UpdateRule> action) {
        add("global", "*", action);
    }

    /**
     * Adds a rule for one project path.
     *
     * @param path   project path or source path
     * @param action configuration callback or captured rule action
     */
    public void project(String path, Action<? super UpdateRule> action) {
        add("project", path, action);
    }

    /**
     * Adds a rule for modules in one dependency group.
     *
     * @param group  dependency group
     * @param action configuration callback or captured rule action
     */
    public void group(String group, Action<? super UpdateRule> action) {
        add("group", group, action);
    }

    /**
     * Adds a rule for one dependency coordinate.
     *
     * @param coordinate module coordinate or plugin ID
     * @param action     configuration callback or captured rule action
     */
    public void dependency(String coordinate, Action<? super UpdateRule> action) {
        add("dependency", coordinate, action);
    }

    /**
     * Adds a rule for one platform or BOM coordinate.
     *
     * @param coordinate module coordinate or plugin ID
     * @param action     configuration callback or captured rule action
     */
    public void platform(String coordinate, Action<? super UpdateRule> action) {
        add("platform", coordinate, action);
    }

    /**
     * Adds a rule for one Gradle plugin ID.
     *
     * @param id     plugin ID or operation identifier
     * @param action configuration callback or captured rule action
     */
    public void plugin(String id, Action<? super UpdateRule> action) {
        add("plugin", id, action);
    }

    /**
     * Adds a rule for the Gradle wrapper.
     *
     * @param action configuration callback or captured rule action
     */
    public void gradle(Action<? super UpdateRule> action) {
        add("gradle", "gradle", action);
    }

    /**
     * Copies configured rules into detached values for analysis models and cache reuse.
     *
     * @return detached rule values in declaration order
     */
    public List<Spec> snapshot() {
        bindings.forEach(b -> validatePatterns(b.scope, b.rule));
        return bindings.stream().map(b -> new Spec(b.scope, b.key, b.rule.getPinned().getOrNull(),
                b.rule.getIncludePreReleases().getOrNull(), b.rule.getAllowedUpdates().getOrNull(),
                b.rule.getIgnoredVersions().isPresent() ? b.rule.getIgnoredVersions().get() : null,
                b.rule.getVersionPattern().getOrNull(), b.rule.getPreReleasePattern().getOrNull(),
                b.rule.getVersionNormalizationPattern().getOrNull(), b.rule.getVersionNormalizationReplacement().getOrNull(),
                b.rule.getIgnoredVersionPatterns().isPresent() ? List.copyOf(b.rule.getIgnoredVersionPatterns().get()) : null)).toList();
    }
}
