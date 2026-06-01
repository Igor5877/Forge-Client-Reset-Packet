package gg.chaldea.client.reset.packet;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of the client-side RecipeManager state.
 *
 * Vanilla RecipeManager.replaceRecipes(Iterable) rebuilds a HashMap from
 * ~10000 modded recipes on every connect — measured ~3-4s on the test pack.
 * Since the same modset always produces the same recipe set (server-side
 * recipes are baked into the modpack), we can swap pre-built maps in O(1)
 * when the modset fingerprint AND the recipe-list hash both match the cache.
 *
 * Lifetime: in-memory only, lost on client restart. First connect after JVM
 * start runs vanilla path; subsequent same-modset switches hit the cache.
 *
 * Invalidation:
 *   - Modset different → different key, automatic miss.
 *   - Server reloaded recipes (/reload) → different content hash, automatic miss.
 *   - GameData.revertToFrozen → caller invokes invalidateAll().
 */
public class RecipeCache {

    private static final Logger LOGGER = LogManager.getLogger("CRP/RecipeCache");

    public static class CachedRecipes {
        public final Map recipes;
        public final Map byName;

        public CachedRecipes(Map recipes, Map byName) {
            this.recipes = recipes;
            this.byName = byName;
        }
    }

    private static final ConcurrentHashMap<String, CachedRecipes> CACHE = new ConcurrentHashMap<>();

    /**
     * Secondary index keyed by modset fingerprint only (the part of the cache
     * key before the ':' content hash). Lets RecipeSkipParse retrieve the
     * recipe set when the packet was discarded without parsing — we know the
     * fingerprint but not the content hash. Same modset → same baked recipes,
     * so the latest entry per fingerprint is the right one.
     */
    private static final ConcurrentHashMap<String, CachedRecipes> BY_FP = new ConcurrentHashMap<>();

    /**
     * Hash an iterable of recipes by XOR-combining (id.hashCode() * 31 ^ type.hashCode()).
     * Order-independent so the same recipe set always produces the same hash.
     * Caller must ensure the Iterable can be iterated again (List works).
     */
    public static long computeHash(Iterable<Recipe<?>> recipes) {
        long h = 0L;
        int count = 0;
        for (Recipe<?> r : recipes) {
            ResourceLocation id = r.getId();
            RecipeType<?> type = r.getType();
            long mix = ((long) id.hashCode()) * 31L ^ (long) type.hashCode();
            h ^= mix;
            count++;
        }
        return h ^ ((long) count << 32);
    }

    public static CachedRecipes get(String key) {
        return CACHE.get(key);
    }

    public static boolean contains(String key) {
        return CACHE.containsKey(key);
    }

    public static void put(String key, Map recipes, Map byName) {
        CachedRecipes cr = new CachedRecipes(recipes, byName);
        CACHE.put(key, cr);
        int sep = key.lastIndexOf(':');
        if (sep > 0) {
            BY_FP.put(key.substring(0, sep), cr);
        }
        LOGGER.info("[RecipeCache] STORED key={} ({} recipes, {} types)",
            key, byName.size(), recipes.size());
    }

    /** Retrieve cached recipes by modset fingerprint alone (no content hash).
     *  Used by RecipeSkipParse when the packet bytes were discarded. */
    public static CachedRecipes getByFingerprint(String modsetFp) {
        return modsetFp == null ? null : BY_FP.get(modsetFp);
    }

    public static void invalidateAll(String reason) {
        int n = CACHE.size();
        CACHE.clear();
        BY_FP.clear();
        if (n > 0) {
            LOGGER.info("[RecipeCache] INVALIDATED {} entries (reason: {})", n, reason);
        }
    }

    public static String makeKey(String modsetFp, long contentHash) {
        return modsetFp + ":" + Long.toHexString(contentHash);
    }
}
