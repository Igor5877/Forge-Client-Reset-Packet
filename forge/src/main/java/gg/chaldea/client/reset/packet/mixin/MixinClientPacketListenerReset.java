package gg.chaldea.client.reset.packet.mixin;

import gg.chaldea.client.reset.packet.ClientReset;
import gg.chaldea.client.reset.packet.RecipeCache;
import gg.chaldea.client.reset.packet.SeamlessTransition;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Catches PLAY-phase S2CReset from Ambassador 1.5.x.
 *
 * Ambassador 1.4.x sent reset via LoginPluginMessagePacket(98, "fml:loginwrapper", ...)
 * during LOGIN phase — handled by the standard SimpleChannel handler in ClientReset.
 *
 * Ambassador 1.5.x sends reset via PluginMessagePacket("fml:handshake", payload={varint 98})
 * during PLAY phase. Forge's IndexedMessageCodec may not dispatch this to our
 * SimpleChannel handler (different phase / different message type), so we
 * intercept at ClientPacketListener.handleCustomPayload directly.
 */
@Mixin(ClientPacketListener.class)
public class MixinClientPacketListenerReset {

    private static final Logger CRP_LOGGER = LogManager.getLogger("CRP/PlayPhase");

    @Shadow @Final private Connection connection;

    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void crp$interceptPlayPhaseMessages(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        ResourceLocation id = packet.getIdentifier();
        if (id == null) return;

        // fastlogin:same_modset — sent by Ambassador Velocity plugin before the CRP
        // reset when it confirms old and new backends share the same registry fingerprint.
        // Setting this flag enables Phase 2 chunk-buffer reuse in ClientReset.handleClear.
        if ("fastlogin".equals(id.getNamespace()) && "same_modset".equals(id.getPath())) {
            SeamlessTransition.sameModset = true;
            CRP_LOGGER.info("[sameModset] Received fastlogin:same_modset — keepChunkBuffers will activate");
            ci.cancel();
            return;
        }

        // fastlogin:recipe_offer — the server parked its multi-MB UpdateRecipesPacket
        // and offers the recipe epoch instead. If our cache holds the set for exactly
        // that epoch we apply it locally and ack ok (the big packet is never sent);
        // otherwise ack need and the server sends the full packet as usual.
        if ("fastlogin".equals(id.getNamespace()) && "recipe_offer".equals(id.getPath())) {
            FriendlyByteBuf data = packet.getData();
            long epoch = (data != null && data.readableBytes() >= 8) ? data.readLong() : 0L;
            RecipeCache.CachedRecipes cached =
                ClientReset.RECIPE_CACHE_ENABLED ? RecipeCache.getByEpoch(epoch) : null;
            boolean ok = cached != null;
            if (ok) {
                Minecraft.getInstance().execute(() -> {
                    ClientPacketListener self = (ClientPacketListener) (Object) this;
                    RecipeManagerAccessor acc = (RecipeManagerAccessor) self.getRecipeManager();
                    acc.crp$setRecipes(cached.recipes);
                    acc.crp$setByName(cached.byName);
                    if (SeamlessTransition.tRecipesApplied == 0L && SeamlessTransition.tLoginSuccess != 0L) {
                        SeamlessTransition.tRecipesApplied = System.nanoTime();
                    }
                    CRP_LOGGER.info("[RecipeDefer] epoch {} HIT — applied {} cached recipes, full packet skipped server-side",
                        Long.toHexString(epoch), cached.byName.size());
                });
            } else {
                // Remember the epoch so the incoming full packet's store path can
                // associate its cache entry with it for future switches.
                RecipeCache.lastOfferEpoch = epoch;
                CRP_LOGGER.info("[RecipeDefer] epoch {} MISS — requesting full recipe packet",
                    Long.toHexString(epoch));
            }
            FriendlyByteBuf ack = new FriendlyByteBuf(Unpooled.buffer(1));
            ack.writeBoolean(ok);
            connection.send(new ServerboundCustomPayloadPacket(
                new ResourceLocation("fastlogin", "recipe_ack"), ack));
            ci.cancel();
            return;
        }

        if (!"fml".equals(id.getNamespace()) || !"handshake".equals(id.getPath())) {
            return;
        }
        FriendlyByteBuf data = packet.getData();
        if (data == null || !data.isReadable()) {
            return;
        }
        int readerStart = data.readerIndex();
        try {
            int packetId = data.readVarInt();
            if (packetId == 98) {
                ClientReset.handlePlayPhaseReset(connection);
                ci.cancel();
                return;
            }
        } catch (Exception ignored) {
            // Not a parseable handshake packet — let Forge handle it.
        }
        data.readerIndex(readerStart);
    }
}
