// Builds and sends the Fire envelope. M1 is basic-shell only (PLAN.md M1:
// "one hardcoded ... weapon (basic shell)"), so weaponId is hardcoded here
// rather than exposed via a weapon-select UI (that's M3).

import { wsClient } from '../../net/wsClient';
import { buildEnvelope, nextRequestId } from '../../protocol/envelope';
import type { FirePayload } from '../../protocol/types';
import { matchStore } from '../../stores/matchStore';

export const HARDCODED_WEAPON_ID = 'basic_shell';

export function sendFire(angleDeg: number, power: number): string {
	const requestId = nextRequestId();
	const payload: FirePayload = { weaponId: HARDCODED_WEAPON_ID, angleDeg, power };
	const envelope = buildEnvelope('Fire', payload, requestId);
	wsClient.sendJson(envelope);
	matchStore.markFireSent();
	return requestId;
}
