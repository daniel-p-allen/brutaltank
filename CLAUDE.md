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
are pushed less, light ones more, per user feedback), plus a **temporary
debug-only wind-override slider** (`DevSetWind`/`WindOverridden`, an orange
"DEBUG Wind" slider in `FireControls.svelte`) added specifically to
manually verify the wind-direction fix below — **every piece of it is
commented "TEMPORARY DEBUG-ONLY" and should be stripped out** once that
verification is done; see its `PLAN.md` Future Ideas entry.

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

**Risk/reward for skipping Trajectory Help, same session (2026-08-23):**
firing without it grants +25% damage and 2x cash on that shot (the two
compound to ~2.5x cash — confirmed as intended, not a bug — since cash is
earned from the already-boosted damage). `Fire.trajectoryHelpUsed`
(client-trusted, see `shared/protocol.md`) drives
`Match.applyDetonations`'s multipliers; Nuke always gets the bonus since
help is never available there. Building this surfaced a real gap: player
cash was never live-updated from `ShotResolved.cashEarned` client-side
(only from `ShopUpdate`/`TurnForfeited`) — fixed in `matchStore.ts`'s
`applyShotResolved`. Also added a new live broadcast pair,
`TrajectoryHelpUpdate`/`PlayerTrajectoryHelp` (mirrors `AimUpdate`/
`PlayerAiming`), so `MatchScreen.svelte`'s players list can show every
player's live cash and Trajectory Help on/off status, per user request.

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

## Known open item: HUD/UI needs a real design pass

Flagged by the user via screenshot (2026-08-22) — the current HUD (weapon
select, fire controls, shop, turn banner) is placeholder-quality:
inconsistent button widths, inconsistent corner radii, weak visual
hierarchy, no real design system. Filed in `PLAN.md`'s Future Ideas as
"HUD/UI visual overhaul" — read that entry for the full scope (which
components, what the user explicitly asked for: consistent widths, rounded
edges, genre-appropriate design research first) before starting on it.
Not started yet.
