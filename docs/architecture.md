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
- `server`: `./gradlew run` — Undertow listens on `:6154`, serving `/ws`.
- `client`: `npm run dev` (Vite on `:5173`), dev WS URL defaults to `ws://localhost:6154/ws` (override via `VITE_SERVER_URL`).
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
- **Filed idea, not yet scoped/implemented**: blast damage is currently uniform/omnidirectional (`smoothstepFalloff(distance/R)` with no angle term). User feedback: damage should be directional, shaped along the projectile's trajectory angle at impact, rather than a uniform circle — *except* for "big round bombs" (e.g. Nuke), which should stay omnidirectional. Needs a scoping pass before implementation: what exactly "shaped along trajectory" means for the falloff function, and which weapons count as "big round bomb" vs directional.

### 4.4 Weapon roster (v1: 10 weapons + 3 shields)

| Weapon | Behavior | Blast R | Center dmg | Price | Default qty |
|---|---|---|---|---|---|
| Basic Shell | Standard ballistic | 30 | 25 | free | ∞ |
| Baby Missile | Standard, flatter/faster | 22 | 18 | 0 (starter) | 5 |
| Heavy Cannonball | Standard, bigger/slower | 45 | 40 | 150 | 3 |
| MIRV | Splits into 3-5 children at apex (±15° spread) | 25/child | 15/child | 300 | 2 |
| Napalm | Elevated splash radius/damage (simplified — true DOT deferred to v2) | 50 | 20 | 250 | 2 |
| Tunneling Shot | Continues through terrain up to 40-unit penetration, carves a tunnel | 25 | 30 | 200 | 2 |
| Bouncing Betty | Always reflects off terrain (no angle gate), 3-5 bounces by first-hit angle, ×0.6 vertical / ×0.85 horizontal speed loss each, flat 25% damage per connecting bounce | 30 | 25 | 220 | 2 |
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

- **Keyboard controls — implemented, 2026-08-24** (`client/src/lib/game/input/keyboardInput.ts`, wired into `FireControls.svelte`). A/D angle, W/S power (smooth held-key ramp via `requestAnimationFrame`), Spacebar fire, 1-9/0 select the 10 hotbar weapons — all gated identically to the matching on-screen control. Found and fixed a real bug in the process (a naive "any `<input>` is a typing target" guard blocked every shortcut once the range-slider inputs had focus — see `CLAUDE.md`'s 2026-08-24 part 3 section for the full story).
- **Bug: reported live, not yet reproduced — "both players readied up, match stuck on lobby" (2026-08-24).** User report on the deployed app, two separate fresh tabs (confirmed not a duplicated/`window.open`-from-opener tab — see below). Both names showed "Ready" in the roster but no `MatchStarted`. 3 separate scripted repro attempts directly against the live deployed server (WS-frame-level capture, human-realistic pacing) all completed normally. Leading theory if it recurs: `Match.setReady`'s `allReady` gate requires `connectedCount >= 2` of currently-`connected` players, but `buildLobbyUpdate`'s roster doesn't filter on `connected` — so a player whose server-side `connected` flag went false could still show a stale "Ready" badge while silently blocking the gate forever, with no client-visible error. Unconfirmed; see `CLAUDE.md` for full investigation notes. (Separately, *did* confirm as real but not what happened here: opening a new tab via "Duplicate Tab" or a same-origin `window.open`/ctrl-click copies `sessionStorage`, so that tab silently auto-rejoins as the *same* player instead of becoming a distinct second one — worth remembering for future manual testing.)
- **HUD/UI visual overhaul — dropped, 2026-08-24.** A 3-direction mockup canvas (Command Bar / Tactical Corners / Dashboard Strip) was drafted 2026-08-23 for user pick; user reviewed and rejected all three ("Not liking those designs") — no direction will be built from that canvas. The outer `.game-frame` container (`App.svelte`: rounded rect, border colored to the local player's tank color, `width: fit-content` fixing the HUD-cards-vs-canvas width mismatch) stays as already-implemented, unrelated to the rejected directions. If a HUD redesign is revisited later, start fresh rather than reviving these 3 directions.
- **Opt-in trajectory-preview aim assist — implemented** (`client/src/lib/game/render/trajectoryPreview.ts`, `stores/trajectoryHelpStore.ts`, wired into `GameCanvas.svelte`/`FireControls.svelte`). A "Trajectory Help: On/Off" toggle button next to Fire (default off, persisted to localStorage) shows a spread-out dotted preview while aiming, computed from a from-scratch ballistic sim that mirrors `ProjectileSim.java`'s `GRAVITY`/`POWER_SCALE` constants and now **does account for the selected weapon's real weight** (`WEAPON_PHYSICS`'s `gravityMultiplier`/`powerScaleMultiplier`, refined same day per user feedback: "that trajectory does need to take into consideration weight") — but still ignores wind and homing/bounce/tunnel/screen-wrap, deliberately, so it stays a rough guide rather than a solved aim.
- **Trajectory Help is deliberately unavailable for Nuke — implemented (2026-08-23).** What was originally filed as an unreproduced bug (2026-08-22: Trajectory Help appeared to "break" for Nuke, button reading disabled with no preview) turned out to be the desired behavior, per explicit user decision: a rare/premium weapon shouldn't get an aim assist. Rather than continue investigating a root cause for accidental behavior, this is now built deliberately: `FireControls.svelte`'s `trajectoryHelpUnavailable` (true when `$weaponSelectStore === 'nuke'`) sets the button's `disabled` attribute and its label to "N/A"; `GameCanvas.svelte`'s preview-draw condition adds an explicit `selectedWeaponId !== NUKE_WEAPON_ID` check so the dotted preview is suppressed the same way. Selecting a different weapon restores the button/preview immediately, same as before.
- **Risk/reward bonus for firing without Trajectory Help — implemented (2026-08-23), rebalanced (2026-08-24).** Per explicit user request: "if you choose NOT to use the trajectory help, your earnings are doubled and damage is 25% more." `Fire`'s new `trajectoryHelpUsed` boolean (client-sent, trusted — see shared/protocol.md's own note on why this one field isn't independently re-derivable server-side, same trust level as `angleDeg`/`power`) originally drove both a damage multiplier and a 2x cash multiplier, compounding to ~2.5x total cash. **Reverted 2026-08-24** per live-playtest feedback ("it should not be 2.5x... it should be +25% for money, but not the damage"): the damage multiplier is gone entirely (no-help shots deal identical damage), and `NO_HELP_CASH_MULTIPLIER` is now a flat 1.25 (was 2.0) — a simple +25% cash bonus, no compounding. Deliberately **not** applied to fall damage (an incidental terrain-collapse side effect, not something the shooter's aim choice caused). Since Trajectory Help is permanently unavailable for Nuke, every Nuke shot always gets this bonus (confirmed with the user, not an oversight — Nuke is a consistent high-risk/high-reward pick as a result). `fireInput.ts`'s `sendFire` computes the flag as `$trajectoryHelpStore && weaponId !== 'nuke'` so a stale toggle-left-on doesn't misreport for Nuke. Regression test: `WeaponAndShieldTest.firingWithoutTrajectoryHelpEarnsMoreCashButSameDamage`.
- **Per-player live cash + Trajectory Help status in the players list — implemented (2026-08-23).** Per explicit user request (asked alongside the risk/reward bonus above, so its payoff is visible turn to turn): `MatchScreen.svelte`'s `.players` row (directly under the turn banner) now shows each player's live cash (`${player.cash}`) and a "help: on/off" badge next to their name/health. This surfaced two real gaps that needed fixing to make the display accurate rather than just added new UI: (1) `matchStore.ts`'s `applyShotResolved` never applied `ShotResolved.cashEarned` to `player.cash` at all before this — cash only ever updated from `ShopUpdate`/`TurnForfeited`, so it silently went stale for a whole round after every damage-earning shot; fixed by summing `cashEarned` per player (a shot can carry multiple entries — damage cash, elimination bonus) and applying it live, same pattern as ammo/health. (2) Trajectory Help's on/off state is purely local (`trajectoryHelpStore`, persisted to `localStorage`) with no prior server-visible footprint, so other clients had no way to know another player's toggle state — added a new live broadcast pair mirroring `AimUpdate`/`PlayerAiming`: `TrajectoryHelpUpdate` (client -> server, sent reactively from `FireControls.svelte` on every toggle) and `PlayerTrajectoryHelp` (server -> broadcast relay, `Match.updateTrajectoryHelp`), tracked client-side in `matchStore.ts`'s new `remoteTrajectoryHelp` record. This broadcast is purely informational — the actual bonus (above) is read fresh off each `Fire`'s own `trajectoryHelpUsed` field, not this live value, since a player can toggle between shots.
- **Consolidated per-player stat cards + running-totals bar + round-end WINNER!! flash — implemented (2026-08-23).** Per explicit user request: "these all need to be in one card, all the same colour... we need a good ending of the round... a really good visual way to show our running totals." Superseded the plain-text `.players` row above (same session, same day) with a bordered card per player (`MatchScreen.svelte`'s `.player-card`), border/name/stat-value color all driven by one `--player-color` CSS variable so every stat visibly belongs to that player at a glance; name, HP, cash, and Trajectory Help status are grouped inside one card instead of a flat inline row. Cards are sorted highest-cash-first (`rankedPlayers`, resorts live) so the current leader is always immediately visible — the leader's card also gets a subtle glow (`.leader` box-shadow) when cash > 0. **Running totals**: each card has a `cash-bar-track`/`cash-bar-fill` bar whose width is the player's cash as a fraction of the current match leader's cash (`maxCash`), giving an instant visual read on relative standing beyond the raw `$` number — cash is already the match's cumulative running total by design (carries across rounds per section 4.5), so this is a direct visualization of it, not a new stat. **WINNER!! flash**: on `RoundEnded` (the null -> non-null transition of `roundEndedInfo`, watched explicitly so it fires once per round, not on every reactive re-render across the whole shop/next-round window `roundEndedInfo` stays populated for), the round-end card's winner heading gets a `.flashing` CSS class (`winner-pulse` keyframe: opacity/scale pulse, 0.5s cycle) for exactly 5 seconds via a `setTimeout` clearing it, colored to the winner's own tank color, plus a new synthesized victory fanfare (`soundManager.ts`'s `playRoundWin`, a 4-note ascending major-triad arpeggio + a bright final two-note chord — same Web Audio synthesis policy as every other sound, no new asset). A draw (safety-cap round end, no `winnerPlayerId`) gets a plain "Draw" heading, no flash/fanfare.
- **Wind now scales with weapon weight — implemented** (`ProjectileSim.java`: `windAccel` divided by `gravityMultiplier`, per user feedback: "wind should play a part on weight of the weapon, heavy weapons are less effected"). Reuses the existing weight-class `gravityMultiplier` rather than adding a new stat — heavier weapons (Nuke/Digger/Heavy Cannonball) are pushed proportionally less by wind, lighter ones (Baby Missile) more; `gravityMultiplier: 1.0` (most of the roster) is unaffected, so this only visibly changes the 3 heavy-tier and 1 light-tier weapons' wind sensitivity.
- **TEMPORARY DEBUG-ONLY manual wind override slider — removed (2026-08-23).** Was added per explicit user request ("put a temporary 3rd slider in... so i can zero the wind and check everything") to manually verify the wind-direction fix (see item above). Once verification was confirmed done, every piece was stripped: the `DevSetWind`/`WindOverridden` message pair (`Payloads.java`, `Match.devSetWind`, `BrutalTankServer`'s dispatch case), `client/src/lib/game/input/devInput.ts` (deleted outright), and the orange "DEBUG Wind" slider in `FireControls.svelte`. It was never documented in `shared/protocol.md`, matching its explicitly-temporary status.
- **Bug: Digger passed straight through a tank without hitting it — fixed (2026-08-23).** Root cause confirmed: in `ProjectileSim.simulate`'s TUNNELING branch, once `inPenetration` became true the per-step loop unconditionally `continue`d past the tank-hit check for the rest of the underground path — the comment literally said "no further terrain/tank checks." Any tunneling shot (Digger, Tunneling Shot) that passed a tank while underground never registered a hit. Fixed by running the same `TANK_HITBOX_RADIUS` check inside the `inPenetration` branch before the `continue`. Regression test added: `ProjectileSimTest.tunnelingRegistersATankHitEncounteredWhileUnderground` (verified it fails against the pre-fix code, passes against the fix).
- **Bug: a tank appears to drop/fall before the projectile has actually landed — fixed (2026-08-23).** Root cause confirmed client-side, not server-side: `matchStore.ts` applies `ShotResolved`'s authoritative state (including a tank's post-fall `tank.y`) to the store immediately on receipt, by design, so later messages aren't blocked on animation timing. `GameCanvas.svelte` already deferred *terrain* and *health* rendering during the flight-animation window (`preShotHeights`/`preShotHealth`, rendering the "before" values until the projectile animation catches up) but never did the same for tank Y position — so a tank whose ground gave way snapped to its fallen position the instant the message arrived, well before the projectile's flight animation reached impact. Fixed by adding a matching `preShotTankY` (captured in `matchStore.ts`'s `ShotResolved` handler, threaded through `shotAnimationStore.ts`'s `PendingShotAnimation`, applied the same way `preShotHealth` already was in `GameCanvas.svelte`'s `playersToDraw`).
- **Bots (AI-controlled players) — implemented, 2026-08-25.** Chosen at match creation (`MenuScreen.svelte`'s bot-count + difficulty selectors, 0-6 bots, Easy/Medium/Hard/Mixed), spawned server-side as ordinary `MatchPlayer`s with no socket (`Match.addBot`, sink=null — already safe since `broadcast()` null-checks every sink) that stay `connected=true` forever so they count toward turn order/ready checks exactly like a human, but are structurally unreachable by the disconnect/reap machinery. A new `BotController` (one per match with bots) reacts to the same `beginTurn()`/`openShop()` moments a real client would react to over the wire, scheduling a randomized 1.5-4.5s "thinking" delay before calling `Match.fire()`/`purchase()`/`shopContinue()` directly — no fake WebSocket, no client-side bot simulation. Two pure, independently unit-tested planners drive the actual decisions: `BotAimPlanner` (brute-force grid-searches `ProjectileSim.simulate()` for a candidate angle/power that hits the chosen target, then deliberately degrades it with skill-scaled Gaussian noise, a wind-blindness flag, and a small "wild miss" chance — see `BotProfile`'s per-tier ranges) and `BotShopPlanner` (spends a profile-scaled fraction of cash, weighted toward damage-per-dollar value picks for high-`moneySense` bots vs. uniform-random impulse buying for low ones). Verified end-to-end against the real dev servers (Playwright): lobby shows bots pre-readied with a BOT badge, match auto-starts the instant a lone human clicks Ready, bots take every turn/shop action themselves within the timeout, real combat/cash flow resulted. Full server test suite covers the planners + a live integration test (`BotAimPlannerTest`/`BotShopPlannerTest`/`BotIntegrationTest`). See `shared/protocol.md` section 3 for the new `matchConfig.botCount`/`botDifficulty` fields and `isBot` on every player DTO.
- **Weapon physics/rendering audit and fixes — implemented 2026-08-25 (live playtest with bots surfaced these).** Ran an actual physics audit (`ProjectileSim.simulate` across every weapon x angles 15-90 x powers 25-100, flat terrain) rather than guessing at "the bot's shell has insane power" reports. Confirmed range scales ~power^2 and `POWER_SCALE=12` (from the earlier deliberate doubling, see the wind-scaling entry above) let most weapons wrap the 1600-unit map 1-4 times at power >=75/45deg — bots' unconstrained grid search kept finding exactly these "technically hits but flies across the screen twice" solutions. Fixed:
  1. **Bot turrets never visibly aimed** — `BotController` never called the existing `Match.updateAim` (same broadcast a human's slider drag sends). Now calls it right after computing each turn's plan, with a short (default 500ms, respects the test fast-mode override) pause before the actual `fire()` so the turret visibly snaps into position first.
  2. **MIRV children could fly backwards after the split** — a second instance of the same wrap bug fixed earlier for the flight-animation dot (`pointAtProgress`'s `lerpWrappedX`): the MIRV fall-phase block solved each child's fall velocity from a raw `impact.x - splitPoint.x` subtraction with no wrap awareness. Fixed with the same shortest-wrapped-path math, factored out as `shortestWrapDx` and reused by both.
  3. **Bots picking wrap-requiring shots** — since the map is only 1600 units wide, no target ever actually *needs* a wrap to reach; `BotAimPlanner.findBestShot` now skips any grid candidate whose vacuum-ballistics estimate implies one, so bots only ever choose direct trajectories.
  4. **`POWER_SCALE` 12.0 -> 9.0** (a 25% cut, applies to everyone) and **`MAX_WIND_VX_CONTRIBUTION` 300 -> 225** (scaled proportionally, keeping its relationship to shot velocity consistent) — explicitly a smaller correction than what fully constraining bots (fix #3) represents, per the user's own framing when asked whether to touch the human-facing curve too ("turn it down by half... we will test it and see if it feels right").
  5. **Wind toned down** — `WIND_ACCEL_PER_STRENGTH` 4.0 -> 2.5 (confirmed linear, no curve, per user question) after live-playtest feedback that max wind (10) felt too strong.
  6. **Digger/Tunneling Shot's tunnel didn't visibly follow the trajectory** — the existing bore-track fix (many small `DetonationSpec`s along the underground path) called the generic *additive* `Terrain.applyCrater`, so dense overlapping digs either compounded (over-dig) or left gaps, never cleanly tracing the curve. New `Terrain.carveTunnelSegment` instead takes the *max* of the column's current height and a falloff-blended target-Y (the segment's actual world-Y) — repeated overlapping calls converge on exactly "the deepest the path got near this column," i.e. the surface literally traces the descending trajectory. Wired in via a new `DetonationSpec.isTunnelSegment` flag, branched on in `applyDetonations`'s crater loop; the final big crater at the tunnel's end is unaffected.

  Verified: full server (`./gradlew test`, includes new `TerrainTest`/`BotAimPlannerTest` cases) and client (`npm run test`, includes new `shortestWrapDx` tests) suites green, plus a live WS-frame-level re-verification against the real dev servers (Playwright) confirming `PlayerAiming` broadcasts now fire for bots and bot-fired shots never show a wrap-sized trajectory jump.
- **Wind scaled to match/bot difficulty — requested 2026-08-25, not built.** User's alternate idea, raised alongside the wind tone-down above: rather than (or in addition to) a flat `WIND_ACCEL_PER_STRENGTH` cut, scale wind's effect to whatever difficulty was chosen at match creation. This is a distinct "match difficulty" concept from bot skill difficulty (`BotProfile`/`Difficulty` — wind affects every player equally each turn, it isn't per-bot) and needs its own scoping conversation: would a bots-only match with no humans just use the bot difficulty directly, and what would a human-only match's wind difficulty default to? Not scoped, not built.
- **Permanent cross-match leaderboard — requested 2026-08-25, not yet built (plan only).** User wants an ongoing running total across games: whoever has the top score for a completed match gets tracked on a persistent leaderboard, not just the single match's final standings. Not scoped yet — open questions to resolve before implementation: what "score" means for the leaderboard (cumulative cash from that one match, as `MatchEnded.finalStandings` already ranks by? total wins across matches? something else), whether it's keyed by `playerId` (ephemeral, regenerated per match/session today — see `Match.addPlayer`) or a persistent display-name/account identity (none exists yet — there's no login system, just a per-match token), and where it's stored (currently zero persistence anywhere in this codebase — `MatchRegistry` and every `Match` live only in server memory and vanish on restart; a leaderboard would be the first genuinely persistent data this project has, needing a real storage decision: flat file, embedded DB, etc.). Revisit with the user to nail these down before scoping a build.
- **Baby Missile: a small terminal-homing nudge, ~6% — implemented** (`WeaponDef.BABY_MISSILE`'s `homingStrength 0.06`, consumed in `ProjectileSim.java`). As discussed: only during the descending half of its arc (past apex), and only once it's already near an enemy tank, bends its velocity direction a little (a ~6% per-tick blend toward the nearest live target) rather than adding true guidance for the whole flight — a small assist on close shots, not a lock-on. Every other weapon stays pure ballistic (`homingStrength` defaults to 0.0 elsewhere; see `docs/weapon-gap-analysis.md`, which is what surfaced this idea).
- **Per-weapon sound design** (user request, filed not implemented). Real-world/genre sound characteristics for the whole roster are gathered in `docs/weapon-gap-analysis.md`'s "Sound target" line on each weapon. Open question noted there: per-weapon distinct sound design, or a smaller shared set (one "explosion" family + one "launch" family) reused across the roster.
- **Closing the weapon gap-analysis findings** (see `docs/weapon-gap-analysis.md`): Heavy Cannonball has no kinetic-only (non-exploding) behavior, Napalm has no burn/damage-over-time mechanic, Bouncing Betty skips instead of self-launching, and every weapon currently shares one identical generic explosion effect (no distinct per-weapon look). None of these are scheduled — filed for a future decision on which, if any, are worth closing.
- **Digger: fizzle (no-op) on a direct tank hit, tunnel-then-blast only on a ground hit** (user request, filed not implemented; scoping advanced by genre research — see `docs/weapon-gap-analysis.md`'s Digger entry). Scorched Earth's own Digger: *"tunnel when they hit ground. If they hit a tank, they fizzle."* Currently a direct tank hit with Digger behaves like every other weapon's direct hit (bonus damage via `DamageCalculator.DIRECT_HIT_MULTIPLIER`); this idea asks for that case to instead do nothing (a dud), reserving the tunnel-and-crater behavior for ground-only hits. Needs scoping: whether "fizzle" means zero damage entirely or some minimal consolation damage, and how it's communicated to the player (a distinct dud sound/visual per the sound-design section below) so a fizzle doesn't just look like a bug.
- **Sandhog — new weapon, shield-bypass burrower** (surfaced by genre research, not previously filed). Scorched Earth precedent: a Digger variant with *"a small but powerful charge, which can destroy an enemy tank from beneath"*, explicitly useful for *"burrowing beneath enemy shields"*. Would be the first weapon in our roster whose damage ignores `ShieldDef` mitigation entirely. Needs scoping: how "beneath" is detected on a 1D heightmap (likely: detonates directly under a tank's x-position after a short tunnel phase, similar to Tunneling Shot/Digger), and whether it bypasses shields fully or partially.
- **Tracer — new weapon, non-damaging targeting aid** (surfaced by genre research, not previously filed). Scorched Earth precedent: zero destructive capability; *"the trajectory of each shot fired with tracer will stay on the screen for some time after the shot is made"* — fired purely to preview a trajectory before committing a real shot. We have no non-damaging weapon category today; every `WeaponDef` carries `centerDamage`/`blastRadius`. Needs scoping: whether this consumes a turn like every other `Fire` (matching the existing "shield activation also spends a turn" precedent) or is a free/unlimited aim-preview action layered on top of the existing live `AimUpdate`/`PlayerAiming` broadcast, which already shows aim angle live — a Tracer might be redundant with that unless it specifically previews the *arc*, not just the angle.
- **Dirt-restoration weapons — new mechanic, terrain-building instead of terrain-destroying** (surfaced by genre research, not previously filed). Scorched Earth family: Dirt Clod/Ball/Ton (*"explode into a sphere of dirt when hitting something"*), Liquid Dirt (*"oozes out wherever it lands, filling holes and smoothing the terrain"*), Dirt Charge, Earth Disrupter. All 10 of our current weapons only carve craters (`Terrain.applyCrater`); there is no terrain-raising operation in `Terrain.java` at all. This is a genuinely new terrain mutation, not a stats variant — needs its own `Terrain.applyFill(...)`-shaped method and real scoping on tactical purpose (denying an opponent's low ground? rebuilding your own cover?).
- **Riot Charge/Blast/Bomb — new weapon family, self-rescue digging** (surfaced by genre research, not previously filed). Scorched Earth precedent: clears a wedge (Riot Charge/Blast, around your own turret) or sphere (Riot Bomb) of dirt, doing *no damage to tanks* — purely for digging yourself out. Directly complements our existing `tankFalls`/terrain-collapse mechanics (`Match.applyDetonations` already drops a tank when its ground gives way) and would pair naturally with the dirt-restoration idea above (something to dig yourself out of, if that ships first).
- **Leapfrog — new weapon, sequential (not simultaneous) multi-warhead** (surfaced by genre research, not previously filed). Scorched Earth precedent: *"three warheads which launch one after another"* — distinct from our MIRV's simultaneous apex-split. Would conceptually reuse MIRV's child-launch infrastructure but on a delay/re-trigger basis (fire, wait, re-launch from impact point) rather than a single split moment.
- **Digger: narrow tunnel along its trajectory, ending in a big hole, then the sides collapse** (user request, filed not implemented). Currently Digger is a single-point-impact weapon (`Behavior.DIGGER`, dispatched like `STANDARD` — it detonates on first terrain contact, no penetration phase at all); its only current signature is a narrow-radius/high-depth-multiplier crater. This request asks for something closer to Tunneling Shot's behavior instead: continue along the trajectory underground first (a real tunneling phase, not just a deep single crater), then a bigger detonation at the end. The post-crater slope-settle pass (`Terrain.settleSlopes`, already runs generically after every shot) may already deliver "then collapse" once the hole is big enough to leave a steep edge — worth checking before building anything new there. Needs scoping: how far it tunnels vs. Tunneling Shot's 160-unit penetration, how much bigger "big hole" means numerically, and whether Digger should just become a Tunneling-Shot variant (small radius, shallow penetration, huge final crater) rather than a distinct behavior.
- **Nuke: a bigger, more dramatic explosion effect — smoke and fire, not just the shared generic flash** (user request, filed not implemented; damage was bumped 70→95 immediately since that part was a simple number). This is the same root gap already noted for the whole roster ("every weapon currently shares one identical generic explosion effect") but called out specifically for Nuke as the highest-priority one to get a distinct look. Needs real client rendering work (a bigger/longer flash, smoke particles, maybe a fire-colored palette) — worth deciding whether to build this Nuke-only first or as part of giving every weapon its own explosion look at once.
- **Bug: a killing shot skips its own flight/explosion animation because the round ends before it plays — filed 2026-08-24, fixed same day.** Root cause confirmed exactly as suspected: `Match.java` broadcasts `RoundEnded`/`ShopOpened`/`MatchEnded` synchronously right after the triggering `ShotResolved`, same tick the client queues that shot's flight animation. Fixed client-side in `matchStore.ts`: all three are now held back while `shotAnimationStore.pendingShotAnimation` is non-null and flushed the instant it clears (the same "animation actually finished" signal `GameCanvas.svelte` already uses). This turned out to be the same root cause behind the 2s round-end splash "not working" — it was firing at the right relative moment, just off the wrong (too-early) trigger.
- **Bug: MIRV children visually appear to launch from a standstill in all directions instead of continuing the parent's trajectory — filed 2026-08-24, fixed same day.** Root cause confirmed exactly as suspected: `projectileRenderer.ts`'s MIRV fall-phase block drew each child as a straight-line eased interpolation. Fixed by solving for the constant initial velocity that reaches each child's known impact point at exactly `MIRV_FALL_DURATION_MS` under real gravity (mirrors `ProjectileSim.GRAVITY`) — an exact fit at both endpoints (split point, impact) that reads as a proper falling arc in between, without needing the server to send each child's actual launch velocity.
- **Bug: deploy pipeline reported success but the server wasn't actually left running — fixed 2026-08-24.** See `docs/deployment.md`'s "Fixed bug" section for full detail. Root cause: `Start-Process -WindowStyle Hidden`-launched children get nested into the SSH session's Windows Job Object, which sshd kills when the session closes. Fixed by launching via a Scheduled Task instead (`schtasks /Create`+`/Run`, `.github/workflows/deploy-server.yml`), which runs outside that job — confirmed working (server stayed up with live established connections well after the deploying SSH session ended). A second, unrelated red herring during debugging: several early fix attempts chased the wrong cause because `$ErrorActionPreference = 'Stop'` was converting a completely benign `schtasks` stderr warning into a terminating exception, masking that the task creation was actually succeeding the whole time.
- **Bug: stale WebSocket connections accumulate in CloseWait, server never reaps them — filed 2026-08-24, fixed same day.** Observed live on the deployed VM: `Get-NetTCPConnection -LocalPort 6154` showed 9 connections stuck in `CloseWait` from the nginx proxy machine's IP after normal browser reload/reconnect testing. Root cause confirmed: `BrutalTankServer.java`'s `onClose` handler only fires on a proper WS close frame; a dropped TCP connection never sends one. Fixed with a periodic idle-reap task (`BrutalTankServer`'s `reapIdleConnections`, checks every 30s, force-closes anything silent past 90s) paired with a new periodic `Ping` from `wsClient.ts` every 20s while genuinely connected, so a real, still-alive connection's `PlayerSession.lastSeenAt` never goes stale enough to be reaped — only a silently-dropped one is.
- **Game/round-end UX gaps noted by the user during 2026-08-24 live deploy testing**: (1) the end-of-round/game results screen needs more information shown (exact content not yet specified — still open, revisit with the user); (2) **no in-app way to restart/start a new match from the UI once one ends — fixed 2026-08-24 (same day).** New `PlayAgain` message (`shared/protocol.md`) → `Match.rematch()`: resets the same `Match` back to `WAITING` in place (same connections/session, no re-login) with the same roster, cash/health/loadout/ready state all reset to fresh-match defaults; `PostMatchScreen`'s "Back to Start" button now sends it instead of only resetting client-side stores (which used to strand the player at the menu). (3) no in-app instructions/how-to-play for a new player — **fixed 2026-08-24**, see `InstructionsScreen.svelte` above; (4) the user wants to double-check the standard shot's actual trajectory/aim behavior against what's expected — **investigated 2026-08-24**: found and fixed a real wind-runaway bug (see `ProjectileSim.MAX_WIND_VX_CONTRIBUTION` below), which was the actual cause of the "weird trajectory" reports.
- **Bug: wind has a wildly inconsistent, duration-dependent effect on trajectories — filed and fixed 2026-08-24.** User report: weak wind (e.g. strength 5) occasionally had an outsized effect, and Bouncing Betty shots at shallow angles would "go super fast." Root cause: `ProjectileSim`'s wind acceleration applied every simulation step for the shot's *entire* flight duration with no cap — a normal high-arc shot (1-8s flight) stayed roughly proportional to wind strength as intended, but BOUNCING shots (each of 3-5 bounces adds more hangtime on top of the original arc) could accumulate 15-20s of wind exposure, letting even weak wind end up contributing more to `vx` than the shot's own launch velocity. Fixed with `ProjectileSim.MAX_WIND_VX_CONTRIBUTION` (300, tuned to sit just above what a normal max-power 45° shot accumulates today) clamping wind's cumulative contribution to `vx` each step, independent of flight duration — ordinary shots are visually unchanged, only pathologically long flights stop compounding past this point. Regression tests: `ProjectileSimTest.windContributionToVxIsCappedRegardlessOfFlightDuration`/`windContributionIsUnchangedForANormalShortFlightShot`.
- **Napalm: ground/tank damage over time, pooling in cavities for ongoing damage while it stays on target** (user request, filed not implemented). Idea as discussed: napalm shouldn't be a single instant hit — it should keep dealing damage on subsequent rounds to the ground and any tank it's touching, with some visual sign of it lingering, and specifically *pool* (collect/deepen) in a cavity/dip in the terrain, doing repeated rounds of damage for as long as it stays pooled there. This is a genuinely new category of mechanic, not a stats tweak — nothing in the engine currently persists any state between turns (every shot resolves fully, synchronously, within one `Match.fire()` call); a lingering/ticking effect would need real scoping: where the persistent state lives, how many turns it lasts, how "pooling in a cavity" is detected/represented on a 1D heightmap, and how repeated damage ticks interact with shields/elimination/shop timing. See `docs/weapon-gap-analysis.md`'s Napalm entry for the real-world burn behavior this is modeling.
- **Bug: a shallow-angle tunneling shot (Digger/Tunneling Shot) can carve a trench across almost the entire map, no falloff with distance — reported 2026-08-25 (live bot playtest), not investigated/fixed yet.** User report: bots fired a tunneling weapon nearly horizontally into the terrain and it visibly reshaped "the entire lay of the land" across a huge span, as if penetration had no real limit. Leading theory, fairly confident: `ProjectileSim`'s `TUNNELING_MAX_PENETRATION`/`DIGGER_MAX_PENETRATION` caps only apply to **cumulative vertical depth** (`Math.abs(y - penetrationEntryY) >= maxPenetration` — see the underground-path loop in `ProjectileSim.simulate`), with no matching cap on horizontal distance or total path length. A shot that enters the ground at a shallow/near-horizontal angle accumulates vertical depth very slowly while still traveling a large horizontal distance each step, so it can tunnel most of the map's width before the depth cap ever triggers — exactly "shooting across the map... impacting the entire path with no falling off of power" as described. The fix direction the user is already pointing at: penetration should drain/decay with distance traveled underground (a real "runs out of power" limit), not just be capped by vertical depth alone — needs scoping (a horizontal-distance cap alongside the existing depth cap? an actual decaying-power model that shrinks the tunnel radius as it goes?) before implementing. Not yet confirmed against the actual code by a targeted test, just diagnosed from the report — verify first.
- **Bug: Baby Missile's trajectory-help preview and the real (wind-favored) shot disagree in the wrong direction — reported 2026-08-25, not investigated yet.** User report: fired Baby Missile with wind blowing in their favor; the Trajectory Help dotted preview showed a path, but the *real* shot landed **short of** (before) where the preview indicated — the opposite of what favorable wind should do (should overshoot the unaided preview, not undershoot it). Leading theory, not confirmed: `trajectoryPreview.ts`'s preview is a from-scratch client-side ballistic sim that already deliberately ignores wind (documented behavior, see the Trajectory Help entry above) — but Baby Missile is also the one weapon with nonzero `homingStrength` (`ProjectileSim.HOMING_ACTIVATION_RADIUS`/terminal homing, see `WeaponDef.BABY_MISSILE`), and the client preview almost certainly doesn't model homing at all, only wind. If a live target is within homing range during the real shot's descent, homing could pull the real trajectory short of the preview's unaimed endpoint, independent of wind. Needs real investigation (confirm the preview code path, confirm whether homing is modeled) before concluding this is the actual cause — filed for a future session, not fixed.
- **Connection/access log for troubleshooting at scale — requested 2026-08-25, not built.** User wants every game logged: every connection/join attempt (successful or failed), with enough detail to troubleshoot problems if a lot of users are playing at once. Explicitly wants it low-resource (plain text, not a database) and date-linked entries (one line/entry per event, timestamped). Not scoped yet — open questions: exact event set to log (candidates: WS connect, CreateMatch/JoinMatch success+failure with reason, disconnect, reconnect grace expiry, match end), what "browser info" means concretely (User-Agent header is available from the Undertow handshake request; IP is available from the socket, though the real client IP behind the nginx TLS reverse proxy — see `docs/deployment.md` — would need `X-Forwarded-For` read and trusted correctly, not just the proxy's own IP), where the file lives and how it rotates (an ever-growing flat file needs a rotation/retention policy or it becomes its own resource problem), and whether this needs any privacy/retention consideration before going live given it would capture player IPs. Revisit with the user to pin these down before scoping a build — this is infrastructure/observability, not a gameplay feature, so it likely belongs in `BrutalTankServer.java`'s connection-handling layer rather than `Match`.

**Shields** (all three surfaced by genre research — see `docs/weapon-gap-analysis.md`'s "Shield Gap Analysis" section; the visual/sound plan for the current 3 shields is decided, see section 7.4 above — these are the *mechanics* questions the research raised, explicitly speculative, none of them recommendations):

- **Absorb: a random per-hit failure chance, like Scorched Earth's base-tier Shield** (surfaced by genre research, not previously filed). Scorched Earth's cheapest shield tier has a small documented chance to simply fail to absorb a hit at all, distinguishing it from the top tier's guaranteed reliability; ours is fully deterministic. Purely speculative — would make our cheapest shield riskier, which may or may not be a texture worth adding.
- **Reflect's price/tier ordering vs. Scorched Earth's precedent** (surfaced by genre research, not previously filed). Scorched Earth's most expensive tier (Heavy Shield) is framed as the strongest/most reliable; our most expensive shield (Reflect, 300) has the *mildest* raw percentage mitigation (-30%, vs. Absorb's -50%) of the three, with its value proposition instead being the 20% cashback. Whether shop price should track raw shield strength, or whether the cashback mechanic is sufficient justification for its price as-is, is an open design question, not a finding that something's wrong.
- **A fourth, Heavy-Shield-equivalent top tier** (surfaced by genre research, not previously filed). Scorched Earth had three purchasable shield tiers; ours has three shields but they map more to "three different mechanics" than "three tiers of the same mechanic." Flagged only because the research surfaced it — not a claim that three shields is insufficient.

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

## 7. Audio & Visual Signature System

Currently every weapon shares one identical generic explosion effect (an
orange flash, radius 6→26 over 250ms — see `docs/weapon-gap-analysis.md`'s
baseline gap note) and, until the pilot below, there was no audio in the
codebase at all. This section is the implementation plan for closing both
gaps — colors/visuals and sound — for the weapon roster (including the new
weapons filed above) and, secondarily, shields and key UI moments.

**Pilot shipped: Bouncing Betty.** The first weapon built end-to-end under
this plan — mechanic redesign, visual, and sound together — as a template
for the rest of the roster before committing to it everywhere. It also
fixed a real bug found along the way: bounce-damage points were excluded
from `allImpacts`, so a bounce that actually damaged a tank produced zero
client-visible feedback. Shipped: a distinct small "spark" flash for a
connecting bounce vs. the normal flash for the final detonation
(`client/src/lib/game/render/projectileRenderer.ts`), and the first real
audio in the client — two synthesized Web Audio sounds (`ricochet`,
`impact_light`) via a new minimal `client/src/lib/audio/soundManager.ts`,
per the sourcing policy in §7.3 below (synthesis was the deliberate choice
for this single-weapon pilot; sourcing real CC0 assets for the rest of the
roster is the natural next step once this pilot's approach is confirmed by
playtest). Everything else in this section remains a plan, not yet built.

**Sound shipped for the full weapon roster.** Following the pilot, every
weapon's launch/impact sound was iterated live against user feedback (on a
throwaway preview board, not committed) and the confirmed picks landed in
`client/src/lib/audio/soundManager.ts`: Basic Shell/MIRV/Napalm/Tunneling
Shot/Digger share the light-shell crack+thump family; Baby Missile gets its
whoosh (also reused, unmodified, as Cluster Bomb's per-bomblet explosion —
a deliberate cross-weapon reuse, not a new sound); Heavy Cannonball gets the
heavy-shell family; Tunneling Shot/Digger get a dirt-scrape-then-muffled-
boom sequence; Cluster Bomb gets its own mortar-tube launch thunk and a
rebuilt canister-pop (the first version read as a game blip, not an
explosion); Nuke is the richest — a real public-domain air-raid-siren
recording from launch (5s, fades out), a real falling-bomb-whistle
recording for the final descent, and a reworked lightning-crack + rumble on
impact (see `docs/asset-sources.md` for the two real-recording sources and
licenses). Visual signatures (§7.1 below) were **not** part of this pass —
only Nuke and Bouncing Betty have distinct visuals; the rest of the roster
still shares the generic flash, which remains a plan.

### 7.1 Visual signature per weapon

**Goal**: each weapon reads as visually distinct on detonation, not just by
crater shape (which already varies — see `WeaponDef.craterDepthMultiplier`)
but by the explosion effect itself. Palette choices below are grounded in
each weapon's researched real-world/genre character from
`docs/weapon-gap-analysis.md`, not arbitrary:

| Weapon | Explosion character | Color direction |
|---|---|---|
| Basic Shell / Baby Missile | plain shell burst (the reference case) | current orange flash, unchanged |
| Heavy Cannonball | heavier, blunter burst; a rolling phase (if built) wants a dust-trail smear along its path | dull grey-brown, less "fire" than a shell |
| MIRV / Cluster Bomb | already multi-point (`allImpacts`) — needs each child impact to flash, not just the shared point | keep shell-orange per child, no new color needed |
| Napalm | sustained, spreading, incendiary | orange→dark red gradient, wider/longer-lived than a shell flash |
| Tunneling Shot / Digger | muffled, most of the energy goes into the ground | dust/earth-brown puff at the bore-track marks, dulled flash at final detonation |
| Digger fizzle (new, §above) | anticlimactic dud | a small grey puff + dim spark, deliberately *not* a bright flash — sells "this one didn't work" |
| Bouncing Betty | **shipped**: every bounce-hit gets a small, fast, sharp-white "spark" (radius 4→10, 150ms) distinct from the final full detonation's normal flash | pale/white spark for a graze, unchanged orange flash for the real blast |
| Nuke | already scoped as the highest-priority distinct effect (filed above) | white-hot core → orange → smoke, bigger radius and longer duration than every other weapon |
| Sandhog (new) | detonates from beneath — genre precedent bypasses shields | flash originates *below* the tank sprite, not at ground level, to visually sell "from beneath" |
| Tracer (new) | non-damaging, no explosion at all | a thin persistent line along the trajectory, no flash |
| Dirt-restoration family (new) | terrain rising, not cratering | brown/tan mound-forming particle burst, inverse motion (upward/outward) from every destructive weapon's burst |

**Code path**: today, `GameCanvas.svelte`'s render loop reads
`pendingShotAnimation` (`shotAnimationStore.ts`) and calls one hardcoded
explosion draw. This needs a new per-weapon config table — e.g.
`client/src/lib/game/render/weaponVisuals.ts` exporting a
`Record<weaponId, { flashColor, flashDurationMs, particleStyle, ringVsBurst }>`
— read by a new `explosionRenderer.ts` (splitting explosion drawing out of
the generic projectile/impact draw path), keyed by `payload.weaponId` from
`ShotResolved`/`shotAnimationStore`'s already-carried `weaponId` field (no
protocol change needed — the data is already there, just unused for this
purpose).

### 7.2 Sound design per weapon

Per-weapon "Sound target" lines are already researched in
`docs/weapon-gap-analysis.md` for all 10 current weapons, plus the new
Digger-fizzle and Cannonball-roll targets added above. Open question from
`PLAN.md`'s original Future Ideas note — **fully distinct per-weapon sounds,
or a smaller shared family set reused across the roster** — is resolved here
in favor of **shared families**, matching the same simplification philosophy
already used for visuals (`~6 projectile sprites covering 10 weapons by
category`, per section 3.3): a small hobby project gets more value from 5-6
well-made shared sounds than 10+ thin/sample-y unique ones. Proposed
families, each mapped from the gap-analysis research:

1. **Light shell** (fire crack + impact thump) — Basic Shell, Baby Missile, MIRV children, Cluster bomblets. **Shipped as `impact_light`** in the Bouncing Betty pilot (a short synthesized low thump), deliberately generic so it's reusable here once the rest of the roster's sounds are built, rather than a bespoke Betty-only sound.
2. **Heavy shell** (deeper/louder version of #1) — Heavy Cannonball, Nuke's initial bang.
3. **Missile whoosh** (sustained launch, not a crack) — Baby Missile's launch specifically (shares #1's impact).
4. **Muffled/underground** (dulled boom) — Tunneling Shot, Digger's real detonation.
5. **Dud** (anticlimactic, no boom) — Digger's fizzle-on-tank-hit.
6. **Incendiary whoosh-crackle** — Napalm.
7. **Two-phase rumble** (sharp bang, then a long low rumble) — Nuke's follow-through (layers with #2's initial bang).
8. **Ricochet** (short percussive skip/graze blip) — **shipped** for Bouncing Betty, played once per connecting bounce, staggered ~90ms apart, ending in family #1's `impact_light` at the final resting point. Replaces the original "Airburst" framing (propellant pop + mid-air bang), which was modeled on the S-mine's self-launching real-world behavior and no longer fits the redesigned always-bounces-along-the-ground mechanic.

**Code path**: a new `client/src/lib/audio/soundManager.ts` — a small pool of
preloaded `HTMLAudioElement`s (or Web Audio `AudioBufferSourceNode`s if
overlapping/latency becomes an issue; not needed at this scale to start)
keyed by family name, with a `playFor(weaponId, event: 'launch'|'impact')`
function that looks up weapon→family in a new config table (parallel to
§7.1's visual table, could live in the same file). Triggered from the same
place `shotAnimationStore`/`GameCanvas` already handles `ShotResolved` — no
protocol change needed. **Browser autoplay policy**: audio can't play before
a user gesture; unlock the `AudioContext`/first play on the player's first
`Fire` click (already a user gesture) rather than on page load. Add a mute
toggle (persisted to `localStorage`, not a new store dependency) since not
every player wants sound.

### 7.3 Asset sourcing & licensing policy

Preference order, in this order every time an asset is needed:

1. **Search first for a suitable existing sound**, restricted to
   genuinely unencumbered licenses — **CC0 / public domain only**, not
   merely "royalty-free" (royalty-free commercial libraries can still
   require attribution, restrict redistribution, or bundle a delivery
   SDK/tracking). Good sources: **Freesound.org** (filter search results to
   the CC0 license explicitly, not just sorted by popularity), **OpenGameArt.org**
   (filter to CC0), **Kenney.nl** audio packs (explicitly CC0, made
   specifically for games — a strong first stop given how well it fits this
   project's scope).
2. **If nothing suitable exists, make our own.** For a retro-styled
   artillery game, simple procedural synthesis (Web Audio `OscillatorNode`/
   noise generation for cracks, thumps, whooshes) is a legitimate and
   actually well-suited option — zero licensing risk, tiny footprint, and a
   good stylistic fit. Self-recorded audio is the other fallback.
3. **Never use an asset whose license or provenance can't be verified.**
   Before any audio file is committed to the repo:
   - Confirm the license on the **hosting site's actual license page for
     that specific file**, not just an aggregator search-result label
     (labels are sometimes wrong).
   - Prefer a file with a named author and an explicit CC0/public-domain
     grant over vague "free for personal use" or unlabeled "royalty free"
     claims — those can still carry legal encumbrance, which is the
     "trackable back to a restrictive source" risk to avoid.
   - Record every sourced asset's origin URL + license in a new
     `docs/asset-sources.md` manifest at the time it's added — filename,
     source URL, license, date — so provenance is always auditable and any
     asset can be pulled later if a source turns out to be misattributed.
   - Self-synthesized/self-recorded assets get a manifest entry too (marked
     "original," no external source), so the manifest is a complete
     accounting of every audio file in the repo, not just the sourced ones.

**File organization**: new `client/src/assets/audio/` directory (mirrors the
existing `assets/` plan from section 3.1), compressed format (`.ogg`
preferred, `.mp3` fallback for broader codec support), small file counts
given the ~8 shared families above rather than per-weapon uniqueness.

### 7.4 Shields — visual/sound signature

Researched in full in `docs/weapon-gap-analysis.md`'s "Shield Gap Analysis"
section (Scorched Earth's Shield/Force Shield/Heavy Shield tiers as genre
precedent, Trophy APS/explosive-reactive-armor as the real analog for
Deflect specifically). Visuals are now implemented — `client/src/lib/game/
render/shieldRenderer.ts` draws a distinct per-shield dome, and
`DamageEvent.activeShieldId` (threaded through `ShotResolved`, see
`CLAUDE.md`'s bug-fix log) keeps that dome in sync across rounds. Sound is
still zero — no activation hum, absorb crackle, or break fizzle exists yet,
and there's still no readout of remaining capacity. Research-backed plan,
by shield:

- **Absorb**: rising electronic hum/whine on activation (0.3-0.5s) paired
  with a translucent dome/ring around the tank sprite — a shader-rendered
  version of Scorched Earth's own reference UI (*"a circle will appear
  around your tank"*). A crackle/zap on each absorbed hit, layered under the
  existing generic blast SFX rather than replacing it (the incoming shot
  still detonates against the shield, just for less). A lower "power-down"
  fizzle on break, dome visibly shattering rather than vanishing. Optional:
  scale the crackle's intensity/pitch as cumulative absorbed damage climbs
  toward `ABSORB_BREAK_THRESHOLD=80`, giving players an audible read on
  "this shield's close to failing" without a numeric readout.
- **Deflect**: same dome-stand-up activation language as Absorb (players
  should recognize "a shield is up" at a glance regardless of type), but a
  tighter/harder-edged shader — faceted rather than Absorb's soft glow, to
  read as a hard block. Since Deflect's absorb-and-break are always the
  same single moment, combine them into one event: a bright, fast metallic
  "ping"/energy-crackle burst simultaneous with the dome shattering outward.
- **Reflect**: same base dome/hum language, tinted/textured differently
  (a warmer color, since this is the economy-integrated shield) to
  distinguish it from Absorb at a glance. A milder crackle on the hit itself
  (matching its milder -30% mitigation), then — this is the one shield
  moment that isn't purely combat feedback — a distinct short "cha-ching"/
  coin-chime cue on the *following turn* when the 20% cashback is credited,
  kept as its own sound family entirely (not the shield-audio family) so
  the economic payoff doesn't get lost inside the next turn's generic UI.

**Code path**: same `weaponVisuals.ts`/`soundManager.ts` infrastructure from
§7.1/7.2, extended to cover `shieldId` alongside `weaponId` — the "dome"
visual is a new render primitive (a persistent overlay while
`activeShieldId` is set, not a one-shot flash like a weapon explosion) that
`GameCanvas.svelte` would need to draw every frame a shield is active, not
just at the moment of activation.

### 7.5 Other UI moments

Smaller follow-on scope: a few key non-shield/weapon UI moments (turn-start
notification, round-end, a shop purchase confirmation) are natural
candidates for the same `soundManager` once it exists. Not scoped further
here — revisit once §7.1-7.4 land.

---

## Critical Files to Create First

1. `server/src/main/java/com/brutaltank/match/MatchActor.java` — the single-threaded actor/state machine, the concurrency backbone of the backend.
2. `server/src/main/java/com/brutaltank/net/BrutalTankServer.java` — Undertow bootstrap and WebSocket routing entrypoint.
3. `server/src/main/java/com/brutaltank/domain/terrain/Terrain.java` + `TerrainGenerator.java` — destructible heightmap and crater math.
4. `server/src/main/java/com/brutaltank/domain/weapon/ProjectileSim.java` — server-authoritative trajectory physics.
5. `shared/protocol.md` — canonical message schema both sides implement against.
6. `client/src/lib/net/` + `client/src/lib/stores/matchStore.ts` — WebSocket client and the store all rendering/HUD depends on.
