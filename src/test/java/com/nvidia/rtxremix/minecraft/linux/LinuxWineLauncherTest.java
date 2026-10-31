/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.linux;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinuxWineLauncherTest {

    @TempDir
    Path temp;

    @Test
    void convertsPathsClasspathAndNativesForWindowsJava() throws IOException {
        Path argFile = temp.resolve("args.txt");
        Files.writeString(argFile, "-Dfoo=\"a b\" --gameDir '/home/me/My Game'\n");
        List<String> converted = WineCommandLine.convert(List.of(
                        "-Xmx4G",
                        "-Djava.library.path=/tmp/linux-natives",
                        "-cp", "/a/one.jar:/b/two.jar",
                        "-javaagent:/agents/agent.jar",
                        "-Dfabric.gameJarPath=/mc/client.jar",
                        "net.fabricmc.loader.impl.launch.knot.KnotClient",
                        "@" + argFile),
                Path.of("/data/natives"), "/home/me", List.of());
        assertEquals(List.of(
                "-Djava.library.path=Z:/data/natives",
                "-Dorg.lwjgl.librarypath=Z:/data/natives",
                "-Duser.home=Z:/home/me",
                "-Xmx4G",
                "-cp", "Z:/a/one.jar;Z:/b/two.jar",
                "-javaagent:Z:/agents/agent.jar",
                "-Dfabric.gameJarPath=Z:/mc/client.jar",
                "net.fabricmc.loader.impl.launch.knot.KnotClient",
                "-Dfoo=a b", "--gameDir", "Z:/home/me/My Game"), converted);
    }

    @Test
    void splitsProcCmdlineOnNul() {
        byte[] raw = "java\0-cp\0/a b.jar\0Main\0".getBytes(StandardCharsets.UTF_8);
        assertEquals(List.of("java", "-cp", "/a b.jar", "Main"), WineCommandLine.parseProcCmdline(raw));
    }

    @Test
    void argFileQuotingRoundTrips() {
        String value = "C:\\path with \"quotes\"\nnext";
        assertEquals(List.of(value), WineCommandLine.parseArgFile(WineCommandLine.quoteForArgFile(value)));
    }

    @Test
    void forcesBundledD3d9AndKeepsOtherOverrides() {
        assertEquals("dxgi=n;d3d9=n;mscoree,mshtml=",
                LinuxWineLauncher.dllOverrides("dxgi=n;d3d9=b"));
        assertEquals("d3d9=n;mscoree,mshtml=", LinuxWineLauncher.dllOverrides(null));
    }

    @Test
    void rendererDefaultsYieldToUserSettings() throws IOException {
        Files.writeString(temp.resolve("user.conf"), "rtx.upscalerType = 4\n");
        String options = LinuxWineLauncher.rendererOptions("rtx.isReflexEnabled=true", temp);
        assertTrue(options.startsWith("rtx.isReflexEnabled=true;"));
        assertFalse(options.contains("rtx.upscalerType"));
        assertFalse(options.contains("rtx.isReflexEnabled=false"));
        assertTrue(options.contains("rtx.dlfg.enable=false"));
    }

    @Test
    void extractsTarWithSymlinksModesAndLongNames() throws IOException {
        String longName = "top/" + "d".repeat(120) + "/file.txt";
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        writeEntry(tar, "top/bin/", '5', 0755, "", new byte[0]);
        writeEntry(tar, "top/bin/wine", '0', 0755, "", "#!/bin/sh\n".getBytes());
        writeEntry(tar, "top/bin/wine64", '2', 0777, "wine", new byte[0]);
        writeEntry(tar, "././@LongLink", 'L', 0644, "", (longName + "\0").getBytes());
        writeEntry(tar, longName.substring(0, 99), '0', 0644, "", "hello".getBytes());
        tar.write(new byte[1024]);

        Path out = temp.resolve("out");
        TarExtractor.extract(new ByteArrayInputStream(tar.toByteArray()), out, 1);
        assertTrue(Files.isExecutable(out.resolve("bin/wine")));
        assertTrue(Files.isSymbolicLink(out.resolve("bin/wine64")));
        assertEquals(Path.of("wine"), Files.readSymbolicLink(out.resolve("bin/wine64")));
        assertEquals("hello", Files.readString(out.resolve(longName.substring(4))));
    }

    @Test
    void refusesTarEntriesThatEscape() throws IOException {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        writeEntry(tar, "top/../../evil", '0', 0644, "", "x".getBytes());
        tar.write(new byte[1024]);
        assertThrows(IOException.class, () -> TarExtractor.extract(
                new ByteArrayInputStream(tar.toByteArray()), temp.resolve("out"), 1));

        ByteArrayOutputStream link = new ByteArrayOutputStream();
        writeEntry(link, "top/link", '2', 0777, "../../etc/passwd", new byte[0]);
        link.write(new byte[1024]);
        assertThrows(IOException.class, () -> TarExtractor.extract(
                new ByteArrayInputStream(link.toByteArray()), temp.resolve("out2"), 1));
    }

    @Test
    void extractsZipStrippingTopDirectory() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("jdk-25-jre/bin/java.exe"));
            zip.write("MZ".getBytes());
            zip.closeEntry();
        }
        LinuxWineLauncher.extractZip(new ByteArrayInputStream(bytes.toByteArray()), temp.resolve("jre"), 1);
        assertEquals("MZ", Files.readString(temp.resolve("jre/bin/java.exe")));
    }

    private static void writeEntry(ByteArrayOutputStream out, String name, char type, int mode,
            String link, byte[] data) throws IOException {
        byte[] header = new byte[512];
        put(header, 0, name);
        put(header, 100, String.format("%07o", mode));
        put(header, 108, "0000000");
        put(header, 116, "0000000");
        put(header, 124, String.format("%011o", data.length));
        put(header, 136, "00000000000");
        header[156] = (byte) type;
        put(header, 157, link);
        put(header, 257, "ustar");
        for (int i = 148; i < 156; i++) header[i] = ' ';
        int sum = 0;
        for (byte b : header) sum += b & 0xff;
        put(header, 148, String.format("%06o\0 ", sum));
        out.write(header);
        out.write(data);
        int pad = (512 - data.length % 512) % 512;
        out.write(new byte[pad]);
    }

    private static void put(byte[] header, int offset, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }
}
