package gg.chaldea.server.fastlogin.mixin;

import gg.chaldea.server.fastlogin.ChannelContext;
import gg.chaldea.server.fastlogin.ConnectionSkipTracker;
import gg.chaldea.server.fastlogin.FastLoginMod;
import gg.chaldea.server.fastlogin.RegistryHashUtil;
import gg.chaldea.server.fastlogin.network.S2CHashChallenge;
import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraftforge.network.HandshakeHandler;
import net.minecraftforge.network.NetworkDirection;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Inject before NetworkRegistry.gatherLoginPayloads(...) is called inside the
 * HandshakeHandler constructor. We use @ModifyArg as a side-effecting hook —
 * it returns the same direction unchanged but does our setup before the call.
 *
 * Why @ModifyArg: Mixin disallows @Inject targeting constructors; @Redirect
 * would also work but requires calling the original (which is package-private
 * and not directly callable from outside the Forge package).
 *
 * After this fires, the existing MixinGameData hook on gatherLoginPayloads
 * sees a pending connection (via CURRENT_CHANNEL) and spin-waits for the
 * client's response.
 */
@Mixin(value = HandshakeHandler.class, remap = false)
public abstract class MixinHandshakeHandler {

    private static final Logger LOGGER = LogManager.getLogger();

    @Shadow @Final private Connection manager;

    @ModifyArg(
        method = "<init>(Lnet/minecraft/network/Connection;Lnet/minecraftforge/network/NetworkDirection;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraftforge/network/NetworkRegistry;gatherLoginPayloads(Lnet/minecraftforge/network/NetworkDirection;Z)Ljava/util/List;",
            remap = false
        ),
        index = 0,
        remap = false
    )
    private NetworkDirection fl$beforeGather(NetworkDirection direction) {
        if (!FastLoginMod.ENABLED) return direction;
        // Only act on the server-side LOGIN_TO_CLIENT direction over a real
        // (non-memory) network connection, with the registry hash computed.
        if (direction == NetworkDirection.LOGIN_TO_CLIENT
            && RegistryHashUtil.getHash() != null
            && this.manager != null
            && !this.manager.isMemoryConnection())
        {
            Channel ch = this.manager.channel();
            if (ch != null) {
                ChannelContext.CURRENT_CHANNEL.set(ch);
                ConnectionSkipTracker.markPending(ch);
                ConnectionSkipTracker.markChallengeSent(ch, System.nanoTime());

                S2CHashChallenge challenge = new S2CHashChallenge(RegistryHashUtil.getHash());
                try {
                    FastLoginMod.sendHashChallenge(challenge, this.manager);
                    LOGGER.info("[FastLogin][T0] challenge_sent (ctor) addr={} hash={}",
                        this.manager.getRemoteAddress(), RegistryHashUtil.getHash());
                } catch (Exception e) {
                    LOGGER.warn("[FastLogin] Failed to send hash challenge from ctor: {}", e.getMessage());
                    ConnectionSkipTracker.markNoSkip(ch);
                    ChannelContext.CURRENT_CHANNEL.remove();
                }
            }
        }
        return direction;
    }
}
