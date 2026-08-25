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

import { worldToCanvas, WORLD_WIDTH, type Viewport } from './coords';
import type { Point } from '../../protocol/types';

export const PROJECTILE_ANIMATION_DURATION_MS = 1200;
/** How long the shared generic post-impact flash lingers, for every weapon except Nuke. */
const IMPACT_FLASH_DURATION_MS = 250;
/** Bouncing Betty's bounce-hit spark — smaller/faster than the generic flash, to read as a graze, not an explosion. */
const BOUNCE_SPARK_DURATION_MS = 150;
/** Nuke's fireball flash duration — longer than the generic flash so it reads as more dramatic. */
const NUKE_FLASH_DURATION_MS = 700;
/** Nuke's smoke puffs outlive the fireball flash; the whole post-impact effect must stay mounted this long. */
const NUKE_EFFECT_DURATION_MS = 1500;
const NUKE_SMOKE_PUFF_COUNT = 7;
/** MIRV: how long children take to fall from the split point to their individual landing spots, after the shared climb-to-apex phase ends. */
const MIRV_FALL_DURATION_MS = 500;
/** Mirrors ProjectileSim.GRAVITY -- Match.java's MIRV children always simulate with gravityMultiplier=1.0, so this applies unscaled. Keep in sync by hand if either changes (same convention as Match.java's TANK_WORLD_HEIGHT/BARREL_LENGTH mirrors). */
const MIRV_FALL_GRAVITY = 220;

/** When the impact/flash phase begins for a given shot — normally right after the flight animation, but MIRV with multiple children gets an extra fall phase first so they're visibly seen landing separately, not just flashing at once. */
export function getImpactPhaseStartMs(weaponId: string, impactCount: number): number {
	if (weaponId === 'mirv' && impactCount > 1) {
		return PROJECTILE_ANIMATION_DURATION_MS + MIRV_FALL_DURATION_MS;
	}
	return PROJECTILE_ANIMATION_DURATION_MS;
}

/** Napalm's initial splash burst — wider than the generic flash, per PLAN.md 7.1 ("orange->dark red gradient, wider/longer-lived"). */
const NAPALM_FLASH_DURATION_MS = 900;
/** Flickering flame licks persist after the splash fades, so it reads as burning ground, not one blast. */
const NAPALM_EFFECT_DURATION_MS = 1400;
const NAPALM_FLAME_COUNT = 6;

// ProjectileSim.java lets a shot's x wrap around the map edge mid-flight
// (world is horizontally cyclic — see its "Screen wrap" comment), so two
// adjacent points in the raw/resampled path can jump from near WORLD_WIDTH
// to near 0 (or vice versa). A plain lerp across that jump swept the dot
// backward across almost the entire map in one resampled segment's time —
// looked exactly like "flew off the left edge, then shot backwards over the
// firer to the other side" (bug report, MIRV/Digger — anything with enough
// range to wrap). Fixed by lerping along the *shorter* wrapped path instead.
function lerpWrappedX(ax: number, bx: number, t: number): number {
	let dx = bx - ax;
	if (dx > WORLD_WIDTH / 2) dx -= WORLD_WIDTH;
	else if (dx < -WORLD_WIDTH / 2) dx += WORLD_WIDTH;
	const x = ax + dx * t;
	return ((x % WORLD_WIDTH) + WORLD_WIDTH) % WORLD_WIDTH;
}

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
	return { x: lerpWrappedX(a.x, b.x, t), y: a.y + (b.y - a.y) * t };
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

/** Bouncing Betty's bounce-hit "graze" spark — smaller and sharper/whiter than the generic explosion flash. */
function drawBounceSpark(ctx: CanvasRenderingContext2D, x: number, y: number, sparkProgress: number): void {
	const radius = 4 + sparkProgress * 6;
	ctx.beginPath();
	ctx.fillStyle = `rgba(255, 240, 210, ${1 - sparkProgress})`;
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

/** Napalm: a wide orange-to-dark-red splash burst, then flickering flame licks that persist and sway rather than smoothly fading — reads as burning ground, not a single blast. */
function drawNapalmEffect(ctx: CanvasRenderingContext2D, x: number, y: number, flashElapsedMs: number, seed: number): void {
	const overallFade = 1 - Math.max(0, (flashElapsedMs - NAPALM_EFFECT_DURATION_MS * 0.7) / (NAPALM_EFFECT_DURATION_MS * 0.3));

	if (flashElapsedMs <= NAPALM_FLASH_DURATION_MS) {
		const flashProgress = flashElapsedMs / NAPALM_FLASH_DURATION_MS;
		const radius = 12 + flashProgress * 50;
		const alpha = 1 - flashProgress * 0.7;
		const gradient = ctx.createRadialGradient(x, y, 0, x, y, radius);
		gradient.addColorStop(0, `rgba(255, 200, 90, ${alpha})`);
		gradient.addColorStop(0.4, `rgba(240, 110, 30, ${alpha * 0.9})`);
		gradient.addColorStop(0.75, `rgba(150, 30, 15, ${alpha * 0.7})`);
		gradient.addColorStop(1, `rgba(80, 10, 10, 0)`);
		ctx.beginPath();
		ctx.fillStyle = gradient;
		ctx.arc(x, y, radius, 0, Math.PI * 2);
		ctx.fill();
	}

	for (let i = 0; i < NAPALM_FLAME_COUNT; i++) {
		const flameSeed = seed + i * 577;
		const offsetX = (seededRandom(flameSeed) - 0.5) * 60;
		const flicker = 0.6 + 0.4 * Math.sin(flashElapsedMs / (70 + seededRandom(flameSeed + 1) * 40) + flameSeed);
		const sway = Math.sin(flashElapsedMs / 220 + flameSeed) * 4;
		const height = 14 + 10 * flicker;
		const flameX = x + offsetX + sway;
		const flameY = y - height * 0.4;
		const alpha = overallFade * (0.35 + 0.35 * flicker);
		const gradient = ctx.createRadialGradient(flameX, flameY, 0, flameX, flameY, height);
		gradient.addColorStop(0, `rgba(255, 220, 120, ${alpha})`);
		gradient.addColorStop(0.5, `rgba(240, 100, 30, ${alpha * 0.8})`);
		gradient.addColorStop(1, `rgba(120, 20, 10, 0)`);
		ctx.beginPath();
		ctx.fillStyle = gradient;
		ctx.ellipse(flameX, flameY, height * 0.4, height, 0, 0, Math.PI * 2);
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

	const impactPhaseStart = getImpactPhaseStartMs(weaponId, impacts.length);

	// MIRV fall phase: the shared trajectory only covers the climb to the
	// split point (children's individual flight paths were deliberately
	// dropped from ShotResolved.trajectory in an earlier rewind-glitch fix,
	// leaving nothing to visually connect the split to each child's landing
	// spot — per user feedback, "not seeing the falling part of the
	// children"). Each child gets its own dot falling from the split point
	// to its own impact along a real ballistic parabola under gravity —
	// not a straight-line lerp (the previous approach, which read as
	// "launching from a standstill in all directions" per a later bug
	// report: children never actually continue the parent's momentum on a
	// straight line, they arc). Since the client never receives each
	// child's actual launch velocity, this solves for the constant initial
	// velocity that reaches the known (impact.x, impact.y) at exactly
	// MIRV_FALL_DURATION_MS under real gravity — an exact fit at both
	// endpoints that still reads as a proper arc in between.
	if (weaponId === 'mirv' && impacts.length > 1 && elapsedMs <= impactPhaseStart) {
		const splitPoint = trajectory[trajectory.length - 1];
		if (!splitPoint) return;
		const tSec = Math.max(0, (elapsedMs - PROJECTILE_ANIMATION_DURATION_MS) / 1000);
		const totalSec = MIRV_FALL_DURATION_MS / 1000;
		for (const impact of impacts) {
			const vx0 = (impact.x - splitPoint.x) / totalSec;
			const vy0 = (impact.y - splitPoint.y - 0.5 * MIRV_FALL_GRAVITY * totalSec * totalSec) / totalSec;
			const worldX = splitPoint.x + vx0 * tSec;
			const worldY = splitPoint.y + vy0 * tSec + 0.5 * MIRV_FALL_GRAVITY * tSec * tSec;
			const { x, y } = worldToCanvas(worldX, worldY, viewport);
			ctx.beginPath();
			ctx.fillStyle = '#222';
			ctx.arc(x, y, 3, 0, Math.PI * 2);
			ctx.fill();
		}
		return;
	}

	// Post-flight impact effect(s), one per real detonation point.
	const flashElapsed = elapsedMs - impactPhaseStart;
	const isNuke = weaponId === 'nuke';
	const isNapalm = weaponId === 'napalm';
	const effectDuration = isNuke
		? NUKE_EFFECT_DURATION_MS
		: isNapalm
			? NAPALM_EFFECT_DURATION_MS
			: IMPACT_FLASH_DURATION_MS;
	if (flashElapsed > effectDuration) return;

	// Bouncing Betty: every impact point except the last is a bounce that
	// grazed a tank (a direct-hit-only graze, no blast radius — see
	// Match.BOUNCE_DAMAGE_FRACTION) and gets the smaller/faster spark; the
	// last point is always the real detonation (server-side ordering: bounce
	// points are added to `detonations` before the final impact) and gets
	// the normal flash, same as every other weapon.
	const isBouncingBetty = weaponId === 'bouncing_betty';

	impacts.forEach((point, index) => {
		const { x, y } = worldToCanvas(point.x, point.y, viewport);
		if (isNuke) {
			// Seed varies per impact point so multiple simultaneous nuke
			// effects (not expected in practice — Nuke is single-impact —
			// but handled generically since `impacts` is always a list)
			// don't look identical.
			drawNukeEffect(ctx, x, y, flashElapsed, point.x * 1000 + point.y * 37);
		} else if (isNapalm) {
			drawNapalmEffect(ctx, x, y, flashElapsed, point.x * 1000 + point.y * 37);
		} else if (isBouncingBetty && index < impacts.length - 1) {
			const sparkProgress = flashElapsed / BOUNCE_SPARK_DURATION_MS;
			if (sparkProgress <= 1) {
				drawBounceSpark(ctx, x, y, sparkProgress);
			}
		} else {
			const flashProgress = flashElapsed / IMPACT_FLASH_DURATION_MS;
			if (flashProgress <= 1) {
				drawGenericFlash(ctx, x, y, flashProgress);
			}
		}
	});
}

export function isAnimationFinished(elapsedMs: number, weaponId?: string, impactCount = 1): boolean {
	const effectDuration =
		weaponId === 'nuke'
			? NUKE_EFFECT_DURATION_MS
			: weaponId === 'napalm'
				? NAPALM_EFFECT_DURATION_MS
				: IMPACT_FLASH_DURATION_MS;
	const impactPhaseStart = getImpactPhaseStartMs(weaponId ?? '', impactCount);
	return elapsedMs > impactPhaseStart + effectDuration;
}
