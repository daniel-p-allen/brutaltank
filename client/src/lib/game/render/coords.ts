// World <-> canvas coordinate mapping.
//
// COORDINATE CONVENTION (integration risk — flag for the server side too):
// World coordinates use a canvas-style convention where **y grows
// downward**. `Terrain.heights[x]` is the world-space y-coordinate of the
// ground surface at that column (NOT a "height above the floor"). This
// reading is inferred from shared/protocol.md's MatchStateSync example,
// where a tank's `tank.y` (405) sits right next to the terrain height at
// its x (409 at x=120) — i.e. tank.y ~= terrain.heights[tank.x], which only
// makes sense if both are "distance down from the top of the world" rather
// than "height above the ground". The ShotResolved example's impact.y (388)
// sitting just above the surrounding terrain heights (~390) at that column
// range is consistent with the same reading.
//
// If the server implementation instead treats `heights` as height-above-floor
// (y grows upward, larger value = higher terrain), tanks will appear to sit
// on head instead of feet and the terrain will render upside down — this is
// the single highest-risk client/server assumption in the M1 slice and
// should be checked against the very first real MatchStateSync received
// from the real server.
//
// World size: width 1600 units (PLAN.md section 4.1). World height range is
// documented as clamped 80-550; we give some margin and treat the world as
// 0..700 tall for the canvas mapping.

export const WORLD_WIDTH = 1600;
export const WORLD_HEIGHT = 700;

export interface Viewport {
	canvasWidth: number;
	canvasHeight: number;
}

/** Maps a world-space point to canvas pixel coordinates for the given viewport. */
export function worldToCanvas(
	worldX: number,
	worldY: number,
	viewport: Viewport
): { x: number; y: number } {
	const scaleX = viewport.canvasWidth / WORLD_WIDTH;
	const scaleY = viewport.canvasHeight / WORLD_HEIGHT;
	return { x: worldX * scaleX, y: worldY * scaleY };
}

export function worldScale(viewport: Viewport): { scaleX: number; scaleY: number } {
	return {
		scaleX: viewport.canvasWidth / WORLD_WIDTH,
		scaleY: viewport.canvasHeight / WORLD_HEIGHT
	};
}
