/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */
package com.sneedit.deplens;

import com.sneedit.deplens.inventory.BuildInventory;
import com.sneedit.deplens.inventory.InventoryEntry;
import com.sneedit.deplens.inventory.OutputDeclaredConfigurationFilter;
import com.sneedit.deplens.resolution.AnalysisDefaults;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static com.sneedit.deplens.inventory.InventoryEntry.*;

class ConfigurationFilterTest {
    @Test
    void includesMatchWholeNamesAndExclusionsWin() {
        var all = ConfigurationFilter.predicate("checkResolvableConfigurationFilter", List.of(), List.of());
        assertThat(all.test("anything")).isTrue();
        var selected = ConfigurationFilter.predicate("checkResolvableConfigurationFilter", List.of(".*Classpath", "tools"), List.of("test.*"));
        assertThat(selected.test("runtimeClasspath")).isTrue();
        assertThat(selected.test("tools")).isTrue();
        assertThat(selected.test("testRuntimeClasspath")).isFalse();
        assertThat(selected.test("toolsExtra")).isFalse();
        assertThatThrownBy(() -> ConfigurationFilter.predicate("checkResolvableConfigurationFilter", List.of("["), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("checkResolvableConfigurationFilter.includes");
    }

    @Test
    void explicitListsOverrideInheritedDefaultsIncludingAnEmptyList() {
        var objects = ProjectBuilder.builder().build().getObjects();
        var root = objects.newInstance(DepLensExtension.class);
        root.getCheckResolvableConfigurationFilter().getIncludes().set(List.of("runtimeClasspath"));
        root.getCheckResolvableConfigurationFilter().getExcludes().set(List.of("test.*"));
        var child = objects.newInstance(DepLensExtension.class);
        child.getCheckResolvableConfigurationFilter().getIncludes().set(List.of());
        AnalysisDefaults.capture(root).inheritCheckResolvableConfigurationFilter(child);
        assertThat(child.getCheckResolvableConfigurationFilter().getIncludes().get()).isEmpty();
        assertThat(child.getCheckResolvableConfigurationFilter().getExcludes().get()).containsExactly("test.*");
    }

    @Test
    void reportViewPreservesCompletenessFailuresSourcesAndEntriesWithoutConfigurationOrigins() {
        BuildInventory original = new BuildInventory();
        var hidden = new InventoryEntry("build", ":", Type.DEPENDENCY, "org.test:hidden");
        hidden.declarations.add(new Declaration("build.gradle", 1, "tools", "1"));
        hidden.statuses.add(Status.FAILED);
        var shared = new InventoryEntry("build", ":", Type.DEPENDENCY, "org.test:shared");
        shared.declarations.addAll(List.of(new Declaration("build.gradle", 2, "tools", "1"),
                new Declaration("build.gradle", 3, "implementation", "1")));
        var generated = new InventoryEntry("build", ":", Type.DEPENDENCY, "org.test:generated");
        generated.configurations.add("tools");
        var unknown = new InventoryEntry("build", ":", Type.DEPENDENCY, "org.test:unknown");
        var script = new InventoryEntry("build", ":", Type.DEPENDENCY, "org.test:script");
        script.configurations.add("buildscript:classpath");
        var plugin = new InventoryEntry("build", ":", Type.PLUGIN, "org.test.plugin");
        plugin.configurations.add("tools");
        var gradle = new InventoryEntry("build", ":", Type.GRADLE, "gradle");
        original.entries.addAll(List.of(hidden, shared, generated, unknown, script, plugin, gradle));
        original.complete = false;
        original.checksComplete = false;
        original.fastCheck = true;
        original.warnings.add("A checked configuration failed");
        original.builds.add("build");
        original.selectedSources.add(new BuildInventory.SelectedSource(new Source("build.gradle", 3, SourceKind.BUILD_SCRIPT, null, "1"), "2", List.of(shared.id()), List.of()));
        var view = OutputDeclaredConfigurationFilter.apply(original, List.of(), List.of("tools"));
        assertThat(view.entries).containsExactly(shared, unknown, script, plugin, gradle);
        assertThat(view.complete).isFalse();
        assertThat(view.checksComplete).isFalse();
        assertThat(view.fastCheck).isTrue();
        assertThat(view.hiddenEntries).isEqualTo(2);
        assertThat(view.hiddenFailedEntries).isEqualTo(1);
        assertThat(view.warnings).containsExactlyElementsOf(original.warnings);
        assertThat(view.builds).containsExactly("build");
        assertThat(view.selectedSources).containsExactlyElementsOf(original.selectedSources);
        assertThat(original.entries).hasSize(7);
        assertThat(shared.declarations).hasSize(2);
        assertThat(OutputDeclaredConfigurationFilter.apply(original, List.of(), List.of())).isSameAs(original);
    }
}
