package gg.chaldea.client.reset.packet.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
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

    // Тригер 1: Знімаємо екран при отриманні першого чанку світу
    @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"))
    private void onChunkReceived(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        if (SeamlessTransition.tFirstChunk == 0L && SeamlessTransition.tLoginSuccess != 0L) {
            SeamlessTransition.tFirstChunk = System.nanoTime();
            long ms = (SeamlessTransition.tFirstChunk - SeamlessTransition.tLoginSuccess) / 1_000_000L;
            LOGGER.info("[FastLogin][T5] first_chunk delta_since_login_success_ms={}", ms);
        }
        checkAndCloseLoadingScreen();
    }

    // Тригер 2 (Резервний): Знімаємо екран, коли сервер позиціонує гравця на острові
    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void onPlayerPosition(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        checkAndCloseLoadingScreen();
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