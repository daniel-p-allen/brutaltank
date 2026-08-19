// Draws tanks as simple colored shapes at their x,y per PLAN.md's M1 scope
// ("simple colored rectangles/triangles" as a pre-art-pass placeholder, real
// sprites land in M5). Destroyed (!alive) tanks are hidden.

import { worldToCanvas, worldScale, type Viewport } from './coords';
import type { Player } from '../../protocol/types';

// "Fractionally bigger" + a proper hull/turret silhouette instead of a bare
// rectangle, per user feedback on the M1/M2 placeholder look.
const TANK_WORLD_WIDTH = 30;
const TANK_WORLD_HEIGHT = 17;
const TURRET_RADIUS = TANK_WORLD_HEIGHT * 0.42;
// The barrel needs to visibly track the aim angle (drag the angle control,
// the barrel sweeps to match), so it's drawn as a long rectangle rotated by
// aimAngleDeg rather than a fixed-direction triangle. Longer per feedback.
const BARREL_LENGTH = TANK_WORLD_WIDTH * 1.15;
const BARREL_WIDTH = TANK_WORLD_HEIGHT * 0.22;
const DEFAULT_AIM_ANGLE_DEG = 45;

/**
 * @param aimAngleDeg Uses the same convention as the server's ProjectileSim
 *   (0deg = straight along +x/world-right, 90deg = straight up; the barrel
 *   direction is (cos(angle), -sin(angle)) in world space, matching how a
 *   shot's initial velocity is computed). Pass the live angle-control value
 *   for the local player's tank so the barrel visibly follows the slider;
 *   omitted for other players, who fall back to a neutral default until a
 *   later milestone threads through their last-fired angle.
 */
export function drawTank(
	ctx: CanvasRenderingContext2D,
	player: Player,
	viewport: Viewport,
	aimAngleDeg: number = DEFAULT_AIM_ANGLE_DEG
): void {
	if (!player.tank.alive) return;

	const { scaleX, scaleY } = worldScale(viewport);
	const w = TANK_WORLD_WIDTH * scaleX;
	const h = TANK_WORLD_HEIGHT * scaleY;
	const { x: cx, y: cy } = worldToCanvas(player.tank.x, player.tank.y, viewport);

	const bodyColor = player.color || '#ccc';
	const hullTop = cy - h;
	const turretCx = cx;
	const turretCy = hullTop;

	// Hull: rounded body sitting on the ground surface, wider at the base
	// (tracks) than the top for a less "bare rectangle" silhouette.
	ctx.fillStyle = bodyColor;
	ctx.beginPath();
	const r = Math.min(w, h) * 0.25;
	if (typeof (ctx as any).roundRect === 'function') {
		(ctx as any).roundRect(cx - w / 2, hullTop, w, h, r);
	} else {
		ctx.rect(cx - w / 2, hullTop, w, h);
	}
	ctx.fill();

	// Tracks: a darker strip along the base for a bit of shape read.
	ctx.fillStyle = 'rgba(0, 0, 0, 0.35)';
	ctx.fillRect(cx - w / 2, cy - h * 0.28, w, h * 0.28);

	// Turret: circle pivot the barrel rotates around.
	ctx.fillStyle = bodyColor;
	ctx.beginPath();
	ctx.arc(turretCx, turretCy, TURRET_RADIUS * scaleY, 0, Math.PI * 2);
	ctx.fill();

	// Barrel: long rectangle from the turret pivot outward at aimAngleDeg.
	const angleRad = (aimAngleDeg * Math.PI) / 180;
	// World-space direction (matches ProjectileSim's velocity convention),
	// then scaled independently in x/y like every other world->canvas draw.
	const dirX = Math.cos(angleRad) * scaleX;
	const dirY = -Math.sin(angleRad) * scaleY;
	const barrelLen = BARREL_LENGTH * Math.max(scaleX, scaleY);
	const dirLen = Math.hypot(dirX, dirY) || 1;
	const ndx = dirX / dirLen;
	const ndy = dirY / dirLen;
	const canvasAngle = Math.atan2(ndy, ndx);

	ctx.save();
	ctx.translate(turretCx, turretCy);
	ctx.rotate(canvasAngle);
	ctx.fillStyle = '#333';
	ctx.fillRect(0, -(BARREL_WIDTH * scaleY) / 2, barrelLen, BARREL_WIDTH * scaleY);
	ctx.restore();

	// Shield indicator (nice-to-have): a faint ring if activeShieldId is set.
	if (player.activeShieldId) {
		ctx.beginPath();
		ctx.strokeStyle = 'rgba(120, 200, 255, 0.8)';
		ctx.lineWidth = 2;
		ctx.arc(cx, cy - h / 2, Math.max(w, h), 0, Math.PI * 2);
		ctx.stroke();
	}

	// Health bar.
	const barW = w * 1.3;
	const barH = 3;
	const barX = cx - barW / 2;
	const barY = cy - h - h * 0.9 - 10;
	ctx.fillStyle = '#222';
	ctx.fillRect(barX, barY, barW, barH);
	ctx.fillStyle = player.tank.health > 30 ? '#3ecf5f' : '#e0403f';
	ctx.fillRect(barX, barY, barW * Math.max(0, player.tank.health) / 100, barH);
}

export function drawTanks(
	ctx: CanvasRenderingContext2D,
	players: Player[],
	viewport: Viewport,
	localPlayerId?: string | null,
	localAimAngleDeg?: number
): void {
	for (const player of players) {
		const aim =
			localPlayerId != null && player.playerId === localPlayerId && localAimAngleDeg != null
				? localAimAngleDeg
				: undefined;
		drawTank(ctx, player, viewport, aim);
	}
}
