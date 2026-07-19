package gg.chaldea.client.reset.packet.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import gg.chaldea.client.reset.packet.ClientReset;
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

    // Phase 4 — on a sameModset seamless switch, suppress the LoggingIn event (paired
    // with firePlayerLogout in MixinMinecraft) so JEI's StartEventObserver and other
    // login/logout listeners keep their state instead of resetting and rebuilding.
    // require=0: degrades to firing normally if the call site moves between versions.
    @Redirect(
        method = "handleLogin",
        at = @At(value = "INVOKE", target = "Lnet/minecraftforge/client/ForgeHooksClient;firePlayerLogin(Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/network/Connection;)V"),
        require = 0
    )
    private void crp$skipPlayerLoginOnSeamless(MultiPlayerGameMode gameMode, LocalPlayer player, Connection connection) {
        if (ClientReset.KEEP_CLIENT_MOD_STATE_ENABLED && SeamlessTransition.keepClientModState) {
            return; // suppressed — keep mods "logged in" across the seamless switch
        }
        net.minecraftforge.client.ForgeHooksClient.firePlayerLogin(gameMode, player, connection);
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
        if (SeamlessTransition.keepClientModState) {
            SeamlessTransition.keepClientModState = false;
            LOGGER.info("[Phase4] keepClientModState reset at player_position (T6) — login/logout events fire normally again");
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

        // Dismiss ONLY the transition's own loading/frozen screen — never a real GUI.
        // Previously this was gated on SeamlessTransition.active and closed whatever
        // screen was open (mc.screen != null) on every chunk/position packet. Since
        // `active` can leak true (FrozenFrameScreen.removed() didn't call end()), the
        // player got kicked out of inventory/chat every time a chunk loaded. Checking
        // the concrete screen type — re-checked inside execute() to avoid a race —
        // closes the transition screen without ever touching the player's own GUI.
        if (mc.screen instanceof ReceivingLevelScreen || mc.screen instanceof FrozenFrameScreen) {
            mc.execute(() -> {
                if (mc.screen instanceof ReceivingLevelScreen || mc.screen instanceof FrozenFrameScreen) {
                    mc.setScreen(null);
                }
            });
        }
    }
}