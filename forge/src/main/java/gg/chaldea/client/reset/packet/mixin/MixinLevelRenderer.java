package gg.chaldea.client.reset.packet.mixin;

import gg.chaldea.client.reset.packet.SeamlessTransition;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
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
     * Phase 2 chunk buffer reuse.
     *
     * When SeamlessTransition.keepChunkBuffers is set (activated by
     * ClientReset.handleClear only when sameModset=true), skip the
     * viewArea.releaseAllBuffers() call inside allChanged() so the GPU VBO
     * meshes survive the server switch.
     *
     * Instead of releasing, we set each RenderChunk.compiled to UNCOMPILED.
     * This clears the stale mesh reference so the renderer won't draw old
     * geometry, but the underlying GL VertexBuffer objects stay allocated —
     * much cheaper than freeing and re-allocating for every section.
     *
     * New chunks from the incoming backend will overwrite the buffers
     * in-place as they are compiled and uploaded.
     *
     * Guard: only activates when sameModset=true (set by Ambassador when it
     * confirms the two backends share identical registry fingerprints). This
     * prevents artefacts when switching between servers with different mod
     * blocks.
     */
    @Redirect(
        method = "allChanged",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ViewArea;releaseAllBuffers()V")
    )
    private void crp$skipReleaseAllBuffers(ViewArea viewArea) {
        if (SeamlessTransition.keepChunkBuffers) {
            // Keep GPU buffers alive; clear compiled reference to avoid stale meshes.
            // LevelRenderer.allChanged() will reschedule all chunks for recompile.
            for (ChunkRenderDispatcher.RenderChunk chunk : viewArea.chunks) {
                if (chunk != null) {
                    chunk.compiled.set(ChunkRenderDispatcher.CompiledChunk.UNCOMPILED);
                }
            }
        } else {
            viewArea.releaseAllBuffers();
        }
    }

    /**
     * Self-reset keepChunkBuffers after one allChanged() call so the
     * optimisation fires exactly once per transition — never on regular
     * graphics-settings reloads or render-distance changes.
     */
    @Inject(method = "allChanged", at = @At("RETURN"))
    private void crp$clearKeepChunkBuffersFlag(CallbackInfo ci) {
        if (SeamlessTransition.keepChunkBuffers) {
            SeamlessTransition.keepChunkBuffers = false;
        }
    }
}
