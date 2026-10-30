/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import java.nio.ByteBuffer;

/** Repeats a sprite's edge texels into its atlas gutter before mip filtering. */
final class AtlasPadding {
    private AtlasPadding() {}

    static void extend(byte[] atlas, int atlasWidth, int x, int y,
            int width, int height, int padding, int channels) {
        for (int dy = -padding; dy < height + padding; dy++) {
            int sourceY = y + Math.clamp(dy, 0, height - 1);
            for (int dx = -padding; dx < width + padding; dx++) {
                if (dy >= 0 && dy < height && dx >= 0 && dx < width) continue;
                int sourceX = x + Math.clamp(dx, 0, width - 1);
                int source = (sourceY * atlasWidth + sourceX) * channels;
                int destination = ((y + dy) * atlasWidth + x + dx) * channels;
                System.arraycopy(atlas, source, atlas, destination, channels);
            }
        }
    }

    /** Writes a frame and its gutter together, so animated atlas patches keep their border current. */
    static void writePadded(ByteBuffer source, int rowPitch, ByteBuffer destination,
            int width, int height, int padding, int channels) {
        for (int y = -padding; y < height + padding; y++) {
            int row = Math.clamp(y, 0, height - 1) * rowPitch;
            for (int x = -padding; x < width + padding; x++) {
                int offset = row + Math.clamp(x, 0, width - 1) * channels;
                for (int channel = 0; channel < channels; channel++) {
                    destination.put(source.get(offset + channel));
                }
            }
        }
    }
}
