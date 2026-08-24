# BrutalTank — instructions for Claude

## Where the plan actually lives

- `PLAN.md` (repo root) — the full design/implementation plan: architecture,
  protocol summary, gameplay systems, weapon/shield tables, milestone
  roadmap (M0-M6), testing approach.
- `shared/protocol.md` — the canonical, hand-maintained WebSocket message
  schema. Source of truth for every message shape; both `server/` and
  `client/` implement it by hand (no codegen).
- `docs/architecture.md` — a living copy of `PLAN.md`; keep both in sync if
  editing either.
- `README.md` — quickstart/dev workflow, also points at the two files above.

**Note to self (past session hit this):** a `Glob` call for `**/PLAN.md` or
`**/protocol.md` with no explicit `path` can silently search the wrong
directory and come back empty, even though both files exist at predictable,
well-known locations (repo root and `shared/`, respectively). Read them
directly by path first — `PLAN.md` and `shared/protocol.md` — rather than
globbing for them.

## Current status

Built through **M4** (shop/economy) — both server and client sides,
including the shop UI (`ShopOverlay.svelte`/`ShopItemCard.svelte` under
`client/src/lib/components/shop/`, wired to `ShopOpened`/`ShopUpdate`/
`ShopPurchase`) and the shared match-wide stock pool
(`WeaponDef.shopStock`/`ShieldDef.shopStock`, `Match.purchase()`'s
`OUT_OF_STOCK` rejection — not in the original `protocol.md` table, added
per user feedback: "the shop should not be unlimited in stock... this plays
into tactics"). Plus a live-playtest polish pass (terrain/damage animation
sync, deep craters, terrain collapse + tank fall damage, per-weapon terrain
signatures, live per-player aim broadcast, a disconnect/round-end fix). See
`PLAN.md` section 5 for the milestone definitions, and
`docs/brutaltank-blueprint.pdf` for a verified architecture/UML +
match-lifecycle reference (landscape PDF, also covers where `PLAN.md`'s
design has drifted from the shipped code — e.g. `Match`'s real
`synchronized`-based concurrency vs. the plan's `MatchActor`/queue design).

Full weapon sound design shipped this same 2026-08-22 session (see
`docs/asset-sources.md` for the 2 real sourced audio files' licenses —
everything else is Web Audio synthesis in `client/src/lib/audio/
soundManager.ts`). Also shipped that session, in order: economy/damage
balance pass (every weapon's damage doubled, blast-radius-vs-tank-footprint
fix, $50 turn-forfeit penalty + $0-cash elimination, a distinct $500
round-win bonus on top of the $50 survival bonus), shield-round-reset,
`ShopContinue` (opt-in early-advance, backup timer now 120s not 30s — see
its own bug note below), MIRV children falling animation, a per-weapon
weight-class system (`WeaponDef.gravityMultiplier`/`powerScaleMultiplier`,
3-tier ★ rating mirrored client-side in `weaponSelectStore.WEAPON_CATALOG`
and shown on each weapon button), an opt-in "Trajectory Help" dotted aim
preview (`client/src/lib/game/render/trajectoryPreview.ts` — accounts for
weapon weight, ignores wind, deliberately inaccurate by design), and a
top-level `.game-frame` container in `App.svelte` (player-colored rounded
border, whole UI framed as one piece) plus the heading doubling as a live
"Your Turn - Ns" indicator.

Later the same session: **wind now scales with weapon weight**
(`ProjectileSim.windAccel` divided by `gravityMultiplier` — heavy weapons
are pushed less, light ones more, per user feedback). A temporary
debug-only wind-override slider (`DevSetWind`/`WindOverridden`, an orange
"DEBUG Wind" slider in `FireControls.svelte`) was added the same session to
manually verify the wind-direction fix below, then **removed entirely on
2026-08-23** once that verification was confirmed done; see its `PLAN.md`
Future Ideas entry.

**Four real bugs found and fixed this session, worth knowing about:**
1. **Shield graphics silently never rendered.** The client only ever learned
   a shield was active from a full `MatchStateSync`, but shields now reset
   every round (per user feedback) — so by the time the next sync arrived,
   the shield was already gone client-side. Fixed by threading
   `activeShieldId` through `ShotResolved`'s `DamageEvent` (shield
   activation *and* every subsequent hit now reports the shield's live
   state) — see `shared/protocol.md`'s `DamageEvent.activeShieldId`.
2. **Wind indicator arrow was decoupled from the actual physics.**
   `WindDto.strength` is already signed (matches `ProjectileSim`'s
   `windAccel` applied straight to `vx`); `directionSign` is just
   `sign(strength)`. `GameCanvas.svelte` was passing `strength *
   directionSign` into `WindIndicator`, which squares the sign — the arrow
   always pointed the same way regardless of actual wind direction. Fixed
   by passing `strength` straight through (the physics itself, using raw
   `windStrength` server-side, was never wrong — this was a display-only
   bug).
3. **Fire wasn't ammo-gated.** Only turn/in-flight state disabled Fire, not
   whether the *selected* weapon had ammo left — a spent weapon could be
   fired again, and `fireInput.ts` played the launch sound optimistically
   *before* the server's rejection came back. Fixed with a separate
   `fireDisabled` in `FireControls.svelte` that also checks `hasAmmo`.
4. **Shop's round-end overlay never cleared on `ShopOpened`** — stayed
   stacked on top of the shop UI, silently eating the shop timer underneath
   it (looked like "the shop randomly times out"). Fixed in `matchStore.ts`'s
   `applyShopOpened`; also bumped the shop's backup timer 30s -> 120s since
   `ShopContinue` is now the primary way it ends.

**Two more real bugs fixed 2026-08-23** (see `PLAN.md`'s Future Ideas
entries for full detail):
5. **Digger/Tunneling Shot could pass through a tank underground without
   registering a hit.** `ProjectileSim`'s TUNNELING branch skipped the
   tank-hit check entirely once penetration began. Fixed by checking it
   every underground step too; regression test added
   (`ProjectileSimTest.tunnelingRegistersATankHitEncounteredWhileUnderground`).
6. **A tank's fall animation triggered before its incoming shot visually
   landed.** `GameCanvas.svelte` already deferred rendering terrain/health
   during the flight animation (`preShotHeights`/`preShotHealth`) but not
   tank Y position, so a tank whose ground gave way snapped to its fallen
   spot the instant `ShotResolved` arrived. Fixed with a matching
   `preShotTankY` threaded through `matchStore.ts` ->
   `shotAnimationStore.ts` -> `GameCanvas.svelte`.

**Trajectory Help is intentionally unavailable for Nuke** (not a bug — user
decision, 2026-08-23: a rare/premium weapon shouldn't get an aim assist).
What looked like a bug on 2026-08-22 (button reading disabled, no preview,
for Nuke specifically) is now built deliberately:
`FireControls.svelte`'s `trajectoryHelpUnavailable` disables the button
(label reads "N/A") and `GameCanvas.svelte`'s `NUKE_WEAPON_ID` check
suppresses the dotted preview, both keyed off `weaponSelectStore === 'nuke'`.

**Risk/reward for skipping Trajectory Help** (2026-08-23, revised
2026-08-24): firing without it grants +25% cash on that shot, damage
unchanged. Originally also boosted damage 25%, which compounded with a 2x
cash rate to an effective ~2.5x cash reward — per live-playtest feedback
("it should not be 2.5x... it should be +25% for money, but not the
damage") this was reverted to a flat +25% cash-only bonus.
`Fire.trajectoryHelpUsed` (client-trusted, see `shared/protocol.md`) drives
`Match.applyDetonations`'s `NO_HELP_CASH_MULTIPLIER`; Nuke always gets the
bonus since help is never available there. Building this surfaced a real gap: player
cash was never live-updated from `ShotResolved.cashEarned` client-side
(only from `ShopUpdate`/`TurnForfeited`) — fixed in `matchStore.ts`'s
`applyShotResolved`. Also added a new live broadcast pair,
`TrajectoryHelpUpdate`/`PlayerTrajectoryHelp` (mirrors `AimUpdate`/
`PlayerAiming`), so `MatchScreen.svelte`'s players list can show every
player's live cash and Trajectory Help on/off status, per user request.

**Player-card redesign + round-end WINNER!! flash, same session
(2026-08-23):** the plain-text players row became one bordered card per
player (`.player-card`, color-driven by `--player-color` so name/HP/cash/
help all read as one player's stats at a glance), sorted highest-cash-first,
each with a `cash-bar-fill` bar showing cash relative to the match leader
(a direct visualization of the already-cumulative running total). Round end
now flashes a `WINNER!!` heading in the winner's color for exactly 5s
(`winner-pulse` CSS animation + `setTimeout`) with a new synthesized victory
fanfare (`soundManager.ts`'s `playRoundWin`).

## Where the weapon/shield research and design work lives

This project has accumulated substantial **design research** (real-world +
genre precedent, mainly from Scorched Earth) that is easy to lose track of
across sessions — check here first before assuming something needs
re-researching:

- **`docs/weapon-gap-analysis.md`** — per-weapon (all 10) and per-shield
  (Absorb/Deflect/Reflect) research: real-world/genre "should be" vs. what
  our code actually does, with an explicit gap and a sound-design target for
  each. This is where the Scorched Earth "Roller"/"Sandhog"/"Riot"/etc.
  precedent research lives, and it directly informs several items below.
- **`PLAN.md` section 5, "Future ideas"** — every weapon/gameplay idea the
  user has raised that isn't built yet, each with open scoping questions (or,
  where the gap-analysis research has answered some of those questions
  already, that's noted inline). Includes newly-surfaced weapon concepts from
  genre research that were never explicitly requested but came up naturally
  (Sandhog, Tracer, Dirt-restoration weapons, Riot self-rescue, Leapfrog) —
  these are filed as candidates, not committed work.
- **`PLAN.md` section 7, "Audio & Visual Signature System"** — the
  actual implementation plan (not just research) for giving each weapon/
  shield a distinct color and sound, including the asset-sourcing policy:
  prefer existing CC0/public-domain sources (Freesound.org, OpenGameArt.org,
  Kenney.nl) over "royalty-free" libraries that can carry hidden
  attribution/tracking obligations; fall back to procedural synthesis or
  self-recording when nothing suitable exists; every asset's source+license
  gets recorded in `docs/asset-sources.md` (created — 2 real sourced audio
  files logged there so far) so provenance is always auditable.
- **`docs/architecture.md`** is kept as a literal in-sync copy of `PLAN.md`
  per this file's existing instruction — if one has a section the other
  doesn't, that's drift; fix it by copying, don't re-derive content.

## Closed item: HUD/UI visual overhaul — dropped 2026-08-24

Flagged by the user via screenshot (2026-08-22). A 3-direction mockup
canvas was drafted (2026-08-23) and rejected by the user (2026-08-24:
"Not liking those designs") — see `PLAN.md`'s Future Ideas entry. Do not
revive those 3 directions if this is picked up again later; start fresh.

## 2026-08-24 session: live deploy stood up, HUD tweaks, instructions screen

Full hosting pipeline (previously partially set up, see
`docs/deployment.md`) was finished and verified working end-to-end this
session: client on Vercel (`wss://brutaltank.aktiva.com.au/ws` via an
nginx TLS reverse proxy on a separate machine, required since Vercel is
HTTPS-only and browsers block `ws://` from an HTTPS page), server on the
friend's VM auto-deployed by `.github/workflows/deploy-server.yml`. Two
real bugs found and fixed in that pipeline — see `docs/deployment.md`'s
"Fixed bug" section for full detail: (1) `Get-CimInstance`-based old-
process kill crashed the SSH-spawned PowerShell host silently, replaced
with a `Get-NetTCPConnection`-based port lookup; (2) `Start-Process`-
launched servers died the moment the deploying SSH session closed (Windows
Job Object inheritance) — fixed by launching via a Scheduled Task instead,
which survives session close.

UI work this session, all shipped:
- **Angle/power sliders** (`FireControls.svelte`) — bigger track/thumb,
  angle in blue, power in amber (unused hue elsewhere), power widened 30%
  further per follow-up request — reflects them being "the main tools".
- **Shop shields** (`ShopOverlay.svelte`/`ShopItemCard.svelte`) — violet
  accent (unused hue elsewhere) plus a 2-word blurb per shield (Absorb:
  "Halves damage", Deflect: "Blocks once", Reflect: "Refunds cash"),
  design-approved via a mockup canvas before building.
- **Instructions screen** (`InstructionsScreen.svelte`, new) — inserted
  between clicking Ready in the lobby and `SetReady` actually being sent
  (`LobbyScreen.svelte`'s `showInstructions` local state; no protocol
  change). Diagram uses real rendering colors (sky/terrain from
  `GameCanvas.svelte`/`terrainRenderer.ts`, tank colors from
  `Match.java`'s `COLORS[]`) and literal copies of the real weapon chip/
  sliders/buttons/shield card styling rather than illustrated icons, per
  explicit user follow-up ("images or diagrams that reflect our finished
  UI UX"). One of 3 directions from an earlier design-canvas exploration
  (`https://claude.ai/code/artifact/5ca7a0b9-7cb5-4dbc-823e-f2a72497e484`),
  user picked "A: Illustrated Diagram".
- **Post-match "Back to Start" button** (`PostMatchScreen.svelte`) — wired
  `matchStore.reset()`/`lobbyStore.reset()` (both already existed for
  exactly this, only ever called from tests before) to a new button.
- **Shop backup timer bumped 120s -> 600s** (`Match.java`'s
  `DEFAULT_SHOP_TIMEOUT_MS`) — the 120s value (set 2026-08-22) was still
  firing during genuine normal-length shopping, force-starting the round
  mid-browse. User explicitly chose "much longer backup timer" over
  "remove entirely" when asked, so this stays a safety net (e.g. AFK
  player), not a hard requirement to click Continue.

Also filed this session: MIRV split-animation client rendering bug, a
CloseWait connection-leak in `BrutalTankServer.java`, and a
killing-shot-skips-its-own-animation bug. **All three fixed the same day**
— see the "2026-08-24 session, part 2" section below.

**2s round-end splash added** (`MatchScreen.svelte`'s `.round-end-splash`,
`splashShowing` state) — per user request ("even if it is a splash screen
for two seconds"), since the shop currently opens immediately server-side
with zero transition. Purely a client-side visual gate over the whole
match screen for 2s on `RoundEnded`, not a server-side timing change —
the shop underneath is already open/interactive the moment the splash
starts, this only delays it being *visible*.

## 2026-08-24 session, part 2: rematch flow, death-delay/splash root cause, wind runaway, reward rebalance

Live-playtest feedback the same day surfaced 4 more issues; folded in the 3
bugs filed earlier that day (MIRV animation, CloseWait leak, killing-shot-
skips-animation) since two of them shared a root cause with the new
reports. Full investigation notes in the session's plan file
(`cryptic-sniffing-crab.md`); summary of what shipped:

1. **"Back to Start" now returns to the same lobby, no re-login.**
   `PostMatchScreen`'s button used to only reset client-side stores, which
   stranded the player at the menu since there was no server-side path back
   from `Status.COMPLETE` to `WAITING`. Added `PlayAgain`
   (`shared/protocol.md`) → `Match.rematch()`: resets the *same* `Match` in
   place (same connections/`matchId`/`playerToken`, no re-auth) — every
   non-departed player's cash/health/loadout/ready state back to fresh-match
   defaults, departed players dropped, `LobbyUpdate` broadcast. Any
   connected player can trigger it (no host gate, symmetric with
   `SetReady`). `matchStore.ts` clears its own `COMPLETE`/`matchEndedInfo`
   state on that `LobbyUpdate` so `App.svelte`'s routing falls through to
   `LobbyScreen` automatically.

2. **Death/round-end UI (and the shop) no longer shown before the killing
   shot's animation plays** — this was the actual root cause behind two
   separate complaints: "the death occurs before we see the animation" and
   the 2s splash "not appearing to work." `Match.java` broadcasts
   `RoundEnded`/`ShopOpened`/`MatchEnded` synchronously right after the
   triggering `ShotResolved`, same tick the client queues that shot's
   flight/impact animation — `matchStore.ts` used to apply all three
   immediately, so the splash/shop/standings covered the screen before the
   kill had visually landed. Fixed by holding them back while
   `shotAnimationStore.pendingShotAnimation` is non-null and flushing the
   instant it clears (the same signal `GameCanvas.svelte` already uses to
   know a shot's animation finished). No change needed to the splash's own
   logic — it was firing at the right *relative* moment all along, just
   off the wrong trigger.

3. **Wind's effect on trajectories is now capped independent of flight
   duration.** Root cause: `ProjectileSim`'s wind accel applied every
   simulation step for the shot's *entire* flight with no cap — normal
   high-arc shots (1-8s flight) stayed roughly proportional to wind
   strength as intended, but BOUNCING shots (each of 3-5 bounces adds more
   hangtime) could rack up 15-20s of accumulation, letting even weak wind
   end up contributing more to `vx` than the shot's own launch velocity
   ("wind 5 having an unusual effect... bouncing occasionally seem to go
   super fast" — user report). Fixed with `ProjectileSim.MAX_WIND_VX_CONTRIBUTION`
   (300, tuned to sit just above what a normal max-power 45° shot
   accumulates today) clamping wind's *cumulative* contribution to `vx`
   each step, leaving ordinary shots visually unchanged.

4. **Trajectory Help reward rebalanced**: no-help shots used to get +25%
   damage *and* 2x cash, compounding to ~2.5x cash since cash is earned
   from the already-boosted damage. Per user feedback ("it should not be
   2.5x... it should be +25% for money, but not the damage") the damage
   bonus is removed entirely; `NO_HELP_CASH_MULTIPLIER` is now a flat 1.25
   (was 2.0).

5. **MIRV children now fall along a real parabola, not a straight-line
   lerp** — `projectileRenderer.ts`'s fall-phase interpolation used to ease
   linearly from the split point to each child's impact, reading as
   "launching from a standstill in all directions." Since the client never
   receives each child's actual launch velocity, this instead solves for
   the constant initial velocity that reaches the known impact point at
   exactly `MIRV_FALL_DURATION_MS` under real gravity (mirrors
   `ProjectileSim.GRAVITY`) — exact fit at both endpoints, reads as a
   proper arc in between.

6. **CloseWait connection leak fixed** with a periodic idle-reap task in
   `BrutalTankServer.java` (closes any connection whose `PlayerSession`
   hasn't been heard from in 90s) paired with a new periodic `Ping` every
   20s from `wsClient.ts` while genuinely connected, so a real, still-alive
   connection never approaches the reap threshold — only a silently-dropped
   one (no close frame ever sent) does.

**Retest pending as of this commit**: the user's first live playtest after
this work (same session) still saw both issue #2 (end screen before the
final shot's animation) and issue #1 (Back to Start returning to the menu,
not the same lobby) reproduce. Before treating these as surviving bugs,
confirmed with the user that the local server/client dev processes being
tested against had **not** been confirmed restarted after this session's
edits — the Java server in particular has no hot-reload, so a
still-running pre-fix process would show exactly this old behavior
regardless of the code changes above. Next step: fully stop and restart
both `server` (`./gradlew run`) and `client` (`npm run dev`), then retest.
If either issue still reproduces after a genuine clean restart, it's a
real regression in the fixes above and needs proper investigation — not
yet confirmed either way.

## 2026-08-24 session, part 3: keyboard controls, HUD mockups dropped, rematch/animation bugs closed

Follow-up session. Three things:

1. **Rematch flow + round-end animation timing bugs (part 2's "retest
   pending" items) — treated as closed.** Rather than a live retest, this
   was verified at the code/test level: the full client suite (`npm run
   test`, 57/57) and full server suite (`./gradlew test --rerun`) both ran
   clean from a cold state, including the regression tests added alongside
   the `fc12b6d` fix commit (`MatchTurnStateMachineTest`,
   `BrutalTankServerTest`, `ProjectileSimTest`, updated
   `matchStore.test.ts`). A genuine live playtest is still the strongest
   signal if one happens, but nothing points to a regression.

2. **HUD redesign mockups dropped.** User reviewed the 3-direction canvas
   from 2026-08-23 and rejected all three ("Not liking those designs").
   Removed from `PLAN.md`'s Future Ideas, `docs/architecture.md`, and this
   file's former "Known open item" section. Don't revive those 3
   directions if a HUD pass comes up again — start fresh.

3. **Keyboard controls added**: A/D angle, W/S power (smooth
   `requestAnimationFrame` ramp while held), Spacebar fire, 1-9/0 select
   the 10 hotbar weapons (`client/src/lib/game/input/keyboardInput.ts`,
   wired into `FireControls.svelte`). **Found and fixed a real bug via a
   scripted two-player browser test** (Playwright against the actual local
   dev server, not mocks): `isTypingTarget()` originally treated *any*
   `<input>` element as a "user is typing, suppress shortcuts" target —
   but the angle/power sliders are themselves `<input type="range">`, and
   dragging one (the most natural first action) kept focus there, silently
   swallowing every subsequent keystroke. This was the literal cause of
   "nothing to do with the keyboard is working" reported live. Fixed to
   only treat real text-entry input types (text/number/email/etc.) plus
   textareas/contenteditable as typing targets; range/checkbox/radio/button
   inputs pass through. Regression test added
   (`keyboardInput.test.ts`'s `isTypingTarget` describe block) pinning the
   range-input case specifically.

**Open, unreproduced report from this session: "both readied up, match
stuck on lobby."** Live report from the user testing the deployed app
(`brutaltank.vercel.app` client / `wss://brutaltank.aktiva.com.au` server)
with two separate freshly-opened tabs (confirmed: not a duplicated tab, so
not the `sessionStorage`-gets-copied-into-a-new-tab browser behavior that
was the first hypothesis and was separately confirmed real via a
`window.open`-from-opener repro — just not what happened here). Both
players' names showed the "Ready" badge in the roster but the match never
started. Investigated via 3 separate scripted two-player runs directly
against the live deployed server (not local), including WebSocket-frame-
level capture and human-realistic pacing (delays between join/ready,
pausing on the instructions screen) — every run's `SetReady`/`SetReady`
pair correctly produced a `MatchStarted` broadcast and both clients
entered the match. `Match.setReady`'s `allReady` gate
(`server/.../match/Match.java` ~line 239) requires `connectedCount >= 2`
of currently-`connected` (not just non-departed) players — the leading
theory if this reproduces again is that one player's server-side
`connected` flag was somehow `false` at the moment the second SetReady
landed (roster still *displays* a departed/disconnected player's stale
`ready=true`, since `buildLobbyUpdate` doesn't filter on `connected` —
only the `allReady` gate does), which would look exactly like this with no
client-visible error. Not confirmed — asked the user to retry and, if it
recurs, capture the match code + browser console output for a targeted
look at that specific match's live state.
