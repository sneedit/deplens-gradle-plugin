/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */
package com.sneedit.deplens.updates;

import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ResolvableDependencies;
import org.gradle.api.artifacts.result.ResolutionResult;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;
import org.gradle.api.internal.artifacts.configurations.ResolutionStrategyInternal;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.internal.resolve.ModuleVersionResolveException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ModuleVersionQueriesTest {
    /** A conflict in the original graph must not be hidden by candidate compatibility fallback. */
    @Test
    void rejectedCandidateDoesNotHideAnUnresolvableInstalledGraph() {
        var template = mock(Configuration.class);
        var baseline = mock(Configuration.class);
        when(template.getResolutionStrategy()).thenReturn(mock(ResolutionStrategyInternal.class));
        when(baseline.getResolutionStrategy()).thenReturn(mock(ResolutionStrategyInternal.class));
        when(template.copyRecursive()).thenReturn(baseline);
        var candidateFailure = rejected("2.0");
        var baselineFailure = rejected("1.0");
        graph(template, candidateFailure);
        graph(baseline, baselineFailure);
        var failures = new ArrayList<UnresolvedDependencyResult>();

        assertThat(ModuleVersionQueries.resolveAllowed(template, "org.test:plugin", List.of("2.0", "1.0"),
                failures::add, false, "1.0")).isNull();
        assertThat(failures).containsExactly(baselineFailure, candidateFailure);
        verify(template, times(1)).copyRecursive();
    }

    /** Legacy Gradle rejection headers are categorized without exposing raw exception messages. */
    @Test
    void rejectionDiagnosticsRemainSanitizedAndDoNotConcealTransportFailures() {
        var failure = rejected("2.0").getFailure();
        var detail = ResolutionFailureDetails.describe("classpath", "org.test:annotations", failure);
        assertThat(detail.reason()).isEqualTo("version rejected by constraints or selection rules");
        assertThat(detail.reason()).doesNotContain("secret", "password", "https://");
        failure.addSuppressed(new java.io.IOException("https://secret:password@example.test"));
        assertThat(ModuleVersionQueries.versionRejected(failure)).isFalse();
        assertThat(ResolutionFailureDetails.describe("classpath", "org.test:annotations", failure).reason())
                .doesNotContain("secret", "password", "https://");
    }

    private static UnresolvedDependencyResult rejected(String version) {
        var selector = mock(ModuleComponentSelector.class);
        when(selector.getGroup()).thenReturn("org.test");
        when(selector.getModule()).thenReturn("annotations");
        when(selector.getVersion()).thenReturn(version);
        when(selector.getDisplayName()).thenReturn("org.test:annotations:" + version);
        var failure = new ModuleVersionResolveException(selector, new org.gradle.api.GradleException(
                "Cannot find a version of 'org.test:annotations' that satisfies the version constraints:\nhttps://secret:password@example.test"));
        var edge = mock(UnresolvedDependencyResult.class);
        when(edge.getAttempted()).thenReturn(selector);
        when(edge.getFailure()).thenReturn(failure);
        return edge;
    }

    private static void graph(Configuration configuration, UnresolvedDependencyResult failure) {
        var incoming = mock(ResolvableDependencies.class);
        var graph = mock(ResolutionResult.class);
        when(configuration.getIncoming()).thenReturn(incoming);
        when(incoming.getResolutionResult()).thenReturn(graph);
        doReturn(Set.of(failure)).when(graph).getAllDependencies();
    }
}
