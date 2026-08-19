// Draws tanks as simple colored rectangles/triangles at their x,y per
// PLAN.md's M1 scope ("simple colored rectangles/triangles"). Destroyed
// (!alive) tanks are hidden.

import { worldToCanvas, worldScale, type Viewport } from './coords';
import type { Player } from '../../protocol/types';

const TANK_WORLD_WIDTH = 24;
const TANK_WORLD_HEIGHT = 14;

export function drawTank(
	ctx: CanvasRenderingContext2D,
	player: Player,
	viewport: Viewport
): void {
	if (!player.tank.alive) return;

	const { scaleX, scaleY } = worldScale(viewport);
	const w = TANK_WORLD_WIDTH * scaleX;
	const h = TANK_WORLD_HEIGHT * scaleY;
	const { x: cx, y: cy } = worldToCanvas(player.tank.x, player.tank.y, viewport);

	// Body: rectangle sitting on top of (x,y), since y is the ground surface.
	ctx.fillStyle = player.color || '#ccc';
	ctx.fillRect(cx - w / 2, cy - h, w, h);

	// Barrel: small triangle pointing up-ish, just a visual placeholder.
	ctx.beginPath();
	ctx.moveTo(cx, cy - h);
	ctx.lineTo(cx + w * 0.35, cy - h - h * 0.8);
	ctx.lineTo(cx + w * 0.55, cy - h - h * 0.6);
	ctx.closePath();
	ctx.fillStyle = '#333';
	ctx.fill();

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
	const barY = cy - h - h * 0.9 - 6;
	ctx.fillStyle = '#222';
	ctx.fillRect(barX, barY, barW, barH);
	ctx.fillStyle = player.tank.health > 30 ? '#3ecf5f' : '#e0403f';
	ctx.fillRect(barX, barY, barW * Math.max(0, player.tank.health) / 100, barH);
}

export function drawTanks(
	ctx: CanvasRenderingContext2D,
	players: Player[],
	viewport: Viewport
): void {
	for (const player of players) drawTank(ctx, player, viewport);
}
