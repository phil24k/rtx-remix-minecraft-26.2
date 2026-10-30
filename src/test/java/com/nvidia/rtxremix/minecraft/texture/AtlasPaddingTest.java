/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AtlasPaddingTest {
    @Test
    void solidSpriteHasNoBlackOrTransparentMipBorder() {
        byte[] atlas = new byte[8 * 8 * 4];
        for (int y = 2; y < 4; y++) for (int x = 2; x < 4; x++) {
            int pixel = (y * 8 + x) * 4;
            atlas[pixel] = (byte) 180;
            atlas[pixel + 1] = (byte) 100;
            atlas[pixel + 2] = (byte) 40;
            atlas[pixel + 3] = (byte) 255;
        }
        AtlasPadding.extend(atlas, 8, 2, 2, 2, 2, 2, 4);
        for (int y = 0; y < 6; y++) for (int x = 0; x < 6; x++) {
            int pixel = (y * 8 + x) * 4;
            assertArrayEquals(new byte[] {(byte) 180, 100, 40, (byte) 255},
                    java.util.Arrays.copyOfRange(atlas, pixel, pixel + 4));
        }
        // A low-resolution sample spanning the edge must retain the block's color and opacity.
        int averageRed = 0, averageAlpha = 0;
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
            averageRed += atlas[(y * 8 + x) * 4] & 255;
            averageAlpha += atlas[(y * 8 + x) * 4 + 3] & 255;
        }
        assertEquals(180, averageRed / 16);
        assertEquals(255, averageAlpha / 16);
        assertEquals(0, atlas[(7 * 8 + 7) * 4]);
    }

    @Test
    void guttersDoNotTakePixelsFromAdjacentSprites() {
        byte[] atlas = new byte[8 * 4];
        for (int y = 1; y < 3; y++) {
            atlas[y * 8 + 1] = 10;
            atlas[y * 8 + 2] = 20;
            atlas[y * 8 + 5] = 30;
            atlas[y * 8 + 6] = 40;
        }
        AtlasPadding.extend(atlas, 8, 1, 1, 2, 2, 1, 1);
        AtlasPadding.extend(atlas, 8, 5, 1, 2, 2, 1, 1);
        for (int y = 0; y < 4; y++) {
            assertArrayEquals(new byte[] {10, 10, 20, 20, 30, 30, 40, 40},
                    java.util.Arrays.copyOfRange(atlas, y * 8, y * 8 + 8));
        }
    }

    @Test
    void animatedFrameCopiesItsOwnEdgesIncludingAlpha() {
        ByteBuffer source = ByteBuffer.wrap(new byte[] {1, 2, 3, 0, 4, 5, 6, (byte) 255});
        ByteBuffer output = ByteBuffer.allocate(4 * 3 * 4);
        AtlasPadding.writePadded(source, 8, output, 2, 1, 1, 4);
        byte[] row = {1, 2, 3, 0, 1, 2, 3, 0, 4, 5, 6, (byte) 255, 4, 5, 6, (byte) 255};
        for (int y = 0; y < 3; y++) {
            assertArrayEquals(row, java.util.Arrays.copyOfRange(output.array(), y * 16, y * 16 + 16));
        }
        assertEquals(0, source.position());
    }
}
