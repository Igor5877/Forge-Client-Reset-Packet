package gg.chaldea.server.fastlogin.mixin;

import com.mojang.authlib.GameProfile;
import gg.chaldea.server.fastlogin.FastLoginMod;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerNegotiationEvent;
import net.minecraftforge.network.HandshakeHandler;
import net.minecraftforge.network.LoginWrapper;
import net.minecraftforge.network.NetworkConstants;
import net.minecraftforge.network.NetworkRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Batch-flush FML handshake packets.
 *
 * Vanilla {@link HandshakeHandler#tickServer()} sends ONE LoginPayload per
 * server tick (~50 ms). With a 150-mod modpack that produces ~80 payloads
 * (29 registry + ~50 config-data), so the full handshake takes ~4 seconds
 * even though the actual byte production is sub-millisecond per packet —
 * the entire delay is artificial per-tick throttling.
 *
 * We inject at HEAD of tickServer, drain the whole messageList in a single
 * call, run the same future-cleanup + done-check logic the original does,
 * then cancel the original (return the same boolean it would have).
 *
 * Safe because:
 *   - Forge protocol orders packets by login index; netty preserves write
 *     order; client processes in the exact same sequence.
 *   - sentMessages still gets populated for every packet that needs a
 *     response, so the ACK accounting is identical.
 *   - The done-check is unchanged, so any state machine that depends on
 *     tickServer returning true at the right moment is untouched.
 */
@Mixin(value = HandshakeHandler.class, remap = false)
public abstract class MixinHandshakeHandlerBatch {

    private static final Logger BATCH_LOGGER = LogManager.getLogger("FastLogin/Batch");

    @Shadow @Final private static LoginWrapper loginWrapper;
    @Shadow @Final private Connection manager;
    @Shadow @Final private List<Future<Void>> pendingFutures;
    @Shadow private List<NetworkRegistry.LoginPayload> messageList;
    @Shadow private List<Integer> sentMessages;
    @Shadow private int packetPosition;
    @Shadow private boolean negotiationStarted;

    @Inject(method = "tickServer", at = @At("HEAD"), cancellable = true)
    private void fl$batchTickServer(CallbackInfoReturnable<Boolean> cir) {
        if (!FastLoginMod.BATCH_HANDSHAKE_ENABLED) {
            return;
        }

        if (!negotiationStarted) {
            GameProfile profile = ((ServerLoginPacketListenerImpl) manager.getPacketListener()).gameProfile;
            PlayerNegotiationEvent event = new PlayerNegotiationEvent(manager, profile, pendingFutures);
            MinecraftForge.EVENT_BUS.post(event);
            negotiationStarted = true;
        }

        // Drain the full backlog in one tick (vs vanilla's one-per-tick).
        long t0 = System.nanoTime();
        int drained = 0;
        LoginWrapperAccessor accessor = (LoginWrapperAccessor) (Object) loginWrapper;
        while (packetPosition < messageList.size()) {
            NetworkRegistry.LoginPayload message = messageList.get(packetPosition);
            if (message.needsResponse()) {
                sentMessages.add(packetPosition);
            }
            accessor.fl$sendServerToClientLoginPacket(
                    message.getChannelName(), message.getData(), packetPosition, manager);
            packetPosition++;
            drained++;
        }
        if (drained > 0) {
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            BATCH_LOGGER.info("[FastLogin/Batch] drained {} handshake packets in {} ms (was ~{} ms one-per-tick)",
                    drained, ms, drained * 50);
        }

        // Same completed-futures cleanup as vanilla tickServer.
        pendingFutures.removeIf(future -> {
            if (!future.isDone()) {
                return false;
            }
            try {
                future.get();
            } catch (ExecutionException ex) {
                BATCH_LOGGER.error("Error during negotiation", ex.getCause());
            } catch (CancellationException | InterruptedException ex) {
                // no-op — match vanilla behavior
            }
            return true;
        });

        // Same done-check as vanilla.
        if (sentMessages.isEmpty() && packetPosition >= messageList.size() - 1 && pendingFutures.isEmpty()) {
            manager.channel().attr(NetworkConstants.FML_HANDSHAKE_HANDLER).set(null);
            BATCH_LOGGER.debug("[FastLogin/Batch] Handshake complete!");
            cir.setReturnValue(true);
            return;
        }
        cir.setReturnValue(false);
    }
}
