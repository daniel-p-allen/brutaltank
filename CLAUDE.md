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
  gets recorded in `docs/asset-sources.md` (not created yet — create it
  alongside the first real asset, not before) so provenance is always
  auditable.
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
