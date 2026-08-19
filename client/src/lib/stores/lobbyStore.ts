// Lobby roster state (PLAN.md section 3.1 LobbyScreen / PlayerReadyList),
// populated from the server's LobbyUpdate broadcast (protocol.md section 3).
// Kept separate from matchStore since it's phase-specific (WAITING only) and
// has a different shape (no tank/terrain data, just roster + ready state).

import { writable } from 'svelte/store';
import { wsClient } from '../net/wsClient';
import { parseEnvelope } from '../protocol/envelope';
import type { LobbyPlayer, LobbyUpdatePayload } from '../protocol/types';

export interface LobbyState {
	matchId: string | null;
	players: LobbyPlayer[];
	hostId: string | null;
}

function initialState(): LobbyState {
	return { matchId: null, players: [], hostId: null };
}

/** Pure helper (exported for unit testing): apply a LobbyUpdate payload as a full roster replace. */
export function applyLobbyUpdate(payload: LobbyUpdatePayload): LobbyState {
	return {
		matchId: payload.matchId,
		players: payload.players.map((p) => ({ ...p })),
		hostId: payload.hostId
	};
}

function createLobbyStore() {
	const { subscribe, set } = writable<LobbyState>(initialState());

	wsClient.onMessage((data) => {
		const envelope = parseEnvelope(data);
		if (!envelope) return;

		if (envelope.type === 'LobbyUpdate') {
			set(applyLobbyUpdate(envelope.payload as LobbyUpdatePayload));
			return;
		}
	});

	function reset(): void {
		set(initialState());
	}

	return { subscribe, reset };
}

export const lobbyStore = createLobbyStore();
