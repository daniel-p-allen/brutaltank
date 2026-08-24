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
	let pendingShotAnimation: typeof import('./shotAnimationStore').pendingShotAnimation;
	let clearShotAnimation: typeof import('./shotAnimationStore').clearShotAnimation;
	let get: typeof import('svelte/store').get;

	beforeEach(async () => {
		MockWebSocket.reset();
		vi.stubGlobal('WebSocket', MockWebSocket as unknown as typeof WebSocket);
		vi.resetModules();
		({ get } = await import('svelte/store'));
		({ matchStore, applyMatchStateSync, applyShotResolved } = await import('./matchStore'));
		({ pendingShotAnimation, clearShotAnimation } = await import('./shotAnimationStore'));
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
			damageEvents: [{ playerId: 'p-2', damage: 22, newHealth: 78, eliminated: false, activeShieldId: null }],
			cashEarned: [{ playerId: 'p-1', amount: 110 }],
			tankFalls: [],
			ammoRemaining: 4,
			allImpacts: [{ x: 8, y: 108 }]
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
		expect(p1.loadout.basic_shell).toBe(4); // shooter's ammo count for weaponId decrements live
		expect(p1.cash).toBe(610); // 500 starting + 110 cashEarned, applied live rather than waiting for the next round's MatchStateSync
	});

	it('sums multiple cashEarned entries for the same player (e.g. damage cash + elimination bonus)', () => {
		const state = applyShotResolved(applyMatchStateSync(samplePayload), {
			shooterId: 'p-1',
			weaponId: 'basic_shell',
			trajectory: [],
			impact: { x: 8, y: 108 },
			terrainDelta: { startX: 0, endX: 0, heights: [99] },
			damageEvents: [{ playerId: 'p-2', damage: 100, newHealth: 0, eliminated: true, activeShieldId: null }],
			cashEarned: [
				{ playerId: 'p-1', amount: 500 },
				{ playerId: 'p-1', amount: 100 }
			],
			tankFalls: [],
			ammoRemaining: -1,
			allImpacts: []
		});

		const p1 = state.players.find((p) => p.playerId === 'p-1')!;
		expect(p1.cash).toBe(1100); // 500 starting + 500 + 100 summed
	});

	it('marks eliminated when newHealth <= 0 / eliminated true', () => {
		const state = applyShotResolved(applyMatchStateSync(samplePayload), {
			shooterId: 'p-1',
			weaponId: 'basic_shell',
			trajectory: [],
			impact: { x: 8, y: 108 },
			terrainDelta: { startX: 0, endX: 0, heights: [99] },
			damageEvents: [{ playerId: 'p-2', damage: 100, newHealth: 0, eliminated: true, activeShieldId: null }],
			cashEarned: [],
			tankFalls: [],
			ammoRemaining: -1,
			allImpacts: []
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
			tankFalls: [],
			ammoRemaining: -1,
			allImpacts: []
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
			tankFalls: [{ playerId: 'p-2', newY: 250 }],
			ammoRemaining: -1,
			allImpacts: []
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

	// Regression tests for a real bug: RoundEnded/ShopOpened/MatchEnded used
	// to apply to state (and thus MatchScreen's round-end splash) the instant
	// they arrived, even though the server sends them synchronously right
	// after the killing shot's ShotResolved -- before that shot's flight/
	// impact animation had actually played client-side. Fix: hold them back
	// while shotAnimationStore.pendingShotAnimation is non-null, and flush
	// once it clears (mirroring what GameCanvas.svelte does on real impact).
	const killingShot: ShotResolvedPayload = {
		shooterId: 'p-1',
		weaponId: 'basic_shell',
		trajectory: [{ x: 1, y: 101 }],
		impact: { x: 8, y: 108 },
		terrainDelta: { startX: 0, endX: 0, heights: [99] },
		damageEvents: [{ playerId: 'p-2', damage: 100, newHealth: 0, eliminated: true, activeShieldId: null }],
		cashEarned: [],
		tankFalls: [],
		ammoRemaining: -1,
		allImpacts: [{ x: 8, y: 108 }]
	};

	it('holds RoundEnded back while a shot animation is in flight, then flushes on clear', async () => {
		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();
		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'MatchStateSync', v: 1, payload: samplePayload })
		);
		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'ShotResolved', v: 1, requestId: 'r1', payload: killingShot })
		);
		expect(get(pendingShotAnimation)).not.toBeNull();

		MockWebSocket.latest().emitMessage(
			JSON.stringify({
				type: 'RoundEnded',
				v: 1,
				payload: { winnerPlayerId: 'p-1', standings: [{ playerId: 'p-1', cash: 500 }] }
			})
		);
		expect(get(matchStore).roundEndedInfo).toBeNull();

		clearShotAnimation();
		expect(get(matchStore).roundEndedInfo?.winnerPlayerId).toBe('p-1');
	});

	it('holds ShopOpened back while a shot animation is in flight, then flushes on clear', async () => {
		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();
		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'MatchStateSync', v: 1, payload: samplePayload })
		);
		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'ShotResolved', v: 1, requestId: 'r1', payload: killingShot })
		);

		MockWebSocket.latest().emitMessage(
			JSON.stringify({
				type: 'ShopOpened',
				v: 1,
				payload: { timeoutSec: 30, priceList: [{ itemId: 'heavy_cannonball', itemType: 'WEAPON', price: 150, stock: 10 }] }
			})
		);
		expect(get(matchStore).status).toBe('IN_PROGRESS'); // not yet flipped to SHOP

		clearShotAnimation();
		expect(get(matchStore).status).toBe('SHOP');
	});

	it('holds MatchEnded back while a shot animation is in flight, then flushes on clear', async () => {
		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();
		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'MatchStateSync', v: 1, payload: samplePayload })
		);
		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'ShotResolved', v: 1, requestId: 'r1', payload: killingShot })
		);

		MockWebSocket.latest().emitMessage(
			JSON.stringify({
				type: 'MatchEnded',
				v: 1,
				payload: { finalStandings: [{ playerId: 'p-1', cash: 500, damageDealt: 100, kills: 1 }] }
			})
		);
		expect(get(matchStore).status).toBe('IN_PROGRESS'); // not yet flipped to COMPLETE

		clearShotAnimation();
		expect(get(matchStore).status).toBe('COMPLETE');
		expect(get(matchStore).matchEndedInfo?.finalStandings).toHaveLength(1);
	});

	it('applies RoundEnded immediately when no shot animation is in flight', async () => {
		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();
		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'MatchStateSync', v: 1, payload: samplePayload })
		);
		// No ShotResolved first -- e.g. a safety-cap draw.
		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'RoundEnded', v: 1, payload: { winnerPlayerId: null, standings: [] } })
		);
		expect(get(matchStore).roundEndedInfo).not.toBeNull();
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

	it('opens the shop with a fresh price list/stock snapshot on ShopOpened', async () => {
		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();
		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'MatchStateSync', v: 1, payload: samplePayload })
		);

		MockWebSocket.latest().emitMessage(
			JSON.stringify({
				type: 'ShopOpened',
				v: 1,
				payload: {
					timeoutSec: 30,
					priceList: [
						{ itemId: 'heavy_cannonball', itemType: 'WEAPON', price: 150, stock: 10 },
						{ itemId: 'absorb_shield', itemType: 'SHIELD', price: 200, stock: 5 }
					]
				}
			})
		);

		const state = get(matchStore);
		expect(state.status).toBe('SHOP');
		expect(state.shop?.timeoutSec).toBe(30);
		expect(state.shop?.priceList).toHaveLength(2);
		expect(state.shop?.stockRemaining).toEqual({ heavy_cannonball: 10, absorb_shield: 5 });
	});

	it('patches the purchaser cash/loadout and the shared stock pool on ShopUpdate', async () => {
		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();
		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'MatchStateSync', v: 1, payload: samplePayload })
		);
		MockWebSocket.latest().emitMessage(
			JSON.stringify({
				type: 'ShopOpened',
				v: 1,
				payload: {
					timeoutSec: 30,
					priceList: [{ itemId: 'heavy_cannonball', itemType: 'WEAPON', price: 150, stock: 10 }]
				}
			})
		);

		MockWebSocket.latest().emitMessage(
			JSON.stringify({
				type: 'ShopUpdate',
				v: 1,
				payload: {
					playerId: 'p-1',
					cash: 350,
					loadout: { basic_shell: -1, heavy_cannonball: 2 },
					stockRemaining: { heavy_cannonball: 8 }
				}
			})
		);

		const state = get(matchStore);
		const p1 = state.players.find((p) => p.playerId === 'p-1')!;
		expect(p1.cash).toBe(350);
		expect(p1.loadout).toEqual({ basic_shell: -1, heavy_cannonball: 2 });
		expect(state.shop?.stockRemaining).toEqual({ heavy_cannonball: 8 });

		// A different player's cash/loadout must be untouched.
		const p2 = state.players.find((p) => p.playerId === 'p-2')!;
		expect(p2.cash).toBe(500);
	});

	it('records ErrorMsg codes (e.g. a rejected ShopPurchase) as shopErrorReason', async () => {
		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();
		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'MatchStateSync', v: 1, payload: samplePayload })
		);

		MockWebSocket.latest().emitMessage(
			JSON.stringify({
				type: 'ErrorMsg',
				v: 1,
				payload: { code: 'INSUFFICIENT_CASH', message: "You can't afford that." }
			})
		);
		expect(get(matchStore).shopErrorReason).toBe('INSUFFICIENT_CASH');
	});

	it('records live aim angles per player from PlayerAiming, regardless of whose turn it is', async () => {
		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();
		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'MatchStateSync', v: 1, payload: samplePayload })
		);

		// p2 isn't the active player (p1 is, per samplePayload's currentTurnIndex: 0),
		// but aim broadcasts aren't turn-gated.
		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'PlayerAiming', v: 1, payload: { playerId: 'p-2', angleDeg: 77 } })
		);
		expect(get(matchStore).remoteAim).toEqual({ 'p-2': 77 });

		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'PlayerAiming', v: 1, payload: { playerId: 'p-1', angleDeg: 30 } })
		);
		expect(get(matchStore).remoteAim).toEqual({ 'p-2': 77, 'p-1': 30 });

		// A later update for the same player replaces, not accumulates.
		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'PlayerAiming', v: 1, payload: { playerId: 'p-2', angleDeg: 12 } })
		);
		expect(get(matchStore).remoteAim).toEqual({ 'p-2': 12, 'p-1': 30 });
	});

	it('records live Trajectory Help on/off status per player from PlayerTrajectoryHelp', async () => {
		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();
		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'MatchStateSync', v: 1, payload: samplePayload })
		);

		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'PlayerTrajectoryHelp', v: 1, payload: { playerId: 'p-2', enabled: true } })
		);
		expect(get(matchStore).remoteTrajectoryHelp).toEqual({ 'p-2': true });

		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'PlayerTrajectoryHelp', v: 1, payload: { playerId: 'p-2', enabled: false } })
		);
		expect(get(matchStore).remoteTrajectoryHelp).toEqual({ 'p-2': false });
	});

	it('clears the shop on MatchEnded', async () => {
		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();
		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'MatchStateSync', v: 1, payload: samplePayload })
		);
		MockWebSocket.latest().emitMessage(
			JSON.stringify({
				type: 'ShopOpened',
				v: 1,
				payload: { timeoutSec: 30, priceList: [] }
			})
		);
		expect(get(matchStore).shop).not.toBeNull();

		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'MatchEnded', v: 1, payload: { finalStandings: [] } })
		);
		expect(get(matchStore).shop).toBeNull();
	});

	it('queues every ShotResolved.allImpacts point into the pending shot animation (MIRV/Cluster: not just one shared impact)', async () => {
		const { wsClient } = await import('../net/wsClient');
		wsClient.connect();
		MockWebSocket.latest().emitOpen();
		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'MatchStateSync', v: 1, payload: samplePayload })
		);

		const multiImpactShot: ShotResolvedPayload = {
			shooterId: 'p-1',
			weaponId: 'mirv',
			trajectory: [{ x: 1, y: 101 }],
			impact: { x: 5, y: 90 }, // the shared apex/split point
			terrainDelta: { startX: 0, endX: 0, heights: [99] },
			damageEvents: [],
			cashEarned: [],
			tankFalls: [],
			ammoRemaining: 1,
			allImpacts: [
				{ x: 3, y: 95 },
				{ x: 4, y: 92 },
				{ x: 6, y: 92 },
				{ x: 7, y: 95 }
			]
		};

		MockWebSocket.latest().emitMessage(
			JSON.stringify({ type: 'ShotResolved', v: 1, requestId: 'r11', payload: multiImpactShot })
		);

		const animation = get(pendingShotAnimation);
		expect(animation).not.toBeNull();
		expect(animation!.impact).toEqual({ x: 5, y: 90 });
		expect(animation!.impacts).toEqual(multiImpactShot.allImpacts);
		expect(animation!.impacts).toHaveLength(4);
	});
});
