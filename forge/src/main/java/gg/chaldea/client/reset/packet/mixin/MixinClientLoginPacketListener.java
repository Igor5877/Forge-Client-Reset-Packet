package gg.chaldea.client.reset.packet.mixin;

import gg.chaldea.client.reset.packet.ClientReset;
import gg.chaldea.client.reset.packet.RegistryCache;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.protocol.login.ClientboundGameProfilePacket;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks into the login-success path to save the registry cache after a full sync.
 *
 * In MC 1.20.1 the packet is `ClientboundGameProfilePacket` and the handler
 * method on `ClientHandshakePacketListenerImpl` is `handleGameProfile`.
 * (Earlier versions used `ClientboundLoginSuccessPacket` / `handleLoginSuccess`.)
 */
@Mixin(ClientHandshakePacketListenerImpl.class)
@OnlyIn(Dist.CLIENT)
public class MixinClientLoginPacketListener {

    private static final Logger LOGGER = LogManager.getLogger();

    @Inject(
        method = "handleGameProfile(Lnet/minecraft/network/protocol/login/ClientboundGameProfilePacket;)V",
        at     = @At("TAIL")
    )
    private void crp$onLoginSuccess(ClientboundGameProfilePacket packet, CallbackInfo ci) {
        LOGGER.info("[FastLogin][T4] login_success");
        gg.chaldea.client.reset.packet.SeamlessTransition.tLoginSuccess = System.nanoTime();

        String hash = ClientReset.lastReceivedServerHash;
        if (hash == null) return;

        String hashCopy = hash;
        Thread saver = new Thread(() -> RegistryCache.saveRegistry(hashCopy), "CRP-RegistryCacheSaver");
        saver.setDaemon(true);
        saver.start();
    }
}
