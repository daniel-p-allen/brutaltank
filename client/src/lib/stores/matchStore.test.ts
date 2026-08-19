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
			cashEarned: [{ playerId: 'p-1', amount: 110 }]
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
			cashEarned: []
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
			cashEarned: []
		});
		expect(state.terrain.heights).toEqual(samplePayload.terrain.heights);
	});
});
