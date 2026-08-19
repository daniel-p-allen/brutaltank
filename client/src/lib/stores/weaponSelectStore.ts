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
}

export const WEAPON_CATALOG: WeaponCatalogEntry[] = [
	{ id: 'basic_shell', label: 'Basic Shell', isShield: false },
	{ id: 'baby_missile', label: 'Baby Missile', isShield: false },
	{ id: 'heavy_cannonball', label: 'Heavy Cannonball', isShield: false },
	{ id: 'mirv', label: 'MIRV', isShield: false },
	{ id: 'napalm', label: 'Napalm', isShield: false },
	{ id: 'tunneling_shot', label: 'Tunneling Shot', isShield: false },
	{ id: 'bouncing_betty', label: 'Bouncing Betty', isShield: false },
	{ id: 'cluster_bomb', label: 'Cluster Bomb', isShield: false },
	{ id: 'digger', label: 'Digger', isShield: false },
	{ id: 'nuke', label: 'Nuke', isShield: false },
	{ id: 'absorb_shield', label: 'Absorb Shield', isShield: true },
	{ id: 'deflect_shield', label: 'Deflect Shield', isShield: true },
	{ id: 'reflect_shield', label: 'Reflect Shield', isShield: true }
];

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
