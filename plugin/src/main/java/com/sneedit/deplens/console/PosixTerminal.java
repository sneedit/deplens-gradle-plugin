/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.console;

import org.jspecify.annotations.Nullable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Opens the invoking Gradle client's terminal, independent of daemon stdin.
 */
public final class PosixTerminal implements AutoCloseable {
    private final File device;
    private final String saved;
    // Gradle 8.8 may forward the final bytes from a read already pending before raw mode.
    private final InputStream forwarded = System.in instanceof PipedInputStream ? System.in : InputStream.nullInputStream();
    private @Nullable FileInputStream stream;
    private @Nullable PrintWriter writer;
    public final int width, height;

    /**
     * Opens the terminal device and switches it to raw input for key-by-key selection.
     *
     * @param path POSIX terminal device path, such as /dev/ttys001
     * @throws IOException if a source, report or terminal operation fails
     */
    public PosixTerminal(@Nullable String path) throws IOException {
        if (path == null || !path.matches("/dev/(?:tty[\\w]*|pts/[0-9]+)"))
            throw new IOException("A terminal is required. Run ./gradlew depLensInteractiveUpdate from a POSIX terminal.");
        device = Path.of(path).toRealPath().toFile();
        saved = stty("-g").trim();
        String[] size = stty("size").trim().split("\\s+");
        height = size.length == 2 ? Integer.parseInt(size[0]) : 24;
        width = size.length == 2 ? Integer.parseInt(size[1]) : 100;
        try {
            writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(device), StandardCharsets.UTF_8), true);
            stty("-icanon", "-echo", "-isig", "min", "0", "time", "1");
            // Older Gradle clients eagerly read stdin. Let the pending read reach EOF
            // on the terminal timeout before opening our own reader.
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Terminal interrupted", interrupted);
            }
            stream = new FileInputStream(device);
        } catch (IOException failure) {
            close();
            throw failure;
        }
    }

    /**
     * Finds the CLI connected to this daemon; never guesses from other open terminals.
     */
    public static String discover() throws IOException {
        long daemon = ProcessHandle.current().pid();
        String ownTerminal = terminalFor(daemon);
        if (!ownTerminal.isEmpty() && !ownTerminal.equals("??") && !ownTerminal.equals("?"))
            return "/dev/" + ownTerminal;
        var processes = new ArrayList<String>();
        processes.add(Long.toString(daemon));
        try (var handles = ProcessHandle.allProcesses()) {
            handles.filter(handle -> handle.info().commandLine().orElse("").matches(
                            ".*(?:org\\.gradle\\.wrapper\\.GradleWrapperMain|org\\.gradle\\.launcher\\.GradleMain|[^ ]*(?:gradle-wrapper|gradle-gradle-cli-main-[^ /]+)\\.jar)(?: .*|$)"))
                    .forEach(handle -> processes.add(Long.toString(handle.pid())));
        }
        if (processes.size() == 1) throw new IOException("A terminal is required. No Gradle CLI is connected.");
        if (Files.isDirectory(Path.of("/proc/self/fd"))) {
            var sockets = new LinkedHashMap<String, String>();
            var owned = socketInodes(daemon);
            for (String table : List.of("/proc/net/tcp", "/proc/net/tcp6")) {
                if (!Files.exists(Path.of(table))) continue;
                for (String line : Files.readAllLines(Path.of(table))) {
                    String[] fields = line.trim().split("\\s+");
                    if (fields.length > 9 && fields[3].equals("01"))
                        sockets.put(fields[1] + "->" + fields[2], fields[9]);
                }
            }
            var clients = new LinkedHashSet<Long>();
            for (var socket : sockets.entrySet()) {
                if (!owned.contains(socket.getValue())) continue;
                String[] pair = socket.getKey().split("->", 2);
                String peer = sockets.get(pair[1] + "->" + pair[0]);
                if (peer == null) continue;
                for (String candidate : processes) {
                    long client = Long.parseLong(candidate);
                    if (client != daemon && socketInodes(client).contains(peer)) clients.add(client);
                }
            }
            return terminalOfClient(clients);
        }
        String connections = command("lsof", "-nP", "-a", "-p", String.join(",", processes),
                "-iTCP", "-sTCP:ESTABLISHED", "-Fpn");
        var endpoints = new LinkedHashMap<String, Long>();
        long pid = -1;
        for (String line : connections.split("\\R")) {
            if (line.startsWith("p")) pid = Long.parseLong(line.substring(1));
            if (line.startsWith("n") && line.contains("->")) endpoints.put(line.substring(1), pid);
        }
        var clients = new LinkedHashSet<Long>();
        for (var endpoint : endpoints.entrySet()) {
            if (endpoint.getValue() != daemon) continue;
            String[] pair = endpoint.getKey().split("->", 2);
            Long client = endpoints.get(pair[1] + "->" + pair[0]);
            if (client != null && client != daemon) clients.add(client);
        }
        return terminalOfClient(clients);
    }

    private static Set<String> socketInodes(long pid) throws IOException {
        var sockets = new LinkedHashSet<String>();
        try (var descriptors = Files.list(Path.of("/proc/" + pid + "/fd"))) {
            for (Path descriptor : descriptors.toList()) {
                try {
                    String link = Files.readSymbolicLink(descriptor).toString();
                    if (link.startsWith("socket:[")) sockets.add(link.substring(8, link.length() - 1));
                } catch (IOException ignored) {
                    // Descriptors can close while inspecting a running CLI.
                }
            }
        } catch (java.nio.file.NoSuchFileException ignored) {
            // A different Gradle invocation may have just exited.
        }
        return sockets;
    }

    private static String terminalFor(long pid) throws IOException {
        if (Files.isDirectory(Path.of("/proc/self/fd"))) {
            String path = Files.readSymbolicLink(Path.of("/proc/" + pid + "/fd/0")).toString();
            return path.matches("/dev/(?:tty[\\w]*|pts/[0-9]+)") ? path.substring(5) : "?";
        }
        return command("ps", "-p", Long.toString(pid), "-o", "tty=").trim();
    }

    private static String terminalOfClient(Set<Long> clients) throws IOException {
        if (clients.size() != 1) throw new IOException("Cannot identify the invoking Gradle terminal unambiguously.");
        String tty = terminalFor(clients.iterator().next());
        if (tty.isEmpty() || tty.equals("??") || tty.equals("?"))
            throw new IOException("A terminal is required. Run ./gradlew depLensInteractiveUpdate from a POSIX terminal.");
        return "/dev/" + tty;
    }

    private static String command(String... arguments) throws IOException {
        Process process = new ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Terminal discovery timed out");
            }
            if (process.exitValue() != 0)
                throw new IOException("Cannot discover the Gradle terminal using " + arguments[0]);
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Terminal discovery interrupted", interrupted);
        }
    }

    /**
     * Provides the terminal input stream.
     *
     * @return terminal byte stream, using zero for a read timeout
     */
    public InputStream input() {
        return new InputStream() {
            /**
             * Reads the next terminal byte; a timeout becomes zero so Escape can be distinguished from arrows.
             *
             * @return next terminal byte, or zero when the terminal read times out
             *
             * @throws IOException if a source, report or terminal operation fails
             */
            @Override
            public int read() throws IOException {
                if (forwarded.available() > 0) return forwarded.read();
                int key = Objects.requireNonNull(stream, "Terminal input is not open").read();
                return key < 0 ? 0 : key; // VTIME expired; Escape can be distinguished from an arrow sequence.
            }
        };
    }

    /**
     * Provides the terminal writer.
     *
     * @return open terminal writer
     */
    public PrintWriter output() {
        return Objects.requireNonNull(writer, "Terminal output is not open");
    }

    /**
     * Runs stty against the terminal device and returns its output.
     */
    private String stty(String... arguments) throws IOException {
        List<String> command = new ArrayList<>(List.of("stty"));
        command.addAll(Arrays.asList(arguments));
        Process process = new ProcessBuilder(command).redirectInput(device).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Terminal configuration timed out");
            }
            String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) throw new IOException("Cannot access the supplied terminal");
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Terminal interrupted", interrupted);
        }
    }

    /**
     * Restores the terminal settings and closes the device streams.
     *
     * @throws IOException if a source, report or terminal operation fails
     */
    @Override
    public void close() throws IOException {
        try {
            stty(saved);
        } finally {
            if (writer != null) writer.close();
            if (stream != null) stream.close();
        }
    }
}
