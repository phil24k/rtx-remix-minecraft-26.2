/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 rtx-remix-minecraft-radeon contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.nvidia.rtxremix.RemixException;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePurpose;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import com.nvidia.rtxremix.minecraft.asset.RemixTextureStore;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Creates Remix textures through DDS files that carry a CPU-built mip chain.
 *
 * <p>The runtime's GPU mip pass leaves the lower levels of a texture created
 * from memory unwritten on AMD, so distant surfaces sampled zeros (mirror-like
 * roughness and metallic, black diffuse, missing entity textures, garbage
 * normals) or stale memory after a reload. A texture created from a file takes
 * its mips from the file.
 *
 * <p>A file is sampled as stored, so this side also does the purpose's
 * processing: a height map keeps the maximum of each footprint, and a
 * tangent-space normal map is written in the octahedral encoding the runtime
 * would have converted it to.
 *
 * <p>A file-backed texture must not be patched with {@code UpdateTextureFromMemory},
 * which writes the top level and regenerates the rest on the GPU; recreate it instead.
 */
public final class CpuMipTextures {

    private static final Logger LOGGER = LoggerFactory.getLogger(CpuMipTextures.class);

    private static final String CACHE_DIR = "rtx-remix-cache";
    private static final int MAX_NAME_CHARS = 96;

    private CpuMipTextures() {}

    /**
     * Creates {@code data} as a file-backed texture with CPU-built mips under
     * {@code rtx-remix-cache/<subdir>/}, or returns {@code null} when the kill
     * switch is off, the format or purpose is not handled, or the file cannot
     * be written or loaded. The caller then uploads from memory as before.
     */
    public static RemixTexture create(String subdir, String name, byte[] data, int w, int h,
                                      int fmt, int purpose) {
        String override = System.getProperty("rtxremix.texture.cpuAtlasMips");
        if (!CpuMipPolicy.enabled(override, override == null ? GL11.glGetString(GL11.GL_VENDOR) : null)) {
            return null;
        }
        int channels;
        int dxgiFormat;
        if (fmt == TextureFormat.RGBA8_UNORM) {
            channels = 4;
            dxgiFormat = DdsMipWriter.DXGI_FORMAT_R8G8B8A8_UNORM;
        } else if (fmt == TextureFormat.R8_UNORM) {
            channels = 1;
            dxgiFormat = DdsMipWriter.DXGI_FORMAT_R8_UNORM;
        } else {
            return null;
        }
        boolean normal = purpose == TexturePurpose.NORMAL_DX || purpose == TexturePurpose.NORMAL_OGL;
        boolean supported = switch (purpose) {
            case TexturePurpose.COLOR, TexturePurpose.DATA, TexturePurpose.HEIGHTMAP -> true;
            case TexturePurpose.NORMAL_DX, TexturePurpose.NORMAL_OGL -> channels == 4;
            default -> false;
        };
        long byteCount = (long) w * h * channels;
        if (!supported || w <= 0 || h <= 0 || byteCount > data.length) return null;

        // The content hash keeps a reload from reusing a texture the runtime
        // may have cached by path.
        String fileName = fileName(name);
        CRC32 crc = new CRC32();
        crc.update(data, 0, w * h * channels);
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve(CACHE_DIR).resolve(subdir);
        Path path = dir.resolve(String.format("%s-%d-%d-%dx%d-%08x.dds", fileName, fmt, purpose, w, h, crc.getValue()));
        try {
            if (!Files.exists(path)) {
                deleteStale(dir, fileName);
                if (normal) {
                    DdsMipWriter.writeOctahedralNormals(path, data, w, h, purpose == TexturePurpose.NORMAL_DX);
                } else {
                    DdsMipWriter.write(path, data, w, h, channels, dxgiFormat,
                            purpose == TexturePurpose.HEIGHTMAP ? DdsMipWriter.Filter.MAX
                                    : DdsMipWriter.Filter.AVERAGE);
                }
            }
            return RemixTextureStore.get().create(new RemixTexture(path));
        } catch (IOException | RemixException e) {
            LOGGER.warn("Falling back to GPU mips for {}: {}", name, e.getMessage());
            return null;
        }
    }

    /** Maps a texture name, which may be an identifier, to a safe and bounded file name stem. */
    static String fileName(String name) {
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() <= MAX_NAME_CHARS) return safe;
        CRC32 crc = new CRC32();
        crc.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return safe.substring(0, MAX_NAME_CHARS) + String.format("_%08x", crc.getValue());
    }

    /** Deletes earlier versions of {@code fileName}, and no file of a texture whose name merely starts with it. */
    private static void deleteStale(Path dir, String fileName) {
        if (!Files.isDirectory(dir)) return;
        Pattern own = Pattern.compile(Pattern.quote(fileName) + "-\\d+-\\d+-\\d+x\\d+-[0-9a-f]{8}\\.dds");
        try (DirectoryStream<Path> stale = Files.newDirectoryStream(dir, fileName + "-*.dds")) {
            for (Path p : stale) {
                if (own.matcher(p.getFileName().toString()).matches()) {
                    Files.deleteIfExists(p);
                }
            }
        } catch (IOException e) {
            LOGGER.debug("Could not prune stale {} DDS files: {}", fileName, e.getMessage());
        }
    }
}
