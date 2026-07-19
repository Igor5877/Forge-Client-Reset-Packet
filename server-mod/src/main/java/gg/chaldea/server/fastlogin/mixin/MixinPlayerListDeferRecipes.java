package gg.chaldea.server.fastlogin.mixin;

import gg.chaldea.server.fastlogin.FastLoginMod;
import gg.chaldea.server.fastlogin.RecipeDeferState;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Intercepts the initial-join recipe send inside PlayerList.placeNewPlayer and
 * routes it through the recipe_offer defer protocol (see RecipeDeferState).
 *
 * The redirect matches every ServerGamePacketListenerImpl.send() call in
 * placeNewPlayer; only the ClientboundUpdateRecipesPacket one is deferred,
 * everything else passes straight through. The reload broadcast path
 * (PlayerList.reloadResources) is intentionally untouched — after /reload
 * every online client must receive the new recipes unconditionally.
 */
@Mixin(PlayerList.class)
public abstract class MixinPlayerListDeferRecipes {

    @Redirect(
        method = "placeNewPlayer",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"),
        require = 0
    )
    private void fl$deferRecipeSend(ServerGamePacketListenerImpl listener, Packet<?> packet) {
        if (FastLoginMod.RECIPE_DEFER_ENABLED
                && packet instanceof ClientboundUpdateRecipesPacket recipes
                && RecipeDeferState.getEpoch() != 0L) {
            RecipeDeferState.defer(listener, recipes);
            return;
        }
        listener.send(packet);
    }
}
