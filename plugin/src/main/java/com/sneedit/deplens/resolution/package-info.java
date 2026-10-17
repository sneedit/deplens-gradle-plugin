/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Captures Gradle resolution settings as detached values and rebuilds them for cache-safe queries.
 * GradleModelProvider adapts Gradle 8 model requests to the calling thread to avoid lifecycle-lock deadlocks.
 */
@NullMarked
package com.sneedit.deplens.resolution;

import org.jspecify.annotations.NullMarked;
