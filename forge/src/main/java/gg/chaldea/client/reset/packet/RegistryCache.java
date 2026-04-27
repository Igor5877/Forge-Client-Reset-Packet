package gg.chaldea.client.reset.packet;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.RegistryManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Persists the Forge registry ID mappings (RL → int) to disk after a
 * successful server login, and restores them on subsequent logins when the
 * server confirms the registry hash matches.
 *
 * Cache directory:  .minecraft/fastlogin-cache/
 * Cache file name:  {sha256-hash}.bin
 *
 * Binary format (DataOutputStream):
 *   [int]  number of registries
 *   for each registry:
 *     [UTF]  registry ResourceLocation  (e.g. "minecraft:blocks")
 *     [int]  number of ID entries
 *     for each entry:
 *       [UTF]  entry ResourceLocation   (e.g. "minecraft:stone")
 *       [int]  integer ID
 */
public class RegistryCache {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final String CACHE_DIR = "fastlogin-cache";

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** True when a cache file for this hash exists on disk. */
    public static boolean hasCachedRegistry(String hash) {
        return cacheFile(hash).toFile().exists();
    }

    /**
     * Saves the current ACTIVE Forge registry state to disk.
     * Must be called on or after LoginSuccess (registries are fully applied).
     */
    public static void saveRegistry(String hash) {
        try {
            Map<ResourceLocation, Map<ResourceLocation, Integer>> data = captureActiveRegistries();
            if (data.isEmpty()) {
                LOGGER.warn("[RegistryCache] Nothing to save – active registries are empty");
                return;
            }
            writeToDisk(hash, data);
            LOGGER.info("[RegistryCache] Saved registry cache for hash {}", hash);
        } catch (Exception e) {
            LOGGER.error("[RegistryCache] Failed to save registry cache: {}", e.getMessage());
        }
    }

    /**
     * Restores the registry ID mappings from the cached file.
     * Must be called AFTER {@link net.minecraftforge.registries.GameData#revertToFrozen()}
     * and BEFORE the handshake proceeds to PLAY state.
     *
     * @return true if the restore succeeded, false if it failed (caller should
     *         request a full sync fallback)
     */
    public static boolean restoreFromCache(String hash) {
        try {
            Map<ResourceLocation, Map<ResourceLocation, Integer>> data = readFromDisk(hash);
            applyToActiveRegistries(data);
            LOGGER.info("[RegistryCache] Restored {} registries from cache (hash {})",
                data.size(), hash);
            return true;
        } catch (Exception e) {
            LOGGER.error("[RegistryCache] Failed to restore from cache: {}", e.getMessage());
            return false;
        }
    }

    /** Deletes all cache files (e.g. after a mod update is detected). */
    public static void clearAll() {
        try {
            Path dir = cacheDir();
            if (Files.exists(dir)) {
                Files.list(dir)
                    .filter(p -> p.toString().endsWith(".bin"))
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
                LOGGER.info("[RegistryCache] Cache cleared");
            }
        } catch (Exception e) {
            LOGGER.warn("[RegistryCache] Failed to clear cache: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Reads all ACTIVE Forge registries and returns a map of
     * registryName → (entryRL → id).
     *
     * Uses RegistryManager.ACTIVE which is accessible via ObfuscationReflectionHelper
     * or direct field access (the field is package-private in Forge 43.x).
     *
     * TODO: verify field/method name against Forge 43.x sources.
     *       Candidates: RegistryManager.ACTIVE.registries (Map<RL, ForgeRegistry>)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<ResourceLocation, Map<ResourceLocation, Integer>> captureActiveRegistries()
        throws Exception
    {
        Map<ResourceLocation, Map<ResourceLocation, Integer>> result = new HashMap<>();

        // RegistryManager.ACTIVE is a public static field in Forge 43.x
        RegistryManager active = RegistryManager.ACTIVE;

        // registries is a Map<ResourceLocation, ForgeRegistry<?>> – package-private
        // TODO: verify field name; may need ObfuscationReflectionHelper
        var registriesField = RegistryManager.class.getDeclaredField("registries");
        registriesField.setAccessible(true);
        Map<ResourceLocation, ForgeRegistry<?>> registries =
            (Map<ResourceLocation, ForgeRegistry<?>>) registriesField.get(active);

        for (Map.Entry<ResourceLocation, ForgeRegistry<?>> entry : registries.entrySet()) {
            ForgeRegistry<?> reg = entry.getValue();
            Map<ResourceLocation, Integer> ids = new HashMap<>();

            // ForgeRegistry.getKeys() is public
            for (ResourceLocation key : reg.getKeys()) {
                int id = reg.getID(key);
                if (id >= 0) ids.put(key, id);
            }

            if (!ids.isEmpty()) result.put(entry.getKey(), ids);
        }

        return result;
    }

    /**
     * Applies the cached id mappings back to the ACTIVE Forge registries.
     *
     * Calls ForgeRegistry.loadIds() or the equivalent internal method.
     * TODO: verify method signature against Forge 43.x sources.
     *       Candidate: ForgeRegistry.loadIds(Map<ResourceLocation,Integer> ids, ...)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void applyToActiveRegistries(Map<ResourceLocation, Map<ResourceLocation, Integer>> data)
        throws Exception
    {
        RegistryManager active = RegistryManager.ACTIVE;
        var registriesField = RegistryManager.class.getDeclaredField("registries");
        registriesField.setAccessible(true);
        Map<ResourceLocation, ForgeRegistry<?>> registries =
            (Map<ResourceLocation, ForgeRegistry<?>>) registriesField.get(active);

        for (Map.Entry<ResourceLocation, Map<ResourceLocation, Integer>> entry : data.entrySet()) {
            ForgeRegistry<?> reg = registries.get(entry.getKey());
            if (reg == null) continue;

            // ForgeRegistry has internal setId / overrideId methods.
            // The safest public path is via ForgeRegistry.Snapshot + GameData.injectSnapshot.
            // TODO: use GameData.injectSnapshot(reg, snapshot, false) once we build a Snapshot.
            // For now we use reflection on ForgeRegistry directly.
            Method setId = ObfuscationReflectionHelper.findMethod(
                ForgeRegistry.class, "setId",
                ResourceLocation.class, int.class
            );
            setId.setAccessible(true);
            for (Map.Entry<ResourceLocation, Integer> idEntry : entry.getValue().entrySet()) {
                setId.invoke(reg, idEntry.getKey(), idEntry.getValue());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Disk I/O
    // -------------------------------------------------------------------------

    private static void writeToDisk(String hash, Map<ResourceLocation, Map<ResourceLocation, Integer>> data)
        throws IOException
    {
        Path file = cacheFile(hash);
        Files.createDirectories(file.getParent());
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(data.size());
            for (Map.Entry<ResourceLocation, Map<ResourceLocation, Integer>> reg : data.entrySet()) {
                out.writeUTF(reg.getKey().toString());
                out.writeInt(reg.getValue().size());
                for (Map.Entry<ResourceLocation, Integer> id : reg.getValue().entrySet()) {
                    out.writeUTF(id.getKey().toString());
                    out.writeInt(id.getValue());
                }
            }
        }
    }

    private static Map<ResourceLocation, Map<ResourceLocation, Integer>> readFromDisk(String hash)
        throws IOException
    {
        Map<ResourceLocation, Map<ResourceLocation, Integer>> result = new HashMap<>();
        Path file = cacheFile(hash);
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            int regCount = in.readInt();
            for (int r = 0; r < regCount; r++) {
                ResourceLocation regName = new ResourceLocation(in.readUTF());
                int idCount = in.readInt();
                Map<ResourceLocation, Integer> ids = new HashMap<>(idCount);
                for (int i = 0; i < idCount; i++) {
                    ids.put(new ResourceLocation(in.readUTF()), in.readInt());
                }
                result.put(regName, ids);
            }
        }
        return result;
    }

    private static Path cacheDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(CACHE_DIR);
    }

    private static Path cacheFile(String hash) {
        return cacheDir().resolve(hash + ".bin");
    }
}
