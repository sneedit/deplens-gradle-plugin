/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.updates;

import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * Transforms comparison keys only; original versions remain repository selectors and report values.
 *
 * @param pattern regex applied to each version before comparison
 * @param replacement Java regex replacement used to produce comparison keys
 */
public record VersionNormalization(Pattern pattern, String replacement) implements Comparator<String> {
    /**
     * Validates Java replacement references before any repository queries run.
     *
     * @param expression normalization regex
     * @param replacement Java regex replacement, including any group references
     * @return validated normalization rule
     */
    public static VersionNormalization compile(String expression, String replacement) {
        Pattern pattern = VersionPatterns.compile("versionNormalizationPattern", expression);
        for (int i = 0; i < replacement.length(); i++) {
            char c = replacement.charAt(i);
            if (c == '\\') {
                if (++i >= replacement.length())
                    throw new IllegalArgumentException("Incomplete escape in versionNormalizationReplacement");
            } else if (c == '$') {
                if (++i >= replacement.length())
                    throw new IllegalArgumentException("Incomplete group reference in versionNormalizationReplacement");
                if (replacement.charAt(i) == '{') {
                    int end = replacement.indexOf('}', i + 1);
                    if (end < 0 || !pattern.namedGroups().containsKey(replacement.substring(i + 1, end)))
                        throw new IllegalArgumentException("Unknown named group in versionNormalizationReplacement");
                    i = end;
                } else if (replacement.charAt(i) >= '0' && replacement.charAt(i) <= '9') {
                    int group = replacement.charAt(i) - '0';
                    if (group > pattern.matcher("").groupCount())
                        throw new IllegalArgumentException("Unknown numeric group in versionNormalizationReplacement");
                    // Java consumes additional digits only while they form an existing group number.
                    while (i + 1 < replacement.length() && Character.isDigit(replacement.charAt(i + 1))
                            && (long) group * 10 + replacement.charAt(i + 1) - '0' <= pattern.matcher("").groupCount())
                        group = group * 10 + replacement.charAt(++i) - '0';
                } else throw new IllegalArgumentException("Invalid group reference in versionNormalizationReplacement");
            }
        }
        return new VersionNormalization(pattern, replacement);
    }

    /**
     * Transforms a version into a nonblank comparison key.
     *
     * @param version original repository version string
     * @return normalized comparison key
     * @throws IllegalArgumentException if normalization produces a blank key
     */
    public String normalize(String version) {
        String result = pattern.matcher(version).replaceAll(replacement);
        if (result.isBlank())
            throw new IllegalArgumentException("Version normalization produced an empty comparison key");
        return result;
    }

    @Override
    public int compare(String left, String right) {
        return Versions.compare(normalize(left), normalize(right));
    }

    /**
     * Restores the comparator from additive report fields; older reports retain Gradle ordering.
     *
     * @param expression normalization regex, or null for Gradle ordering
     * @param replacement normalization replacement, or null for Gradle ordering
     * @return configured normalization comparator, or the default Gradle comparator
     */
    public static Comparator<String> comparator(@Nullable String expression, @Nullable String replacement) {
        return expression == null || replacement == null ? Versions::compare : compile(expression, replacement);
    }
}
