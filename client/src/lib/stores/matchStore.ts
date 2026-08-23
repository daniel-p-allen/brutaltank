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
//   - ShopOpened        -> flips status to SHOP and records the price list
//                          (with initial stock) + timeout for ShopOverlay.
//   - ShopUpdate        -> patches the purchasing player's cash/loadout and
//                          the shared stock pool (broadcast to everyone).
//   - ErrorMsg          -> currently only surfaced for rejected
//                          ShopPurchases (shopErrorReason).
//   - PlayerAiming      -> records a player's live aim angle (remoteAim),
//                          cosmetic-only, not turn-gated.
//   - PlayerTrajectoryHelp -> records a player's live Trajectory Help on/off
//                          status (remoteTrajectoryHelp), same as PlayerAiming.
//
// See shared/protocol.md sections 3-4 for the message shapes this store
// must stay in lockstep with.

import { writable } from 'svelte/store';
import { wsClient } from '../net/wsClient';
import { parseEnvelope } from '../protocol/envelope';
import type {
	ErrorMsgPayload,
	FireRejectedPayload,
	MatchEndedPayload,
	MatchStateSyncPayload,
	Player,
	PlayerAimingPayload,
	PlayerTrajectoryHelpPayload,
	PriceListEntry,
	RoundEndedPayload,
	ShopOpenedPayload,
	ShopUpdatePayload,
	ShotResolvedPayload,
	Terrain,
	TurnForfeitedPayload,
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
	/**
	 * Set on ShopOpened, cleared on the next MatchStateSync/MatchEnded —
	 * drives ShopOverlay.svelte. `stockRemaining` starts from each price
	 * list entry's initial `stock` and is replaced wholesale by every
	 * ShopUpdate's `stockRemaining` (the shared pool, M4).
	 */
	shop: { priceList: PriceListEntry[]; timeoutSec: number; openedAtMs: number; stockRemaining: Record<string, number> } | null;
	/** Reason code from the most recent ErrorMsg (currently only used for rejected ShopPurchases), cleared on the next ShopOpened. */
	shopErrorReason: string | null;
	/**
	 * Every player's last-known live aim angle (from PlayerAiming broadcasts),
	 * keyed by playerId. Cosmetic-only — tankRenderer uses this so every
	 * connected client sees every tank's barrel track the angle its owner is
	 * currently dragging, not just the local player's own tank. Cleared on
	 * MatchStateSync since a fresh round/reconnect has no live values yet.
	 */
	remoteAim: Record<string, number>;
	/**
	 * Every player's last-known Trajectory Help on/off status (from
	 * PlayerTrajectoryHelp broadcasts), keyed by playerId — same rationale
	 * and lifecycle as remoteAim, shown in the players list per user
	 * request, 2026-08-23. Missing entry means "off" (nobody's sent one yet
	 * this session).
	 */
	remoteTrajectoryHelp: Record<string, boolean>;
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
		matchEndedInfo: null,
		shop: null,
		shopErrorReason: null,
		remoteAim: {},
		remoteTrajectoryHelp: {}
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
		matchEndedInfo: null,
		shop: null,
		shopErrorReason: null,
		remoteAim: {},
		remoteTrajectoryHelp: {}
	};
}

// Mirrors WEAPON_CATALOG's isShield flags (weaponSelectStore.ts) — kept as a
// small local set rather than importing that store, since this is the only
// spot matchStore.ts needs it: detecting a shield-activation "shot" so the
// shooter's activeShieldId gets patched in (see applyShotResolved below).
const SHIELD_IDS = new Set(['absorb_shield', 'deflect_shield', 'reflect_shield']);

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
	const fallByPlayer = new Map((payload.tankFalls ?? []).map((f) => [f.playerId, f]));
	// A shot can earn its shooter multiple CashEarned entries (damage cash,
	// elimination bonus, a shield's cashback) — sum per player rather than
	// assuming one entry each, so a player's cash stays live-accurate turn
	// to turn instead of only refreshing on the next round's MatchStateSync
	// (needed for the players-list cash display, per user request 2026-08-23).
	const cashGainedByPlayer = new Map<string, number>();
	for (const c of payload.cashEarned) {
		cashGainedByPlayer.set(c.playerId, (cashGainedByPlayer.get(c.playerId) ?? 0) + c.amount);
	}
	const players = state.players.map((p) => {
		const dmg = healthByPlayer.get(p.playerId);
		const fall = fallByPlayer.get(p.playerId);
		const cashGained = cashGainedByPlayer.get(p.playerId);
		// The shooter's ammo count for weaponId decrements on every shot server-side,
		// but without this the client only ever saw it refresh on the next full
		// MatchStateSync (i.e. the next round) — see ShotResolvedPayload.ammoRemaining.
		const isShooter = p.playerId === payload.shooterId;
		const activatedShieldId = isShooter && SHIELD_IDS.has(payload.weaponId) ? payload.weaponId : null;
		if (!dmg && !fall && !isShooter && !cashGained) return p;
		return {
			...p,
			...(isShooter ? { loadout: { ...p.loadout, [payload.weaponId]: payload.ammoRemaining } } : {}),
			...(cashGained ? { cash: p.cash + cashGained } : {}),
			...(activatedShieldId ? { activeShieldId: activatedShieldId } : {}),
			...(dmg ? { activeShieldId: dmg.activeShieldId } : {}),
			tank: {
				...p.tank,
				...(dmg ? { health: dmg.newHealth, alive: !dmg.eliminated } : {}),
				...(fall ? { y: fall.newY } : {})
			}
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
	return { ...state, matchEndedInfo: payload, status: 'COMPLETE', shop: null, shopErrorReason: null };
}

/** Pure helper (exported for unit testing): opens the shop UI with a fresh price list/stock snapshot. */
export function applyShopOpened(state: MatchState, payload: ShopOpenedPayload): MatchState {
	return {
		...state,
		status: 'SHOP',
		shop: {
			priceList: payload.priceList,
			timeoutSec: payload.timeoutSec,
			openedAtMs: Date.now(),
			stockRemaining: Object.fromEntries(payload.priceList.map((e) => [e.itemId, e.stock]))
		},
		shopErrorReason: null,
		// The round-end overlay served its purpose the moment the shop opens
		// (its own doc comment says it lingers "until the next
		// MatchStateSync clears it", but ShopOpened fires immediately after
		// RoundEnded and MatchStateSync doesn't arrive until the *next*
		// round starts) — without this, the round-end overlay stayed
		// stacked on top of ShopOverlay for the player's whole shopping
		// window, silently eating into the shop's timer while they read a
		// summary they'd already seen (per user report: shop "randomly"
		// timing out — the timer was running the whole time, just hidden).
		roundEndedInfo: null
	};
}

/** Pure helper (exported for unit testing): patches the purchaser's cash/loadout and the shared stock pool. */
export function applyShopUpdate(state: MatchState, payload: ShopUpdatePayload): MatchState {
	return {
		...state,
		players: state.players.map((p) =>
			p.playerId === payload.playerId ? { ...p, cash: payload.cash, loadout: payload.loadout } : p
		),
		shop: state.shop ? { ...state.shop, stockRemaining: payload.stockRemaining } : state.shop
	};
}

/** Pure helper (exported for unit testing). */
export function applyErrorMsg(state: MatchState, payload: ErrorMsgPayload): MatchState {
	return { ...state, shopErrorReason: payload.code };
}

/** Pure helper (exported for unit testing): records a player's live aim angle from a PlayerAiming broadcast. */
export function applyPlayerAiming(state: MatchState, payload: PlayerAimingPayload): MatchState {
	return { ...state, remoteAim: { ...state.remoteAim, [payload.playerId]: payload.angleDeg } };
}

/** Pure helper (exported for unit testing): records a player's live Trajectory Help on/off status from a PlayerTrajectoryHelp broadcast. */
export function applyPlayerTrajectoryHelp(state: MatchState, payload: PlayerTrajectoryHelpPayload): MatchState {
	return {
		...state,
		remoteTrajectoryHelp: { ...state.remoteTrajectoryHelp, [payload.playerId]: payload.enabled }
	};
}

/** Pure helper (exported for unit testing): applies a missed-turn cash penalty, and bankruptcy elimination if it brought cash to 0. */
export function applyTurnForfeited(state: MatchState, payload: TurnForfeitedPayload): MatchState {
	return {
		...state,
		players: state.players.map((p) =>
			p.playerId === payload.playerId
				? {
						...p,
						cash: payload.newCash,
						tank: payload.eliminated ? { ...p.tank, alive: false } : p.tank
					}
				: p
		)
	};
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
				let preShotHeights: number[] = [];
				const preShotHealth: Record<string, { health: number; alive: boolean }> = {};
				const preShotTankY: Record<string, number> = {};
				update((state) => {
					preShotHeights = state.terrain.heights;
					for (const e of payload.damageEvents) {
						const p = state.players.find((pl) => pl.playerId === e.playerId);
						if (p) preShotHealth[e.playerId] = { health: p.tank.health, alive: p.tank.alive };
					}
					for (const f of payload.tankFalls ?? []) {
						const p = state.players.find((pl) => pl.playerId === f.playerId);
						if (p) preShotTankY[f.playerId] = p.tank.y;
					}
					return applyShotResolved(state, payload);
				});
				queueShotAnimation({
					shooterId: payload.shooterId,
					weaponId: payload.weaponId,
					trajectory: payload.trajectory,
					impact: payload.impact,
					impacts: payload.allImpacts,
					preShotHeights,
					preShotHealth,
					preShotTankY
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

			case 'ShopOpened':
				update((state) => applyShopOpened(state, envelope.payload as ShopOpenedPayload));
				return;

			case 'ShopUpdate':
				update((state) => applyShopUpdate(state, envelope.payload as ShopUpdatePayload));
				return;

			case 'ErrorMsg':
				update((state) => applyErrorMsg(state, envelope.payload as ErrorMsgPayload));
				return;

			case 'PlayerAiming':
				update((state) => applyPlayerAiming(state, envelope.payload as PlayerAimingPayload));
				return;

			case 'PlayerTrajectoryHelp':
				update((state) => applyPlayerTrajectoryHelp(state, envelope.payload as PlayerTrajectoryHelpPayload));
				return;

			case 'TurnForfeited':
				update((state) => applyTurnForfeited(state, envelope.payload as TurnForfeitedPayload));
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
