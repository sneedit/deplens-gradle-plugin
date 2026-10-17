/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */
package com.sneedit.deplens.console;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

/** Prepares a matching checksum edit without changing the wrapper or any other source. */
public final class WrapperChecksumUpdater {
    @FunctionalInterface
    interface ChecksumReader {
        String read(URI uri) throws IOException;
    }

    private final ChecksumReader reader;

    public WrapperChecksumUpdater(boolean offline) {
        this(new DistributionChecksumReader(offline)::read);
    }

    WrapperChecksumUpdater(ChecksumReader reader) {
        this.reader = reader;
    }

    /** Resolve the exact selected distribution's checksum before the shared source-edit preflight. */
    public List<SourceEditor.Change> changes(SourceEditor.Location version, String target) throws IOException {
        String text = version.snapshot();
        var properties = new WrapperProperties();
        try {
            properties.load(new StringReader(text));
        } catch (IllegalArgumentException failure) {
            throw new IOException("Ambiguous or unsupported wrapper properties; no edits applied.");
        }
        if (!properties.containsKey("distributionSha256Sum")) return List.of();
        var checksum = Pattern.compile("(?m)^[ \\t]*distributionSha256Sum[ \\t]*[=:][ \\t]*([^\\r\\n]*?)[ \\t]*\\r?$").matcher(text);
        if (!checksum.find()) throw new IOException("Wrapper checksum must be an unambiguous single-line literal; no edits applied.");
        var location = new SourceEditor.Location(version.file(), checksum.start(1), checksum.end(1), checksum.group(1), text);
        if (checksum.find() || !location.original().equals(properties.getProperty("distributionSha256Sum").stripTrailing()))
            throw new IOException("Wrapper checksum must be an unambiguous single-line literal; no edits applied.");
        // Parse the selected URL with Java properties escaping, without reserializing the source file.
        var selected = new WrapperProperties();
        try {
            selected.load(new StringReader(text.substring(0, version.start()) + target + text.substring(version.end())));
            URI distribution = URI.create(selected.getProperty("distributionUrl", ""));
            String path = distribution.getRawPath();
            if (path == null || !path.matches(".*/gradle-" + Pattern.quote(target) + "-(bin|all)\\.zip")
                    || distribution.getFragment() != null)
                throw new IllegalArgumentException("Unsupported distribution URL");
            String url = distribution.toASCIIString();
            int query = url.indexOf('?');
            URI sidecar = URI.create(query < 0 ? url + ".sha256"
                    : url.substring(0, query) + ".sha256" + url.substring(query));
            String metadata = reader.read(sidecar);
            String hash = metadata.strip();
            if (metadata.length() > 256 || !hash.matches("[a-fA-F0-9]{64}"))
                throw new IOException("Invalid wrapper distribution checksum; expected 64 hexadecimal characters. No edits applied.");
            return List.of(new SourceEditor.Change(location, hash.toLowerCase(Locale.ROOT)));
        } catch (IllegalArgumentException failure) {
            throw new IOException("Unsupported wrapper distribution URL; no edits applied.");
        }
    }

    /** Reject duplicate decoded keys, including escaped spellings that a text matcher could miss. */
    private static final class WrapperProperties extends Properties {
        @Override
        public synchronized Object put(Object key, Object value) {
            if ((key.equals("distributionUrl") || key.equals("distributionSha256Sum")) && containsKey(key))
                throw new IllegalArgumentException("Duplicate wrapper property");
            return super.put(key, value);
        }
    }
}
