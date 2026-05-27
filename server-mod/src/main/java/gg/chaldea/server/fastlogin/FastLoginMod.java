package gg.chaldea.server.fastlogin;

import gg.chaldea.server.fastlogin.network.C2SHashResponse;
import gg.chaldea.server.fastlogin.network.S2CHashChallenge;
import net.minecraft.network.Connection;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
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

    /**
     * Master kill switch for the hash-challenge protocol. When false, the
     * server-side mixins skip the challenge send and the gather spin-wait,
     * so backend behaves like vanilla Forge handshake. Flip to true only
     * once the protocol is fully working end-to-end.
     */
    public static final boolean ENABLED = false;

    /**
     * Batch-flush all FML handshake packets in one server tick instead of
     * Forge's default "1 per tick". The vanilla HandshakeHandler.tickServer
     * sends a single LoginPayload per call, so ~80 packets take ~4 seconds
     * even though each packet's content is sub-millisecond to serialize.
     *
     * Measured impact on /myisland switch: reset → login_success drops
     * from ~5.3s to ~1.5s (saves ~3.5s — the dominant remaining cost).
     *
     * Safe because FML protocol orders packets by index, and the netty
     * pipeline preserves write order: the client processes them in the
     * exact same sequence it would have, just without the per-packet
     * tick delay.
     */
    public static final boolean BATCH_HANDSHAKE_ENABLED = true;

    /**
     * Flush ALL pending chunks to the player in a single server tick instead
     * of Minecraft's default batch (typically 10 chunks per tick).
     *
     * Without this, a 21×21 render-distance (441 sections) takes ~44 ticks
     * (~2.2s) to deliver.  With this, all chunks are sent in one shot as fast
     * as the network allows.
     *
     * Applied to ALL connections (not just CRP resets) — sending chunks faster
     * is always safe; the client can always handle more packets than the server's
     * throttle normally allows.  Flip to false to revert to vanilla behaviour.
     */
    public static final boolean CHUNK_FLUSH_ENABLED = true;

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

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        try {
            Field f = ObfuscationReflectionHelper.findField(NetworkConstants.class, "handshakeChannel");
            handshakeChannel = (SimpleChannel) f.get(null);

            contextConstructor = ObfuscationReflectionHelper.findConstructor(
                NetworkEvent.Context.class, Connection.class, NetworkDirection.class, int.class);

            // S2C: server sends hash challenge.
            // The cast disambiguates between BiConsumer and ToBooleanBiFunction
            // overloads of consumerNetworkThread on Forge 47.x.
            java.util.function.BiConsumer<S2CHashChallenge, java.util.function.Supplier<NetworkEvent.Context>> challengeStub =
                (msg, ctx) -> ctx.get().setPacketHandled(true);
            handshakeChannel.messageBuilder(S2CHashChallenge.class, ID_S2C_CHALLENGE)
                .loginIndex(S2CHashChallenge::getLoginIndex, S2CHashChallenge::setLoginIndex)
                .decoder(S2CHashChallenge::decode)
                .encoder(S2CHashChallenge::encode)
                // Client-side handler is registered in the client CRP mod
                .consumerNetworkThread(challengeStub)
                .add();

            // C2S: client responds with whether it has a matching cache.
            //
            // IMPORTANT: use a direct network-thread lambda, NOT HandshakeHandler.biConsumerFor().
            // biConsumerFor() routes through ctx.enqueueWork(), which schedules onto the server
            // main thread.  But the main thread may be blocking in MixinGameData.fl$maybeSkipSnapshot
            // waiting for this very response – using enqueueWork would deadlock.
            // Running directly on the Netty IO thread avoids the deadlock: ConnectionSkipTracker
            // uses ConcurrentHashSet and is safe to write from any thread.
            java.util.function.BiConsumer<C2SHashResponse, java.util.function.Supplier<NetworkEvent.Context>> responseHandler =
                (msg, ctxSupplier) -> {
                    NetworkEvent.Context ctx = ctxSupplier.get();
                    Connection conn = ctx.getNetworkManager();
                    Long sentAt = ConnectionSkipTracker.getChallengeSentAt(conn.channel());
                    long rttMs = sentAt == null ? -1L : (System.nanoTime() - sentAt) / 1_000_000L;
                    if (msg.hasCache()) {
                        LOGGER.info("[FastLogin][T1] response_received addr={} hasCache=true rtt_ms={}",
                            conn.getRemoteAddress(), rttMs);
                        ConnectionSkipTracker.markSkip(conn.channel());
                    } else {
                        LOGGER.info("[FastLogin][T1] response_received addr={} hasCache=false rtt_ms={}",
                            conn.getRemoteAddress(), rttMs);
                        ConnectionSkipTracker.markNoSkip(conn.channel());
                    }
                    ctx.setPacketHandled(true);
                };
            handshakeChannel.messageBuilder(C2SHashResponse.class, ID_C2S_RESPONSE)
                .loginIndex(C2SHashResponse::getLoginIndex, C2SHashResponse::setLoginIndex)
                .decoder(C2SHashResponse::decode)
                .encoder(C2SHashResponse::encode)
                .consumerNetworkThread(responseHandler)
                .add();

            LOGGER.info("[FastLogin] Registered hash-challenge handshake packets.");
        } catch (Exception e) {
            LOGGER.error("[FastLogin] Setup failed – mod will have no effect: {}", e.getMessage());
            handshakeChannel = null;
        }
    }

    private static void onServerStarted(ServerStartedEvent event) {
        // Compute registry hash after all mods have registered everything
        RegistryHashUtil.computeAndCache();
        // Phase 3 diagnostic — dump server-side fingerprint so we can compare
        // backends (lobby vs island) and confirm whether ID-sync is even needed.
        dumpRegistryFingerprint();
        // Canonical ID sync — first backend to start writes the reference file;
        // subsequent backends' level.dat is patched on next load via MixinForgeHooksReadLevelData.
        CanonicalIdManager.saveCanonicalIfAbsent(event.getServer());
    }

    private static volatile java.lang.reflect.Field SNAPSHOT_IDS_FIELD = null;
    private static volatile boolean SNAPSHOT_IDS_FIELD_RESOLVED = false;

    @SuppressWarnings("unchecked")
    private static java.util.Map<net.minecraft.resources.ResourceLocation, Integer>
            reflectSnapshotIds(net.minecraftforge.registries.ForgeRegistry.Snapshot snap) {
        if (!SNAPSHOT_IDS_FIELD_RESOLVED) {
            synchronized (FastLoginMod.class) {
                if (!SNAPSHOT_IDS_FIELD_RESOLVED) {
                    for (String name : new String[]{"ids", "f_ids", "entries"}) {
                        try {
                            java.lang.reflect.Field f = snap.getClass().getDeclaredField(name);
                            f.setAccessible(true);
                            if (java.util.Map.class.isAssignableFrom(f.getType())) {
                                SNAPSHOT_IDS_FIELD = f;
                                break;
                            }
                        } catch (NoSuchFieldException ignored) {}
                    }
                    if (SNAPSHOT_IDS_FIELD == null) {
                        for (java.lang.reflect.Field f : snap.getClass().getDeclaredFields()) {
                            if (java.util.Map.class.isAssignableFrom(f.getType())) {
                                f.setAccessible(true);
                                SNAPSHOT_IDS_FIELD = f;
                                break;
                            }
                        }
                    }
                    SNAPSHOT_IDS_FIELD_RESOLVED = true;
                }
            }
        }
        if (SNAPSHOT_IDS_FIELD == null) return java.util.Collections.emptyMap();
        try {
            return (java.util.Map<net.minecraft.resources.ResourceLocation, Integer>) SNAPSHOT_IDS_FIELD.get(snap);
        } catch (IllegalAccessException e) {
            return java.util.Collections.emptyMap();
        }
    }

    private static void dumpRegistryFingerprint() {
        try {
            java.util.Map<net.minecraft.resources.ResourceLocation,
                          net.minecraftforge.registries.ForgeRegistry.Snapshot> snap =
                net.minecraftforge.registries.RegistryManager.ACTIVE.takeSnapshot(false);
            java.util.TreeMap<net.minecraft.resources.ResourceLocation,
                              net.minecraftforge.registries.ForgeRegistry.Snapshot> sorted =
                new java.util.TreeMap<>(snap);
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder summary = new StringBuilder();
            for (java.util.Map.Entry<net.minecraft.resources.ResourceLocation,
                                     net.minecraftforge.registries.ForgeRegistry.Snapshot> e : sorted.entrySet()) {
                md.update(e.getKey().toString().getBytes());
                md.update((byte) '|');
                java.util.Map<net.minecraft.resources.ResourceLocation, Integer> ids = reflectSnapshotIds(e.getValue());
                java.util.TreeMap<net.minecraft.resources.ResourceLocation, Integer> idsSorted = new java.util.TreeMap<>(ids);
                for (java.util.Map.Entry<net.minecraft.resources.ResourceLocation, Integer> idEntry : idsSorted.entrySet()) {
                    md.update(idEntry.getKey().toString().getBytes());
                    md.update((byte) '=');
                    md.update(idEntry.getValue().toString().getBytes());
                    md.update((byte) ',');
                }
                md.update((byte) '\n');
                summary.append("  ").append(e.getKey()).append(" → ").append(ids.size()).append(" entries\n");
            }
            String fp = com.google.common.io.BaseEncoding.base16().lowerCase().encode(md.digest());
            LOGGER.info("[FastLogin/RegDump] Server-side registry fingerprint: {}", fp);
            LOGGER.info("[FastLogin/RegDump] {} registries breakdown:\n{}", sorted.size(), summary);
        } catch (Exception e) {
            LOGGER.warn("[FastLogin/RegDump] Failed to compute fingerprint: {}", e.getMessage());
        }
    }

    /**
     * Sends S2CHashChallenge from server to client on the FML handshake channel.
     *
     * We call sendTo() directly (not reply()) because reply() flips the context's
     * direction: passing LOGIN_TO_CLIENT to reply() actually sends LOGIN_TO_SERVER,
     * which the server cannot serialize (it's a client→server direction).
     */
    public static void sendHashChallenge(S2CHashChallenge challenge, Connection connection) throws Exception {
        if (handshakeChannel == null) return;
        handshakeChannel.sendTo(challenge, connection, NetworkDirection.LOGIN_TO_CLIENT);
    }
}
