package de.henner.lyricsinthesky.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

/**
 * Minecraft 26.3: draws the custom sky's quads right after the game's sky, before the lyrics. Textured and colored
 * like the end sky, without the text shader's cut at 10 % opacity, so soft glows fade out smoothly; light that adds
 * up (aurora, beams, stars) is blended like lightning.
 */
final class SkyDrawer {
    private static final RenderPipeline COVER = pipeline("custom_sky", BlendFunction.TRANSLUCENT);
    private static final RenderPipeline GLOW = pipeline("custom_sky_glow", BlendFunction.LIGHTNING);

    private record Draw(PreparedRenderType renderType, int baseVertex, int indexCount) {}

    private static GpuBuffer vertexBuffer;
    private static ByteBuffer upload;

    private SkyDrawer() {}

    private static RenderPipeline pipeline(String name, BlendFunction blend) {
        return RenderPipeline.builder()
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withLocation(Identifier.fromNamespaceAndPath("lyricsinthesky", "pipeline/" + name))
                .withVertexShader("core/position_tex_color")
                .withFragmentShader("core/position_tex_color")
                .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                .withColorTargetState(new ColorTargetState(blend))
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withCull(false)
                .withDepthStencilState(Optional.of(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false)))
                .build();
    }

    static void draw(RenderTarget target, SkyMesh mesh) {
        List<SkyMesh.Batch> batches = mesh.drawn();
        if (batches.isEmpty()) {
            return;
        }
        List<Draw> draws = new ArrayList<>(batches.size());
        int vertexSize = DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize();
        int size = 0;
        int maxIndexCount = 0;
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(256 * 1024)) {
            List<MeshData> meshes = new ArrayList<>(batches.size());
            List<Integer> offsets = new ArrayList<>(batches.size());
            try {
                for (SkyMesh.Batch batch : batches) {
                    PreparedRenderType type = renderType(batch);
                    if (type == null) {
                        continue;
                    }
                    BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
                    for (int i = 0; i < batch.vertices; i++) {
                        int at = i * 5;
                        builder.addVertex(batch.data[at], batch.data[at + 1], batch.data[at + 2])
                                .setUv(batch.data[at + 3], batch.data[at + 4])
                                .setColor(batch.colors[i]);
                    }
                    MeshData data = builder.build();
                    if (data == null) {
                        continue;
                    }
                    meshes.add(data);
                    offsets.add(size);
                    int indexCount = data.drawState().indexCount();
                    draws.add(new Draw(type, size / vertexSize, indexCount));
                    maxIndexCount = Math.max(maxIndexCount, indexCount);
                    size += data.vertexBuffer().remaining();
                }
                if (draws.isEmpty()) {
                    return;
                }
                ByteBuffer buffer = uploadBuffer(size);
                for (int i = 0; i < meshes.size(); i++) {
                    buffer.position(offsets.get(i));
                    buffer.put(meshes.get(i).vertexBuffer());
                }
                buffer.position(0).limit(size);
                GpuDevice device = RenderSystem.getDevice();
                ensureVertexBuffer(device, size);
                device.createCommandEncoder().writeToBuffer(vertexBuffer.slice(0, size), buffer);
            } finally {
                meshes.forEach(MeshData::close);
            }
        }

        RenderSystem.AutoStorageIndexBuffer quadIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = quadIndices.getBuffer(maxIndexCount);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Lyrics in the Sky: sky", target.getColorTextureView(), Optional.empty(),
                target.getDepthTextureView(), OptionalDouble.empty())) {
            for (Draw draw : draws) {
                draw.renderType().drawFromBuffer(new StagedVertexBuffer.ExecuteInfo(vertexBuffer, indexBuffer,
                        quadIndices.type(), draw.baseVertex(), 0, draw.indexCount(), PrimitiveTopology.QUADS), pass);
            }
        }
    }

    /** The batch's texture, smoothly filtered, with the view of this frame borrowed from a vanilla render type. */
    private static PreparedRenderType renderType(SkyMesh.Batch batch) {
        AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(batch.texture);
        if (texture == null || texture.getTextureView() == null) {
            return null;
        }
        GpuSampler sampler = batch.repeat ? RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR)
                : RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        PreparedRenderType borrowed = RenderTypes.textSeeThrough(batch.texture).prepare();
        return new PreparedRenderType("lyricsinthesky_sky", batch.glow ? GLOW : COVER, null, borrowed.dynamicTransforms(),
                borrowed.scissorState(), List.of(new PreparedRenderType.Texture("Sampler0", texture.getTextureView(), sampler)));
    }

    private static ByteBuffer uploadBuffer(int size) {
        if (upload == null || upload.capacity() < size) {
            upload = ByteBuffer.allocateDirect(Integer.highestOneBit(Math.max(1024, size - 1)) << 1).order(ByteOrder.nativeOrder());
        }
        upload.clear();
        return upload;
    }

    private static void ensureVertexBuffer(GpuDevice device, int size) {
        if (vertexBuffer == null || vertexBuffer.size() < size) {
            if (vertexBuffer != null) {
                vertexBuffer.close();
            }
            int capacity = Integer.highestOneBit(Math.max(1024, size - 1)) << 1;
            vertexBuffer = device.createBuffer(() -> "Lyrics in the Sky sky vertices",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, capacity);
        }
    }
}
