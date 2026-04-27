package gg.chaldea.client.reset.packet.mixin;

import gg.chaldea.client.reset.packet.RegistryCache;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks into the moment the client receives LoginFinished / LoginSuccess from
 * the server.  At this point all S2CRegistry packets have already been
 * processed and GameData contains the fully applied server registry state.
 *
 * We use this opportunity to persist the registry to the local cache so that
 * the NEXT login (or server switch) can skip the registry sync entirely when
 * the server hash matches.
 *
 * The server hash is stored by {@link gg.chaldea.client.reset.packet.ClientReset}
 * when it processes the S2CHashChallenge packet and placed in
 * {@link gg.chaldea.client.reset.packet.ClientReset#lastReceivedServerHash}.
 *
 * NOTE: In Forge 43.x the finish-login packet may arrive via a different code
 * path (LoginSuccess is wrapped in a Forge handshake ack).  The method name
 * below ("handleLoginFinished") is the MojMap name for 1.19.2.
 * TODO: verify against decompiled 1.19.2 sources if the mixin fails to apply.
 */
@Mixin(ClientHandshakePacketListenerImpl.class)
@OnlyIn(Dist.CLIENT)
public class MixinClientLoginPacketListener {

    private static final Logger LOGGER = LogManager.getLogger();

    @Inject(
        method = "handleLoginFinished(Lnet/minecraft/network/protocol/login/ClientboundLoginFinishedPacket;)V",
        at     = @At("TAIL")
    )
    private void crp$onLoginFinished(ClientboundLoginFinishedPacket packet, CallbackInfo ci) {
        String hash = gg.chaldea.client.reset.packet.ClientReset.lastReceivedServerHash;
        if (hash == null) return;

        // Save async so we don't stall the network thread
        String hashCopy = hash;
        Thread saver = new Thread(() -> RegistryCache.saveRegistry(hashCopy), "CRP-RegistryCacheSaver");
        saver.setDaemon(true);
        saver.start();

        LOGGER.info("[RegistryCache] Scheduled registry save for hash {}", hash);
    }
}
