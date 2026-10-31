/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 rtx-remix-minecraft-radeon contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CpuMipTexturesTest {

    @Test
    void identifierNamesBecomeSafeFileNames() {
        assertEquals("entity_a_minecraft_textures_entity_zombie_zombie.png",
                CpuMipTextures.fileName("entity_a_minecraft:textures/entity/zombie/zombie.png"));
    }

    @Test
    void longNamesAreBoundedAndStayDistinct() {
        String base = "entity_a_minecraft:textures/" + "x".repeat(200);
        String a = CpuMipTextures.fileName(base + "/a.png");
        String b = CpuMipTextures.fileName(base + "/b.png");
        assertTrue(a.length() <= 96 + 9);
        assertNotEquals(a, b);
    }
}
