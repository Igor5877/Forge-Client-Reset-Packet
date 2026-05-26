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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
     * On ServerStartedEvent — write our current ID mapping if no canonical
     * file exists yet. The first backend to start defines the canonical set.
     */
    public static void saveCanonicalIfAbsent(MinecraftServer server) {
        Path canonicalFile = resolveCanonicalPath(server);
        if (Files.exists(canonicalFile)) {
            LOGGER.info("[CanonicalID] Existing canonical at {} — leaving alone", canonicalFile);
            return;
        }
        try {
            CompoundTag registries = new CompoundTag();
            Map<ResourceLocation, ForgeRegistry.Snapshot> snap =
                    RegistryManager.ACTIVE.takeSnapshot(true);
            for (Map.Entry<ResourceLocation, ForgeRegistry.Snapshot> e : snap.entrySet()) {
                registries.put(e.getKey().toString(), e.getValue().write());
            }
            Path parent = canonicalFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            NbtIo.writeCompressed(registries, canonicalFile.toFile());
            LOGGER.info("[CanonicalID] WROTE canonical: {} registries → {}",
                    snap.size(), canonicalFile);
        } catch (IOException e) {
            LOGGER.error("[CanonicalID] Failed to write canonical: {}", e.getMessage());
        }
    }

    /**
     * Called from MixinForgeHooks BEFORE Forge processes a level.dat's
     * "fml.Registries" tag. Replaces the in-memory entries with the canonical
     * ones (per-key MERGE — leaves untouched registries that exist on this
     * server but not in canonical).
     */
    public static void patchRootTag(CompoundTag rootTag, LevelStorageSource.LevelDirectory levelDirectory) {
        // Find canonical file. We don't have a MinecraftServer here, so use
        // the world directory to derive the same path saveCanonicalIfAbsent
        // would use.
        Path worldRoot = levelDirectory.path();
        String fromEnv = System.getenv("FASTLOGIN_CANONICAL_IDS_FILE");
        Path canonicalFile = (fromEnv != null && !fromEnv.isEmpty())
                ? Paths.get(fromEnv)
                : worldRoot.getParent().resolve("canonical-registry-ids.nbt");

        if (!Files.exists(canonicalFile)) {
            LOGGER.info("[CanonicalID] No canonical at {} — this server will become canonical after first start",
                    canonicalFile);
            return;
        }

        try {
            CompoundTag canonical = NbtIo.readCompressed(canonicalFile.toFile());
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

            LOGGER.info("[CanonicalID] PATCHED level.dat in-memory: {} canonical registries (merged={}, added={}) from {}",
                    canonical.getAllKeys().size(), merged, added, canonicalFile);
        } catch (IOException e) {
            LOGGER.error("[CanonicalID] Failed to read canonical {}: {}", canonicalFile, e.getMessage());
        }
    }
}
