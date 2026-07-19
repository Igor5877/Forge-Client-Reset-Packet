package gg.chaldea.client.reset.packet.mixin;

import gg.chaldea.client.reset.packet.ClientReset;
import gg.chaldea.client.reset.packet.RegistryCacheState;
import gg.chaldea.client.reset.packet.SeamlessTransition;
import gg.chaldea.client.reset.packet.TagCache;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundUpdateTagsPacket;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

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

    /**
     * handleUpdateTags = deserialize+bind tags (correctness-critical: handleLogin
     * wiped them via resetTags) + Blocks.rebuildCache (needed after block-tag
     * rebind) + creative-tab search-tree rebuild. On a 200-mod pack that search
     * rebuild alone is ~4s of the switch — and on a verified same-modset switch
     * the item set is identical, so the tree it would rebuild is byte-for-byte
     * the one already built. Skip just that part by feeding the rebuild loop an
     * empty tab list; tags and the block cache still refresh normally.
     * Gated by skipRecipeEvents (set only when sameModset, cleared at T6).
     */
    @Redirect(
        method = "handleUpdateTags",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/CreativeModeTabs;allTabs()Ljava/util/List;"),
        require = 0
    )
    private List<CreativeModeTab> crp$skipSearchTreeRebuild() {
        if (SeamlessTransition.skipRecipeEvents) {
            LOGGER.info("[Phase3] creative search-tree rebuild skipped (sameModset)");
            return List.of();
        }
        return CreativeModeTabs.allTabs();
    }

    /**
     * Blocks.rebuildCache() re-runs initCache() on every blockstate in the game
     * (~hundreds of thousands of states on a 200-mod pack — seconds of render-
     * thread time). Its results depend only on registry contents + tag bindings,
     * both of which are verified identical on a sameModset switch, so the caches
     * it would recompute are exactly the ones already in place. The tag re-bind
     * above (updateTagsForRegistry) still runs — only this recompute is skipped.
     */
    @Redirect(
        method = "handleUpdateTags",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Blocks;rebuildCache()V"),
        require = 0
    )
    private void crp$skipBlocksRebuildCache() {
        if (SeamlessTransition.skipRecipeEvents) {
            LOGGER.info("[Phase3] Blocks.rebuildCache skipped (sameModset)");
            return;
        }
        net.minecraft.world.level.block.Blocks.rebuildCache();
    }
}
