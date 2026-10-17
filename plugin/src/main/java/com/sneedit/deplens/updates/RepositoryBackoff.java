/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.updates;

import com.sneedit.deplens.resolution.ResolutionContext;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;
import org.gradle.api.logging.Logging;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;

/**
 * Invocation-local cooldown for HTTP 429, without bypassing Gradle's authenticated transport.
 */
final class RepositoryBackoff {
    record Attempt<T extends @Nullable Object>(T value, List<UnresolvedDependencyResult> failures) {
    }

    private volatile Instant resumeAt = Instant.MIN;
    private int consecutiveRateLimits;

    /**
     * Retries only temporary rate limits, rebuilding the Gradle session for each retry.
     * All other failures retain their original outcome and diagnostics.
     */
    <T extends @Nullable Object> Attempt<T> execute(ResolutionContext original, Function<ResolutionContext, Attempt<T>> query) {
        ResolutionContext context = original.current();
        for (int attempt = 1; ; attempt++) {
            awaitCooldown();
            Attempt<T> result;
            try {
                result = query.apply(context);
            } catch (RuntimeException failure) {
                if (!rateLimited(List.of(failure))) throw failure;
                pause(List.of(failure));
                if (attempt >= 3) {
                    context.fresh();
                    throw failure;
                }
                context = context.fresh();
                continue;
            }
            List<Throwable> failures = result.failures().stream().map(edge -> (Throwable) edge.getFailure()).toList();
            if (!rateLimited(failures)) {
                synchronized (this) {
                    consecutiveRateLimits = 0;
                }
                return result;
            }
            pause(failures);
            if (attempt >= 3) {
                context.fresh();
                return result;
            }
            context = context.fresh();
        }
    }

    private synchronized void pause(List<Throwable> failures) {
        consecutiveRateLimits++;
        Duration supplied = null;
        for (Throwable failure : causes(failures)) {
            Duration value = retryAfter(failure);
            if (value != null && (supplied == null || value.compareTo(supplied) > 0)) supplied = value;
        }
        Duration delay = supplied == null ? Duration.ofSeconds(30L << Math.min(consecutiveRateLimits - 1, 2)) : supplied;
        Instant deadline = Instant.now().plus(delay);
        if (deadline.isAfter(resumeAt)) resumeAt = deadline;
        Logging.getLogger(RepositoryBackoff.class).warn(
                "DepLens: HTTP 429 rate limit. {} Pausing repository queries for {} seconds; up to 3 attempts per query.",
                supplied == null ? "Retry-After is not exposed by Gradle; using exponential backoff."
                        : "Respecting Retry-After.", delay.toSeconds());
    }

    private void awaitCooldown() {
        while (Instant.now().isBefore(resumeAt)) {
            Duration remaining = Duration.between(Instant.now(), resumeAt);
            long millis = remaining.compareTo(Duration.ofSeconds(1)) > 0 ? 1000 : Math.max(1, remaining.toMillis());
            try {
                Thread.sleep(millis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Repository retry interrupted", interrupted);
            }
        }
    }

    static boolean rateLimited(List<Throwable> failures) {
        return causes(failures).stream().anyMatch(cause -> Integer.valueOf(429).equals(invoke(cause, "getStatusCode")));
    }

    /**
     * Gradle versions differ in what response information survives exception wrapping.
     * Never infer a Retry-After value from a raw exception message.
     */
    private static @Nullable Duration retryAfter(Throwable failure) {
        Object value = invoke(failure, "getRetryAfterSeconds");
        if (value == null) value = invoke(failure, "getRetryAfter");
        if (value == null) {
            Object headers = invoke(failure, "getResponseHeaders");
            if (!(headers instanceof Map<?, ?>)) headers = invoke(failure, "getHeaders");
            if (headers instanceof Map<?, ?> map)
                for (var header : map.entrySet())
                    if (header.getKey() instanceof String key && key.equalsIgnoreCase("Retry-After")) {
                        value = header.getValue();
                        if (value instanceof List<?> list) value = list.isEmpty() ? null : list.getFirst();
                        break;
                    }
        }
        if (value instanceof Duration duration) return validDelay(duration);
        String text = value instanceof Number number ? number.toString() : value instanceof String string ? string.trim() : "";
        try {
            if (text.matches("[0-9]+")) return validDelay(Duration.ofSeconds(Long.parseLong(text)));
            if (!text.isEmpty()) {
                Duration delay = Duration.between(Instant.now(), ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                return validDelay(delay);
            }
        } catch (NumberFormatException | java.time.DateTimeException ignored) {
            // A malformed header uses the explicitly labelled fallback.
        }
        return null;
    }

    private static @Nullable Duration validDelay(Duration duration) {
        if (duration.isNegative()) return Duration.ZERO;
        try {
            Instant.now().plus(duration);
            return duration;
        } catch (java.time.DateTimeException | ArithmeticException ignored) {
            return null;
        }
    }

    static List<Throwable> causes(List<Throwable> failures) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        var pending = new ArrayDeque<>(failures);
        var result = new java.util.ArrayList<Throwable>();
        while (!pending.isEmpty()) {
            Throwable cause = pending.removeFirst();
            if (!visited.add(cause)) continue;
            result.add(cause);
            Throwable nested = cause.getCause();
            if (nested != null) pending.add(nested);
            Collections.addAll(pending, cause.getSuppressed());
            Object multiple = invoke(cause, "getCauses");
            if (multiple instanceof Iterable<?> iterable)
                for (Object item : iterable)
                    if (item instanceof Throwable other) pending.add(other);
        }
        return result;
    }

    static @Nullable Object invoke(Object value, String method) {
        try {
            return value.getClass().getMethod(method).invoke(value);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
