/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.updates;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sneedit.deplens.inventory.InventoryEntry;
import com.sneedit.deplens.resolution.AnalysisModel;
import org.gradle.util.GradleVersion;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.sneedit.deplens.inventory.InventoryEntry.Status;
import static com.sneedit.deplens.updates.UpdateResults.addContext;
import static com.sneedit.deplens.updates.UpdateResults.unchecked;

/**
 * Checks the wrapper against the Gradle release feed, including offline mode and release flags.
 */
final class GradleReleaseChecker {
    /**
     * Keeps this utility class static-only.
     */
    private GradleReleaseChecker() {
    }

    /**
     * Reads the local or HTTPS release feed and records wrapper updates allowed by the policy.
     */
    static void check(AnalysisModel model, InventoryEntry e, RulePolicy policy) throws Exception {
        URI uri = URI.create(model.versionsUrl);
        String json;
        if ("file".equals(uri.getScheme())) json = Files.readString(Path.of(uri));
        else {
            if (model.offline) {
                unchecked(e, "Gradle release feed was not checked in offline mode");
                return;
            }
            if (!"https".equals(uri.getScheme())) throw new IllegalArgumentException("HTTPS release feed required");
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
            var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("Release feed unavailable");
            json = response.body();
        }
        JsonArray feed = JsonParser.parseString(json).getAsJsonArray();
        if (feed.isEmpty()) throw new IllegalStateException("Empty release feed");
        List<String> versions = new ArrayList<>();
        for (JsonElement item : feed) {
            JsonObject release = item.getAsJsonObject();
            String v = release.get("version").getAsString();
            boolean unstable = Versions.preRelease(v) || flag(release, "snapshot") || flag(release, "nightly") || flag(release, "releaseNightly")
                    || (release.has("rcFor") && !release.get("rcFor").getAsString().isBlank());
            if ((policy.preReleases || !unstable) && !flag(release, "broken")) versions.add(v);
        }
        versions.sort((a, b) -> GradleVersion.version(b).compareTo(GradleVersion.version(a)));
        if (versions.stream().anyMatch(policy::isIgnored)) e.statuses.add(Status.IGNORED);
        versions.removeIf(policy::isIgnored);
        String current = e.declaredSelectors.stream().findFirst().orElse(e.resolutions.iterator().next().version());
        addContext(e, "wrapper", current, current, versions.isEmpty() ? null : versions.getFirst(), List.of(), versions, policy);
    }

    /**
     * Reads an optional JSON boolean; absent and null fields count as false.
     */
    private static boolean flag(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean();
    }

}
