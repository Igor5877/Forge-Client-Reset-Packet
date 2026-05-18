package gg.chaldea.server.fastlogin.mixin;

import gg.chaldea.server.fastlogin.ConnectionSkipTracker;
import io.netty.channel.Channel;
import net.minecraftforge.registries.GameData;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * Intercepts {@link GameData#buildSnapshotList()} which is the method Forge
 * calls server-side to produce the list of S2CRegistry packets.
 *
 * If the current network thread is processing a connection that has been
 * marked "skip" by {@link ConnectionSkipTracker}, we return an empty list so
 * that no registry packets are sent and the handshake proceeds directly to
 * LoginSuccess.
 *
 * We use a ThreadLocal to identify which connection the current network-thread
 * call belongs to, set just before HandshakeHandler calls buildSnapshotList().
 *
 * NOTE: Method name may differ across Forge 47.x builds.
 *       Verify with: grep -r "buildSnapshotList\|generateRegistryPackets\|takeSnapshot"
 *                         forge-1.20.1-47.2.0-sources.jar
 *
 *       Common candidates for Forge 47.x:
 *         - buildSnapshotList()
 *         - getCustomTagDiff(...)
 *         - generateRegistryPackets(...)
 */
@Mixin(value = GameData.class, remap = false)
public class MixinGameData {

    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * Set by {@link MixinHandshakeHandler} at the HEAD of handleClientModListOnServer,
     * before the original method body calls buildSnapshotList().  The ThreadLocal
     * identifies which Netty channel the current server-main-thread invocation is
     * serving so we can look up its skip state.
     */
    public static final ThreadLocal<Channel> CURRENT_CHANNEL = new ThreadLocal<>();

    /**
     * TODO: verify the exact method name/descriptor in Forge 47.2.0.
     *       Replace "buildSnapshotList" below with the correct name if needed.
     *       Candidates: buildSnapshotList, generateRegistryPackets, takeSnapshot
     */
    @Inject(
        method = "buildSnapshotList()Ljava/util/List;",
        at     = @At("HEAD"),
        remap  = false,
        cancellable = true
    )
    private static void fl$maybeSkipSnapshot(CallbackInfoReturnable<List<Pair<String, ?>>> cir) {
        Channel ch = CURRENT_CHANNEL.get();
        if (ch == null) return; // Not a connection we instrumented

        // Spin-wait for the client's C2SHashResponse, which arrives on the Netty IO
        // thread and updates ConnectionSkipTracker via ConcurrentHashSet (thread-safe).
        // The C2SHashResponse consumer runs directly on the IO thread (NOT via
        // enqueueWork) to avoid a deadlock where the main thread waits here while
        // the enqueued response is stuck behind this same main thread.
        //
        // Typical wait: < 200 ms (one network roundtrip).
        // Maximum wait: 3 seconds, then fall back to full sync.
        long deadline = System.currentTimeMillis() + 3_000L;
        while (ConnectionSkipTracker.isPending(ch)) {
            if (System.currentTimeMillis() > deadline) {
                LOGGER.warn("[FastLogin] Timed out waiting for hash response – falling back to full registry sync");
                ConnectionSkipTracker.markNoSkip(ch);
                break;
            }
            LockSupport.parkNanos(500_000L); // 0.5 ms
        }

        CURRENT_CHANNEL.remove(); // Always clean up ThreadLocal

        if (ConnectionSkipTracker.shouldSkip(ch)) {
            LOGGER.info("[FastLogin] Skipping registry sync for channel {}", ch);
            cir.setReturnValue(Collections.emptyList());
        }
    }
}
