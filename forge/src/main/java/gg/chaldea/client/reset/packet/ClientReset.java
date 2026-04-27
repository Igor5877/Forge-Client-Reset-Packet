package gg.chaldea.client.reset.packet;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.ibm.icu.impl.Pair;
import gg.chaldea.client.reset.packet.network.C2SHashResponse;
import gg.chaldea.client.reset.packet.network.S2CHashChallenge;
import gg.chaldea.client.reset.packet.network.S2CReset;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.Pack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.IExtensionPoint.DisplayTest;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.network.HandshakeHandler;
import net.minecraftforge.network.HandshakeMessages;
import net.minecraftforge.network.NetworkConstants;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.GameData;

@Mod("clientresetpacket")
public class ClientReset {

	public static final Field handshakeField;
	public static final Constructor contextConstructor;
	static final Logger logger = LogManager.getLogger();
	static final Marker RESETMARKER = MarkerManager.getMarker("RESETPACKET").setParents(MarkerManager.getMarker("FMLNETWORK"));

	public static SimpleChannel handshakeChannel;

	/**
	 * The last registry hash received from the server via S2CHashChallenge.
	 * MixinClientLoginPacketListener reads this after LoginSuccess to name the cache file.
	 */
	public static volatile String lastReceivedServerHash = null;

	public ClientReset() {
		IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
		bus.addListener(ClientReset::init);
		ModLoadingContext.get().registerExtensionPoint(DisplayTest.class, () -> new DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (a, b) -> true));
	}

	@SubscribeEvent
	public static void init(FMLCommonSetupEvent event) {
		if (handshakeField == null) {
			logger.error(RESETMARKER, "Failed to find FML's handshake channel. Disabling mod.");
			return;
		}
		if (contextConstructor == null) {
			logger.error(RESETMARKER, "Failed to find FML's network event context constructor. Disabling mod.");
			return;
		}
		try {
			//handshakeField.setAccessible(true);
			//contextConstructor.setAccessible(true);
			Object handshake = handshakeField.get(null);
			if (handshake instanceof SimpleChannel) {
				handshakeChannel = (SimpleChannel)handshake;
				logger.info(RESETMARKER, "Registering forge reset packet.");
				handshakeChannel.messageBuilder(S2CReset.class, 98)
						.loginIndex(S2CReset::getLoginIndex, S2CReset::setLoginIndex)
						.decoder(S2CReset::decode)
						.encoder(S2CReset::encode)
						.consumerNetworkThread(HandshakeHandler.biConsumerFor(ClientReset::handleReset))
						.add();
				logger.info(RESETMARKER, "Registered forge reset packet successfully.");

				// Hash-challenge protocol (IDs 96/97) – paired with the fastlogin server mod
				handshakeChannel.messageBuilder(S2CHashChallenge.class, 96)
						.loginIndex(S2CHashChallenge::getLoginIndex, S2CHashChallenge::setLoginIndex)
						.decoder(S2CHashChallenge::decode)
						.encoder(S2CHashChallenge::encode)
						.consumerNetworkThread(HandshakeHandler.biConsumerFor(ClientReset::handleHashChallenge))
						.add();
				// C2SHashResponse only needs an encoder on the client side (server decodes it)
				handshakeChannel.messageBuilder(C2SHashResponse.class, 97)
						.loginIndex(C2SHashResponse::getLoginIndex, C2SHashResponse::setLoginIndex)
						.decoder(C2SHashResponse::decode)
						.encoder(C2SHashResponse::encode)
						.consumerNetworkThread((msg, ctx) -> ctx.get().setPacketHandled(true))
						.add();
				logger.info(RESETMARKER, "Registered hash-challenge packets (IDs 96/97).");
			}
		}
		catch (Exception e) {
			logger.error(RESETMARKER, "Caught exception when attempting to utilize FML's handshake. Disabling mod. Exception: " + e.getMessage());
		}
	}

	/**
	 * Handles S2CHashChallenge from the fastlogin server mod.
	 *
	 * Checks whether the local cache contains registry data for the given hash:
	 *   - Cache hit  → restore registry from disk, reply hasCache=true (server skips S2CRegistry)
	 *   - Cache miss → reply hasCache=false (server does full sync, we save after LoginSuccess)
	 */
	@OnlyIn(Dist.CLIENT)
	public static void handleHashChallenge(HandshakeHandler handler, S2CHashChallenge msg,
			Supplier<NetworkEvent.Context> ctxSupplier) {
		NetworkEvent.Context ctx = ctxSupplier.get();
		Connection connection    = ctx.getNetworkManager();
		String hash              = msg.getRegistryHash();

		logger.info(RESETMARKER, "Received hash challenge from server: {}", hash);
		lastReceivedServerHash = hash;

		boolean hasCache = RegistryCache.hasCachedRegistry(hash);

		if (hasCache) {
			logger.info(RESETMARKER, "Registry cache HIT for hash {} – scheduling restore", hash);
			// Restore on the main thread so GameData is in the right state before handshake continues
			ctx.enqueueWork(() -> {
				boolean ok = RegistryCache.restoreFromCache(hash);
				if (!ok) {
					logger.warn(RESETMARKER, "Cache restore failed – server will do full sync on next connection");
					RegistryCache.clearAll();
					lastReceivedServerHash = null;
				}
			});
		} else {
			logger.info(RESETMARKER, "Registry cache MISS for hash {} – full sync will proceed", hash);
		}

		ctx.setPacketHandled(true);

		try {
			C2SHashResponse response = new C2SHashResponse(hasCache);
			handshakeChannel.reply(
				response,
				(NetworkEvent.Context) contextConstructor.newInstance(
					connection, NetworkDirection.LOGIN_TO_SERVER, 97)
			);
		} catch (Exception e) {
			logger.error(RESETMARKER, "Failed to send C2SHashResponse: {}", e.getMessage());
		}
	}

	public static void handleReset(HandshakeHandler handler, S2CReset msg, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		Connection connection = context.getNetworkManager();

		if (context.getDirection() != NetworkDirection.LOGIN_TO_CLIENT && context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
			connection.disconnect(Component.literal("Illegal packet received, terminating connection"));
			throw new IllegalStateException("Invalid packet received, aborting connection");
		}

		logger.info(RESETMARKER, "Received reset packet from server.");

		if (!handleClear(context)) {
			return;
		}

		NetworkHooks.registerClientLoginChannel(connection);
		connection.setProtocol(ConnectionProtocol.LOGIN);
		connection.setListener(new ClientHandshakePacketListenerImpl(
				connection, Minecraft.getInstance(), null, statusMessage -> {}
		));
		Minecraft.getInstance().pendingConnection = connection;
		context.setPacketHandled(true);
		try {
			handshakeChannel.reply(
				new HandshakeMessages.C2SAcknowledge(),
				(NetworkEvent.Context)contextConstructor.newInstance(connection, NetworkDirection.LOGIN_TO_CLIENT, 98)
			);
		}
		catch (Exception e) {
			logger.error(RESETMARKER, "Exception occurred when attempting to reply to reset packet.  Exception: " + e.getMessage());
			context.setPacketHandled(false);
			return;
		}
		logger.info(RESETMARKER, "Reset complete.");
	}

	@OnlyIn(Dist.CLIENT)
	public static boolean handleClear(NetworkEvent.Context context) {
		CompletableFuture<Void> future = context.enqueueWork(() -> {
			logger.debug(RESETMARKER, "Clearing");

			Minecraft mc = Minecraft.getInstance();

			// Preserve
			ServerData serverData = mc.getCurrentServer();
			Pack serverPack = mc.getClientPackSource().serverPack;

			// Clear
			if (mc.level == null) {
				// Ensure the GameData is reverted in case the client is reset during the handshake.
				GameData.revertToFrozen();
			}
			mc.getClientPackSource().serverPack = null;

			// Capture the current frame before clearing so we can show it during transition
			FrozenFrameScreen transitionScreen = FrozenFrameScreen.capture(mc);
			SeamlessTransition.begin();

			// Use our frozen frame screen instead of the dirt "Negotiating..." screen
			mc.clearLevel(transitionScreen);

			try {
				context.getNetworkManager().channel().pipeline().remove("forge:forge_fixes");
			} catch (NoSuchElementException ignored) {
			}
			try {
				context.getNetworkManager().channel().pipeline().remove("forge:vanilla_filter");
			} catch (NoSuchElementException ignored) {
			}
			// Restore
			mc.getClientPackSource().serverPack = serverPack;
			mc.setCurrentServer(serverData);
		});

		logger.debug(RESETMARKER, "Waiting for clear to complete");
		try {
			future.get();
			logger.debug("Clear complete, continuing reset");
			return true;
		} catch (Exception ex) {
			logger.error(RESETMARKER, "Failed to clear, closing connection", ex);
			context.getNetworkManager().disconnect(Component.literal("Failed to clear, closing connection"));
			return false;
		}
	}

	private static Field fetchHandshakeChannel() {
		try {
			return ObfuscationReflectionHelper.findField(NetworkConstants.class, "handshakeChannel");
		}
		catch (Exception e) {
			logger.error("Exception occurred while accessing handshakeChannel: " + e.getMessage());
			return null;
		}
	}

	private static Constructor fetchNetworkEventContext() {
		try {
			return ObfuscationReflectionHelper.findConstructor(NetworkEvent.Context.class, Connection.class, NetworkDirection.class, int.class);
		}
		catch (Exception e) {
			logger.error("Exception occurred while accessing getLoginIndex: " + e.getMessage());
			return null;
		}
	}

	static {
		handshakeField = fetchHandshakeChannel();
		contextConstructor = fetchNetworkEventContext();
	}
}
