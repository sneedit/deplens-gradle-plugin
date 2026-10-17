/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.logging;

import org.gradle.api.Project;
import org.gradle.api.internal.GradleInternal;
import org.gradle.internal.operations.*;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Filters IDE download events for this check only; resolution, logging and other build events stay intact.
 */
public final class DownloadProgressScope implements AutoCloseable {
    private static final Map<BuildOperationListenerManager, Hook> HOOKS = new WeakHashMap<>();
    private final BuildOperationListenerManager manager;
    private final Hook hook;
    private final @Nullable OperationIdentifier root;

    /**
     * Tracks one user of a shared download-event filter and its root operation.
     */
    private DownloadProgressScope(BuildOperationListenerManager manager, Hook hook, @Nullable OperationIdentifier root) {
        this.manager = manager;
        this.hook = hook;
        this.root = root;
    }

    /**
     * Opens a scoped IDE download-event filter, tolerating unsupported Gradle internals.
     *
     * @param project Gradle project being inspected
     * @return scope to close when the check finishes
     */
    public static DownloadProgressScope open(Project project) {
        BuildOperationListenerManager manager = ((GradleInternal) project.getGradle()).getServices().get(BuildOperationListenerManager.class);
        return open(manager, project.getLogger());
    }

    /**
     * Opens a scoped IDE download-event filter, tolerating unsupported Gradle internals.
     *
     * @param manager Gradle build operation listener manager
     * @param logger  logger used for report output
     * @return scope to close when the check finishes
     */
    public static DownloadProgressScope open(BuildOperationListenerManager manager, org.gradle.api.logging.Logger logger) {
        OperationIdentifier root = CurrentBuildOperationRef.instance().getId();
        synchronized (HOOKS) {
            Hook hook = HOOKS.get(manager);
            if (hook == null) {
                hook = new Hook();
                try {
                    hook.install(manager);
                } catch (ReflectiveOperationException | RuntimeException unsupported) {
                    hook.restore();
                    // This compatibility adapter is optional: never break a check on a newer Gradle internals layout.
                    logger.info("DepLens: IDE download progress filtering is unavailable on this Gradle version.");
                }
                HOOKS.put(manager, hook);
            }
            if (root != null) hook.roots.add(root);
            hook.users++;
            return new DownloadProgressScope(manager, hook, root);
        }
    }

    /**
     * Releases this scope and restores listeners when the last user leaves.
     */
    @Override
    public void close() {
        synchronized (HOOKS) {
            if (root != null) hook.roots.remove(root);
            if (--hook.users == 0) {
                hook.restore();
                HOOKS.remove(manager);
            }
        }
    }

    /**
     * An original listener and the field that must be restored when filtering ends.
     */
    private record Replacement(Object holder, Field field, BuildOperationListener original) {
    }

    /**
     * Shared listener replacements and active check roots for overlapping filter scopes.
     */
    private static final class Hook {
        final Set<OperationIdentifier> roots = ConcurrentHashMap.newKeySet();
        final List<Replacement> replacements = new ArrayList<>();
        int users;

        /**
         * Wraps IDE event listeners while keeping Gradle's existing listener holders intact.
         */
        void install(BuildOperationListenerManager manager) throws ReflectiveOperationException {
            // Gradle exposes no public API for a plugin to filter Tooling API progress events.
            // Keep the compatibility bridge isolated. Preserve the existing listener wrappers and their active-operation state.
            Field listeners = manager.getClass().getDeclaredField("listeners");
            listeners.setAccessible(true);
            Object value = listeners.get(manager);
            if (value instanceof AtomicReference<?> reference) value = reference.get(); // Gradle 9
            for (Object holder : (Iterable<?>) value) {
                Field delegate = holder.getClass().getDeclaredField("delegate");
                delegate.setAccessible(true);
                BuildOperationListener original = (BuildOperationListener) delegate.get(holder);
                if (!original.getClass().getName().equals("org.gradle.tooling.internal.provider.runner.ClientBuildEventGenerator"))
                    continue;
                delegate.set(holder, new Filter(original, roots));
                replacements.add(new Replacement(holder, delegate, original));
            }
        }

        /**
         * Restores original listeners and clears the recorded replacements.
         */
        void restore() {
            for (Replacement replacement : replacements) {
                try {
                    replacement.field.set(replacement.holder, replacement.original);
                } catch (IllegalAccessException failure) {
                    throw new IllegalStateException("Cannot restore IDE progress listener", failure);
                }
            }
            replacements.clear();
        }
    }

    /**
     * Forwards build events while hiding downloads below active DepLens check roots.
     */
    private static final class Filter implements BuildOperationListener {
        private final BuildOperationListener delegate;
        private final Set<OperationIdentifier> roots;
        private final Set<OperationIdentifier> descendants = ConcurrentHashMap.newKeySet();
        private final Set<OperationIdentifier> hidden = ConcurrentHashMap.newKeySet();

        /**
         * Wraps a listener and shares the set of active check root operations.
         */
        Filter(BuildOperationListener delegate, Set<OperationIdentifier> roots) {
            this.delegate = delegate;
            this.roots = roots;
        }

        /**
         * Tracks check descendants and hides only their external-resource download operations.
         *
         * @param operation Gradle build operation
         * @param event     operation event to forward or hide
         */
        @Override
        public void started(BuildOperationDescriptor operation, OperationStartEvent event) {
            OperationIdentifier parent = operation.getParentId();
            boolean inCheck = roots.contains(operation.getId()) || (parent != null && (roots.contains(parent) || descendants.contains(parent)));
            if (inCheck) {
                descendants.add(operation.getId());
                if (downloadDetails(operation.getDetails())) {
                    hidden.add(operation.getId());
                    return;
                }
            }
            delegate.started(operation, event);
        }

        /**
         * Forwards progress unless its operation was hidden by this filter.
         *
         * @param id    plugin ID or operation identifier
         * @param event operation event to forward or hide
         */
        @Override
        public void progress(OperationIdentifier id, OperationProgressEvent event) {
            if (!hidden.contains(id)) delegate.progress(id, event);
        }

        /**
         * Releases operation tracking and forwards completion for visible operations.
         *
         * @param operation Gradle build operation
         * @param event     operation event to forward or hide
         */
        @Override
        public void finished(BuildOperationDescriptor operation, OperationFinishEvent event) {
            descendants.remove(operation.getId());
            if (!hidden.remove(operation.getId())) delegate.finished(operation, event);
        }

        /**
         * Recognizes Gradle's external resource read operation details.
         */
        private static boolean downloadDetails(@Nullable Object details) {
            if (details == null) return false;
            return hasType(details.getClass(), "org.gradle.internal.resource.ExternalResourceReadBuildOperationType$Details");
        }

        /**
         * Searches interfaces and superclasses for a named Gradle operation contract.
         */
        private static boolean hasType(Class<?> type, String name) {
            if (type.getName().equals(name)) return true;
            for (Class<?> contract : type.getInterfaces()) if (hasType(contract, name)) return true;
            return type.getSuperclass() != null && hasType(type.getSuperclass(), name);
        }
    }
}
