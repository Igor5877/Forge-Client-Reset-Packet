package gg.chaldea.client.reset.packet.mixin;

import gg.chaldea.client.reset.packet.RegistryCacheState;
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
 */
@Mixin(value = GameData.class, remap = false)
public abstract class MixinGameDataRevertToFrozen {

    @Inject(method = "revertToFrozen", at = @At("HEAD"))
    private static void crp$invalidateRegistryCache(CallbackInfo ci) {
        RegistryCacheState.invalidate("GameData.revertToFrozen");
    }
}
