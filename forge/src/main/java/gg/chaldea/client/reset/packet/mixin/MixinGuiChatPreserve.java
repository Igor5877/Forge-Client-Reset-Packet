package gg.chaldea.client.reset.packet.mixin;

import gg.chaldea.client.reset.packet.SeamlessTransition;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Preserve chat scrollback and command-recall history (up-arrow) across a
 * seamless server-to-server transition.
 *
 * Vanilla Gui.onDisconnected() -- called from Minecraft.clearLevel(), which
 * every CRP switch goes through -- unconditionally calls
 * ChatComponent.clearMessages(true), wiping both the visible chat log
 * (allMessages) and the recall history (recentChat) used by the chat input
 * box's up-arrow. On a seamless switch (same client session, just a
 * different backend) that history isn't stale -- there was no real
 * disconnect -- so we skip the wipe while SeamlessTransition.softClear is
 * set (same guard MixinMinecraft uses for the other clearLevel-internal
 * skips, true for every seamless switch regardless of same-modset).
 */
@Mixin(Gui.class)
@OnlyIn(Dist.CLIENT)
public abstract class MixinGuiChatPreserve {

    @Redirect(
        method = "onDisconnected",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;clearMessages(Z)V")
    )
    private void crp$preserveChatOnSeamless(ChatComponent chat, boolean clearRecentChat) {
        if (SeamlessTransition.softClear) {
            return; // keep scrollback + recall history across the seamless switch
        }
        chat.clearMessages(clearRecentChat);
    }
}
