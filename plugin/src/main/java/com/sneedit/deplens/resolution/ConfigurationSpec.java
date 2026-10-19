/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.resolution;

import org.gradle.api.artifacts.*;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;
import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * Values needed to reconstruct a query configuration in a detached Gradle resolver.
 */
public final class ConfigurationSpec {
    /**
     * Original configuration name used to restore its resolution context.
     */
    public final String name;

    /**
     * Starts a detached snapshot for a named configuration.
     */
    private ConfigurationSpec(String name) {
        this.name = name;
    }

    /**
     * Whether the original configuration can be resolved.
     */
    public boolean resolvable;
    /**
     * Detached dependency resolution actions, in execution order.
     */
    public List<org.gradle.api.Action<? super DependencyResolveDetails>> dependencyRules = new ArrayList<>();
    /**
     * Detached component selection rules, including their input specifications.
     */
    public List<org.gradle.internal.rules.SpecRuleAction<? super ComponentSelection>> selectionRules = new ArrayList<>();
    /**
     * Handles to stateful rules retained by the invocation-local service.
     */
    public List<ResolutionRules.Handle> invocationRules = new ArrayList<>();
    /**
     * Consumer attributes used for variant selection.
     */
    public Map<AttributeSpec, Object> attributes = new LinkedHashMap<>();
    /**
     * Declared dependencies, including inherited declarations.
     */
    public List<Module> dependencies = new ArrayList<>();
    /**
     * Dependency constraints applied to the reconstructed graph.
     */
    public List<Module> constraints = new ArrayList<>();
    /**
     * Forced module selectors from the original resolution strategy.
     */
    public List<String> forced = new ArrayList<>();
    /**
     * Group and module exclusions applied to this configuration.
     */
    public Set<Map<String, String>> excludes = new LinkedHashSet<>();
    /**
     * Currently selected versions indexed by module coordinate.
     */
    public Map<String, String> managed = new LinkedHashMap<>();
    /**
     * Versions supplied by dependency-management integrations, indexed by module coordinate.
     */
    public Map<String, String> integrationManaged = new LinkedHashMap<>();

    /**
     * Preserves eachDependency/substitution rules when a resolved configuration is copied later.
     * Gradle otherwise discards them after graph resolution, and copies do not inherit this flag.
     *
     * @param configuration original, reconstructed or copied query configuration
     */
    public static void retainResolutionRules(Configuration configuration) {
        ((org.gradle.api.internal.artifacts.configurations.ResolutionStrategyInternal) configuration.getResolutionStrategy())
                .setKeepStateRequiredForGraphResolution(true);
    }

    /**
     * Identifies equivalent reconstructed graphs independently of their configuration names.
     * Arbitrary user rules may capture project/configuration state, so those graphs are not shared.
     *
     * @return graph inputs, or null when resolution rules cannot safely be compared
     */
    public @Nullable List<?> graphIdentity() {
        if (!selectionRules.isEmpty() || dependencyRules.stream().anyMatch(rule -> !(rule instanceof StaticDependencyRule)))
            return null;
        return List.of(List.copyOf(dependencyRules), new LinkedHashMap<>(attributes),
                List.copyOf(dependencies), List.copyOf(constraints), List.copyOf(forced),
                new LinkedHashSet<>(excludes), new LinkedHashMap<>(managed), new LinkedHashMap<>(integrationManaged));
    }

    /**
     * A detached rich version constraint with required, strict, preferred and rejected values.
     *
     * @param required  required version selector
     * @param strict    strict version selector
     * @param preferred preferred version selector
     * @param rejected  versions excluded by the rich constraint
     */
    public record Version(String required, String strict, String preferred, List<String> rejected) {
        /**
         * Copies all four parts of Gradle's rich version constraint.
         */
        static Version capture(VersionConstraint v) {
            return new Version(v.getRequiredVersion(), v.getStrictVersion(), v.getPreferredVersion(), new ArrayList<>(v.getRejectedVersions()));
        }

        /**
         * Restores required, strict, preferred and rejected versions into a mutable constraint.
         */
        void apply(MutableVersionConstraint v) {
            v.require(required);
            if (!strict.isBlank()) v.strictly(strict);
            if (!preferred.isBlank()) v.prefer(preferred);
            if (!rejected.isEmpty()) v.reject(rejected.toArray(String[]::new));
        }
    }

    /**
     * A named attribute value that can be recreated through Gradle's object factory.
     *
     * @param name Gradle named attribute value
     */
    public record NamedValue(String name) {
    }

    /**
     * An enum constant stored by name for detached attribute restoration.
     *
     * @param name enum constant name
     */
    public record EnumValue(String name) {
    }

    /**
     * An explicitly requested dependency artifact and its optional classifier and extension.
     *
     * @param name       artifact name
     * @param type       artifact type
     * @param extension  artifact filename extension, or null
     * @param classifier artifact classifier, or null
     */
    public record Artifact(String name, String type, @Nullable String extension, @Nullable String classifier) {
    }

    /**
     * A detached dependency or constraint, including its variant requirements.
     *
     * @param group          module group
     * @param name           module name
     * @param version        rich version constraint
     * @param transitive     whether transitive dependencies are included
     * @param attributes     captured dependency attributes
     * @param excludes       captured group and module exclusions
     * @param artifacts      explicitly requested artifacts
     * @param capabilities   requested capability coordinates
     * @param endorsesStrict whether strict platform versions are endorsed
     */
    public record Module(String group, String name, Version version, boolean transitive,
                         Map<AttributeSpec, Object> attributes, Set<Map<String, String>> excludes,
                         List<Artifact> artifacts, List<String> capabilities, boolean endorsesStrict) {
        /**
         * Rebuilds an external dependency with its attributes, excludes, artifacts and capabilities.
         */
        ExternalModuleDependency dependency(DependencyHandler handler, org.gradle.api.model.ObjectFactory objects) {
            ExternalModuleDependency result = (ExternalModuleDependency) handler.create(group + ":" + name);
            result.version(version::apply);
            result.setTransitive(transitive);
            result.attributes(a -> applyAttributes(attributes, a, objects));
            excludes.forEach(result::exclude);
            for (var artifact : artifacts)
                result.artifact(a -> {
                    a.setName(artifact.name());
                    a.setType(artifact.type());
                    a.setExtension(artifact.extension());
                    a.setClassifier(artifact.classifier());
                });
            if (!capabilities.isEmpty()) result.capabilities(c -> capabilities.forEach(c::requireCapability));
            if (endorsesStrict) result.endorseStrictVersions();
            return result;
        }
    }

    /**
     * Snapshots dependencies, constraints, attributes and rules needed to recreate Gradle resolution.
     *
     * @param configuration Gradle configuration to inspect
     * @param scope         original context retained for opaque rules
     * @return detached configuration snapshot
     */
    public static ConfigurationSpec capture(Configuration configuration, ResolutionRules.Scope scope) {
        return capture(configuration, rule -> false, scope);
    }

    /**
     * Snapshots dependencies, constraints, attributes and rules needed to recreate Gradle resolution.
     *
     * @param configuration Gradle configuration to inspect
     * @param integrated    predicate identifying integration-owned resolution rules
     * @param scope         original context retained for opaque rules
     * @return detached configuration snapshot
     */
    public static ConfigurationSpec capture(Configuration configuration, java.util.function.Predicate<org.gradle.api.Action<?>> integrated,
                                            ResolutionRules.Scope scope) {
        ConfigurationSpec s = new ConfigurationSpec(configuration.getName());
        s.resolvable = configuration.isCanBeResolved();
        s.captureDependencyRules(configuration, integrated, scope);
        for (var rule : ((org.gradle.api.internal.artifacts.ComponentSelectionRulesInternal) configuration.getResolutionStrategy().getComponentSelection()).getRules())
            s.selectionRules.add(scope.selection(rule, s.invocationRules));
        s.attributes = attributes(configuration.getAttributes());
        configuration.getAllDependencies().forEach(d -> {
            if (!(d instanceof ExternalModuleDependency m)) return;
            s.dependencies.add(new Module(m.getGroup(), m.getName(), Version.capture(m.getVersionConstraint()), m.isTransitive(), attributes(m.getAttributes()), excludes(m.getExcludeRules()),
                    new ArrayList<>(m.getArtifacts().stream().map(a -> new Artifact(a.getName(), a.getType(), a.getExtension(), a.getClassifier())).toList()),
                    new ArrayList<>(m.getRequestedCapabilities().stream().map(c -> c.getGroup() + ":" + c.getName() + (c.getVersion() == null ? "" : ":" + c.getVersion())).toList()), m.isEndorsingStrictVersions()));
        });
        configuration.getAllDependencyConstraints().forEach(c -> s.constraints.add(new Module(c.getGroup(), c.getName(), Version.capture(c.getVersionConstraint()), false, attributes(c.getAttributes()), new LinkedHashSet<>(), new ArrayList<>(), new ArrayList<>(), false)));
        configuration.getResolutionStrategy().getForcedModules().forEach(m -> s.forced.add(m.getGroup() + ":" + m.getName() + ":" + m.getVersion()));
        s.excludes = excludes(configuration.getExcludeRules());
        if (s.resolvable && configuration.getAllDependencies().stream().anyMatch(ProjectDependency.class::isInstance))
            s.captureProjectEdges(configuration);
        return s;
    }

    /**
     * Copies dependency rules while leaving integration-owned rules to their adapters.
     */
    @SuppressWarnings("unchecked")
    private void captureDependencyRules(Configuration configuration, java.util.function.Predicate<org.gradle.api.Action<?>> integrated,
                                        ResolutionRules.Scope scope) {
        var strategy = (org.gradle.api.internal.artifacts.configurations.ResolutionStrategyInternal) configuration.getResolutionStrategy();
        Object actions = strategy.getDependencySubstitution().getRuleAction();
        try {
            Class<?> set = Class.forName("com.google.common.collect.ImmutableSet", true, org.gradle.internal.ImmutableActionSet.class.getClassLoader());
            Class<?> builderType = Class.forName("com.google.common.collect.ImmutableSet$Builder", true, set.getClassLoader());
            var unpack = org.gradle.internal.ImmutableActionSet.class.getDeclaredMethod("unpackInto", builderType);
            unpack.setAccessible(true);
            var builder = set.getMethod("builder").invoke(null);
            unpack.invoke(actions, builder);
            for (Object action : (Iterable<?>) builderType.getMethod("build").invoke(builder)) {
                if (!action.getClass().getSimpleName().equals("DependencyResolveDetailsWrapperAction")) continue;
                var field = action.getClass().getDeclaredField("delegate");
                field.setAccessible(true);
                var delegate = (org.gradle.api.Action<? super DependencyResolveDetails>) field.get(action);
                if (!integrated.test(delegate))
                    dependencyRules.add(scope.dependency(cacheableRule(delegate), invocationRules));
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported Gradle dependency rule API", failure);
        }
    }

    /**
     * Replaces unambiguous hidden Gradle lambdas with a restorable static method descriptor.
     */
    private static org.gradle.api.Action<? super DependencyResolveDetails> cacheableRule(org.gradle.api.Action<? super DependencyResolveDetails> action) throws ReflectiveOperationException {
        Class<?> type = action.getClass();
        // Core plugins can register hidden, non-capturing lambda classes. Their
        // generated names cannot be restored by the configuration cache.
        if (!type.isHidden() || !type.getName().startsWith("org.gradle.") || type.getDeclaredFields().length != 0)
            return action;
        String ownerName = type.getName().substring(0, type.getName().indexOf("$$Lambda"));
        Class<?> owner = Class.forName(ownerName, false, type.getClassLoader());
        var methods = Arrays.stream(owner.getDeclaredMethods()).filter(m -> m.getName().startsWith("lambda$")
                && java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getReturnType() == void.class
                && Arrays.equals(m.getParameterTypes(), new Class<?>[]{DependencyResolveDetails.class})).toList();
        if (methods.size() != 1) return action;
        return new StaticDependencyRule(owner, methods.getFirst().getName());
    }

    /**
     * A restorable reference to a non-capturing static Gradle dependency rule.
     *
     * @param owner  class containing the static rule method
     * @param method static method name
     */
    public record StaticDependencyRule(Class<?> owner,
                                       String method) implements org.gradle.api.Action<DependencyResolveDetails> {
        /**
         * Invokes the captured core Gradle dependency rule and preserves its runtime failure.
         *
         * @param details Gradle dependency or operation details
         */
        @Override
        public void execute(DependencyResolveDetails details) {
            try {
                var action = owner.getDeclaredMethod(method, DependencyResolveDetails.class);
                action.setAccessible(true);
                action.invoke(null, details);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                if (failure.getCause() instanceof RuntimeException cause) throw cause;
                throw new IllegalStateException("Core dependency rule failed", failure.getCause());
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot restore core dependency rule", failure);
            }
        }
    }

    /**
     * Export project variants as external peers/constraints; execution never dereferences another Project.
     */
    private void captureProjectEdges(Configuration configuration) {
        var root = configuration.getIncoming().getResolutionResult().getRootComponent().get();
        /**
         * A project component and selected variant visited while exporting external graph edges.
         */
        record Variant(org.gradle.api.artifacts.result.ResolvedComponentResult component,
                       org.gradle.api.artifacts.result.ResolvedVariantResult variant) {
        }
        Set<Variant> visited = new HashSet<>();
        Deque<Variant> pending = new ArrayDeque<>();
        for (var edge : root.getDependencies())
            if (edge instanceof org.gradle.api.artifacts.result.ResolvedDependencyResult resolved
                    && resolved.getSelected().getId() instanceof org.gradle.api.artifacts.component.ProjectComponentIdentifier)
                pending.add(new Variant(resolved.getSelected(), resolved.getResolvedVariant()));
        while (!pending.isEmpty()) {
            var selected = pending.removeFirst();
            if (!visited.add(selected)) continue;
            for (var edge : selected.component().getDependenciesForVariant(selected.variant())) {
                if (edge instanceof org.gradle.api.artifacts.result.ResolvedDependencyResult resolved
                        && resolved.getSelected().getId() instanceof org.gradle.api.artifacts.component.ProjectComponentIdentifier) {
                    pending.add(new Variant(resolved.getSelected(), resolved.getResolvedVariant()));
                } else if (edge.getRequested() instanceof org.gradle.api.artifacts.component.ModuleComponentSelector selector) {
                    var module = new Module(selector.getGroup(), selector.getModule(), Version.capture(selector.getVersionConstraint()), true,
                            attributes(selector.getAttributes()), new LinkedHashSet<>(), new ArrayList<>(), new ArrayList<>(), false);
                    (edge.isConstraint() ? constraints : dependencies).add(module);
                }
            }
        }
    }

    /**
     * Copies attribute keys and values, preserving enum and named values in detached form.
     */
    private static Map<AttributeSpec, Object> attributes(AttributeContainer container) {
        Map<AttributeSpec, Object> values = new LinkedHashMap<>();
        for (var key : container.keySet()) {
            Object value = container.getAttribute(key);
            values.put(AttributeSpec.capture(key), value instanceof Enum<?> enumeration ? new EnumValue(enumeration.name()) : value instanceof org.gradle.api.Named named ? new NamedValue(named.getName()) : value);
        }
        return values;
    }

    /**
     * Copies exclude rules into plain group and module maps.
     */
    private static Set<Map<String, String>> excludes(Set<ExcludeRule> rules) {
        Set<Map<String, String>> values = new LinkedHashSet<>();
        rules.forEach(r -> {
            Map<String, String> v = new LinkedHashMap<>();
            if (r.getGroup() != null) v.put("group", r.getGroup());
            if (r.getModule() != null) v.put("module", r.getModule());
            values.add(v);
        });
        return values;
    }

    /**
     * Restores captured attribute values into a Gradle attribute container.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void applyAttributes(Map<AttributeSpec, Object> values, AttributeContainer target, org.gradle.api.model.ObjectFactory objects) {
        values.forEach((key, value) -> target.attribute((Attribute) key.restore(), value instanceof EnumValue enumeration ? Enum.valueOf((Class) key.type(), enumeration.name()) : value instanceof NamedValue named ? objects.named((Class) key.type(), named.name()) : value));
    }

    /**
     * Rebuilds a detached configuration with the captured dependencies, constraints and resolution rules.
     *
     * @param configurations Gradle configuration container
     * @param handler        Gradle dependency handler
     * @param objects        Gradle object factory
     * @return new detached configuration ready for resolution
     */
    public Configuration create(ConfigurationContainer configurations, DependencyHandler handler, org.gradle.api.model.ObjectFactory objects) {
        Configuration c = configurations.detachedConfiguration();
        retainResolutionRules(c);
        c.setCanBeConsumed(false);
        c.setCanBeResolved(true);
        applyAttributes(attributes, c.getAttributes(), objects);
        dependencies.forEach(d -> c.getDependencies().add(d.dependency(handler, objects)));
        constraints.forEach(d -> {
            var constraint = handler.getConstraints().create(d.group() + ":" + d.name());
            constraint.version(d.version()::apply);
            constraint.attributes(a -> applyAttributes(d.attributes(), a, objects));
            c.getDependencyConstraints().add(constraint);
        });
        var selection = (org.gradle.api.internal.artifacts.ComponentSelectionRulesInternal) c.getResolutionStrategy().getComponentSelection();
        selectionRules.forEach(selection::addRule);
        if (!forced.isEmpty()) c.getResolutionStrategy().force(forced.toArray());
        excludes.forEach(c::exclude);
        dependencyRules.forEach(c.getResolutionStrategy()::eachDependency);
        if (!integrationManaged.isEmpty()) {
            Map<String, String> versions = new LinkedHashMap<>(integrationManaged);
            Set<String> direct = new HashSet<>();
            dependencies.forEach(d -> direct.add(d.group() + ":" + d.name()));
            c.getResolutionStrategy().eachDependency(d -> {
                String id = d.getTarget().getGroup() + ":" + d.getTarget().getName();
                String version = d.getTarget().getVersion();
                if (direct.contains(id) && version != null && (version.contains("+") || version.startsWith("latest.") || version.startsWith("[") || version.startsWith("(")))
                    return;
                if (versions.containsKey(id)) d.useVersion(versions.get(id));
            });
        }
        if (!managed.isEmpty()) {
            Map<String, String> versions = Map.copyOf(managed);
            c.getResolutionStrategy().eachDependency(d -> {
                String version = versions.get(d.getRequested().getGroup() + ":" + d.getRequested().getName());
                if (version != null && (d.getRequested().getVersion() == null || d.getRequested().getVersion().isBlank()))
                    d.useVersion(version);
            });
        }
        return c;
    }
}
