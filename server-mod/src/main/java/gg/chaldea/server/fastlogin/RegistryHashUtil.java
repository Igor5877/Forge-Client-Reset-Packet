package gg.chaldea.server.fastlogin;

import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * Computes a stable SHA-256 fingerprint of the server's registry state.
 *
 * The hash covers every ResourceLocation key in every Forge registry, sorted
 * alphabetically so the result is independent of registration order.  Any mod
 * addition, removal, or change that adds/removes registry entries will change
 * the hash and trigger a full sync for the next client connection.
 */
public class RegistryHashUtil {

    private static final Logger LOGGER = LogManager.getLogger();

    // Registries that participate in the FML registry sync
    private static final List<IForgeRegistry<?>> TRACKED_REGISTRIES = List.of(
        ForgeRegistries.BLOCKS,
        ForgeRegistries.ITEMS,
        ForgeRegistries.ENTITY_TYPES,
        ForgeRegistries.BLOCK_ENTITY_TYPES,
        ForgeRegistries.BIOMES,
        ForgeRegistries.SOUND_EVENTS,
        ForgeRegistries.ENCHANTMENTS,
        ForgeRegistries.MOB_EFFECTS,
        ForgeRegistries.POTIONS,
        ForgeRegistries.PARTICLE_TYPES,
        ForgeRegistries.MENU_TYPES,
        ForgeRegistries.RECIPE_TYPES,
        ForgeRegistries.RECIPE_SERIALIZERS,
        ForgeRegistries.ATTRIBUTES,
        ForgeRegistries.PAINTING_VARIANTS
        // CAT_VARIANTS and FROG_VARIANTS moved to vanilla BuiltInRegistries in 1.20.1
        // and are no longer exposed through ForgeRegistries; they are also not
        // synced via the FML registry sync, so excluding them is correct.
    );

    private static volatile String cachedHash = null;

    /** Called once on server startup after all mods are registered. */
    public static void computeAndCache() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");

            for (IForgeRegistry<?> registry : TRACKED_REGISTRIES) {
                // Registry name as a separator so two registries can't collide
                md.update(registry.getRegistryName().toString().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);

                // Sort keys for deterministic order regardless of mod load order
                registry.getKeys().stream()
                    .map(Object::toString)
                    .sorted()
                    .forEach(key -> {
                        md.update(key.getBytes(StandardCharsets.UTF_8));
                        md.update((byte) 0);
                    });
            }

            cachedHash = HexFormat.of().formatHex(md.digest());
            LOGGER.info("[FastLogin] Registry hash computed: {}", cachedHash);

        } catch (Exception e) {
            LOGGER.error("[FastLogin] Failed to compute registry hash", e);
            cachedHash = null;
        }
    }

    public static String getHash() {
        return cachedHash;
    }

    /** Returns true if the server hash is known and matches the client-provided value. */
    public static boolean matches(String clientHash) {
        return cachedHash != null && cachedHash.equals(clientHash);
    }
}
