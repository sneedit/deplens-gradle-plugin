/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.functional;

import java.math.BigInteger;
import java.util.regex.Pattern;

/** Version handling for fixtures that follow the actual integration plugin versions. */
final class IntegrationTestVersions {
    private static final Pattern VERSION = Pattern.compile("^(\\d+\\.\\d+\\.)(\\d+)(?:[-+].+)?$");
    private static final Pattern SPRING_BOOT_GRADLE_REJECTION = Pattern.compile(
            "(?m)^Caused by: org\\.gradle\\.api\\.GradleException: "
                    + "(Spring Boot plugin requires Gradle [^\\r\\n]+\\. The current version is [^\\r\\n]+)$");

    private IntegrationTestVersions() {
    }

    /** Produces a stable newer fixture version, including from milestone and snapshot versions. */
    static String nextPatch(String current) {
        var match = VERSION.matcher(current);
        if (!match.matches()) throw new IllegalArgumentException("Expected a Kotlin version X.Y.Z with optional qualifier: " + current);
        return match.group(1) + new BigInteger(match.group(2)).add(BigInteger.ONE);
    }

    /** Recognizes Spring Boot's own compatibility rejection without hiding other build failures. */
    static String springBootGradleRejection(String output) {
        var match = SPRING_BOOT_GRADLE_REJECTION.matcher(output);
        return match.find() ? match.group(1) : null;
    }
}
