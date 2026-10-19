/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */
package com.sneedit.deplens;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;

import javax.inject.Inject;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Serializable name-based selection of configurations; exclusions take precedence.
 */
public abstract class ConfigurationFilter {
    /**
     * Full-name Java regular expressions; an empty list accepts every name.
     *
     * @return include regexes; an empty list includes every configuration
     */
    @Input
    public abstract ListProperty<String> getIncludes();

    /**
     * Full-name Java regular expressions rejected even when an include matches.
     *
     * @return exclude regexes, which take precedence over include patterns
     */
    @Input
    public abstract ListProperty<String> getExcludes();

    /**
     * Creates a filter with no inclusion or exclusion restrictions.
     */
    @Inject
    public ConfigurationFilter() {
        getIncludes().convention(List.of());
        getExcludes().convention(List.of());
    }

    /**
     * Compiles detached values, without retaining Gradle objects or build-script closures.
     *
     * @param field DSL field name used in invalid-pattern diagnostics
     * @param includes include regexes; an empty list accepts every configuration
     * @param excludes exclude regexes applied after inclusion
     * @return predicate accepting included names that match no exclusion
     */
    public static Predicate<String> predicate(String field, List<String> includes, List<String> excludes) {
        List<Pattern> accepted = compile(field + ".includes", includes);
        List<Pattern> rejected = compile(field + ".excludes", excludes);
        return name -> (accepted.isEmpty() || accepted.stream().anyMatch(p -> p.matcher(name).matches()))
                && rejected.stream().noneMatch(p -> p.matcher(name).matches());
    }

    private static List<Pattern> compile(String field, List<String> expressions) {
        return expressions.stream().map(expression -> {
            try {
                return Pattern.compile(expression);
            } catch (PatternSyntaxException failure) {
                throw new IllegalArgumentException("Invalid " + field + " regex: " + expression, failure);
            }
        }).toList();
    }
}
