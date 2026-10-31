/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.linux;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * Extracts an uncompressed POSIX/GNU tar stream, keeping symbolic links, hard
 * links, and executable bits, which Wine needs and a ZIP entry cannot carry.
 * Entries are rooted below the destination after stripping the leading path
 * component; an entry or link target that would escape it is refused.
 */
final class TarExtractor {

    private static final int BLOCK = 512;

    private TarExtractor() {
    }

    static void extract(InputStream in, Path destination, int stripComponents) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        Files.createDirectories(root);
        byte[] header = new byte[BLOCK];
        String longName = null;
        String longLink = null;
        String paxPath = null;
        String paxLink = null;
        while (true) {
            readFully(in, header);
            if (isZeroBlock(header)) {
                return;
            }
            String name = string(header, 0, 100);
            String prefix = string(header, 345, 155);
            if (!prefix.isEmpty() && "ustar".equals(string(header, 257, 5))) {
                name = prefix + "/" + name;
            }
            int mode = (int) octal(header, 100, 8);
            long size = octal(header, 124, 12);
            char type = (char) header[156];
            String link = string(header, 157, 100);

            if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                byte[] data = readData(in, size);
                if (type == 'L') {
                    longName = string(data, 0, data.length);
                } else if (type == 'K') {
                    longLink = string(data, 0, data.length);
                } else if (type == 'x') {
                    String[] pax = parsePax(data);
                    if (pax[0] != null) paxPath = pax[0];
                    if (pax[1] != null) paxLink = pax[1];
                }
                continue;
            }
            if (paxPath != null) name = paxPath;
            else if (longName != null) name = longName;
            if (paxLink != null) link = paxLink;
            else if (longLink != null) link = longLink;
            longName = longLink = paxPath = paxLink = null;

            Path target = resolve(root, name, stripComponents);
            if (target == null) {
                skip(in, size);
                continue;
            }
            switch (type) {
                case '5' -> Files.createDirectories(target);
                case '2' -> {
                    Path linkTarget = target.getParent().resolve(link).normalize();
                    if (!linkTarget.startsWith(root)) {
                        throw new IOException("Tar symlink escapes destination: " + name + " -> " + link);
                    }
                    Files.createDirectories(target.getParent());
                    Files.deleteIfExists(target);
                    Files.createSymbolicLink(target, Path.of(link));
                }
                case '1' -> {
                    Path source = resolve(root, link, stripComponents);
                    if (source == null) {
                        throw new IOException("Tar hard link escapes destination: " + name + " -> " + link);
                    }
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    setMode(target, mode);
                }
                case '0', '\0', '7' -> {
                    Files.createDirectories(target.getParent());
                    Files.deleteIfExists(target);
                    try (OutputStream out = Files.newOutputStream(target)) {
                        copy(in, out, size);
                    }
                    skipPadding(in, size);
                    setMode(target, mode);
                }
                default -> skip(in, size); // devices and FIFOs are not used by the payload
            }
        }
    }

    /** Returns the stripped destination path, null for an entry stripped to nothing, or throws on escape. */
    static Path resolve(Path root, String name, int stripComponents) throws IOException {
        String[] parts = name.split("/");
        StringBuilder rest = new StringBuilder();
        int skipped = 0;
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (skipped < stripComponents) {
                skipped++;
                continue;
            }
            if (!rest.isEmpty()) rest.append('/');
            rest.append(part);
        }
        if (rest.isEmpty()) {
            return null;
        }
        Path target = root.resolve(rest.toString()).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IOException("Tar entry escapes destination: " + name);
        }
        return target;
    }

    private static void setMode(Path file, int mode) throws IOException {
        Set<PosixFilePermission> permissions = EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);
        if ((mode & 0111) != 0) {
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
        }
        Files.setPosixFilePermissions(file, permissions);
    }

    private static String[] parsePax(byte[] data) {
        String[] result = new String[2];
        String text = new String(data, StandardCharsets.UTF_8);
        int pos = 0;
        while (pos < text.length()) {
            int space = text.indexOf(' ', pos);
            if (space < 0) break;
            int length = Integer.parseInt(text.substring(pos, space));
            String record = text.substring(space + 1, pos + length - 1);
            int eq = record.indexOf('=');
            if (eq > 0) {
                String key = record.substring(0, eq);
                String value = record.substring(eq + 1);
                if (key.equals("path")) result[0] = value;
                else if (key.equals("linkpath")) result[1] = value;
            }
            pos += length;
        }
        return result;
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) return false;
        }
        return true;
    }

    private static String string(byte[] data, int offset, int length) {
        int end = offset;
        while (end < offset + length && end < data.length && data[end] != 0) end++;
        return new String(data, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] data, int offset, int length) {
        if ((data[offset] & 0x80) != 0) { // GNU base-256 size
            long value = data[offset] & 0x7f;
            for (int i = 1; i < length; i++) value = (value << 8) | (data[offset + i] & 0xff);
            return value;
        }
        long value = 0;
        for (int i = offset; i < offset + length; i++) {
            byte b = data[i];
            if (b == 0 || b == ' ') {
                if (value != 0) break;
                continue;
            }
            value = value * 8 + (b - '0');
        }
        return value;
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        if (size > 1 << 20) throw new IOException("Oversized tar metadata entry");
        byte[] data = new byte[(int) size];
        readFully(in, data);
        skipPadding(in, size);
        return data;
    }

    private static void copy(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buffer = new byte[1 << 16];
        long remaining = size;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new EOFException("Truncated tar entry");
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void skip(InputStream in, long size) throws IOException {
        in.skipNBytes(size);
        skipPadding(in, size);
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long padding = (BLOCK - (size % BLOCK)) % BLOCK;
        in.skipNBytes(padding);
    }

    private static void readFully(InputStream in, byte[] buffer) throws IOException {
        int off = 0;
        while (off < buffer.length) {
            int read = in.read(buffer, off, buffer.length - off);
            if (read < 0) throw new EOFException("Truncated tar stream");
            off += read;
        }
    }
}
