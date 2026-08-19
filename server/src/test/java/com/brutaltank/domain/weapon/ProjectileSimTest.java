package com.brutaltank.domain.weapon;

import com.brutaltank.domain.terrain.Terrain;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectileSimTest {

    private static Terrain flatTerrain(int height) {
        int[] heights = new int[1600];
        java.util.Arrays.fill(heights, height);
        return new Terrain(heights);
    }

    @Test
    void firingStraightUpLandsNearLaunchX() {
        Terrain terrain = flatTerrain(500);
        ProjectileSim.Result result = ProjectileSim.simulate(
                200, 400, 90, 40, 0, terrain, Collections.emptyList());

        assertEquals(200, result.impactX, 2.0);
        assertTrue(result.impactY >= 499); // landed on/near the ground
    }

    @Test
    void higherPowerTravelsFartherAtSameAngle() {
        Terrain terrain = flatTerrain(500);
        ProjectileSim.Result low = ProjectileSim.simulate(200, 400, 45, 30, 0, terrain, Collections.emptyList());
        ProjectileSim.Result high = ProjectileSim.simulate(200, 400, 45, 80, 0, terrain, Collections.emptyList());

        double lowDist = Math.abs(low.impactX - 200);
        double highDist = Math.abs(high.impactX - 200);
        assertTrue(highDist > lowDist, "higher power should travel farther: low=" + lowDist + " high=" + highDist);
    }

    @Test
    void positiveWindPushesImpactFartherInPositiveX() {
        Terrain terrain = flatTerrain(500);
        ProjectileSim.Result noWind = ProjectileSim.simulate(200, 400, 45, 50, 0, terrain, Collections.emptyList());
        ProjectileSim.Result withWind = ProjectileSim.simulate(200, 400, 45, 50, 20, terrain, Collections.emptyList());

        assertTrue(withWind.impactX > noWind.impactX,
                "wind should push impact farther right: noWind=" + noWind.impactX + " withWind=" + withWind.impactX);
    }

    @Test
    void detectsTankHitAtImpactPoint() {
        Terrain terrain = flatTerrain(500);

        // First find where this shot naturally lands on flat terrain (no targets).
        ProjectileSim.Result noTarget = ProjectileSim.simulate(
                200, 400, 30, 60, 0, terrain, Collections.emptyList());

        // A tank sitting exactly at that landing point must register as a hit
        // (tank checks run before the terrain check in the same simulation step).
        List<ProjectileSim.TankTarget> targets = List.of(
                new ProjectileSim.TankTarget("p-2", noTarget.impactX, noTarget.impactY));

        ProjectileSim.Result result = ProjectileSim.simulate(
                200, 400, 30, 60, 0, terrain, targets);

        assertEquals("p-2", result.hitPlayerId);
    }

    @Test
    void terminatesWhenFiredOutOfBounds() {
        Terrain terrain = flatTerrain(500);
        // Very flat, very fast shot fired left from near the edge -> exits world bounds quickly.
        ProjectileSim.Result result = ProjectileSim.simulate(
                5, 400, 180, 90, 0, terrain, Collections.emptyList());

        assertTrue(result.impactX < 5, "expected the shot to exit the left edge");
    }

    @Test
    void resampledTrajectoryIsCompact() {
        Terrain terrain = flatTerrain(500);
        ProjectileSim.Result result = ProjectileSim.simulate(200, 400, 45, 60, 0, terrain, Collections.emptyList());

        assertTrue(result.resampledTrajectory.size() <= ProjectileSim.RESAMPLE_POINTS);
        assertTrue(result.resampledTrajectory.size() >= 2);
    }
}
