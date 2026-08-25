# BrutalTank — instructions for Claude

## Where the plan actually lives

- `PLAN.md` (repo root) — the full design/implementation plan: architecture,
  protocol summary, gameplay systems, weapon/shield tables, milestone
  roadmap (M0-M6), testing approach, and a "Future ideas" backlog of
  **open** bugs/features only (see `PLAN_ARCHIVE.md` below).
- `PLAN_ARCHIVE.md` (repo root, new 2026-08-25) — every bug/feature/
  decision item that started in `PLAN.md`'s Future Ideas backlog and has
  since been fully implemented, fixed, or explicitly closed. Moved out of
  `PLAN.md` so that file stays a list of genuinely open work — check this
  archive first before assuming something needs re-investigating or
  re-building.
- `shared/protocol.md` — the canonical, hand-maintained WebSocket message
  schema. Source of truth for every message shape; both `server/` and
  `client/` implement it by hand (no codegen).
- `docs/architecture.md` — a living copy of `PLAN.md`; keep both in sync if
  editing either. (`PLAN_ARCHIVE.md` is not mirrored into `docs/` — it's a
  standalone historical record, not part of the living architecture doc.)
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

## 2026-08-25 session: playtest bug batch — wrap-render bug, shop-phase disconnect gap, balance

Live-playtest notes surfaced 6 items; investigated all before fixing.

**Fixed, high-confidence:**
1. **MIRV/Digger "flew backwards across the map"** — real client rendering
   bug, confirmed. `ProjectileSim.java` intentionally lets a shot's x wrap
   around the cyclic map edge mid-flight (comment: shots can wrap the
   1600-unit map multiple times at full power). But
   `projectileRenderer.ts`'s `pointAtProgress` lerped x in a straight line
   between two resampled trajectory points with no wrap awareness — when a
   segment straddled the wrap (e.g. x≈1590 -> x≈10), it swept the rendered
   dot backward across almost the entire map in that one segment's slice of
   the 1200ms flight animation, before resuming forward from the correct
   post-wrap position. Read exactly like the report: "went off the top
   left... magically returned... backwards over the person that fired."
   Fixed with `lerpWrappedX` (shortest-path wrap-aware lerp). Regression
   test added (`projectileRenderer.test.ts`, new file).
2. **Heavy Cannonball toned down**: centerDamage 80->60, blastRadius
   45->38 (per "tone down the heavy cannonball... it is devastating").
   Still the roster's second-hardest hitter behind Nuke.
3. **Baby Missile shop price 0->60** (per "baby missiles are bloody free").
   Starting loadout (defaultQty 5) is still free — only shop resupply now
   costs cash. Cheapest paid weapon in the shop, matching its lightweight
   stats.
4. **Shop-phase disconnect was a silent no-op — several real gaps closed.**
   `Match.handleDisconnect` used to `return` immediately whenever
   `status != IN_PROGRESS`, which (beyond the intentional WAITING case)
   silently swallowed SHOP too: no broadcast, and critically no grace timer
   ever scheduled, so a player who dropped mid-shop was invisible and never
   properly reaped into `departed`. Combined with `shopContinue()` already
   treating `!connected` players as skippable for its "everyone's ready"
   check, this meant a shop-phase disconnect could let the remaining
   player(s) advance out of the shop — and, on later rounds, straight to
   match end — with zero warning. This is the leading suspect for "we were
   in the shop and all of a sudden the other player had the match complete
   screen come up," though not confirmed via live repro. Fixed:
   `handleDisconnect` now schedules the grace timer for SHOP same as
   IN_PROGRESS; `onGraceExpired`'s SHOP branch now broadcasts
   `PlayerDisconnected` and calls `endMatch()` (so `MatchEnded` actually
   fires) if that was the last remaining player; `advanceToNextRoundOrEnd`'s
   empty-turnOrder path now calls `endMatch()` too instead of silently
   setting `status = COMPLETE` with no broadcast (same bug, different
   trigger path); `rejoin()` gained a SHOP branch (previously unhandled
   entirely — a reconnect during SHOP got no `MatchStateSync`-equivalent at
   all) that resends `ShopOpened` with the live price list/stock to the
   rejoining client and broadcasts `PlayerReconnected`.

**Investigated, not confirmed — needs a live repro:**
5. **"My number of weapons in the shop is not updating when other players
   buy them."** Read through the full path (`Match.purchase()` broadcasts
   `ShopUpdate` to every connected player; `matchStore.applyShopUpdate`
   patches `shop.stockRemaining` unconditionally; `ShopOverlay.svelte`/
   `ShopItemCard.svelte` are reactive off it, keyed `#each` blocks) and
   found nothing wrong. Possible it's actually downstream of bug #4 above
   (a ghost/disconnected player's client stops receiving broadcasts) rather
   than its own bug — no fix applied pending a repro (match code + rough
   timing).
   **Third check, 2026-08-25 — live Playwright repro, also found nothing
   wrong.** New hypothesis tested specifically: that "other players" in
   the report meant *bots*, since this session's bot-vs-human play is
   where the report likely originated, and only human-vs-human purchase
   visibility had been reasoned about before. Ran a real browser against
   the actual dev servers (human + 1 HARD bot, WebSocket frames captured
   via a `window.WebSocket` monkeypatch), let the bot autonomously buy
   into the shop phase, and watched the human client's own DOM stock
   text update from `20 left` → `18 left` within the first ~2.5s poll,
   exactly matching 3 `ShopUpdate` frames the human client received for
   the bot's purchases (`baby_missile: 20→19→18`), with no purchase
   action taken by the human side at all. Live behavior matches the code
   reading: bot purchases sync to a connected human's shop UI correctly.
   Two independent code reads plus this live behavioral test have now all
   failed to find a defect — still no fix applied; still needs a genuine
   repro (exact match code + timing + who bought what) if it recurs.

**Not a bug, noted and left as-is:**
6. **"instead of just a slider"** — the note as given was cut off with no
   context; needs the user to clarify what they meant before anything can
   be done.

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

## 2026-08-25 session part 2: playtest bug batch, then bots (new main feature)

Two pieces of work this session.

**Playtest bug batch** (server: MIRV/Digger trajectory-wrap render bug,
Heavy Cannonball damage/radius pulled back, Baby Missile given a real shop
price, a shop-phase disconnect gap that likely explains "match complete
popped up mid-shop"): full detail already folded into the relevant weapon/
bug sections above and in `PLAN.md`'s Future Ideas — see git history around
this session for the exact diffs (`projectileRenderer.ts`'s `lerpWrappedX`,
`WeaponDef.HEAVY_CANNONBALL`/`BABY_MISSILE`, `Match.handleDisconnect`/
`onGraceExpired`/`rejoin`'s new SHOP-phase branches).

**Bots — new main feature, planned then built same session (2026-08-25).**
Full design lives in `PLAN.md`'s Future Ideas entry (search "Bots
(AI-controlled players)"); this note is the "how it actually works" pointer
for a future session picking it up.

- **Architecture**: bots are server-side only. `Match.addBot(displayName,
  BotProfile)` adds a plain `MatchPlayer` with `sink=null` (already safe —
  `broadcast()` null-checks every sink) and `connected=true` forever (so it
  counts toward turn-order/ready checks like a human, but is structurally
  unreachable by the disconnect/reap machinery — nothing ever calls
  `handleDisconnect` on it). `Match` gained two tiny hooks —
  `botController.onTurnStarted(...)` at the end of `beginTurn()`,
  `botController.onShopOpened(...)` at the end of `openShop()` — plus a
  handful of read-only package-private snapshot accessors
  (`tankSnapshots()`, `terrainSnapshot()`, `windStrength()`,
  `loadoutSnapshot()`, `priceListSnapshot()`, `isTurnTokenCurrent()`/
  `isShopTokenCurrent()` staleness guards mirroring `onTurnTimeout`'s
  existing `token != turnToken` pattern). Everything else is 5 new files
  in `server/src/main/java/com/brutaltank/match/`: `Difficulty` (enum),
  `BotProfile` (per-bot randomized skill/personality, `forDifficulty`
  factory), `BotAimPlanner` (pure — grid-searches `ProjectileSim.simulate`
  for a target-hitting shot, then degrades it per skill), `BotShopPlanner`
  (pure — spends a profile-scaled cash fraction), `BotController` (the only
  stateful piece — schedules a 1.5-4.5s "thinking" delay via `Match`'s
  existing `ScheduledExecutorService`, then calls `match.fire()`/
  `purchase()`/`shopContinue()` directly, no fake WebSocket).
- **Real physics-sensitivity finding while tuning `BotProfile`**: this
  game's ballistic model (`POWER_SCALE=12`, multi-second flight times)
  makes even a handful of raw power-units of noise swing the landing point
  by hundreds of world-units — a naive "small % error" didn't read as
  "small" at all once simulated. Retuned `BotProfile.forDifficulty`'s
  power-error ranges down substantially from the first-pass numbers
  (HARD 0.5-2, MEDIUM 2-5, EASY 6-12, vs. angle-error which didn't need the
  same correction) after `BotAimPlannerTest` initially failed with ~270-unit
  average misses for a "skilled" bot on a flat 700-unit shot.
- **Config surface**: `matchConfig.botCount`/`botDifficulty` on `CreateMatch`
  (`MenuScreen.svelte`'s new bot-count/difficulty selectors, 0-6 bots,
  default Mixed — Mixed resolves each bot to an independently random
  concrete tier, not one blended skill level). `isBot` threaded onto every
  player DTO (`LobbyPlayerDto`/`StartedPlayerDto`/`PlayerDto` server-side,
  `LobbyPlayer`/`MatchStartedPlayer`/`Player` client-side) — shown as a
  small badge in both `LobbyScreen.svelte` and `MatchScreen.svelte`'s
  player cards. `shared/protocol.md` updated in lockstep per its own
  convention (additive fields, no `v` bump).
- **Verification**: `BotAimPlannerTest`/`BotShopPlannerTest` (pure-function
  unit tests — skilled vs. unskilled miss distance, wind-blind vs.
  wind-aware, never-fires-with-no-ammo, shield-activation-when-low-health;
  shop purchases never exceed cash/stock, money-sense affects reserve size)
  and `BotIntegrationTest` (a lone human + 2 bots auto-starts on Ready,
  reaches `COMPLETE`, bots never `TurnForfeited`). Full server (`./gradlew
  test`) and client (`npm run test -- --run`) suites both green throughout.
  Also did a **real end-to-end manual verification** against the actual dev
  servers (Playwright, not mocked): created a match as a lone human with 2
  bots, confirmed the lobby showed both pre-readied with BOT badges, the
  match auto-started on Ready, and — after firing once as the human to kick
  things off — the bots visibly took their own turns within seconds, dealt
  real damage, earned real cash, and the match progressed exactly like a
  real multiplayer game, entirely on its own from there.
- **Deliberately out of scope this pass** (see `PLAN.md`'s Future Ideas
  entry for the full list): no cosmetic `PlayerAiming`/`TrajectoryHelpUpdate`
  broadcasts from bots (no visible barrel-tracking before they fire), no
  mid-lobby bot add/remove UI (creation-time only, per the user's own
  framing), no per-bot individual difficulty picker (one match-wide setting,
  or Mixed for per-bot randomness within that).

**Also this session**: user asked for a **permanent cross-match
leaderboard** (whoever has the top score for a match goes on an ongoing
running total) — explicitly plan-only, not to be built yet. Filed as its
own `PLAN.md` Future Ideas entry with the real open questions (what "score"
means, `playerId` being ephemeral/per-match today with no persistent
identity system, and this being the *first* genuinely persistent data this
project would ever need — everything today lives only in server memory and
vanishes on restart). Needs a real conversation with the user before
scoping, not a design-by-assumption.

## 2026-08-25 session part 3: weapon physics/rendering audit after live bot playtest

Follow-up the same day: user actually played against bots and reported four
real problems (bot turrets not visibly aiming, MIRV still flying backwards
after a split, shots "crossing the screen twice" before landing, Digger/
Tunneling still not visibly tunneling) plus a separate wind complaint (feels
too strong at max strength). Investigated with an actual physics audit
(`ProjectileSim.simulate` swept across every weapon x angle x power on flat
terrain — a real data-gathering pass, not guessing) before touching any
code. Full detail in `PLAN.md`'s "Weapon physics/rendering audit and fixes"
Future Ideas entry; short version of what the audit found and what shipped:

- **The audit's key finding**: range scales ~power^2 (normal ballistics),
  and `ProjectileSim.POWER_SCALE=12.0` (doubled in an earlier session,
  2026-08-22) was tuned high enough that most weapons already wrapped the
  1600-unit cyclic map 1-4 times at power >=75/45deg. Bots' grid search
  (`BotAimPlanner.findBestShot`) didn't discriminate a direct hit from a
  hit reached by wrapping the map — that's the literal root cause of "how
  can they have so much power," and a wrapping shot is exactly the
  condition that exposes any wrap-unaware rendering code (which is also
  exactly what the MIRV bug turned out to be — see below).
- **Asked the user explicitly** whether to touch the human-facing power
  curve too, since it was a deliberate past tuning choice ("today's max
  power should become the new 50% mark"). Answer: yes, but at roughly half
  the correction bots get — bots get fully constrained to never pick a
  wrapping solution, humans get a real but smaller cut, explicitly framed
  as "we will test it and see if it feels right" (first-pass tuning, not
  presented as final).
- **Six fixes shipped, all server/client tests green + a live WS-frame-level
  re-verification against the real dev servers** (Playwright, intercepting
  the actual WebSocket frames rather than just visual inspection — confirmed
  `PlayerAiming` now broadcasts for bots, and bot-fired shots show no
  wrap-sized trajectory jump while an unrelated human default-slider shot
  did wrap once from wind, which is expected/acceptable since humans keep
  the toned-down wrap mechanic by design):
  1. Bot turret-aim fix (`BotController` now calls `Match.updateAim` before
     firing, with a short pause).
  2. MIRV backwards-after-split fix (`shortestWrapDx`, factored out of the
     earlier `lerpWrappedX` fix, now also used in the fall-phase `vx0` solve
     — this was a *second*, separate instance of the same wrap bug class,
     not a regression of the first fix).
  3. `BotAimPlanner` now analytically pre-filters out any grid candidate
     that would require wrapping the map (the map is only 1600 units wide,
     so a wrap is never actually *necessary* to reach any target, only
     possible at high power).
  4. `POWER_SCALE` 12.0 -> 9.0, `MAX_WIND_VX_CONTRIBUTION` 300 -> 225 (scaled
     proportionally so wind's cap-relative-to-shot-velocity relationship
     stays consistent).
  5. `WIND_ACCEL_PER_STRENGTH` 4.0 -> 2.5 (confirmed linear when the user
     asked — no curve to speak of).
  6. New `Terrain.carveTunnelSegment` (max-based, path-following, replaces
     the old additive-crater bore track for Digger/Tunneling Shot's
     underground segments only — their final crater is unaffected) so the
     visible tunnel actually traces the trajectory instead of over/under-
     digging from overlapping additive craters.
- **A real gotcha worth remembering**: after the `POWER_SCALE` retune, three
  existing tests broke — not from a code bug, but because their specific
  hardcoded power values happened to cross a map-wrap boundary once ranges
  shrank, making an `impactX > otherImpactX` comparison meaningless (both
  are post-wrap-modulo values). Fixed by choosing test power values that
  stay safely under one map-width for both compared shots, with a comment
  explaining why — worth keeping in mind for any *future* `POWER_SCALE`
  retune too, since any test asserting on raw `impactX` distances is
  implicitly coupled to the map not wrapping during that specific shot.
- **Also filed, not built**: wind scaled to a match/bot difficulty setting
  (user's alternate idea, raised alongside the wind complaint) — flagged in
  `PLAN.md` as a distinct "match difficulty" concept from bot skill
  difficulty, needing its own scoping conversation (wind affects every
  player equally per turn, it isn't per-bot).

## 2026-08-25 session part 4: architecture blueprint refresh, two more filed items

User pointed at `docs/brutaltank-blueprint.pdf` (a polished multi-page
diagram doc, last generated 2026-08-21 at commit `469b92c`) and asked for
an updated equivalent covering everything since — bots and the physics
audit above being the two big ones. Built as a new Claude Design canvas
(8 landscape pages: cover, system overview, server domain model, a new
dedicated bots-subsystem page, client component/store map, match
lifecycle, a new dedicated physics/rendering-audit page, and a refreshed
"filed, not built" list), matching the PDF's visual style from the
reference image the user attached (condensed display headline font,
cream/grid background, color-coded bordered boxes: orange=client,
blue=server, gray=domain, red=broadcast/flagged, plus a new violet=new-
this-pass accent). Published at
`https://claude.ai/code/artifact/6bed76eb-484a-48fe-b89c-a95656dc3202`.
Also corrected several claims that had gone stale since the 08-21 PDF
independent of this session's own changes (Bouncing Betty per-bounce
damage is real not cosmetic, per-weapon sound design shipped, Napalm has
its own explosion effect) and fixed the shop-timeout figure (was shown as
30s, actually 600s). One real bug caught and fixed during a self-review
pass before publishing: two CSS spacer divs on the domain-model page had
only their *text* hidden (`visibility:hidden` on the `<h4>`/`<p>`, not the
container), so they rendered as visible empty boxes — fixed by removing
them instead of trying to hide a whole box that didn't need to exist.

**Follow-up, same session: the diagram was only a web link, not an
in-repo file — user caught this.** Asked directly whether it was saved to
`docs/` and whether it had been exported to PDF and checked; the honest
answer to both was no. Fixed properly rather than just answering the
question: the design canvas's own "Export PDF" control wasn't reachable
from an unauthenticated local preview (needs the real claude.ai session),
so instead exported each of the 8 pages as a PNG via the canvas's
per-artboard "Export artboard" menu (Playwright driving the local seeded
`.html`, capturing each real download), assembled them into one real
multi-page PDF myself (`page.pdf()` on a small wrapper HTML, one page per
image, 1360×900 to match the artboards exactly), and verified the result
mechanically (8 `/Type /Page` objects, matching file size) since no
`pdftoppm`/poppler is installed on this machine for the usual PDF-render
check. Saved over the stale `docs/brutaltank-blueprint.pdf`. The live
Claude Design canvas link stays the editable "source" version; this PDF
is the static in-repo snapshot, same relationship the old PDF had to
nothing (it was hand-generated once, this one now has a documented
regeneration path if it goes stale again: re-seed the canvas from
`PLAN.md`, export each artboard as PNG 2×, wrap in HTML, `page.pdf()`).

Two more items surfaced by the user mid-session, both filed in `PLAN.md`,
neither built:
- **Connection/access log for troubleshooting at scale** — every join
  attempt logged (IP, user-agent, timestamp), low-resource flat text,
  date-linked entries, for troubleshooting under real player load. Real
  open questions before scoping: exact event set, how to get the real
  client IP through the nginx TLS proxy (`X-Forwarded-For`, not the
  proxy's own IP), file rotation/retention policy, and a privacy pass
  since it would capture player IPs.
- **Bug, not yet investigated: Baby Missile + favorable wind, trajectory
  help preview disagrees with the real shot in the wrong direction.**
  User report: real shot landed *short of* the preview line, not past it,
  despite wind blowing in their favor. Leading theory (unconfirmed): the
  preview (`trajectoryPreview.ts`) already deliberately ignores wind, but
  Baby Missile is also the one weapon with nonzero `homingStrength` — the
  preview almost certainly doesn't model homing either, and a live target
  within homing range during descent could pull the real shot short
  independent of wind. Needs real investigation next time, not fixed.

## 2026-08-25 session part 5: real UML diagrams, one more filed bug

The blueprint canvas from part 3 was a stylized architecture diagram, not
actual UML — the user asked directly ("do you know what a UML is") and then
asked for real UML class diagrams (server + client), full current state,
as a hi-res PNG. Built with **Mermaid's `classDiagram` syntax** (genuine
UML notation: composition/aggregation/dependency arrows with correct
semantics, `+`/`-` visibility, `«stereotype»` tags, multiplicities,
underlined static members) rather than hand-drawn boxes — this is the
right tool when the ask is specifically "proper UML," not just
"looks like a diagram." Rendered via a local headless Chromium loading
Mermaid from a CDN (`mermaid.render()` called directly, not the
`startOnLoad`+`<pre class="mermaid">` auto-detect path — that path
silently failed with "Syntax error in text" even on source that
`mermaid.parse()` confirmed was valid; calling `render()` directly worked
first try once switched). Saved to **`docs/uml/server.mmd`** /
**`docs/uml/client.mmd`** (editable source) and **`docs/uml/server.png`**
/ **`docs/uml/client.png`** (2532×2100 / 2802×1536, sent to the user
directly too) — learned from the part-3 gap, saved into the repo this
time without having to be asked twice.

Also logged one more bug reported mid-session, filed not fixed: a
shallow-angle tunneling shot (Digger/Tunneling Shot) can carve a trench
across almost the entire map — `ProjectileSim`'s penetration cap
(`TUNNELING_MAX_PENETRATION`/`DIGGER_MAX_PENETRATION`) only limits
**cumulative vertical depth**, not horizontal distance or total path
length, so a near-horizontal entry angle accumulates depth very slowly
while still tunneling most of the map's width before the cap ever fires.
User's own diagnosis, matches the code shape closely — fairly confident
theory, not yet confirmed against the code by a targeted test. See
`PLAN.md`'s Future Ideas for the full write-up.
