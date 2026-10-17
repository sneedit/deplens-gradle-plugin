/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.updates;

import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionComparator;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests versions.
 */
class VersionsTest {
    /**
     * Verifies that comparison matches the running Gradle for qualifiers separators and numeric parts.
     */
    @Test
    void comparisonMatchesTheRunningGradleForQualifiersSeparatorsAndNumericParts() {
        var parser = new VersionParser();
        var comparator = new DefaultVersionComparator().asVersionComparator();
        var versions = List.of("1.0", "1.0.0", "1.0-rc1", "1.0-RC1", "1.0-final", "1.0-GA",
                "1.0-release", "1.0-sp1", "1.0-dev", "1.01", "1_2", "1+2", "1.2a1",
                "7.5.0.8588", "7.5.0.9000", "7.5.1.9010", "7.6.0.9100", "8.0.0.9200", "20260814", "20260901", "100000000000000000000.1");
        for (String left : versions)
            for (String right : versions)
                assertThat(Integer.signum(Versions.compare(left, right))).as("%s versus %s", left, right)
                        .isEqualTo(Integer.signum(comparator.compare(parser.transform(left), parser.transform(right))));
        assertThat(Versions.compare("7.5.0.9000", "7.5.0.8588")).isPositive();
        assertThat(Versions.compare("20260901", "20260814")).isPositive();
    }

    /**
     * Verifies that SonarQube keeps its numeric update tiers including fourth part changes.
     */
    @Test
    void sonarQubeKeepsItsNumericUpdateTiersIncludingFourthPartChanges() {
        for (String candidate : List.of("7.5.0.9000", "7.5.1.9010")) {
            assertThat(Versions.same("7.5.0.8588", candidate, 2)).isTrue();
            assertThat(Versions.unknownCompatibility("7.5.0.8588", candidate)).isFalse();
        }
        assertThat(Versions.same("7.5.0.8588", "7.6.0.9100", 1)).isTrue();
        assertThat(Versions.same("7.5.0.8588", "7.6.0.9100", 2)).isFalse();
        assertThat(Versions.same("7.5.0.8588", "8.0.0.9200", 1)).isFalse();
        assertThat(Versions.same("1.01-jre", "1_1_4", 2)).isTrue();
        assertThat(Versions.same("1+2", "1.2.3", 2)).isTrue();
    }

    /**
     * Verifies that DepLens recognizes prerelease tokens without treating ordinary qualifiers as milestones.
     */
    @Test
    void recognizesPrereleaseTokensWithoutTreatingOrdinaryQualifiersAsMilestones() {
        for (String v : List.of("1.0-RC1", "1.0rc1", "1.0-M2", "1.0-beta", "1.0-SNAPSHOT", "1.0-preview-2"))
            assertThat(Versions.preRelease(v)).as(v).isTrue();
        for (String v : List.of("1.0", "1.0.Final", "1.0-jre", "1.0-memory", "1.0-sp1"))
            assertThat(Versions.preRelease(v)).as(v).isFalse();
    }

    /**
     * Verifies that calendar dates have unknown compatibility and must be valid complete dates.
     */
    @Test
    void calendarDatesHaveUnknownCompatibilityAndMustBeValidCompleteDates() {
        for (String version : List.of("20260814", "2026-08-14", "2026.08.14", "2026_08_14", "20240229"))
            assertThat(Versions.dateVersion(version)).as(version).isTrue();
        for (String version : List.of("20260229", "20261301", "20260832", "2026-08.14", "202608", "7.5.0.8588", "12345678"))
            assertThat(Versions.dateVersion(version)).as(version).isFalse();
        assertThat(Versions.unknownCompatibility("20260814", "20260901")).isTrue();
        assertThat(Versions.unknownCompatibility("1.0.0", "20260901")).isTrue();
    }
}
