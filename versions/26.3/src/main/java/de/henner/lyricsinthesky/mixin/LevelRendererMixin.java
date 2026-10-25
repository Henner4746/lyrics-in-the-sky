package de.henner.lyricsinthesky.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import de.henner.lyricsinthesky.render.SkyLyricsRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds two frame passes. One right after the sky pass, before anything else is drawn: the sky text goes there, so
 * terrain, clouds and entities drawn later all stand in front of it, like in front of the sun. One at the very end,
 * for the words around the player that stay in front of the world.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Shadow
    @Final
    private LevelTargetBundle targets;

    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    @Inject(method = "addSkyPass", at = @At("TAIL"))
    private void lyricsinthesky$addSkyLyricsPass(FrameGraphBuilder frame, CameraRenderState camera, GpuBufferSlice skyFog,
            CallbackInfo ci) {
        float luminance = luminance();
        FramePass pass = frame.addPass("lyricsinthesky_sky");
        targets.main = pass.readsAndWrites(targets.main);
        var main = targets.main;
        pass.executes(() -> SkyLyricsRenderer.INSTANCE.renderSky(main.get(), luminance));
    }

    /** The last pass of the frame, after clouds and weather, so nothing of the world covers these words. */
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;"
            + "execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V"))
    private void lyricsinthesky$addLyricsPass(FrameGraphBuilder frame, GraphicsResourceAllocator allocator,
            FrameGraphBuilder.Inspector inspector, Operation<Void> original) {
        float luminance = luminance();
        FramePass pass = frame.addPass("lyricsinthesky");
        targets.main = pass.readsAndWrites(targets.main);
        var main = targets.main;
        pass.executes(() -> SkyLyricsRenderer.INSTANCE.renderInFront(main.get(), luminance));
        original.call(frame, allocator, inspector);
    }

    private float luminance() {
        Vector3fc sky = levelRenderState.skyRenderState.skyColor;
        return sky == null ? 1f : 0.299f * sky.x() + 0.587f * sky.y() + 0.114f * sky.z();
    }
}
