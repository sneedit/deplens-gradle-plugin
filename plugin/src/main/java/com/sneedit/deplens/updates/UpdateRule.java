/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.updates;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * Explicit fields override broader rules; absent fields inherit.
 */
public abstract class UpdateRule {
    /**
     * Creates an initially unset rule so each field can inherit from a broader scope.
     */
    @javax.inject.Inject
    public UpdateRule() {
        getIgnoredVersions().convention((Iterable<String>) null);
        getIgnoredVersionPatterns().convention((Iterable<String>) null);
    }

    /**
     * Whole-version regex with numeric named groups major, minor and patch.
     *
     * @return optional version pattern with numeric capture groups
     */
    public abstract Property<String> getVersionPattern();

    /**
     * Regex searched within a version; replaces the automatic prerelease detector.
     *
     * @return optional prerelease detection pattern
     */
    public abstract Property<String> getPreReleasePattern();

    /**
     * Java regex applied to comparison keys only; configure together with its replacement.
     *
     * @return optional regex for transforming version comparison keys
     */
    public abstract Property<String> getVersionNormalizationPattern();

    /**
     * Java regex replacement, including numeric or named capture references.
     *
     * @return optional Java regex replacement for normalized comparison keys
     */
    public abstract Property<String> getVersionNormalizationReplacement();

    /**
     * Configures whether this rule protects the current version from updates.
     *
     * @return optional pin override
     */
    public abstract Property<Boolean> getPinned();

    /**
     * Configures whether prerelease candidates are allowed.
     *
     * @return optional prerelease override
     */
    public abstract Property<Boolean> getIncludePreReleases();

    /**
     * patch, minor (includes patch), or major (all updates).
     *
     * @return optional highest allowed update tier
     */
    public abstract Property<String> getAllowedUpdates();

    /**
     * Configures the exact versions excluded by this rule.
     *
     * @return optional ignored version list
     */
    public abstract ListProperty<String> getIgnoredVersions();

    /**
     * Java regexes searched within original versions, independently of prerelease filtering.
     * An explicit empty list clears inherited patterns.
     *
     * @return optional version exclusion patterns
     */
    public abstract ListProperty<String> getIgnoredVersionPatterns();
}
