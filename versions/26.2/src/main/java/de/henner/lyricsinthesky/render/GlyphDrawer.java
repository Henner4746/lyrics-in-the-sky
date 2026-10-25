package de.henner.lyricsinthesky.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * Minecraft 26.2: uploads the glyph quads and draws them, once right after the sky and once after the world. In 26.2
 * every prepared render type opens its own render pass on its output target, so there is no pass to manage here.
 */
final class GlyphDrawer {
    /** Each draw of a frame has buffers of its own, so the second upload never overwrites what the first draws. */
    static final int SKY_SLOT = 0;
    static final int FRONT_SLOT = 1;
    /**
     * Text without fog, tested against the world's depth but never writing it, blended with premultiplied
     * alpha as fed by {@link TintingVertexConsumer}. Vanilla's grayscale see-through variant does not blend,
     * which would make the TrueType font opaque.
     */
    private static final RenderPipeline SKY_TEXT = skyPipeline("sky_text", false, true);
    private static final RenderPipeline SKY_TEXT_GRAYSCALE = skyPipeline("sky_text_grayscale", true, true);
    /** Same without depth test, for the "around you" mode that stays visible in front of blocks. */
    private static final RenderPipeline OVERLAY_TEXT = skyPipeline("overlay_text", false, false);
    private static final RenderPipeline OVERLAY_TEXT_GRAYSCALE = skyPipeline("overlay_text_grayscale", true, false);
    private static final int FULL_BRIGHT = 0xF000F0;

    private record Group(RenderType type, int layer, boolean overlay) {}

    private record Draw(PreparedRenderType renderType, int baseVertex, int indexCount) {}

    private static final TintingVertexConsumer TINT = new TintingVertexConsumer();
    private static final GpuBuffer[] vertexBuffers = new GpuBuffer[2];
    private static final ByteBuffer[] uploads = new ByteBuffer[2];

    private GlyphDrawer() {}

    private static RenderPipeline skyPipeline(String name, boolean grayscale, boolean depthTest) {
        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.TEXT_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("lyricsinthesky", "pipeline/" + name))
                .withVertexShader("core/text")
                .withFragmentShader("core/text")
                .withShaderDefine("IS_SEE_THROUGH")
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
                .withDepthStencilState(depthTest
                        ? Optional.of(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
                        : Optional.empty());
        if (grayscale) {
            builder.withShaderDefine("IS_GRAYSCALE");
        }
        return builder.build();
    }

    static void draw(RenderTarget target, List<SkyGlyph> glyphs, int slot) {
        // Glyphs from different atlas pages need separate draws; glow and blur layers go below the text.
        Map<Group, List<SkyGlyph>> groups = new LinkedHashMap<>();
        for (SkyGlyph glyph : glyphs) {
            RenderType type = glyph.renderable().renderType(Font.DisplayMode.SEE_THROUGH);
            groups.computeIfAbsent(new Group(type, glyph.layer(), glyph.overlay()), key -> new ArrayList<>()).add(glyph);
        }
        List<Map.Entry<Group, List<SkyGlyph>>> ordered = new ArrayList<>(groups.entrySet());
        // Depth-tested sky text first, overlay text last so it ends up on top; within each, glow before text.
        ordered.sort(Comparator.<Map.Entry<Group, List<SkyGlyph>>>comparingInt(entry -> entry.getKey().overlay() ? 1 : 0)
                .thenComparingInt(entry -> entry.getKey().layer()));

        List<Draw> draws = new ArrayList<>(ordered.size());
        int size = 0;
        int maxIndexCount = 0;
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(64 * 1024)) {
            List<MeshData> meshes = new ArrayList<>(ordered.size());
            List<Integer> offsets = new ArrayList<>(ordered.size());
            try {
                for (Map.Entry<Group, List<SkyGlyph>> group : ordered) {
                    RenderType type = group.getKey().type();
                    BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.QUADS, type.format());
                    for (SkyGlyph glyph : group.getValue()) {
                        glyph.renderable().render(glyph.pose(), TINT.wrap(builder, glyph.argb(), glyph.argbBottom()),
                                FULL_BRIGHT, false);
                    }
                    MeshData mesh = builder.build();
                    if (mesh == null) {
                        continue;
                    }
                    meshes.add(mesh);
                    int vertexSize = type.format().getVertexSize();
                    // Each group starts on a vertex boundary of its own format so baseVertex lines up.
                    size = (size + vertexSize - 1) / vertexSize * vertexSize;
                    offsets.add(size);
                    int indexCount = mesh.drawState().indexCount();
                    draws.add(new Draw(skyRenderType(type, group.getKey().overlay()), size / vertexSize, indexCount));
                    maxIndexCount = Math.max(maxIndexCount, indexCount);
                    size += mesh.vertexBuffer().remaining();
                }
                if (draws.isEmpty()) {
                    return;
                }
                ByteBuffer data = uploadBuffer(slot, size);
                for (int i = 0; i < meshes.size(); i++) {
                    data.position(offsets.get(i));
                    data.put(meshes.get(i).vertexBuffer());
                }
                data.position(0).limit(size);
                GpuDevice device = RenderSystem.getDevice();
                ensureVertexBuffer(device, slot, size);
                device.createCommandEncoder().writeToBuffer(vertexBuffers[slot].slice(0, size), data);
            } finally {
                meshes.forEach(MeshData::close);
            }
        }

        RenderSystem.AutoStorageIndexBuffer quadIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = quadIndices.getBuffer(maxIndexCount);
        for (Draw draw : draws) {
            draw.renderType().drawFromBuffer(vertexBuffers[slot], indexBuffer, quadIndices.type(), draw.baseVertex(), 0,
                    draw.indexCount());
        }
    }

    /** Vanilla glyph render type with its texture and transforms, but drawn through our pipeline. */
    private static PreparedRenderType skyRenderType(RenderType type, boolean overlay) {
        PreparedRenderType prepared = type.prepare();
        boolean grayscale = type.pipeline() == RenderPipelines.TEXT_GRAYSCALE_SEE_THROUGH;
        RenderPipeline pipeline = overlay
                ? (grayscale ? OVERLAY_TEXT_GRAYSCALE : OVERLAY_TEXT)
                : (grayscale ? SKY_TEXT_GRAYSCALE : SKY_TEXT);
        return new PreparedRenderType(pipeline, prepared.outputTarget(), prepared.dynamicTransforms(),
                prepared.scissorState(), prepared.textures());
    }

    private static ByteBuffer uploadBuffer(int slot, int size) {
        if (uploads[slot] == null || uploads[slot].capacity() < size) {
            uploads[slot] = ByteBuffer.allocateDirect(Integer.highestOneBit(Math.max(1024, size - 1)) << 1)
                    .order(ByteOrder.nativeOrder());
        }
        uploads[slot].clear();
        return uploads[slot];
    }

    private static void ensureVertexBuffer(GpuDevice device, int slot, int size) {
        if (vertexBuffers[slot] == null || vertexBuffers[slot].size() < size) {
            if (vertexBuffers[slot] != null) {
                vertexBuffers[slot].close();
            }
            int capacity = Integer.highestOneBit(Math.max(1024, size - 1)) << 1;
            vertexBuffers[slot] = device.createBuffer(() -> "Lyrics in the sky vertices",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, capacity);
        }
    }
}
