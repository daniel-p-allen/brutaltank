// Interpolates a dot along a ShotResolved trajectory over a short fixed
// duration, per PLAN.md section 3.2 ("active projectile animation
// (interpolated along ShotResolved.trajectory)"). No explosion sprite for
// M1 — a simple radius-flash circle at impact is a nice-to-have.

import { worldToCanvas, type Viewport } from './coords';
import type { Point } from '../../protocol/types';

export const PROJECTILE_ANIMATION_DURATION_MS = 1200;
/** How long the post-impact flash lingers, appended after the flight duration. */
const IMPACT_FLASH_DURATION_MS = 250;

/** Returns a point along the (already resampled) trajectory for a given 0..1 progress. */
export function pointAtProgress(trajectory: Point[], progress: number): Point | null {
	if (trajectory.length === 0) return null;
	if (trajectory.length === 1) return trajectory[0];

	const clamped = Math.min(1, Math.max(0, progress));
	const segmentCount = trajectory.length - 1;
	const scaled = clamped * segmentCount;
	const i = Math.min(segmentCount - 1, Math.floor(scaled));
	const t = scaled - i;

	const a = trajectory[i];
	const b = trajectory[i + 1];
	return { x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t };
}

export function drawProjectile(
	ctx: CanvasRenderingContext2D,
	trajectory: Point[],
	impact: Point,
	elapsedMs: number,
	viewport: Viewport
): void {
	if (elapsedMs <= PROJECTILE_ANIMATION_DURATION_MS) {
		const progress = elapsedMs / PROJECTILE_ANIMATION_DURATION_MS;
		const worldPoint = pointAtProgress(trajectory, progress);
		if (!worldPoint) return;
		const { x, y } = worldToCanvas(worldPoint.x, worldPoint.y, viewport);
		ctx.beginPath();
		ctx.fillStyle = '#222';
		ctx.arc(x, y, 4, 0, Math.PI * 2);
		ctx.fill();
		return;
	}

	// Post-flight impact flash, nice-to-have.
	const flashElapsed = elapsedMs - PROJECTILE_ANIMATION_DURATION_MS;
	if (flashElapsed <= IMPACT_FLASH_DURATION_MS) {
		const flashProgress = flashElapsed / IMPACT_FLASH_DURATION_MS;
		const { x, y } = worldToCanvas(impact.x, impact.y, viewport);
		const radius = 6 + flashProgress * 20;
		ctx.beginPath();
		ctx.fillStyle = `rgba(255, 160, 40, ${1 - flashProgress})`;
		ctx.arc(x, y, radius, 0, Math.PI * 2);
		ctx.fill();
	}
}

export function isAnimationFinished(elapsedMs: number): boolean {
	return elapsedMs > PROJECTILE_ANIMATION_DURATION_MS + IMPACT_FLASH_DURATION_MS;
}
