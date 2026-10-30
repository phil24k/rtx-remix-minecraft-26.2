/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import sun.misc.Unsafe;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45.*;

class TextureOverlayResizeTest {
    @Test
    void aReplacementTextureWithTheSameGlNameReceivesTheRemixImage() throws Exception {
        assertTrue(glfwInit(), "The GPU regression requires a working GLFW display");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 5);
        // Compatibility allows deterministic name reuse with glBindTexture instead
        // of depending on the driver's glGenTextures allocation policy.
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_COMPAT_PROFILE);
        long window = glfwCreateWindow(16, 16, "RTX Remix offscreen resize check", 0, 0);
        assertNotEquals(0, window, "The GPU regression requires OpenGL 4.5");
        try {
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Unsafe unsafe = (Unsafe) unsafeField.get(null);
            TextureOverlay overlay = (TextureOverlay) unsafe.allocateInstance(TextureOverlay.class);
            Method framebufferFor = TextureOverlay.class.getDeclaredMethod("framebufferFor", GlTexture.class);
            framebufferFor.setAccessible(true);
            Method release = TextureOverlay.class.getDeclaredMethod("releaseFramebuffer");
            release.setAccessible(true);
            int texture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, 4, 4);
            GlTexture first = new FixtureTexture(texture, 4, 4);
            int oldFbo = (int) framebufferFor.invoke(overlay, first);
            glClearNamedFramebufferfv(oldFbo, GL_COLOR, 0, new float[] {1, 0, 0, 1});
            glDeleteTextures(texture);
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, 8, 8);
            GlTexture replacement = new FixtureTexture(texture, 8, 8);
            int checkFbo = glCreateFramebuffers();
            glNamedFramebufferTexture(checkFbo, GL_COLOR_ATTACHMENT0, texture, 0);
            glClearNamedFramebufferfv(checkFbo, GL_COLOR, 0, new float[] {0, 0, 1, 1});
            assertEquals(0xFF0000, pixel(oldFbo), "The old FBO still retains the deleted image");
            int currentFbo = (int) framebufferFor.invoke(overlay, replacement);
            assertEquals(0x0000FF, pixel(currentFbo), "The compositor must use the replacement image");
            assertEquals(currentFbo, (int) framebufferFor.invoke(overlay, replacement),
                    "An unchanged texture should keep its framebuffer");
            assertEquals(GL_NO_ERROR, glGetError());
            release.invoke(overlay);
            glDeleteFramebuffers(checkFbo);
            glDeleteTextures(texture);
        } finally {
            GL.setCapabilities(null);
            glfwMakeContextCurrent(0);
            glfwDestroyWindow(window);
            glfwTerminate();
        }
    }

    private static int pixel(int framebuffer) {
        ByteBuffer pixel = MemoryUtil.memAlloc(4);
        try {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
            glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            return ((pixel.get(0) & 255) << 16) | ((pixel.get(1) & 255) << 8) | (pixel.get(2) & 255);
        } finally {
            MemoryUtil.memFree(pixel);
        }
    }

    private static class FixtureTexture extends GlTexture {
        FixtureTexture(int id, int width, int height) {
            super(GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                    "Resize regression", GpuFormat.RGBA8_UNORM, width, height, 1, 1, id, null);
        }
    }
}
