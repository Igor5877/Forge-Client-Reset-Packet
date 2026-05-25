# Progress Log — Seamless Server Transition Optimization

**Date:** 2026-05-25 (session ends due to token limits)
**Goal:** Reduce server-to-server switch time from ~15s to ~2s (matching loliland 1.7.10 reference).
**Branch:** `claude/seamless-server-transition-sq7di`

---

## Architecture Context (CRITICAL)

User runs a **Velocity proxy + Ambassador-Velocity-1.4.5 plugin** with multiple Forge 1.20.1 backend servers:
- `lobby` = 10.198.126.52:25565 (Spawn-dev)
- `factions` = 10.198.126.61:25565
- `minigames` = 127.0.0.1:30068
- Possibly more (skyblock-solo-Igor)

Player connects to Velocity (port 25565) → routed to backend. Each server switch = NEW FML handshake with destination backend.

The `S2CReset` packet (handled by this mod) is sent **by Ambassador via Velocity**, not by our server-mod. Our `forge/` module is the CLIENT-side receiver.

This repo started as fork of `8MiYile/Forge-Client-Reset-Packet` (basic Ambassador client). User added registry caching attempts on top.

**Modpack:** ~150 mods, mix of Forge + Fabric (via Sinytra Connector). MC 1.20.1, Forge 47.4.x server-side.

---

## Current Timing (measured 2026-05-25 ~01:27 local)

```
T+0.0s — S2CReset received from Velocity/Ambassador
T+0.0s — Початок очищення
T+3.6s — Очищення рівня (clearLevel finishes)
T+3.6s — Switch to LOGIN protocol, new ClientHandshakePacketListenerImpl
T+7.6s — login_success
T+15.7s — first_chunk (8s after login_success)
TOTAL: ~15s reset cycle
```

**Bottleneck breakdown (from Forge source analysis):**
- `clearLevel(screen)`: 3.5s — dominated by `levelRenderer.setLevel(null)` doing `viewArea.releaseAllBuffers()` (thousands of `glDeleteBuffers` on render thread, single-threaded by GL design)
- FML handshake + registry sync: ~4s
- World rebuild + first chunk: ~8s

**User's btop observation:** "1 thread at 100%" — that's the render thread doing GL dispose synchronously.

---

## What Was Investigated & Learned

### Hash-Challenge Protocol (DEAD END for now)
Attempted to skip FML registry sync via hash-based cache:
- `server-mod/.../FastLoginMod.java` — registers C2SHashResponse/S2CHashChallenge SimpleChannel handlers on FML handshake channel (IDs 96/97)
- `server-mod/.../RegistryHashUtil.java` — SHA-256 fingerprint of all Forge registry keys, computed on `ServerStartedEvent`
- `server-mod/.../mixin/MixinHandshakeHandler.java` — sends challenge from `HandshakeHandler` constructor (via `@ModifyArg` on `gatherLoginPayloads`)
- `server-mod/.../mixin/MixinGameData.java` — spin-wait in `NetworkRegistry.gatherLoginPayloads`, returns `Collections.emptyList()` if `shouldSkip(ch)`

**Why broken:**
1. **Timing:** Challenge sent in ctor (before client's HandshakeHandler is ready) → client receives via vanilla `ClientboundCustomQueryPacket` but FML doesn't dispatch to our `handleHashChallenge` handler → no `[T0] challenge_received` in client logs.
2. **Server logs every login:** `[T0] challenge_sent (ctor)` ✓, then 3s timeout, then "Query ID 0 was received but no query has been associated" + "Received empty payload on channel fml:handshake" (client's empty Forge ACK, not our reply).
3. **Architectural issue:** Even if response worked, returning `Collections.emptyList()` skips S2CModList + S2CModData (essential for FML), would break client's handshake state machine.

**Mixin restrictions discovered:**
- `@Inject` on `<init>` constructor is **forbidden** in Mixin 0.8.5 (any `@At` shift).
- `@Redirect` needs to call original — but `NetworkRegistry.gatherLoginPayloads` is package-private, unreachable from outside.
- Workaround used: `@ModifyArg` (allowed in ctor, runs as side-effect, returns arg unchanged).

### Forge HandshakeHandler structure (from `forge/build/tmp/.cache/expanded/`)
Critical Forge code paths reviewed:
- `HandshakeHandler.java:118-131` — ctor calls `gatherLoginPayloads` ONCE; `messageList` is fixed at construction time.
- `HandshakeHandler.java:341-384` — `tickServer()` iterates `messageList` one-per-tick (~50ms per packet). With ~30 registry packets that's the handshake cost.
- `NetworkRegistry.gatherLoginPayloads` is package-private static.
- `IndexedMessageCodec.java:137-144` — "Received empty payload" fires for empty Forge ACKs (NORMAL noise, not a bug).

### Ambassador Source Analysis (GOLD FIND)
`adde0109/Ambassador` Velocity plugin (the upstream we depend on):

**Files of interest:**
- `forge/ForgeConnection.java` — caches `recivedClientModlist`, `recivedClientACK`, `transmittedHandshake` (CLIENT-side caches for replay to backend)
- `forge/ForgeServerConnection.java` — has `CachedServerHandshake` field
- `forge/ForgeHandshakeUtils.java` — defines:
  ```java
  public static class CachedServerHandshake {
      private final long fingerprint;
      public byte[] modListPacket;
      public List<byte[]> otherPackets;  // S2CRegistry + S2CModData
  }
  ```
- `downloadHandshake` has cached variant that **reuses cache if fingerprint matches**.

**KEY INSIGHT:** Ambassador already has full infrastructure for caching backend's FML handshake (modList + registry packets) with fingerprint validation. It only uses this internally for Ambassador↔backend optimization. **It does NOT replay to the CLIENT.**

The optimization path is: **extend Ambassador to replay cached packets to client + add custom plugin message for cache exchange**.

---

## Plan: 3 Phases to Reach ~2s

### Phase 1: Soft clearLevel (NEXT — IN PROGRESS)
**Target:** 3.5s → 2.5-3s clearLevel
**Time:** 2-3 hours
**Risk:** Low

Replace `mc.clearLevel(transitionScreen)` in `ClientReset.handleClear` (forge/.../ClientReset.java:229) with custom code that mirrors `Minecraft.clearLevel(Screen)` (Minecraft.java:2092) but skips:
- `gameRenderer.resetData()` (~50ms, GL state reset)
- `updateScreenAndTick(screen)` → both `soundManager.stop()` (~50-200ms) AND `runTick(false)` (~100-300ms)
- `handleClientLevelClosing()` → `GameData.revertToFrozen()` (~200-500ms, safe to skip since same modset across backends)

KEEP (necessary):
- `clientpacketlistener.close()` — needed to end PLAY listener
- `firePlayerLogout` — mods may listen
- `updateLevelInEngines(null)` — chunk dispose IS the 2-3s killer but unsafe to skip in Phase 1
- `level = null`, `player = null`

Add timing logs around each step to confirm savings.

Modify `forge/src/main/java/gg/chaldea/client/reset/packet/ClientReset.java:217-249` (`handleClear`).
May need Mixin shadow access to private `Minecraft` fields (`narrator`, `playerSocialManager`, `metricsRecorder`, etc.). Could simplify by calling vanilla `clearLevel` minus the deferred work via custom mixin.

### Phase 2: Chunk Reuse (no GL dispose)
**Target:** 2.5s → 0.5s clearLevel
**Time:** 5-8 hours
**Risk:** Medium (visual artifacts)

Skip `updateLevelInEngines(null)` so:
- `levelRenderer` keeps `viewArea` alive
- `chunkRenderDispatcher` keeps mesh data
- `particleEngine`, `blockEntityRenderDispatcher` retain references

When new `ClientboundLoginPacket` arrives:
- Existing `Minecraft.setLevel(newLevel)` calls `updateLevelInEngines(newLevel)` → `LevelRenderer.allChanged()` → `releaseAllBuffers()` again ← STILL the dispose!
- Need to mixin `LevelRenderer.allChanged()` to SKIP `releaseAllBuffers()` if the new level has same dimension
- Or override `Minecraft.setLevel` to detect "reset reuse" case and just update internal references

Add config flag `reuseChunksOnReset` so user can disable if visual bugs.

### Phase 3: Ambassador Extension for Registry Cache
**Target:** 4s → 1s FML handshake on cached clients
**Time:** 9-14 hours
**Risk:** Low-medium (cross-component)

**Velocity plugin (new code, possibly fork or PR Ambassador):**
1. Cache backend's S2CRegistry packet bytes per RegisteredServer (use Ambassador's `CachedServerHandshake` mechanism — already there).
2. Add custom plugin message `ambassador:cache_query` exchanged with client during login:
   - Velocity → Client: `cache_query(fingerprint_hex)`
   - Client → Velocity: `cache_response(has_cache: bool)`
3. If `has_cache=true`:
   - Send S2CModList + S2CModData to client (lightweight, required for FML state).
   - DON'T forward S2CRegistry packets from backend stream.
   - Send final ACK to complete handshake.
4. If `has_cache=false`:
   - Replay cached S2CRegistry from Velocity cache (faster than backend roundtrip).

**Client Forge mod changes:**
1. Existing `RegistryCache.java` already saves on LoginSuccess. Need to:
   - Index by fingerprint (not just hash).
   - Add restore logic that runs BEFORE FML handshake completes if cache_response=true.
   - Mixin into FML handshake to NOT wait for S2CRegistry packets if cache_used=true.
2. Persistent cache directory: `.minecraft/fastlogin-cache/{fingerprint}.bin`.
3. On client startup: pre-load list of available fingerprints into memory for sync cache_query response.

**Custom plugin message channel:**
- Channel name: `ambassador:cache` or similar
- Protocol: simple varint hex + boolean

**Files to touch:**
- New: Velocity plugin module (separate Gradle project, Kotlin or Java + Velocity API 3.x)
- Modify: `forge/src/main/java/gg/chaldea/client/reset/packet/RegistryCache.java` (add fingerprint indexing)
- Modify: `forge/.../ClientReset.java` (register cache_query channel, respond to query)
- New mixin: skip S2CRegistry wait if cache_used flag set

---

## Code State (deployed jars as of 01:21)

```
server-mod/build/libs/forge-fast-login-1.0.0.jar  (16 KB, 01:21)
forge/build/libs/forge-0.2.1.jar                  (29.9 KB, 00:34)
release/ForgeClientResetPacket-0.2.1.jar          (29.9 KB, 00:34)
```

**Current behavior:**
- ✅ S2CReset reception + soft transition with FrozenFrameScreen (works)
- ✅ Server computes registry hash and sends challenge from `HandshakeHandler` ctor
- ❌ Client does NOT receive/dispatch the challenge (timing issue with Forge handshake state)
- ❌ Cache restore never tested end-to-end
- ✅ Cache save on LoginSuccess (writes file but never tested restore)

**Important files modified by user (vs upstream 8MiYile/1.20.1):**
- `forge/src/main/java/gg/chaldea/client/reset/packet/ClientReset.java` (timing logs T0-T3, hash channel registration)
- `forge/src/main/java/gg/chaldea/client/reset/packet/RegistryCache.java` (NEW)
- `forge/src/main/java/gg/chaldea/client/reset/packet/FrozenFrameScreen.java` (NEW)
- `forge/src/main/java/gg/chaldea/client/reset/packet/SeamlessTransition.java` (NEW, has timing markers)
- `forge/src/main/java/gg/chaldea/client/reset/packet/network/{S2CHashChallenge,C2SHashResponse}.java` (NEW)
- `forge/src/main/java/gg/chaldea/client/reset/packet/mixin/{MixinClientLoginPacketListener,MixinClientPacketListenerFix}.java` (NEW)
- Entire `server-mod/` module (NEW)

---

## Build & Deploy

**Build server-mod:**
```bash
cd /home/igor/Документи/GitHub/Forge-Client-Reset-Packet/server-mod
./gradlew build --no-daemon
# → build/libs/forge-fast-login-1.0.0.jar
```

**Build forge client mod:**
```bash
cd /home/igor/Документи/GitHub/Forge-Client-Reset-Packet
./gradlew build --no-daemon
# → forge/build/libs/forge-0.2.1.jar + release/ForgeClientResetPacket-0.2.1.jar
```

**Deploy:**
- Server jar → user uploads `forge-fast-login-1.0.0.jar` to backend server's `mods/`
- Client jar → user copies `ForgeClientResetPacket-0.2.1.jar` to `~/.minecraftx/instances/1.20.1-forge47.4.20/mods/`

**Test logs:**
- Server: `/opt/minecraft/logs/latest.log` on backend (Spawn-dev or skyblock-solo-Igor)
- Client: `/home/igor/.minecraftx/instances/1.20.1-forge47.4.20/logs/{latest,debug}.log`

Grep `[FastLogin]` and `RESETPACKET` for our timing markers.

---

## Reference Sources Reviewed

- Forge 47.2.0 sources in `forge/build/tmp/.cache/expanded/zip_8301284005f3a3b9dadc9d0ed77a6813/`:
  - `net/minecraft/client/Minecraft.java` (lines 2088-2157 for clearLevel)
  - `net/minecraft/client/renderer/LevelRenderer.java` (lines 658-725 for setLevel + allChanged)
  - `net/minecraftforge/network/HandshakeHandler.java` (lines 118-384 for ctor + tickServer)
  - `net/minecraftforge/network/NetworkRegistry.java` (line 245+ for gatherLoginPayloads)
  - `net/minecraftforge/network/simple/IndexedMessageCodec.java` (lines 105-155 for codec)
  - `net/minecraftforge/network/HandshakeMessages.java` (line 33+ for LoginIndexedMessage)
  - `net/minecraftforge/client/ForgeHooksClient.java` (line 925+ for handleClientLevelClosing)

- Ambassador GitHub: `github.com/adde0109/Ambassador/tree/main/src/main/java/org/adde0109/ambassador/forge/`
  - `ForgeHandshakeUtils.java` — has `CachedServerHandshake` (KEY!)
  - `ForgeConnection.java` — caches client-side data
  - `ForgeServerConnection.java` — wraps RegisteredServer with cached handshake
  - `ForgeServerSwitchHandler.java` — handles server switch event
  - `ForgeHandshakeHandler.java` — main FML handshake interception

- Velocity config: `/opt/minecraft/velocity.toml` on proxy (pro-dev host)

---

## Open Questions / Risks

1. **Phase 1 — does Forge depend on the `runTick(false)` inside `updateScreenAndTick`?** Skipping may leave some manager in inconsistent state. Need to test carefully — first iteration may crash.

2. **Phase 2 — do chunks survive `mc.level = null`?** `LevelRenderer` holds reference but level is detached. If renderer tries to render with no level → NPE. Need to also skip render call until new level set.

3. **Phase 3 — does Ambassador have an extension API or do we have to fork?** Not visible from README. Likely fork the repo.

4. **Phase 3 — backend may have ConfigSync (`S2CConfigData`) packets which depend on per-connection state.** Caching ALL handshake packets vs only registry-related — need to test.

5. **Cross-version compat:** Forge 47.4.9 server runs the user's setup; we built against 47.2.0. So far works (minor version compat). Future Forge updates may change `HandshakeHandler` internals.

---

## Resume Instructions for Next Session

1. Read this file first.
2. Check `git status` and `git log` to see if user committed anything since.
3. Verify current jar timestamps in `release/` and both `build/libs/`.
4. Begin Phase 1 work in `forge/src/main/java/gg/chaldea/client/reset/packet/ClientReset.java`.
5. Build + ask user to deploy + test, gather new timing logs, then iterate.

**User communication style:**
- Ukrainian conversational, technical content.
- Wants honest assessments and realistic time estimates.
- Has tested several iterations already, somewhat tired of long debug sessions.
- Original token-limit message preserved in conversation history; this file was written near limit.
