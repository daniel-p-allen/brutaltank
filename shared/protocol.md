# BrutalTank WebSocket Protocol

This document is the single hand-maintained source of truth for every WebSocket message shape exchanged between the BrutalTank client (Svelte) and server (Java/Undertow). Both the server-side DTOs (`server/src/main/java/com/brutaltank/protocol/`) and the client-side TypeScript interfaces (`client/src/lib/protocol/`) are hand-written to match this document — there is no codegen at v1. If you change a message shape, update this file first, then update both implementations to match.

All messages travel as JSON **text frames** over a single WebSocket endpoint: `/ws` (server listens on `:8080`, client dev connects to `ws://localhost:8080/ws`).

---

## 1. Envelope

Every message, in both directions, is wrapped in the same envelope:

```json
{
  "type": "Fire",
  "v": 1,
  "requestId": "optional-client-correlation-id",
  "payload": { }
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `type` | string | yes | The message type name, exactly as listed in the tables below (e.g. `"Fire"`, `"ShotResolved"`). Case-sensitive. |
| `v` | integer | yes | Protocol version. Currently always `1`. Bump this only on a breaking envelope or payload change; additive fields do not require a bump. Receivers should reject or gracefully ignore envelopes with an unrecognized `v`. |
| `requestId` | string | no | Client-generated correlation id (e.g. a UUID or short random token), only meaningful on client→server messages that expect a direct server reply (`Fire`, `Ping`, etc.). The server echoes it back on the corresponding response (`ShotResolved`/`FireRejected`, `Pong`, `ErrorMsg`) so the client can match request to response. Server→client messages that are not a direct reply to a specific client request (e.g. broadcasts like `LobbyUpdate`, `TurnStarted`) omit `requestId`. |
| `payload` | object | yes | The message-specific fields, as documented per type below. An empty-payload message still includes `"payload": {}`. |

Both sides should validate `type` and `v` before touching `payload`. Unknown `type` values, or a payload that fails validation, should be answered with an `ErrorMsg` (see section 5) rather than crashing the connection.

---

## 2. M0 smoke test: Ping / Pong

The very first thing tested end-to-end (M0 checkpoint: "ping round-trips") is a bare echo round trip over the bare Undertow WS endpoint, before any lobby/match logic exists.

### Client → Server: `Ping`

```json
{ "type": "Ping", "v": 1, "requestId": "abc123", "payload": {} }
```

No payload fields. Sent by the client on connect (and optionally periodically) to verify the socket is alive and the server is reachable.

### Server → Client: `Pong`

```json
{ "type": "Pong", "v": 1, "requestId": "abc123", "payload": { "serverTimeMs": 1755640000000 } }
```

| Field | Type | Notes |
|---|---|---|
| `serverTimeMs` | integer | Server's current epoch millis at the time of reply. Optional use: client-side latency/clock-skew display. |

The server echoes back the `requestId` from the triggering `Ping` unchanged. This is the whole M0 checkpoint: client sends `Ping`, server replies `Pong`, client shows connection status.

---

## 3. Lobby messages

Cover match creation, joining, readiness, and reconnection (`LobbyManager`, section 2.1 of the plan).

### Client → Server

#### `CreateMatch`

```json
{ "type": "CreateMatch", "v": 1, "requestId": "r1", "payload": { "displayName": "Dan", "matchConfig": null } }
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `displayName` | string | yes | Player's chosen name, shown to other players. |
| `matchConfig` | object \| null | no | Optional overrides (e.g. `maxRounds`, `maxPlayers`). `null`/omitted uses server defaults (`maxRounds: 4`, up to 8 players). |

Creates a new `WAITING` match, registers it in `MatchRegistry`, and makes the creator the host.

#### `JoinMatch`

```json
{ "type": "JoinMatch", "v": 1, "requestId": "r2", "payload": { "matchId": "m-9f2a", "displayName": "Riley" } }
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `matchId` | string | yes | The match/join code to join. |
| `displayName` | string | yes | Player's chosen name. |

Rejected (via `FireRejected`-style `ErrorMsg`) if the match is full (max 8), already `IN_PROGRESS`, or does not exist.

#### `SetReady`

```json
{ "type": "SetReady", "v": 1, "requestId": "r3", "payload": { "ready": true } }
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `ready` | boolean | yes | Toggles this player's ready state in the lobby. When all connected players are ready, the server triggers `StartMatch` internally and broadcasts `MatchStarted`. |

#### `Rejoin`

```json
{ "type": "Rejoin", "v": 1, "requestId": "r4", "payload": { "matchId": "m-9f2a", "playerToken": "tok-6e1c..." } }
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `matchId` | string | yes | The match to re-attach to. |
| `playerToken` | string | yes | Secret token issued in `MatchCreated`/on join, stored client-side in `sessionStorage`, used to re-associate a new socket with an existing `playerId` within the reconnect grace period (e.g. 120s). |

On success the server replies with a full `MatchStateSync` so the client can rebuild state from scratch.

#### `LeaveMatch`

```json
{ "type": "LeaveMatch", "v": 1, "requestId": "r5", "payload": {} }
```

No payload fields. Removes the sender from their current match (lobby or in-progress); triggers a `LobbyUpdate` (lobby phase) or is treated like a disconnect (in-progress phase).

### Server → Client

#### `MatchCreated`

```json
{ "type": "MatchCreated", "v": 1, "requestId": "r1", "payload": { "matchId": "m-9f2a", "joinCode": "9F2A", "playerToken": "tok-6e1c...", "playerId": "p-1" } }
```

| Field | Type | Notes |
|---|---|---|
| `matchId` | string | Internal/routable match id. |
| `joinCode` | string | Short human-shareable code for `JoinMatch`. |
| `playerToken` | string | Secret, store in `sessionStorage`; used by `Rejoin`. Never broadcast to other players. |
| `playerId` | string | Stable player id for this session, persists across reconnects. |

Sent only to the creator, in reply to `CreateMatch`.

#### `LobbyUpdate`

```json
{
  "type": "LobbyUpdate",
  "v": 1,
  "payload": {
    "matchId": "m-9f2a",
    "players": [
      { "playerId": "p-1", "displayName": "Dan", "ready": true, "isHost": true },
      { "playerId": "p-2", "displayName": "Riley", "ready": false, "isHost": false }
    ],
    "hostId": "p-1"
  }
}
```

Broadcast to everyone in a `WAITING` match whenever roster or readiness changes (join, leave, ready toggle, disconnect).

#### `MatchStarted`

```json
{
  "type": "MatchStarted",
  "v": 1,
  "payload": {
    "matchConfig": { "maxRounds": 4, "maxPlayers": 8 },
    "players": [ { "playerId": "p-1", "displayName": "Dan", "color": "#e33", "cash": 500 } ]
  }
}
```

Broadcast once when the lobby transitions `WAITING` → `IN_PROGRESS` (all players ready). Followed by a `MatchStateSync` carrying the first round's full state.

#### `MatchStateSync`

```json
{
  "type": "MatchStateSync",
  "v": 1,
  "payload": {
    "matchId": "m-9f2a",
    "status": "IN_PROGRESS",
    "roundNumber": 1,
    "maxRounds": 4,
    "terrain": { "heights": [412, 411, 409, "... one int per world column ..."] },
    "players": [
      {
        "playerId": "p-1",
        "displayName": "Dan",
        "color": "#e33",
        "cash": 500,
        "loadout": { "basic_shell": -1, "baby_missile": 5 },
        "activeShieldId": null,
        "tank": { "x": 120, "y": 405, "health": 100, "alive": true }
      }
    ],
    "turnOrder": ["p-1", "p-2"],
    "currentTurnIndex": 0,
    "wind": { "strength": 12, "directionSign": 1 }
  }
}
```

**This is the only message that carries the full world state.** Sent on: initial join into an in-progress match, `Rejoin`, and the start of every round (fresh terrain). All other terrain changes travel as the small `terrainDelta` on `ShotResolved` — see section 4. A `loadout` quantity of `-1` conventionally means "unlimited" (e.g. Basic Shell).

#### `PlayerDisconnected` / `PlayerReconnected`

```json
{ "type": "PlayerDisconnected", "v": 1, "payload": { "playerId": "p-2" } }
```

```json
{ "type": "PlayerReconnected", "v": 1, "payload": { "playerId": "p-2" } }
```

Broadcast to the rest of the match when a player's socket drops or a `Rejoin` succeeds. Does not itself change turn order; if it's the disconnected player's turn, the `TurnManager` handles the timeout/auto-skip separately (see `TurnStarted`, section 4).

---

## 4. Match / turn messages

Cover in-round play: turn order, firing, shot resolution, and round lifecycle (`TurnManager`, sections 2.3/4.1-4.3 of the plan).

### Client → Server

#### `Fire`

```json
{ "type": "Fire", "v": 1, "requestId": "r10", "payload": { "weaponId": "basic_shell", "angleDeg": 42.5, "power": 78 } }
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `weaponId` | string | yes | Id of the weapon (or shield, see section 3.6/4.4 of the plan — shield activation is also sent as `Fire` with a shield id) being used this turn. |
| `angleDeg` | number | yes | Firing angle in degrees. Client input only — never trusted as an outcome; server recomputes everything. |
| `power` | number | yes | Firing power, 0-100 scale (client-defined range; server clamps/validates). |

Only accepted from the player whose turn it currently is (`AWAITING_FIRE` state); otherwise rejected with `FireRejected`.

### Server → Client

#### `TurnStarted`

```json
{ "type": "TurnStarted", "v": 1, "payload": { "playerId": "p-2", "wind": { "strength": -8, "directionSign": -1 }, "turnTimeoutSec": 30 } }
```

Broadcast at the start of every turn. `wind` is rerolled each turn (e.g. strength range -20..20). `turnTimeoutSec` is the server-enforced time before auto-skip.

#### `FireRejected`

```json
{ "type": "FireRejected", "v": 1, "requestId": "r10", "payload": { "reason": "NOT_YOUR_TURN" } }
```

| Field | Type | Notes |
|---|---|---|
| `reason` | string | One of a small fixed set of reason codes, e.g. `NOT_YOUR_TURN`, `INVALID_WEAPON`, `MATCH_NOT_IN_PROGRESS`, `RATE_LIMITED`. |

Sent only to the requester, echoing their `requestId`.

#### `ShotResolved`

```json
{
  "type": "ShotResolved",
  "v": 1,
  "requestId": "r10",
  "payload": {
    "shooterId": "p-2",
    "weaponId": "basic_shell",
    "trajectory": [ { "x": 200, "y": 400 }, { "x": 230, "y": 360 }, "... ~30-40 resampled points ..." ],
    "impact": { "x": 640, "y": 388 },
    "terrainDelta": { "startX": 600, "endX": 680, "heights": [390, 388, "... only the affected column range ..."] },
    "damageEvents": [ { "playerId": "p-1", "damage": 22, "newHealth": 78, "eliminated": false } ],
    "cashEarned": [ { "playerId": "p-2", "amount": 110 } ]
  }
}
```

| Field | Type | Notes |
|---|---|---|
| `shooterId` | string | Player who fired. |
| `weaponId` | string | Weapon used. |
| `trajectory` | array of `{x,y}` | Resampled to ~30-40 points (not every raw simulation step) to keep the message compact. Client interpolates the projectile animation along these points. |
| `impact` | `{x,y}` | Final impact/detonation point. |
| `terrainDelta` | `{startX, endX, heights[]}` | Only the affected column range — the sole mechanism for keeping client terrain in sync outside of `MatchStateSync`. `heights` has `endX - startX + 1` entries. |
| `damageEvents` | array | One entry per tank affected by the blast (including possible self-damage). `eliminated: true` when `newHealth <= 0`. |
| `cashEarned` | array | One entry per player credited cash from this shot (damage dealt to others × 5, elimination bonus, etc. — see plan section 4.5). |

Broadcast to every player in the match — all clients must render an identical result from the same message (this is the core M1 checkpoint: "two tabs fire a shell, see identical terrain deformation and damage on both"). Echoes the firing player's `requestId`; other clients receive it without a matching pending request, which is fine since `requestId` is just correlation metadata.

Weapon-specific behavior (MIRV children, bounces, tunneling) is still expressed through this same message shape — e.g. MIRV children are simply additional points/impacts folded into `trajectory`/`damageEvents` as needed by the weapon's implementation; this document does not mandate a separate per-child sub-message at v1.

#### `RoundEnded`

```json
{ "type": "RoundEnded", "v": 1, "payload": { "winnerPlayerId": "p-2", "standings": [ { "playerId": "p-2", "cash": 780 }, { "playerId": "p-1", "cash": 540 } ] } }
```

| Field | Type | Notes |
|---|---|---|
| `winnerPlayerId` | string \| null | Last tank standing, or `null` on the 60-turn safety-cap draw case (award logic still applies to `standings`/cash even when `null`). |
| `standings` | array | Per-player cash snapshot at round end, for the post-round summary UI. |

Followed by `ShopOpened` (shop phase) and eventually a new `MatchStateSync` for the next round, or `MatchEnded` if `maxRounds` reached.

#### `MatchEnded`

```json
{ "type": "MatchEnded", "v": 1, "payload": { "finalStandings": [ { "playerId": "p-2", "cash": 1240, "damageDealt": 860, "kills": 3 } ] } }
```

`finalStandings` sorted by cash descending (match winner = highest cumulative cash); includes damage/kills for tiebreakers and flavor per plan section 4.5.

---

## 5. Shop messages

Cover the between-round economy phase (`Shop`/`PriceTable`, plan sections 2.3/4.5).

### Client → Server

#### `ShopPurchase`

```json
{ "type": "ShopPurchase", "v": 1, "requestId": "r20", "payload": { "itemId": "heavy_cannonball", "itemType": "WEAPON", "quantity": 2 } }
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `itemId` | string | yes | Weapon or shield id from the price list. |
| `itemType` | string | yes | `"WEAPON"` or `"SHIELD"`. |
| `quantity` | integer | yes | Number of units to buy (shields are typically quantity 1). |

Only accepted while `status == SHOP`; validated server-side against the player's current cash. Insufficient funds or an out-of-phase purchase gets an `ErrorMsg`/rejection rather than silently failing.

### Server → Client

#### `ShopOpened`

```json
{
  "type": "ShopOpened",
  "v": 1,
  "payload": {
    "timeoutSec": 30,
    "priceList": [
      { "itemId": "heavy_cannonball", "itemType": "WEAPON", "price": 150 },
      { "itemId": "absorb_shield", "itemType": "SHIELD", "price": 200 }
    ]
  }
}
```

Broadcast at the start of the shop phase. `timeoutSec` is server-enforced (default 30s); when it elapses the server transitions to the next round's `TURN_START` regardless of purchases made.

#### `ShopUpdate`

```json
{ "type": "ShopUpdate", "v": 1, "requestId": "r20", "payload": { "playerId": "p-1", "cash": 350, "loadout": { "basic_shell": -1, "heavy_cannonball": 2 } } }
```

Broadcast (or sent to the purchaser, at minimum) after a successful `ShopPurchase`, reflecting the player's updated cash and loadout. Echoes the purchaser's `requestId` when sent to them directly.

---

## 6. Error / connection messages

#### `ErrorMsg`

```json
{ "type": "ErrorMsg", "v": 1, "requestId": "r7", "payload": { "code": "MATCH_NOT_FOUND", "message": "No match with that code exists." } }
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `code` | string | yes | Short machine-readable error code (e.g. `MATCH_NOT_FOUND`, `MATCH_FULL`, `INVALID_ENVELOPE`, `UNKNOWN_TYPE`, `NOT_YOUR_TURN`, `INSUFFICIENT_CASH`). |
| `message` | string | yes | Human-readable detail, safe to show in a toast. |

`requestId` is included and echoed whenever the error is responding to a specific client request; omitted for unsolicited server-initiated errors (rare — e.g. malformed frame with no parseable envelope at all).

Note: `FireRejected` (section 4) is a dedicated message type for the specific, high-frequency case of a rejected `Fire`, rather than a generic `ErrorMsg`, so the client can special-case "flash the fire button" without string-matching an error code. All other rejections use `ErrorMsg`.

---

## 7. Versioning conventions

- The envelope's `v` field is the protocol version, currently `1` for every message.
- Treat `v` as applying to the whole envelope/payload-shape convention, not per-message-type. A breaking change to *any* message's payload shape is a candidate for bumping `v`; purely additive optional fields do not require a bump.
- Both client and server should be defensive: unknown/newer `v` values should be handled gracefully (e.g. server logs and sends `ErrorMsg{code: "UNSUPPORTED_VERSION"}`, client shows a "please refresh" style notice) rather than crashing the connection.
- Because there is no codegen, a payload shape change must be reflected in three places in lockstep: this document, `server/src/main/java/com/brutaltank/protocol/`, and `client/src/lib/protocol/`. Treat this file as the PR-review checklist for protocol changes — if a message shape changed and this file didn't, the change is incomplete.
