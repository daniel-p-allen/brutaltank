// Authoritative synced match state (PLAN.md section 3.2 `matchStore`):
// terrain heights, players (incl. tank x/y/health), wind, turn info.
//
// Subscribes to wsClient messages:
//   - MatchStateSync    -> replaces the whole store (full snapshot).
//   - ShotResolved      -> patches terrain.heights via terrainDelta, updates
//                          the affected players' tank health/alive from
//                          damageEvents, and queues a transient shot
//                          animation (see shotAnimationStore.ts).
//   - MatchStarted      -> flips status to IN_PROGRESS (the full snapshot
//                          follows shortly after as MatchStateSync).
//   - TurnStarted       -> records the active player, rerolled wind, and
//                          turn timeout/start time for the HUD countdown.
//   - FireRejected      -> records the rejection reason for a toast/inline
//                          message.
//   - RoundEnded        -> records round-end info; MatchScreen shows this as
//                          a banner until the next MatchStateSync clears it.
//   - MatchEnded        -> records final standings and flips status to
//                          COMPLETE.
//   - PlayerDisconnected/PlayerReconnected -> tracks a small set of
//                          currently-disconnected playerIds for per-player
//                          status badges.
//
// See shared/protocol.md sections 3-4 for the message shapes this store
// must stay in lockstep with.

import { writable } from 'svelte/store';
import { wsClient } from '../net/wsClient';
import { parseEnvelope } from '../protocol/envelope';
import type {
	FireRejectedPayload,
	MatchEndedPayload,
	MatchStateSyncPayload,
	Player,
	RoundEndedPayload,
	ShotResolvedPayload,
	Terrain,
	TurnStartedPayload,
	Wind
} from '../protocol/types';
import { queueShotAnimation } from './shotAnimationStore';

export interface MatchState {
	matchId: string | null;
	status: string | null;
	roundNumber: number | null;
	maxRounds: number | null;
	terrain: Terrain;
	players: Player[];
	turnOrder: string[];
	currentTurnIndex: number;
	wind: Wind | null;
	/** True from the moment a Fire is sent until the matching ShotResolved arrives. */
	awaitingShotResolution: boolean;
	/** Whose turn it currently is, from the most recent TurnStarted. */
	activePlayerId: string | null;
	/** Server-enforced auto-skip timeout for the current turn, in seconds. */
	turnTimeoutSec: number | null;
	/** Client-side timestamp (Date.now()) the current turn started, for a countdown. */
	turnStartedAtMs: number | null;
	/** Reason code from the most recent FireRejected, if any (cleared on the next TurnStarted). */
	fireRejectedReason: string | null;
	/** playerIds currently reported disconnected (PlayerDisconnected without a matching PlayerReconnected yet). */
	disconnectedPlayerIds: string[];
	/** Set on RoundEnded, cleared on the next MatchStateSync — drives the round-end banner. */
	roundEndedInfo: RoundEndedPayload | null;
	/** Set on MatchEnded — drives the final-standings screen. */
	matchEndedInfo: MatchEndedPayload | null;
}

function initialState(): MatchState {
	return {
		matchId: null,
		status: null,
		roundNumber: null,
		maxRounds: null,
		terrain: { heights: [] },
		players: [],
		turnOrder: [],
		currentTurnIndex: 0,
		wind: null,
		awaitingShotResolution: false,
		activePlayerId: null,
		turnTimeoutSec: null,
		turnStartedAtMs: null,
		fireRejectedReason: null,
		disconnectedPlayerIds: [],
		roundEndedInfo: null,
		matchEndedInfo: null
	};
}

/** Pure helper (exported for unit testing): apply a MatchStateSync payload as a full replace. */
export function applyMatchStateSync(payload: MatchStateSyncPayload): MatchState {
	return {
		matchId: payload.matchId,
		status: payload.status,
		roundNumber: payload.roundNumber,
		maxRounds: payload.maxRounds,
		terrain: { heights: [...payload.terrain.heights] },
		players: payload.players.map((p) => ({ ...p, tank: { ...p.tank } })),
		turnOrder: [...payload.turnOrder],
		currentTurnIndex: payload.currentTurnIndex,
		wind: { ...payload.wind },
		awaitingShotResolution: false,
		activePlayerId: payload.turnOrder[payload.currentTurnIndex] ?? null,
		turnTimeoutSec: null,
		turnStartedAtMs: null,
		fireRejectedReason: null,
		// A full snapshot doesn't carry live connection status; disconnected
		// badges rebuild from subsequent PlayerDisconnected events.
		disconnectedPlayerIds: [],
		roundEndedInfo: null,
		matchEndedInfo: null
	};
}

/** Pure helper (exported for unit testing): patch terrain + player health from a ShotResolved payload. */
export function applyShotResolved(state: MatchState, payload: ShotResolvedPayload): MatchState {
	const heights = [...state.terrain.heights];
	const { startX, endX, heights: deltaHeights } = payload.terrainDelta;
	// Splice in the affected column range. Guard against an out-of-range delta
	// (e.g. before the initial MatchStateSync has populated terrain) rather
	// than throwing.
	if (startX >= 0 && endX >= startX && heights.length >= endX + 1) {
		heights.splice(startX, endX - startX + 1, ...deltaHeights);
	}

	const healthByPlayer = new Map(payload.damageEvents.map((e) => [e.playerId, e]));
	const players = state.players.map((p) => {
		const dmg = healthByPlayer.get(p.playerId);
		if (!dmg) return p;
		return {
			...p,
			tank: { ...p.tank, health: dmg.newHealth, alive: !dmg.eliminated }
		};
	});

	return {
		...state,
		terrain: { heights },
		players,
		awaitingShotResolution: false
	};
}

/** Pure helper (exported for unit testing): records the active player/wind/timeout for a new turn. */
export function applyTurnStarted(state: MatchState, payload: TurnStartedPayload): MatchState {
	return {
		...state,
		activePlayerId: payload.playerId,
		wind: { ...payload.wind },
		turnTimeoutSec: payload.turnTimeoutSec,
		turnStartedAtMs: Date.now(),
		fireRejectedReason: null,
		awaitingShotResolution: false
	};
}

/** Pure helper (exported for unit testing). */
export function applyFireRejected(state: MatchState, payload: FireRejectedPayload): MatchState {
	return { ...state, fireRejectedReason: payload.reason, awaitingShotResolution: false };
}

/** Pure helper (exported for unit testing). */
export function applyRoundEnded(state: MatchState, payload: RoundEndedPayload): MatchState {
	return { ...state, roundEndedInfo: payload };
}

/** Pure helper (exported for unit testing). */
export function applyMatchEnded(state: MatchState, payload: MatchEndedPayload): MatchState {
	return { ...state, matchEndedInfo: payload, status: 'COMPLETE' };
}

/** Pure helper (exported for unit testing). */
export function applyPlayerDisconnected(state: MatchState, playerId: string): MatchState {
	if (state.disconnectedPlayerIds.includes(playerId)) return state;
	return { ...state, disconnectedPlayerIds: [...state.disconnectedPlayerIds, playerId] };
}

/** Pure helper (exported for unit testing). */
export function applyPlayerReconnected(state: MatchState, playerId: string): MatchState {
	if (!state.disconnectedPlayerIds.includes(playerId)) return state;
	return {
		...state,
		disconnectedPlayerIds: state.disconnectedPlayerIds.filter((id) => id !== playerId)
	};
}

function createMatchStore() {
	const { subscribe, set, update } = writable<MatchState>(initialState());

	wsClient.onMessage((data) => {
		const envelope = parseEnvelope(data);
		if (!envelope) return;

		switch (envelope.type) {
			case 'MatchStateSync':
				set(applyMatchStateSync(envelope.payload as MatchStateSyncPayload));
				return;

			case 'ShotResolved': {
				const payload = envelope.payload as ShotResolvedPayload;
				update((state) => applyShotResolved(state, payload));
				queueShotAnimation({
					shooterId: payload.shooterId,
					weaponId: payload.weaponId,
					trajectory: payload.trajectory,
					impact: payload.impact
				});
				return;
			}

			case 'MatchStarted':
				update((state) => ({ ...state, status: 'IN_PROGRESS' }));
				return;

			case 'TurnStarted':
				update((state) => applyTurnStarted(state, envelope.payload as TurnStartedPayload));
				return;

			case 'FireRejected':
				update((state) => applyFireRejected(state, envelope.payload as FireRejectedPayload));
				return;

			case 'RoundEnded':
				update((state) => applyRoundEnded(state, envelope.payload as RoundEndedPayload));
				return;

			case 'MatchEnded':
				update((state) => applyMatchEnded(state, envelope.payload as MatchEndedPayload));
				return;

			case 'PlayerDisconnected':
				update((state) =>
					applyPlayerDisconnected(state, (envelope.payload as { playerId: string }).playerId)
				);
				return;

			case 'PlayerReconnected':
				update((state) =>
					applyPlayerReconnected(state, (envelope.payload as { playerId: string }).playerId)
				);
				return;
		}
	});

	/** Marks a shot as in-flight (called right after a Fire envelope is sent). */
	function markFireSent(): void {
		update((state) => ({ ...state, awaitingShotResolution: true, fireRejectedReason: null }));
	}

	/** Resets to the pristine initial state (e.g. returning to the menu after a match ends). */
	function reset(): void {
		set(initialState());
	}

	return { subscribe, markFireSent, reset };
}

export const matchStore = createMatchStore();
