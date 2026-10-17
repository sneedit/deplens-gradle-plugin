/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.updates;

import org.jspecify.annotations.Nullable;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Explicit extraction policies; ordering is always delegated to Gradle.
 */
final class VersionPatterns {
    /**
     * Keeps this utility class static-only.
     */
    private VersionPatterns() {
    }

    /**
     * Compiles a custom detector and checks that version patterns expose numeric groups.
     */
    static Pattern compile(String field, String expression) {
        try {
            Pattern pattern = Pattern.compile(expression);
            if (field.equals("versionPattern") && !pattern.namedGroups().keySet().containsAll(Set.of("major", "minor", "patch")))
                throw new IllegalArgumentException("versionPattern requires named groups major, minor and patch");
            return pattern;
        } catch (PatternSyntaxException failure) {
            throw new IllegalArgumentException("Invalid " + field + ": " + failure.getDescription(), failure);
        }
    }

    /**
     * Compares captured numeric parts; unmatched versions get unknown compatibility.
     */
    static Versions.Tier classify(Pattern pattern, String current, String candidate) {
        List<BigInteger> before = parts(pattern, current), after = parts(pattern, candidate);
        if (before == null || after == null) return Versions.Tier.UNKNOWN;
        if (!before.get(0).equals(after.get(0))) return Versions.Tier.MAJOR;
        if (!before.get(1).equals(after.get(1))) return Versions.Tier.MINOR;
        return Versions.Tier.PATCH;
    }

    /**
     * Extracts numeric capture groups, or returns null when the version does not fit the pattern.
     */
    private static @Nullable List<BigInteger> parts(Pattern pattern, String version) {
        var match = pattern.matcher(version);
        if (!match.matches()) return null;
        var values = new java.util.ArrayList<BigInteger>();
        for (String field : List.of("major", "minor", "patch")) {
            String value = match.group(field);
            if (value == null || !value.matches("[0-9]+")) return null;
            values.add(new BigInteger(value));
        }
        return values;
    }
}
