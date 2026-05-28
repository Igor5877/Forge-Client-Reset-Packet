package gg.chaldea.client.reset.packet.mixin;

import gg.chaldea.client.reset.packet.ClientReset;
import gg.chaldea.client.reset.packet.RegistryCacheState;
import gg.chaldea.client.reset.packet.SeamlessTransition;
import gg.chaldea.client.reset.packet.TagCache;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundUpdateTagsPacket;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Full bypass of vanilla handleUpdateTags when modset + payload hash already
 * applied to current registry state. Registry tag bindings persist across CRP
 * soft resets, so re-applying identical tags is wasted work.
 *
 * Invalidated automatically when GameData.revertToFrozen wipes registries.
 */
@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListenerTagCache {

    private static final Logger LOGGER = LogManager.getLogger("CRP/TagCache");

    @Unique
    private String crp$pendingTagCacheKey;

    @Inject(method = "handleUpdateTags", at = @At("HEAD"), cancellable = true, require = 1)
    private void crp$useTagCache(ClientboundUpdateTagsPacket packet, CallbackInfo ci) {
        crp$pendingTagCacheKey = null;
        if (!ClientReset.TAG_CACHE_ENABLED) return;

        String fp = RegistryCacheState.lastInjectedFingerprint;
        if (fp == null) return;

        long t0 = System.nanoTime();
        long contentHash = TagCache.computeHash(packet);
        String key = TagCache.makeKey(fp, contentHash);
        long hashMs = (System.nanoTime() - t0) / 1_000_000L;

        if (TagCache.isApplied(key)) {
            LOGGER.info("[TagCache] HIT — bypass handleUpdateTags ({} registries, hash_ms={})",
                packet.getTags().size(), hashMs);
            if (SeamlessTransition.tTagsApplied == 0L && SeamlessTransition.tLoginSuccess != 0L) {
                SeamlessTransition.tTagsApplied = System.nanoTime();
            }
            ci.cancel();
            return;
        }

        crp$pendingTagCacheKey = key;
        LOGGER.info("[TagCache] MISS key={} hash_ms={} — running vanilla", key, hashMs);
    }

    @Inject(method = "handleUpdateTags", at = @At("RETURN"), require = 1)
    private void crp$storeInTagCache(ClientboundUpdateTagsPacket packet, CallbackInfo ci) {
        if (crp$pendingTagCacheKey == null) return;
        TagCache.markApplied(crp$pendingTagCacheKey, packet.getTags().size());
        crp$pendingTagCacheKey = null;
    }
}
