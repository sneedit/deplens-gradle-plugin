/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.updates;

import com.google.gson.Gson;
import com.sneedit.deplens.inventory.InventoryEntry;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests version patterns.
 */
class VersionPatternsTest {
    private static final String DATE = "(?<major>[0-9]{4})(?<minor>[0-9]{2})(?<patch>[0-9]{2})";

    /**
     * Verifies that explicit pattern overrides calendar detection with numeric groups.
     */
    @Test
    void explicitPatternOverridesCalendarDetectionWithNumericGroups() {
        var p = VersionPatterns.compile("versionPattern", DATE);
        assertThat(VersionPatterns.classify(p, "20260814", "20260815")).isEqualTo(Versions.Tier.PATCH);
        assertThat(VersionPatterns.classify(p, "20260814", "20260901")).isEqualTo(Versions.Tier.MINOR);
        assertThat(VersionPatterns.classify(p, "20260814", "20270101")).isEqualTo(Versions.Tier.MAJOR);
        assertThat(VersionPatterns.classify(p, "20260814", "prefix20260815")).isEqualTo(Versions.Tier.UNKNOWN);
        assertThat(VersionPatterns.classify(p, "1.0.0", "20260815")).isEqualTo(Versions.Tier.UNKNOWN);
        var nonNumeric = VersionPatterns.compile("versionPattern", "(?<major>.*)[.](?<minor>.*)[.](?<patch>.*)");
        assertThat(VersionPatterns.classify(nonNumeric, "1.0.0", "one.0.0")).isEqualTo(Versions.Tier.UNKNOWN);
    }

    /**
     * Verifies that rules serialize inherit per field and explicit prerelease detector replaces the default.
     */
    @Test
    void rulesSerializeInheritPerFieldAndExplicitPrereleaseDetectorReplacesTheDefault() {
        var project = ProjectBuilder.builder().build();
        var rules = project.getObjects().newInstance(UpdateRules.class);
        rules.dependency("org.json:json", r -> {
            r.getVersionPattern().set(DATE);
            r.getPreReleasePattern().set("(?i)-eap[0-9]+$");
        });
        // A later rule changing another field must retain both patterns.
        rules.dependency("org.json:json", r -> r.getAllowedUpdates().set("minor"));
        var gson = new Gson();
        var inherited = gson.fromJson(gson.toJson(rules.snapshot()), UpdateRules.Spec[].class);
        var entry = new InventoryEntry(".", ":", InventoryEntry.Type.DEPENDENCY, "org.json:json");
        var p = RulePolicy.resolve(entry, null, List.of(inherited));
        assertThat(p.classify("20260814", "20260901")).isEqualTo(Versions.Tier.MINOR);
        assertThat(p.preRelease("1.0-EAP1")).isTrue();
        assertThat(p.preRelease("1.0-rc1")).isFalse();
        assertThat(p.allows(Versions.Tier.MINOR)).isTrue();
        assertThat(p.allows(Versions.Tier.UNKNOWN)).isFalse();
        assertThat(RulePolicy.resolve(new InventoryEntry(".", ":", InventoryEntry.Type.DEPENDENCY, "other:lib"), null, List.of(inherited))
                .preRelease("1.0-rc1")).isTrue();
        rules.dependency("org.json:json", r -> r.getPreReleasePattern().set("(?!)"));
        assertThat(RulePolicy.resolve(entry, null, rules.snapshot()).preRelease("1.0-EAP1")).isFalse();
    }

    /** Exclusions survive detached snapshots and inherit independently of other policy fields. */
    @Test
    void ignoredVersionPatternsInheritOverrideAndClearIndependently() {
        var rules = ProjectBuilder.builder().build().getObjects().newInstance(UpdateRules.class);
        rules.global(r -> r.getIgnoredVersionPatterns().set(List.of("(?i)-redhat", "^9[.]")));
        rules.project(":app", r -> r.getIncludePreReleases().set(true));
        rules.group("org.test", r -> r.getIgnoredVersions().set(List.of("2.0")));
        rules.dependency("org.test:lib", r -> r.getAllowedUpdates().set("minor"));
        var gson = new Gson();
        var specs = List.of(gson.fromJson(gson.toJson(rules.snapshot()), UpdateRules.Spec[].class));
        var entry = new InventoryEntry(".", ":app", InventoryEntry.Type.DEPENDENCY, "org.test:lib");
        var policy = RulePolicy.resolve(entry, null, specs);
        assertThat(policy.preReleases).isTrue();
        assertThat(policy.allowed).isEqualTo("minor");
        assertThat(policy.isIgnored("3.0-REDHAT-00001")).isTrue();
        assertThat(policy.isIgnored("9.1-beta1")).isTrue();
        assertThat(policy.isIgnored("2.0")).isTrue();
        assertThat(policy.isIgnored("3.0-beta1")).isFalse();
        assertThat(policy.isIgnored("19.0")).isFalse();
        rules.dependency("org.test:lib", r -> r.getIgnoredVersionPatterns().set(List.of("-vendor")));
        policy = RulePolicy.resolve(entry, null, rules.snapshot());
        assertThat(policy.isIgnored("3.0-redhat")).isFalse();
        assertThat(policy.isIgnored("3.0-vendor")).isTrue();
        rules.dependency("org.test:lib", r -> r.getIgnoredVersionPatterns().set(List.of()));
        policy = RulePolicy.resolve(entry, null, rules.snapshot());
        assertThat(policy.isIgnored("3.0-vendor")).isFalse();
        assertThat(policy.isIgnored("2.0")).isTrue();
        for (var type : List.of(InventoryEntry.Type.PLUGIN, InventoryEntry.Type.PLATFORM, InventoryEntry.Type.GRADLE)) {
            var other = new InventoryEntry(".", ":", type, "other");
            assertThat(RulePolicy.resolve(other, null, specs).isIgnored("3.0-redhat")).as(type.toString()).isTrue();
        }
    }

    /** Invalid exclusions fail configuration in every rule scope, including late mutations. */
    @Test
    void invalidIgnoredVersionPatternsFailEarly() {
        var rules = ProjectBuilder.builder().build().getObjects().newInstance(UpdateRules.class);
        assertThatThrownBy(() -> rules.global(r -> r.getIgnoredVersionPatterns().set(List.of("["))))
                .hasMessageContaining("Invalid ignoredVersionPatterns");
        rules.gradle(r -> r.getIgnoredVersionPatterns().set(List.of("-rc")));
        rules.platform("org.test:bom", r -> r.getIgnoredVersionPatterns().set(List.of("-vendor")));
        rules.plugin("org.test.plugin", r -> r.getIgnoredVersionPatterns().set(List.of("-vendor")));
        var rule = rules.snapshot();
        assertThat(rule).hasSize(3);
        var bound = new java.util.concurrent.atomic.AtomicReference<UpdateRule>();
        rules.global(bound::set);
        bound.get().getIgnoredVersionPatterns().set(List.of("["));
        assertThatThrownBy(rules::snapshot).hasMessageContaining("Invalid ignoredVersionPatterns");
    }

    /**
     * Verifies that invalid patterns and unsupported scopes fail early.
     */
    @Test
    void invalidPatternsAndUnsupportedScopesFailEarly() {
        assertThatThrownBy(() -> VersionPatterns.compile("versionPattern", "[")).hasMessageContaining("Invalid versionPattern");
        assertThatThrownBy(() -> VersionPatterns.compile("preReleasePattern", "[")).hasMessageContaining("Invalid preReleasePattern");
        assertThatThrownBy(() -> VersionPatterns.compile("versionPattern", "([0-9]+)[.]([0-9]+)"))
                .hasMessageContaining("named groups major, minor and patch");
        var rules = ProjectBuilder.builder().build().getObjects().newInstance(UpdateRules.class);
        assertThatThrownBy(() -> rules.global(r -> r.getVersionPattern().set(DATE))).hasMessageContaining("only in dependency and plugin rules");
        assertThatThrownBy(() -> rules.gradle(r -> r.getPreReleasePattern().set("rc"))).hasMessageContaining("only in dependency and plugin rules");
    }
}
