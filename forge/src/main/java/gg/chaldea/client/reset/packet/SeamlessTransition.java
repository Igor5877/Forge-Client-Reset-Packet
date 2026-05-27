package gg.chaldea.client.reset.packet;

public class SeamlessTransition {

    public static volatile boolean active = false;

    // Phase 1 soft clearLevel — when true, MixinMinecraft skips the heavy
    // calls inside Minecraft.clearLevel (gameRenderer.resetData, soundManager.stop,
    // runTick inside updateScreenAndTick, ForgeHooksClient.handleClientLevelClosing).
    // Saves ~400-1000ms of render-thread time.
    public static volatile boolean softClear = false;

    // Phase 2 — when true, the next LevelRenderer.allChanged() call (triggered
    // by setLevel(newLevel) for the destination server) will skip
    // viewArea.releaseAllBuffers() so the existing chunk VBO meshes survive.
    // Instead, each RenderSection.reset() is called so compiled=EMPTY (no stale
    // textures), but the GPU buffers stay allocated and are reused in-place.
    // Reset to false after one allChanged() call (mixin self-resets).
    // Only activated when sameModset=true to guard against render artefacts.
    public static volatile boolean keepChunkBuffers = false;

    // Set to true by MixinClientPacketListenerReset when Ambassador Velocity
    // plugin sends PluginMessage("fastlogin:same_modset") before the CRP reset.
    // Signals that the current and target backends share the same mod registry
    // fingerprint — safe to reuse GPU chunk buffers (Phase 2 keepChunkBuffers).
    // Consumed and reset to false inside ClientReset.handleClear / handlePlayPhaseReset
    // immediately after keepChunkBuffers is set from it.
    public static volatile boolean sameModset = false;

    // Timing markers — populated by mixins so we can compute deltas in logs.
    // Reset to 0L at the start of each reset cycle (ClientReset.handleClear)
    // so each transition gets fresh deltas instead of only the first one.
    public static volatile long tReset = 0L;
    public static volatile long tLoginSuccess = 0L;
    public static volatile long tFirstChunk = 0L;

    public static void begin() {
        active = true;
    }

    public static void end() {
        active = false;
    }

    /** Reset all per-cycle markers. Called from ClientReset.handleClear. */
    public static void resetMarkers() {
        tReset = System.nanoTime();
        tLoginSuccess = 0L;
        tFirstChunk = 0L;
    }
}
