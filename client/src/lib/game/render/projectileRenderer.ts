// Interpolates a dot along a ShotResolved trajectory over a short fixed
// duration, per PLAN.md section 3.2 ("active projectile animation
// (interpolated along ShotResolved.trajectory)"). No explosion sprite for
// M1 — a simple radius-flash circle at impact is a nice-to-have.
//
// Multi-impact weapons (MIRV children, Cluster Bomb bomblets) report every
// real detonation point via ShotResolved.allImpacts, not just one shared
// `impact` — the flash is drawn at every point in that list, not just one,
// so those weapons' other craters don't silently appear with zero visual
// feedback (a regression this fixes: the MIRV animation-rewind fix removed
// children's flight paths from the trajectory, which also removed the only
// thing that used to draw near their actual landing spots).
//
// Nuke gets a distinct, more dramatic effect (bigger fireball + smoke) per
// user feedback ("more graphics and smoke, and fire") — every other weapon
// keeps the original generic flash unchanged.

import { worldToCanvas, type Viewport } from './coords';
import type { Point } from '../../protocol/types';

export const PROJECTILE_ANIMATION_DURATION_MS = 1200;
/** How long the shared generic post-impact flash lingers, for every weapon except Nuke. */
const IMPACT_FLASH_DURATION_MS = 250;
/** Nuke's fireball flash duration — longer than the generic flash so it reads as more dramatic. */
const NUKE_FLASH_DURATION_MS = 700;
/** Nuke's smoke puffs outlive the fireball flash; the whole post-impact effect must stay mounted this long. */
const NUKE_EFFECT_DURATION_MS = 1500;
const NUKE_SMOKE_PUFF_COUNT = 7;

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

/** Deterministic per-(seed) pseudo-random in [0,1) — stable across animation frames, varies per puff/impact so puffs don't all move identically. */
function seededRandom(seed: number): number {
	const x = Math.sin(seed) * 10000;
	return x - Math.floor(x);
}

function drawGenericFlash(ctx: CanvasRenderingContext2D, x: number, y: number, flashProgress: number): void {
	const radius = 6 + flashProgress * 20;
	ctx.beginPath();
	ctx.fillStyle = `rgba(255, 160, 40, ${1 - flashProgress})`;
	ctx.arc(x, y, radius, 0, Math.PI * 2);
	ctx.fill();
}

function drawNukeEffect(ctx: CanvasRenderingContext2D, x: number, y: number, flashElapsedMs: number, seed: number): void {
	// Fireball: bigger/longer than the generic flash, with a radial gradient
	// (bright core -> orange/red -> dark edge) instead of a flat fill.
	if (flashElapsedMs <= NUKE_FLASH_DURATION_MS) {
		const flashProgress = flashElapsedMs / NUKE_FLASH_DURATION_MS;
		const radius = 10 + flashProgress * 60;
		const alpha = 1 - flashProgress;
		const gradient = ctx.createRadialGradient(x, y, 0, x, y, radius);
		gradient.addColorStop(0, `rgba(255, 245, 220, ${alpha})`);
		gradient.addColorStop(0.35, `rgba(255, 170, 40, ${alpha})`);
		gradient.addColorStop(0.7, `rgba(220, 70, 20, ${alpha * 0.85})`);
		gradient.addColorStop(1, `rgba(40, 15, 10, 0)`);
		ctx.beginPath();
		ctx.fillStyle = gradient;
		ctx.arc(x, y, radius, 0, Math.PI * 2);
		ctx.fill();
	}

	// Smoke: a handful of puffs, each spawning at a slightly staggered delay,
	// drifting upward (canvas y decreases = up) and fading out. Offsets are
	// seeded off the impact point + puff index so they're stable frame to
	// frame but vary explosion to explosion.
	for (let i = 0; i < NUKE_SMOKE_PUFF_COUNT; i++) {
		const puffSeed = seed + i * 911;
		const spawnDelayMs = seededRandom(puffSeed) * 350;
		const puffAgeMs = flashElapsedMs - spawnDelayMs;
		const puffLifespanMs = 900 + seededRandom(puffSeed + 1) * 300;
		if (puffAgeMs <= 0 || puffAgeMs >= puffLifespanMs) continue;

		const puffProgress = puffAgeMs / puffLifespanMs;
		const lateralOffset = (seededRandom(puffSeed + 2) - 0.5) * 44;
		const driftSpeedPxPerMs = (14 + seededRandom(puffSeed + 3) * 10) / 1000;
		const puffX = x + lateralOffset * puffProgress;
		const puffY = y - puffAgeMs * driftSpeedPxPerMs;
		const puffRadius = 5 + puffProgress * 13;
		const puffAlpha = 0.5 * (1 - puffProgress);

		ctx.beginPath();
		ctx.fillStyle = `rgba(90, 88, 84, ${puffAlpha})`;
		ctx.arc(puffX, puffY, puffRadius, 0, Math.PI * 2);
		ctx.fill();
	}
}

export function drawProjectile(
	ctx: CanvasRenderingContext2D,
	trajectory: Point[],
	impacts: Point[],
	weaponId: string,
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

	// Post-flight impact effect(s), one per real detonation point.
	const flashElapsed = elapsedMs - PROJECTILE_ANIMATION_DURATION_MS;
	const isNuke = weaponId === 'nuke';
	const effectDuration = isNuke ? NUKE_EFFECT_DURATION_MS : IMPACT_FLASH_DURATION_MS;
	if (flashElapsed > effectDuration) return;

	for (const point of impacts) {
		const { x, y } = worldToCanvas(point.x, point.y, viewport);
		if (isNuke) {
			// Seed varies per impact point so multiple simultaneous nuke
			// effects (not expected in practice — Nuke is single-impact —
			// but handled generically since `impacts` is always a list)
			// don't look identical.
			drawNukeEffect(ctx, x, y, flashElapsed, point.x * 1000 + point.y * 37);
		} else {
			const flashProgress = flashElapsed / IMPACT_FLASH_DURATION_MS;
			if (flashProgress <= 1) {
				drawGenericFlash(ctx, x, y, flashProgress);
			}
		}
	}
}

export function isAnimationFinished(elapsedMs: number, weaponId?: string): boolean {
	const effectDuration = weaponId === 'nuke' ? NUKE_EFFECT_DURATION_MS : IMPACT_FLASH_DURATION_MS;
	return elapsedMs > PROJECTILE_ANIMATION_DURATION_MS + effectDuration;
}
