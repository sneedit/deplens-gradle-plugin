/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.resolution;

import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.artifacts.repositories.*;
import org.gradle.api.credentials.AwsCredentials;
import org.gradle.api.credentials.Credentials;
import org.gradle.api.credentials.HttpHeaderCredentials;
import org.gradle.authentication.Authentication;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.*;

/**
 * Detached repository configuration. Never retains a Gradle project, handler or credentials provider.
 */
public final class RepositorySpec {
    public final String name, kind;
    public @Nullable String url;

    /**
     * Starts a repository snapshot with its original name and repository kind.
     */
    private RepositorySpec(String name, String kind) {
        this.name = name;
        this.kind = kind;
    }

    /**
     * Recreates Gradle's implicit plugin repository without modifying the consumer's settings.
     */
    public static RepositorySpec implicitPluginPortal() {
        var spec = new RepositorySpec("Gradle Central Plugin Repository", "pluginPortal");
        spec.url = System.getProperty("org.gradle.internal.plugins.portal.url.override", "https://plugins.gradle.org/m2");
        return spec;
    }

    public boolean insecure;
    public List<String> artifactUrls = new ArrayList<>();
    public List<String> directories = new ArrayList<>();
    public Map<String, @Nullable String> credentials = new LinkedHashMap<>();
    public @Nullable String credentialsKind;
    public List<Class<? extends Authentication>> authentication = new ArrayList<>();
    public List<ContentRule> content = new ArrayList<>();
    public Set<String> includedConfigurations = new LinkedHashSet<>(), excludedConfigurations = new LinkedHashSet<>();
    public Map<AttributeSpec, Set<Object>> requiredAttributes = new LinkedHashMap<>();
    public boolean pom, gradleMetadata, artifact, ignoreRedirect, snapshots = true, releases = true;
    public List<String> ivyPatterns = new ArrayList<>(), artifactPatterns = new ArrayList<>();
    public boolean m2compatible, ivyDescriptor;

    /**
     * Snapshots every repository setting for invocation-local query reuse.
     * Credentials remain in memory and must never be logged or persisted as a cache key.
     *
     * @return structural repository identity, preserving repository and rule ordering
     */
    public List<?> queryIdentity() {
        return Collections.unmodifiableList(Arrays.asList(name, kind, url, insecure,
                List.copyOf(artifactUrls), List.copyOf(directories), new LinkedHashMap<>(credentials),
                credentialsKind, List.copyOf(authentication), List.copyOf(content),
                new LinkedHashSet<>(includedConfigurations), new LinkedHashSet<>(excludedConfigurations),
                requiredAttributes.entrySet().stream().map(e -> Map.entry(e.getKey(), Set.copyOf(e.getValue()))).toList(),
                pom, gradleMetadata, artifact, ignoreRedirect, snapshots, releases,
                List.copyOf(ivyPatterns), List.copyOf(artifactPatterns), m2compatible, ivyDescriptor));
    }

    /**
     * One repository include or exclude rule for a group, module or version.
     *
     * @param include true for an include rule, false for an exclude rule
     * @param matcher literal, regex or subgroup matching mode
     * @param group   group name or pattern
     * @param module  module name, or null for a group-wide rule
     * @param version version name or pattern, or null for a broader rule
     */
    public record ContentRule(boolean include, String matcher, String group, @Nullable String module,
                              @Nullable String version) {
    }

    /**
     * Snapshots repository order, metadata settings, credentials, authentication and content restrictions.
     *
     * @param repositories repositories in declaration order
     * @return repository snapshots in their original order
     */
    public static List<RepositorySpec> capture(Iterable<? extends ArtifactRepository> repositories) {
        List<RepositorySpec> result = new ArrayList<>();
        for (var repository : repositories) result.add(capture(repository));
        return result;
    }

    /**
     * Snapshots repository order, metadata settings, credentials, authentication and content restrictions.
     */
    private static RepositorySpec capture(ArtifactRepository repository) {
        ArtifactRepository original = repository;
        if (repository.getClass().getName().equals("org.gradle.plugin.use.internal.PluginArtifactRepository")) {
            try {
                repository = (ArtifactRepository) required(repository, "delegate");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot capture plugin repository", e);
            }
        }
        RepositorySpec s = new RepositorySpec(original.getName(), repository instanceof MavenArtifactRepository ? "maven"
                : repository instanceof IvyArtifactRepository ? "ivy" : "flat");
        if (repository instanceof MavenArtifactRepository m) {
            s.url = m.getUrl().toString();
            s.insecure = m.isAllowInsecureProtocol();
            m.getArtifactUrls().forEach(u -> s.artifactUrls.add(u.toString()));
            var sources = m.getMetadataSources();
            s.pom = sources.isMavenPomEnabled();
            s.gradleMetadata = sources.isGradleMetadataEnabled();
            s.artifact = sources.isArtifactEnabled();
            s.ignoreRedirect = sources.isIgnoreGradleMetadataRedirectionEnabled();
        } else if (repository instanceof IvyArtifactRepository i) {
            s.url = i.getUrl() == null ? null : i.getUrl().toString();
            s.insecure = i.isAllowInsecureProtocol();
            try {
                Object layout = repository.getClass().getMethod("getDescriptor").invoke(repository);
                s.ivyPatterns.addAll((Collection<String>) required(layout, "ivyPatterns"));
                s.artifactPatterns.addAll((Collection<String>) required(layout, "artifactPatterns"));
                s.m2compatible = (boolean) required(layout, "m2Compatible");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot capture Ivy layout", e);
            }
            var sources = i.getMetadataSources();
            s.ivyDescriptor = sources.isIvyDescriptorEnabled();
            s.gradleMetadata = sources.isGradleMetadataEnabled();
            s.artifact = sources.isArtifactEnabled();
            s.ignoreRedirect = sources.isIgnoreGradleMetadataRedirectionEnabled();
        } else if (repository instanceof FlatDirectoryArtifactRepository f) {
            f.getDirs().forEach(d -> s.directories.add(d.getAbsolutePath()));
        } else throw new IllegalArgumentException("Unsupported repository type: " + repository.getClass().getName());
        if (repository instanceof AuthenticationSupported auth) {
            Credentials c;
            try {
                Object configured = auth.getClass().getMethod("getConfiguredCredentials").invoke(auth);
                c = configured instanceof org.gradle.api.provider.Provider<?> provider ? (Credentials) provider.getOrNull() : (Credentials) configured;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot capture repository credentials", e);
            }
            if (c instanceof org.gradle.api.credentials.PasswordCredentials p) {
                s.credentialsKind = "password";
                s.credentials.put("username", p.getUsername());
                s.credentials.put("password", p.getPassword());
            } else if (c instanceof HttpHeaderCredentials h) {
                s.credentialsKind = "header";
                s.credentials.put("name", h.getName());
                s.credentials.put("value", h.getValue());
            } else if (c instanceof AwsCredentials a) {
                s.credentialsKind = "aws";
                s.credentials.put("accessKey", a.getAccessKey());
                s.credentials.put("secretKey", a.getSecretKey());
                s.credentials.put("sessionToken", a.getSessionToken());
            }
            auth.getAuthentication().forEach(a -> s.authentication.add(authenticationType(a)));
        }
        // Gradle has no public read API for repository content restrictions. Keep this small
        // compatibility adapter separate from the task and analysis model; unsupported layouts fail closed.
        try {
            Object descriptor = ((org.gradle.api.internal.artifacts.repositories.ArtifactRepositoryInternal) original).getRepositoryDescriptorCopy();
            for (String field : List.of("includeSpecs", "excludeSpecs")) {
                Object value = read(descriptor, field);
                if (value instanceof Iterable<?> specs) for (Object spec : specs)
                    s.content.add(new ContentRule(
                            field.equals("includeSpecs"), required(spec, "matcherKind").toString(), (String) required(spec, "group"),
                            (String) read(spec, "module"), (String) read(spec, "version")));
            }
            s.includedConfigurations = copySet(read(descriptor, "includedConfigurations"));
            s.excludedConfigurations = copySet(read(descriptor, "excludedConfigurations"));
            @SuppressWarnings("unchecked") var attrs = (Map<org.gradle.api.attributes.Attribute<Object>, Set<Object>>) read(descriptor, "requiredAttributes");
            s.requiredAttributes = new LinkedHashMap<>();
            if (attrs != null)
                attrs.forEach((key, values) -> s.requiredAttributes.put(AttributeSpec.capture(key), new LinkedHashSet<>(values)));
            Object snapshots = readOptional(descriptor, "snapshots"), releases = readOptional(descriptor, "releases");
            if (snapshots != null) s.snapshots = (boolean) snapshots;
            if (releases != null) s.releases = (boolean) releases;
            if (readOptional(repository, "componentMetadataSupplierRuleClass") != null || readOptional(repository, "componentMetadataListerRuleClass") != null)
                throw new IllegalArgumentException("Custom repository metadata rules require a native rule adapter: " + s.name);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot capture repository restrictions for " + s.name, failure);
        }
        return s;
    }

    /**
     * Copies an optional internal string set, using an empty set when absent.
     */
    @SuppressWarnings("unchecked")
    private static Set<String> copySet(@Nullable Object value) {
        return value == null ? new LinkedHashSet<>() : new LinkedHashSet<>((Set<String>) value);
    }

    /**
     * Finds the authentication interface Gradle can instantiate again.
     */
    @SuppressWarnings("unchecked")
    private static Class<? extends Authentication> authenticationType(Authentication value) {
        for (Class<?> type : value.getClass().getInterfaces())
            if (Authentication.class.isAssignableFrom(type) && type != Authentication.class)
                return (Class<? extends Authentication>) type;
        Class<?> type = value.getClass().getSuperclass();
        for (Class<?> contract : type.getInterfaces())
            if (Authentication.class.isAssignableFrom(contract)) return (Class<? extends Authentication>) contract;
        throw new IllegalArgumentException("Unsupported repository authentication type");
    }

    /**
     * Reads a repository field through its class hierarchy, allowing a null value.
     */
    private static @Nullable Object read(Object owner, String name) throws ReflectiveOperationException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(owner);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    /**
     * Reads a repository field and rejects missing values.
     */
    private static Object required(Object owner, String name) throws ReflectiveOperationException {
        Object value = read(owner, name);
        if (value == null) throw new IllegalStateException("Missing Gradle repository field: " + name);
        return value;
    }

    /**
     * Reads a repository field, returning null when the field is absent.
     */
    private static @Nullable Object readOptional(Object owner, String name) throws ReflectiveOperationException {
        try {
            return read(owner, name);
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    /**
     * Checks configuration and attribute restrictions before installing a repository for a query.
     *
     * @param configuration Gradle configuration to inspect
     * @return true when the configuration matches the captured repository restrictions
     */
    public boolean accepts(@Nullable ConfigurationSpec configuration) {
        String name = configuration == null ? "classpath" : configuration.name;
        if (!includedConfigurations.isEmpty() && !includedConfigurations.contains(name)) return false;
        if (excludedConfigurations.contains(name)) return false;
        if (configuration == null) return requiredAttributes.isEmpty();
        return requiredAttributes.entrySet().stream().allMatch(e -> e.getValue().stream().anyMatch(v -> {
            Object actual = configuration.attributes.get(e.getKey());
            return java.util.Objects.equals(actual, v) || actual instanceof ConfigurationSpec.NamedValue named && v instanceof org.gradle.api.Named expected && named.name().equals(expected.getName())
                    || actual instanceof ConfigurationSpec.EnumValue enumeration && v instanceof Enum<?> expected && enumeration.name().equals(expected.name());
        }));
    }

    /**
     * Recreates this repository with its captured metadata, access and content settings.
     *
     * @param repositories repositories in declaration order
     */
    public void install(RepositoryHandler repositories) {
        ArtifactRepository repository = switch (kind) {
            case "pluginPortal" -> repositories.gradlePluginPortal();
            case "maven" -> repositories.maven(m -> {
                m.setName(name);
                m.setUrl(java.util.Objects.requireNonNull(url, "Maven repository URL"));
                m.setAllowInsecureProtocol(insecure);
                m.setArtifactUrls(artifactUrls);
                m.metadataSources(s -> {
                    if (pom) s.mavenPom();
                    if (gradleMetadata) s.gradleMetadata();
                    if (artifact) s.artifact();
                    if (ignoreRedirect) s.ignoreGradleMetadataRedirection();
                });
                m.mavenContent(c -> {
                    if (!snapshots) c.releasesOnly();
                    if (!releases) c.snapshotsOnly();
                });
            });
            case "ivy" -> repositories.ivy(i -> {
                i.setName(name);
                if (url != null) i.setUrl(url);
                i.setAllowInsecureProtocol(insecure);
                i.patternLayout(layout -> {
                    layout.setM2compatible(m2compatible);
                    ivyPatterns.forEach(layout::ivy);
                    artifactPatterns.forEach(layout::artifact);
                });
                i.metadataSources(s -> {
                    if (ivyDescriptor) s.ivyDescriptor();
                    if (gradleMetadata) s.gradleMetadata();
                    if (artifact) s.artifact();
                    if (ignoreRedirect) s.ignoreGradleMetadataRedirection();
                });
            });
            case "flat" -> repositories.flatDir(f -> {
                f.setName(name);
                f.dirs(directories.toArray());
            });
            default -> throw new IllegalStateException("Unknown repository kind");
        };
        repository.content(c -> {
            for (var r : content) apply(c, r);
        });
        if (repository instanceof AuthenticationSupported auth) {
            if ("password".equals(credentialsKind))
                auth.credentials(org.gradle.api.artifacts.repositories.PasswordCredentials.class, c -> {
                    c.setUsername(credentials.get("username"));
                    c.setPassword(credentials.get("password"));
                });
            if ("header".equals(credentialsKind)) auth.credentials(HttpHeaderCredentials.class, c -> {
                c.setName(credentials.get("name"));
                c.setValue(credentials.get("value"));
            });
            if ("aws".equals(credentialsKind)) auth.credentials(AwsCredentials.class, c -> {
                c.setAccessKey(credentials.get("accessKey"));
                c.setSecretKey(credentials.get("secretKey"));
                c.setSessionToken(credentials.get("sessionToken"));
            });
            auth.authentication(container -> authentication.forEach(type -> container.create(type.getSimpleName(), type)));
        }
    }

    /**
     * Applies one include or exclude rule to Gradle's repository content descriptor.
     */
    private static void apply(RepositoryContentDescriptor c, ContentRule r) {
        boolean regex = r.matcher().equals("REGEX");
        if (r.version() != null) {
            String module = java.util.Objects.requireNonNull(r.module(), "Module for repository version rule");
            if (r.include()) {
                if (regex) c.includeVersionByRegex(r.group(), module, r.version());
                else c.includeVersion(r.group(), module, r.version());
            } else {
                if (regex) c.excludeVersionByRegex(r.group(), module, r.version());
                else c.excludeVersion(r.group(), module, r.version());
            }
        } else if (r.module() != null) {
            if (r.include()) {
                if (regex) c.includeModuleByRegex(r.group(), r.module());
                else c.includeModule(r.group(), r.module());
            } else {
                if (regex) c.excludeModuleByRegex(r.group(), r.module());
                else c.excludeModule(r.group(), r.module());
            }
        } else if (r.matcher().equals("SUB_GROUP")) {
            if (r.include()) c.includeGroupAndSubgroups(r.group());
            else c.excludeGroupAndSubgroups(r.group());
        } else if (r.include()) {
            if (regex) c.includeGroupByRegex(r.group());
            else c.includeGroup(r.group());
        } else {
            if (regex) c.excludeGroupByRegex(r.group());
            else c.excludeGroup(r.group());
        }
    }
}
