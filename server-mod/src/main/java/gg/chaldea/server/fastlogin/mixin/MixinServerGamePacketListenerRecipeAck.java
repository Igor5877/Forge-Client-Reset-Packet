package gg.chaldea.server.fastlogin.mixin;

import gg.chaldea.server.fastlogin.RecipeDeferState;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Catches the client's "fastlogin:recipe_ack" reply to a recipe_offer.
 * Runs at HEAD (netty thread, before ensureRunningOnSameThread) — both
 * ackOk/ackNeed only touch a concurrent map and Connection.send, which are
 * thread-safe, so no main-thread hop is needed.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MixinServerGamePacketListenerRecipeAck {

    @Shadow @Final public Connection connection;

    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true, require = 0)
    private void fl$handleRecipeAck(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
        if (!RecipeDeferState.ACK_ID.equals(packet.getIdentifier())) {
            return;
        }
        FriendlyByteBuf data = packet.getData();
        boolean ok = data != null && data.isReadable() && data.readBoolean();
        if (ok) {
            RecipeDeferState.ackOk(connection.channel());
        } else {
            RecipeDeferState.ackNeed(connection.channel());
        }
        ci.cancel();
    }
}
