package gg.chaldea.client.reset.packet.mixin;

import gg.chaldea.client.reset.packet.ClientReset;
import gg.chaldea.client.reset.packet.RegistryCache;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.protocol.login.ClientboundLoginSuccessPacket;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks into LoginSuccess to save the registry cache after a full sync.
 *
 * Packet class names differ between MC versions:
 *   1.19.2 → ClientboundLoginSuccessPacket  / handleLoginSuccess
 *   1.20.1 → ClientboundLoginFinishedPacket / handleLoginFinished
 *
 * This file targets 1.19.2.  When porting to 1.20.1 update both the import
 * and the method descriptor below.
 */
@Mixin(ClientHandshakePacketListenerImpl.class)
@OnlyIn(Dist.CLIENT)
public class MixinClientLoginPacketListener {

    private static final Logger LOGGER = LogManager.getLogger();

    @Inject(
        method = "handleLoginSuccess(Lnet/minecraft/network/protocol/login/ClientboundLoginSuccessPacket;)V",
        at     = @At("TAIL")
    )
    private void crp$onLoginSuccess(ClientboundLoginSuccessPacket packet, CallbackInfo ci) {
        String hash = ClientReset.lastReceivedServerHash;
        if (hash == null) return;

        // Save on a background thread so we do not stall the network event loop.
        // GameData is stable after LoginSuccess – reading registry keys is thread-safe here.
        String hashCopy = hash;
        Thread saver = new Thread(() -> RegistryCache.saveRegistry(hashCopy), "CRP-RegistryCacheSaver");
        saver.setDaemon(true);
        saver.start();

        LOGGER.info("[RegistryCache] Scheduled registry save for hash {}", hash);
    }
}
