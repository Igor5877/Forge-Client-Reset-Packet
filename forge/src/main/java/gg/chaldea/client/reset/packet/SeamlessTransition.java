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

    // When true, MixinClientPacketListenerRecipeSkip suppresses RecipesUpdatedEvent
    // and TagsUpdatedEvent during the CRP login sequence so REI/thermal don't do a
    // full plugin reload (~2.6s) on every switch.
    //
    // Safety: only set when sameModset=true, which means Ambassador confirmed that
    // old and new backends share the same Forge registry fingerprints (same block/item
    // IDs → same mod jars → same recipes and tags). If fingerprints differ,
    // sameModset stays false → skipRecipeEvents stays false → REI reloads normally.
    //
    // The RecipeManager is still updated from the packet data — only the broadcast
    // event is suppressed. Tags are still applied to registries — only TagsUpdatedEvent
    // is suppressed.
    //
    // Reset to false by MixinClientPacketListenerFix.onChunkReceived (T5 marker).
    public static volatile boolean skipRecipeEvents = false;

    // When true (sameModset seamless switch), suppress the ClientPlayerNetworkEvent
    // LoggingOut/LoggingIn pair — firePlayerLogout (in Minecraft.clearLevel) and
    // firePlayerLogin (in ClientPacketListener.handleLogin). Client mods that rebuild
    // on login/logout then treat the switch as continuous and keep their built state.
    //
    // Primary motivation: JEI's StartEventObserver resets on LoggingOut and only
    // restarts after LoggingIn + TagsUpdatedEvent + RecipesUpdatedEvent. Our cache
    // HITs cancel the recipe/tag handlers so those two events never fire, leaving JEI
    // "not started" → it force-rebuilds the whole ingredient filter (~2.7s, single-
    // threaded on the render thread) the first time any screen opens after a switch.
    // On a same-modset switch the ingredient set is identical, so the cleanest fix is
    // to never reset JEI: suppress the login/logout events and it keeps running.
    //
    // Set alongside skipRecipeEvents in the sameModset block; reset at T6
    // (handleMovePlayer) by MixinClientPacketListenerFix. Gated by sameModset, so
    // cross-modset switches and first connect fire the events normally.
    public static volatile boolean keepClientModState = false;

    // Set to true by MixinUpdateRecipesPacketSkip when it discards the recipe
    // packet bytes without parsing them (same-modset switch + cache present).
    // Read at HEAD of MixinClientPacketListenerRecipeCache.handleUpdateRecipes:
    // when true, the incoming packet's recipe list is empty by design, so we
    // apply the cached recipe maps by fingerprint instead of hashing the (empty)
    // list. Consumed (reset to false) there.
    public static volatile boolean recipePacketSkipped = false;

    // Timing markers — populated by mixins so we can compute deltas in logs.
    // Reset to 0L at the start of each reset cycle (ClientReset.handleClear)
    // so each transition gets fresh deltas instead of only the first one.
    public static volatile long tReset = 0L;
    public static volatile long tLoginSuccess = 0L;
    public static volatile long tJoinGame = 0L;
    public static volatile long tFirstChunk = 0L;
    public static volatile long tTagsApplied = 0L;
    public static volatile long tRecipesApplied = 0L;
    public static volatile long tPlayerPosition = 0L;

    public static void begin() {
        active = true;
    }

    public static void end() {
        active = false;
    }

    /** Reset all per-cycle markers. Called from ClientReset.handleClear. */
    public static void resetMarkers() {
        recipePacketSkipped = false;
        keepClientModState = false; // cleared at cycle start; set true only if sameModset
        tReset = System.nanoTime();
        tLoginSuccess = 0L;
        tJoinGame = 0L;
        tFirstChunk = 0L;
        tTagsApplied = 0L;
        tRecipesApplied = 0L;
        tPlayerPosition = 0L;
    }
}
