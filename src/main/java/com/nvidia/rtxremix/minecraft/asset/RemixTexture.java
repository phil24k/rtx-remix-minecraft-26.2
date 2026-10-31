/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.asset;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureFromMemoryInfo;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;

import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * Wraps a single Remix texture resource backed by pixel data in memory, or by
 * a texture file whose mip chain the runtime loads as stored.
 *
 * <p>Create via {@link RemixTextureStore#create(RemixAssetBase)}.
 * Destroy via the public {@link #destroy()} method.
 */
public class RemixTexture extends RemixAssetBase {

    private int width;
    private int height;
    private int format;
    private int purpose;
    private ByteBuffer pixelData;
    private Path filePath;

    public RemixTexture(int width, int height, int format, int purpose, ByteBuffer pixelData) {
        this.width = width;
        this.height = height;
        this.format = format;
        this.purpose = purpose;
        this.pixelData = pixelData;
    }

    /** A texture loaded from {@code filePath}, such as a DDS that carries its own mips. */
    public RemixTexture(Path filePath) {
        this.filePath = filePath;
    }

    /** Whether this texture was loaded from a file and so carries mips that a memory patch would not refresh. */
    public boolean isFileBacked() {
        return filePath != null;
    }

    @Override
    protected long doCreate(RemixAPI api, long hash) {
        if (filePath != null) {
            return api.createTextureFromFile(filePath.toString());
        }
        TextureFromMemoryInfo info = new TextureFromMemoryInfo();
        info.hash = hash;
        info.width = width;
        info.height = height;
        info.format = format;
        info.purpose = purpose;
        info.pixelData = pixelData;

        long result = api.createTextureFromMemory(info);
        Reference.reachabilityFence(pixelData);
        return result;
    }

    @Override
    protected void doDestroy(RemixAPI api, long handle) {
        api.destroyTexture(handle);
    }

    @Override
    protected void doDisposeData() {
        DirectBufferPool.release(pixelData);
        pixelData = null;
        width = 0;
        height = 0;
    }

    @Override
    protected String typeName() {
        return "RemixTexture";
    }
}
