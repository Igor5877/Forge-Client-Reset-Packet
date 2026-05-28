package gg.chaldea.server.fastlogin.mixin;

import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Server-side packet send timing — measures the gap between JoinGame and
 * UpdateRecipes / UpdateTags / PlayerPosition packets.
 *
 * If the gap is on the server (slow send), we'll see big deltas here.
 * If the gap is on the client (slow process), the server logs show all packets
 * sent within ms of each other.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class MixinServerPacketTiming {

    private static final Logger LOGGER = LogManager.getLogger("FastLogin/SendTiming");

    @Shadow public ServerPlayer player;

    private static final Map<String, Long> fl$firstSendMs = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> fl$firstChunkLogged = new ConcurrentHashMap<>();

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void fl$logKeyPacket(Packet<?> packet, CallbackInfo ci) {
        String name = packet.getClass().getSimpleName();
        if (!fl$isKey(name)) return;

        String playerName = player == null ? "<unknown>" : player.getGameProfile().getName();
        String key = playerName + ":JoinGame";

        long now = System.currentTimeMillis();
        if (name.equals("ClientboundLoginPacket")) {
            fl$firstSendMs.put(key, now);
            fl$firstChunkLogged.remove(playerName);
            LOGGER.info("[SendTiming] {} → JoinGame ts=0ms", playerName);
        } else if (name.equals("ClientboundLevelChunkWithLightPacket")) {
            if (fl$firstChunkLogged.putIfAbsent(playerName, Boolean.TRUE) != null) return;
            Long base = fl$firstSendMs.get(key);
            long delta = base == null ? -1 : (now - base);
            LOGGER.info("[SendTiming] {} → FirstChunk +{}ms", playerName, delta);
        } else {
            Long base = fl$firstSendMs.get(key);
            long delta = base == null ? -1 : (now - base);
            LOGGER.info("[SendTiming] {} → {} +{}ms", playerName, name, delta);
        }
    }

    private static boolean fl$isKey(String name) {
        return name.equals("ClientboundLoginPacket")
            || name.equals("ClientboundUpdateRecipesPacket")
            || name.equals("ClientboundUpdateTagsPacket")
            || name.equals("ClientboundPlayerPositionPacket")
            || name.equals("ClientboundLevelChunkWithLightPacket");
    }
}
