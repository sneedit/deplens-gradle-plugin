/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.updates;

import com.sneedit.deplens.resolution.AnalysisModel;
import com.sneedit.deplens.resolution.NativeResolverFactory;
import com.sneedit.deplens.resolution.ResolutionContext;

import org.jspecify.annotations.Nullable;

/**
 * Lazily owns the project, buildscript and settings resolvers for one update engine.
 */
final class UpdateResolutionContexts {
    private final AnalysisModel model;
    private final NativeResolverFactory services;
    private @Nullable ResolutionContext projectContext;
    private @Nullable ResolutionContext scriptContext;
    private @Nullable ResolutionContext settingsContext;

    UpdateResolutionContexts(AnalysisModel model, NativeResolverFactory services) {
        this.model = model;
        this.services = services;
    }

    /**
     * Lazily rebuilds the project's repository and configuration sandbox.
     */
    ResolutionContext projectContext() {
        if (projectContext == null)
            projectContext = new ResolutionContext(services, model.repositories, model.configurations, model.schema);
        return projectContext;
    }

    /**
     * Lazily rebuilds the buildscript classpath sandbox.
     */
    ResolutionContext scriptContext() {
        if (scriptContext == null)
            scriptContext = new ResolutionContext(services, model.scriptRepositories, model.scriptConfigurations, model.scriptSchema);
        return scriptContext;
    }

    /**
     * Lazily rebuilds the settings plugin resolution sandbox.
     */
    ResolutionContext settingsContext() {
        if (settingsContext == null)
            settingsContext = new ResolutionContext(services, model.settingsRepositories, model.settingsConfigurations, model.scriptSchema);
        return settingsContext;
    }

}
