package gg.chaldea.client.reset.packet.mixin;

import net.minecraft.client.renderer.ChunkBufferBuilderPack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Queue;
import java.util.concurrent.Executor;

/**
 * Increases the number of concurrent chunk-mesh builder slots.
 *
 * In Minecraft 1.20.1, {@link ChunkRenderDispatcher} manages parallelism
 * through a pool of {@link ChunkBufferBuilderPack} objects in
 * {@code freeBuffers}. The number of packs is computed from memory:
 * {@code max(1, min(cores, maxMemory * 0.3 / bufferSize))}.
 *
 * On machines with limited heap allocation (default 2–4 GB), this is often
 * only 2–4 packs, meaning at most 2–4 chunks compile concurrently.
 * After a CRP server switch, the entire visible area (~441 sections for
 * rd=12) must be recompiled from scratch — more packs = faster completion.
 *
 * We inject at the end of the constructor and add more packs until we reach
 * {@code max(1, min(8, cores/2))}. Extra memory cost is bounded: each
 * {@link ChunkBufferBuilderPack} is ~30 MB at typical render settings.
 * OutOfMemoryError is caught so the game degrades gracefully.
 */
@Mixin(ChunkRenderDispatcher.class)
@OnlyIn(Dist.CLIENT)
public class MixinChunkRenderDispatcher {

    private static final Logger CRP_LOGGER = LogManager.getLogger("CRP/RenderBuilders");

    @Shadow @Final private Queue<ChunkBufferBuilderPack> freeBuffers;
    @Shadow private volatile int freeBufferCount;

    /**
     * Runs after the primary constructor finishes setting up freeBuffers.
     * We add more ChunkBufferBuilderPack instances if the vanilla heuristic
     * allocated fewer than our target minimum.
     *
     * Method descriptor targets the 5-arg public constructor which is the one
     * LevelRenderer calls. It delegates to the 6-arg private constructor, so
     * @At("RETURN") fires after the full initialisation is done.
     */
    @Inject(
        method = "<init>(Lnet/minecraft/client/multiplayer/ClientLevel;"
               + "Lnet/minecraft/client/renderer/LevelRenderer;"
               + "Ljava/util/concurrent/Executor;"
               + "Z"
               + "Lnet/minecraft/client/renderer/ChunkBufferBuilderPack;)V",
        at = @At("RETURN")
    )
    private void crp$ensureMinBuilderPacks(
            ClientLevel level, LevelRenderer renderer, Executor executor,
            boolean memoryReserveMode, ChunkBufferBuilderPack fixedBufferPack,
            CallbackInfo ci) {

        int cores = Runtime.getRuntime().availableProcessors();
        // Target: max(1, min(8, cores/2))  — leave at least one core for render/main
        int target = Math.max(1, Math.min(8, cores / 2));
        int current = this.freeBuffers.size();

        if (current >= target) {
            return; // vanilla gave us enough
        }

        int added = 0;
        for (int i = current; i < target; i++) {
            try {
                this.freeBuffers.add(new ChunkBufferBuilderPack());
                added++;
            } catch (OutOfMemoryError oom) {
                CRP_LOGGER.warn("[RenderBuilders] OOM after adding {}/{} extra packs — stopping", added, target - current);
                break;
            }
        }

        if (added > 0) {
            this.freeBufferCount = this.freeBuffers.size();
            CRP_LOGGER.info("[RenderBuilders] Builder packs expanded: {} → {} (cores={})",
                    current, this.freeBufferCount, cores);
        }
    }
}
