/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.opengl.GlSurface;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Redirects the buffer swap inside the OpenGL surface's {@code present} to
 * Remix's present, so Vulkan owns the swapchain whenever the renderer is
 * initialized. Before that, and if initialization fails, the GLFW swap runs
 * unchanged. The frame texture blitted to the window is also handed to the
 * renderer, which copies the frame to Remix from it.
 */
@Mixin(GlSurface.class)
public abstract class RenderSystemMixin {

    @Inject(method = "blitFromTexture", at = @At("HEAD"))
    private void rtxremix$capturePresentSource(
            CommandEncoderBackend encoder, GpuTextureView texture, CallbackInfo ci) {
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer != null && renderer.isInitialized()) {
            renderer.setPresentSource(texture);
        }
    }

    @Redirect(
        method = "present",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V")
    )
    private void rtxremix$redirectSwapBuffers(long window) {
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer != null && renderer.isInitialized()) {
            renderer.presentFrameVk();
            return;
        }
        GLFW.glfwSwapBuffers(window);
    }
}
