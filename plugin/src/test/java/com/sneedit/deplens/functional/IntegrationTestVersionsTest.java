/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.functional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class IntegrationTestVersionsTest {
    @ParameterizedTest
    @CsvSource({"2.4.21, 2.4.22", "2.5.0-RC1, 2.5.1", "2.5.0-Beta2, 2.5.1",
            "2.5.0-M1, 2.5.1", "2.5.0-SNAPSHOT, 2.5.1", "2.5.0+build.123, 2.5.1",
            "2.5.0-RC1+build.123, 2.5.1"})
    void newerFixtureVersionAcceptsReleaseQualifiers(String current, String expected) {
        assertThat(IntegrationTestVersions.nextPatch(current)).isEqualTo(expected);
    }

    @Test
    void malformedVersionFailsClearly() {
        assertThatIllegalArgumentException().isThrownBy(() -> IntegrationTestVersions.nextPatch("invalid"))
                .withMessageContaining("Expected a Kotlin version");
    }

    @ParameterizedTest
    @CsvSource({"8.x (8.14 or later) or 9.x, 8.8", "9.x (9.10 or later) or 10.x, 9.8.1"})
    void gradleCompatibilityComesFromSpringBootRejection(String requirement, String current) {
        String reason = "Spring Boot plugin requires Gradle " + requirement + ". The current version is " + current;
        assertThat(IntegrationTestVersions.springBootGradleRejection(
                "Build failed\nCaused by: org.gradle.api.GradleException: " + reason + "\n\tat example.Frame"))
                .isEqualTo(reason);
    }

    @Test
    void unrelatedFailuresAreNotCompatibilitySkips() {
        for (String output : new String[]{"Could not resolve Spring Boot", "Unsupported class file major version",
                "Caused by: org.gradle.api.GradleException: Spring Boot plugin requires Java 25",
                "Task output: Spring Boot plugin requires Gradle 99. The current version is 9.8"}) {
            assertThat(IntegrationTestVersions.springBootGradleRejection(output)).isNull();
        }
    }
}
