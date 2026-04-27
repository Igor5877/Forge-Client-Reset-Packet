package gg.chaldea.server.fastlogin;

import gg.chaldea.server.fastlogin.mixin.MixinHandshakeHandler;
import gg.chaldea.server.fastlogin.network.C2SHashResponse;
import gg.chaldea.server.fastlogin.network.S2CHashChallenge;
import net.minecraft.network.Connection;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.network.HandshakeHandler;
import net.minecraftforge.network.NetworkConstants;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.simple.SimpleChannel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

@Mod("fastlogin")
public class FastLoginMod {

    private static final Logger LOGGER = LogManager.getLogger();

    /** Packet IDs – must not clash with CRP (98) or Forge internals. */
    public static final int ID_S2C_CHALLENGE = 96;
    public static final int ID_C2S_RESPONSE  = 97;

    private static SimpleChannel handshakeChannel;
    private static Constructor   contextConstructor;

    public FastLoginMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        bus.addListener(FastLoginMod::onCommonSetup);
        MinecraftForge.EVENT_BUS.addListener(FastLoginMod::onServerStarted);
    }

    @SubscribeEvent
    private static void onCommonSetup(FMLCommonSetupEvent event) {
        try {
            Field f = ObfuscationReflectionHelper.findField(NetworkConstants.class, "handshakeChannel");
            handshakeChannel = (SimpleChannel) f.get(null);

            contextConstructor = ObfuscationReflectionHelper.findConstructor(
                NetworkEvent.Context.class, Connection.class, NetworkDirection.class, int.class);

            // S2C: server sends hash challenge
            handshakeChannel.messageBuilder(S2CHashChallenge.class, ID_S2C_CHALLENGE)
                .loginIndex(S2CHashChallenge::getLoginIndex, S2CHashChallenge::setLoginIndex)
                .decoder(S2CHashChallenge::decode)
                .encoder(S2CHashChallenge::encode)
                // Client-side handler is registered in the client CRP mod
                .consumerNetworkThread((msg, ctx) -> ctx.get().setPacketHandled(true))
                .add();

            // C2S: client responds with whether it has a matching cache
            handshakeChannel.messageBuilder(C2SHashResponse.class, ID_C2S_RESPONSE)
                .loginIndex(C2SHashResponse::getLoginIndex, C2SHashResponse::setLoginIndex)
                .decoder(C2SHashResponse::decode)
                .encoder(C2SHashResponse::encode)
                .consumerNetworkThread(HandshakeHandler.biConsumerFor(MixinHandshakeHandler::handleHashResponse))
                .add();

            LOGGER.info("[FastLogin] Registered hash-challenge handshake packets.");
        } catch (Exception e) {
            LOGGER.error("[FastLogin] Setup failed – mod will have no effect: {}", e.getMessage());
            handshakeChannel = null;
        }
    }

    @SubscribeEvent
    private static void onServerStarted(ServerStartedEvent event) {
        // Compute registry hash after all mods have registered everything
        RegistryHashUtil.computeAndCache();
    }

    /** Sends S2CHashChallenge on the FML handshake channel. */
    public static void sendHashChallenge(S2CHashChallenge challenge, Connection connection) throws Exception {
        if (handshakeChannel == null || contextConstructor == null) return;
        NetworkEvent.Context ctx = (NetworkEvent.Context) contextConstructor.newInstance(
            connection, NetworkDirection.LOGIN_TO_CLIENT, ID_S2C_CHALLENGE);
        handshakeChannel.reply(challenge, ctx);
    }
}
