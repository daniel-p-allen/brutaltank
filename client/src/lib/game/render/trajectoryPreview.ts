// Opt-in "Trajectory Help" dotted preview (per user request, 2026-08-22;
// refined same day — "that trajectory does need to take into consideration
// weight"): computes a ballistic arc using the selected weapon's real
// gravityMultiplier/powerScaleMultiplier (see WEAPON_PHYSICS below, mirroring
// server WeaponDef.java), but still ignores wind and homing/bounce/tunnel
// behavior — so it's a rough guide, not a solved aim. The real shot
// (ProjectileSim.java, server-authoritative) can and will land somewhere
// else; that's by design (per user: "it can not be 100 percent accurate
// otherwise it is not fun").
//
// Physics constants mirror ProjectileSim.java's GRAVITY/POWER_SCALE/DT
// exactly — same convention: angleDeg 0 = +x/world-right, 90 = straight up;
// y grows downward.

import { worldToCanvas, type Viewport } from './coords';

const GRAVITY = 220.0;
const POWER_SCALE = 12.0;
const DT = 1 / 30; // coarser than the server's 1/60 — a preview, not a resolver
const MAX_STEPS = 600; // 20s of flight time, matching ProjectileSim's own safety cap

export interface PreviewPoint {
	x: number;
	y: number;
}

/**
 * Per-weapon physics mirror of server WeaponDef.java's gravityMultiplier/
 * powerScaleMultiplier (kept in sync by hand, same as WEAPON_CATALOG's
 * weightClass already is — weapon stats aren't sent over the wire). Only the
 * fields the idealized preview actually uses; unlisted weapons (and shields,
 * which are never thrown) fall back to 1.0/1.0 via computeTrajectoryPreview's
 * defaults.
 */
export const WEAPON_PHYSICS: Record<string, { powerScaleMultiplier: number; gravityMultiplier: number }> = {
	basic_shell: { powerScaleMultiplier: 1.0, gravityMultiplier: 1.0 },
	baby_missile: { powerScaleMultiplier: 1.15, gravityMultiplier: 0.85 },
	heavy_cannonball: { powerScaleMultiplier: 0.85, gravityMultiplier: 1.2 },
	mirv: { powerScaleMultiplier: 1.0, gravityMultiplier: 1.0 },
	napalm: { powerScaleMultiplier: 1.0, gravityMultiplier: 1.0 },
	tunneling_shot: { powerScaleMultiplier: 1.0, gravityMultiplier: 1.0 },
	bouncing_betty: { powerScaleMultiplier: 1.0, gravityMultiplier: 1.0 },
	cluster_bomb: { powerScaleMultiplier: 1.0, gravityMultiplier: 1.0 },
	digger: { powerScaleMultiplier: 1.0, gravityMultiplier: 1.15 },
	nuke: { powerScaleMultiplier: 1.0, gravityMultiplier: 1.25 }
};

/**
 * Returns sample points along the arc from (originX, originY) until it
 * reaches terrain height at its column or leaves the world horizontally.
 * `terrainHeights[x]` follows the same convention as everywhere else in the
 * client: the world-space y-coordinate of the ground surface at column x.
 */
export function computeTrajectoryPreview(
	originX: number,
	originY: number,
	angleDeg: number,
	power: number,
	terrainHeights: number[],
	powerScaleMultiplier = 1.0,
	gravityMultiplier = 1.0
): PreviewPoint[] {
	const angleRad = (angleDeg * Math.PI) / 180;
	const v0 = power * POWER_SCALE * powerScaleMultiplier;
	const gravity = GRAVITY * gravityMultiplier;
	let x = originX;
	let y = originY;
	let vx = Math.cos(angleRad) * v0;
	let vy = -Math.sin(angleRad) * v0;

	const points: PreviewPoint[] = [{ x, y }];
	const width = terrainHeights.length;

	for (let step = 0; step < MAX_STEPS; step++) {
		vy += gravity * DT;
		x += vx * DT;
		y += vy * DT;

		if (x < 0 || x >= width) break; // preview doesn't screen-wrap — off-map is off-preview

		const groundY = terrainHeights[Math.round(x)];
		points.push({ x, y });
		if (y >= groundY) break;
	}

	return points;
}

const DOT_SPACING_POINTS = 3; // every 3rd sample point — "spread out", per user request
const DOT_RADIUS_PX = 2;

/** Renders the preview as a spread-out dotted line, not a solid path — a rough guide, not a locked-in aim. */
export function drawTrajectoryPreview(
	ctx: CanvasRenderingContext2D,
	points: PreviewPoint[],
	viewport: Viewport
): void {
	ctx.fillStyle = 'rgba(255, 255, 255, 0.55)';
	for (let i = 0; i < points.length; i += DOT_SPACING_POINTS) {
		const { x, y } = worldToCanvas(points[i].x, points[i].y, viewport);
		ctx.beginPath();
		ctx.arc(x, y, DOT_RADIUS_PX, 0, Math.PI * 2);
		ctx.fill();
	}
}
