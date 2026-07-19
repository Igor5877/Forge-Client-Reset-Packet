package gg.chaldea.server.fastlogin;

import io.netty.channel.Channel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deferred recipe send ("recipe_offer" protocol).
 *
 * Vanilla placeNewPlayer pushes the full ClientboundUpdateRecipesPacket
 * (~18k recipes, several MB) to every joining player. CRP clients already
 * hold the parsed recipe set from the previous backend and discard those
 * bytes unparsed — the serialization + transfer is pure waste, and on slow
 * links it dominates the switch time.
 *
 * Flow (PLAY phase only, no login-protocol changes):
 *   1. placeNewPlayer's recipe send is intercepted; instead a tiny
 *      "fastlogin:recipe_offer" plugin message carrying the current epoch is
 *      sent, and the real packet is parked here with a deadline.
 *   2. Client replies "fastlogin:recipe_ack" ok=true  -> parked packet dropped
 *                                            ok=false -> parked packet sent now.
 *   3. No reply within TIMEOUT_MS (non-CRP client, packet loss) -> parked
 *      packet sent anyway. Worst case is vanilla behavior a moment late.
 *
 * The epoch identifies the server's recipe generation: re-stamped at server
 * start and on every datapack reload. A client cache entry is only reused
 * for the exact epoch it was filled from, so /reload can never leave a
 * client on stale recipes — after reload the epoch differs, the client acks
 * "need", and the full packet flows.
 */
public final class RecipeDeferState {

    private static final Logger LOGGER = LogManager.getLogger("FastLogin/RecipeDefer");

    public static final ResourceLocation OFFER_ID = new ResourceLocation("fastlogin", "recipe_offer");
    public static final ResourceLocation ACK_ID = new ResourceLocation("fastlogin", "recipe_ack");

    private static final long TIMEOUT_MS = 3000L;

    /** Recipe generation stamp. 0 = not started yet (defer disabled until stamped). */
    private static volatile long epoch = 0L;

    private record Pending(ServerGamePacketListenerImpl listener,
                           ClientboundUpdateRecipesPacket packet,
                           long deadlineMs) {}

    private static final ConcurrentHashMap<Channel, Pending> PENDING = new ConcurrentHashMap<>();

    private RecipeDeferState() {}

    public static void stampEpoch(String reason) {
        epoch = System.nanoTime();
        LOGGER.info("[RecipeDefer] epoch stamped ({}) = {}", reason, Long.toHexString(epoch));
    }

    public static long getEpoch() {
        return epoch;
    }

    /** Park the recipes packet for this connection and send the tiny offer instead. */
    public static void defer(ServerGamePacketListenerImpl listener, ClientboundUpdateRecipesPacket packet) {
        Channel ch = listener.connection.channel();
        PENDING.put(ch, new Pending(listener, packet, System.currentTimeMillis() + TIMEOUT_MS));
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer(8));
        buf.writeLong(epoch);
        listener.send(new ClientboundCustomPayloadPacket(OFFER_ID, buf));
        LOGGER.info("[RecipeDefer] offered epoch={} to {} — recipes parked",
                Long.toHexString(epoch), listener.getPlayer().getGameProfile().getName());
    }

    /** Client confirmed it applied its cached recipes for this epoch. */
    public static void ackOk(Channel ch) {
        Pending p = PENDING.remove(ch);
        if (p != null) {
            LOGGER.info("[RecipeDefer] ACK ok from {} — full recipe packet skipped",
                    p.listener.getPlayer().getGameProfile().getName());
        }
    }

    /** Client has no matching cache — send the parked packet immediately. */
    public static void ackNeed(Channel ch) {
        Pending p = PENDING.remove(ch);
        if (p != null) {
            LOGGER.info("[RecipeDefer] ACK need from {} — sending full recipe packet",
                    p.listener.getPlayer().getGameProfile().getName());
            p.listener.send(p.packet);
        }
    }

    /** Called every server tick: flush parked packets whose deadline passed. */
    public static void flushExpired() {
        if (PENDING.isEmpty()) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Channel, Pending>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Channel, Pending> e = it.next();
            Pending p = e.getValue();
            if (now >= p.deadlineMs) {
                it.remove();
                if (e.getKey().isOpen()) {
                    LOGGER.warn("[RecipeDefer] no ack from {} within {}ms — sending full recipe packet (fallback)",
                            p.listener.getPlayer().getGameProfile().getName(), TIMEOUT_MS);
                    p.listener.send(p.packet);
                }
            }
        }
    }
}
