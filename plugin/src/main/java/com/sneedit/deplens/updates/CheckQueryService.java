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
    private final VersionQueryCache queries = new VersionQueryCache();

    public VersionQueryCache queries() {
        return queries;
    }
}
