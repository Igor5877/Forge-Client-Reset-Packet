package gg.chaldea.server.fastlogin.mixin;

import gg.chaldea.server.fastlogin.ConnectionSkipTracker;
import gg.chaldea.server.fastlogin.FastLoginMod;
import gg.chaldea.server.fastlogin.RegistryHashUtil;
import gg.chaldea.server.fastlogin.network.C2SHashResponse;
import gg.chaldea.server.fastlogin.network.S2CHashChallenge;
import net.minecraft.network.Connection;
import net.minecraftforge.network.HandshakeHandler;
import net.minecraftforge.network.HandshakeMessages;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
 * Hooks into the server-side FML handshake handler.
 *
 * Injection point A – after the server receives C2SModListReply:
 *   Send S2CHashChallenge to the client so it can tell us whether it has a
 *   valid cached registry.  Mark the connection as "pending hash response" so
 *   MixinGameData knows to check back later.
 *
 * Injection point B – handler for C2SHashResponse (registered in FastLoginMod):
 *   Called when the client replies.  Marks the connection for skip or no-skip.
 *
 * NOTE: The exact method signature of handleClientModListOnServer may differ
 * between Forge 47.x builds.  Verify against the decompiled source with:
 *   ./gradlew :server-mod:decompile   (ForgeGradle 6 task)
 */
@Mixin(value = HandshakeHandler.class, remap = false)
public class MixinHandshakeHandler {

    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * Injected AFTER the normal C2SModListReply handling so the mod list is
     * already validated.  At this point we send our hash challenge and flag
     * the connection as pending, which MixinGameData will check before
     * actually generating the registry packets.
     *
     * TODO: verify method name / descriptor against Forge 47.2.0 sources.
     *       Common candidates: "handleClientModListOnServer",
     *                          "handleModListOnServer",
     *                          "onModListResponse"
     */
    @Inject(
        method = "handleClientModListOnServer(Lnet/minecraftforge/network/HandshakeMessages$C2SModListReply;Ljava/util/function/Supplier;)V",
        at     = @At("HEAD"),
        remap  = false,
        cancellable = false
    )
    private void fl$afterModList(
        HandshakeMessages.C2SModListReply reply,
        Supplier<NetworkEvent.Context> ctxSupplier,
        CallbackInfo ci
    ) {
        if (RegistryHashUtil.getHash() == null) return;

        NetworkEvent.Context ctx = ctxSupplier.get();
        Connection connection    = ctx.getNetworkManager();
        io.netty.channel.Channel ch = connection.channel();

        // Set CURRENT_CHANNEL first so MixinGameData can identify this connection
        // in buildSnapshotList() which runs later on the same server-main thread.
        MixinGameData.CURRENT_CHANNEL.set(ch);

        // Mark this connection as waiting so MixinGameData spin-waits for our response
        ConnectionSkipTracker.markPending(ch);

        // Send hash challenge to client
        S2CHashChallenge challenge = new S2CHashChallenge(RegistryHashUtil.getHash());
        try {
            FastLoginMod.sendHashChallenge(challenge, connection);
            LOGGER.debug("[FastLogin] Sent hash challenge to {}", connection.getRemoteAddress());
        } catch (Exception e) {
            LOGGER.warn("[FastLogin] Failed to send hash challenge, will do full sync: {}", e.getMessage());
            ConnectionSkipTracker.markNoSkip(ch);
            MixinGameData.CURRENT_CHANNEL.remove();
        }
    }

    // -------------------------------------------------------------------------
    // Static handler called by FastLoginMod when C2SHashResponse arrives
    // -------------------------------------------------------------------------

    public static void handleHashResponse(
        HandshakeHandler handler,
        C2SHashResponse msg,
        Supplier<NetworkEvent.Context> ctxSupplier
    ) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        Connection connection    = ctx.getNetworkManager();

        if (msg.hasCache()) {
            LOGGER.info("[FastLogin] Client has matching registry cache – skipping S2CRegistry for {}",
                connection.getRemoteAddress());
            ConnectionSkipTracker.markSkip(connection.channel());
        } else {
            LOGGER.debug("[FastLogin] Client needs full registry sync for {}",
                connection.getRemoteAddress());
            ConnectionSkipTracker.markNoSkip(connection.channel());
        }

        ctx.setPacketHandled(true);
    }
}
