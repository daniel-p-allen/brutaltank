// The local player's currently-selected weapon/shield id for the next Fire
// (PLAN.md M3: "weapon-select HUD"). Ephemeral local UI state, not synced/
// authoritative match state (see PLAN.md 3.2's localUiStore concept) — same
// pattern as aimStore.ts. No new protocol message: WeaponSelect.svelte just
// writes here, and fireInput.ts reads it as the `weaponId` on the existing
// Fire envelope.

import { writable } from 'svelte/store';

/** The full M3 roster (PLAN.md 4.4), in table order. Icons are a later (M5) polish item. */
export interface WeaponCatalogEntry {
	id: string;
	label: string;
	isShield: boolean;
	/**
	 * 1-3 star weight class (per user feedback, 2026-08-22: "each of the
	 * weapons need to sit in a weight class... visible under each of the
	 * weapons as part of the button"). Mirrors the server's WeaponDef
	 * gravityMultiplier tiers (server/.../WeaponDef.java) — kept in sync by
	 * hand, same as id/label already are, since weapon stats aren't sent
	 * over the wire. Shields have no weight class (never thrown ballistic).
	 */
	weightClass?: 1 | 2 | 3;
	/**
	 * Damage *rating* for the graphical damage pie (2026-08-25 user request:
	 * "a way to graphically show the damage of weapons like we do weight").
	 * Mirrors the server's WeaponDef.centerDamage by hand (server/.../
	 * WeaponDef.java) — kept in sync the same way weightClass is, since
	 * weapon stats aren't sent over the wire. For most weapons this is
	 * exactly centerDamage (one detonation, effectively a guaranteed hit).
	 * Two multi-impact weapons are deliberately NOT their raw centerDamage
	 * summed across every child/bomblet, per explicit user framing
	 * (2026-08-25): rate what a shot realistically deals, not its best-case
	 * total.
	 *   - MIRV: centerDamage IS already per-child (30) in WeaponDef, so no
	 *     multiplier is needed here — "only one will hit" is already what's
	 *     stored.
	 *   - Cluster Bomb: WeaponDef.centerDamage (40) is per detonation point
	 *     (1 primary + 4 bomblets, confirmed in Match.java's CLUSTER
	 *     branch — every one of the 5 uses the same centerDamage, just a
	 *     different blastRadius/bombletBlastRadius). Rated as 40*2=80
	 *     ("two might hit").
	 * Shields have no damage rating (never deal direct damage themselves).
	 */
	centerDamage?: number;
}

export const WEAPON_CATALOG: WeaponCatalogEntry[] = [
	{ id: 'basic_shell', label: 'Basic Shell', isShield: false, weightClass: 2, centerDamage: 50 },
	{ id: 'baby_missile', label: 'Baby Missile', isShield: false, weightClass: 1, centerDamage: 36 },
	{ id: 'heavy_cannonball', label: 'Heavy Cannonball', isShield: false, weightClass: 3, centerDamage: 60 },
	{ id: 'mirv', label: 'MIRV', isShield: false, weightClass: 2, centerDamage: 30 },
	{ id: 'napalm', label: 'Napalm', isShield: false, weightClass: 2, centerDamage: 40 },
	{ id: 'tunneling_shot', label: 'Tunneling Shot', isShield: false, weightClass: 2, centerDamage: 60 },
	{ id: 'bouncing_betty', label: 'Bouncing Betty', isShield: false, weightClass: 1, centerDamage: 50 },
	{ id: 'cluster_bomb', label: 'Cluster Bomb', isShield: false, weightClass: 2, centerDamage: 80 },
	{ id: 'digger', label: 'Digger', isShield: false, weightClass: 3, centerDamage: 20 },
	{ id: 'nuke', label: 'Nuke', isShield: false, weightClass: 3, centerDamage: 190 },
	{ id: 'absorb_shield', label: 'Absorb Shield', isShield: true },
	{ id: 'deflect_shield', label: 'Deflect Shield', isShield: true },
	{ id: 'reflect_shield', label: 'Reflect Shield', isShield: true }
];

/** Nuke's rating (190) — the roster max the damage pie scales against. Kept in sync by hand alongside the table above. */
export const MAX_DAMAGE_RATING = 190;

const PIE_YELLOW: [number, number, number] = [255, 209, 102]; // #ffd166
const PIE_ORANGE: [number, number, number] = [255, 140, 66]; // #ff8c42
const PIE_RED: [number, number, number] = [230, 57, 70]; // #e63946
const PIE_GRAY = '#2c3341'; // unfilled wedge, matches chip background family
const PIE_YELLOW_ANCHOR_PCT = 100 / 12; // 1 o'clock
const PIE_ORANGE_ANCHOR_PCT = 50; // 6 o'clock

function lerpChannel(a: number, b: number, t: number): number {
	return a + (b - a) * t;
}

function lerpColor(a: [number, number, number], b: [number, number, number], t: number): [number, number, number] {
	return [lerpChannel(a[0], b[0], t), lerpChannel(a[1], b[1], t), lerpChannel(a[2], b[2], t)];
}

function toHex([r, g, b]: [number, number, number]): string {
	return (
		'#' +
		[r, g, b]
			.map((v) => Math.round(Math.max(0, Math.min(255, v))).toString(16).padStart(2, '0'))
			.join('')
	);
}

/**
 * Pure helper (exported for unit testing): the color at a given percentage
 * around the FIXED damage-pie color wheel (0-100, wrapping like a clock
 * face) — 1 o'clock (~8.3%) is always yellow, 6 o'clock (50%) is always
 * orange, 12 o'clock (100%, a full lap) is always red. This wheel is the
 * same for every weapon; only how much of it a weapon's wedge reveals
 * changes (see damagePieGradient below) — a small wedge only ever samples
 * the yellow zone near the start, a near-max wedge sweeps all the way
 * through to red.
 */
export function damagePieColorAt(pct: number): string {
	const clamped = Math.max(0, Math.min(100, pct));
	if (clamped <= PIE_YELLOW_ANCHOR_PCT) return toHex(PIE_YELLOW);
	if (clamped <= PIE_ORANGE_ANCHOR_PCT) {
		const t = (clamped - PIE_YELLOW_ANCHOR_PCT) / (PIE_ORANGE_ANCHOR_PCT - PIE_YELLOW_ANCHOR_PCT);
		return toHex(lerpColor(PIE_YELLOW, PIE_ORANGE, t));
	}
	const t = (clamped - PIE_ORANGE_ANCHOR_PCT) / (100 - PIE_ORANGE_ANCHOR_PCT);
	return toHex(lerpColor(PIE_ORANGE, PIE_RED, t));
}

/**
 * Pure helper (exported for unit testing): the CSS conic-gradient for a
 * weapon's damage pie, given its damage rating. Reveals a wedge of the
 * fixed color wheel (damagePieColorAt) starting at 12 o'clock, sized to
 * `centerDamage / MAX_DAMAGE_RATING`, with the remainder rendered as
 * PIE_GRAY. See WeaponCatalogEntry.centerDamage's own doc for why this
 * value isn't always the raw server centerDamage for multi-impact weapons.
 */
export function damagePieGradient(centerDamage: number): string {
	const cutoffPct = Math.max(0, Math.min(100, (centerDamage / MAX_DAMAGE_RATING) * 100));
	const edgeColor = damagePieColorAt(cutoffPct);
	const stops = [`${toHex(PIE_YELLOW)} 0%`, `${toHex(PIE_YELLOW)} ${PIE_YELLOW_ANCHOR_PCT}%`];
	if (cutoffPct > PIE_ORANGE_ANCHOR_PCT) {
		stops.push(`${toHex(PIE_ORANGE)} ${PIE_ORANGE_ANCHOR_PCT}%`);
	}
	stops.push(`${edgeColor} ${cutoffPct}%`);
	stops.push(`${PIE_GRAY} ${cutoffPct}% 100%`);
	return `conic-gradient(${stops.join(', ')})`;
}

const DEFAULT_WEAPON_ID = 'basic_shell';

function createWeaponSelectStore() {
	const { subscribe, set } = writable<string>(DEFAULT_WEAPON_ID);

	return {
		subscribe,
		/** Selects a weapon/shield id as the one to send on the next Fire. */
		select(weaponId: string): void {
			set(weaponId);
		},
		/** Resets to the default selection (e.g. returning to the menu after a match ends). */
		reset(): void {
			set(DEFAULT_WEAPON_ID);
		}
	};
}

export const weaponSelectStore = createWeaponSelectStore();

/** Pure helper (exported for unit testing): -1 displays as unlimited, everything else as its count. */
export function formatQuantity(qty: number | undefined): string {
	if (qty === undefined) return '0';
	return qty === -1 ? '∞' : String(qty);
}

/** Pure helper (exported for unit testing): a weapon/shield is selectable only if it has ammo remaining. */
export function hasAmmo(qty: number | undefined): boolean {
	return qty !== undefined && qty !== 0;
}
