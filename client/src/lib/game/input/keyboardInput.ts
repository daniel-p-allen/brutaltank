// Keyboard controls (per user request, 2026-08-24 follow-up): A/D adjust
// angle, W/S adjust power, Spacebar fires, 1-9/0 select weapons 1-10 from
// WEAPON_CATALOG's hotbar order (the 10 non-shield weapons only — shields
// aren't on the numeric hotbar). Held A/D/W/S ramp smoothly via
// requestAnimationFrame rather than relying on the browser's native
// key-repeat, which has an initial delay then an uneven repeat rate.
//
// Mirrors FireControls.svelte's existing gating exactly: angle/power stay
// live regardless of whose turn it is (per 2026-08-22 feedback — "you
// should be able to play with your aim even when it's not your shot"), but
// weapon-select and Fire itself respect the same disabled/fireDisabled
// state the on-screen controls already use.

import { get } from 'svelte/store';
import { aimStore } from '../../stores/aimStore';
import { weaponSelectStore, WEAPON_CATALOG, hasAmmo } from '../../stores/weaponSelectStore';
import { matchStore } from '../../stores/matchStore';
import { sessionStore } from '../../stores/sessionStore';
import { sendFire } from './fireInput';

const ANGLE_MIN = 0;
const ANGLE_MAX = 180;
const POWER_MIN = 0;
const POWER_MAX = 100;
const ANGLE_RATE_PER_SEC = 60;
const POWER_RATE_PER_SEC = 80;

/** 1-9 then 0, in WEAPON_CATALOG order, skipping shields (shields have no hotbar slot). */
export const HOTBAR_WEAPON_IDS = WEAPON_CATALOG.filter((w) => !w.isShield).map((w) => w.id);

const KEY_TO_HOTBAR_INDEX: Record<string, number> = {
	'1': 0,
	'2': 1,
	'3': 2,
	'4': 3,
	'5': 4,
	'6': 5,
	'7': 6,
	'8': 7,
	'9': 8,
	'0': 9
};

const held = { left: false, right: false, up: false, down: false };
let rafHandle: number | null = null;
let lastFrameMs: number | null = null;

// Excludes non-text input types (range, checkbox, radio, button, ...) --
// the angle/power sliders are <input type="range">, and a player naturally
// focuses one by dragging it before ever touching the keyboard. Treating
// every <input> as a "typing" target blocked all shortcuts the moment a
// slider had focus, which was the actual root cause of "nothing to do with
// the keyboard is working" (found via a scripted browser repro, 2026-08-24).
const TEXT_ENTRY_INPUT_TYPES = new Set([
	'text',
	'search',
	'email',
	'url',
	'tel',
	'password',
	'number',
	'date',
	'time',
	'datetime-local',
	'month',
	'week'
]);

/** Exported for unit testing. */
export function isTypingTarget(target: EventTarget | null): boolean {
	if (!(target instanceof HTMLElement)) return false;
	if (target.isContentEditable) return true;
	if (target.tagName === 'TEXTAREA') return true;
	if (target.tagName === 'INPUT') return TEXT_ENTRY_INPUT_TYPES.has((target as HTMLInputElement).type);
	return false;
}

function clamp(value: number, min: number, max: number): number {
	return Math.min(max, Math.max(min, value));
}

function step(deltaMs: number): void {
	if (!held.left && !held.right && !held.up && !held.down) return;
	aimStore.update((s) => {
		let { angleDeg, power } = s;
		if (held.left) angleDeg -= (ANGLE_RATE_PER_SEC * deltaMs) / 1000;
		if (held.right) angleDeg += (ANGLE_RATE_PER_SEC * deltaMs) / 1000;
		if (held.up) power += (POWER_RATE_PER_SEC * deltaMs) / 1000;
		if (held.down) power -= (POWER_RATE_PER_SEC * deltaMs) / 1000;
		return {
			angleDeg: Math.round(clamp(angleDeg, ANGLE_MIN, ANGLE_MAX)),
			power: Math.round(clamp(power, POWER_MIN, POWER_MAX))
		};
	});
}

function frame(nowMs: number): void {
	if (lastFrameMs !== null) step(nowMs - lastFrameMs);
	lastFrameMs = nowMs;
	rafHandle = requestAnimationFrame(frame);
}

function startLoopIfNeeded(): void {
	if (rafHandle !== null) return;
	lastFrameMs = null;
	rafHandle = requestAnimationFrame(frame);
}

function stopLoopIfIdle(): void {
	if (held.left || held.right || held.up || held.down) return;
	if (rafHandle !== null) {
		cancelAnimationFrame(rafHandle);
		rafHandle = null;
	}
}

function releaseAllHeldKeys(): void {
	held.left = held.right = held.up = held.down = false;
	stopLoopIfIdle();
}

function isMyTurn(): boolean {
	const m = get(matchStore);
	const s = get(sessionStore);
	return m.activePlayerId !== null && m.activePlayerId === s.playerId;
}

function localLoadout(): Record<string, number> {
	const m = get(matchStore);
	const localPlayer = m.players.find((p) => p.playerId === get(sessionStore).playerId);
	return localPlayer?.loadout ?? {};
}

function fireIfAllowed(): void {
	const m = get(matchStore);
	if (!isMyTurn() || m.awaitingShotResolution) return;
	const weaponId = get(weaponSelectStore);
	if (!hasAmmo(localLoadout()[weaponId])) return;
	const { angleDeg, power } = get(aimStore);
	sendFire(angleDeg, power);
}

function selectHotbarIfAllowed(index: number): void {
	const weaponId = HOTBAR_WEAPON_IDS[index];
	if (weaponId === undefined) return;
	if (!isMyTurn() || get(matchStore).awaitingShotResolution) return;
	if (!hasAmmo(localLoadout()[weaponId])) return;
	weaponSelectStore.select(weaponId);
}

function onKeyDown(e: KeyboardEvent): void {
	if (isTypingTarget(e.target)) return;
	switch (e.key) {
		case 'a':
		case 'A':
			held.left = true;
			startLoopIfNeeded();
			break;
		case 'd':
		case 'D':
			held.right = true;
			startLoopIfNeeded();
			break;
		case 'w':
		case 'W':
			held.up = true;
			startLoopIfNeeded();
			break;
		case 's':
		case 'S':
			held.down = true;
			startLoopIfNeeded();
			break;
		case ' ':
		case 'Spacebar':
			e.preventDefault();
			if (!e.repeat) fireIfAllowed();
			break;
		default: {
			const index = KEY_TO_HOTBAR_INDEX[e.key];
			if (index !== undefined && !e.repeat) selectHotbarIfAllowed(index);
		}
	}
}

function onKeyUp(e: KeyboardEvent): void {
	switch (e.key) {
		case 'a':
		case 'A':
			held.left = false;
			break;
		case 'd':
		case 'D':
			held.right = false;
			break;
		case 'w':
		case 'W':
			held.up = false;
			break;
		case 's':
		case 'S':
			held.down = false;
			break;
	}
	stopLoopIfIdle();
}

let attached = false;

/** Attaches the window-level keyboard listeners; call once from FireControls.svelte's onMount. */
export function attachKeyboardControls(): void {
	if (attached) return;
	attached = true;
	window.addEventListener('keydown', onKeyDown);
	window.addEventListener('keyup', onKeyUp);
	// A window blur (e.g. alt-tab) while a key is held never delivers a
	// matching keyup, which would otherwise leave the RAF ramp loop running
	// forever with a "stuck" direction.
	window.addEventListener('blur', releaseAllHeldKeys);
}

/** Detaches listeners and resets held-key state; call from onDestroy. */
export function detachKeyboardControls(): void {
	attached = false;
	window.removeEventListener('keydown', onKeyDown);
	window.removeEventListener('keyup', onKeyUp);
	window.removeEventListener('blur', releaseAllHeldKeys);
	releaseAllHeldKeys();
}
