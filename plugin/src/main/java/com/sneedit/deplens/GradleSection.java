/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens;

import org.gradle.api.provider.Property;

import javax.inject.Inject;

/**
 * Gradle DSL settings for wrapper updates.
 */
public abstract class GradleSection {
    /**
     * Configures the Gradle release feed URL; local file URLs support repeatable checks.
     *
     * @return release feed URL property
     */
    public abstract Property<String> getVersionsUrl();

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
     * Configures whether the current wrapper version is pinned.
     *
     * @return wrapper pin property
     */
    public abstract Property<Boolean> getPinCurrentVersion();

    /**
     * Sets the section's default update policy.
     */
    @Inject
    public GradleSection() {
        getVersionsUrl().convention("https://services.gradle.org/versions/all");
        getFailIfUpdatesFound().convention(false);
        getIncludePreReleases().convention(false);
        getAllowedUpdates().convention("major");
        getPinCurrentVersion().convention(false);
    }
}
