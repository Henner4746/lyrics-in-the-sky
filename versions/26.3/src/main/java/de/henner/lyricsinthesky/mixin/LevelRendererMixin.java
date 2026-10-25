package de.henner.lyricsinthesky.mixin;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import de.henner.lyricsinthesky.render.SkyLyricsRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a frame pass right after the main world pass. At that point terrain, entities and clouds are in the
 * depth buffer, so the lyrics (48 blocks away, camera-relative) hide behind nearby trees and buildings but
 * stay in front of clouds and the sky.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Shadow
    @Final
    private LevelTargetBundle targets;

    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    @Inject(method = "addMainPass", at = @At("TAIL"))
    private void lyricsinthesky$addLyricsPass(FrameGraphBuilder frame, FeatureRenderDispatcher.PreparedFrame featureFrame,
            GpuBufferSlice terrainFog, ChunkSectionsToRender chunkSectionsToRender, boolean consistentDepthRequired,
            CallbackInfo ci) {
        Vector3fc sky = levelRenderState.skyRenderState.skyColor;
        float luminance = sky == null ? 1f : 0.299f * sky.x() + 0.587f * sky.y() + 0.114f * sky.z();
        FramePass pass = frame.addPass("lyricsinthesky");
        targets.main = pass.readsAndWrites(targets.main);
        var main = targets.main;
        pass.executes(() -> SkyLyricsRenderer.INSTANCE.render(main.get(), luminance));
    }
}
