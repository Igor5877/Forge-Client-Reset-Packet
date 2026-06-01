package gg.chaldea.client.reset.packet.mixin;

import gg.chaldea.client.reset.packet.ClientReset;
import gg.chaldea.client.reset.packet.RecipeCache;
import gg.chaldea.client.reset.packet.RegistryCacheState;
import gg.chaldea.client.reset.packet.SeamlessTransition;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;

/**
 * RecipeSkipParse — skip the network DECODE of the recipe packet on a same-modset switch.
 *
 * <p>{@link RecipeCache} (via MixinClientPacketListenerRecipeCache) already cancels the
 * vanilla {@code handleUpdateRecipes} handler on a cache HIT, saving {@code replaceRecipes}
 * (~3-4s) and {@code ClientRecipeBook.setupCollections} (~2-3s). But the handler runs
 * <em>after</em> the packet is constructed, and the vanilla
 * {@code ClientboundUpdateRecipesPacket(FriendlyByteBuf)} constructor deserializes the
 * <em>entire</em> ~18000-recipe list off the wire first — measured ~1-1.5s of the remaining
 * {@code join→recipes} window, on the Netty IO thread, before any of our handler mixins fire.
 *
 * <p>This redirect intercepts the single {@code buf.readList(...)} call inside that
 * constructor. When the transition is a confirmed same-modset switch
 * ({@link SeamlessTransition#skipRecipeEvents}) AND we already hold a cached recipe set for
 * the current registry fingerprint, it discards the recipe bytes without parsing them and
 * returns an empty list. {@link SeamlessTransition#recipePacketSkipped} signals the handler
 * mixin to apply the cached recipes by fingerprint instead of hashing the (now empty) list.
 *
 * <p>Safety:
 * <ul>
 *   <li>Only fires when a cache entry for the fingerprint exists — a fresh client (cold cache)
 *       parses normally, so recipes are never lost.</li>
 *   <li>recipes is the only field of this packet, so the recipe list spans the whole packet
 *       body; consuming all readable bytes leaves the buffer correctly empty (vanilla
 *       PacketDecoder otherwise throws "Packet was larger than I expected").</li>
 *   <li>require=0: if the vanilla constructor shape changes between versions the redirect
 *       silently no-ops (vanilla parse runs) instead of crashing.</li>
 * </ul>
 */
@Mixin(ClientboundUpdateRecipesPacket.class)
public abstract class MixinUpdateRecipesPacketSkip {

    private static final Logger LOGGER = LogManager.getLogger("CRP/RecipeSkip");

    @Redirect(
        method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/network/FriendlyByteBuf;readList(Lnet/minecraft/network/FriendlyByteBuf$Reader;)Ljava/util/List;"),
        require = 0
    )
    private List<?> crp$skipRecipeParse(FriendlyByteBuf buf, FriendlyByteBuf.Reader<?> reader) {
        if (crp$shouldSkip()) {
            int discarded = buf.readableBytes();
            buf.skipBytes(discarded);
            SeamlessTransition.recipePacketSkipped = true;
            LOGGER.info("[RecipeSkip] discarded {} recipe bytes unparsed (sameModset, cache present) — applying cached recipes by fingerprint",
                discarded);
            return new ArrayList<>();
        }
        return buf.readList(reader);
    }

    private static boolean crp$shouldSkip() {
        if (!ClientReset.RECIPE_SKIP_PARSE_ENABLED || !ClientReset.RECIPE_CACHE_ENABLED) return false;
        if (!SeamlessTransition.skipRecipeEvents) return false; // confirmed same-modset transition window
        String fp = RegistryCacheState.lastInjectedFingerprint;
        // Cold cache (fresh client) → no entry → parse normally so recipes aren't lost.
        return fp != null && RecipeCache.getByFingerprint(fp) != null;
    }
}
