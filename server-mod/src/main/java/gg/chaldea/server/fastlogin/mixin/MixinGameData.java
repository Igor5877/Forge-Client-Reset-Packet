package gg.chaldea.server.fastlogin.mixin;

import gg.chaldea.server.fastlogin.ConnectionSkipTracker;
import io.netty.channel.Channel;
import net.minecraftforge.registries.GameData;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.List;

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

    /**
     * A ThreadLocal set by {@link MixinHandshakeHandler} just before the
     * registry-packet generation call so we know which channel is being served.
     */
    public static final ThreadLocal<Channel> CURRENT_CHANNEL = new ThreadLocal<>();

    /**
     * TODO: verify the exact method name/descriptor in Forge 47.2.0.
     *       Replace "buildSnapshotList" below with the correct name if needed.
     */
    @Inject(
        method = "buildSnapshotList()Ljava/util/List;",
        at     = @At("HEAD"),
        remap  = false,
        cancellable = true
    )
    private static void fl$maybeSkipSnapshot(CallbackInfoReturnable<List<Pair<String, ?>>> cir) {
        Channel ch = CURRENT_CHANNEL.get();
        if (ch != null && ConnectionSkipTracker.shouldSkip(ch)) {
            // Return an empty list → Forge sends zero S2CRegistry packets
            cir.setReturnValue(Collections.emptyList());
        }
    }
}
