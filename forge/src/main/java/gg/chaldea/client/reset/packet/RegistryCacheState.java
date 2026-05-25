package gg.chaldea.client.reset.packet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Holder for the registry-injection cache state.
 *
 * Lives outside the mixin package because Mixin 0.8.5 disallows non-private
 * static fields on mixin classes, and we need both MixinHandshakeHandlerCache
 * (HIT/MISS/STORE) and MixinGameDataRevertToFrozen (INVALIDATE) to share it.
 */
public final class RegistryCacheState {

    private static final Logger LOGGER = LogManager.getLogger("CRP/RegCache");

    /** SHA-256 fingerprint of the registry snapshots last successfully injected into GameData. */
    public static volatile String lastInjectedFingerprint = null;

    private RegistryCacheState() {}

    /** Called when GameData.revertToFrozen() wipes the registry state we cached against. */
    public static void invalidate(String reason) {
        if (lastInjectedFingerprint != null) {
            LOGGER.info("[RegCache] INVALIDATED (reason: {}) — was {}", reason,
                    lastInjectedFingerprint.substring(0, 12) + "…");
            lastInjectedFingerprint = null;
        }
    }
}
