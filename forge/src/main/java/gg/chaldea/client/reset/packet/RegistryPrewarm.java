package gg.chaldea.client.reset.packet;

import com.google.common.collect.Multimap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.GameData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Stage 0 pre-warm of the registry cache (docs/network/FAST-TRANSITION-PROTOCOL.md, 1a).
 *
 * Measured on a cold first join: RegCache MISS = 3.0 s of GameData.injectSnapshot on the join
 * path. RegCache only stores a FINGERPRINT whose meaning is "GameData in THIS process already
 * holds this snapshot", so the fingerprint itself must never be persisted. What is persisted
 * here are the INPUTS (the per-registry Snapshot NBT the server sent). On the next launch they
 * are REPLAYED through the very same GameData.injectSnapshot at the main menu, after which the
 * in-memory fingerprint is set and the real handshake HITs.
 *
 * Safety rules:
 *  - the replay is skipped (file deleted) when the client's own mod list / loader differs;
 *  - injectSnapshot builds a STAGING registry first and returns the missing entries WITHOUT
 *    touching ACTIVE, so a snapshot that does not fit is harmless;
 *  - if the next connection targets another host, or the player leaves the multiplayer path
 *    (singleplayer, realms), the injected state is reverted to frozen (revertToFrozen also
 *    invalidates the RegCache fingerprint through MixinGameDataRevertToFrozen);
 *  - a server whose registries differ simply MISSes and takes the normal path.
 * Kill switch: -Dcrp.prewarm=false.
 */
public final class RegistryPrewarm {

    private static final Logger LOGGER = LogManager.getLogger("CRP/RegPrewarm");

    public static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("crp.prewarm"));
    private static final int FORMAT = 1;

    /** Host of the most recent connection attempt (set by MixinConnectScreenPrewarm). */
    public static volatile String lastConnectHost = null;
    /** Host the replayed snapshot was recorded against; non-null only while the replay is active. */
    private static volatile String activeHost = null;
    private static volatile boolean replayAttempted = false;
    private static volatile String lastPersistedFingerprint = null;

    private RegistryPrewarm() {}

    private static Path file() {
        return FMLPaths.GAMEDIR.get().resolve("crp-cache").resolve("registry-snapshots.nbt");
    }

    /** Hash of the client's own loaded mods + loader: a different set invalidates any snapshot. */
    private static String clientKey() {
        try {
            TreeSet<String> mods = new TreeSet<>();
            ModList.get().getMods().forEach(m -> mods.add(m.getModId() + "@" + m.getVersion()));
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(("fmt" + FORMAT + "\n").getBytes(StandardCharsets.UTF_8));
            for (String s : mods) md.update((s + "\n").getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }

    /** Called right after a fresh successful injection of the server's snapshots. */
    public static void persistAsync(Map<ResourceLocation, ForgeRegistry.Snapshot> snapshots, String fingerprint) {
        if (!ENABLED || snapshots == null || snapshots.isEmpty()) return;
        if (fingerprint.equals(lastPersistedFingerprint)) return;
        final String key = clientKey();
        final String host = lastConnectHost;
        if (key == null || host == null) return;
        final CompoundTag root = new CompoundTag();
        try {
            // Serialise on the calling thread: later mutation of the handler's map cannot race this.
            root.putInt("format", FORMAT);
            root.putString("clientKey", key);
            root.putString("host", host.toLowerCase());
            root.putString("fingerprint", fingerprint);
            CompoundTag regs = new CompoundTag();
            for (Map.Entry<ResourceLocation, ForgeRegistry.Snapshot> e : snapshots.entrySet()) {
                regs.put(e.getKey().toString(), e.getValue().write());
            }
            root.put("registries", regs);
        } catch (Throwable t) {
            LOGGER.warn("[RegPrewarm] could not serialise snapshots, nothing persisted", t);
            return;
        }
        Thread t = new Thread(() -> writeAndVerify(root, fingerprint), "CRP-RegPrewarm-persist");
        t.setDaemon(true);
        t.start();
    }

    private static void writeAndVerify(CompoundTag root, String fingerprint) {
        Path target = file();
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            NbtIo.writeCompressed(root, tmp.toFile());
            // Self-check: what we would replay must hash exactly like what the server sent.
            Map<ResourceLocation, ForgeRegistry.Snapshot> back = decode(NbtIo.readCompressed(tmp.toFile()));
            String again = RegistryFingerprint.compute(back);
            if (!fingerprint.equals(again)) {
                Files.deleteIfExists(tmp);
                LOGGER.error("[RegPrewarm] self-check FAILED (fingerprint {} != {}); not persisted",
                        fingerprint.substring(0, 12), again.substring(0, 12));
                return;
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            lastPersistedFingerprint = fingerprint;
            LOGGER.info("[RegPrewarm] persisted {} registries, fingerprint {}…, {} bytes",
                    back.size(), fingerprint.substring(0, 12), Files.size(target));
        } catch (Throwable t) {
            LOGGER.warn("[RegPrewarm] persist failed", t);
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
        }
    }

    private static Map<ResourceLocation, ForgeRegistry.Snapshot> decode(CompoundTag root) {
        CompoundTag regs = root.getCompound("registries");
        Map<ResourceLocation, ForgeRegistry.Snapshot> out = new LinkedHashMap<>();
        for (String name : regs.getAllKeys()) {
            out.put(new ResourceLocation(name), ForgeRegistry.Snapshot.read(regs.getCompound(name)));
        }
        return out;
    }

    /** True while a replayed snapshot is injected and no connection has consumed or dropped it. */
    public static boolean isActive() {
        return activeHost != null;
    }

    /** Render thread, main menu: replay the persisted snapshots (once per JVM). */
    public static void replayAtMenu() {
        if (!ENABLED || replayAttempted) return;
        replayAttempted = true;
        Path target = file();
        if (!Files.isRegularFile(target)) {
            LOGGER.info("[RegPrewarm] no persisted snapshot yet (first launch): nothing to replay");
            return;
        }
        long t0 = System.nanoTime();
        try {
            CompoundTag root = NbtIo.readCompressed(target.toFile());
            if (root.getInt("format") != FORMAT || !root.getString("clientKey").equals(clientKey())) {
                LOGGER.info("[RegPrewarm] snapshot is for a different client mod set / format: discarded");
                Files.deleteIfExists(target);
                return;
            }
            Map<ResourceLocation, ForgeRegistry.Snapshot> snaps = decode(root);
            String fp = RegistryFingerprint.compute(snaps);
            if (!fp.equals(root.getString("fingerprint"))) {
                LOGGER.warn("[RegPrewarm] snapshot fingerprint mismatch on read: discarded");
                Files.deleteIfExists(target);
                return;
            }
            Multimap<ResourceLocation, ResourceLocation> missing = GameData.injectSnapshot(snaps, false, false);
            if (!missing.isEmpty()) {
                // ACTIVE was not touched (STAGING failed first); the file no longer fits this client.
                LOGGER.info("[RegPrewarm] snapshot does not fit this client ({} unknown entries): discarded", missing.size());
                Files.deleteIfExists(target);
                return;
            }
            RegistryCacheState.lastInjectedFingerprint = fp;
            lastPersistedFingerprint = fp;
            activeHost = root.getString("host");
            LOGGER.info("[RegPrewarm] replayed {} registries in {} ms at the main menu, fingerprint {}… (host {})",
                    snaps.size(), (System.nanoTime() - t0) / 1_000_000L, fp.substring(0, 12), activeHost);
        } catch (Throwable t) {
            LOGGER.error("[RegPrewarm] replay failed, restoring frozen registries", t);
            revert("replay failure");
            try { Files.deleteIfExists(target); } catch (Exception ignored) {}
        }
    }

    /** The real handshake took over (HIT or MISS): the replay has done its job. */
    public static void consumed() {
        activeHost = null;
    }

    /** Called when a connection to {@code host} starts. */
    public static void onConnect(String host) {
        lastConnectHost = host;
        String expected = activeHost;
        if (expected != null && !expected.equalsIgnoreCase(host)) {
            revert("connecting to " + host + ", snapshot was recorded against " + expected);
        }
    }

    /** Leave the replayed state: used when the player does not take the multiplayer path it was meant for. */
    public static void revert(String reason) {
        if (activeHost == null && RegistryCacheState.lastInjectedFingerprint == null) return;
        boolean wasActive = activeHost != null;
        activeHost = null;
        if (wasActive) {
            LOGGER.info("[RegPrewarm] reverting replayed registries ({})", reason);
            GameData.revertToFrozen(); // also invalidates the RegCache fingerprint via MixinGameDataRevertToFrozen
        }
    }
}
