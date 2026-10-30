/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BakedQuadMixinTest {

    private static BakedQuadStaticGeometryCache cache() {
        var point = new org.joml.Vector3f();
        var quad = new net.minecraft.client.resources.model.geometry.BakedQuad(point, point, point, point,
                0L, 0L, 0L, 0L, net.minecraft.core.Direction.UP,
                new net.minecraft.client.resources.model.geometry.BakedQuad.MaterialInfo(null, null, null, -1, true, 0));
        return (BakedQuadStaticGeometryCache) (Object) quad;
    }

    @Test
    void zeroIsAValidCachedHash() {
        BakedQuadStaticGeometryCache cache = cache();
        int[] vertexData = {1, 2, 3, 4};

        assertFalse(cache.rtxremix$matchesStaticGeometry(vertexData, 11, 12));
        cache.rtxremix$setStaticGeometryHash(0L, vertexData, 11, 12);

        assertTrue(cache.rtxremix$matchesStaticGeometry(vertexData, 11, 12));
        assertEquals(0L, cache.rtxremix$getStaticGeometryHash());
    }

    @Test
    void mutableVertexDataInvalidatesAndReusesTheSnapshot() {
        BakedQuadStaticGeometryCache cache = cache();
        int[] vertexData = {1, 2, 3, 4};
        cache.rtxremix$setStaticGeometryHash(7L, vertexData, 11, 12);

        vertexData[2] = 99;
        assertFalse(cache.rtxremix$matchesStaticGeometry(vertexData, 11, 12));

        cache.rtxremix$setStaticGeometryHash(8L, vertexData, 11, 12);
        assertTrue(cache.rtxremix$matchesStaticGeometry(vertexData, 11, 12));
        assertEquals(8L, cache.rtxremix$getStaticGeometryHash());
    }

    @Test
    void changedSpriteBoundsInvalidateUnchangedVertexData() {
        BakedQuadStaticGeometryCache cache = cache();
        int[] vertexData = {1, 2, 3, 4};
        cache.rtxremix$setStaticGeometryHash(7L, vertexData, 11, 12);

        assertFalse(cache.rtxremix$matchesStaticGeometry(vertexData, 21, 12));
        assertFalse(cache.rtxremix$matchesStaticGeometry(vertexData, 11, 22));
    }
}
