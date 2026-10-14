package gg.chaldea.client.reset.packet.mixin;

import gg.chaldea.client.reset.packet.RegistryPrewarm;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells RegistryPrewarm which host a connection targets, so a snapshot replayed at the main
 * menu is reverted (not kept) if the player joins some other server than the one it was
 * recorded against. require = 1 on purpose: if the signature ever stops matching we want a
 * loud failure in testing, not a silently missing safety guard.
 */
@Mixin(ConnectScreen.class)
public abstract class MixinConnectScreenPrewarm {

    @Inject(method = "startConnecting", at = @At("HEAD"), require = 1)
    private static void crp$onStartConnecting(Screen parent, Minecraft minecraft, ServerAddress address,
                                              ServerData data, boolean quickPlay, CallbackInfo ci) {
        RegistryPrewarm.onConnect(address.getHost());
    }
}
