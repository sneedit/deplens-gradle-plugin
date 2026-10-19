/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */
package com.sneedit.deplens.resolution;

import org.gradle.api.internal.project.ProjectState;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Original resolution rules retained only for the current build invocation.
 */
public abstract class ResolutionRuleService implements BuildService<BuildServiceParameters.None>, AutoCloseable {
    /** Creates an invocation-local registry for stateful resolution rules. */
    public ResolutionRuleService() {
    }

    private record Entry(Object rule, @Nullable ProjectState owner) {
    }

    private final Map<String, Entry> rules = new ConcurrentHashMap<>();

    String register(Object rule, @Nullable ProjectState owner) {
        String key = UUID.randomUUID().toString();
        rules.put(key, new Entry(rule, owner));
        return key;
    }

    <T> T withRule(String key, Function<Object, T> operation) {
        var entry = rules.get(key);
        if (entry == null)
            throw new IllegalStateException("Original resolution rule is unavailable. Run DepLens with --no-configuration-cache.");
        var owner = entry.owner();
        return owner == null ? operation.apply(entry.rule())
                : owner.fromMutableState(project -> operation.apply(entry.rule()));
    }

    @Override
    public void close() {
        rules.clear();
    }
}
