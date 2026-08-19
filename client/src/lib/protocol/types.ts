// TypeScript interfaces mirroring shared/protocol.md, hand-maintained per the
// protocol doc's own convention (no codegen at v1). Only the M1 subset is
// implemented here: the envelope, MatchStateSync, Fire, and ShotResolved.
// See shared/protocol.md sections 1, 3 ("MatchStateSync"), and 4 ("Fire",
// "ShotResolved") for the canonical shapes this file must stay in lockstep
// with.

/** Generic envelope wrapping every message, both directions (protocol.md section 1). */
export interface Envelope<TPayload = unknown> {
	type: string;
	v: number;
	requestId?: string;
	payload: TPayload;
}

// ---------------------------------------------------------------------------
// Shared value types (protocol.md section 3/4 examples)
// ---------------------------------------------------------------------------

export interface Terrain {
	/** One entry per world column, e.g. width 1600 units. */
	heights: number[];
}

export interface Wind {
	strength: number;
	directionSign: number;
}

export interface Tank {
	x: number;
	y: number;
	health: number;
	alive: boolean;
}

export interface Player {
	playerId: string;
	displayName: string;
	color: string;
	cash: number;
	/** weaponId -> quantity. -1 conventionally means "unlimited". */
	loadout: Record<string, number>;
	activeShieldId: string | null;
	tank: Tank;
}

export interface Point {
	x: number;
	y: number;
}

// ---------------------------------------------------------------------------
// MatchStateSync (server -> client) — the only full-world-state message.
// ---------------------------------------------------------------------------

export interface MatchStateSyncPayload {
	matchId: string;
	status: string;
	roundNumber: number;
	maxRounds: number;
	terrain: Terrain;
	players: Player[];
	turnOrder: string[];
	currentTurnIndex: number;
	wind: Wind;
}

// ---------------------------------------------------------------------------
// Fire (client -> server)
// ---------------------------------------------------------------------------

export interface FirePayload {
	weaponId: string;
	angleDeg: number;
	power: number;
}

// ---------------------------------------------------------------------------
// ShotResolved (server -> client)
// ---------------------------------------------------------------------------

export interface TerrainDelta {
	startX: number;
	endX: number;
	/** heights.length === endX - startX + 1 */
	heights: number[];
}

export interface DamageEvent {
	playerId: string;
	damage: number;
	newHealth: number;
	eliminated: boolean;
}

export interface CashEarned {
	playerId: string;
	amount: number;
}

export interface ShotResolvedPayload {
	shooterId: string;
	weaponId: string;
	trajectory: Point[];
	impact: Point;
	terrainDelta: TerrainDelta;
	damageEvents: DamageEvent[];
	cashEarned: CashEarned[];
}

export type MatchStateSyncEnvelope = Envelope<MatchStateSyncPayload>;
export type FireEnvelope = Envelope<FirePayload>;
export type ShotResolvedEnvelope = Envelope<ShotResolvedPayload>;
