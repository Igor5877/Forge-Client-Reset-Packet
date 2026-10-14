package gg.chaldea.client.reset.packet;

import com.google.common.io.BaseEncoding;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic SHA-256 fingerprint of a set of registry snapshots.
 *
 * Shared by MixinHandshakeHandlerCache (fingerprint of what the server sent) and
 * RegistryPrewarm (fingerprint of what was replayed from disk at the main menu):
 * both MUST hash identically, otherwise a replayed snapshot would never HIT.
 *
 * Moved verbatim out of the mixin: Mixin 0.8.5 forbids non-private static members on
 * mixin classes, and this logic is now needed outside of it.
 */
public final class RegistryFingerprint {

    private static final Logger LOGGER = LogManager.getLogger("CRP/RegCache");

    private static volatile Field idsField = null;
    private static volatile boolean idsFieldResolved = false;

    private RegistryFingerprint() {}

    /**
     * Resolve the "registry entries" field on ForgeRegistry.Snapshot lazily via reflection.
     * With Sinytra Connector loaded the class layout may differ from our compile-time view
     * (which caused NoSuchFieldError on direct snap.ids access). We try common names; if all
     * fail, fall back to a weaker fingerprint that uses only the outer Map's keys.
     */
    private static Map<?, ?> reflectIds(Object snap) {
        if (!idsFieldResolved) {
            synchronized (RegistryFingerprint.class) {
                if (!idsFieldResolved) {
                    for (String name : new String[]{"ids", "f_ids", "entries", "registry"}) {
                        try {
                            Field f = snap.getClass().getDeclaredField(name);
                            f.setAccessible(true);
                            if (Map.class.isAssignableFrom(f.getType())) {
                                idsField = f;
                                LOGGER.info("[RegCache] Resolved Snapshot ids field via reflection: {}", name);
                                break;
                            }
                        } catch (NoSuchFieldException ignored) {}
                    }
                    if (idsField == null) {
                        for (Field f : snap.getClass().getDeclaredFields()) {
                            if (Map.class.isAssignableFrom(f.getType())) {
                                f.setAccessible(true);
                                idsField = f;
                                LOGGER.info("[RegCache] Falling back to first Map field on Snapshot: {}", f.getName());
                                break;
                            }
                        }
                    }
                    if (idsField == null) {
                        LOGGER.warn("[RegCache] No Map field found on Snapshot — fingerprint will use names only");
                    }
                    idsFieldResolved = true;
                }
            }
        }
        if (idsField == null) return null;
        try {
            return (Map<?, ?>) idsField.get(snap);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    public static String compute(Map<ResourceLocation, ForgeRegistry.Snapshot> snapshots) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            TreeMap<ResourceLocation, ForgeRegistry.Snapshot> sorted = new TreeMap<>(snapshots);
            for (Map.Entry<ResourceLocation, ForgeRegistry.Snapshot> e : sorted.entrySet()) {
                md.update(e.getKey().toString().getBytes());
                md.update((byte) '|');
                ForgeRegistry.Snapshot snap = e.getValue();
                Map<?, ?> ids = snap == null ? null : reflectIds(snap);
                if (ids != null) {
                    TreeMap<String, Object> idsSorted = new TreeMap<>();
                    for (Map.Entry<?, ?> idEntry : ids.entrySet()) {
                        idsSorted.put(String.valueOf(idEntry.getKey()), idEntry.getValue());
                    }
                    for (Map.Entry<String, Object> idEntry : idsSorted.entrySet()) {
                        md.update(idEntry.getKey().getBytes());
                        md.update((byte) '=');
                        md.update(String.valueOf(idEntry.getValue()).getBytes());
                        md.update((byte) ',');
                    }
                }
                md.update((byte) '\n');
            }
            return BaseEncoding.base16().lowerCase().encode(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
