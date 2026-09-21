package gg.chaldea.client.reset.packet.mixin;

import gg.chaldea.client.reset.packet.RegistryCacheState;
import gg.chaldea.client.reset.packet.TagCache;
import net.minecraftforge.registries.GameData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cache safety: GameData.revertToFrozen() resets every Forge registry back
 * to its initial frozen state, wiping all the entries our cached fingerprint
 * was vouching for. If we don't invalidate after this, a subsequent reconnect
 * would HIT the cache, SKIP injectSnapshot, and leave GameData empty — which
 * triggers "output cannot be empty" decoder errors on the first PLAY packet
 * that touches an unknown block / item / etc.
 *
 * MixinMinecraft already skips ForgeHooksClient.handleClientLevelClosing
 * during soft CRP transitions (Phase 1), so this only fires on full-disconnect
 * paths — exactly the path that was crashing.
 *
 * RecipeCache is deliberately NOT invalidated here. injectSnapshot only
 * remaps the RL<->int ID table of each ForgeRegistry; it never recreates the
 * registered Item/Block Java objects (mods construct those once at load
 * time), so previously-cached Recipe/Ingredient object graphs still
 * reference the correct entries after a fresh injection. RecipeCache's own
 * key (registry fingerprint + recipe content hash) already forces a miss
 * whenever either actually changes, so revisiting a fingerprint we've seen
 * before in this session can reuse the cached recipe set instead of paying
 * the ~3-9s vanilla rebuild again. TagCache is different: tag bindings live
 * inside the Registry objects themselves (not a swappable Map), and
 * revertToFrozen resets that live state back to frozen, so a stale "already
 * applied" marker would wrongly skip re-binding — it must stay invalidated.
 */
@Mixin(value = GameData.class, remap = false)
public abstract class MixinGameDataRevertToFrozen {

    @Inject(method = "revertToFrozen", at = @At("HEAD"))
    private static void crp$invalidateRegistryCache(CallbackInfo ci) {
        RegistryCacheState.invalidate("GameData.revertToFrozen");
        TagCache.invalidateAll("GameData.revertToFrozen");
    }
}
