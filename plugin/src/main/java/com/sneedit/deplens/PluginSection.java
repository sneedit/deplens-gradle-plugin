/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Gradle DSL settings for plugin updates.
 */
public abstract class PluginSection {
    /**
     * Configures whether available updates fail the check after the report is written.
     *
     * @return failure-on-updates property
     */
    public abstract Property<Boolean> getFailIfUpdatesFound();

    /**
     * Configures whether prerelease versions participate in update checks.
     *
     * @return prerelease opt-in property
     */
    public abstract Property<Boolean> getIncludePreReleases();

    /**
     * Configures the highest allowed update tier: patch, minor or major.
     *
     * @return update tier limit property
     */
    public abstract Property<String> getAllowedUpdates();

    /**
     * Coordinates whose current versions are pinned.
     *
     * @return pinned plugin IDs
     */
    public abstract ListProperty<String> getPinCurrentVersions();

    /**
     * Plugin IDs mapped to ignored versions.
     *
     * @return ignored versions keyed by plugin ID
     */
    public abstract MapProperty<String, List<String>> getIgnoredVersions();

    /**
     * Sets the section's default update policy.
     */
    @Inject
    public PluginSection() {
        getFailIfUpdatesFound().convention(false);
        getIncludePreReleases().convention(false);
        getAllowedUpdates().convention("major");

        getPinCurrentVersions().convention(new ArrayList<>());
        getIgnoredVersions().convention(new LinkedHashMap<>());
    }
}
