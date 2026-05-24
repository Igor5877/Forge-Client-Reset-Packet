package gg.chaldea.server.fastlogin.mixin;

import gg.chaldea.server.fastlogin.ChannelContext;
import gg.chaldea.server.fastlogin.ConnectionSkipTracker;
import gg.chaldea.server.fastlogin.FastLoginMod;
import gg.chaldea.server.fastlogin.RegistryHashUtil;
import gg.chaldea.server.fastlogin.network.S2CHashChallenge;
import net.minecraft.network.Connection;
import net.minecraftforge.network.HandshakeHandler;
import net.minecraftforge.network.HandshakeMessages;
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
 * Injection point: HEAD of handleClientModListOnServer.  Sends S2CHashChallenge
 * to the client and flags the connection as "pending hash response" so the
 * companion MixinGameData (on NetworkRegistry.gatherLoginPayloads) knows to
 * spin-wait for the response before deciding whether to skip the registry sync.
 *
 * The C2SHashResponse arrival handler lives in FastLoginMod as an inline
 * consumerNetworkThread lambda (running on the Netty IO thread).
 */
@Mixin(value = HandshakeHandler.class, remap = false)
public class MixinHandshakeHandler {

    private static final Logger LOGGER = LogManager.getLogger();

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
        // when NetworkRegistry.gatherLoginPayloads() runs later on the same thread.
        ChannelContext.CURRENT_CHANNEL.set(ch);

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
            ChannelContext.CURRENT_CHANNEL.remove();
        }
    }
}
