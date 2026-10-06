package gg.chaldea.server.fastlogin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.RegistryManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;

/**
 * Canonical registry-ID sync for Forge multi-backend networks.
 *
 * Problem: Forge assigns numerical IDs to registry entries (block IDs, item IDs,
 * etc.) at random during mod loading. Two backends with the same modset still
 * end up with different ID assignments (e.g. lobby gives steel=100, island
 * gives steel=4123), which forces the client to run a full FML registry sync
 * on every server switch.
 *
 * Approach: nominate the first-started backend as the canonical reference.
 * Save its ID mapping to a shared file. Every other backend reads that file
 * and patches its own level.dat in-memory BEFORE Forge reads it. Forge's
 * existing remap-on-load logic (designed for adding/removing mods to existing
 * saves) then re-assigns runtime IDs to match canonical.
 *
 * Safety: chunk files in MC 1.13+ store block names as strings, not integer
 * IDs. Re-mapping integer IDs does not corrupt existing chunks — Forge
 * resolves names against the current registry on chunk load.
 *
 * Lifecycle:
 *   1. ServerStartedEvent on backend A (first-ever startup of network):
 *      → saveCanonicalIfAbsent writes the JSON-NBT snapshot to canonical file
 *   2. ServerAboutToStartEvent on any backend → mixin on
 *      ForgeHooks.readAdditionalLevelSaveData → patchRootTag merges canonical
 *      into the in-memory level.dat NBT before Forge consumes it
 *   3. Forge's standard injectSnapshot path takes the patched NBT and
 *      re-assigns runtime IDs to match canonical
 *
 * File location: defaults to <server-root>/../canonical-registry-ids.nbt
 * (parent of world dir, so it's outside the world that gets regenerated for
 * dynamic island instances). Override with env var FASTLOGIN_CANONICAL_IDS_FILE.
 */
public final class CanonicalIdManager {

    private static final Logger LOGGER = LogManager.getLogger("FastLogin/CanonicalID");

    private CanonicalIdManager() {}

    /**
     * Local canonical file: <server-root>/canonical-registry-ids.nbt (parent of
     * the world dir). ONE resolver for both write (ServerStarted) and read
     * (patchRootTag). Until 2026-10 the writer used getWorldPath(ROOT) =
     * "./world/." whose parent is the WORLD dir, while the reader used the
     * server root — the written file was never read, so a server that started
     * while the proxy was down got no canonical at all and its non-persisted
     * registries (menus, particles, ...) mismatched every other backend.
     */
    private static Path canonicalPathForWorld(Path worldDir) {
        String fromEnv = System.getenv("FASTLOGIN_CANONICAL_IDS_FILE");
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return Paths.get(fromEnv);
        }
        Path parent = worldDir.toAbsolutePath().normalize().getParent();
        return (parent != null ? parent : worldDir).resolve("canonical-registry-ids.nbt");
    }

    private static Path resolveCanonicalPath(MinecraftServer server) {
        return canonicalPathForWorld(server.getWorldPath(LevelResource.ROOT));
    }

    /** Sidecar with the modset fingerprint the local canonical belongs to. */
    private static Path modsetSidecar(Path canonicalFile) {
        return canonicalFile.resolveSibling(canonicalFile.getFileName() + ".modset");
    }

    /**
     * Fingerprint of the loaded modset (sorted modId@version). A local canonical
     * is only trusted when it was written for the same modset — after a mod is
     * added/removed the old IDs no longer describe this server (2026-10-06:
     * removing 3 Packaged mods left every local file stale).
     */
    static String modsetFingerprint() {
        try {
            java.util.List<String> ids = new java.util.ArrayList<>();
            for (net.minecraftforge.forgespi.language.IModInfo m : net.minecraftforge.fml.ModList.get().getMods()) {
                ids.add(m.getModId() + "@" + m.getVersion());
            }
            java.util.Collections.sort(ids);
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(String.join("\n", ids).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * On ServerStartedEvent — register our current ID mapping with the
     * Ambassador proxy's HTTP endpoint. The proxy keeps the first-registered
     * snapshot as canonical and returns it to every subsequent caller. If
     * what we got back differs from what we sent, we save it to a local file;
     * the mixin will apply it to level.dat on the NEXT server restart.
     *
     * If FASTLOGIN_PROXY_URL is unset, falls back to writing local canonical
     * only (compatible with file-based deploys).
     */
    public static void saveCanonicalIfAbsent(MinecraftServer server) {
        // 1. Build our current ID snapshot as NBT-compressed bytes
        byte[] ourBytes;
        int snapCount;
        try {
            CompoundTag registries = new CompoundTag();
            // takeSnapshot(false) = the "synced" set (everything sent to the
            // client during FML handshake, ~32 registries) rather than
            // takeSnapshot(true) = "persisted" only (~21, what Forge itself
            // writes to level.dat). The extra ones (MENU_TYPES, PARTICLE_TYPES,
            // BLOCK_ENTITY_TYPES, RECIPE_SERIALIZERS, COMMAND_ARGUMENT_TYPES,
            // FLUID_TYPES, ...) aren't world-save-critical, but their numeric
            // IDs still get compared by the client's CRP fast-switch fingerprint
            // - leaving them out of canonical meant two backends with identical
            // mods never actually matched, so every switch fell back to the
            // slow full-reinject path. ForgeHooks.readAdditionalLevelSaveData
            // applies whatever keys are present in the "Registries" tag
            // generically (no persisted-only filter), so simply writing more
            // keys here is enough - no change needed on the read side.
            Map<ResourceLocation, ForgeRegistry.Snapshot> snap =
                    RegistryManager.ACTIVE.takeSnapshot(false);
            snapCount = snap.size();
            for (Map.Entry<ResourceLocation, ForgeRegistry.Snapshot> e : snap.entrySet()) {
                registries.put(e.getKey().toString(), e.getValue().write());
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(registries, new DataOutputStream(baos));
            ourBytes = baos.toByteArray();
        } catch (IOException e) {
            LOGGER.error("[CanonicalID] Failed to serialize own snapshot: {}", e.getMessage());
            return;
        }

        Path canonicalFile = resolveCanonicalPath(server);
        String proxyUrl = System.getenv("FASTLOGIN_PROXY_URL");

        if (proxyUrl != null && !proxyUrl.isEmpty()) {
            if (registerWithProxy(proxyUrl, server.getMotd(), ourBytes, snapCount, canonicalFile)) {
                return;
            }
            LOGGER.warn("[CanonicalID] Proxy at {} unreachable — will keep retrying in the background "
                    + "(every {} s for {} min)", proxyUrl, RETRY_EVERY_S, RETRY_FOR_MIN);
            scheduleRetries(proxyUrl, server.getMotd(), ourBytes, snapCount, canonicalFile);
            return;
        }

        // Standalone (no proxy): our own snapshot is the canonical; keep the file fresh.
        writeLocalCanonical(canonicalFile, ourBytes);
        LOGGER.info("[CanonicalID] WROTE local canonical: {} registries → {}", snapCount, canonicalFile);
    }

    private static final int RETRY_EVERY_S = 30;
    private static final int RETRY_FOR_MIN = 15;

    /**
     * POST our snapshot; on success refresh the local file with the canonical
     * the proxy holds (so a later start without the proxy still gets the
     * CURRENT canonical). Returns false if the proxy did not answer.
     */
    private static boolean registerWithProxy(String proxyUrl, String serverName, byte[] ourBytes,
                                             int snapCount, Path canonicalFile) {
        byte[] received = postToProxy(proxyUrl, serverName, ourBytes);
        if (received == null) {
            return false;
        }
        writeLocalCanonical(canonicalFile, received);
        if (Arrays.equals(received, ourBytes)) {
            LOGGER.info("[CanonicalID] Proxy confirms WE ARE canonical master ({} registries, {} bytes); local copy refreshed",
                    snapCount, ourBytes.length);
        } else {
            // Running IDs differ from canonical — only a restart applies them (the
            // mixin patches level.dat before Forge loads registries).
            LOGGER.warn("[CanonicalID] RESTART NEEDED: proxy canonical ({} bytes) differs from our running IDs "
                    + "({} bytes) — saved locally, applied on the next start; until then switches to/from this "
                    + "server use the slow path", received.length, ourBytes.length);
        }
        return true;
    }

    /** Proxy was down at startup (host boot order, proxy restart): register as soon as it is back. */
    private static void scheduleRetries(String proxyUrl, String serverName, byte[] ourBytes,
                                        int snapCount, Path canonicalFile) {
        java.util.concurrent.ScheduledExecutorService ex =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "FastLogin-CanonicalRetry");
                    t.setDaemon(true);  // never keep the JVM alive on shutdown
                    return t;
                });
        final int[] attempts = {0};
        final int maxAttempts = RETRY_FOR_MIN * 60 / RETRY_EVERY_S;
        ex.scheduleWithFixedDelay(() -> {
            attempts[0]++;
            if (registerWithProxy(proxyUrl, serverName, ourBytes, snapCount, canonicalFile)) {
                LOGGER.info("[CanonicalID] Registered with the proxy after {} retr{}", attempts[0],
                        attempts[0] == 1 ? "y" : "ies");
                ex.shutdown();
            } else if (attempts[0] >= maxAttempts) {
                LOGGER.warn("[CanonicalID] Proxy still unreachable after {} min — giving up until the next start",
                        RETRY_FOR_MIN);
                ex.shutdown();
            }
        }, RETRY_EVERY_S, RETRY_EVERY_S, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static void writeLocalCanonical(Path canonicalFile, byte[] bytes) {
        try {
            Path parent = canonicalFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = canonicalFile.resolveSibling(canonicalFile.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, canonicalFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(modsetSidecar(canonicalFile), modsetFingerprint());
        } catch (IOException e) {
            LOGGER.error("[CanonicalID] Failed to write local canonical at {}: {}", canonicalFile, e.getMessage());
        }
    }

    private static byte[] postToProxy(String proxyUrl, String serverName, byte[] body) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(proxyUrl + (proxyUrl.endsWith("/") ? "" : "/") + "canonical/register");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setRequestProperty("X-Server-Name", serverName == null ? "unknown" : serverName);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                LOGGER.warn("[CanonicalID] Proxy responded HTTP {}", code);
                return null;
            }
            try (InputStream is = conn.getInputStream()) {
                return is.readAllBytes();
            }
        } catch (IOException e) {
            LOGGER.warn("[CanonicalID] Proxy POST failed: {}", e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Called from MixinForgeHooks BEFORE Forge processes a level.dat's
     * "fml.Registries" tag. Replaces the in-memory entries with the canonical
     * ones (per-key MERGE — leaves untouched registries that exist on this
     * server but not in canonical).
     */
    public static void patchRootTag(CompoundTag rootTag, LevelStorageSource.LevelDirectory levelDirectory) {
        // 1. Try fetching from the proxy first — this enables single-restart
        //    sync when the proxy is reachable.
        CompoundTag canonical = tryFetchFromProxy();
        Path canonicalFile = null;

        // 2. Fall back to local canonical file.
        if (canonical == null) {
            // LevelDirectory.path() is a Java record component accessor.
            // ForgeGradle does NOT remap it (it's stored as f_230850_ in SRG),
            // so calling path() directly throws NoSuchMethodError at runtime.
            // dataFile() (→ m_230858_()) returns <worldRoot>/level.dat — its
            // parent is identical to path() and IS correctly remapped.
            Path worldRoot = levelDirectory.dataFile().getParent();
            canonicalFile = canonicalPathForWorld(worldRoot);

            if (!Files.exists(canonicalFile)) {
                LOGGER.info("[CanonicalID] No canonical from proxy or at {} — this server will become canonical after first start",
                        canonicalFile);
                return;
            }
            String stored = "";
            try {
                stored = Files.readString(modsetSidecar(canonicalFile)).trim();
            } catch (IOException ignored) {
                // no sidecar = file from before modset tracking — treat as stale
            }
            String current = modsetFingerprint();
            if (!stored.equals(current)) {
                LOGGER.warn("[CanonicalID] Local canonical at {} belongs to a different modset ({} vs current {}) — "
                        + "ignoring it; proxy unreachable, using this server's own IDs", canonicalFile,
                        stored.isEmpty() ? "unknown" : stored, current);
                return;
            }
            try {
                canonical = NbtIo.readCompressed(canonicalFile.toFile());
            } catch (IOException e) {
                LOGGER.error("[CanonicalID] Failed to read local canonical {}: {}", canonicalFile, e.getMessage());
                return;
            }
        }

        // Forge writes to: rootTag.fml.Registries[<regName>] = Snapshot.write()
        CompoundTag fml = rootTag.getCompound("fml");
        CompoundTag existingRegistries = fml.getCompound("Registries");

        int merged = 0;
        int added = 0;
        for (String regKey : canonical.getAllKeys()) {
            if (existingRegistries.contains(regKey)) {
                merged++;
            } else {
                added++;
            }
            existingRegistries.put(regKey, canonical.getCompound(regKey));
        }
        fml.put("Registries", existingRegistries);
        rootTag.put("fml", fml);

        String src = canonicalFile != null ? canonicalFile.toString() : "proxy";
        LOGGER.info("[CanonicalID] PATCHED level.dat in-memory: {} canonical registries (merged={}, added={}) from {}",
                canonical.getAllKeys().size(), merged, added, src);
    }

    /** Try fetching canonical from the proxy's HTTP endpoint. Returns null on
     *  any failure (proxy unreachable, no canonical registered yet, etc.). */
    private static CompoundTag tryFetchFromProxy() {
        String proxyUrl = System.getenv("FASTLOGIN_PROXY_URL");
        if (proxyUrl == null || proxyUrl.isEmpty()) return null;
        HttpURLConnection conn = null;
        try {
            URL url = new URL(proxyUrl + (proxyUrl.endsWith("/") ? "" : "/") + "canonical/get");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code == 404) {
                LOGGER.info("[CanonicalID] Proxy has no canonical yet (404)");
                return null;
            }
            if (code != 200) {
                LOGGER.warn("[CanonicalID] Proxy GET responded HTTP {}", code);
                return null;
            }
            byte[] bytes;
            try (InputStream is = conn.getInputStream()) {
                bytes = is.readAllBytes();
            }
            CompoundTag tag = NbtIo.readCompressed(new java.io.ByteArrayInputStream(bytes));
            LOGGER.info("[CanonicalID] Fetched canonical from proxy ({} bytes, {} registries)",
                    bytes.length, tag.getAllKeys().size());
            return tag;
        } catch (IOException e) {
            LOGGER.warn("[CanonicalID] Proxy GET failed: {}", e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
