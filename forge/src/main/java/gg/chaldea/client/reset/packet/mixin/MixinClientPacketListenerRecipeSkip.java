package gg.chaldea.client.reset.packet.mixin;

import gg.chaldea.client.reset.packet.SeamlessTransition;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.IEventBus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Phase 3 — suppress REI full reload during same-modset CRP server switches.
 *
 * <p>Problem: on every server switch the client receives UpdateTagsPacket and
 * UpdateRecipesPacket. These trigger TagsUpdatedEvent and RecipesUpdatedEvent
 * respectively (injected by Forge coremod via ASM into the vanilla handlers).
 * REI (Roughly Enough Items) listens to both and performs a full plugin reload —
 * rebuilding 24,596 recipe displays across 71 JEI-compat plugins.
 * Measured cost: ~932ms (tags/START) + ~1697ms (recipes/END) = ~2.6s per switch.
 *
 * <p>Both events are injected by Forge as {@code MinecraftForge.EVENT_BUS.post(event)}
 * calls at the end of their respective handlers. We redirect those specific post()
 * calls: when skipRecipeEvents=true (sameModset confirmed) we skip the broadcast,
 * saving ~2.6s. All vanilla processing (recipe data, tag binding, recipe book update,
 * creative tabs rebuild) still runs normally — only the event broadcast is skipped.
 *
 * <p>When sameModset=false (different mods, or first switch without cached fingerprint),
 * both events fire normally — REI reloads and shows correct data.
 *
 * <p>require=0: if Forge changes its injection point between versions the redirect
 * silently becomes a no-op (events fire normally) rather than crashing the game.
 *
 * <p>skipRecipeEvents is reset to false by MixinClientPacketListenerFix.onChunkReceived
 * (T5 marker) after the first world chunk arrives, ending the transition window.
 */
@Mixin(ClientPacketListener.class)
@OnlyIn(Dist.CLIENT)
public class MixinClientPacketListenerRecipeSkip {

    private static final Logger CRP_LOGGER = LogManager.getLogger("CRP/SkipREI");

    // -------------------------------------------------------------------------
    // handleUpdateRecipes — suppress RecipesUpdatedEvent (REI END phase ~1.7s)
    // Forge injects: MinecraftForge.EVENT_BUS.post(new RecipesUpdatedEvent(...))
    // at RETURN of handleUpdateRecipes via coremod ASM.
    // -------------------------------------------------------------------------

    /**
     * When skipRecipeEvents=true, intercept the Forge-injected event bus post
     * inside handleUpdateRecipes and suppress it. RecipeManager is updated by the
     * vanilla code before this call — only the event broadcast is skipped.
     */
    @Redirect(
        method = "handleUpdateRecipes",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraftforge/eventbus/api/IEventBus;post(Lnet/minecraftforge/eventbus/api/Event;)Z"),
        require = 0
    )
    private boolean crp$skipRecipesUpdatedEvent(IEventBus bus, Event event) {
        if (SeamlessTransition.skipRecipeEvents) {
            CRP_LOGGER.info("[SkipREI] RecipesUpdatedEvent suppressed (sameModset) — REI END skipped ~1.7s");
            return false;
        }
        return bus.post(event);
    }

    // -------------------------------------------------------------------------
    // handleUpdateTags — suppress TagsUpdatedEvent (REI START phase ~0.9s)
    // Forge injects: MinecraftForge.EVENT_BUS.post(new TagsUpdatedEvent(...))
    // at RETURN of handleUpdateTags via coremod ASM.
    // -------------------------------------------------------------------------

    /**
     * When skipRecipeEvents=true, intercept the Forge-injected event bus post
     * inside handleUpdateTags and suppress it. All tag-binding to registries and
     * CreativeModeTabs rebuild still run — only the broadcast event is skipped.
     */
    @Redirect(
        method = "handleUpdateTags",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraftforge/eventbus/api/IEventBus;post(Lnet/minecraftforge/eventbus/api/Event;)Z"),
        require = 0
    )
    private boolean crp$skipTagsUpdatedEvent(IEventBus bus, Event event) {
        if (SeamlessTransition.skipRecipeEvents) {
            CRP_LOGGER.info("[SkipREI] TagsUpdatedEvent suppressed (sameModset) — REI START skipped ~0.9s");
            return false;
        }
        return bus.post(event);
    }
}
