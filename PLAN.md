# BrutalTank — Implementation Plan
## Multiplayer 2D Artillery Game (Svelte + Java/Undertow + WebSockets)

## Context

This is a from-scratch build in an empty repository. The goal is a modernized multiplayer remake of *Scorched Earth* / *Scorched Tanks* / *Pocket Tanks* — a turn-based, side-view 2D artillery game — rather than the real-time top-down "Combat"-style tank duel originally discussed. That pivot was made deliberately: the turn-based genre is a much better fit for a WebSocket multiplayer architecture (no real-time physics tick/sync needed, just per-turn server-authoritative resolution), while still delivering the "cool features" identified from genre research (wall/terrain interaction, weapon variety, power-ups/shields, destructible terrain).

Confirmed direction from planning discussion:
- **Frontend**: Svelte + HTML5 Canvas 2D (no WebGL library).
- **Backend**: Java + Undertow, WebSocket-based, server-authoritative.
- **Multiplayer-first**: up to 6-8 players, free-for-all, turn-based.
- **Weapons**: small curated arsenal (~10 weapons + 3 shields), not a huge roster.
- **Economy**: shop system between rounds from the start.
- **Rounds**: multiple rounds per match, terrain regenerates fresh each round, cumulative cash decides the match winner.
- **Visual style**: painterly/pixel-art remaster — detailed pixel terrain/tanks/explosions, not flat vector art.

---

## 1. Project Structure

Monorepo, two independently-buildable modules connected by a shared hand-maintained protocol doc.

```
BrutalTank/
├── client/                          # Svelte + Vite frontend
│   ├── src/
│   │   ├── lib/
│   │   │   ├── net/                 # WebSocket client, message codec, reconnect logic
│   │   │   ├── stores/              # Svelte stores: matchState, connectionState, uiState
│   │   │   ├── game/                # Canvas renderer, camera, animation/tween system
│   │   │   │   ├── render/          # terrain, tank, projectile, explosion, background renderers
│   │   │   │   └── input/           # angle/power drag & keyboard handlers
│   │   │   ├── components/
│   │   │   │   ├── lobby/           # MatchBrowser, CreateMatchForm, LobbyRoom, PlayerReadyList
│   │   │   │   ├── hud/             # AngleDial, PowerBar, WindIndicator, WeaponSelect, TurnBanner
│   │   │   │   ├── shop/            # ShopPanel, WeaponCard, ShieldCard, CashDisplay
│   │   │   │   └── common/          # buttons, modals, toasts
│   │   │   └── protocol/            # typed message interfaces (mirrors server DTOs)
│   │   ├── App.svelte                # top-level screens: Menu -> Lobby -> Match -> PostMatch
│   │   ├── assets/                  # sprite sheets, atlases, audio
│   │   └── main.ts
│   ├── public/
│   ├── index.html
│   ├── vite.config.ts
│   ├── package.json
│   └── tsconfig.json
│
├── server/                          # Java + Undertow backend
│   ├── src/main/java/com/brutaltank/
│   │   ├── BrutalTankServer.java    # entrypoint: Undertow bootstrap, WS routing
│   │   ├── net/                     # WebSocketConnectionCallback, session registry, message envelope codec
│   │   ├── lobby/                   # LobbyManager, MatchRegistry, MatchmakingHandlers
│   │   ├── match/                   # MatchActor (game loop/state machine), MatchState, TurnManager
│   │   ├── domain/
│   │   │   ├── terrain/             # Terrain, TerrainGenerator, CraterOp
│   │   │   ├── player/              # Player, Tank, Loadout, Shield
│   │   │   ├── weapon/              # WeaponDef, WeaponRegistry, ProjectileSim, DamageCalculator
│   │   │   └── economy/             # Shop, PriceTable, CashLedger
│   │   ├── protocol/                # DTOs / records for every message type, JSON (de)serialization
│   │   └── util/                    # deterministic RNG wrapper, math helpers
│   ├── src/test/java/com/brutaltank/
│   │   ├── domain/terrain/          # crater math, heightmap generation tests
│   │   ├── domain/weapon/           # trajectory + damage unit tests
│   │   └── match/                   # turn state machine tests
│   ├── build.gradle.kts
│   └── settings.gradle.kts
│
├── shared/
│   └── protocol.md                  # canonical message schema doc (hand-authored, both sides implement against it)
│
├── docs/
│   └── architecture.md              # living copy of this plan
│
└── README.md                        # how to run client+server together locally
```

**Build tooling**: Gradle (Kotlin DSL) for the server, Vite + Svelte + TypeScript for the client.

**Local dev workflow**: two processes, no unified build at v1.
- `server`: `./gradlew run` — Undertow listens on `:8080`, serving `/ws`.
- `client`: `npm run dev` (Vite on `:5173`), hardcoded dev WS URL `ws://localhost:8080/ws`.
- Multiplayer playtesting: multiple browser tabs/windows against the same local server.
- Production packaging (post-v1): a Gradle fat-jar task serves the built `client/dist` via Undertow's `ResourceHandler` so one process serves both.

**Protocol contract**: `shared/protocol.md` is the single hand-maintained source of truth for every message shape. Both server DTOs and client TS interfaces are written to match it by hand — no codegen at v1.

---

## 2. Backend Architecture (Java/Undertow)

### 2.1 Connection & lifecycle handling

- `BrutalTankServer` boots `Undertow` with a single WebSocket path `/ws`. A `WebSocketConnectionCallback` registers each new channel with a `SessionRegistry`.
- Each socket is wrapped in a `PlayerSession`: `sessionId (UUID)`, `channel`, `playerId` (stable across reconnect), `currentMatchId` (nullable), `lastSeenAt`.
- **Lobby phase**: `LobbyManager` coordinates `CreateMatch` (creates `WAITING` `MatchState`, registers in `MatchRegistry`, returns matchId + join code), `JoinMatch` (capacity check, max 8, broadcasts `LobbyUpdate`), `SetReady` (all-ready triggers `StartMatch`), `LeaveMatch`/disconnect (remove slot, broadcast update).
- **Reconnect**: on disconnect, `PlayerSession` marked `disconnected`; `MatchActor` retains state for a grace period (e.g. 120s, tunable). If it's the disconnected player's turn, `TurnManager` auto-skips after a timeout (e.g. 30s). Client stores a secret `playerToken` (sessionStorage) issued at join, sends `Rejoin{matchId, playerToken}` to re-attach and receives a full `MatchStateSync`. Past the grace period, the player is simply removed (full spectator mode is a v2 refinement).

### 2.2 Concurrency model

**One match = one single-threaded actor.** This is the key correctness decision for N players mutating shared match state concurrently from separate Undertow I/O threads.

- `MatchActor` owns a single-threaded worker (recommend Java 21+ virtual threads — match threads are mostly idle waiting on their queue) with a bounded `LinkedBlockingQueue<MatchCommand>`.
- Inbound WS messages that mutate state (`Fire`, `ShopPurchase`, `SetReady`, `Rejoin`, disconnect notices) are parsed on the Undertow I/O thread into `MatchCommand` objects and enqueued — **no game-state mutation happens on the I/O thread**.
- The actor's worker thread drains the queue serially, mutates `MatchState`, computes results, produces `MatchEvent`s, and broadcasts them. This gives sequential consistency per match with zero manual locking.
- `MatchRegistry` (matchId → MatchActor) is a `ConcurrentHashMap`, the only cross-thread-touched structure outside a match's own queue.
- Server is authoritative: trajectory/damage/terrain are computed **only** inside the `MatchActor` thread from a validated `Fire` command; client angle/power are inputs, never trusted outcomes.

### 2.3 Core domain model

- **`Match`/`MatchState`**: `matchId`, `status` (WAITING/IN_PROGRESS/ROUND_TRANSITION/SHOP/COMPLETE), `players`, `currentRound`, `roundNumber`, `maxRounds` (default 4), `config`.
- **`Round`/`RoundState`**: `terrain`, `turnOrder` (rotates starting player each round), `currentTurnIndex`, `wind {strength, directionSign}` (rerolled each turn, e.g. -20..+20), `activePlayers`, `shotHistory`.
- **`Player`/`Tank`**: `playerId`, `displayName`, `color`, `cash`, `loadout` (weaponId → quantity), `activeShieldId`, `tank {x, y (from terrain height), health 0-100, alive}`.
- **`Terrain`**: `int[] heights`, one entry per world column (e.g. width 1600 units, independent of screen resolution, client scales to canvas). `TerrainGenerator.applyCrater(x, radius, depthFn)` lowers a falloff-shaped range of columns, clamped to a world floor. Server-side mutable truth; never fully re-sent mid-round.
- **`WeaponDef`**: `weaponId`, `displayName`, `projectileType`, `blastRadius`, `damageAtCenter`, `damageFalloffCurve`, `price`, `defaultQuantity`, behavior-specific params.
- **Shop/Economy**: `Player.cash`, static `PriceTable`, purchases validated server-side against cash, only allowed while `status == SHOP`.
- **Turn state machine** (`TurnManager`): `TURN_START` (roll wind, notify active player) → `AWAITING_FIRE` (only active player's `Fire` accepted) → `RESOLVING` (compute trajectory/damage/terrain delta, broadcast `ShotResolved`) → win/elimination check → `TURN_END` (advance to next alive player) → loop, or `ROUND_END` → `SHOP` (timed, e.g. 30s) → next round's `TURN_START` with fresh terrain, or `MATCH_COMPLETE`.

### 2.4 Message protocol

**Envelope** (JSON text frames):
```json
{ "type": "Fire", "v": 1, "requestId": "optional-client-correlation-id", "payload": { ... } }
```

**Client → Server**: `CreateMatch{displayName, matchConfig?}`, `JoinMatch{matchId, displayName}`, `SetReady{ready}`, `Rejoin{matchId, playerToken}`, `Fire{weaponId, angleDeg, power}` (active-turn only), `ShopPurchase{itemId, itemType, quantity}` (shop phase only), `LeaveMatch{}`, `Ping{}`.

**Server → Client**: `MatchCreated{matchId, joinCode, playerToken, playerId}`, `LobbyUpdate{players, hostId}`, `MatchStarted{matchConfig, players}`, `MatchStateSync{full snapshot}` (only full-state message — join/reconnect/round-start), `TurnStarted{playerId, wind, turnTimeoutSec}`, `FireRejected{requestId, reason}`, `ShotResolved{shooterId, weaponId, trajectory[], impact, terrainDelta{startX, endX, heights[]}, damageEvents[], cashEarned[]}`, `RoundEnded{winnerPlayerId?, standings[]}`, `ShopOpened{timeoutSec, priceList}`, `ShopUpdate{playerId, cash, loadout}`, `MatchEnded{finalStandings}`, `PlayerDisconnected`/`PlayerReconnected{playerId}`, `ErrorMsg{code, message, requestId?}`.

**Terrain sync**: full heightmap only in `MatchStateSync`. Every other change ships as a small delta embedded in `ShotResolved.terrainDelta` (just the affected column range) — keeps steady-state traffic to a few hundred bytes per shot.

### 2.5 Threading/broadcast plumbing

- `PlayerSession.send(Object)` serializes via one shared `ObjectMapper` and writes with Undertow's `WebSockets.sendTextAsync`.
- `MatchActor.broadcast(MatchEvent)` iterates connected sessions in the match.
- Command ingestion: `AbstractReceiveListener.onFullTextMessage` parses the envelope, validates sender/match membership, and enqueues a `MatchCommand` — no blocking, no game logic on the I/O thread.

---

## 3. Frontend Architecture (Svelte)

### 3.1 Screens/components

```
App.svelte
├── MenuScreen.svelte              (create/join match form, display name entry)
├── LobbyScreen.svelte  (PlayerReadyList, MatchConfigSummary)
├── MatchScreen.svelte  (persists across rounds/shop)
│   ├── GameCanvas.svelte           (the single canvas + render loop)
│   ├── HUD/ (TurnBanner, WindIndicator, AngleDial, PowerBar, WeaponSelect, HealthBars)
│   ├── ShopOverlay.svelte (WeaponCard, ShieldCard)
│   └── PostRoundSummary.svelte
└── PostMatchScreen.svelte
```

### 3.2 Canvas + state integration

- **`GameCanvas.svelte`** is the only component touching the 2D context — owns a `requestAnimationFrame` loop started in `onMount`, cancelled in `onDestroy`.
- **Svelte stores** (`lib/stores/`): `matchStore` (authoritative synced state — terrain heights, players, round, turn, wind), `connectionStore` (status/reconnects/latency), `localUiStore` (ephemeral drag state for angle/power/weapon selection before firing).
- The render loop reads from a mutable `GameSceneState` object updated by store subscriptions, rather than subscribing every frame — avoids per-frame reactivity overhead. Draw order per frame: parallax background → terrain (from `heights`) → scorch marks → tanks → active projectile animation (interpolated along `ShotResolved.trajectory`) → explosion sprite → wind cosmetic.
- **Client-side prediction** (nice-to-have, not v1): defer to a post-M3 polish milestone. v1 simply waits for `ShotResolved` before animating — perfectly fine given turn-based pacing.
- **Input**: `AngleDial`/`PowerBar` write to `localUiStore`; `Fire` sends the WS message and disables input until `ShotResolved`/`FireRejected` (optimistic-disable, not optimistic-outcome).

### 3.3 Asset plan (v1 minimum)

- **Tanks**: one base chassis spritesheet, per-player recolor via canvas tint (not N palette-swapped sheets). Body sprite static + turret sprite rotated via canvas transform (not frame-animated) — cuts art scope significantly for v1. Frames: idle, fire-recoil (2-3), destroyed (1-2).
- **Projectiles**: ~6 sprites covering the 10-weapon roster by shared category (basic shell, cluster/MIRV bomblet, napalm canister, tunneling drill, bouncing ball, nuke).
- **Explosions**: one frame-animated spritesheet (6-8 frames) reused at different draw scales, plus napalm and nuke variants — 3 variants total.
- **Terrain texture**: generate procedurally via canvas gradient/pattern fill keyed off depth-from-surface for v1; replace with hand-painted tiles in the art-pass milestone (M5).
- **Background**: 2-3 static parallax layers.
- **Shields**: a simple radial shimmer/bubble overlay, no complex art.

Actual art asset creation (drawing sprite sheets) is a separate content task outside this plan's scope — the above defines *what's* needed and where simplifications (recolor-not-redraw, procedural terrain texture, shared projectile sprites) reduce that scope for v1.

---

## 4. Core Gameplay Systems

### 4.1 Terrain generation

- **Midpoint displacement** (1D fractal terrain) over the world width (e.g. 1600 columns): seed endpoints at a random base height, recursively displace midpoints with a decreasing random offset (start ±120, halve each level with damping exponent ~0.55-0.65), clamp to world bounds (e.g. 80-550), light smoothing pass (3-tap moving average).
- Flatten small windows (±15 columns) around each tank spawn x-position after generation; spawn positions evenly distributed with jitter and minimum spacing.
- Fresh terrain each round using a per-match seeded `SplittableRandom` (seeded from matchId + roundNumber) for reproducibility and testability.

### 4.2 Projectile physics (server-side, deterministic)

- Simple kinematic simulation (not a physics engine): state `x, y, vx, vy`.
- `vx0 = power * cos(angleRad) * powerScale`, `vy0 = -power * sin(angleRad) * powerScale` (tunable `powerScale = 4.0`).
- Fixed-timestep integration (`dt = 1/60`): `vx += windAccel*dt`, `vy += gravity*dt`, `x += vx*dt`, `y += vy*dt`. Tunable defaults: `gravity = 220 units/s²`, `windAccel = wind.strength * 4` (wind range -20..20 → accel -80..80).
- Step until terrain hit, tank hit (within ±14 units hitbox), or out-of-bounds.
- Resample to ~30-40 trajectory points for `ShotResolved.trajectory` (not every raw step) to keep the message compact.
- Weapon-specific hooks modify the base loop: MIRV splits at apex into 3-5 children; bouncing shot reflects `vy` (×0.6 energy loss) below a shallow-angle threshold, up to 3 bounces; tunneling continues through terrain up to a max penetration depth before detonating.

### 4.3 Damage model

- Terrain: `applyCrater(impactX, R, falloff)`, parabolic dig profile, max depth ~`R*0.8` tapering to 0 at `R`.
- Tanks within `R` of impact: `damage = D * smoothstepFalloff(distance/R)` (1.0 at center, 0.0 at edge).
- Direct-hit bonus: ×1.3 damage if terminal point is within ~8 units of a tank's exact position.
- Shield interaction applied before subtracting from health (see shield table).
- Self-damage applies if the shooter's own tank is within blast radius (e.g. tunneling/close shots).
- Cash reward: `damageDealt * 5` (to others only), credited to shooter.
- Elimination at health ≤ 0; round ends at ≤1 tank alive (safety cap: 60 turns/round, award to highest remaining health if hit).

### 4.4 Weapon roster (v1: 10 weapons + 3 shields)

| Weapon | Behavior | Blast R | Center dmg | Price | Default qty |
|---|---|---|---|---|---|
| Basic Shell | Standard ballistic | 30 | 25 | free | ∞ |
| Baby Missile | Standard, flatter/faster | 22 | 18 | 0 (starter) | 5 |
| Heavy Cannonball | Standard, bigger/slower | 45 | 40 | 150 | 3 |
| MIRV | Splits into 3-5 children at apex (±15° spread) | 25/child | 15/child | 300 | 2 |
| Napalm | Elevated splash radius/damage (simplified — true DOT deferred to v2) | 50 | 20 | 250 | 2 |
| Tunneling Shot | Continues through terrain up to 40-unit penetration, carves a tunnel | 25 | 30 | 200 | 2 |
| Bouncing Betty | Reflects on shallow-angle impact (<35°), up to 3 bounces, ×0.6 speed loss each | 30 | 25 | 220 | 2 |
| Cluster Bomb | Primary detonation + 4 sideways bomblets | 20/12 | 20 | 280 | 2 |
| Digger | Small blast, ×1.8 crater depth — terrain-shaping tool | 20 | 10 | 120 | 3 |
| Nuke | Standard arc, massive radius/damage, rare | 90 | 70 | 600 | 1 |

**Shields**:
| Shield | Effect | Price |
|---|---|---|
| Absorb | -50% incoming damage while active, up to 80 cumulative absorption before breaking | 200 |
| Deflect | First direct hit fully negated then breaks (binary block); near-miss splash still applies at 60% | 250 |
| Reflect (simplified) | -30% damage taken, 20% of blocked damage returned as bonus cash next turn (true projectile reflection deferred as stretch goal) | 300 |

Shields are activated by spending a turn (the `Fire` message's `weaponId` can reference a shield id; `RESOLVING` sets `activeShieldId` instead of running projectile physics) — matches classic genre convention and keeps the turn state machine simple.

### 4.5 Shop/economy numbers (tunable defaults, centralize in one `EconomyConfig`/`PhysicsConfig` class, not scattered magic numbers)

- Starting cash: 500/player.
- Cash carries forward across rounds (cumulative); match winner = highest cash at `MATCH_COMPLETE` (track damage/kills too, for tiebreakers/flavor).
- Cash per damage point dealt to an opponent: 5.
- Elimination bonus: +100 flat.
- Round-survival participation bonus: +50 flat.
- Shop phase duration: 30s, server-enforced.
- Price tiers: free/starter (0), cheap (120-150), mid (200-280), premium (300), rare (600).
- **Shop stock is limited, not infinite** (M4 addition beyond this table — user feedback: "the shop should not be unlimited in stock... this plays into tactics"). Each purchasable weapon/shield has a `shopStock` count that is a **shared pool across every player in the match**, not per-player, replenished fresh at the start of each shop phase. Sized in inverse proportion to power/price (e.g. Nuke: 3, Baby Missile: 20) so scarce/powerful items create real "buy it now before someone else does" tactics. `basic_shell` is excluded from the shop entirely (its `defaultQty` is already -1/unlimited). See `server/.../domain/weapon/WeaponDef.java`/`ShieldDef.java`'s `shopStock` field and `Match.purchase()`'s `OUT_OF_STOCK` rejection.

All numbers above are explicitly starting values for playtesting, not final balance.

---

## 5. Build Phasing (Milestones)

- **M0 — Scaffolding**: repo structure, Gradle server with a bare Undertow WS echo endpoint, Vite/Svelte client that connects and shows status. *Checkpoint: ping round-trips.*
- **M1 — Single-shot smoke test**: one hardcoded match/terrain/weapon (basic shell), two hardcoded tanks, no lobby (auto-join), no turn enforcement. Server computes trajectory/crater/damage, client renders terrain + tanks + projectile arc + crater carve. *Checkpoint: two tabs fire a shell, see identical terrain deformation and damage on both — the core "does server-authoritative multiplayer loop work" gate.*
- **M2 — Full lobby & turn-based flow**: `CreateMatch`/`JoinMatch`/`SetReady`/`MatchStarted`, real turn order with server-enforced active-player-only firing, wind display, turn timers, disconnect/reconnect, round-end/elimination, up to 8 players. *Checkpoint: full multi-client match from lobby through round completion with turn gating.*
- **M3 — Full weapon roster + shields**: all 10 weapons + 3 shields, weapon-select HUD, shield-as-turn-action flow. *Checkpoint: every weapon/shield fireable and behaves distinctly.*
- **M4 — Shop/economy**: cash tracking, shop message flow, price table, loadout persists into next round, match-end standings by cumulative cash. *Checkpoint: full multi-round match, purchases and cash correct across rounds.*
- **M5 — Pixel-art asset integration**: replace placeholder rendering with real sprites (tanks, projectiles, explosions, parallax background), procedural terrain texture, scorch-mark overlay persistence. *Checkpoint: visuals match target aesthetic.*
- **M6 — Polish/juice**: screen shake, sound hooks, optional client-side prediction, turn-timer urgency states, spectator mode, replay/history (stretch), basic anti-abuse (Fire spam rate limiting, input validation). *Checkpoint: game feels good to replay, not just functionally correct.*

This ordering front-loads the highest-risk item (server-authoritative shot resolution + terrain sync over WebSocket, M1) before investing in breadth (weapon count, art).

### Future ideas (post-v1, not yet scheduled into a milestone)

- **Single-player mode + a bot opponent** (user request, noted for future reference). Not scoped yet — would need at minimum a bot decision loop (pick a weapon/angle/power against the current terrain+opponent state) that can act through the same server-authoritative `Match.fire()` path a human player uses, so it doesn't need its own parallel code path. Revisit once the human-multiplayer game loop (through at least M4) is solid.

---

## 6. Testing/Verification Approach

### 6.1 Server-side automated tests (JUnit 5)

- `TerrainGeneratorTest`: fixed-seed determinism, spawn-pad flattening.
- `CraterMathTest`: affected column range, falloff shape at center/edge, floor clamping.
- `ProjectileSimTest`: table-driven ballistic cases cross-checked against closed-form projectile motion (tolerance for discretization); per-behavior tests for MIRV split, bounce count/angle, tunneling depth cap.
- `DamageCalculatorTest`: falloff curve at known distances, direct-hit bonus radius, shield mitigation per type, self-damage.
- `TurnManagerTest`: turn order skips eliminated/disconnected players, non-active-player `Fire` rejected, round-end at ≤1 alive, shop timeout transitions correctly.
- `MatchActorConcurrencyTest`: fire concurrent `MatchCommand`s from multiple threads at one `MatchActor`, assert final state matches sequential-order processing — validates the queue-based concurrency model under contention.

### 6.2 Manual multiplayer end-to-end verification

- Run `./gradlew run` + `npm run dev` locally; open N browser tabs/windows (incognito for separate sessionStorage/`playerToken`s) joining the same match by code.
- Per-milestone checklist:
  - M1: fire from tab A, confirm tab B receives an identical `terrainDelta`/damage (compare via WS frame inspector in devtools).
  - M2: close a tab mid-turn, confirm auto-skip after timeout; reopen with stored `playerToken`, confirm `Rejoin` restores state.
  - M3/M4: play a full match across all weapons and a shop cycle with ≥3 tabs, confirm no cash/loadout desync.
- Add a togglable structured server-side debug log (one line per `MatchCommand`/`MatchEvent`) to trace a specific match's event sequence when debugging desyncs.
- Optional later: a small Java WS test client that scripts N simulated players joining/firing randomly, to fuzz-test `MatchActor` under concurrent load without human testers.

---

## Critical Files to Create First

1. `server/src/main/java/com/brutaltank/match/MatchActor.java` — the single-threaded actor/state machine, the concurrency backbone of the backend.
2. `server/src/main/java/com/brutaltank/net/BrutalTankServer.java` — Undertow bootstrap and WebSocket routing entrypoint.
3. `server/src/main/java/com/brutaltank/domain/terrain/Terrain.java` + `TerrainGenerator.java` — destructible heightmap and crater math.
4. `server/src/main/java/com/brutaltank/domain/weapon/ProjectileSim.java` — server-authoritative trajectory physics.
5. `shared/protocol.md` — canonical message schema both sides implement against.
6. `client/src/lib/net/` + `client/src/lib/stores/matchStore.ts` — WebSocket client and the store all rendering/HUD depends on.
