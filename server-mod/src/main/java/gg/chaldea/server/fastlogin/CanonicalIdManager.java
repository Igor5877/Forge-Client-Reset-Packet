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

    private static Path resolveCanonicalPath(MinecraftServer server) {
        String fromEnv = System.getenv("FASTLOGIN_CANONICAL_IDS_FILE");
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return Paths.get(fromEnv);
        }
        // Default: parent of world dir.
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        return worldRoot.getParent().resolve("canonical-registry-ids.nbt");
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
            Map<ResourceLocation, ForgeRegistry.Snapshot> snap =
                    RegistryManager.ACTIVE.takeSnapshot(true);
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
            byte[] received = postToProxy(proxyUrl, server.getMotd(), ourBytes);
            if (received != null) {
                if (Arrays.equals(received, ourBytes)) {
                    LOGGER.info("[CanonicalID] Proxy confirms WE ARE canonical master ({} registries, {} bytes)",
                            snapCount, ourBytes.length);
                } else {
                    LOGGER.info("[CanonicalID] Proxy returned different canonical ({} bytes vs our {} bytes) — saving for next restart",
                            received.length, ourBytes.length);
                    writeLocalCanonical(canonicalFile, received);
                }
                return;
            }
            LOGGER.warn("[CanonicalID] Proxy at {} unreachable — falling back to local-file canonical", proxyUrl);
        }

        // Fallback: local-file canonical (legacy/standalone mode)
        if (Files.exists(canonicalFile)) {
            LOGGER.info("[CanonicalID] Existing local canonical at {} — leaving alone", canonicalFile);
            return;
        }
        writeLocalCanonical(canonicalFile, ourBytes);
        LOGGER.info("[CanonicalID] WROTE local canonical: {} registries → {}", snapCount, canonicalFile);
    }

    private static void writeLocalCanonical(Path canonicalFile, byte[] bytes) {
        try {
            Path parent = canonicalFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(canonicalFile, bytes);
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
            Path worldRoot = levelDirectory.path();
            String fromEnv = System.getenv("FASTLOGIN_CANONICAL_IDS_FILE");
            canonicalFile = (fromEnv != null && !fromEnv.isEmpty())
                    ? Paths.get(fromEnv)
                    : worldRoot.getParent().resolve("canonical-registry-ids.nbt");

            if (!Files.exists(canonicalFile)) {
                LOGGER.info("[CanonicalID] No canonical from proxy or at {} — this server will become canonical after first start",
                        canonicalFile);
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
