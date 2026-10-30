/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.resources.SplashManager;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SplashManager.class)
public abstract class SplashManagerMixin {
    @Inject(method = "getSplash", at = @At("HEAD"), cancellable = true)
    private void remix$forkSplash(CallbackInfoReturnable<SplashRenderer> cir) {
        cir.setReturnValue(new SplashRenderer(
                Component.literal("RTX REMIX FORKED BY Phil24k!").withStyle(ChatFormatting.YELLOW)));
    }
}
