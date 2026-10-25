package de.henner.lyricsinthesky.mixin;

import de.henner.lyricsinthesky.render.SkyLyricsRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Right after the world is drawn, before the hand: with a shader pack, its final image is finished here and the
 * lyrics go on top of it. The world call takes different arguments in 26.2 and 26.3, so it is matched by name.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;render", shift = At.Shift.AFTER))
    private void lyricsinthesky$drawAfterShaderPack(CallbackInfo ci) {
        SkyLyricsRenderer.INSTANCE.renderAfterLevel(((GameRenderer) (Object) this).mainRenderTarget());
    }
}
