// The local player's currently-selected (not yet fired) angle/power, so the
// GameCanvas renderer can show the local tank's barrel tracking the angle
// control live as the user drags it, rather than only updating on Fire.
//
// Written by the angle/power input UI, read by GameCanvas. Kept separate
// from matchStore since this is ephemeral local UI state, not synced/
// authoritative match state (see PLAN.md 3.2's localUiStore concept).

import { writable } from 'svelte/store';

export interface AimState {
	angleDeg: number;
	power: number;
}

export const aimStore = writable<AimState>({ angleDeg: 45, power: 60 });
