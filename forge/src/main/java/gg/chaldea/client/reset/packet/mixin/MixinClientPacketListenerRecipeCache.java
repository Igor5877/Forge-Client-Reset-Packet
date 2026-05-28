package gg.chaldea.client.reset.packet.mixin;

import gg.chaldea.client.reset.packet.ClientReset;
import gg.chaldea.client.reset.packet.RecipeCache;
import gg.chaldea.client.reset.packet.RegistryCacheState;
import gg.chaldea.client.reset.packet.SeamlessTransition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.world.item.crafting.RecipeManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Full bypass of vanilla handleUpdateRecipes when modset + content hash match cache.
 *
 * On HIT we swap the RecipeManager.recipes/byName maps directly and cancel the
 * whole handler — skipping both replaceRecipes (~3-4s vanilla rebuild) AND
 * ClientRecipeBook.setupCollections (~2-3s reclassification of 13k recipes).
 *
 * On MISS we let vanilla run and capture the freshly-built maps in TAIL.
 */
@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListenerRecipeCache {

    private static final Logger LOGGER = LogManager.getLogger("CRP/RecipeCache");

    @Shadow private RecipeManager recipeManager;

    @Unique
    private String crp$pendingRecipeCacheKey;

    @Inject(method = "handleUpdateRecipes", at = @At("HEAD"), cancellable = true, require = 1)
    private void crp$useRecipeCache(ClientboundUpdateRecipesPacket packet, CallbackInfo ci) {
        crp$pendingRecipeCacheKey = null;
        if (!ClientReset.RECIPE_CACHE_ENABLED) return;

        String fp = RegistryCacheState.lastInjectedFingerprint;
        if (fp == null) return;

        long t0 = System.nanoTime();
        long contentHash = RecipeCache.computeHash(packet.getRecipes());
        String key = RecipeCache.makeKey(fp, contentHash);
        long hashMs = (System.nanoTime() - t0) / 1_000_000L;

        RecipeCache.CachedRecipes cached = RecipeCache.get(key);
        if (cached != null) {
            RecipeManagerAccessor acc = (RecipeManagerAccessor) recipeManager;
            acc.crp$setRecipes(cached.recipes);
            acc.crp$setByName(cached.byName);
            LOGGER.info("[RecipeCache] HIT — full bypass, swapped {} recipes (hash_ms={}, saved ~5s: replaceRecipes + setupCollections + event)",
                cached.byName.size(), hashMs);
            if (SeamlessTransition.tRecipesApplied == 0L && SeamlessTransition.tLoginSuccess != 0L) {
                SeamlessTransition.tRecipesApplied = System.nanoTime();
            }
            // setupCollections is skipped — ClientRecipeBook keeps state from previous
            // connect which used the same modset and therefore the same recipes.
            // Forge RecipesUpdatedEvent also not posted — same-modset listeners
            // already saw it on the first connect, no need to re-fire.
            ci.cancel();
            return;
        }

        crp$pendingRecipeCacheKey = key;
        LOGGER.info("[RecipeCache] MISS key={} hash_ms={} — running vanilla rebuild", key, hashMs);
    }

    @Inject(method = "handleUpdateRecipes", at = @At("RETURN"), require = 1)
    private void crp$storeInRecipeCache(ClientboundUpdateRecipesPacket packet, CallbackInfo ci) {
        if (crp$pendingRecipeCacheKey == null) return;
        RecipeManagerAccessor acc = (RecipeManagerAccessor) recipeManager;
        RecipeCache.put(crp$pendingRecipeCacheKey, acc.crp$getRecipes(), acc.crp$getByName());
        crp$pendingRecipeCacheKey = null;
    }
}
