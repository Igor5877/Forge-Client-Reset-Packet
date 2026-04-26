package gg.chaldea.client.reset.packet.mixin;

import gg.chaldea.client.reset.packet.SeamlessTransition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
@OnlyIn(Dist.CLIENT)
public class MixinMinecraft {

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
}
