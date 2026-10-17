/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.inventory;

import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * A component in one project and build; declarations and resolutions are deliberately separate.
 */
public final class InventoryEntry {
    /**
     * The component families that an inventory can track.
     */
    public enum Type {DEPENDENCY, PLATFORM, CONSTRAINT, PLUGIN, GRADLE}

    /**
     * Report flags describing resolution, policy decisions and update availability.
     */
    public enum Status {UP_TO_DATE, UPDATE_AVAILABLE, NO_MATCHING_CANDIDATES, PINNED, IGNORED, CONSTRAINED, NOT_CHECKED, BOM_MANAGED, VERSION_ALIGNED, FAILED}

    /**
     * The origin of a version value, including generated and unknown sources.
     */
    public enum SourceKind {BUILD_SCRIPT, SETTINGS, CATALOG, PROPERTY, VARIABLE, PLATFORM, GENERATED, WRAPPER, UNKNOWN}

    /**
     * A version value and its file location; synthetic sources use descriptive file labels.
     *
     * @param file  source path or descriptive synthetic origin
     * @param line  one-based source line, or zero for a synthetic origin
     * @param kind  kind of version source
     * @param key   property, variable or catalog key, when available
     * @param value recorded version value, when available
     */
    public record Source(String file, int line, SourceKind kind, @Nullable String key, @Nullable String value) {
    }

    /**
     * A declaration site kept separate from the version Gradle actually resolved.
     *
     * @param file          build or settings script path
     * @param line          one-based source line
     * @param configuration configuration containing the declaration
     * @param selector      declared version selector, when available
     */
    public record Declaration(String file, int line, String configuration, @Nullable String selector) {
    }

    /**
     * A selected version in one configuration, with safe Gradle selection cause codes.
     *
     * @param configuration configuration in which Gradle selected this version
     * @param version       selected module version
     * @param reasons       safe Gradle selection cause codes
     */
    public record Resolution(String configuration, String version, List<String> reasons) {
        /**
         * BOM supplied by a management integration rather than a dependency graph edge.
         */
        public static final String IMPORTED_BOM = "IMPORTED_BOM";
    }

    /**
     * A catalog alias, optional shared version key and bundle membership.
     *
     * @param catalog    version catalog name
     * @param alias      library or plugin alias
     * @param versionKey shared catalog version key, or null for inline versions
     * @param bundles    catalog bundles containing this alias
     */
    public record CatalogReference(String catalog, String alias, @Nullable String versionKey, List<String> bundles) {
    }

    /**
     * The rich version constraint as declared in one configuration.
     *
     * @param configuration configuration owning the constraint
     * @param required      required version selector
     * @param strictly      strict version selector
     * @param preferred     preferred version selector
     * @param rejected      versions excluded by the rich constraint
     */
    public record VersionConstraintInfo(String configuration, String required, String strictly, String preferred,
                                        List<String> rejected) {
    }

    public final String build;
    public final String project;
    public final Type type;
    public final String coordinate;
    public final Set<String> declaredSelectors = new LinkedHashSet<>();
    public final Set<String> configurations = new TreeSet<>();
    public final Set<Declaration> declarations = new LinkedHashSet<>();
    public final Set<Source> versionSources = new LinkedHashSet<>();
    public final Set<Resolution> resolutions = new LinkedHashSet<>();
    public final Set<String> platforms = new TreeSet<>();
    /**
     * Effective BOM owners by resolution context, including integration contributions.
     */
    public final Map<String, Set<String>> bomOwners = new TreeMap<>();
    /**
     * Runtime component or plugin supplying the version in each configuration.
     */
    public final Map<String, String> versionAlignments = new TreeMap<>();
    public final Set<String> managementOverrides = new TreeSet<>();
    public final Set<CatalogReference> catalogs = new LinkedHashSet<>();
    public final Set<VersionConstraintInfo> versionConstraints = new LinkedHashSet<>();
    public final Set<String> pluginStates = new TreeSet<>();
    public final Set<Status> statuses = EnumSet.of(Status.NOT_CHECKED);
    public final Set<String> warnings = new LinkedHashSet<>();
    // Update checks fill these additive fields; inventory-only reports leave them empty.
    public final List<String> candidates = new ArrayList<>();
    /**
     * True when update discovery deliberately skipped full consumer-graph checks.
     */
    public boolean fastCheck;

    /**
     * The current version and update choices for one configuration and policy.
     *
     * @param configuration        resolution configuration name
     * @param currentVersion       current version in this configuration
     * @param latestWithinSelector latest selector match, or null
     * @param latestAvailable      latest policy-filtered version, or null
     * @param latestAllowedByBuild latest compatible version, or null
     * @param patch                latest patch candidate, or null
     * @param minor                latest minor candidate, or null
     * @param major                latest major candidate, or null
     * @param outsideSelector      newer versions requiring a selector edit
     * @param candidates           candidates allowed by the update tier policy
     * @param unknownCompatibility candidates with unknown compatibility; legacy null input becomes an empty list
     * @param versionPattern       custom numeric version pattern, or null
     * @param preReleasePattern    custom prerelease detector, or null
     * @param noMatchingCandidates successful query with no policy-accepted versions
     */
    public record UpdateContext(String configuration, String currentVersion, @Nullable String latestWithinSelector,
                                @Nullable String latestAvailable, @Nullable String latestAllowedByBuild,
                                @Nullable String patch, @Nullable String minor,
                                @Nullable String major, List<String> outsideSelector, List<String> candidates,
                                List<String> unknownCompatibility,
                                @Nullable String versionPattern, @Nullable String preReleasePattern,
                                boolean noMatchingCandidates) {
        // Older JSON reports may omit this additive list. Accept null on input,
        // but expose a non-null immutable list to every caller.

        /** Preserves source compatibility for callers constructing older report contexts. */
        public UpdateContext(String configuration, String currentVersion, @Nullable String latestWithinSelector,
                             @Nullable String latestAvailable, @Nullable String latestAllowedByBuild,
                             @Nullable String patch, @Nullable String minor, @Nullable String major,
                             List<String> outsideSelector, List<String> candidates,
                             @Nullable List<String> unknownCompatibility,
                             @Nullable String versionPattern, @Nullable String preReleasePattern) {
            this(configuration, currentVersion, latestWithinSelector, latestAvailable, latestAllowedByBuild,
                    patch, minor, major, outsideSelector, candidates, unknownCompatibility,
                    versionPattern, preReleasePattern, false);
        }

        /**
         * Stores a configuration's update result, normalizing missing legacy compatibility lists to an empty list.
         *
         * @param configuration        resolution configuration name
         * @param currentVersion       current version in this configuration
         * @param latestWithinSelector latest selector match, or null
         * @param latestAvailable      latest policy-filtered version, or null
         * @param latestAllowedByBuild latest compatible version, or null
         * @param patch                latest patch candidate, or null
         * @param minor                latest minor candidate, or null
         * @param major                latest major candidate, or null
         * @param outsideSelector      newer versions requiring a selector edit
         * @param candidates           candidates allowed by the update tier policy
         * @param unknownCompatibility candidates with unknown compatibility; legacy null input becomes an empty list
         * @param versionPattern       custom numeric version pattern, or null
         * @param preReleasePattern    custom prerelease detector, or null
         * @param noMatchingCandidates successful query with no versions accepted by the prerelease/ignore policy
         */
        public UpdateContext(String configuration, String currentVersion, @Nullable String latestWithinSelector,
                             @Nullable String latestAvailable, @Nullable String latestAllowedByBuild,
                             @Nullable String patch, @Nullable String minor, @Nullable String major,
                             List<String> outsideSelector, List<String> candidates,
                             @Nullable List<String> unknownCompatibility,
                             @Nullable String versionPattern, @Nullable String preReleasePattern, boolean noMatchingCandidates) {
            this.configuration = configuration;
            this.currentVersion = currentVersion;
            this.latestWithinSelector = latestWithinSelector;
            this.latestAvailable = latestAvailable;
            this.latestAllowedByBuild = latestAllowedByBuild;
            this.patch = patch;
            this.minor = minor;
            this.major = major;
            this.outsideSelector = outsideSelector;
            this.candidates = candidates;
            this.unknownCompatibility = unknownCompatibility == null ? List.of() : List.copyOf(unknownCompatibility);
            this.versionPattern = versionPattern;
            this.preReleasePattern = preReleasePattern;
            this.noMatchingCandidates = noMatchingCandidates;
        }
    }

    /** A discovered version is a recommendation only when the check completed successfully. */
    public boolean hasRecommendedUpdate() {
        return recommendedVersion != null && statuses.contains(Status.UPDATE_AVAILABLE)
                && !statuses.contains(Status.FAILED) && !statuses.contains(Status.NOT_CHECKED);
    }

    public final List<UpdateContext> updateContexts = new ArrayList<>();

    /**
     * A credential-safe explanation of an unresolved module in an update-check context.
     *
     * @param configuration original consumer configuration
     * @param dependency    module coordinate that could not be resolved
     * @param reason        structured failure category and supported attribute mismatches
     */
    public record ResolutionFailure(String configuration, String dependency, String reason) {
    }

    public final List<ResolutionFailure> resolutionFailures = new ArrayList<>();
    public @Nullable String appliedRule;
    public @Nullable String recommendedVersion;
    public @Nullable String selectedVersion;
    public @Nullable String versionNormalizationPattern;
    public @Nullable String versionNormalizationReplacement;

    /**
     * Restores the configured ordering for reports and interactive selection.
     */
    public java.util.Comparator<String> versionComparator() {
        return com.sneedit.deplens.updates.VersionNormalization.comparator(versionNormalizationPattern, versionNormalizationReplacement);
    }

    /**
     * Returns the check's latest patch/minor alternatives followed by its update target.
     * The interactive selector uses the same choices instead of every repository candidate.
     */
    public List<String> updateOptions() {
        String latest = updateContexts.stream().map(UpdateContext::latestAvailable).filter(Objects::nonNull)
                .max(versionComparator()).orElse(null);
        String target = recommendedVersion == null ? candidates.stream().max(versionComparator()).orElse(latest) : recommendedVersion;
        if (target == null) return List.of();
        var patches = new ArrayList<String>();
        var minors = new ArrayList<String>();
        for (UpdateContext context : updateContexts) {
            String patch = context.patch(), minor = context.minor();
            if (patch != null && context.candidates().contains(patch) && versionComparator().compare(patch, target) < 0)
                patches.add(patch);
            if (minor != null && context.candidates().contains(minor) && versionComparator().compare(minor, target) < 0)
                minors.add(minor);
        }
        var options = new ArrayList<String>();
        patches.stream().max(versionComparator()).ifPresent(options::add);
        minors.stream().max(versionComparator()).filter(version -> !options.contains(version)).ifPresent(options::add);
        options.add(target);
        return options;
    }

    /**
     * Creates a component record with a stable build, project, type and coordinate identity.
     *
     * @param build      build directory
     * @param project    Gradle project being inspected
     * @param type       component type or class to inspect
     * @param coordinate module coordinate or plugin ID
     */
    public InventoryEntry(String build, String project, Type type, String coordinate) {
        this.build = build;
        this.project = project;
        this.type = type;
        this.coordinate = coordinate;
    }

    /**
     * Checks whether BOMs supply every resolved version and no declaration or management override wins.
     *
     * @return true when BOMs own every resolution and no explicit override wins
     */
    public boolean isBomManaged() {
        return type == Type.DEPENDENCY && declaredSelectors.isEmpty() && managementOverrides.isEmpty()
                && !resolutions.isEmpty() && resolutions.stream().allMatch(r -> !bomOwners.getOrDefault(r.configuration(), Set.of()).isEmpty());
    }

    /**
     * Checks whether every resolution gets its version from another component without an explicit override.
     *
     * @return true when another component supplies every resolved version without an explicit override
     */
    public boolean isVersionAligned() {
        return (type == Type.DEPENDENCY || type == Type.CONSTRAINT) && declaredSelectors.isEmpty() && managementOverrides.isEmpty()
                && !resolutions.isEmpty() && resolutions.stream().allMatch(r -> versionAlignments.containsKey(r.configuration()));
    }

    /**
     * Local implementation plugins and builds without a wrapper have no independent update target.
     *
     * @return true when this entry has an independently versioned update target
     */
    public boolean hasUpdateTarget() {
        if (isBomManaged() || isVersionAligned()) return false;
        if (type == Type.GRADLE) return versionSources.stream().anyMatch(source -> source.kind() == SourceKind.WRAPPER);
        return type != Type.PLUGIN || (!pluginStates.contains("LOCAL_CONVENTION") && !pluginStates.contains("PLUGIN_DEFINITION")
                && (pluginStates.contains("EXTERNAL") || !declaredSelectors.isEmpty()));
    }

    /**
     * Builds the stable key used to merge records from the same inventory context.
     *
     * @return stable build, project, type and coordinate key
     */
    public String id() {
        return build + "|" + project + "|" + type + "|" + coordinate;
    }

    /**
     * Combines evidence and update results from an entry with the same identity.
     *
     * @param other entry with the same inventory identity
     */
    public void merge(InventoryEntry other) {
        if (!id().equals(other.id())) throw new IllegalArgumentException("Different inventory contexts");
        declaredSelectors.addAll(other.declaredSelectors);
        configurations.addAll(other.configurations);
        declarations.addAll(other.declarations);
        versionSources.addAll(other.versionSources);
        resolutions.addAll(other.resolutions);
        platforms.addAll(other.platforms);
        other.bomOwners.forEach((configuration, owners) -> bomOwners.computeIfAbsent(configuration, ignored -> new TreeSet<>()).addAll(owners));
        managementOverrides.addAll(other.managementOverrides);
        versionAlignments.putAll(other.versionAlignments);
        catalogs.addAll(other.catalogs);
        versionConstraints.addAll(other.versionConstraints);
        pluginStates.addAll(other.pluginStates);
        statuses.addAll(other.statuses);
        warnings.addAll(other.warnings);
        fastCheck |= other.fastCheck;
        other.resolutionFailures.forEach(failure -> {
            if (!resolutionFailures.contains(failure)) resolutionFailures.add(failure);
        });
        other.candidates.forEach(v -> {
            if (!candidates.contains(v)) candidates.add(v);
        });
        other.updateContexts.forEach(v -> {
            if (!updateContexts.contains(v)) updateContexts.add(v);
        });
        if (appliedRule == null) appliedRule = other.appliedRule;
        if (recommendedVersion == null) recommendedVersion = other.recommendedVersion;
        if (versionNormalizationPattern == null) {
            versionNormalizationPattern = other.versionNormalizationPattern;
            versionNormalizationReplacement = other.versionNormalizationReplacement;
        }
    }
}
