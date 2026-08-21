// TEMPORARY DEBUG-ONLY (per user request, 2026-08-22: "put a temporary 3rd
// slider in... so i can zero the wind and check everything"). Remove this
// file, its FireControls.svelte slider, and the matching server/protocol
// pieces (Payloads.DevSetWind/WindOverridden, Match.devSetWind,
// BrutalTankServer's dispatch case) once manual wind-direction verification
// is done.

import { wsClient } from '../../net/wsClient';
import { buildEnvelope } from '../../protocol/envelope';
import type { DevSetWindPayload } from '../../protocol/types';

export function sendDevSetWind(strength: number): void {
	const payload: DevSetWindPayload = { strength };
	const envelope = buildEnvelope('DevSetWind', payload);
	wsClient.sendJson(envelope);
}
