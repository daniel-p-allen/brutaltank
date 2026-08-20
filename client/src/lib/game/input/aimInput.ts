// Sends throttled AimUpdate envelopes (cosmetic-only, not turn-gated — see
// shared/protocol.md's AimUpdate/PlayerAiming). Called from FireControls.svelte
// whenever the local player's angle slider moves. Throttled (not sent on
// every pixel of drag) to keep traffic light; always flushes the latest
// value on a trailing edge so the final position is never dropped.

import { wsClient } from '../../net/wsClient';
import { buildEnvelope } from '../../protocol/envelope';
import type { AimUpdatePayload } from '../../protocol/types';

const THROTTLE_MS = 100;

let lastSentAtMs = 0;
let trailingTimer: ReturnType<typeof setTimeout> | null = null;
let pendingAngleDeg: number | null = null;

function send(angleDeg: number): void {
	lastSentAtMs = Date.now();
	const payload: AimUpdatePayload = { angleDeg };
	wsClient.sendJson(buildEnvelope('AimUpdate', payload));
}

/** Throttled: sends immediately if enough time has passed since the last send, otherwise schedules a trailing-edge send of the latest value. */
export function sendAimUpdate(angleDeg: number): void {
	const elapsed = Date.now() - lastSentAtMs;
	if (elapsed >= THROTTLE_MS) {
		if (trailingTimer !== null) {
			clearTimeout(trailingTimer);
			trailingTimer = null;
		}
		send(angleDeg);
		return;
	}

	pendingAngleDeg = angleDeg;
	if (trailingTimer !== null) return;
	trailingTimer = setTimeout(() => {
		trailingTimer = null;
		if (pendingAngleDeg !== null) {
			send(pendingAngleDeg);
			pendingAngleDeg = null;
		}
	}, THROTTLE_MS - elapsed);
}
