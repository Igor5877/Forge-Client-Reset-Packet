package gg.chaldea.server.fastlogin.mixin;

import gg.chaldea.server.fastlogin.FastLoginMod;
import gg.chaldea.server.fastlogin.NestworldBridge;
import gg.chaldea.server.fastlogin.RecipeDeferState;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.ChunkPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Intercepts the initial-join recipe send inside PlayerList.placeNewPlayer and
 * routes it through the recipe_offer defer protocol (see RecipeDeferState).
 *
 * The redirect matches every ServerGamePacketListenerImpl.send() call in
 * placeNewPlayer; only the ClientboundUpdateRecipesPacket one is deferred,
 * everything else passes straight through. The reload broadcast path
 * (PlayerList.reloadResources) is intentionally untouched — after /reload
 * every online client must receive the new recipes unconditionally.
 *
 * <p>Also hooks the tail of {@code placeNewPlayer} to request a NestworldCore
 * chunk prefetch (see {@link NestworldBridge}) for the joining player's view-distance
 * area, once the player's {@code ServerLevel} and position are known. No-op on plain
 * Forge or older NestworldCore builds without the prefetch API.
 */
@Mixin(PlayerList.class)
public abstract class MixinPlayerListDeferRecipes {

    private static final Logger LOGGER = LogManager.getLogger("FastLogin/NestworldPrefetch");

    @Shadow public abstract int getViewDistance();

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

    /**
     * Once the joining player is placed into its ServerLevel with a known position,
     * ask NestworldCore to prefetch the view-distance chunk area (see NestworldBridge).
     * This replaces MixinIOWorkerParallel's mailbox-bypass on NestworldCore, giving
     * the same read-ahead speedup through a path NestworldCore's own region-sharding
     * already accounts for. Complete no-op (isActive() short-circuits) on plain Forge.
     */
    @Inject(method = "placeNewPlayer", at = @At("RETURN"), require = 0)
    private void fl$prefetchNestworldChunks(Connection connection, ServerPlayer player, CallbackInfo ci) {
        if (!NestworldBridge.isActive()) {
            return;
        }
        try {
            ServerLevel level = player.serverLevel();
            int viewDistance = getViewDistance();
            ChunkPos center = new ChunkPos(player.blockPosition());
            List<ChunkPos> positions = new ArrayList<>();
            for (int dx = -viewDistance; dx <= viewDistance; dx++) {
                for (int dz = -viewDistance; dz <= viewDistance; dz++) {
                    positions.add(new ChunkPos(center.x + dx, center.z + dz));
                }
            }
            NestworldBridge.prefetchChunks(level, positions);
            LOGGER.debug("Nestworld prefetch requested: {} chunks around {} for {}",
                positions.size(), center, player.getGameProfile().getName());
        } catch (Throwable t) {
            // Never let a prefetch failure break the player's join.
            LOGGER.warn("Nestworld prefetch on join failed, continuing without it", t);
        }
    }
}
