// TypeScript interfaces mirroring shared/protocol.md, hand-maintained per the
// protocol doc's own convention (no codegen at v1). M1 covered the envelope,
// MatchStateSync, Fire, and ShotResolved. M2 adds the lobby (section 3) and
// turn/round-lifecycle (section 4) message shapes: CreateMatch, JoinMatch,
// MatchCreated, MatchJoined, SetReady, LobbyUpdate, MatchStarted, Rejoin,
// LeaveMatch, PlayerDisconnected/PlayerReconnected, TurnStarted,
// FireRejected, RoundEnded, MatchEnded. Shop messages (section 5) are
// explicitly deferred to M4 per protocol.md's M2 note and are not modeled
// here yet.

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
	/**
	 * Whether Trajectory Help was toggled on for this shot (per user
	 * decision, 2026-08-23: firing without it earns a risk/reward bonus —
	 * see shared/protocol.md's Fire section). Always false for Nuke since
	 * Trajectory Help is permanently unavailable there.
	 */
	trajectoryHelpUsed: boolean;
}

// ---------------------------------------------------------------------------
// AimUpdate / PlayerAiming (cosmetic-only live aim broadcast, not turn-gated)
// ---------------------------------------------------------------------------

export interface AimUpdatePayload {
	angleDeg: number;
}

export interface PlayerAimingPayload {
	playerId: string;
	angleDeg: number;
}

export type AimUpdateEnvelope = Envelope<AimUpdatePayload>;
export type PlayerAimingEnvelope = Envelope<PlayerAimingPayload>;

// ---------------------------------------------------------------------------
// TrajectoryHelpUpdate / PlayerTrajectoryHelp (live per-player on/off status
// broadcast, not turn-gated — mirrors AimUpdate/PlayerAiming)
// ---------------------------------------------------------------------------

export interface TrajectoryHelpUpdatePayload {
	enabled: boolean;
}

export interface PlayerTrajectoryHelpPayload {
	playerId: string;
	enabled: boolean;
}

export type TrajectoryHelpUpdateEnvelope = Envelope<TrajectoryHelpUpdatePayload>;
export type PlayerTrajectoryHelpEnvelope = Envelope<PlayerTrajectoryHelpPayload>;

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
	/** The target's activeShieldId immediately after this hit resolved (null if none/broken). */
	activeShieldId: string | null;
}

export interface CashEarned {
	playerId: string;
	amount: number;
}

/** A tank whose ground gave way this shot and dropped to the new terrain level (position-only; any fall damage is in damageEvents). */
export interface TankFall {
	playerId: string;
	newY: number;
}

export interface ShotResolvedPayload {
	shooterId: string;
	weaponId: string;
	trajectory: Point[];
	impact: Point;
	terrainDelta: TerrainDelta;
	damageEvents: DamageEvent[];
	cashEarned: CashEarned[];
	tankFalls: TankFall[];
	/** The shooter's remaining quantity of weaponId after this shot (-1 == unlimited). */
	ammoRemaining: number;
	/**
	 * Every real (damage-capable) detonation point this shot produced, in
	 * order — for a single-impact weapon this is just [impact]; for
	 * MIRV/Cluster Bomb it's every child's/bomblet's landing point. Cosmetic
	 * zero-damage marks (tunnel bore track, bounce skip marks) are excluded.
	 */
	allImpacts: Point[];
}

export type MatchStateSyncEnvelope = Envelope<MatchStateSyncPayload>;
export type FireEnvelope = Envelope<FirePayload>;
export type ShotResolvedEnvelope = Envelope<ShotResolvedPayload>;

// ---------------------------------------------------------------------------
// Lobby messages (protocol.md section 3)
// ---------------------------------------------------------------------------

export interface MatchConfig {
	maxRounds: number;
	maxPlayers: number;
}

export interface CreateMatchPayload {
	displayName: string;
	matchConfig?: Partial<MatchConfig> | null;
}

export interface JoinMatchPayload {
	matchId: string;
	displayName: string;
}

export interface SetReadyPayload {
	ready: boolean;
}

export interface RejoinPayload {
	matchId: string;
	playerToken: string;
}

/** LeaveMatch has no payload fields (protocol.md section 3). */
export type LeaveMatchPayload = Record<string, never>;

/** PlayAgain has no payload fields (protocol.md section 3). */
export type PlayAgainPayload = Record<string, never>;

export interface MatchCreatedPayload {
	matchId: string;
	joinCode: string;
	playerToken: string;
	playerId: string;
}

export interface MatchJoinedPayload {
	matchId: string;
	playerToken: string;
	playerId: string;
}

export interface LobbyPlayer {
	playerId: string;
	displayName: string;
	ready: boolean;
	isHost: boolean;
}

export interface LobbyUpdatePayload {
	matchId: string;
	players: LobbyPlayer[];
	hostId: string;
}

export interface MatchStartedPlayer {
	playerId: string;
	displayName: string;
	color: string;
	cash: number;
}

export interface MatchStartedPayload {
	matchConfig: MatchConfig;
	players: MatchStartedPlayer[];
}

export interface PlayerDisconnectedPayload {
	playerId: string;
}

export interface PlayerReconnectedPayload {
	playerId: string;
}

export type CreateMatchEnvelope = Envelope<CreateMatchPayload>;
export type JoinMatchEnvelope = Envelope<JoinMatchPayload>;
export type SetReadyEnvelope = Envelope<SetReadyPayload>;
export type RejoinEnvelope = Envelope<RejoinPayload>;
export type LeaveMatchEnvelope = Envelope<LeaveMatchPayload>;
export type PlayAgainEnvelope = Envelope<PlayAgainPayload>;
export type MatchCreatedEnvelope = Envelope<MatchCreatedPayload>;
export type MatchJoinedEnvelope = Envelope<MatchJoinedPayload>;
export type LobbyUpdateEnvelope = Envelope<LobbyUpdatePayload>;
export type MatchStartedEnvelope = Envelope<MatchStartedPayload>;

// ---------------------------------------------------------------------------
// Match/turn lifecycle messages (protocol.md section 4)
// ---------------------------------------------------------------------------

export interface TurnStartedPayload {
	playerId: string;
	wind: Wind;
	turnTimeoutSec: number;
}

export interface TurnForfeitedPayload {
	playerId: string;
	penalty: number;
	newCash: number;
	eliminated: boolean;
}

/** One of a small fixed set of reason codes, e.g. NOT_YOUR_TURN, INVALID_WEAPON, MATCH_NOT_IN_PROGRESS, RATE_LIMITED. */
export interface FireRejectedPayload {
	reason: string;
}

export interface RoundEndedStanding {
	playerId: string;
	cash: number;
}

export interface RoundEndedPayload {
	winnerPlayerId: string | null;
	standings: RoundEndedStanding[];
}

export interface MatchEndedStanding {
	playerId: string;
	cash: number;
	damageDealt: number;
	kills: number;
}

export interface MatchEndedPayload {
	finalStandings: MatchEndedStanding[];
}

export type TurnStartedEnvelope = Envelope<TurnStartedPayload>;
export type FireRejectedEnvelope = Envelope<FireRejectedPayload>;
export type RoundEndedEnvelope = Envelope<RoundEndedPayload>;
export type MatchEndedEnvelope = Envelope<MatchEndedPayload>;
export type PlayerDisconnectedEnvelope = Envelope<PlayerDisconnectedPayload>;
export type PlayerReconnectedEnvelope = Envelope<PlayerReconnectedPayload>;

// ---------------------------------------------------------------------------
// Shop messages (protocol.md section 5, M4)
// ---------------------------------------------------------------------------

export interface ShopPurchasePayload {
	itemId: string;
	itemType: 'WEAPON' | 'SHIELD';
	quantity: number;
}

/**
 * One priced item in the shop's price list. `stock` (M4 addition beyond the
 * item's static price) is how many units are left to buy across the whole
 * match for this shop phase, not per-player — see ShopUpdatePayload's
 * `stockRemaining` for how it updates live as anyone buys.
 */
export interface PriceListEntry {
	itemId: string;
	itemType: 'WEAPON' | 'SHIELD';
	price: number;
	stock: number;
}

export interface ShopOpenedPayload {
	timeoutSec: number;
	priceList: PriceListEntry[];
}

export interface ShopUpdatePayload {
	playerId: string;
	cash: number;
	loadout: Record<string, number>;
	/** The full shared stock pool after this purchase, keyed by itemId — broadcast to everyone, not just the buyer. */
	stockRemaining: Record<string, number>;
}

export type ShopPurchaseEnvelope = Envelope<ShopPurchasePayload>;
export type ShopOpenedEnvelope = Envelope<ShopOpenedPayload>;
export type ShopUpdateEnvelope = Envelope<ShopUpdatePayload>;

// ---------------------------------------------------------------------------
// Error / connection messages (protocol.md section 6)
// ---------------------------------------------------------------------------

export interface ErrorMsgPayload {
	code: string;
	message: string;
}

export type ErrorMsgEnvelope = Envelope<ErrorMsgPayload>;
