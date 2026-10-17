/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.resolution;

import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.LibraryElements;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests attribute spec.
 */
class AttributeSpecTest {
    /**
     * Verifies that restored attributes match fresh schema keys despite an old JVM hash.
     */
    @Test
    void restoredAttributesMatchFreshSchemaKeysDespiteAnOldJvmHash() throws Exception {
        Attribute<LibraryElements> saved = Attribute.of("org.gradle.libraryelements", LibraryElements.class);
        Attribute<LibraryElements> fresh = Attribute.of("org.gradle.libraryelements", LibraryElements.class);
        // Gradle Attribute caches a hash derived from Class identity. A bean restored
        // in a different JVM can retain that old hash despite having an equal type.
        var hash = Attribute.class.getDeclaredField("hashCode");
        hash.setAccessible(true);
        hash.setInt(saved, fresh.hashCode() + 1);
        assertThat(saved).isEqualTo(fresh);
        var rules = new HashMap<>(Map.of(fresh, "compatibility rule"));
        assertThat(rules.get(saved)).isNull();

        var snapshot = AttributeSpec.capture(saved);
        assertThat(snapshot).isEqualTo(AttributeSpec.capture(fresh));
        assertThat(rules.get(snapshot.restore()))
                .isEqualTo("compatibility rule");
    }
}
