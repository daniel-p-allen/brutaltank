// Opt-in "Trajectory Help" toggle (per user request, 2026-08-22; refined same
// day — "that trajectory does need to take into consideration weight"): a
// dotted preview line while aiming showing where the shot would land
// accounting for the selected weapon's real weight (gravityMultiplier/
// powerScaleMultiplier) but ignoring wind — deliberately not the real
// predicted impact (wind, homing, bounce/tunnel behavior all still apply to
// the actual fired shot; see trajectoryPreview.ts). Off by default,
// persisted to localStorage like soundManager's mute toggle so the choice
// sticks across sessions.

import { writable } from 'svelte/store';

const STORAGE_KEY = 'brutaltank.trajectoryHelp';

function readInitial(): boolean {
	try {
		return localStorage.getItem(STORAGE_KEY) === '1';
	} catch {
		return false;
	}
}

function createTrajectoryHelpStore() {
	const { subscribe, update } = writable<boolean>(readInitial());

	return {
		subscribe,
		toggle(): void {
			update((enabled) => {
				const next = !enabled;
				try {
					localStorage.setItem(STORAGE_KEY, next ? '1' : '0');
				} catch {
					// localStorage unavailable (e.g. private mode) — in-memory only, fine.
				}
				return next;
			});
		}
	};
}

export const trajectoryHelpStore = createTrajectoryHelpStore();
