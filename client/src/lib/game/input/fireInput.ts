// Builds and sends the Fire envelope. M3: weaponId now comes from the
// weapon-select HUD (weaponSelectStore) instead of the M1 hardcoded
// basic_shell constant.

import { wsClient } from '../../net/wsClient';
import { buildEnvelope, nextRequestId } from '../../protocol/envelope';
import type { FirePayload } from '../../protocol/types';
import { matchStore } from '../../stores/matchStore';
import { weaponSelectStore } from '../../stores/weaponSelectStore';
import { get } from 'svelte/store';

/** Retained for tests/back-compat; the live default lives in weaponSelectStore.ts. */
export const HARDCODED_WEAPON_ID = 'basic_shell';

export function sendFire(angleDeg: number, power: number): string {
	const requestId = nextRequestId();
	const weaponId = get(weaponSelectStore);
	const payload: FirePayload = { weaponId, angleDeg, power };
	const envelope = buildEnvelope('Fire', payload, requestId);
	wsClient.sendJson(envelope);
	matchStore.markFireSent();
	return requestId;
}
