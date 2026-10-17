/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.updates;

import java.util.regex.Pattern;

/**
 * Dependency ordering delegates to Gradle; update tiers and calendar detection are separate policies.
 */
public final class Versions {
    /**
     * Keeps this utility class static-only.
     */
    private Versions() {
    }

    private static final Pattern PRE = Pattern.compile("(?i)(?:^|[.\\-_]|(?<=\\d))(?:alpha|beta|rc|cr|m|milestone|preview|snapshot|dev|ea|nightly)(?:[.\\-_]?\\d.*|$)");
    private static final Pattern DATE = Pattern.compile("^((?:19|2[0-9])[0-9]{2})([-._]?)([0-9]{2})\\2([0-9]{2})$");

    /**
     * Detects common prerelease tokens without treating arbitrary qualifiers as unstable.
     *
     * @param v version value or constraint
     * @return true when the version contains a recognized prerelease token
     */
    public static boolean preRelease(String v) {
        return PRE.matcher(v).find();
    }

    /**
     * True only for complete, valid dates, not arbitrary long numeric versions.
     *
     * @param version version or constraint being inspected
     * @return true for a complete, valid calendar date version
     */
    public static boolean dateVersion(String version) {
        var match = DATE.matcher(version);
        if (!match.matches()) return false;
        try {
            java.time.LocalDate.of(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(3)), Integer.parseInt(match.group(4)));
            return true;
        } catch (java.time.DateTimeException invalidDate) {
            return false;
        }
    }

    /**
     * Numeric update levels plus an unknown bucket for versions that do not fit.
     */
    public enum Tier {PATCH, MINOR, MAJOR, UNKNOWN}

    /**
     * Classifies a candidate as patch, minor, major or unknown compatibility.
     *
     * @param current   current resolved or declared version
     * @param candidate candidate version to compare
     * @return candidate's numeric update tier, or UNKNOWN for calendar versions
     */
    public static Tier classify(String current, String candidate) {
        if (unknownCompatibility(current, candidate)) return Tier.UNKNOWN;
        if (!same(current, candidate, 1)) return Tier.MAJOR;
        return same(current, candidate, 2) ? Tier.PATCH : Tier.MINOR;
    }

    /**
     * Treats calendar-date versions as having unknown numeric compatibility.
     *
     * @param current   current resolved or declared version
     * @param candidate candidate version to compare
     * @return true when either version is a calendar date
     */
    public static boolean unknownCompatibility(String current, String candidate) {
        return dateVersion(current) || dateVersion(candidate);
    }

    /**
     * Asks Gradle whether two versions share the requested number of numeric parts.
     *
     * @param a     left version
     * @param b     right version
     * @param parts number of leading numeric parts to compare
     * @return true when the requested numeric prefix matches
     */
    public static boolean same(String a, String b, int parts) {
        return com.sneedit.deplens.integrations.gradle.GradleVersionAdapter.sameNumericPrefix(a, b, parts);
    }

    /**
     * Orders dependency versions using the running Gradle's version comparator.
     *
     * @param a left version
     * @param b right version
     * @return a negative number, zero or a positive number when a sorts before, with or after b
     */
    public static int compare(String a, String b) {
        return com.sneedit.deplens.integrations.gradle.GradleVersionAdapter.compare(a, b);
    }
}
