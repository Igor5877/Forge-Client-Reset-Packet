package gg.chaldea.client.reset.packet.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import gg.chaldea.client.reset.packet.SeamlessTransition;
import gg.chaldea.client.reset.packet.FrozenFrameScreen;

@Mixin(ClientPacketListener.class)
public class MixinClientPacketListenerFix {

    private static final Logger LOGGER = LogManager.getLogger();

    @Inject(method = "handleLogin", at = @At("RETURN"))
    private void onJoinGame(ClientboundLoginPacket packet, CallbackInfo ci) {
        if (SeamlessTransition.tJoinGame == 0L && SeamlessTransition.tLoginSuccess != 0L) {
            SeamlessTransition.tJoinGame = System.nanoTime();
        }
    }

    // Тригер 1: Знімаємо екран при отриманні першого чанку світу
    @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"))
    private void onChunkReceived(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        if (SeamlessTransition.tFirstChunk == 0L && SeamlessTransition.tLoginSuccess != 0L) {
            SeamlessTransition.tFirstChunk = System.nanoTime();
            long sinceLogin = (SeamlessTransition.tFirstChunk - SeamlessTransition.tLoginSuccess) / 1_000_000L;
            long sinceReset = SeamlessTransition.tReset == 0L ? -1L
                : (SeamlessTransition.tFirstChunk - SeamlessTransition.tReset) / 1_000_000L;
            LOGGER.info("[FastLogin][T5] first_chunk since_login_ms={} since_reset_ms={}",
                sinceLogin, sinceReset);
        }
        // NOTE: skipRecipeEvents is NOT reset here.
        // In Forge 1.20.1 the server can send UpdateRecipesPacket AFTER the first chunk
        // (Recipes come later than Tags in the play-phase initialization sequence).
        // We therefore defer the reset to handleMovePlayer (T6) which is guaranteed
        // to arrive after both TagsUpdatedEvent and RecipesUpdatedEvent are processed.
        checkAndCloseLoadingScreen();
    }

    // Тригер 2 (Резервний/Phase3 reset): Знімаємо екран і скидаємо skipRecipeEvents
    // коли сервер позиціонує гравця. PlayerPositionPacket завжди приходить після
    // UpdateTags і UpdateRecipes, тому це безпечне місце для скидання Phase 3.
    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void onPlayerPosition(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        // Phase 3: reset skipRecipeEvents when server positions the player — transition
        // window is definitively over. Both TagsUpdatedEvent and RecipesUpdatedEvent
        // are guaranteed to have been processed before the server sends player position.
        // Any UpdateRecipesPacket arriving after player position (e.g. from a late
        // world-specific datapack reload) must fire normally.
        if (SeamlessTransition.skipRecipeEvents) {
            SeamlessTransition.skipRecipeEvents = false;
            LOGGER.info("[Phase3] skipRecipeEvents reset at player_position (T6) — REI can reload normally from now");
        }
        if (SeamlessTransition.tPlayerPosition == 0L && SeamlessTransition.tLoginSuccess != 0L) {
            SeamlessTransition.tPlayerPosition = System.nanoTime();
            logBreakdown();
        }
        checkAndCloseLoadingScreen();
    }

    private static long ms(long fromNs, long toNs) {
        return (fromNs == 0L || toNs == 0L) ? -1L : (toNs - fromNs) / 1_000_000L;
    }

    private static void logBreakdown() {
        long t4 = SeamlessTransition.tLoginSuccess;
        long tJoin = SeamlessTransition.tJoinGame;
        long tRec = SeamlessTransition.tRecipesApplied;
        long tTag = SeamlessTransition.tTagsApplied;
        long tPos = SeamlessTransition.tPlayerPosition;
        LOGGER.info(
            "[FastLogin][T6/breakdown] login→join={}ms join→recipes={}ms recipes→tags={}ms tags→pos={}ms total(T4→T6)={}ms",
            ms(t4, tJoin), ms(tJoin, tRec), ms(tRec, tTag), ms(tTag, tPos), ms(t4, tPos));
    }

    private void checkAndCloseLoadingScreen() {
        Minecraft mc = Minecraft.getInstance();
        
        // Перевіряємо, чи ми зараз у стані переходу, або чи висить екран завантаження
        if (SeamlessTransition.active || mc.screen instanceof ReceivingLevelScreen || mc.screen instanceof FrozenFrameScreen) {
            // Використовуємо execute для безпечної взаємодії з UI у головному потоці
            mc.execute(() -> {
                if (mc.screen != null) {
                    mc.setScreen(null); // Примусово закриваємо екран!
                }
            });
        }
    }
}