// Draws the terrain heightmap as a simple filled polygon down to the bottom
// of the canvas. No procedural texture yet — that's M5 per PLAN.md section
// 3.3/5.

import { worldToCanvas, type Viewport } from './coords';

export function drawTerrain(
	ctx: CanvasRenderingContext2D,
	heights: number[],
	viewport: Viewport
): void {
	if (heights.length === 0) return;

	ctx.beginPath();
	ctx.moveTo(0, viewport.canvasHeight);

	for (let x = 0; x < heights.length; x++) {
		const { x: cx, y: cy } = worldToCanvas(x, heights[x], viewport);
		ctx.lineTo(cx, cy);
	}

	const last = worldToCanvas(heights.length - 1, heights[heights.length - 1], viewport);
	ctx.lineTo(last.x, viewport.canvasHeight);
	ctx.closePath();

	ctx.fillStyle = '#4a7c3f';
	ctx.fill();
}
