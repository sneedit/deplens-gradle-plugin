/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.updates;

import com.sneedit.deplens.inventory.InventoryEntry.ResolutionFailure;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;
import org.gradle.api.attributes.Attribute;
import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * Describes resolution failures without copying exception messages or repository URLs.
 */
public final class ResolutionFailureDetails {
    private ResolutionFailureDetails() {
    }

    /**
     * Only known deterministic leaves may be reused; transport, cancellation and unknown failures retry normally.
     */
    static boolean permanent(UnresolvedDependencyResult edge) {
        if (Thread.currentThread().isInterrupted()) return false;
        var causes = RepositoryBackoff.causes(java.util.List.of(edge.getFailure()));
        boolean recognizedLeaf = false;
        for (Throwable cause : causes) {
            if (cause instanceof java.io.IOException || cause instanceof InterruptedException
                    || cause instanceof java.util.concurrent.CancellationException
                    || cause instanceof org.gradle.api.BuildCancelledException
                    || RepositoryBackoff.invoke(cause, "getStatusCode") != null) return false;
            boolean children = cause.getCause() != null || cause.getSuppressed().length > 0;
            Object multiple = RepositoryBackoff.invoke(cause, "getCauses");
            if (multiple instanceof Iterable<?> iterable)
                for (Object child : iterable) if (child instanceof Throwable) children = true;
            if (!children) {
                if (!cause.getClass().getSimpleName().equals("ModuleVersionNotFoundException")
                        && !ModuleVersionQueries.variantIncompatible(cause)) return false;
                recognizedLeaf = true;
            }
        }
        return recognizedLeaf;
    }

    /**
     * Identifies the failed module and explains its structured failure, including JVM/format mismatches.
     */
    public static ResolutionFailure describe(String configuration, UnresolvedDependencyResult edge) {
        String dependency = edge.getAttempted() instanceof ModuleComponentSelector module
                ? module.getGroup() + ":" + module.getModule() + (module.getVersion().isBlank() ? "" : ":" + module.getVersion())
                : "non-module dependency";
        return describe(configuration, dependency, edge.getFailure());
    }

    /**
     * Also describes repository exceptions raised before an unresolved graph edge is available.
     */
    public static ResolutionFailure describe(String configuration, String dependency, Throwable failure) {
        String httpFailures = httpFailures(failure);
        if (!httpFailures.isEmpty())
            return new ResolutionFailure(configuration, dependency, "repository access failed (" + httpFailures + ")");
        if (ModuleVersionQueries.versionRejected(failure))
            return new ResolutionFailure(configuration, dependency, "version rejected by constraints or selection rules");
        String reason = "resolution failed";
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            Object detail = invoke(cause, "getFailure");
            String type = cause.getClass().getSimpleName();
            String detailType = detail == null ? "" : detail.getClass().getSimpleName();
            if (type.equals("VariantSelectionByAttributesException") || detailType.equals("NoCompatibleVariantsFailure")
                    || detailType.equals("IncompatibleGraphVariantFailure") || detailType.equals("IncompatibleResolutionFailure")) {
                String mismatch = detail == null ? "" : attributeMismatches(detail);
                return new ResolutionFailure(configuration, dependency,
                        "no compatible variant" + (mismatch.isEmpty() ? "" : " (" + mismatch + ")"));
            }
            if (type.equals("ModuleVersionNotFoundException")) {
                // Recognize Gradle's fixed NoRepositoriesResolver diagnostic; never render the message itself.
                String message = cause.getMessage();
                reason = message != null && message.startsWith("Cannot resolve external dependency ")
                        && message.endsWith(" because no repositories are defined.")
                        ? "no repositories configured for this classpath"
                        : "module/version not found in configured repositories";
            } else if (type.contains("SSL") || type.contains("Certificate"))
                reason = "repository TLS/certificate failure";
            else if (type.equals("UnknownHostException")) reason = "repository host could not be resolved";
            else if (type.contains("Timeout")) reason = "repository request timed out";
            else if (type.contains("Parse") || type.contains("MetaDataParse")) reason = "invalid module metadata";
            else if (reason.equals("resolution failed") && (type.contains("Http") || type.contains("Resource")))
                reason = "repository access failed";
        }
        return new ResolutionFailure(configuration, dependency, reason);
    }

    /**
     * Extracts numeric HTTP statuses from wrapped, multi-cause and suppressed failures.
     * Only codes and fixed English labels are rendered, never server messages or request URLs.
     */
    private static String httpFailures(Throwable failure) {
        Set<Integer> statuses = new TreeSet<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable cause = pending.removeFirst();
            if (!visited.add(cause)) continue;
            Object status = invoke(cause, "getStatusCode");
            if (status instanceof Integer code && code >= 100 && code <= 599) statuses.add(code);
            Throwable nested = cause.getCause();
            if (nested != null) pending.add(nested);
            Collections.addAll(pending, cause.getSuppressed());
            Object causes = invoke(cause, "getCauses");
            if (causes instanceof Iterable<?> multiple)
                for (Object item : multiple)
                    if (item instanceof Throwable nestedCause) pending.add(nestedCause);
        }
        return statuses.stream().map(code -> {
            String label = switch (code) {
                case 400 -> "bad request";
                case 401 -> "unauthorized";
                case 403 -> "forbidden";
                case 404 -> "not found";
                case 408 -> "request timeout";
                case 429 -> "rate limit";
                case 500 -> "internal server error";
                case 502 -> "bad gateway";
                case 503 -> "service unavailable";
                case 504 -> "gateway timeout";
                default -> "";
            };
            return "HTTP " + code + (label.isEmpty() ? "" : ": " + label);
        }).collect(java.util.stream.Collectors.joining("; "));
    }

    /**
     * Reads Gradle's assessed variants where supported; older versions retain the failure category.
     */
    private static String attributeMismatches(Object detail) {
        Set<String> differences = new TreeSet<>();
        Object candidates = invoke(detail, "getCandidates");
        if (candidates instanceof Iterable<?> variants) {
            for (Object candidate : variants) {
                if (candidate == null) continue;
                Object incompatible = invoke(candidate, "getIncompatibleAttributes");
                if (!(incompatible instanceof Iterable<?> attributes)) continue;
                for (Object mismatch : attributes) {
                    if (mismatch == null) continue;
                    Object attribute = invoke(mismatch, "getAttribute");
                    if (!(attribute instanceof Attribute<?> key)) continue;
                    String label = switch (key.getName()) {
                        case "org.gradle.jvm.version" -> "Java";
                        case "org.gradle.libraryelements" -> "artifact format";
                        default -> "";
                    };
                    Object requested = invoke(mismatch, "getRequested");
                    Object provided = invoke(mismatch, "getProvided");
                    if (!label.isEmpty() && requested instanceof String wanted && provided instanceof String offered
                            && wanted.matches("[A-Za-z0-9._+-]+") && offered.matches("[A-Za-z0-9._+-]+"))
                        differences.add(label + ": required " + wanted + ", provided " + offered);
                }
            }
        }
        return String.join("; ", differences);
    }

    /**
     * Adapts optional structured Gradle failure APIs without rendering their raw messages.
     */
    private static @Nullable Object invoke(Object value, String method) {
        try {
            return value.getClass().getMethod(method).invoke(value);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
