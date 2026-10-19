/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.resolution;

import org.gradle.api.Action;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributesSchema;
import org.gradle.internal.action.InstantiatingAction;
import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * Attribute rule classes and isolated parameters, without a project-scoped instantiator.
 */
public final class SchemaSpec {
    /** Creates an empty attribute schema snapshot. */
    public SchemaSpec() {
    }

    /**
     * Attribute disambiguation precedence, in evaluation order.
     */
    public List<AttributeSpec> precedence = new ArrayList<>();
    /**
     * Compatibility and disambiguation rules indexed by captured attribute.
     */
    public Map<AttributeSpec, Rules> attributes = new LinkedHashMap<>();

    /**
     * The type and name needed to recreate a named Gradle rule parameter.
     *
     * @param type named Gradle interface to instantiate
     * @param name instance name
     */
    public record NamedParameter(Class<?> type, String name) {
    }

    /**
     * An attribute rule represented by its class and parameters or its ordered action.
     *
     * @param type          rule implementation class, or null for an ordered action
     * @param parameters    isolated rule parameters
     * @param orderedAction captured ordered action when no rule class is available
     */
    public record Rule(@Nullable Class<?> type, List<@Nullable Object> parameters, @Nullable Action<?> orderedAction) {
    }

    /**
     * Ordered compatibility and disambiguation rules for one attribute.
     *
     * @param compatibility  ordered compatibility rules
     * @param disambiguation ordered disambiguation rules
     */
    public record Rules(List<Rule> compatibility, List<Rule> disambiguation) {
    }

    /**
     * Copies attribute rules into detached rule descriptors, preserving their order.
     *
     * @param schema Gradle attribute schema or its captured rules
     * @return detached attribute rules and precedence
     */
    public static SchemaSpec capture(AttributesSchema schema) {
        SchemaSpec result = new SchemaSpec();
        schema.getAttributeDisambiguationPrecedence().stream().map(AttributeSpec::capture).forEach(result.precedence::add);
        for (var attribute : schema.getAttributes()) {
            var strategy = schema.getMatchingStrategy(attribute);
            result.attributes.put(AttributeSpec.capture(attribute), new Rules(
                    capture(actions(strategy.getCompatibilityRules())),
                    capture(actions(strategy.getDisambiguationRules()))));
        }
        return result;
    }

    /**
     * Reads the ordered action list behind a Gradle attribute rule chain.
     */
    @SuppressWarnings("unchecked")
    private static List<Action<?>> actions(Object chain) {
        try {
            Class<?> owner = chain.getClass();
            while (owner != null && !java.util.Arrays.stream(owner.getDeclaredFields()).anyMatch(f -> f.getName().equals("rules")))
                owner = owner.getSuperclass();
            if (owner == null) throw new NoSuchFieldException("rules");
            var field = owner.getDeclaredField("rules");
            field.setAccessible(true);
            return (List<Action<?>>) field.get(chain);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported Gradle attribute rule API", failure);
        }
    }

    /**
     * Copies attribute rules into detached rule descriptors, preserving their order.
     */
    private static List<Rule> capture(List<? extends Action<?>> actions) {
        List<Rule> result = new ArrayList<>();
        for (var original : actions) {
            Action<?> action = original;
            if (action.getClass().getSimpleName().equals("ValidatingAction")) {
                try {
                    var field = action.getClass().getDeclaredField("delegate");
                    field.setAccessible(true);
                    action = (Action<?>) field.get(action);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Unsupported Gradle attribute validation rule API", failure);
                }
            }
            if (action instanceof InstantiatingAction<?> instantiating) {
                for (var rule : instantiating.getRules().getConfigurableRules())
                    result.add(new Rule(rule.getRuleClass(), new ArrayList<>(Arrays.stream(rule.getRuleParams().isolate()).map(SchemaSpec::parameter).toList()), null));
            } else result.add(new Rule(null, new ArrayList<>(), action));
        }
        return result;
    }

    /**
     * Converts named Gradle parameters to detached type-and-name values.
     */
    private static @Nullable Object parameter(@Nullable Object value) {
        if (value instanceof org.gradle.api.Named named && !(value instanceof Enum<?>)) {
            for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass())
                for (Class<?> contract : type.getInterfaces())
                    if (org.gradle.api.Named.class.isAssignableFrom(contract) && contract != org.gradle.api.Named.class)
                        return new NamedParameter(contract, named.getName());
        }
        return value;
    }

    /**
     * Recreates a named Gradle parameter, passing ordinary values through unchanged.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static @Nullable Object restore(@Nullable Object value, org.gradle.api.model.ObjectFactory objects) {
        return value instanceof NamedParameter parameter ? objects.named((Class) parameter.type(), parameter.name()) : value;
    }

    /**
     * Installs captured precedence, compatibility and disambiguation rules in a resolver schema.
     *
     * @param schema  Gradle attribute schema or its captured rules
     * @param objects Gradle object factory
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void install(AttributesSchema schema, org.gradle.api.model.ObjectFactory objects) {
        schema.setAttributeDisambiguationPrecedence(precedence.stream().map(AttributeSpec::restore).toList());
        attributes.forEach((attribute, rules) -> {
            var strategy = schema.attribute((Attribute) attribute.restore());
            for (var rule : rules.compatibility()) {
                if (rule.type() != null)
                    strategy.getCompatibilityRules().add((Class) rule.type(), c -> ((org.gradle.api.ActionConfiguration) c).params(rule.parameters().stream().map(p -> restore(p, objects)).toArray()));
                else actions(strategy.getCompatibilityRules()).add(rule.orderedAction());
            }
            for (var rule : rules.disambiguation()) {
                if (rule.type() != null)
                    strategy.getDisambiguationRules().add((Class) rule.type(), c -> ((org.gradle.api.ActionConfiguration) c).params(rule.parameters().stream().map(p -> restore(p, objects)).toArray()));
                else actions(strategy.getDisambiguationRules()).add(rule.orderedAction());
            }
        });
    }
}
