/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Coordinates update checks without teaching DepLens a second dependency resolver.
 * Start at {@link com.sneedit.deplens.updates.UpdateEngine}: it delegates module checks
 * to ModuleUpdateChecker and wrapper checks to GradleReleaseChecker. UpdateResolutionContexts
 * owns lazy resolver scopes; BuildContextResolver prepares candidate graphs and
 * VersionListingPrefetcher groups independent listings. UpdateResults
 * turns their answers into report flags and recommendations. Gradle does the graph work.
 */
@NullMarked
package com.sneedit.deplens.updates;

import org.jspecify.annotations.NullMarked;
