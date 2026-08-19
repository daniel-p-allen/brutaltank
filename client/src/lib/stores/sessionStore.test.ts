import { describe, it, expect, beforeEach, vi } from 'vitest';
import { get } from 'svelte/store';
import { MockWebSocket } from '../net/mockWebSocket.test-util';
import type { MatchCreatedPayload, MatchJoinedPayload } from '../protocol/types';

const STORAGE_KEY = 'brutaltank.session';

describe('sessionStore', () => {
	let sessionStore: typeof import('./sessionStore').sessionStore;
	let loadFromStorage: typeof import('./sessionStore').loadFromStorage;

	beforeEach(async () => {
		sessionStorage.clear();
		MockWebSocket.reset();
		vi.stubGlobal('WebSocket', MockWebSocket as unknown as typeof WebSocket);
		vi.resetModules();
		({ sessionStore, loadFromStorage } = await import('./sessionStore'));
	});

	it('starts empty when sessionStorage has no stored session', () => {
		const state = get(sessionStore);
		expect(state.playerId).toBeNull();
		expect(state.playerToken).toBeNull();
		expect(state.matchId).toBeNull();
	});

	it('persists playerId/playerToken/matchId to sessionStorage on MatchCreated', async () => {
		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();

		const payload: MatchCreatedPayload = {
			matchId: 'm-9f2a',
			joinCode: '9F2A',
			playerToken: 'tok-6e1c',
			playerId: 'p-1'
		};
		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'MatchCreated', v: 1, requestId: 'r1', payload })
		);

		const state = get(sessionStore);
		expect(state).toEqual({ playerId: 'p-1', playerToken: 'tok-6e1c', matchId: 'm-9f2a' });

		const stored = JSON.parse(sessionStorage.getItem(STORAGE_KEY)!);
		expect(stored).toEqual({ playerId: 'p-1', playerToken: 'tok-6e1c', matchId: 'm-9f2a' });
	});

	it('persists session on MatchJoined', async () => {
		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();

		const payload: MatchJoinedPayload = {
			matchId: 'm-9f2a',
			playerToken: 'tok-7a2f',
			playerId: 'p-2'
		};
		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'MatchJoined', v: 1, requestId: 'r2', payload })
		);

		const state = get(sessionStore);
		expect(state).toEqual({ playerId: 'p-2', playerToken: 'tok-7a2f', matchId: 'm-9f2a' });
	});

	it('restores from sessionStorage and sends Rejoin once the socket opens', async () => {
		sessionStorage.setItem(
			STORAGE_KEY,
			JSON.stringify({ playerId: 'p-1', playerToken: 'tok-abc', matchId: 'm-old' })
		);

		// loadFromStorage is a pure helper — sanity check it reads back what we wrote.
		expect(loadFromStorage()).toEqual({
			playerId: 'p-1',
			playerToken: 'tok-abc',
			matchId: 'm-old'
		});

		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();

		expect(get(sessionStore)).toEqual({
			playerId: 'p-1',
			playerToken: 'tok-abc',
			matchId: 'm-old'
		});

		const sent = MockWebSocket.latest().sent.map((s) => JSON.parse(s));
		const rejoinMsgs = sent.filter((m) => m.type === 'Rejoin');
		expect(rejoinMsgs).toHaveLength(1);
		expect(rejoinMsgs[0].payload).toEqual({ matchId: 'm-old', playerToken: 'tok-abc' });
	});

	it('does not send Rejoin when there is no stored session', async () => {
		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();

		const sent = MockWebSocket.latest().sent.map((s) => JSON.parse(s));
		expect(sent.filter((m) => m.type === 'Rejoin')).toHaveLength(0);
	});

	it('clear() resets the store and removes the persisted session', async () => {
		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();

		const payload: MatchCreatedPayload = {
			matchId: 'm-1',
			joinCode: 'ABCD',
			playerToken: 'tok-1',
			playerId: 'p-1'
		};
		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'MatchCreated', v: 1, payload })
		);
		expect(sessionStorage.getItem(STORAGE_KEY)).not.toBeNull();

		sessionStore.clear();

		expect(get(sessionStore)).toEqual({ playerId: null, playerToken: null, matchId: null });
		expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
	});
});
