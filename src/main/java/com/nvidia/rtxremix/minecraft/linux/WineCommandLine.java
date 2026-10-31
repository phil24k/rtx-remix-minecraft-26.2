/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.linux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns the Linux JVM's own command line into the equivalent command line for
 * Windows Java under Wine: argument files are expanded, absolute paths become
 * {@code Z:} drive paths, path lists switch to {@code ;}, and the LWJGL native
 * directory is replaced with the bundled Windows natives.
 */
final class WineCommandLine {

    private static final Set<String> CLASSPATH_FLAGS =
            Set.of("-cp", "-classpath", "--class-path", "-p", "--module-path");

    private WineCommandLine() {
    }

    /** Reads NUL-separated {@code /proc/self/cmdline}; the first element is the executable. */
    static List<String> parseProcCmdline(byte[] raw) {
        List<String> result = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == 0) {
                result.add(new String(raw, start, i - start, StandardCharsets.UTF_8));
                start = i + 1;
            }
        }
        if (start < raw.length) {
            result.add(new String(raw, start, raw.length - start, StandardCharsets.UTF_8));
        }
        return result;
    }

    /**
     * Splits a Java {@code @argfile}: whitespace separates arguments, single or
     * double quotes group them, and a backslash escapes the next character inside
     * double quotes.
     */
    static List<String> parseArgFile(String content) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inToken = false;
        char quote = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else if (c == '\\' && quote == '"' && i + 1 < content.length()) {
                    char next = content.charAt(++i);
                    current.append(switch (next) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        default -> next;
                    });
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    result.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
            } else {
                current.append(c);
                inToken = true;
            }
        }
        if (inToken) {
            result.add(current.toString());
        }
        return result;
    }

    static String windowsPath(String value) {
        return value.startsWith("/") ? "Z:" + value : value;
    }

    static String windowsPathList(String value) {
        String[] parts = value.split(":");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(';');
            }
            out.append(windowsPath(part));
        }
        return out.toString();
    }

    /**
     * Converts JVM arguments (everything after the executable) for Windows Java.
     *
     * @param arguments the Linux JVM arguments, possibly containing {@code @argfile}s
     * @param nativesDir the host directory holding the Windows LWJGL natives
     * @param homeDir the host home directory, passed as {@code user.home}
     * @param extraJvmArguments JVM options inserted before the converted ones
     */
    static List<String> convert(List<String> arguments, Path nativesDir, String homeDir,
            List<String> extraJvmArguments) throws IOException {
        List<String> expanded = new ArrayList<>();
        for (String value : arguments) {
            if (value.startsWith("@") && !value.startsWith("@@")) {
                expanded.addAll(parseArgFile(Files.readString(Path.of(value.substring(1)))));
            } else {
                expanded.add(value);
            }
        }
        String natives = windowsPath(nativesDir.toAbsolutePath().toString());
        List<String> result = new ArrayList<>();
        // JVM options must precede the main class, so they go first.
        result.add("-Djava.library.path=" + natives);
        result.add("-Dorg.lwjgl.librarypath=" + natives);
        result.add("-Duser.home=" + windowsPath(homeDir));
        result.addAll(extraJvmArguments);
        boolean classpathNext = false;
        for (String value : expanded) {
            if (classpathNext) {
                result.add(windowsPathList(value));
                classpathNext = false;
            } else if (CLASSPATH_FLAGS.contains(value)) {
                result.add(value);
                classpathNext = true;
            } else if (value.startsWith("-Djava.library.path=")
                    || value.startsWith("-Dorg.lwjgl.librarypath=")
                    || value.startsWith("-Duser.home=")) {
                // Replaced by the Windows values above.
                continue;
            } else if (value.startsWith("--class-path=")) {
                result.add("--class-path=" + windowsPathList(value.substring("--class-path=".length())));
            } else if (value.startsWith("-javaagent:")) {
                result.add("-javaagent:" + windowsPath(value.substring("-javaagent:".length())));
            } else if (value.startsWith("-D") && value.indexOf('=') > 0) {
                int eq = value.indexOf('=');
                String content = value.substring(eq + 1);
                result.add(value.substring(0, eq + 1)
                        + (content.startsWith("/") ? windowsPathList(content) : content));
            } else {
                result.add(windowsPath(value));
            }
        }
        return result;
    }

    /** Quotes one argument for a Java {@code @argfile}. */
    static String quoteForArgFile(String value) {
        return '"' + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + '"';
    }
}
