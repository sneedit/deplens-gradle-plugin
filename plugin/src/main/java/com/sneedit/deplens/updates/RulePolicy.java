/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.updates;

import com.sneedit.deplens.DepLensExtension;
import com.sneedit.deplens.inventory.InventoryEntry;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Precedence is applied per field, so a specific ignore does not discard a global pin.
 */
public final class RulePolicy {
    /** Creates an unpinned policy accepting stable updates of any tier. */
    public RulePolicy() {
    }

    /**
     * Whether the current version is protected from update recommendations.
     */
    public boolean pinned;
    /**
     * Whether prerelease candidates are accepted.
     */
    public boolean preReleases;
    /**
     * Highest allowed update tier: patch, minor or major.
     */
    public String allowed = "major";
    /**
     * Exact original version strings excluded by this policy.
     */
    public List<String> ignored = List.of();
    /**
     * Patterns excluding matching original version strings.
     */
    public List<Pattern> ignoredVersionPatterns = List.of();
    /**
     * Custom numeric version parser, or null for the default classifier.
     */
    public @Nullable Pattern versionPattern;
    /**
     * Custom prerelease detector, or null for the default token matcher.
     */
    public @Nullable Pattern preReleasePattern;
    /**
     * Comparison-key normalization, or null for Gradle version ordering.
     */
    public @Nullable VersionNormalization normalization;

    /**
     * Excludes exact versions or matching original version strings, even when prereleases are allowed.
     *
     * @param version original repository version string
     * @return true when an exact exclusion or exclusion pattern matches
     */
    public boolean isIgnored(String version) {
        return ignored.contains(version) || ignoredVersionPatterns.stream().anyMatch(pattern -> pattern.matcher(version).find());
    }

    /**
     * Compares normalized keys when configured, otherwise delegates directly to Gradle.
     *
     * @param left first original version string
     * @param right second original version string
     * @return a negative value, zero or a positive value according to version ordering
     */
    public int compare(String left, String right) {
        return normalization == null ? Versions.compare(left, right) : normalization.compare(left, right);
    }

    /**
     * Detects prereleases using the custom pattern when present, otherwise the default token matcher.
     *
     * @param version version or constraint being inspected
     * @return true when the effective detector recognizes a prerelease
     */
    public boolean preRelease(String version) {
        return preReleasePattern == null ? Versions.preRelease(version) : preReleasePattern.matcher(version).find();
    }

    /**
     * Assigns an update tier using the custom version pattern when present.
     *
     * @param current   current resolved or declared version
     * @param candidate candidate version to compare
     * @return candidate's update tier, or UNKNOWN when compatibility cannot be classified
     */
    public Versions.Tier classify(String current, String candidate) {
        return versionPattern == null ? Versions.classify(normalization == null ? current : normalization.normalize(current),
                normalization == null ? candidate : normalization.normalize(candidate)) : VersionPatterns.classify(versionPattern, current, candidate);
    }

    /**
     * Checks whether a candidate tier fits the configured update limit.
     *
     * @param tier candidate update tier
     * @return true when the policy permits this update tier
     */
    public boolean allows(Versions.Tier tier) {
        return switch (allowed) {
            case "patch" -> tier == Versions.Tier.PATCH;
            case "minor" -> tier == Versions.Tier.PATCH || tier == Versions.Tier.MINOR;
            default -> true;
        };
    }

    /**
     * Human-readable description of the effective scoped rule.
     */
    public String description = "default";

    /**
     * Combines section defaults and matching rules, choosing precedence separately for each field.
     *
     * @param e     component being inspected or configured
     * @param ext   configured DepLens extension
     * @param specs detached scoped rules
     * @return effective policy after per-field rule precedence
     */
    public static RulePolicy resolve(InventoryEntry e, @Nullable DepLensExtension ext, List<UpdateRules.Spec> specs) {
        RulePolicy p = new RulePolicy();
        if (ext != null) {
            if (e.type == InventoryEntry.Type.GRADLE) {
                p.pinned = ext.getGradle().getPinCurrentVersion().get();
                p.preReleases = ext.getGradle().getIncludePreReleases().get();
                p.allowed = UpdateRules.validateAllowedUpdates(ext.getGradle().getAllowedUpdates().get());
            } else if (e.type == InventoryEntry.Type.PLUGIN) {
                p.pinned = ext.getPlugins().getPinCurrentVersions().get().contains(e.coordinate);
                p.preReleases = ext.getPlugins().getIncludePreReleases().get();
                p.allowed = UpdateRules.validateAllowedUpdates(ext.getPlugins().getAllowedUpdates().get());
                p.ignored = ext.getPlugins().getIgnoredVersions().get().getOrDefault(e.coordinate, List.of());
            } else {
                p.pinned = ext.getDependencies().getPinCurrentVersions().get().contains(e.coordinate);
                p.preReleases = ext.getDependencies().getIncludePreReleases().get();
                p.allowed = UpdateRules.validateAllowedUpdates(ext.getDependencies().getAllowedUpdates().get());
                p.ignored = ext.getDependencies().getIgnoredVersions().get().getOrDefault(e.coordinate, List.of());
            }
            p.description = "section defaults";
        }
        specs.stream().filter(s -> matches(s, e)).sorted(Comparator.comparingInt(s -> priority(s.scope()))).forEach(s -> {
            if (s.pinned() != null) p.pinned = s.pinned();
            if (s.preReleases() != null) p.preReleases = s.preReleases();
            if (s.allowedUpdates() != null) p.allowed = s.allowedUpdates();
            if (s.ignored() != null) p.ignored = s.ignored();
            if (s.ignoredVersionPatterns() != null)
                p.ignoredVersionPatterns = s.ignoredVersionPatterns().stream()
                        .map(expression -> VersionPatterns.compile("ignoredVersionPatterns", expression)).toList();
            if (s.versionPattern() != null)
                p.versionPattern = VersionPatterns.compile("versionPattern", s.versionPattern());
            if (s.preReleasePattern() != null)
                p.preReleasePattern = VersionPatterns.compile("preReleasePattern", s.preReleasePattern());
            if (s.versionNormalizationPattern() != null && s.versionNormalizationReplacement() != null)
                p.normalization = VersionNormalization.compile(s.versionNormalizationPattern(), s.versionNormalizationReplacement());
            p.description += " > " + s.scope() + ":" + s.key();
        });
        return p;
    }

    /**
     * Returns the precedence rank of a rule scope.
     */
    private static int priority(String scope) {
        return switch (scope) {
            case "global" -> 0;
            case "project" -> 1;
            case "group" -> 2;
            default -> 3;
        };
    }

    /**
     * Checks whether a scoped rule applies to this inventory entry.
     */
    private static boolean matches(UpdateRules.Spec s, InventoryEntry e) {
        return switch (s.scope()) {
            case "global" -> true;
            case "project" -> s.key().equals(e.project);
            case "group" ->
                    e.type != InventoryEntry.Type.GRADLE && e.type != InventoryEntry.Type.PLUGIN && e.coordinate.startsWith(s.key() + ":");
            case "dependency" ->
                    (e.type == InventoryEntry.Type.DEPENDENCY || e.type == InventoryEntry.Type.CONSTRAINT) && s.key().equals(e.coordinate);
            case "platform" -> e.type == InventoryEntry.Type.PLATFORM && s.key().equals(e.coordinate);
            case "plugin" -> e.type == InventoryEntry.Type.PLUGIN && s.key().equals(e.coordinate);
            case "gradle" -> e.type == InventoryEntry.Type.GRADLE;
            default -> false;
        };
    }
}
