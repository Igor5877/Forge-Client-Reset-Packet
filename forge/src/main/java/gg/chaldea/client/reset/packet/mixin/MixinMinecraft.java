package gg.chaldea.client.reset.packet.mixin;

import gg.chaldea.client.reset.packet.SeamlessTransition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
@OnlyIn(Dist.CLIENT)
public abstract class MixinMinecraft {

    @Shadow private void updateScreenAndTick(Screen p_91363_) { throw new AssertionError(); }

    /**
     * Suppress the "Downloading terrain..." screen during a seamless server transition.
     * Our FrozenFrameScreen keeps the last rendered frame visible until the new world is ready.
     */
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void crp$suppressLoadingScreen(Screen screen, CallbackInfo ci) {
        if (SeamlessTransition.active && screen instanceof ReceivingLevelScreen) {
            ci.cancel();
        }
    }

    // Phase 1 soft clearLevel — skip heavy work that's wasted between
    // server-to-server transitions (same modset, no real disconnect).

    @Redirect(
        method = "clearLevel(Lnet/minecraft/client/gui/screens/Screen;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;resetData()V")
    )
    private void crp$skipGameRendererReset(GameRenderer renderer) {
        if (!SeamlessTransition.softClear) {
            renderer.resetData();
        }
    }

    @Redirect(
        method = "clearLevel(Lnet/minecraft/client/gui/screens/Screen;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;updateScreenAndTick(Lnet/minecraft/client/gui/screens/Screen;)V")
    )
    private void crp$lightUpdateScreenAndTick(Minecraft self, Screen screen) {
        if (SeamlessTransition.softClear) {
            self.cameraEntity = null;
            self.pendingConnection = null;
            self.setScreen(screen);
        } else {
            updateScreenAndTick(screen);
        }
    }

    @Redirect(
        method = "clearLevel(Lnet/minecraft/client/gui/screens/Screen;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraftforge/client/ForgeHooksClient;handleClientLevelClosing(Lnet/minecraft/client/multiplayer/ClientLevel;)V")
    )
    private void crp$skipHandleClientLevelClosing(ClientLevel level) {
        if (!SeamlessTransition.softClear) {
            net.minecraftforge.client.ForgeHooksClient.handleClientLevelClosing(level);
        }
    }
}
