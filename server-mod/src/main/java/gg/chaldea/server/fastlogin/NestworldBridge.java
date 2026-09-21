package gg.chaldea.server.fastlogin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collection;

/**
 * Runtime bridge to NestworldCore's net.nestworld.api.NestworldApi, if present.
 * Deliberately reflection-based (no compile-time dependency) so this mod keeps
 * building/running unmodified on plain Forge -- NestworldCore is an optional,
 * runtime-detected core replacement, not a required dependency.
 */
public final class NestworldBridge {
    private static final Logger LOGGER = LogManager.getLogger("FastLogin/NestworldBridge");

    private static final boolean PRESENT;
    private static final MethodHandle IS_ACTIVE;
    private static final MethodHandle PREFETCH_CHUNKS;

    static {
        boolean present = false;
        MethodHandle isActive = null;
        MethodHandle prefetch = null;
        try {
            Class<?> api = Class.forName("net.nestworld.api.NestworldApi");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            isActive = lookup.findStatic(api, "isActive", MethodType.methodType(boolean.class));
            prefetch = lookup.findStatic(api, "prefetchChunks",
                    MethodType.methodType(void.class, ServerLevel.class, Collection.class));
            present = true;
            LOGGER.info("NestworldCore detected -- using its safe prefetchChunks API instead of Mixin-based parallel IO");
        } catch (Throwable t) {
            // Plain Forge, or an older NestworldCore build without this API yet.
            // Not an error -- this is the expected, common case.
            LOGGER.debug("NestworldCore not detected (or API not present): {}", t.toString());
        }
        PRESENT = present;
        IS_ACTIVE = isActive;
        PREFETCH_CHUNKS = prefetch;
    }

    private NestworldBridge() {}

    /** True if NestworldCore's API class is present AND its region system is active
     *  (i.e. this dimension is actually managed by NestworldCore, not just "the jar
     *  happens to be on the classpath"). Gate BOTH the Mixin-disable decision and the
     *  prefetchChunks call on this, not just class presence. */
    public static boolean isActive() {
        if (!PRESENT) return false;
        try {
            return (boolean) IS_ACTIVE.invoke();
        } catch (Throwable t) {
            LOGGER.warn("NestworldApi.isActive() threw, treating as inactive", t);
            return false;
        }
    }

    /** No-op if NestworldCore isn't active. Never throws -- a reflection failure here
     *  must never break a player's join, worst case they just don't get the prefetch
     *  speed benefit that tick. */
    public static void prefetchChunks(ServerLevel level, Collection<ChunkPos> positions) {
        if (!isActive()) return;
        try {
            PREFETCH_CHUNKS.invoke(level, positions);
        } catch (Throwable t) {
            LOGGER.warn("NestworldApi.prefetchChunks() failed, continuing without prefetch", t);
        }
    }
}
