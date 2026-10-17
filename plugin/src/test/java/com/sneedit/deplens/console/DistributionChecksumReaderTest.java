/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */
package com.sneedit.deplens.console;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DistributionChecksumReaderTest {
    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String location) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn("a".repeat(64));
        when(response.headers()).thenReturn(HttpHeaders.of(location.isEmpty() ? Map.of() : Map.of("Location", List.of(location)), (a, b) -> true));
        return response;
    }

    @Test
    void followsHttpsRedirectsAndReturnsChecksum() throws Exception {
        HttpClient client = mock(HttpClient.class);
        var redirect = response(302, "https://cdn.example/gradle.zip.sha256");
        var success = response(200, "");
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(redirect, success);
        assertThat(new DistributionChecksumReader(false, client).read(URI.create("https://mirror.example/checksum")))
                .isEqualTo("a".repeat(64));
        verify(client, times(2)).send(any(HttpRequest.class), any());
    }

    @Test
    void rejectsDowngradesHttpErrorsAndRedirectLoopsWithoutLeakingCredentials() throws Exception {
        for (HttpResponse<String> response : List.of(response(302, "http://unsafe.example/checksum"), response(404, ""),
                response(302, "https://loop.example/checksum"))) {
            HttpClient client = mock(HttpClient.class);
            when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
            assertThatIOException().isThrownBy(() -> new DistributionChecksumReader(false, client)
                    .read(URI.create("https://user:secret@mirror.example/checksum?token=private")))
                    .withMessage("Cannot retrieve wrapper distribution checksum; no edits applied.")
                    .withNoCause();
        }
    }

    @Test
    void transportFailuresAreRedactedAndOfflineDoesNotSendRequests() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new IOException("secret token from a network error"));
        assertThatIOException().isThrownBy(() -> new DistributionChecksumReader(false, client).read(URI.create("https://mirror.example/checksum")))
                .withMessage("Cannot retrieve wrapper distribution checksum; no edits applied.").withNoCause();
        clearInvocations(client);
        assertThatIOException().isThrownBy(() -> new DistributionChecksumReader(true, client).read(URI.create("https://mirror.example/checksum")))
                .withMessageContaining("offline mode");
        verifyNoInteractions(client);
    }
}
