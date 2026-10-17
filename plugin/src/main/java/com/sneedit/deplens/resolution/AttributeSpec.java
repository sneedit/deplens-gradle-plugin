/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.resolution;

import org.gradle.api.attributes.Attribute;

/**
 * Attribute identity without Gradle's cached, JVM-specific Class identity hash.
 *
 * @param name attribute name
 * @param type attribute value type
 */
public record AttributeSpec(String name, Class<?> type) {
    /**
     * Stores an attribute's name and type without its JVM-local cached hash.
     *
     * @param attribute Gradle attribute key
     * @return detached attribute identity
     */
    public static AttributeSpec capture(Attribute<?> attribute) {
        return new AttributeSpec(attribute.getName(), attribute.getType());
    }

    /**
     * Recreates an attribute key that matches freshly constructed Gradle schema keys.
     *
     * @return fresh attribute key with the recorded name and type
     */
    public Attribute<?> restore() {
        return Attribute.of(name, type);
    }
}
