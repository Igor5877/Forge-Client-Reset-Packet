package gg.chaldea.server.fastlogin.mixin;

import gg.chaldea.server.fastlogin.ChannelContext;
import gg.chaldea.server.fastlogin.ConnectionSkipTracker;
import io.netty.channel.Channel;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
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
 * Intercepts {@link NetworkRegistry#gatherLoginPayloads(NetworkDirection, boolean)}
 * which is the method Forge 47.x calls server-side to produce the list of login
 * payload packets (including S2CRegistry packets that carry the mod registries).
 *
 * If the current network thread is processing a connection that has been
 * marked "skip" by {@link ConnectionSkipTracker}, we return an empty list so
 * that no registry packets are sent and the handshake proceeds directly to
 * LoginSuccess.
 *
 * NOTE: The class name `MixinGameData` is historical — it now targets
 * {@link NetworkRegistry}.  We kept the file name to avoid churn in
 * mixins.fastlogin.json.
 *
 * We use a ThreadLocal to identify which connection the current network-thread
 * call belongs to, set just before HandshakeHandler calls gatherLoginPayloads().
 */
@Mixin(value = NetworkRegistry.class, remap = false)
public class MixinGameData {

    private static final Logger LOGGER = LogManager.getLogger();

    // CURRENT_CHANNEL lives in gg.chaldea.server.fastlogin.ChannelContext because
    // Mixin disallows non-private static fields on mixin classes.

    @Inject(
        method = "gatherLoginPayloads(Lnet/minecraftforge/network/NetworkDirection;Z)Ljava/util/List;",
        at     = @At("HEAD"),
        remap  = false,
        cancellable = true
    )
    private static void fl$maybeSkipSnapshot(NetworkDirection direction, boolean isLocal,
                                              CallbackInfoReturnable<List<?>> cir) {
        // Only intercept server→client login payloads; the C2S direction is not
        // generating registry sync packets.
        if (direction != NetworkDirection.LOGIN_TO_CLIENT) return;

        Channel ch = ChannelContext.CURRENT_CHANNEL.get();
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

        ChannelContext.CURRENT_CHANNEL.remove(); // Always clean up ThreadLocal

        Long sentAt = ConnectionSkipTracker.getChallengeSentAt(ch);
        long totalMs = sentAt == null ? -1L : (System.nanoTime() - sentAt) / 1_000_000L;

        if (ConnectionSkipTracker.shouldSkip(ch)) {
            LOGGER.info("[FastLogin][T2] gather_decision channel={} skip=true total_ms={}", ch, totalMs);
            cir.setReturnValue(Collections.emptyList());
        } else {
            LOGGER.info("[FastLogin][T2] gather_decision channel={} skip=false total_ms={}", ch, totalMs);
        }
    }
}
