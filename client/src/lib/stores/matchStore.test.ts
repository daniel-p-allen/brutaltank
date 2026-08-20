import { describe, it, expect, beforeEach, vi } from 'vitest';
import { MockWebSocket } from '../net/mockWebSocket.test-util';
import type { MatchStateSyncPayload, ShotResolvedPayload } from '../protocol/types';

const samplePayload: MatchStateSyncPayload = {
	matchId: 'm-1',
	status: 'IN_PROGRESS',
	roundNumber: 1,
	maxRounds: 4,
	terrain: { heights: [100, 101, 102, 103, 104, 105, 106, 107, 108, 109] },
	players: [
		{
			playerId: 'p-1',
			displayName: 'Dan',
			color: '#e33',
			cash: 500,
			loadout: { basic_shell: -1 },
			activeShieldId: null,
			tank: { x: 1, y: 101, health: 100, alive: true }
		},
		{
			playerId: 'p-2',
			displayName: 'Riley',
			color: '#3e3',
			cash: 500,
			loadout: { basic_shell: -1 },
			activeShieldId: null,
			tank: { x: 8, y: 108, health: 100, alive: true }
		}
	],
	turnOrder: ['p-1', 'p-2'],
	currentTurnIndex: 0,
	wind: { strength: 5, directionSign: 1 }
};

describe('matchStore', () => {
	let matchStore: typeof import('./matchStore').matchStore;
	let applyMatchStateSync: typeof import('./matchStore').applyMatchStateSync;
	let applyShotResolved: typeof import('./matchStore').applyShotResolved;
	let get: typeof import('svelte/store').get;

	beforeEach(async () => {
		MockWebSocket.reset();
		vi.stubGlobal('WebSocket', MockWebSocket as unknown as typeof WebSocket);
		vi.resetModules();
		({ get } = await import('svelte/store'));
		({ matchStore, applyMatchStateSync, applyShotResolved } = await import('./matchStore'));
	});

	it('starts empty', () => {
		const state = get(matchStore);
		expect(state.matchId).toBeNull();
		expect(state.terrain.heights).toEqual([]);
		expect(state.players).toEqual([]);
	});

	it('replaces state entirely on MatchStateSync', async () => {
		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();
		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'MatchStateSync', v: 1, payload: samplePayload })
		);

		const state = get(matchStore);
		expect(state.matchId).toBe('m-1');
		expect(state.terrain.heights).toEqual(samplePayload.terrain.heights);
		expect(state.players).toHaveLength(2);
		expect(state.players[0].tank.health).toBe(100);
		expect(state.wind).toEqual({ strength: 5, directionSign: 1 });
	});

	it('patches terrain heights via terrainDelta and updates player health on ShotResolved', async () => {
		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();
		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'MatchStateSync', v: 1, payload: samplePayload })
		);

		const shotPayload: ShotResolvedPayload = {
			shooterId: 'p-1',
			weaponId: 'basic_shell',
			trajectory: [
				{ x: 1, y: 101 },
				{ x: 5, y: 90 },
				{ x: 8, y: 108 }
			],
			impact: { x: 8, y: 108 },
			terrainDelta: { startX: 6, endX: 9, heights: [200, 201, 202, 203] },
			damageEvents: [{ playerId: 'p-2', damage: 22, newHealth: 78, eliminated: false }],
			cashEarned: [{ playerId: 'p-1', amount: 110 }],
			tankFalls: []
		};

		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'ShotResolved', v: 1, requestId: 'r10', payload: shotPayload })
		);

		const state = get(matchStore);
		// Original heights: [100,101,102,103,104,105,106,107,108,109]
		// startX=6..endX=9 replaced with [200,201,202,203]
		expect(state.terrain.heights).toEqual([100, 101, 102, 103, 104, 105, 200, 201, 202, 203]);

		const p2 = state.players.find((p) => p.playerId === 'p-2')!;
		expect(p2.tank.health).toBe(78);
		expect(p2.tank.alive).toBe(true);

		const p1 = state.players.find((p) => p.playerId === 'p-1')!;
		expect(p1.tank.health).toBe(100); // untouched, no damageEvent for p-1
	});

	it('marks eliminated when newHealth <= 0 / eliminated true', () => {
		const state = applyShotResolved(applyMatchStateSync(samplePayload), {
			shooterId: 'p-1',
			weaponId: 'basic_shell',
			trajectory: [],
			impact: { x: 8, y: 108 },
			terrainDelta: { startX: 0, endX: 0, heights: [99] },
			damageEvents: [{ playerId: 'p-2', damage: 100, newHealth: 0, eliminated: true }],
			cashEarned: [],
			tankFalls: []
		});

		const p2 = state.players.find((p) => p.playerId === 'p-2')!;
		expect(p2.tank.health).toBe(0);
		expect(p2.tank.alive).toBe(false);
	});

	it('ignores non-matching terrainDelta out of range without throwing', () => {
		const state = applyShotResolved(applyMatchStateSync(samplePayload), {
			shooterId: 'p-1',
			weaponId: 'basic_shell',
			trajectory: [],
			impact: { x: 0, y: 0 },
			terrainDelta: { startX: 500, endX: 510, heights: new Array(11).fill(1) },
			damageEvents: [],
			cashEarned: [],
			tankFalls: []
		});
		expect(state.terrain.heights).toEqual(samplePayload.terrain.heights);
	});

	it('patches a tank\'s y from tankFalls without requiring a damageEvent', () => {
		const state = applyShotResolved(applyMatchStateSync(samplePayload), {
			shooterId: 'p-1',
			weaponId: 'basic_shell',
			trajectory: [],
			impact: { x: 8, y: 108 },
			terrainDelta: { startX: 0, endX: 0, heights: [99] },
			damageEvents: [],
			cashEarned: [],
			tankFalls: [{ playerId: 'p-2', newY: 250 }]
		});

		const p2 = state.players.find((p) => p.playerId === 'p-2')!;
		expect(p2.tank.y).toBe(250);
		expect(p2.tank.health).toBe(100); // unaffected: no damageEvent for p-2
	});

	it('sets activePlayerId from turnOrder/currentTurnIndex on MatchStateSync', () => {
		const state = applyMatchStateSync(samplePayload);
		expect(state.activePlayerId).toBe('p-1');
	});

	it('flips status to IN_PROGRESS on MatchStarted', async () => {
		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();
		MockWebSocket.latest().emitMessage(
			JSON.stringify({
				type: 'MatchStarted',
				v: 1,
				payload: { matchConfig: { maxRounds: 4, maxPlayers: 8 }, players: [] }
			})
		);
		expect(get(matchStore).status).toBe('IN_PROGRESS');
	});

	it('records active player/wind/timeout on TurnStarted', async () => {
		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();
		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'MatchStateSync', v: 1, payload: samplePayload })
		);

		const before = Date.now();
		MockWebSocket.latest().emitMessage(
			JSON.stringify({
				type: 'TurnStarted',
				v: 1,
				payload: { playerId: 'p-2', wind: { strength: -8, directionSign: -1 }, turnTimeoutSec: 30 }
			})
		);

		const state = get(matchStore);
		expect(state.activePlayerId).toBe('p-2');
		expect(state.wind).toEqual({ strength: -8, directionSign: -1 });
		expect(state.turnTimeoutSec).toBe(30);
		expect(state.turnStartedAtMs).not.toBeNull();
		expect(state.turnStartedAtMs!).toBeGreaterThanOrEqual(before);
		expect(state.fireRejectedReason).toBeNull();
	});

	it('records the reason on FireRejected', async () => {
		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();
		MockWebSocket.latest().emitMessage(
			JSON.stringify({
				type: 'FireRejected',
				v: 1,
				requestId: 'r10',
				payload: { reason: 'NOT_YOUR_TURN' }
			})
		);
		expect(get(matchStore).fireRejectedReason).toBe('NOT_YOUR_TURN');
	});

	it('a later TurnStarted clears a stale FireRejected reason', async () => {
		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();
		MockWebSocket.latest().emitMessage(
			JSON.stringify({
				type: 'FireRejected',
				v: 1,
				payload: { reason: 'NOT_YOUR_TURN' }
			})
		);
		MockWebSocket.latest().emitMessage(
			JSON.stringify({
				type: 'TurnStarted',
				v: 1,
				payload: { playerId: 'p-1', wind: { strength: 1, directionSign: 1 }, turnTimeoutSec: 30 }
			})
		);
		expect(get(matchStore).fireRejectedReason).toBeNull();
	});

	it('records standings on RoundEnded', async () => {
		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();
		MockWebSocket.latest().emitMessage(
			JSON.stringify({
				type: 'RoundEnded',
				v: 1,
				payload: {
					winnerPlayerId: 'p-2',
					standings: [
						{ playerId: 'p-2', cash: 780 },
						{ playerId: 'p-1', cash: 540 }
					]
				}
			})
		);
		const state = get(matchStore);
		expect(state.roundEndedInfo?.winnerPlayerId).toBe('p-2');
		expect(state.roundEndedInfo?.standings).toHaveLength(2);
	});

	it('a fresh MatchStateSync clears a stale RoundEnded banner', async () => {
		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();
		MockWebSocket.latest().emitMessage(
			JSON.stringify({
				type: 'RoundEnded',
				v: 1,
				payload: { winnerPlayerId: 'p-2', standings: [] }
			})
		);
		expect(get(matchStore).roundEndedInfo).not.toBeNull();

		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'MatchStateSync', v: 1, payload: samplePayload })
		);
		expect(get(matchStore).roundEndedInfo).toBeNull();
	});

	it('records final standings and flips status to COMPLETE on MatchEnded', async () => {
		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();
		MockWebSocket.latest().emitMessage(
			JSON.stringify({
				type: 'MatchEnded',
				v: 1,
				payload: {
					finalStandings: [{ playerId: 'p-2', cash: 1240, damageDealt: 860, kills: 3 }]
				}
			})
		);
		const state = get(matchStore);
		expect(state.status).toBe('COMPLETE');
		expect(state.matchEndedInfo?.finalStandings).toHaveLength(1);
	});

	it('tracks disconnected players and clears them on reconnect', async () => {
		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();
		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'PlayerDisconnected', v: 1, payload: { playerId: 'p-2' } })
		);
		expect(get(matchStore).disconnectedPlayerIds).toEqual(['p-2']);

		// Duplicate disconnect notices shouldn't add a second entry.
		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'PlayerDisconnected', v: 1, payload: { playerId: 'p-2' } })
		);
		expect(get(matchStore).disconnectedPlayerIds).toEqual(['p-2']);

		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'PlayerReconnected', v: 1, payload: { playerId: 'p-2' } })
		);
		expect(get(matchStore).disconnectedPlayerIds).toEqual([]);
	});
});
