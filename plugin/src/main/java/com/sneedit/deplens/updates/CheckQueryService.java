/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */
package com.sneedit.deplens.updates;

import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

/**
 * Invocation-local query reuse and repository cooldown shared by project check tasks.
 */
public abstract class CheckQueryService implements BuildService<BuildServiceParameters.None> {
    /** Creates an invocation-local query cache shared by update-check tasks. */
    public CheckQueryService() {
    }

    private final VersionQueryCache queries = new VersionQueryCache();

    /**
     * Provides the query cache shared by tasks in this Gradle invocation.
     *
     * @return invocation-local version listing and graph query cache
     */
    public VersionQueryCache queries() {
        return queries;
    }
}
