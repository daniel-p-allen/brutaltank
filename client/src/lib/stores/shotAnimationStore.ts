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
	/**
	 * Every real detonation point this shot produced (see
	 * ShotResolvedPayload.allImpacts) — for a single-impact weapon this is
	 * just [impact]; for MIRV/Cluster Bomb it's every child's/bomblet's
	 * landing point, so the flash effect renders at each one instead of
	 * just the shared `impact` (MIRV's apex / Cluster's primary hit).
	 */
	impacts: Point[];
	/** performance.now() timestamp when this animation was queued. */
	startedAtMs: number;
	/**
	 * Terrain heights and per-player health/alive exactly as they were
	 * before this shot's delta was applied. matchStore applies state
	 * immediately on receipt (so subsequent TurnStarted/RoundEnded messages
	 * don't need to be buffered), but GameCanvas renders these "before"
	 * values for the duration of the flight animation instead, so the
	 * crater/damage don't visibly appear before the projectile gets there.
	 */
	preShotHeights: number[];
	preShotHealth: Record<string, { health: number; alive: boolean }>;
}

export const pendingShotAnimation = writable<PendingShotAnimation | null>(null);

export function queueShotAnimation(shot: Omit<PendingShotAnimation, 'startedAtMs'>): void {
	pendingShotAnimation.set({ ...shot, startedAtMs: performance.now() });
}

export function clearShotAnimation(): void {
	pendingShotAnimation.set(null);
}
