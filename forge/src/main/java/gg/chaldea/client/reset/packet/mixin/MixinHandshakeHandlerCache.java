package gg.chaldea.client.reset.packet.mixin;

import com.google.common.io.BaseEncoding;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.HandshakeHandler;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Client-side registry cache: skip GameData.injectSnapshot when the current
 * handshake's registry contents match what we've already injected once.
 *
 * Forge's HandshakeHandler.handleRegistryLoading enqueues GameData.injectSnapshot
 * on the render thread and blocks the netty IO thread on a CountDownLatch
 * until the injection finishes. For a 150-mod modpack with ~80 registries,
 * that blocking call takes ~3-4 seconds per switch — even with the server-side
 * batch send patch, this is the dominant remaining cost in the
 * reset → login_success window.
 *
 * Observation: GameData state persists across CRP-style soft resets (Phase 1
 * skips ForgeHooksClient.handleClientLevelClosing which would otherwise call
 * GameData.revertToFrozen). So if the next backend's registry snapshots match
 * the ones we've already injected, the GameData is already in the correct
 * state — no re-injection needed.
 *
 * Fingerprint: deterministic SHA-256 over (registry name → its sorted ID map)
 * for every registry the server sent. Sorted iteration of TreeMap guarantees
 * a stable byte stream regardless of HashMap iteration order in the outer Map.
 *
 * Safety: if the fingerprint doesn't match (different modset, mod update, etc.)
 * we fall through to the original injectSnapshot path — no behavior change.
 */
@Mixin(value = HandshakeHandler.class, remap = false)
public abstract class MixinHandshakeHandlerCache {

    private static final Logger LOGGER = LogManager.getLogger("CRP/RegCache");
    static volatile String crp$lastInjectedFingerprint = null;

    /** Called by MixinGameDataRevertToFrozen when GameData is being reset to
     *  its frozen state — any cached fingerprint becomes invalid because the
     *  GameData state we cached against has been wiped. */
    public static void crp$invalidate(String reason) {
        if (crp$lastInjectedFingerprint != null) {
            LOGGER.info("[RegCache] INVALIDATED (reason: {}) — was {}", reason,
                    crp$lastInjectedFingerprint.substring(0, 12) + "…");
            crp$lastInjectedFingerprint = null;
        }
    }

    @Shadow private Map<ResourceLocation, ForgeRegistry.Snapshot> registrySnapshots;

    @Inject(method = "handleRegistryLoading", at = @At("HEAD"), cancellable = true)
    private void crp$skipIfCached(Supplier<NetworkEvent.Context> ctx, CallbackInfoReturnable<Boolean> cir) {
        if (registrySnapshots == null || registrySnapshots.isEmpty()) {
            return;
        }
        String currentFp = crp$computeFingerprint(registrySnapshots);
        String cached = crp$lastInjectedFingerprint;
        if (currentFp.equals(cached)) {
            LOGGER.info("[RegCache] HIT — skipping GameData.injectSnapshot for fingerprint {} ({} registries)",
                    currentFp.substring(0, 12) + "…", registrySnapshots.size());
            cir.setReturnValue(true);
        } else {
            LOGGER.info("[RegCache] MISS — current={} cached={} (running full inject)",
                    currentFp.substring(0, 12) + "…",
                    cached == null ? "null" : cached.substring(0, 12) + "…");
        }
    }

    @Inject(method = "handleRegistryLoading", at = @At("RETURN"))
    private void crp$rememberFingerprint(Supplier<NetworkEvent.Context> ctx, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && registrySnapshots != null && !registrySnapshots.isEmpty()) {
            String fp = crp$computeFingerprint(registrySnapshots);
            // Avoid redundant store when we returned early via the HEAD inject above.
            if (!fp.equals(crp$lastInjectedFingerprint)) {
                crp$lastInjectedFingerprint = fp;
                LOGGER.info("[RegCache] STORED fingerprint {} ({} registries) after fresh injection",
                        fp.substring(0, 12) + "…", registrySnapshots.size());
            }
        }
    }

    private static volatile Field crp$idsField = null;
    private static volatile boolean crp$idsFieldResolved = false;

    /**
     * Resolve the "registry entries" field on ForgeRegistry.Snapshot lazily
     * via reflection. With Sinytra Connector loaded the class layout may differ
     * from our compile-time view (which caused NoSuchFieldError on direct
     * snap.ids access). We try common names; if all fail, fall back to a
     * weaker fingerprint that uses only the outer Map's keys (registry
     * names + count). Acceptable for a single-modpack multi-backend setup
     * where every backend serves identical registries.
     */
    private static Map<?, ?> crp$reflectIds(Object snap) {
        if (!crp$idsFieldResolved) {
            synchronized (MixinHandshakeHandlerCache.class) {
                if (!crp$idsFieldResolved) {
                    for (String name : new String[]{"ids", "f_ids", "entries", "registry"}) {
                        try {
                            Field f = snap.getClass().getDeclaredField(name);
                            f.setAccessible(true);
                            if (Map.class.isAssignableFrom(f.getType())) {
                                crp$idsField = f;
                                LOGGER.info("[RegCache] Resolved Snapshot ids field via reflection: {}", name);
                                break;
                            }
                        } catch (NoSuchFieldException ignored) {}
                    }
                    if (crp$idsField == null) {
                        // Last resort: first Map field on the class.
                        for (Field f : snap.getClass().getDeclaredFields()) {
                            if (Map.class.isAssignableFrom(f.getType())) {
                                f.setAccessible(true);
                                crp$idsField = f;
                                LOGGER.info("[RegCache] Falling back to first Map field on Snapshot: {}", f.getName());
                                break;
                            }
                        }
                    }
                    if (crp$idsField == null) {
                        LOGGER.warn("[RegCache] No Map field found on Snapshot — fingerprint will use names only");
                    }
                    crp$idsFieldResolved = true;
                }
            }
        }
        if (crp$idsField == null) return null;
        try {
            return (Map<?, ?>) crp$idsField.get(snap);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static String crp$computeFingerprint(Map<ResourceLocation, ForgeRegistry.Snapshot> snapshots) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            TreeMap<ResourceLocation, ForgeRegistry.Snapshot> sorted = new TreeMap<>(snapshots);
            for (Map.Entry<ResourceLocation, ForgeRegistry.Snapshot> e : sorted.entrySet()) {
                md.update(e.getKey().toString().getBytes());
                md.update((byte) '|');
                ForgeRegistry.Snapshot snap = e.getValue();
                Map<?, ?> ids = snap == null ? null : crp$reflectIds(snap);
                if (ids != null) {
                    // Sort the inner map keys lexicographically for determinism
                    // regardless of underlying Map impl on this Forge build.
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
