/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.integrations.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionComparator;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.Version;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.Objects;

/**
 * Isolates Gradle's internal dependency-version API; never substitutes a different ordering.
 */
public final class GradleVersionAdapter {
    /**
     * Keeps this utility class static-only.
     */
    private GradleVersionAdapter() {
    }

    /**
     * Gradle version parsing and comparison services loaded from the running Gradle.
     */
    private static final class Engine {
        static final VersionParser PARSER = new VersionParser();
        static final Comparator<Version> COMPARATOR = new DefaultVersionComparator().asVersionComparator();
    }

    /**
     * Compares versions through Gradle's own version parser and comparator.
     *
     * @param left  left version
     * @param right right version
     * @return a negative number, zero or a positive number according to Gradle's version ordering
     */
    public static int compare(String left, String right) {
        try {
            return Engine.COMPARATOR.compare(Engine.PARSER.transform(left), Engine.PARSER.transform(right));
        } catch (LinkageError failure) {
            throw unsupported(failure);
        }
    }

    /**
     * Compares the requested number of leading numeric version parts through Gradle.
     *
     * @param left  left version
     * @param right right version
     * @param parts number of leading numeric parts to compare
     * @return true when the requested numeric prefix matches
     */
    public static boolean sameNumericPrefix(String left, String right, int parts) {
        try {
            @Nullable Long[] a = Engine.PARSER.transform(left).getNumericParts();
            @Nullable Long[] b = Engine.PARSER.transform(right).getNumericParts();
            if (a.length < parts || b.length < parts) return false;
            for (int i = 0; i < parts; i++)
                if (a[i] == null || b[i] == null || !Objects.equals(a[i], b[i])) return false;
            return true;
        } catch (LinkageError failure) {
            throw unsupported(failure);
        }
    }

    /**
     * Builds a clear failure when the running Gradle's version API is unsupported.
     */
    private static GradleException unsupported(LinkageError failure) {
        return new GradleException("DepLens cannot use this Gradle runtime's dependency-version comparator.", failure);
    }
}
