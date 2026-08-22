// Builds and sends the Fire envelope. M3: weaponId now comes from the
// weapon-select HUD (weaponSelectStore) instead of the M1 hardcoded
// basic_shell constant.

import { wsClient } from '../../net/wsClient';
import { buildEnvelope, nextRequestId } from '../../protocol/envelope';
import type { FirePayload } from '../../protocol/types';
import { matchStore } from '../../stores/matchStore';
import { weaponSelectStore } from '../../stores/weaponSelectStore';
import { trajectoryHelpStore } from '../../stores/trajectoryHelpStore';
import { get } from 'svelte/store';
import { unlockAudio, playLaunch } from '../../audio/soundManager';

/** Retained for tests/back-compat; the live default lives in weaponSelectStore.ts. */
export const HARDCODED_WEAPON_ID = 'basic_shell';

// Trajectory Help is permanently unavailable for Nuke (FireControls.svelte's
// trajectoryHelpUnavailable) — mirrored here so a Nuke shot is always
// reported as "help not used" even if the toggle itself is left on from a
// previously-selected weapon, keeping the risk/reward bonus (per user
// decision, 2026-08-23) consistent with what the player actually saw on
// screen while aiming.
const NUKE_WEAPON_ID = 'nuke';

export function sendFire(angleDeg: number, power: number): string {
	// A real user gesture — the one place AudioContext creation/resume is
	// allowed to happen under browser autoplay policy (PLAN.md section 7.2).
	unlockAudio();

	const requestId = nextRequestId();
	const weaponId = get(weaponSelectStore);
	const trajectoryHelpUsed = get(trajectoryHelpStore) && weaponId !== NUKE_WEAPON_ID;
	const payload: FirePayload = { weaponId, angleDeg, power, trajectoryHelpUsed };
	const envelope = buildEnvelope('Fire', payload, requestId);
	playLaunch(weaponId);
	wsClient.sendJson(envelope);
	matchStore.markFireSent();
	return requestId;
}
