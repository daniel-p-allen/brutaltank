# BrutalTank — instructions for Claude

## Where the plan actually lives

- `PLAN.md` (repo root) — the full design/implementation plan: architecture,
  protocol summary, gameplay systems, weapon/shield tables, milestone
  roadmap (M0-M6), testing approach.
- `shared/protocol.md` — the canonical, hand-maintained WebSocket message
  schema. Source of truth for every message shape; both `server/` and
  `client/` implement it by hand (no codegen). Check this before assuming a
  message type/field doesn't exist or isn't planned — e.g. the M4 shop
  messages (`ShopPurchase`/`ShopOpened`/`ShopUpdate`) are already fully
  spec'd here even though nothing implements them yet.
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

Built through M3 (full weapon roster + shields) plus a live-playtest polish
pass (terrain/damage animation sync, deep craters reaching the true screen
bottom, terrain collapse + tank fall damage, per-weapon terrain signatures,
a MIRV trajectory bug, wind tuning/indicator). See `PLAN.md` section 5 for
the milestone definitions.

**M4 (shop/economy) is next**: fully spec'd in `shared/protocol.md` section
5, not yet implemented anywhere (no `Shop*` classes server-side, no shop UI
client-side). Cash/damage bookkeeping it depends on already works
(`Match.java`: starting cash, cash-per-damage, elimination/survival
bonuses, per-player loadout quantities) — there's just nowhere to spend it
yet. `RoundEnded` currently skips straight to the next round with no shop
pause (`protocol.md` section 4 calls this out explicitly as a temporary M2
simplification, not a protocol change).
