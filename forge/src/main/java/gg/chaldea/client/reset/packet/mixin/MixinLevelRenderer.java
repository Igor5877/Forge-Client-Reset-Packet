package gg.chaldea.client.reset.packet.mixin;

import gg.chaldea.client.reset.packet.SeamlessTransition;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
@OnlyIn(Dist.CLIENT)
public class MixinLevelRenderer {

    /**
     * Phase 2 chunk reuse: when SeamlessTransition.keepChunkBuffers is set
     * (by ClientReset.handleClear immediately before the reset cycle),
     * skip the viewArea.releaseAllBuffers() call inside allChanged() so the
     * existing chunk VBO meshes survive. Incoming chunk packets from the new
     * server will overwrite the meshes in place — much cheaper than allocating
     * brand-new GL buffers for every render section.
     */
    @Redirect(
        method = "allChanged",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ViewArea;releaseAllBuffers()V")
    )
    private void crp$skipReleaseAllBuffers(ViewArea viewArea) {
        if (!SeamlessTransition.keepChunkBuffers) {
            viewArea.releaseAllBuffers();
        }
    }

    /**
     * Self-reset the keepChunkBuffers flag after one allChanged() call so the
     * optimization fires exactly once per transition — never on regular
     * graphics-settings reloads or render-distance changes.
     */
    @Inject(method = "allChanged", at = @At("RETURN"))
    private void crp$clearKeepChunkBuffersFlag(CallbackInfo ci) {
        if (SeamlessTransition.keepChunkBuffers) {
            SeamlessTransition.keepChunkBuffers = false;
        }
    }
}
