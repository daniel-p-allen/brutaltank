// Transient "a shot is currently animating" state — deliberately separate
// from matchStore per PLAN.md section 3.2 ("expose the shot's
// trajectory/impact so the renderer can animate it ... a separate small
// store/event for 'pending shot animation', since that's transient rather
// than persistent match state").
//
// matchStore writes into this store whenever a ShotResolved arrives (after
// patching the authoritative terrain/health state); GameCanvas reads it to
// drive the projectile animation.

import { writable } from 'svelte/store';
import type { Point } from '../protocol/types';

export interface PendingShotAnimation {
	shooterId: string;
	weaponId: string;
	trajectory: Point[];
	impact: Point;
	/** performance.now() timestamp when this animation was queued. */
	startedAtMs: number;
}

export const pendingShotAnimation = writable<PendingShotAnimation | null>(null);

export function queueShotAnimation(shot: Omit<PendingShotAnimation, 'startedAtMs'>): void {
	pendingShotAnimation.set({ ...shot, startedAtMs: performance.now() });
}

export function clearShotAnimation(): void {
	pendingShotAnimation.set(null);
}
