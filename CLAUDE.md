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

Built through M3 (full weapon roster + shields) plus a live-playtest polish
pass (terrain/damage animation sync, deep craters reaching the true screen
bottom, terrain collapse + tank fall damage, per-weapon terrain signatures,
a MIRV trajectory bug, wind tuning/indicator). See `PLAN.md` section 5 for
the milestone definitions.

**M4 (shop/economy) is in progress.** Server-side is done and tested
(`Match.openShop`/`purchase`, `BrutalTankServer.handleShopPurchase`,
`ShopTest.java`): `RoundEnded` now opens a timed shop phase before the next
round (unless the match just ended) instead of transitioning immediately.
Includes a shared, match-wide **stock** limit per item (not in the original
protocol.md table — see `WeaponDef.shopStock`/`ShieldDef.shopStock`, added
per user feedback: "the shop should not be unlimited in stock... this plays
into tactics"). **Client-side shop UI is not built yet** — that's the
remaining M4 work (`ShopOverlay.svelte` + weapon/shield cards under
`client/src/lib/components/shop/`, per `PLAN.md` section 3.1's component
list, wired to the now-implemented `ShopOpened`/`ShopUpdate`/`ShopPurchase`
messages).
