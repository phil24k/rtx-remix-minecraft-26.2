/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 rtx-remix-minecraft-radeon contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Writes an uncompressed 8-bit texture and its full mip chain, filtered on the
 * CPU, as a DX10-extended DDS file.
 *
 * <p>The runtime builds the mips of a texture created from memory on the GPU,
 * and on AMD that pass leaves every level below the first unwritten: distant
 * surfaces then sample zeros, or stale memory after a resource reload. A
 * texture created from a DDS file takes its mips from the file instead.
 */
final class DdsMipWriter {

    static final int DXGI_FORMAT_R8G8B8A8_UNORM = 28;
    static final int DXGI_FORMAT_R8G8_UNORM = 49;
    static final int DXGI_FORMAT_R8_UNORM = 61;

    /** How each mip level is reduced from the one above it. */
    enum Filter {
        /** Averages each 2x2 footprint: colour and linear data. */
        AVERAGE,
        /** Keeps the highest texel of each footprint, as hierarchical parallax occlusion mapping needs. */
        MAX
    }

    private static final int DDSD_CAPS = 0x1;
    private static final int DDSD_HEIGHT = 0x2;
    private static final int DDSD_WIDTH = 0x4;
    private static final int DDSD_PITCH = 0x8;
    private static final int DDSD_PIXELFORMAT = 0x1000;
    private static final int DDSD_MIPMAPCOUNT = 0x20000;
    private static final int DDPF_FOURCC = 0x4;
    private static final int DDSCAPS_COMPLEX = 0x8;
    private static final int DDSCAPS_TEXTURE = 0x1000;
    private static final int DDSCAPS_MIPMAP = 0x400000;
    private static final int D3D10_RESOURCE_DIMENSION_TEXTURE2D = 3;
    private static final int HEADER_BYTES = 4 + 124 + 20;

    @FunctionalInterface
    private interface Reducer {
        byte[] reduce(byte[] src, int w, int h, int nw, int nh, int channels);
    }

    /** Turns one level of the filtered chain into the bytes stored for it. */
    @FunctionalInterface
    private interface LevelEncoder {
        byte[] encode(byte[] level, int texels);
    }

    private DdsMipWriter() {}

    static int mipCount(int width, int height) {
        return 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
    }

    /**
     * Writes {@code base} (tightly packed rows of {@code channels} bytes per
     * pixel) and its mip chain to {@code path}, replacing any previous file
     * atomically so a reader never sees a partial one.
     */
    static void write(Path path, byte[] base, int width, int height, int channels, int dxgiFormat,
                      Filter filter) throws IOException {
        Reducer reducer = filter == Filter.MAX ? DdsMipWriter::maxReduce : DdsMipWriter::averageReduce;
        writeChain(path, base, width, height, channels, channels, dxgiFormat, reducer, (level, texels) -> level);
    }

    /**
     * Writes an RGBA8 tangent-space normal map ({@code base}, xyz in RGB with
     * 128 as zero) as a two-channel hemisphere-octahedral R8G8 texture, the
     * encoding the runtime decodes every material normal sample from. The
     * runtime converts a texture created from memory with a tangent-space
     * purpose itself; a texture loaded from a file is sampled as stored.
     *
     * <p>Each level averages the unit normals above it and renormalizes before
     * encoding, because an average of octahedral coordinates is not a direction.
     *
     * @param directX {@code true} when green points down (DirectX convention);
     *                the runtime's tangent frame has it pointing up
     */
    static void writeOctahedralNormals(Path path, byte[] base, int width, int height, boolean directX)
            throws IOException {
        writeChain(path, base, width, height, 4, 2, DXGI_FORMAT_R8G8_UNORM, DdsMipWriter::normalReduce,
                (level, texels) -> encodeOctahedral(level, texels, directX));
    }

    private static void writeChain(Path path, byte[] base, int width, int height, int srcChannels,
                                   int outChannels, int dxgiFormat, Reducer reducer, LevelEncoder encoder)
            throws IOException {
        if (width <= 0 || height <= 0 || srcChannels <= 0
                || (long) width * height * srcChannels > base.length) {
            throw new IllegalArgumentException("Invalid texture dimensions or truncated pixels");
        }
        int mips = mipCount(width, height);
        long total = HEADER_BYTES;
        for (int level = 0, w = width, h = height; level < mips; level++) {
            total += (long) w * h * outChannels;
            w = Math.max(1, w >> 1);
            h = Math.max(1, h >> 1);
        }
        if (total > Integer.MAX_VALUE) {
            throw new IOException("DDS too large: " + total + " bytes");
        }

        ByteBuffer out = ByteBuffer.allocate((int) total).order(ByteOrder.LITTLE_ENDIAN);
        putHeader(out, width, height, outChannels, mips, dxgiFormat);

        byte[] level = base;
        int w = width;
        int h = height;
        out.put(encoder.encode(level, w * h), 0, w * h * outChannels);
        for (int i = 1; i < mips; i++) {
            int nw = Math.max(1, w >> 1);
            int nh = Math.max(1, h >> 1);
            level = reducer.reduce(level, w, h, nw, nh, srcChannels);
            out.put(encoder.encode(level, nw * nh), 0, nw * nh * outChannels);
            w = nw;
            h = nh;
        }
        out.flip();

        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            while (out.hasRemaining()) {
                ch.write(out);
            }
        }
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void putHeader(ByteBuffer out, int width, int height, int channels, int mips, int dxgiFormat) {
        out.putInt(0x20534444); // "DDS "
        out.putInt(124);
        out.putInt(DDSD_CAPS | DDSD_HEIGHT | DDSD_WIDTH | DDSD_PITCH | DDSD_PIXELFORMAT | DDSD_MIPMAPCOUNT);
        out.putInt(height);
        out.putInt(width);
        out.putInt(width * channels); // pitch of the top level
        out.putInt(1); // depth
        out.putInt(mips);
        for (int i = 0; i < 11; i++) {
            out.putInt(0); // reserved
        }
        out.putInt(32); // pixel format size
        out.putInt(DDPF_FOURCC);
        out.putInt(0x30315844); // "DX10"
        for (int i = 0; i < 5; i++) {
            out.putInt(0); // RGB bit count and masks
        }
        out.putInt(DDSCAPS_COMPLEX | DDSCAPS_TEXTURE | DDSCAPS_MIPMAP);
        out.putInt(0);
        out.putInt(0);
        out.putInt(0);
        out.putInt(0); // reserved
        out.putInt(dxgiFormat);
        out.putInt(D3D10_RESOURCE_DIMENSION_TEXTURE2D);
        out.putInt(0); // misc flags
        out.putInt(1); // array size
        out.putInt(0); // misc flags 2
    }

    /** Averages each 2x2 footprint, folding the odd edge of a non-power-of-two level into the last texel. */
    private static byte[] averageReduce(byte[] src, int w, int h, int nw, int nh, int channels) {
        byte[] dst = new byte[nw * nh * channels];
        for (int y = 0; y < nh; y++) {
            int y0 = Math.min(y * 2, h - 1);
            int y1 = y == nh - 1 ? h - 1 : Math.min(y * 2 + 1, h - 1);
            for (int x = 0; x < nw; x++) {
                int x0 = Math.min(x * 2, w - 1);
                int x1 = x == nw - 1 ? w - 1 : Math.min(x * 2 + 1, w - 1);
                int o = (y * nw + x) * channels;
                int count = (y1 - y0 + 1) * (x1 - x0 + 1);
                for (int k = 0; k < channels; k++) {
                    int sum = 0;
                    for (int sy = y0; sy <= y1; sy++) {
                        for (int sx = x0; sx <= x1; sx++) {
                            sum += src[(sy * w + sx) * channels + k] & 0xFF;
                        }
                    }
                    dst[o + k] = (byte) ((sum + count / 2) / count);
                }
            }
        }
        return dst;
    }

    /** Keeps the largest value of each 2x2 footprint, with the same edge folding as {@link #averageReduce}. */
    private static byte[] maxReduce(byte[] src, int w, int h, int nw, int nh, int channels) {
        byte[] dst = new byte[nw * nh * channels];
        for (int y = 0; y < nh; y++) {
            int y0 = Math.min(y * 2, h - 1);
            int y1 = y == nh - 1 ? h - 1 : Math.min(y * 2 + 1, h - 1);
            for (int x = 0; x < nw; x++) {
                int x0 = Math.min(x * 2, w - 1);
                int x1 = x == nw - 1 ? w - 1 : Math.min(x * 2 + 1, w - 1);
                int o = (y * nw + x) * channels;
                for (int k = 0; k < channels; k++) {
                    int m = 0;
                    for (int sy = y0; sy <= y1; sy++) {
                        for (int sx = x0; sx <= x1; sx++) {
                            m = Math.max(m, src[(sy * w + sx) * channels + k] & 0xFF);
                        }
                    }
                    dst[o + k] = (byte) m;
                }
            }
        }
        return dst;
    }

    /** Averages each footprint's RGBA normals, then renormalizes xyz so the level stays a unit-normal map. */
    private static byte[] normalReduce(byte[] src, int w, int h, int nw, int nh, int channels) {
        byte[] dst = averageReduce(src, w, h, nw, nh, channels);
        for (int o = 0; o < dst.length; o += channels) {
            double nx = snorm(dst[o]);
            double ny = snorm(dst[o + 1]);
            double nz = snorm(dst[o + 2]);
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-4) {
                // Opposing normals cancelled out; flat is the only safe answer.
                nx = 0.0;
                ny = 0.0;
                nz = 1.0;
                len = 1.0;
            }
            dst[o] = toUnorm(nx / len);
            dst[o + 1] = toUnorm(ny / len);
            dst[o + 2] = toUnorm(nz / len);
        }
        return dst;
    }

    /**
     * Encodes RGBA tangent normals as unsigned hemisphere-octahedral RG, the
     * inverse of the runtime's {@code unsignedOctahedralToHemisphereDirection}.
     */
    static byte[] encodeOctahedral(byte[] rgba, int texels, boolean directX) {
        byte[] dst = new byte[texels * 2];
        for (int i = 0; i < texels; i++) {
            double nx = snorm(rgba[i * 4]);
            double ny = snorm(rgba[i * 4 + 1]);
            double nz = Math.max(0.0, snorm(rgba[i * 4 + 2]));
            if (directX) {
                ny = -ny;
            }
            double l1 = Math.abs(nx) + Math.abs(ny) + nz;
            if (l1 < 1e-6) {
                nx = 0.0;
                ny = 0.0;
                l1 = 1.0;
            }
            double px = nx / l1;
            double py = ny / l1;
            dst[i * 2] = toUnorm(px + py);
            dst[i * 2 + 1] = toUnorm(px - py);
        }
        return dst;
    }

    private static double snorm(byte b) {
        return (b & 0xFF) / 255.0 * 2.0 - 1.0;
    }

    private static byte toUnorm(double v) {
        return (byte) Math.round(Math.max(0.0, Math.min(255.0, (v * 0.5 + 0.5) * 255.0)));
    }
}
