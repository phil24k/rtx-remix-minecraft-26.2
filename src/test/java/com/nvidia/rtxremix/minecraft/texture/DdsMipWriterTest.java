/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 rtx-remix-minecraft-radeon contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DdsMipWriterTest {

    private static final int HEADER_BYTES = 148;

    @Test
    void octahedralNormalsRoundTripThroughTheRuntimeDecode() {
        double[][] normals = {
                {0, 0, 1}, {0.6, 0, 0.8}, {0, 0.6, 0.8}, {-0.48, 0.36, 0.8}, {0.7, -0.7, 0.14},
        };
        for (double[] n : normals) {
            byte[] rgba = {unorm(n[0]), unorm(n[1]), unorm(n[2]), (byte) 255};
            byte[] oct = DdsMipWriter.encodeOctahedral(rgba, 1, false);
            double[] decoded = decodeHemisphereOctahedral(oct[0] & 0xFF, oct[1] & 0xFF);
            for (int k = 0; k < 3; k++) {
                assertEquals(n[k], decoded[k], 0.03, "component " + k + " of " + java.util.Arrays.toString(n));
            }
        }
    }

    @Test
    void directXNormalsFlipGreenIntoTheRuntimeTangentFrame() {
        byte[] rgba = {unorm(0), unorm(0.6), unorm(0.8), (byte) 255};
        byte[] oct = DdsMipWriter.encodeOctahedral(rgba, 1, true);
        double[] decoded = decodeHemisphereOctahedral(oct[0] & 0xFF, oct[1] & 0xFF);
        assertEquals(-0.6, decoded[1], 0.03);
        assertEquals(0.8, decoded[2], 0.03);
    }

    @Test
    void normalChainFlattensOpposingBumpsAndStaysTwoChannel(@TempDir Path dir) throws Exception {
        // A 2x1 map whose texels lean in opposite directions averages to flat.
        byte[] rgba = {
                unorm(0.6), unorm(0), unorm(0.8), (byte) 255,
                unorm(-0.6), unorm(0), unorm(0.8), (byte) 255,
        };
        Path path = dir.resolve("n.dds");
        DdsMipWriter.writeOctahedralNormals(path, rgba, 2, 1, true);

        ByteBuffer file = ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(HEADER_BYTES + 2 * 2 + 2, file.capacity());
        assertEquals(2, file.getInt(28), "mip count");
        assertEquals(DdsMipWriter.DXGI_FORMAT_R8G8_UNORM, file.getInt(128));

        double[] top = decodeHemisphereOctahedral(file.get(HEADER_BYTES + 4) & 0xFF,
                file.get(HEADER_BYTES + 5) & 0xFF);
        assertEquals(0.0, top[0], 0.02);
        assertEquals(0.0, top[1], 0.02);
        assertEquals(1.0, top[2], 0.02);
    }

    @Test
    void heightChainKeepsTheHighestTexel(@TempDir Path dir) throws Exception {
        byte[] height = {10, (byte) 200, 30, 40};
        Path path = dir.resolve("h.dds");
        DdsMipWriter.write(path, height, 2, 2, 1, DdsMipWriter.DXGI_FORMAT_R8_UNORM, DdsMipWriter.Filter.MAX);

        byte[] file = Files.readAllBytes(path);
        assertEquals(HEADER_BYTES + 4 + 1, file.length);
        assertEquals(200, file[HEADER_BYTES + 4] & 0xFF);
    }

    @Test
    void colourChainAverages(@TempDir Path dir) throws Exception {
        byte[] data = {0, 100, (byte) 200, 100};
        Path path = dir.resolve("c.dds");
        DdsMipWriter.write(path, data, 2, 2, 1, DdsMipWriter.DXGI_FORMAT_R8_UNORM, DdsMipWriter.Filter.AVERAGE);

        byte[] file = Files.readAllBytes(path);
        assertEquals(100, file[HEADER_BYTES + 4] & 0xFF);
        assertTrue(Files.notExists(dir.resolve("c.dds.tmp")));
    }

    @Test
    void oddEdgesContributeToColourAndHeightMips(@TempDir Path dir) throws Exception {
        byte[] pixels = {0, 0, (byte) 240};
        Path colour = dir.resolve("odd-colour.dds");
        DdsMipWriter.write(colour, pixels, 3, 1, 1, DdsMipWriter.DXGI_FORMAT_R8_UNORM,
                DdsMipWriter.Filter.AVERAGE);
        assertEquals(80, Files.readAllBytes(colour)[HEADER_BYTES + 3] & 0xFF);
        Path height = dir.resolve("odd-height.dds");
        DdsMipWriter.write(height, pixels, 1, 3, 1, DdsMipWriter.DXGI_FORMAT_R8_UNORM,
                DdsMipWriter.Filter.MAX);
        assertEquals(240, Files.readAllBytes(height)[HEADER_BYTES + 3] & 0xFF);
    }

    @Test
    void rejectsInvalidPixelsBeforeWritingTheCache(@TempDir Path dir) {
        Path path = dir.resolve("invalid.dds");
        assertThrows(IllegalArgumentException.class, () -> DdsMipWriter.write(path,
                new byte[1], 2, 2, 1, DdsMipWriter.DXGI_FORMAT_R8_UNORM, DdsMipWriter.Filter.AVERAGE));
        assertThrows(IllegalArgumentException.class, () -> DdsMipWriter.write(path,
                new byte[1], 0, 1, 1, DdsMipWriter.DXGI_FORMAT_R8_UNORM, DdsMipWriter.Filter.AVERAGE));
        assertTrue(Files.notExists(path));
    }

    /** Mirrors {@code unsignedOctahedralToHemisphereDirection} in dxvk-remix's packing.slangh. */
    private static double[] decodeHemisphereOctahedral(int r, int g) {
        double px = r / 255.0 * 2.0 - 1.0;
        double py = g / 255.0 * 2.0 - 1.0;
        double tx = (px + py) * 0.5;
        double ty = (px - py) * 0.5;
        double tz = 1.0 - Math.abs(tx) - Math.abs(ty);
        double len = Math.sqrt(tx * tx + ty * ty + tz * tz);
        return new double[] {tx / len, ty / len, tz / len};
    }

    private static byte unorm(double v) {
        return (byte) Math.round((v * 0.5 + 0.5) * 255.0);
    }
}
