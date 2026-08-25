# BrutalTank

A turn-based, multiplayer 2D artillery game — a modernized remake in the spirit of *Scorched Earth* / *Scorched Tanks* / *Pocket Tanks*. Players take turns dialing in angle and power to lob shells across destructible terrain, with a curated weapon roster, shields, and a between-round shop economy. Built with a Svelte + Canvas 2D frontend and a server-authoritative Java/Undertow WebSocket backend.

**Status: M0-M4 shipped** (scaffolding, lobby/match core, weapons/shields, terrain destruction, shop/economy). See `PLAN.md` section 5 for milestone definitions and `CLAUDE.md` for a running log of what's been built and fixed session to session.

See `PLAN.md` for the full design/implementation plan (including the open bugs/features backlog) and `docs/architecture.md` for the living copy of it. Completed backlog items live in `PLAN_ARCHIVE.md`.

## Target platform

**Desktop browser only** (mouse + keyboard, resizable-window responsive canvas). Touch/tablet/phone support is explicitly out of scope for v1 — angle/power input is designed around click-drag with a mouse. Revisit if the game outgrows desktop-only.

## Testing

- **Server**: JUnit 5 (`server/src/test/java/...`), run via `cd server && ./gradlew test`. 77 tests covering terrain, projectile physics, damage/economy rules, the turn state machine, and match lifecycle, alongside the original WS echo smoke test.
- **Client**: Vitest (`client/src/**/*.test.ts`), run via `cd client && npm run test`. 50 tests covering `wsClient`'s connection state machine and reconnect/backoff behavior, `connectionStore`'s status/latency tracking (all against a fake in-memory WebSocket, `lib/net/mockWebSocket.test-util.ts` — no real network or server needed), plus store/logic tests added alongside later milestones.
- **Multiplayer/E2E**: manual, via multiple browser tabs against one local server (see below). Automating this (e.g. with Playwright driving several browser contexts) is deferred until there's real gameplay to assert on — not worth the setup cost during scaffolding.

## Monorepo layout

```
brutaltank/
├── client/     # Svelte + Vite frontend (Canvas 2D rendering, WebSocket client)
├── server/     # Java + Undertow backend (WebSocket server, game logic)
├── shared/     # protocol.md — the hand-maintained WebSocket message schema, source of truth for both sides
└── docs/       # architecture.md — living copy of the implementation plan
```

Client and server are two independently-buildable modules connected only by the message contract in `shared/protocol.md`. There is no shared code and no codegen — both sides implement that document by hand.

## Local dev workflow

Run two processes side by side. There is no unified build yet.

### 1. Server (Undertow on `:6154`)

```
cd server
./gradlew run
```

This starts the WebSocket server, serving the single endpoint `/ws` on port `6154`.

### 2. Client (Vite on `:5173`)

```
cd client
npm install
npm run dev
```

This starts the Vite dev server on port `5173`. The client's dev WebSocket URL defaults to `ws://localhost:6154/ws` (override with the `VITE_SERVER_URL` env var), so the server above must already be running.

### Multiplayer playtesting

Once both processes are running, open `http://localhost:5173` in multiple browser tabs or windows (use incognito/private windows for separate tabs to get independent `sessionStorage`, which is where reconnect tokens live) to simulate multiple players joining the same match.

### Production packaging

Not yet implemented. The plan is a post-v1 Gradle fat-jar task that serves the built `client/dist` via Undertow's `ResourceHandler`, so a single process serves both the client and the WebSocket API.
