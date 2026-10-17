/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */
package com.sneedit.deplens.resolution;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.ComponentSelection;
import org.gradle.api.artifacts.DependencyResolveDetails;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.project.ProjectState;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.provider.Provider;
import org.gradle.api.specs.Spec;
import org.gradle.internal.rules.RuleAction;
import org.gradle.internal.rules.SpecRuleAction;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Generic capture of detached rules, with invocation-local execution of opaque rules.
 */
public final class ResolutionRules {
    private ResolutionRules() {
    }

    /**
     * Only handles, never original rule objects, cross the analysis model boundary.
     */
    public record Handle(Provider<ResolutionRuleService> service, String key, String implementation) {
    }

    /**
     * Configuration-phase owner of rules contributed by a project or its buildscript.
     */
    public static Scope forProject(Project project) {
        return new Scope(project.getGradle(), ((ProjectInternal) project).getOwner());
    }

    /**
     * Settings rules retain their original settings closure in the invocation service.
     */
    public static Scope forSettings(Gradle gradle) {
        return new Scope(gradle, null);
    }

    public static final class Scope {
        private final Provider<ResolutionRuleService> service;
        private final @Nullable ProjectState owner;

        private Scope(Gradle gradle, @Nullable ProjectState owner) {
            service = gradle.getSharedServices().registerIfAbsent("depLensResolutionRules", ResolutionRuleService.class, spec -> {
            });
            this.owner = owner;
        }

        private Handle retain(Object rule, List<Handle> retained) {
            var handle = new Handle(service, service.get().register(rule, owner), rule.getClass().getName());
            retained.add(handle);
            return handle;
        }

        Action<? super DependencyResolveDetails> dependency(Action<? super DependencyResolveDetails> rule, List<Handle> retained) {
            return detached(rule) ? rule : new LiveDependency(retain(rule, retained));
        }

        SpecRuleAction<? super ComponentSelection> selection(SpecRuleAction<? super ComponentSelection> rule, List<Handle> retained) {
            if (detached(rule.getAction()) && detached(rule.getSpec())) return rule;
            var handle = retain(rule, retained);
            return new SpecRuleAction<>(new LiveSelectionAction(handle),
                    new LiveSelectionSpec(handle));
        }
    }

    /**
     * Conservatively accepts visible rule classes with immutable scalar captures only.
     * Complex captures are kept intact in the fallback; no getter or rule is executed to inspect them.
     */
    private static boolean detached(Object rule) {
        if (rule instanceof ConfigurationSpec.StaticDependencyRule) return true;
        Class<?> type = rule.getClass();
        if (type.isHidden()) return false;
        try {
            for (Class<?> current = type; current != null; current = current.getSuperclass())
                for (var field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    if (!Modifier.isFinal(field.getModifiers())) return false;
                    Class<?> value = field.getType();
                    if (!(value.isPrimitive() || value.isEnum() || value == String.class || value == Class.class
                            || value == Boolean.class || value == Byte.class || value == Short.class || value == Integer.class
                            || value == Long.class || value == Float.class || value == Double.class || value == Character.class))
                        return false;
                }
        } catch (LinkageError | SecurityException opaque) {
            return false;
        }
        return true;
    }

    public record LiveDependency(Handle handle) implements Action<DependencyResolveDetails> {
        @Override
        @SuppressWarnings("unchecked")
        public void execute(DependencyResolveDetails details) {
            handle.service().get().withRule(handle.key(), original -> {
                ((Action<? super DependencyResolveDetails>) original).execute(details);
                return true;
            });
        }
    }

    public record LiveSelectionAction(Handle handle) implements RuleAction<ComponentSelection> {
        @Override
        public List<Class<?>> getInputTypes() {
            return handle.service().get().withRule(handle.key(), original ->
                    ((SpecRuleAction<?>) original).getAction().getInputTypes());
        }

        @Override
        @SuppressWarnings("unchecked")
        public void execute(ComponentSelection selection, List<?> inputs) {
            handle.service().get().withRule(handle.key(), original -> {
                ((SpecRuleAction<? super ComponentSelection>) original).getAction().execute(selection, inputs);
                return true;
            });
        }
    }

    public record LiveSelectionSpec(Handle handle) implements Spec<ComponentSelection> {
        @Override
        @SuppressWarnings("unchecked")
        public boolean isSatisfiedBy(ComponentSelection selection) {
            return handle.service().get().withRule(handle.key(), original ->
                    ((SpecRuleAction<? super ComponentSelection>) original).getSpec().isSatisfiedBy(selection));
        }
    }

    /**
     * Declares service use and excludes stateful rule execution from configuration-cache reuse.
     */
    public static void configureTask(Task task, List<AnalysisModel> models) {
        var handles = new ArrayList<Handle>();
        for (var model : models) {
            model.configurations.values().forEach(c -> handles.addAll(c.invocationRules));
            model.scriptConfigurations.values().forEach(c -> handles.addAll(c.invocationRules));
            model.settingsConfigurations.values().forEach(c -> handles.addAll(c.invocationRules));
        }
        if (handles.isEmpty()) return;
        new LinkedHashSet<>(handles.stream().map(Handle::service).toList()).forEach(task::usesService);
        String reason = "DepLens preserves original resolution rules that require invocation-local project/settings state";
        task.notCompatibleWithConfigurationCache(reason);
        var features = ((ProjectInternal) task.getProject()).getServices().get(org.gradle.api.configuration.BuildFeatures.class);
        if (features.getConfigurationCache().getActive().get()) {
            try {
                // Native degradation bypasses serialization/reload, including when problems=warn.
                Class<?> degradation = Class.forName("org.gradle.api.internal.ConfigurationCacheDegradation");
                degradation.getMethod("requireDegradation", org.gradle.api.internal.AbstractTask.class, String.class)
                        .invoke(null, task, reason);
            } catch (ClassNotFoundException | NoSuchMethodException unavailable) {
                throw new org.gradle.api.GradleException("DepLens found resolution rules requiring their original context. "
                        + "Run with --no-configuration-cache; automatic fallback requires Gradle 9.7 or newer.", unavailable);
            } catch (ReflectiveOperationException failure) {
                throw new org.gradle.api.GradleException("Cannot disable configuration-cache reuse for original resolution rules", failure);
            }
            task.getLogger().info("DepLens: {} uses original resolution rules; configuration cache reuse disabled", task.getPath());
            handles.stream().map(Handle::implementation).distinct().forEach(implementation ->
                    task.getLogger().info("DepLens: {} retains original rule {}", task.getPath(), implementation));
        }
    }
}
