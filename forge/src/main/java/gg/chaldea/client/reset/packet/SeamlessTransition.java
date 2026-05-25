package gg.chaldea.client.reset.packet;

public class SeamlessTransition {

    public static volatile boolean active = false;

    // Phase 1 soft clearLevel — when true, MixinMinecraft skips the heavy
    // calls inside Minecraft.clearLevel (gameRenderer.resetData, soundManager.stop,
    // runTick inside updateScreenAndTick, ForgeHooksClient.handleClientLevelClosing).
    // Saves ~400-1000ms of render-thread time.
    public static volatile boolean softClear = false;

    // Timing markers — populated by mixins so we can compute deltas in logs.
    public static volatile long tLoginSuccess = 0L;
    public static volatile long tFirstChunk = 0L;

    public static void begin() {
        active = true;
    }

    public static void end() {
        active = false;
    }
}
