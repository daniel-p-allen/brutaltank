import { describe, it, expect } from 'vitest';
import { pointAtProgress, shortestWrapDx } from './projectileRenderer';
import { WORLD_WIDTH } from './coords';

// Regression test for the MIRV/Digger "flies backwards across the map" bug
// (live-playtest report, 2026-08-25): ProjectileSim.java lets a shot's x
// wrap around the cyclic map edge mid-flight, but pointAtProgress used to
// lerp x in a straight line between two resampled points regardless of the
// wrap, sweeping the rendered dot backward across almost the whole map in
// one segment's time. Fixed by lerping along the shorter wrapped path.
describe('pointAtProgress', () => {
	it('does not sweep backward across the map when the trajectory wraps near the right edge', () => {
		const trajectory = [
			{ x: WORLD_WIDTH - 10, y: 100 },
			{ x: 10, y: 100 } // wrapped: this is actually WORLD_WIDTH - 10 + 20
		];
		const mid = pointAtProgress(trajectory, 0.5);
		// The true wrapped midpoint is WORLD_WIDTH - 10 + 10 = WORLD_WIDTH, i.e. 0.
		expect(mid?.x).toBeCloseTo(0, 5);
	});

	it('does not sweep backward across the map when the trajectory wraps near the left edge', () => {
		const trajectory = [
			{ x: 10, y: 100 },
			{ x: WORLD_WIDTH - 10, y: 100 } // wrapped: this is actually 10 - 20
		];
		const mid = pointAtProgress(trajectory, 0.5);
		// The true wrapped midpoint is 10 - 10 = 0.
		expect(mid?.x).toBeCloseTo(0, 5);
	});

	it('still lerps normally for a non-wrapping segment', () => {
		const trajectory = [
			{ x: 100, y: 100 },
			{ x: 200, y: 200 }
		];
		const mid = pointAtProgress(trajectory, 0.5);
		expect(mid?.x).toBeCloseTo(150, 5);
		expect(mid?.y).toBeCloseTo(150, 5);
	});
});

// Regression test for a second instance of the same wrap bug, found via
// live playtest (2026-08-25): "MIRV... once the split happens... things can
// literally go backwards counter to physics." The MIRV fall-phase block in
// drawProjectile solves each child's constant fall velocity from a raw
// (impact.x - splitPoint.x) subtraction -- shortestWrapDx (factored out of
// lerpWrappedX, and now used by that solve) fixes it the same way.
describe('shortestWrapDx', () => {
	it('picks the short way around when the raw difference would be a huge backward jump', () => {
		// Split point near the right edge, child impact just past the left
		// edge (wrapped) -- the raw difference (10 - (WORLD_WIDTH - 10)) is a
		// huge negative number implying the child flew almost the entire map
		// width backwards. The true distance traveled is only 20 units
		// forward (through the wrap).
		const dx = shortestWrapDx(WORLD_WIDTH - 10, 10);
		expect(dx).toBeCloseTo(20, 5);
	});

	it('picks the short way around the other direction too', () => {
		const dx = shortestWrapDx(10, WORLD_WIDTH - 10);
		expect(dx).toBeCloseTo(-20, 5);
	});

	it('is a plain difference for a non-wrapping pair', () => {
		expect(shortestWrapDx(100, 250)).toBeCloseTo(150, 5);
	});
});
