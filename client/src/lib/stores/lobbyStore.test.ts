import { describe, it, expect, beforeEach, vi } from 'vitest';
import { get } from 'svelte/store';
import { MockWebSocket } from '../net/mockWebSocket.test-util';
import type { LobbyUpdatePayload } from '../protocol/types';

describe('lobbyStore', () => {
	let lobbyStore: typeof import('./lobbyStore').lobbyStore;
	let applyLobbyUpdate: typeof import('./lobbyStore').applyLobbyUpdate;

	beforeEach(async () => {
		MockWebSocket.reset();
		vi.stubGlobal('WebSocket', MockWebSocket as unknown as typeof WebSocket);
		vi.resetModules();
		({ lobbyStore, applyLobbyUpdate } = await import('./lobbyStore'));
	});

	it('starts with an empty roster', () => {
		const state = get(lobbyStore);
		expect(state.players).toEqual([]);
		expect(state.hostId).toBeNull();
	});

	it('replaces the roster on LobbyUpdate', async () => {
		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();

		const payload: LobbyUpdatePayload = {
			matchId: 'm-9f2a',
			players: [
				{ playerId: 'p-1', displayName: 'Dan', ready: true, isHost: true },
				{ playerId: 'p-2', displayName: 'Riley', ready: false, isHost: false }
			],
			hostId: 'p-1'
		};
		MockWebSocket.latest().emitMessage(JSON.stringify({ type: 'LobbyUpdate', v: 1, payload }));

		const state = get(lobbyStore);
		expect(state.matchId).toBe('m-9f2a');
		expect(state.hostId).toBe('p-1');
		expect(state.players).toHaveLength(2);
		expect(state.players[1]).toEqual({
			playerId: 'p-2',
			displayName: 'Riley',
			ready: false,
			isHost: false
		});
	});

	it('applyLobbyUpdate is a pure full-replace helper', () => {
		const state = applyLobbyUpdate({
			matchId: 'm-1',
			players: [{ playerId: 'p-1', displayName: 'Dan', ready: false, isHost: true }],
			hostId: 'p-1'
		});
		expect(state.players).toHaveLength(1);
	});

	it('reset() clears the roster back to empty', async () => {
		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();
		MockWebSocket.latest().emitMessage(
			JSON.stringify({
				type: 'LobbyUpdate',
				v: 1,
				payload: {
					matchId: 'm-1',
					players: [{ playerId: 'p-1', displayName: 'Dan', ready: false, isHost: true }],
					hostId: 'p-1'
				}
			})
		);
		expect(get(lobbyStore).players).toHaveLength(1);

		lobbyStore.reset();
		expect(get(lobbyStore).players).toEqual([]);
	});
});
