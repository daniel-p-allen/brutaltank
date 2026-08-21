// Persistent per-player shield overlay (PLAN.md section 7.4). Unlike weapon
// explosion effects (one-shot flashes keyed to a ShotResolved), this draws
// every frame a player's activeShieldId is set — a standing dome, not a
// flash. Tinted with the player's own tank color (per feedback: "same
// colour as the tank, but transparent") rather than a fixed per-shield
// hue — shape/pulse still varies per shield type so the three read apart.

import { worldToCanvas, worldScale, type Viewport } from './coords';
import type { Player } from '../../protocol/types';

const TANK_WORLD_WIDTH = 30;
const TANK_WORLD_HEIGHT = 17;

function hexToRgb(hex: string): { r: number; g: number; b: number } {
	let h = hex.replace('#', '');
	if (h.length === 3) {
		h = h
			.split('')
			.map((c) => c + c)
			.join('');
	}
	const num = parseInt(h, 16);
	if (Number.isNaN(num)) return { r: 200, g: 200, b: 200 };
	return { r: (num >> 16) & 255, g: (num >> 8) & 255, b: num & 255 };
}

/** Absorb: soft glowing dome (graduated-capacity shield), player-colored. */
function drawAbsorbDome(ctx: CanvasRenderingContext2D, cx: number, cy: number, r: number, t: number, rgb: string): void {
	const pulse = 0.75 + 0.25 * Math.sin(t / 260);
	const gradient = ctx.createRadialGradient(cx, cy, r * 0.4, cx, cy, r);
	gradient.addColorStop(0, `rgba(${rgb}, ${0.05 * pulse})`);
	gradient.addColorStop(0.75, `rgba(${rgb}, ${0.12 * pulse})`);
	gradient.addColorStop(1, `rgba(${rgb}, ${0.35 * pulse})`);
	ctx.beginPath();
	ctx.fillStyle = gradient;
	ctx.arc(cx, cy, r, 0, Math.PI * 2);
	ctx.fill();
	ctx.beginPath();
	ctx.strokeStyle = `rgba(${rgb}, ${0.6 * pulse})`;
	ctx.lineWidth = 1.5;
	ctx.arc(cx, cy, r, 0, Math.PI * 2);
	ctx.stroke();
}

/** Deflect: tighter, harder-edged faceted shield, player-colored — reads as a "hard block," not soaking. */
function drawDeflectDome(ctx: CanvasRenderingContext2D, cx: number, cy: number, r: number, t: number, rgb: string): void {
	const pulse = 0.8 + 0.2 * Math.sin(t / 150);
	const facets = 8;
	ctx.beginPath();
	for (let i = 0; i <= facets; i++) {
		const angle = (i / facets) * Math.PI * 2;
		const px = cx + Math.cos(angle) * r;
		const py = cy + Math.sin(angle) * r * 0.95;
		if (i === 0) ctx.moveTo(px, py);
		else ctx.lineTo(px, py);
	}
	ctx.closePath();
	ctx.fillStyle = `rgba(${rgb}, ${0.1 * pulse})`;
	ctx.fill();
	ctx.strokeStyle = `rgba(${rgb}, ${0.75 * pulse})`;
	ctx.lineWidth = 1.5;
	ctx.stroke();
}

/** Reflect: same dome language as Absorb, slower pulse (the economy-integrated shield), player-colored. */
function drawReflectDome(ctx: CanvasRenderingContext2D, cx: number, cy: number, r: number, t: number, rgb: string): void {
	const pulse = 0.75 + 0.25 * Math.sin(t / 340);
	const gradient = ctx.createRadialGradient(cx, cy, r * 0.4, cx, cy, r);
	gradient.addColorStop(0, `rgba(${rgb}, ${0.05 * pulse})`);
	gradient.addColorStop(0.75, `rgba(${rgb}, ${0.1 * pulse})`);
	gradient.addColorStop(1, `rgba(${rgb}, ${0.32 * pulse})`);
	ctx.beginPath();
	ctx.fillStyle = gradient;
	ctx.arc(cx, cy, r, 0, Math.PI * 2);
	ctx.fill();
	ctx.beginPath();
	ctx.strokeStyle = `rgba(${rgb}, ${0.55 * pulse})`;
	ctx.lineWidth = 1.5;
	ctx.setLineDash([4, 3]);
	ctx.arc(cx, cy, r, 0, Math.PI * 2);
	ctx.stroke();
	ctx.setLineDash([]);
}

/** Draws every player's active shield overlay, if any. Called after drawTanks so shields sit above tank sprites. */
export function drawShields(ctx: CanvasRenderingContext2D, players: Player[], viewport: Viewport, elapsedMs: number): void {
	const { scaleX, scaleY } = worldScale(viewport);
	for (const player of players) {
		if (!player.tank.alive || !player.activeShieldId) continue;
		const { r, g, b } = hexToRgb(player.color || '#cccccc');
		const rgb = `${r}, ${g}, ${b}`;
		const w = TANK_WORLD_WIDTH * scaleX;
		const h = TANK_WORLD_HEIGHT * scaleY;
		const { x: cx, y: cyGround } = worldToCanvas(player.tank.x, player.tank.y, viewport);
		const cy = cyGround - h * 0.5;
		const radius = Math.max(w, h) * 1.15;

		if (player.activeShieldId === 'absorb_shield') {
			drawAbsorbDome(ctx, cx, cy, radius, elapsedMs, rgb);
		} else if (player.activeShieldId === 'deflect_shield') {
			drawDeflectDome(ctx, cx, cy, radius, elapsedMs, rgb);
		} else if (player.activeShieldId === 'reflect_shield') {
			drawReflectDome(ctx, cx, cy, radius, elapsedMs, rgb);
		}
	}
}
