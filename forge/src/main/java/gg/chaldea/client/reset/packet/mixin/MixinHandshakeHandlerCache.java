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
    private static volatile String crp$lastInjectedFingerprint = null;

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

    private static String crp$computeFingerprint(Map<ResourceLocation, ForgeRegistry.Snapshot> snapshots) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // Iterate registries in sorted name order for determinism.
            TreeMap<ResourceLocation, ForgeRegistry.Snapshot> sorted = new TreeMap<>(snapshots);
            for (Map.Entry<ResourceLocation, ForgeRegistry.Snapshot> e : sorted.entrySet()) {
                md.update(e.getKey().toString().getBytes());
                md.update((byte) '|');
                ForgeRegistry.Snapshot snap = e.getValue();
                if (snap != null && snap.ids != null) {
                    // snap.ids is already a sorted TreeMap by Forge's design.
                    for (Map.Entry<ResourceLocation, Integer> id : snap.ids.entrySet()) {
                        md.update(id.getKey().toString().getBytes());
                        md.update((byte) '=');
                        md.update(id.getValue().toString().getBytes());
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
