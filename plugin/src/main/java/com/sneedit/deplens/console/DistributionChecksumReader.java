/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */
package com.sneedit.deplens.console;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Reads local checksum sidecars or HTTPS metadata while respecting Gradle's offline mode. */
final class DistributionChecksumReader {
    private final boolean offline;
    private final HttpClient client;

    DistributionChecksumReader(boolean offline) {
        this(offline, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    DistributionChecksumReader(boolean offline, HttpClient client) {
        this.offline = offline;
        this.client = client;
    }

    String read(URI uri) throws IOException {
        try {
            if ("file".equals(uri.getScheme())) {
                try (var stream = Files.newInputStream(Path.of(uri))) {
                    byte[] content = stream.readNBytes(257);
                    if (content.length > 256) throw new IOException("Oversized checksum metadata");
                    return new String(content, StandardCharsets.US_ASCII);
                }
            }
            if (offline) throw new IOException("Wrapper checksum requires network access in offline mode; no edits applied.");
            for (int redirects = 0; redirects <= 5; redirects++) {
                if (!"https".equals(uri.getScheme()))
                    throw new IOException("Wrapper checksum requires HTTPS or a local file; no edits applied.");
                var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.US_ASCII));
                if (response.statusCode() == 200) return response.body();
                if (!java.util.Set.of(301, 302, 303, 307, 308).contains(response.statusCode())) break;
                String redirect = response.headers().firstValue("Location").orElse("");
                if (redirect.isBlank()) break;
                uri = uri.resolve(redirect);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Wrapper checksum retrieval interrupted; no edits applied.");
        } catch (IOException | IllegalArgumentException failure) {
            // URLs and nested transport errors may contain credentials. Keep diagnostics generic.
            if (offline && !"file".equals(uri.getScheme()))
                throw new IOException("Wrapper checksum requires network access in offline mode; no edits applied.");
            throw new IOException("Cannot retrieve wrapper distribution checksum; no edits applied.");
        }
        throw new IOException("Cannot retrieve wrapper distribution checksum; no edits applied.");
    }
}
