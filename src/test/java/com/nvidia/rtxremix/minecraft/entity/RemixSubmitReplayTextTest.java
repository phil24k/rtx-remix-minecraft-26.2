/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RemixSubmitReplayTextTest {
    private static final Identifier PAGE = Identifier.withDefaultNamespace("test/glyphs");

    @Test
    void nameTagsProduceOneInkQuadDespiteTheirSeeThroughCompanion() throws Exception {
        var normal = new NameTagFeatureRenderer.Submit(new Matrix4f(), 0, 0,
                Component.literal("Player"), 0, -1, 0, Font.DisplayMode.NORMAL);
        var through = new NameTagFeatureRenderer.Submit(new Matrix4f(), 0, 0,
                Component.literal("Player"), 0, -1, 0, Font.DisplayMode.SEE_THROUGH);
        assertEquals(4, capture(NameTagFeatureRenderer.TYPE, normal, through));
    }

    @Test
    void aSeeThroughOnlyTextDisplayStillProducesVisibleInk() throws Exception {
        var text = new TextFeatureRenderer.Submit(new Matrix4f(), 0, 0,
                Component.literal("Hologram").getVisualOrderText(), false,
                Font.DisplayMode.SEE_THROUGH, 0, -1, 0, 0);
        assertEquals(4, capture(TextFeatureRenderer.TYPE, text));
    }

    @Test
    void ordinaryTextKeepsItsInk() throws Exception {
        var text = new TextFeatureRenderer.Submit(new Matrix4f(), 0, 0,
                Component.literal("Text").getVisualOrderText(), false,
                Font.DisplayMode.NORMAL, 0, -1, 0, 0);
        assertEquals(4, capture(TextFeatureRenderer.TYPE, text));
    }

    @SuppressWarnings("unchecked")
    private static int capture(FeatureRendererType<?> type, SubmitNode... submits) throws Exception {
        RemixSubmitReplay replay = new RemixSubmitReplay();
        Field groupField = RemixSubmitReplay.class.getDeclaredField("group");
        groupField.setAccessible(true);
        ((List<SubmitNode>) groupField.get(replay)).addAll(List.of(submits));
        Method build = RemixSubmitReplay.class.getDeclaredMethod(
                "buildGroup", FeatureFrameContext.class, FeatureRendererType.class);
        build.setAccessible(true);
        FeatureFrameContext context = new FeatureFrameContext(null, new FixtureFont(),
                null, null, null, null, null, null);
        RemixVertexConsumerProvider provider = new RemixVertexConsumerProvider();
        provider.beginEntity();
        CaptureVertexBuilders.begin(provider);
        try {
            build.invoke(replay, context, type);
            return provider.endEntity().stream().mapToInt(layer -> layer.vertexCount()).sum();
        } finally {
            CaptureVertexBuilders.end();
            EntityRenderContext.get().reset();
        }
    }

    private static class FixtureFont extends Font {
        FixtureFont() { super(null); }
        @Override
        public PreparedText prepareText(FormattedCharSequence text, float x, float y, int color,
                                        boolean shadow, boolean inverseDepth, int background) {
            return new PreparedText() {
                public void visit(GlyphVisitor visitor) { visitor.acceptRenderable(new FixtureGlyph()); }
                public ScreenRectangle bounds() { return new ScreenRectangle(0, 0, 1, 1); }
            };
        }
    }

    private static class FixtureGlyph implements TextRenderable {
        public RenderType renderType(Font.DisplayMode mode) {
            return mode == Font.DisplayMode.SEE_THROUGH
                    ? RenderTypes.textSeeThrough(PAGE) : RenderTypes.text(PAGE);
        }
        public void render(Matrix4fc pose, VertexConsumer out, int light, boolean inverseDepth) {
            for (int i = 0; i < 4; i++) {
                out.addVertex(pose, i == 1 || i == 2 ? 1 : 0, i >= 2 ? 1 : 0, 0)
                        .setColor(-1).setUv(0, 0).setUv2(light & 0xFFFF, light >>> 16).setNormal(0, 0, 1);
            }
        }
        public GpuTextureView textureView() { return null; }
        public RenderPipeline guiPipeline() { return null; }
        public float left() { return 0; }
        public float top() { return 0; }
        public float right() { return 1; }
        public float bottom() { return 1; }
    }
}
