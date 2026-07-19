package gg.chaldea.client.reset.packet;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import gg.chaldea.client.reset.packet.network.C2SHashResponse;
import gg.chaldea.client.reset.packet.network.S2CHashChallenge;
import gg.chaldea.client.reset.packet.network.S2CReset;
import net.minecraft.network.chat.Component;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkConstants;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.NetworkRegistry;
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
	 * Kill switch for Phase 2 chunk-buffer reuse (keepChunkBuffers).
	 * When false, keepChunkBuffers is never set and every transition does a
	 * full viewArea.releaseAllBuffers() — safe but slower (~200-500ms extra).
	 * When true, reuse is activated only when sameModset=true (same-backend
	 * registry fingerprint confirmed by Ambassador Velocity plugin).
	 */
	public static final boolean KEEP_BUFFERS_ENABLED = true;

	/**
	 * Kill switch for Phase 3 REI skip (skipRecipeEvents).
	 *
	 * When false, RecipesUpdatedEvent and TagsUpdatedEvent always fire normally —
	 * REI reloads all 71 plugins (~2.6s) on every switch. Safe but slow.
	 *
	 * When true, MixinClientPacketListenerRecipeSkip suppresses both events during
	 * the CRP login sequence, but ONLY when sameModset=true (Ambassador confirmed
	 * that old and new backends share identical Forge registry fingerprints, meaning
	 * the same mod jars → same recipes and tags). If fingerprints differ the events
	 * fire normally.
	 *
	 * The RecipeManager is still updated from the UpdateRecipesPacket data; tags are
	 * still applied from UpdateTagsPacket. Only the broadcast events are suppressed.
	 *
	 * Expected saving: ~1.7s (RecipesUpdatedEvent/REI END) + ~0.9s (TagsUpdatedEvent/
	 * REI START) = ~2.6s per switch, assuming the tag-event redirect matches.
	 */
	public static final boolean SKIP_RECIPE_EVENTS_ENABLED = true;

	/**
	 * RecipeCache — in-memory swap of RecipeManager.recipes Map on same-modset switch.
	 * Vanilla rebuild of ~10000 modded recipes takes ~3-4s; cache hit is O(1).
	 * Cache key = lastReceivedServerHash + content hash of recipe list, so any
	 * server-side /reload changing recipes auto-invalidates via content mismatch.
	 */
	public static final boolean RECIPE_CACHE_ENABLED = true;

	/**
	 * TagCache — marker-based cache that skips handleUpdateTags entirely when
	 * the same payload was already applied to the current registry state.
	 * Tags persist in registries across CRP soft resets, so re-binding the
	 * same map is wasted work (~100-300ms per switch).
	 */
	public static final boolean TAG_CACHE_ENABLED = true;

	/**
	 * RecipeSkipParse — skip the network DECODE of the recipe packet on a
	 * same-modset switch. RecipeCache (above) only saves post-decode work
	 * (replaceRecipes + setupCollections); the vanilla constructor still
	 * deserializes all ~18000 recipes off the wire (~1-1.5s) BEFORE our handler
	 * runs. When skipRecipeEvents=true (sameModset confirmed) AND we already hold
	 * a cached recipe set for the current fingerprint, MixinUpdateRecipesPacketSkip
	 * discards the recipe bytes without parsing them and we apply the cached
	 * recipes by fingerprint instead. Cold cache (fresh client) → parse normally.
	 */
	public static final boolean RECIPE_SKIP_PARSE_ENABLED = true;

	/**
	 * KeepClientModState — on a same-modset switch, suppress the
	 * ClientPlayerNetworkEvent LoggingOut/LoggingIn pair so client mods that
	 * rebuild on login/logout keep their state across the seamless switch.
	 * Fixes JEI's StartEventObserver force-rebuilding its ingredient filter
	 * (~2.7s render-thread freeze) on the first screen opened after each switch.
	 * Gated by sameModset; kill switch to disable if another mod misbehaves.
	 */
	public static final boolean KEEP_CLIENT_MOD_STATE_ENABLED = true;

	/**
	 * Dummy plugin message channel registered solely so Ambassador 1.5.x (non-api)
	 * detects this mod as CRP-capable via PlayerChannelRegisterEvent. The channel
	 * carries no real packets — its mere presence in the client's channel list
	 * (forwarded by Forge as part of the standard channel registration handshake)
	 * is enough for Ambassador.getResetType() to return ClientResetType.CRP.
	 *
	 * Without this, Ambassador 1.5.x falls back to kick-reset / disconnect,
	 * bypassing our soft-clearLevel optimization entirely.
	 */
	public static SimpleChannel crpDetectionChannel;
	private static final String CRP_DETECTION_VERSION = "1";

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
						.encoder((msg, buf) -> S2CHashChallenge.encode(msg, buf))
						.consumerNetworkThread(HandshakeHandler.biConsumerFor(ClientReset::handleHashChallenge))
						.add();
				// C2SHashResponse only needs an encoder on the client side (server decodes it).
				// Cast disambiguates between the BiConsumer and ToBooleanBiFunction overloads
				// of consumerNetworkThread in Forge 47.x's SimpleChannel.MessageBuilder.
				BiConsumer<C2SHashResponse, Supplier<NetworkEvent.Context>> hashResponseStub =
						(msg, ctx) -> ctx.get().setPacketHandled(true);
				handshakeChannel.messageBuilder(C2SHashResponse.class, 97)
						.loginIndex(C2SHashResponse::getLoginIndex, C2SHashResponse::setLoginIndex)
						.decoder(C2SHashResponse::decode)
						.encoder((msg, buf) -> C2SHashResponse.encode(msg, buf))
						.consumerNetworkThread(hashResponseStub)
						.add();
				logger.info(RESETMARKER, "Registered hash-challenge packets (IDs 96/97).");
			}

			// Register a dummy SimpleChannel "clientresetpacket:main" so Forge
			// announces it via the standard channel-register flow. Ambassador
			// 1.5.x reads this channel list via PlayerChannelRegisterEvent and
			// uses it for CRP capability detection.
			crpDetectionChannel = NetworkRegistry.ChannelBuilder
					.named(new ResourceLocation("clientresetpacket", "main"))
					.networkProtocolVersion(() -> CRP_DETECTION_VERSION)
					.clientAcceptedVersions(v -> true)
					.serverAcceptedVersions(v -> true)
					.simpleChannel();
			logger.info(RESETMARKER, "Registered detection channel 'clientresetpacket:main' for Ambassador 1.5.x");
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

        final long t0 = System.nanoTime();
        NetworkEvent.Context ctx = ctxSupplier.get();
        Connection connection = ctx.getNetworkManager();
        String hash = msg.getRegistryHash();

        logger.info(RESETMARKER, "[T0] challenge_received hash={}", hash);
        lastReceivedServerHash = hash;

        CompletableFuture.runAsync(() -> {
            long t1 = System.nanoTime();
            boolean hasCache = RegistryCache.hasCachedRegistry(hash);
            long checkMs = (System.nanoTime() - t1) / 1_000_000L;
            logger.info(RESETMARKER, "[T1] cache_check hash={} hasCache={} check_ms={}",
                hash, hasCache, checkMs);

            if (hasCache) {
                ctx.enqueueWork(() -> {
                    long t2 = System.nanoTime();
                    boolean ok = RegistryCache.restoreFromCache(hash);
                    long restoreMs = (System.nanoTime() - t2) / 1_000_000L;
                    logger.info(RESETMARKER, "[T2] cache_restore ok={} restore_ms={}", ok, restoreMs);
                    if (!ok) {
                        RegistryCache.clearAll();
                        lastReceivedServerHash = null;
                    }
                });
            }

            try {
                C2SHashResponse response = new C2SHashResponse(hasCache);
                handshakeChannel.reply(
                    response,
                    (NetworkEvent.Context) contextConstructor.newInstance(
                        connection, NetworkDirection.LOGIN_TO_CLIENT, 97)
                );
                long sentMs = (System.nanoTime() - t0) / 1_000_000L;
                logger.info(RESETMARKER, "[T3] response_sent hasCache={} total_ms_since_t0={}",
                    hasCache, sentMs);
            } catch (Exception e) {
                logger.error(RESETMARKER, "Помилка при відправці C2SHashResponse: {}", e.getMessage());
            }
        });

        ctx.setPacketHandled(true);
    }

	public static void sendMessage(String text) {
    	Minecraft mc = Minecraft.getInstance();
    	if (mc.player != null) {
        	mc.player.displayClientMessage(Component.literal("§6[ResetTimer] §f" + text), false);
    	}
    	logger.info(RESETMARKER, text);
	}

	/**
	 * PLAY-phase reset entry point — invoked from MixinClientPacketListenerReset
	 * when Ambassador 1.5.x sends PluginMessage("fml:handshake", {varint 98})
	 * while the client is already in PLAY state. Performs the same clearLevel +
	 * state transition + ACK as handleReset() but with no NetworkEvent.Context.
	 */
	@OnlyIn(Dist.CLIENT)
	public static void handlePlayPhaseReset(Connection connection) {
		logger.info(RESETMARKER, "[PLAY-reset] Received PLAY-phase S2CReset (Ambassador 1.5.x style)");
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			long startTime = System.currentTimeMillis();
			sendMessage("Початок очищення (PLAY)...");
			SeamlessTransition.resetMarkers();

			long captureStart = System.currentTimeMillis();
			// Carry ServerData across the reset so getCurrentServer() stays non-null
			// after the switch (see handleReset note) — keeps JEI bookmark saving alive.
			ServerData prevServerData = mc.getCurrentServer();
			// Only skip revertToFrozen when Ambassador VERIFIED matching registry
			// fingerprints (sameModset=true) - injecting on top of the previous
			// server's un-reverted registries otherwise leaves stale bindings
			// (e.g. TierSortingRegistry tool-tier data) that cause bugs like
			// "correct tool" checks failing until a full reconnect.
			if (mc.level == null || !SeamlessTransition.sameModset) GameData.revertToFrozen();
			FrozenFrameScreen transitionScreen = FrozenFrameScreen.capture(mc);
			SeamlessTransition.begin();
			if (SeamlessTransition.sameModset) {
				if (KEEP_BUFFERS_ENABLED) {
					SeamlessTransition.keepChunkBuffers = true;
					logger.info(RESETMARKER, "[Phase2/PLAY] keepChunkBuffers=true (sameModset)");
				}
				if (SKIP_RECIPE_EVENTS_ENABLED) {
					SeamlessTransition.skipRecipeEvents = true;
					logger.info(RESETMARKER, "[Phase3/PLAY] skipRecipeEvents=true — REI reload suppressed");
				}
				if (KEEP_CLIENT_MOD_STATE_ENABLED) {
					SeamlessTransition.keepClientModState = true;
					logger.info(RESETMARKER, "[Phase4/PLAY] keepClientModState=true — suppress login/logout churn (JEI etc.)");
				}
			}
			SeamlessTransition.sameModset = false; // consumed
			SeamlessTransition.softClear = true;
			try {
				mc.clearLevel(transitionScreen);
			} finally {
				SeamlessTransition.softClear = false;
			}
			sendMessage("Очищення рівня: " + (System.currentTimeMillis() - captureStart) + " мс");

			try {
				connection.channel().pipeline().remove("forge:forge_fixes");
				connection.channel().pipeline().remove("forge:vanilla_filter");
			} catch (NoSuchElementException ignored) {}

			NetworkHooks.registerClientLoginChannel(connection);
			connection.setProtocol(ConnectionProtocol.LOGIN);
			connection.setListener(new ClientHandshakePacketListenerImpl(
				connection, mc, prevServerData, null, false, Duration.ZERO, statusMessage -> {}
			));
			mc.pendingConnection = connection;

			try {
				handshakeChannel.reply(
					new HandshakeMessages.C2SAcknowledge(),
					(NetworkEvent.Context) contextConstructor.newInstance(connection, NetworkDirection.LOGIN_TO_CLIENT, 98)
				);
				logger.info(RESETMARKER, "[PLAY-reset] Sent C2SAcknowledge to Ambassador");
			} catch (Exception e) {
				logger.error(RESETMARKER, "[PLAY-reset] Failed to send ACK: " + e.getMessage());
			}

			sendMessage("Загальний час (PLAY reset): " + (System.currentTimeMillis() - startTime) + " мс");
		});
	}

	public static void handleReset(HandshakeHandler handler, S2CReset msg, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		Connection connection = context.getNetworkManager();

		if (context.getDirection() != NetworkDirection.LOGIN_TO_CLIENT && context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
			connection.disconnect(Component.literal("Illegal packet received, terminating connection"));
			throw new IllegalStateException("Invalid packet received, aborting connection");
		}

		logger.info(RESETMARKER, "Received reset packet from server.");

		// Capture the ServerData BEFORE handleClear() → clearLevel() drops the PLAY
		// listener. Minecraft.getCurrentServer() is derived from the listener's
		// ServerData (getConnection().getServerData()), so if we recreate the
		// handshake listener with null here it stays null after the switch — and
		// anything keyed on the current server breaks. Notably JEI resolves its
		// per-world bookmark path from getCurrentServer(); with null it silently
		// stops saving bookmarks. Carry the ServerData across the reset.
		ServerData prevServerData = Minecraft.getInstance().getCurrentServer();

		if (!handleClear(context)) {
			return;
		}

		NetworkHooks.registerClientLoginChannel(connection);
		connection.setProtocol(ConnectionProtocol.LOGIN);
		// 1.20.1 constructor: (Connection, Minecraft, ServerData, Screen, boolean quickPlay, Duration, Consumer<Component>)
		connection.setListener(new ClientHandshakePacketListenerImpl(
				connection, Minecraft.getInstance(), prevServerData, null, false, Duration.ZERO, statusMessage -> {}
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
    long startTime = System.currentTimeMillis();
    sendMessage("Початок очищення...");

    SeamlessTransition.resetMarkers();
    CompletableFuture<Void> future = context.enqueueWork(() -> {
        long captureStart = System.currentTimeMillis();

        Minecraft mc = Minecraft.getInstance();
        // See handlePlayPhaseReset for why this is gated on sameModset too.
        if (mc.level == null || !SeamlessTransition.sameModset) GameData.revertToFrozen();

        FrozenFrameScreen transitionScreen = FrozenFrameScreen.capture(mc);
        SeamlessTransition.begin();
        // Phase 2 + Phase 3: activate only when sameModset=true
        // (Ambassador confirmed matching registry fingerprints → same mod jars →
        // same blocks/items/recipes/tags).
        if (SeamlessTransition.sameModset) {
            if (KEEP_BUFFERS_ENABLED) {
                // Phase 2: reuse GPU chunk buffers — skip releaseAllBuffers()
                SeamlessTransition.keepChunkBuffers = true;
                logger.info(RESETMARKER, "[Phase2] keepChunkBuffers=true (sameModset)");
            }
            if (SKIP_RECIPE_EVENTS_ENABLED) {
                // Phase 3: suppress RecipesUpdatedEvent + TagsUpdatedEvent so REI
                // doesn't do a full ~2.6s plugin reload on identical recipe sets.
                SeamlessTransition.skipRecipeEvents = true;
                logger.info(RESETMARKER, "[Phase3] skipRecipeEvents=true — REI reload suppressed");
            }
            if (KEEP_CLIENT_MOD_STATE_ENABLED) {
                // Phase 4: suppress LoggingOut/LoggingIn so JEI (and other login/logout
                // listeners) keep their state across the seamless switch — no rebuild.
                SeamlessTransition.keepClientModState = true;
                logger.info(RESETMARKER, "[Phase4] keepClientModState=true — suppress login/logout churn (JEI etc.)");
            }
        }
        SeamlessTransition.sameModset = false; // consumed — reset for next cycle
        SeamlessTransition.softClear = true;
        try {
            mc.clearLevel(transitionScreen);
        } finally {
            SeamlessTransition.softClear = false;
        }

        sendMessage("Очищення рівня: " + (System.currentTimeMillis() - captureStart) + " мс");

        try {
            long pipelineStart = System.currentTimeMillis();
            context.getNetworkManager().channel().pipeline().remove("forge:forge_fixes");
            context.getNetworkManager().channel().pipeline().remove("forge:vanilla_filter");
            sendMessage("Pipeline cleanup: " + (System.currentTimeMillis() - pipelineStart) + " мс");
        } catch (NoSuchElementException ignored) {}
    });

    try {
        future.get();
        sendMessage("Загальний час переходу до логіну: " + (System.currentTimeMillis() - startTime) + " мс");
        return true;
    } catch (Exception ex) {
        sendMessage("Помилка при очищенні!");
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
