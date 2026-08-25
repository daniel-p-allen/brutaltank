import { describe, it, expect } from 'vitest';
import { computeTrajectoryPreview } from './trajectoryPreview';

// Regression test for a real bug (live playtest, 2026-08-25): this file's
// POWER_SCALE constant must be hand-kept in sync with the server's
// ProjectileSim.POWER_SCALE. During the 2026-08-25 physics retune the
// server value dropped 12.0 -> 9.0 but this client constant was never
// updated, so the preview overestimated every shot's power by 33% and
// always showed farther than the real, now-weaker shot could reach
// ("Baby Missile + favorable wind, the real shot landed short of the
// preview line"). Pins the preview's landing point against the same
// analytic vacuum-ballistics formula the server's constants imply, so a
// future POWER_SCALE drift here would fail loudly instead of silently.
describe('computeTrajectoryPreview', () => {
	it('matches the analytic range implied by the current server POWER_SCALE (9.0), not the stale 12.0', () => {
		const GRAVITY = 220.0;
		const SERVER_POWER_SCALE = 9.0; // ProjectileSim.POWER_SCALE, server/.../ProjectileSim.java
		const angleDeg = 45;
		const power = 60;
		const originX = 200;
		const originY = 500;

		// Flat terrain so the shot lands exactly where the vacuum arc predicts.
		const terrainHeights = new Array(1600).fill(500);

		const angleRad = (angleDeg * Math.PI) / 180;
		const v0 = power * SERVER_POWER_SCALE;
		const vx = Math.cos(angleRad) * v0;
		const vy0 = Math.sin(angleRad) * v0;
		const hangtime = (2 * vy0) / GRAVITY;
		const expectedRange = vx * hangtime;

		const points = computeTrajectoryPreview(originX, originY, angleDeg, power, terrainHeights);
		const landing = points[points.length - 1];

		// Generous tolerance: the preview steps at DT=1/30 (coarser than the
		// server's 1/60) and only checks ground contact once per step, so it
		// can overshoot the exact analytic landing point by a step's worth
		// of horizontal travel.
		expect(landing.x - originX).toBeGreaterThan(expectedRange * 0.85);
		expect(landing.x - originX).toBeLessThan(expectedRange * 1.15);

		// The old, stale POWER_SCALE=12.0 would have landed ~78% farther
		// than the correct answer (12^2 / 9^2 = 1.78) -- assert we're
		// nowhere near that, to make sure this test would actually have
		// caught the regression.
		const staleRange = expectedRange * (12.0 / 9.0) ** 2;
		expect(landing.x - originX).toBeLessThan((expectedRange + staleRange) / 2);
	});
});
