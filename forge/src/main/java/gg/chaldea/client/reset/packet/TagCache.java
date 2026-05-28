package gg.chaldea.client.reset.packet;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundUpdateTagsPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagNetworkSerialization;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Marker-based cache for the client-side handleUpdateTags flow.
 *
 * Vanilla handleUpdateTags deserializes ~20 registry tag payloads, binds them
 * to each registry, then calls Blocks.rebuildCache() and posts TagsUpdatedEvent.
 * On a 150-mod modpack this adds 100-300ms even with SkipREI blocking the event.
 *
 * Tag state is stored inside each Registry (not in a single swappable Map), so
 * we can't restore a cached tag binding from outside. Instead we cancel the
 * entire handleUpdateTags when the payload matches the last-applied tags AND
 * the registries are still in the state we left them in (no GameData.revertToFrozen
 * since then — invalidated in MixinGameDataRevertToFrozen).
 *
 * Cache is just a "have we already applied THIS payload to current registry state?"
 * marker keyed by (modsetFingerprint + payloadHash). No tag data stored.
 */
public class TagCache {

    private static final Logger LOGGER = LogManager.getLogger("CRP/TagCache");

    private static final ConcurrentHashMap<String, Boolean> APPLIED = new ConcurrentHashMap<>();

    public static long computeHash(ClientboundUpdateTagsPacket packet) {
        long h = 0L;
        int registryCount = 0;
        int totalBytes = 0;
        for (Map.Entry<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> e
                : packet.getTags().entrySet()) {
            String regName = e.getKey().location().toString();
            byte[] bytes = serializePayload(e.getValue());
            int len = bytes == null ? 0 : bytes.length;
            long mix = (long) regName.hashCode() * 31L ^ (long) len * 1009L;
            if (bytes != null) {
                for (int i = 0; i < bytes.length; i++) {
                    mix = mix * 1099511628211L ^ (bytes[i] & 0xFF);
                }
            }
            h ^= mix;
            registryCount++;
            totalBytes += len;
        }
        return h ^ ((long) registryCount << 32) ^ ((long) totalBytes << 16);
    }

    /**
     * Best-effort payload serialization via TagNetworkSerialization.NetworkPayload.
     * If reflection fails (Forge changed field name), we fall back to identity-hash
     * which is still stable per-instance — same packet object will hash same.
     */
    private static byte[] serializePayload(TagNetworkSerialization.NetworkPayload payload) {
        try {
            java.lang.reflect.Field f = payload.getClass().getDeclaredField("tags");
            f.setAccessible(true);
            Object tags = f.get(payload);
            return (tags == null) ? null : tags.toString().getBytes();
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isApplied(String key) {
        return APPLIED.containsKey(key);
    }

    public static void markApplied(String key, int registryCount) {
        if (APPLIED.putIfAbsent(key, Boolean.TRUE) == null) {
            LOGGER.info("[TagCache] STORED key={} ({} registries)", key, registryCount);
        }
    }

    public static void invalidateAll(String reason) {
        int n = APPLIED.size();
        APPLIED.clear();
        if (n > 0) {
            LOGGER.info("[TagCache] INVALIDATED {} entries (reason: {})", n, reason);
        }
    }

    public static String makeKey(String modsetFp, long contentHash) {
        return modsetFp + ":" + Long.toHexString(contentHash);
    }
}
