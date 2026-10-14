package gg.chaldea.client.reset.packet;

import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-only hooks for RegistryPrewarm: replay once when the title screen first appears
 * (resource reload is finished by then), and drop the replayed state if the player leaves
 * for singleplayer or Realms instead of connecting to the server it was recorded against.
 */
@Mod.EventBusSubscriber(modid = "clientresetpacket", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class PrewarmEvents {

    private PrewarmEvents() {}

    @SubscribeEvent
    public static void onTitleInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof TitleScreen) {
            RegistryPrewarm.replayAtMenu();
        }
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!RegistryPrewarm.isActive()) return;
        Screen next = event.getNewScreen();
        if (next instanceof SelectWorldScreen || next instanceof CreateWorldScreen
                || (next != null && next.getClass().getName().startsWith("com.mojang.realmsclient"))) {
            RegistryPrewarm.revert("player left the multiplayer path: " + next.getClass().getSimpleName());
        }
    }
}
