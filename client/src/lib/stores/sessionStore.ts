// Tracks the local player's session identity (PLAN.md section 2.1 reconnect
// flow): playerId, playerToken, matchId. Persisted to sessionStorage so a
// page reload can Rejoin instead of starting over at the menu.
//
// Populated from the server's MatchCreated (reply to CreateMatch) and
// MatchJoined (reply to JoinMatch) messages. On app startup, if
// sessionStorage already has a stored matchId+playerToken, this store
// attempts a Rejoin automatically as soon as the socket reports 'open' —
// once per socket-open event, so a later real reconnect (after actually
// being in a match) retries too. A failed/rejected rejoin (ErrorMsg, or no
// matching match) is not specially handled: since nothing populates
// matchStore/lobbyStore in that case, App.svelte's routing simply falls
// through to the normal menu screen.

import { writable } from 'svelte/store';
import { wsClient } from '../net/wsClient';
import { parseEnvelope } from '../protocol/envelope';
import { sendRejoin } from '../net/lobbyActions';
import type { MatchCreatedPayload, MatchJoinedPayload } from '../protocol/types';

export interface SessionState {
	playerId: string | null;
	playerToken: string | null;
	matchId: string | null;
}

const STORAGE_KEY = 'brutaltank.session';

function initialState(): SessionState {
	return { playerId: null, playerToken: null, matchId: null };
}

/** Pure helper (exported for unit testing): read persisted session from sessionStorage, if any. */
export function loadFromStorage(): SessionState | null {
	try {
		const raw = sessionStorage.getItem(STORAGE_KEY);
		if (!raw) return null;
		const parsed = JSON.parse(raw);
		if (
			typeof parsed === 'object' &&
			parsed !== null &&
			typeof parsed.playerId === 'string' &&
			typeof parsed.playerToken === 'string' &&
			typeof parsed.matchId === 'string'
		) {
			return { playerId: parsed.playerId, playerToken: parsed.playerToken, matchId: parsed.matchId };
		}
	} catch {
		// Corrupt/unavailable storage: treat as no stored session.
	}
	return null;
}

function saveToStorage(state: SessionState): void {
	try {
		if (state.playerId && state.playerToken && state.matchId) {
			sessionStorage.setItem(STORAGE_KEY, JSON.stringify(state));
		} else {
			sessionStorage.removeItem(STORAGE_KEY);
		}
	} catch {
		// sessionStorage unavailable (e.g. private-mode edge cases) — non-fatal.
	}
}

function clearStorage(): void {
	try {
		sessionStorage.removeItem(STORAGE_KEY);
	} catch {
		// non-fatal
	}
}

function createSessionStore() {
	const { subscribe, set, update } = writable<SessionState>(initialState());

	let rejoinAttemptedForThisSocket = false;

	wsClient.onStateChange((state) => {
		if (state !== 'open') {
			// A fresh socket (initial connect, or after a drop) gets its own
			// rejoin attempt once it comes back up.
			rejoinAttemptedForThisSocket = false;
			return;
		}
		if (rejoinAttemptedForThisSocket) return;
		rejoinAttemptedForThisSocket = true;

		const stored = loadFromStorage();
		if (stored) {
			set(stored);
			sendRejoin(stored.matchId as string, stored.playerToken as string);
		}
	});

	wsClient.onMessage((data) => {
		const envelope = parseEnvelope(data);
		if (!envelope) return;

		if (envelope.type === 'MatchCreated') {
			const payload = envelope.payload as MatchCreatedPayload;
			const next: SessionState = {
				playerId: payload.playerId,
				playerToken: payload.playerToken,
				matchId: payload.matchId
			};
			set(next);
			saveToStorage(next);
			return;
		}

		if (envelope.type === 'MatchJoined') {
			const payload = envelope.payload as MatchJoinedPayload;
			const next: SessionState = {
				playerId: payload.playerId,
				playerToken: payload.playerToken,
				matchId: payload.matchId
			};
			set(next);
			saveToStorage(next);
			return;
		}
	});

	/** Clears local + persisted session (e.g. on LeaveMatch, or a match-end return to menu). */
	function clear(): void {
		set(initialState());
		clearStorage();
	}

	return { subscribe, clear, update };
}

export const sessionStore = createSessionStore();
