# Progress Log — Seamless Server Transition Optimization

**Last updated:** 2026-05-27 (session 2, ~Kyiv)
**Goal:** Reduce `/myisland` server-switch time from ~15s to ~2s (loliland 1.7.10 reference).
**Branch:** `claude/seamless-server-transition-sq7di`

---

## What ships in this branch

### Phase 1 — Soft clearLevel (DONE, deployed, measured)
Skips heavy work inside `Minecraft.clearLevel(Screen)` during a CRP-mediated
server switch:
- `gameRenderer.resetData()` — skipped (~50ms)
- `updateScreenAndTick(screen)` — replaced with `setScreen + nullify`
  (skip `soundManager.stop()` and `runTick(false)`, ~150-500ms)
- `ForgeHooksClient.handleClientLevelClosing(level)` → `GameData.revertToFrozen()`
  — skipped (~200-500ms)

**Files:** `forge/.../mixin/MixinMinecraft.java`, `SeamlessTransition.softClear`
flag, gated in `ClientReset.handleClear` and `ClientReset.handlePlayPhaseReset`.

**Measured impact:** `clearLevel` 3500ms → **7-17ms**. ~3.5s saved per switch.

### Phase 2 — Chunk buffer reuse (RE-ENABLED with sameModset guard, commit `477b512`)
`MixinLevelRenderer` intercepts `viewArea.releaseAllBuffers()` inside
`allChanged()`. When `SeamlessTransition.keepChunkBuffers=true` it instead
sets each `RenderChunk.compiled = CompiledChunk.UNCOMPILED` — clears stale
meshes without freeing the underlying GL `VertexBuffer` objects.

**Guard:** `keepChunkBuffers` is only set to true when `SeamlessTransition.sameModset=true`,
which itself is only set when the Ambassador Velocity plugin sends a
`fastlogin:same_modset` plugin message (see §Ambassador below). This prevents
stale-texture artefacts when switching between backends with different mod blocks.

- `SeamlessTransition.sameModset` — new flag
- `ClientReset.KEEP_BUFFERS_ENABLED = true` — kill switch
- `MixinClientPacketListenerReset` — handles `fastlogin:same_modset`

**Expected impact:** -200 to -500ms on `allChanged()` (no GL buffer realloc),
plus faster first-visible-frame because old buffer memory is reused in-place.

### MixinChunkRenderDispatcher — more builder packs (NEW, commit `477b512`)
Injects at RETURN of `ChunkRenderDispatcher.<init>` and adds extra
`ChunkBufferBuilderPack` instances to `freeBuffers` if the vanilla heuristic
gave fewer than `max(1, min(8, cores/2))`.

In 1.20.1 the class is `net.minecraft.client.renderer.chunk.ChunkRenderDispatcher`
(NOT `SectionRenderDispatcher` which only exists in 1.20.2+).
`ChunkBufferBuilderPack` is in `net.minecraft.client.renderer` (not in `.chunk`).

The number of packs limits concurrent mesh compilations — more packs = more
parallel builds. Each pack is ~30MB; OOM is caught gracefully.

**Expected impact:** Faster chunk rebuild after allChanged() on multi-core CPUs,
especially when Phase 2 reuse is not available (first switch, different modsets).

### Ambassador — sameModset fingerprint (NEW, commit `477b512`)
`VelocityForgeBackendConnectionPhase`:
- `BACKEND_REGISTRY_CACHE ConcurrentHashMap<serverName, Map<registryName, Adler32>>`:
  stores each backend's registry fingerprint after a successful `isCompatible()` check.
- Before CRP reset: compares cached fingerprints for old and new server.
  If equal → writes `PluginMessagePacket("fastlogin:same_modset")` **before**
  the reset packet (Netty write-order guarantee ensures client receives it first).
- Logs decisions at INFO level: `[sameModset] player=X src→dst registry fingerprints match/absent`.

**First switch:** no cache yet → no `sameModset` → safe fallback (Phase 2 off).
**Subsequent switches (lobby↔island):** cache available → sameModset fires → Phase 2 on.

### CRP detection bridge (DONE)
Two-pronged fix so Ambassador 1.5.x reliably detects our mod as CRP-capable:
- Forge mod registers dummy SimpleChannel `clientresetpacket:main` so it
  appears in client's announced channels.
- velocity-plugin patches `getResetType()` to match either mod ID
  `clientresetpacket` (1.4.x style) OR channel ID `clientresetpacket:*`
  (1.5.x style); always logs detected channel list to proxy logs.

Bug discovered & fixed: `VelocityEventHandler.onPlayerChannelRegisterEvent`
was unconditionally overwriting `setModInfo` after LoginSuccess with the
narrow list of channels from the most recent `minecraft:register` packet,
clobbering the full FML mod list from `ModListReplyPacket`. Next switch
saw no `clientresetpacket` → ResetType=NONE → kick-reset (full disconnect +
reconnect) → visual chunk artifacts on island.
- Fix 1: skip overwrite if `getModInfo()` already present (preserves FML2 list)
- Fix 2: sticky CRP — `COMPLETE.setResetType` refuses downgrade CRP→NONE

### PLAY-phase reset adapter (DONE)
Ambassador 1.5.x (non-api branch) sends reset as
`PluginMessagePacket("fml:handshake", {varint 98})` during PLAY phase,
not as `LoginPluginMessagePacket` during LOGIN. Our SimpleChannel handler
(`HandshakeHandler.biConsumerFor`) only fired in LOGIN context, so the
reset packet was silently dropped — ChunkBuilder restarted but Phase 1
never ran.

`MixinClientPacketListenerReset` intercepts `handleCustomPayload` at HEAD,
detects `fml:handshake` channel + varint 98 payload, and routes to
`ClientReset.handlePlayPhaseReset(connection)` which mirrors `handleReset`
but takes Connection directly (no NetworkEvent.Context). Replies
`C2SAcknowledge` via `handshakeChannel.reply` — matches Ambassador's
`FML2CRPMResetCompleteDecoder` expectation (`LoginPluginResponsePacket(id=98)`).

### Server-mod (PASSIVE, kept off via kill switch)
`FastLoginMod.ENABLED = false` (commit `d39b1bd`). Hash-challenge protocol
remained a DEAD END — Mixin restrictions force the challenge to be sent
from `HandshakeHandler` ctor, before the client's handler is registered,
so the client never sees it. Server mod still installs cleanly; flip the
flag to true once the protocol is reworked.

**Crash fix (session 2, commit `c67e355`):** `CanonicalIdManager.patchRootTag`
called `levelDirectory.path()` which throws `NoSuchMethodError` at runtime.
`LevelDirectory.path()` is a Java record component accessor — ForgeGradle
does not remap it (SRG name `f_230850_()`). Fixed by using
`levelDirectory.dataFile().getParent()` instead (`dataFile()` → `m_230858_()`
is correctly remapped and returns `<worldRoot>/level.dat`; parent = worldRoot).

### velocity-plugin/ fork
Forked from `adde0109/Ambassador` non-api branch (1.5.3-beta) into
`velocity-plugin/` with Velocity submodule pinned to `c3583e18`. Build:
Gradle 8.10.2 + `com.gradleup.shadow:8.3.5` (jengelman shadow is unmaintained
and incompatible with Gradle 8.10). Produces `Ambassador-Velocity-1.5.3-cache-all.jar`.

After `git submodule update --init`, build with
`cd velocity-plugin && ./gradlew shadowJar`.

---

## Measured results (live test 2026-05-25 ~23:29)

3 consecutive Velocity-mediated switches via `/myisland`:

```
[PLAY-reset] Received PLAY-phase S2CReset
Очищення рівня: 7-12 мс        ← Phase 1 active
Sent C2SAcknowledge
[T4] login_success ~5.4s later
[T5] first_chunk since_reset_ms=11951–19841 ms
```

| Metric | Before | After |
|---|---|---|
| `clearLevel` | 3500ms | **7-17 ms** (99.5% reduction) |
| CRP detection | broken on 1.5.x → kick-reset | reliable |
| Visual transition | DisconnectScreen → ConnectScreen | FrozenFrameScreen overlay |
| Modded-block artifacts on island | yes (Phase 2 bug) | none (Phase 2 disabled) |
| Total reset → first_chunk | ~15s (kick-reset) | **~12-15s** (CRP soft) |

**~3s wallclock saved + significantly improved UX.**

---

## Why we stopped here (Phase 3 deferred)

Diagnostic logs (`[crp-timing]` in VelocityForgeBackendConnectionPhase)
broke down the remaining ~5.3s of `reset → login_success`:

```
0 –1.4s : 29 RegistryPackets × 50ms each (Forge backend per-tick send)
1.4–3.8s: ~50 ConfigDataPackets × 50ms each (same per-tick)
3.8–5.3s: ~1.5s silence until LoginSuccess (waitForServer / dimension init)
```

**68% of the delay is Forge's `HandshakeHandler.tickServer()` sending one
packet per server tick (~50ms each).** This happens on the *backend*, before
the bytes ever reach Velocity or the client. Two implications:

1. The Phase 3 plan in earlier sessions (Velocity-side cache + skip relay to
   client) would save at most ~1s — only the Velocity↔client network leg.
   The 3.6s backend cost is paid regardless.
2. A real Phase 3 win requires a **backend-side patch**: a Forge server mod
   that intercepts `HandshakeHandler.tickServer` and flushes all
   `HandshakeMessages` in a single tick instead of one per tick. That's
   5-8 hours of careful Mixin work plus regression risk in FML's handshake
   state machine. Worth doing in a future session, not now.

The Ambassador-internal `forgeHandshake.isCompatible()` cache already exists
(non-api) and works for the non-CRP `consideredComplete=true` branch — but
CRP-reset flips clientPhase back to `NOT_STARTED`, so the cache branch never
fires. Routing CRP through the cache path would require non-trivial state
machine surgery.

---

## Commits on this branch (since `8f956c7`)

```
c67e355 fix(server-mod): replace levelDirectory.path() with dataFile().getParent()
80c71b2 feat: proxy-mediated canonical ID sync (no shared FS required)
ad5cb63 feat(server-mod): canonical registry-ID sync across backends
c720526 fix(server-mod): reflective Snapshot.ids access — server crash on dump
43fd78f diag(server-mod): dump server-side registry fingerprint on ServerStarted
def0c20 chore: re-add release jar (rebuild artifact)
6751e37 fix(velocity-plugin): preserve CRP detection across PlayerChannelRegisterEvent
8340bd5 diag(velocity-plugin): timing logs in handshake handler for Phase 3 analysis
f6f131d feat: bridge clientresetpacket detection for Ambassador 1.5.x
85f1e01 feat(client): support Ambassador 1.5.x PLAY-phase reset
a340fbc fix(client): disable Phase 2 chunk buffer reuse — caused stale GL meshes
e5e14d4 feat(velocity-plugin): Phase 3 step 3a — capture FML handshake bytes (replaced by rebase)
1c7a14d chore(velocity-plugin): fork Ambassador 1.4.3-beta as cache extension base (rebased onto non-api)
f4b5b8a feat(client): Phase 2 chunk buffer reuse on next setLevel (later disabled in a340fbc)
83a54fa fix(client): reset timing markers per cycle, log delta-since-reset
d39b1bd fix(server-mod): add FastLoginMod.ENABLED kill switch, default off
442c48e feat(client): Phase 1 soft clearLevel + transition timing markers
```

---

## Deploy

**Client jar:** `release/ForgeClientResetPacket-0.2.1.jar` →
`~/.minecraftx/instances/1.20.1-forge47.4.20/mods/`

**Proxy jar:** `velocity-plugin/build/libs/Ambassador-Velocity-1.5.3-cache-all.jar` →
`/opt/minecraft/plugins/` (replace existing `Ambassador-Velocity-*.jar`,
restart Velocity).

**Server-mod:** `server-mod/build/libs/forge-fast-login-1.0.0.jar` — passive,
optional. Only needed if/when hash-challenge protocol is fixed.

---

## Architecture context

User runs **Velocity 3.5.0-SNAPSHOT** with Ambassador (non-api branch
internally version-stringed as 1.4.5) + custom `nestworldvelocity` plugin
that registers dynamic island servers via `proxyServer.registerServer +
player.createConnectionRequest.connect()`. Every `/myisland` call IS a
Velocity-mediated switch through Ambassador — confirmed by the
`[server connection] Igor -> island-... has connected` lines in proxy logs.

Modpack: ~150 Forge mods + Sinytra Connector (so a `fabric:registry/sync/direct`
channel shows up alongside Forge mod IDs — this is what triggered the
overwrite bug). MC 1.20.1, Forge 47.4.x server-side, 47.4.20 client-side.

Backends: `lobby` (10.198.126.52:25565), `factions` (10.198.126.61:25565),
`minigames` (127.0.0.1:30068), plus dynamic `island-{uuid}` instances
spawned per-player by nestworldvelocity through an external API.

User reports: he never uses `/server factions` or `/server minigames` in
practice. The primary switch pattern is `lobby ↔ island`.

---

## What to do next session

If continuing optimization:

1. **Backend batch-send patch** (server-mod, the big remaining win):
   Mixin `HandshakeHandler.tickServer` so all `HandshakeMessages` flush
   in one tick instead of `1 per tick`. Expected: -3 to -4s.
   Risk: FML handshake state machine assumes sequential ACK ordering;
   batching may need a separate ACK reconciliation. 5-8h.

2. **First-chunk speedup** (server-mod):
   Send permanent chunks immediately on join (no per-tick throttle) and
   defer BlockEntity NBT to a follow-up packet. -2 to -4s on
   `login_success → first_chunk`. Higher risk (chunk packet protocol).

3. **Phase 2 with safety** (forge mod):
   Re-enable `keepChunkBuffers` only when an Ambassador-published
   "same-modset" plugin message says it's safe. Requires fingerprint
   exchange on the Velocity side too. ~3h work + need cache fingerprint
   in velocity-plugin.

If just maintaining:

- Push the 12 commits to origin (manual, needs user's git creds):
  `git push origin claude/seamless-server-transition-sq7di`
- Open PR on GitHub if desired.

---

## Session 2026-05-29

### Optimizations landed (3 commits)

1. **`f805a5f` perf(server-mod): parallel chunk I/O + send-timing instrumentation**
   - `MixinIOWorkerParallel` — bypasses single-threaded mailbox, redirects
     `IOWorker.loadAsync` to dedicated read pool (min(8, cores/2) threads)
   - `MixinRegionFileStorageParallel` — brief lock on regionCache only,
     disk I/O parallel across RegionFiles, retry on `ClosedChannelException`
   - `MixinServerPacketTiming` — `[SendTiming] Player → Packet +Xms`
     diagnostic per-player
   - Writes untouched (FIFO preserved via mailbox path)
   - Measured: ~3s saved on cold-start chunk load

2. **`ad24dd7` perf(client): RecipeCache full bypass + breakdown timing**
   - `RecipeCache` in-memory keyed by `(modsetFingerprint + recipeListHash)`
   - `MixinClientPacketListenerRecipeCache` cancels entire `handleUpdateRecipes`
     on HIT — skips `replaceRecipes` (~3-4s) + `ClientRecipeBook.setupCollections`
     (~2-3s) + Forge event
   - `RecipeManagerAccessor` exposes private `recipes`/`byName` for swap
   - Auto-invalidates on `GameData.revertToFrozen`
   - Added T6/breakdown line: `login→join / join→recipes / recipes→tags / tags→pos / total`
   - Cold connect: unchanged (cache empty). Warm: 6.0s → 4.9s.

3. **`0f31da0` perf(client): TagCache full bypass — warm switch 4.9s → ~0.9s**
   - `TagCache` marker-only (no payload data) keyed by content hash
   - `MixinClientPacketListenerTagCache` cancels `handleUpdateTags` on HIT,
     skipping `Blocks.rebuildCache()` + per-registry `bindTags()` (~3s)
   - **Most important finding**: cancelling the handler also unblocks the
     render-thread packet queue — subsequent `handlePlayerPosition` and
     `handleLevelChunkWithLight` run immediately instead of waiting
   - Combined stack (all 6 optimizations on): warm switch ~0.9s, best 726ms

### Mod-pack upgrade (mods-nev.zip → 182 new mods)

Big mod update introduced several side-only/version-conflict issues that
the user worked through:

- **`kubejsoffline`**: declared `side=BOTH` but actually client-only
  (loads `Screen` class) → crashes dedicated server.
  Removed from servers.
- **`morejs`**: `ServiceLoader.load(MoreJSPlatform)` returns empty on
  Forge 1.20.1 server classloader → NPE at startup.
  Likely Forge ModuleClassLoader vs `META-INF/services` discovery quirk.
  Removed from servers (and consequently from client to avoid registry
  mismatch).
- **`playersync`**: actually server-only despite `side=BOTH` — tries to
  open MySQL on client. Removed from client.
- **`ftb-xmod-compat-forge-2.1.3`**: built against ftb-quests-2001.4.18
  (`ObjectStartedEvent.getData() → TeamData`) but we run 2001.4.15
  (`getData() → IslandData` due to NestWorld lineage). `NoSuchMethodError`
  in `KubeJSIntegration.onStarted`. Removed from servers.
- **`ftb-quests-forge-2001.4.18`**: removed `TeamManager` class that
  `nestworld-mods-server-1.2.2` (custom) calls. Downgraded to 2001.4.15
  everywhere; on Spawn-dev replaced `ftb-quests-NestWorld-2001.4.14`
  custom fork with vanilla 2001.4.15 for uniform mod set.

### CanonicalID re-sync (Spawn-dev ↔ skyblock-solo)

After mod-pack upgrade backends had different per-server registry ID
mapping (32 registries each, but different `level.dat` `fml.Registries`
ordering → fingerprints `5e094b4d…` vs `a35f48ff…`, ~10 bytes diff).
Proxy held stale 134431-byte canonical from before the upgrade
(only 20 of 32 registries).

Fix:
1. Restart `pro-dev` (Velocity) — canonical is in-memory only, wipe
2. Delete `world/canonical-registry-ids.nbt` on both backends
3. Start Spawn-dev first → `POST /canonical/register` (203223 bytes,
   21 Forge registries) → proxy stores it as new canonical
4. Start solo → `GET /canonical/get` → patches level.dat in-memory
5. Both backends now share fingerprint `677486f685c1…`

After this RegCache HIT works again on same-modset switch.

### Block-state ID mismatch (unresolved)

User reported blocks rendering as different blocks even on first connect
(no switching). Diagnosis chain:

- Client side mod versions identical to server ✓
- RegCache MISS + full inject ran ✓ (cache not stale)
- Vanilla blocks render correctly, only modded blocks scrambled
- Affects newly-placed blocks too, not just chunks-from-old-saves
- Reproducible without Velocity proxy (direct connect to Spawn-dev)

Working theory: **`Block.BLOCK_STATE_REGISTRY` is built once at mod init
from Block registry order**. `GameData.injectSnapshot` remaps Block IDs
post-init, but does NOT rebuild the state registry. If server and client
had different init order (one mod loads scripts that another doesn't,
or sub-block registration order shifts) → state IDs diverge → wrong
textures.

Tested by removing `/root/WORLD/` (Oct 2025 era save) and creating a
fresh void world via `level-type=exdeorum:void`. Did NOT clear the
mismatch — confirming the issue is live registry sync, not stale chunk
data.

Created a clean 20×20 stone platform at (0,64,0)-(19,64,19) in void
world for further investigation. Direct connect on 10.198.126.52:25565
(proxy stopped, `proxy-compatible-forge` removed temporarily).

### Next session

1. **State ID rebuild**: investigate whether Forge 1.20.1's
   `IdMappingEvent` actually rebuilds `Block.BLOCK_STATE_REGISTRY` after
   `injectSnapshot`. If not — that may be the root cause of the block
   mismatch. Could force-rebuild in CRP client mixin.
2. **Restore production state**: put `proxy-compatible-forge` back,
   start `pro-dev`, restore `FASTLOGIN_PROXY_URL` env, switch
   `level-name` away from `world-test`.
3. **morejs/kubejsoffline**: re-add somehow (modset alignment) — they
   were originally `side=BOTH`. Either patch them or upstream-report.
4. **Image refresh**: rebuild `skyblock-template-v8` once mod set is
   stable.

---

## Session 2026-06-01

### Context
Warm switch had regressed to ~16-23s after the user uploaded a new mod build.
Diagnosed + fixed several things; client jar rebuilt + deployed to
`~/.minecraftx/instances/1.20.1-forge47.4.20/mods/ForgeClientResetPacket-0.2.1.jar`
several times. Two commits landed on branch `claude/seamless-server-transition-sq7di`:
`632e3a1` perf skip-parse, `9a8ed27` fix ServerData. The Phase 4 / GUI-fix work
below is NOT yet committed (still in working tree).

### 1. Warm-switch regression root cause — FIXED
Not a code regression. **Spawn-dev's `/opt/minecraft/run.sh` had lost
`export FASTLOGIN_PROXY_URL="http://10.198.126.8:25700"`** (removed during the
2026-05-29 direct-connect debugging, never restored — see run.sh.bak from May 29).
`CanonicalIdManager.saveCanonicalIfAbsent` then took the file-only fallback and
never POSTed to the proxy, so Spawn-dev (file-mode, canonical 203053) and
skyblock-solo (proxy-mode, stale 203223 from proxy) had divergent registry
fingerprints → client RegCache/RecipeCache/TagCache MISS every switch → full
~16s rebuild. Fix: re-added the env to run.sh, full canonical re-sync (restart
`pro-dev` proxy → delete `canonical-registry-ids.nbt` on both backends → start
Spawn-dev first as master → start skyblock). Both now share server fingerprint
`327556349d8c…`. Warm switch back to ~1.5-3s. **TODO: move FASTLOGIN_PROXY_URL into
the systemd unit + `skyblock-template-v8` image so it can't drop again; add a WARN
in the mod when the env is unset; auto-invalidate proxy canonical on modset-hash
change.** Backends: Spawn-dev=10.198.126.52 (world-flat50), skyblock-solo-Igor=
10.198.126.51, proxy pro-dev=10.198.126.8, all `lxc … --project SkyBlock-dev`,
clock is UTC (= Kyiv-3). Island spawn API: `http://api-dev.nestworld.site/api/v1`
(nestworldvelocity / nestworld-1.4-websoket.jar). Island pre-warm from the launcher
is ALREADY implemented (so cold-start ~98s is hidden behind login).

### 2. Recipe packet skip-parse — DONE (commit 632e3a1)
RecipeCache HIT only saved post-decode work; the vanilla
`ClientboundUpdateRecipesPacket(FriendlyByteBuf)` constructor still deserialized
~18000 recipes (~1.3s of join→recipes on the Netty thread). New
`MixinUpdateRecipesPacketSkip` redirects `buf.readList()` in that ctor: on a
sameModset switch with a cached set for the fingerprint, it discards the bytes
unparsed and applies cached recipes by fingerprint (new `RecipeCache.BY_FP` index +
`SeamlessTransition.recipePacketSkipped`). Kill switch `RECIPE_SKIP_PARSE_ENABLED`.
**Not yet re-measured post-deploy (was item #9 on the list).**

### 3. JEI bookmarks not saving across switch — FIXED (commit 9a8ed27)
`Minecraft.getCurrentServer()` is NOT a field — it returns
`getConnection().getServerData()`. Our CRP reset recreated
`ClientHandshakePacketListenerImpl` with a **null ServerData** in both
`handleReset` and `handlePlayPhaseReset`, so after the first `/myisland`
`getCurrentServer()` was null all session. JEI 15.20 keys its per-world bookmark
file (`config/jei/world/server/<sanitize(name)_hex(ip.hashCode())>/bookmarks.ini`)
on `getCurrentServer()` (`ServerConfigPathUtil.getWorldPath`); null → `Optional.empty`
→ `saveBookmarks` silently no-ops. Fix: capture `mc.getCurrentServer()` before
`clearLevel()` and pass it into the new listener. Confirmed working: bookmarks now
save+load across switches (shared per-proxy, which is correct for seamless).
NOTE: pack switched REI→JEI on 2026-05-29; `config/roughlyenoughitems/` is dead
leftover; mod `jei_copy_recipe_json` throws harmless ClassNotFound for REI/EMI.

### 4. GUI closes on chunk load (kicked out of inventory/chat) — FIXED (uncommitted)
`MixinClientPacketListenerFix.checkAndCloseLoadingScreen` was gated on
`SeamlessTransition.active` and did `setScreen(null)` on ANY open screen on every
chunk/position packet. Root cause: `checkAndCloseLoadingScreen` dismisses the
FrozenFrameScreen via `setScreen(null)` → routes through `FrozenFrameScreen.removed()`,
which did NOT call `SeamlessTransition.end()` (only `tick()`/`onClose()` did) → the
`active` flag leaked true forever → every subsequent chunk closed the player's GUI.
Two fixes: (a) `FrozenFrameScreen.removed()` now calls `end()`; (b)
`checkAndCloseLoadingScreen` only dismisses `ReceivingLevelScreen`/`FrozenFrameScreen`,
never a real GUI. **User confirmed FIXED.**

### 5. JEI ~2.7-5s render-thread freeze on first screen-open after each switch — STILL OPEN
Confirmed cause: JEI `StartEventObserver` (jei-1.20.1-forge-15.20.0.112) requires
three Forge events to start: `ClientPlayerNetworkEvent.LoggingIn` + `TagsUpdatedEvent`
+ `RecipesUpdatedEvent`. Our cache HITs **cancel** `handleUpdateRecipes`/`handleUpdateTags`,
so Forge's `RecipesUpdatedEvent`/`TagsUpdatedEvent` (injected at handler RETURN) never
fire → JEI stays "not started" → on the first `ScreenEvent.Init.Pre` (inventory) it
force-starts on the render thread: "A Screen is opening but JEI hasn't started yet"
+ Building ingredient filter ~1.3s + Building runtime ~1.4s + 17819 ingredients =
single-threaded freeze, **once per switch**. (First-ever start on cold join ~5s is
normal JEI, unavoidable.)

**Attempted fix (Phase 4 / Option B, uncommitted) — did NOT work.** Added
`SeamlessTransition.keepClientModState` + kill switch `KEEP_CLIENT_MOD_STATE_ENABLED`,
set in both sameModset blocks, reset at T6 (handleMovePlayer) and in `resetMarkers()`.
`@Redirect` on `ForgeHooksClient.firePlayerLogout` (MixinMinecraft.clearLevel) and
`firePlayerLogin` (MixinClientPacketListenerFix.handleLogin), `require=0`, gated on
the flag — idea: don't reset JEI on a sameModset switch so it keeps its built state.
**User reports the freeze still happens.**

**Next session — diagnose why Phase 4 didn't help.** Check client `latest.log`:
1. Does `[Phase4] keepClientModState=true` appear on switches? If NOT → flag/gating
   wrong (sameModset false at that point?) or the redirects silently no-op'd
   (`require=0` → AP couldn't map; Forge classes aren't remapped so it SHOULD match
   at runtime like `handleClientLevelClosing` does — but verify with a log line inside
   the redirect, e.g. log when suppressing).
2. Does "A Screen is opening but JEI hasn't started" STILL appear after a switch? If
   yes → JEI is still being reset. Then LoggingOut/In suppression isn't enough — JEI
   may reset via another path (world/level load, or its observer restarts on the
   recipe/tag packets themselves). Re-examine `StartEventObserver.transitionState`
   (StartEventObserver.java:130, lambda$register$3:76) — it may reset `observedEvents`
   on ANY observed event arriving out of order, or on level load.
3. Likely better fix = **Option A**: after a cache HIT, explicitly re-fire
   `RecipesUpdatedEvent` + `TagsUpdatedEvent` (and ensure LoggingIn fires) so JEI's
   required-events set completes and it starts DURING the frozen transition instead
   of on inventory open. Costs ~2.7s on the switch (hidden behind frozen screen) but
   removes the interactive freeze. JEI's build is render-thread-bound (touches
   registry/ItemStack/GL) → cannot be safely multi-threaded by us.
4. Also verify Phase 4 didn't break other login/logout-keyed mods (Xaero minimap
   waypoints, voicechat) — if it did and we keep Phase 4, flip
   `KEEP_CLIENT_MOD_STATE_ENABLED=false`.

### Uncommitted working-tree changes at session end
Phase 4 + GUI fix across: `ClientReset.java`, `SeamlessTransition.java`,
`MixinMinecraft.java`, `MixinClientPacketListenerFix.java`, `FrozenFrameScreen.java`,
plus rebuilt `release/ForgeClientResetPacket-0.2.1.jar` (also deployed to client).
GUI fix is good to keep; Phase 4 is unproven (freeze persists) — decide next session
whether to keep, revert, or replace with Option A before committing.
Also still uncommitted from before: `PROGRESS.md`, `.claude/settings.local.json`.
