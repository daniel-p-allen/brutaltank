// Authoritative synced match state (PLAN.md section 3.2 `matchStore`):
// terrain heights, players (incl. tank x/y/health), wind, turn info.
//
// Subscribes to wsClient messages:
//   - MatchStateSync -> replaces the whole store (full snapshot).
//   - ShotResolved    -> patches terrain.heights via terrainDelta, updates
//                        the affected players' tank health/alive from
//                        damageEvents, and queues a transient shot animation
//                        (see shotAnimationStore.ts) for the renderer.
//
// M1 has no lobby/turn enforcement, so this store only cares about the
// subset of MatchStateSync/ShotResolved fields relevant to rendering a
// single hardcoded match.

import { writable } from 'svelte/store';
import { wsClient } from '../net/wsClient';
import { parseEnvelope } from '../protocol/envelope';
import type {
	MatchStateSyncPayload,
	Player,
	ShotResolvedPayload,
	Terrain,
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
		awaitingShotResolution: false
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
		awaitingShotResolution: false
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

function createMatchStore() {
	const { subscribe, set, update } = writable<MatchState>(initialState());

	wsClient.onMessage((data) => {
		const envelope = parseEnvelope(data);
		if (!envelope) return;

		if (envelope.type === 'MatchStateSync') {
			set(applyMatchStateSync(envelope.payload as MatchStateSyncPayload));
			return;
		}

		if (envelope.type === 'ShotResolved') {
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
	});

	/** Marks a shot as in-flight (called right after a Fire envelope is sent). */
	function markFireSent(): void {
		update((state) => ({ ...state, awaitingShotResolution: true }));
	}

	return { subscribe, markFireSent };
}

export const matchStore = createMatchStore();
