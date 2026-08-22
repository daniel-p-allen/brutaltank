// Sends TrajectoryHelpUpdate envelopes whenever the local player toggles
// Trajectory Help (cosmetic-only, not turn-gated — see
// shared/protocol.md's TrajectoryHelpUpdate/PlayerTrajectoryHelp). Called
// reactively from FireControls.svelte, same pattern as aimInput.ts's
// sendAimUpdate but unthrottled since toggling is a discrete click, not a
// continuous drag.

import { wsClient } from '../../net/wsClient';
import { buildEnvelope } from '../../protocol/envelope';
import type { TrajectoryHelpUpdatePayload } from '../../protocol/types';

export function sendTrajectoryHelpUpdate(enabled: boolean): void {
	const payload: TrajectoryHelpUpdatePayload = { enabled };
	wsClient.sendJson(buildEnvelope('TrajectoryHelpUpdate', payload));
}
