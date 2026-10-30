/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL45.glCreateFramebuffers;
import static org.lwjgl.opengl.GL45.glNamedFramebufferTexture;

/**
 * Copies the Remix output onto Minecraft's main render target, fully opaque, after
 * the world and first-person model have been submitted and before the screen
 * overlays and HUD.
 *
 * <p>Render thread only. The copy is a direct-state-access framebuffer blit, so it
 * binds nothing and leaves the state Minecraft's OpenGL backend caches untouched;
 * the one exception, the scissor test that would clip a blit, is switched off
 * through the backend's own state manager.
 */
public class TextureOverlay {

    private static final Logger LOGGER = LoggerFactory.getLogger(TextureOverlay.class);

    private final Minecraft client;
    /** A framebuffer over the main target's colour texture, rebuilt when that texture changes. */
    private int targetFramebuffer;
    private GlTexture targetTexture;

    public TextureOverlay() {
        this.client = Minecraft.getInstance();
        LOGGER.info("TextureOverlay initialized");
    }

    /**
     * Copies the Remix colour image onto the main render target.
     *
     * @param interop the interop holding this frame's Remix image
     */
    public void render(GlInterop interop) {
        if (interop == null || interop.getTexture() <= 0) {
            return;
        }

        if (client == null || client.getWindow() == null) {
            return;
        }

        try {
            renderTextureOverlay(interop);
        } catch (Exception e) {
            LOGGER.error("Error rendering texture overlay", e);
        }
    }

    private void renderTextureOverlay(GlInterop interop) {
        RenderTarget mainTarget = client.gameRenderer.mainRenderTarget();
        GpuTexture colorTexture = mainTarget.getColorTexture();
        if (!(colorTexture instanceof GlTexture glTexture)) {
            return;
        }

        int framebuffer = framebufferFor(glTexture);
        GlStateManager._disableScissorTest();
        interop.blitColorTo(framebuffer, mainTarget.width, mainTarget.height);
    }

    private int framebufferFor(GlTexture glTexture) {
        // Texture names can be reused after a resize while an FBO still retains the
        // deleted texture. The new Java texture object identifies the replacement.
        if (targetFramebuffer == 0 || targetTexture != glTexture) {
            releaseFramebuffer();
            targetFramebuffer = glCreateFramebuffers();
            glNamedFramebufferTexture(targetFramebuffer, GL_COLOR_ATTACHMENT0, glTexture.glId(), 0);
            targetTexture = glTexture;
        }
        return targetFramebuffer;
    }

    private void releaseFramebuffer() {
        if (targetFramebuffer != 0) {
            glDeleteFramebuffers(targetFramebuffer);
            targetFramebuffer = 0;
            targetTexture = null;
        }
    }

    /**
     * Cleans up resources.
     */
    public void shutdown() {
        releaseFramebuffer();
        LOGGER.info("TextureOverlay shutdown");
    }
}
