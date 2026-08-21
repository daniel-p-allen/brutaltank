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
    void wrapsAroundWhenFiredPastTheLeftEdge() {
        Terrain terrain = flatTerrain(500); // width 1600, see flatTerrain()
        // Very flat, very fast shot fired left from near the edge -> exits world
        // bounds and should reappear on the right side rather than despawning.
        ProjectileSim.Result result = ProjectileSim.simulate(
                5, 400, 180, 90, 0, terrain, Collections.emptyList());

        assertTrue(result.impactX >= 0 && result.impactX < terrain.width(),
                "wrapped impact should stay within world bounds, got " + result.impactX);
        // Not pinned to which half it lands in (that depends on
        // ProjectileSim.POWER_SCALE, which this test shouldn't be coupled
        // to) — just prove it actually traveled past the edge and wrapped,
        // rather than despawning right at/near it.
        assertTrue(Math.abs(result.impactX - 5) > 50 && Math.abs(result.impactX - terrain.width()) > 50,
                "expected the shot to travel well past the edge and wrap around, got " + result.impactX);
    }

    @Test
    void resampledTrajectoryIsCompact() {
        Terrain terrain = flatTerrain(500);
        ProjectileSim.Result result = ProjectileSim.simulate(200, 400, 45, 60, 0, terrain, Collections.emptyList());

        assertTrue(result.resampledTrajectory.size() <= ProjectileSim.RESAMPLE_POINTS);
        assertTrue(result.resampledTrajectory.size() >= 2);
    }

    // -----------------------------------------------------------------
    // M3: weapon-behavior hooks (WeaponDef.Behavior)
    // -----------------------------------------------------------------

    @Test
    void tunnelingContinuesPastFirstTerrainHitUpToPenetrationCap() {
        Terrain standardTerrain = flatTerrain(500);
        ProjectileSim.Result standard = ProjectileSim.simulate(200, 400, 30, 60, 0, standardTerrain,
                Collections.emptyList(), com.brutaltank.domain.weapon.WeaponDef.Behavior.STANDARD, 1.0, 1.0, false);

        Terrain tunnelTerrain = flatTerrain(500);
        ProjectileSim.Result tunneling = ProjectileSim.simulate(200, 400, 30, 60, 0, tunnelTerrain,
                Collections.emptyList(), com.brutaltank.domain.weapon.WeaponDef.Behavior.TUNNELING, 1.0, 1.0, false);

        // Standard stops right at the terrain surface; tunneling keeps going
        // "underground" up to TUNNELING_MAX_PENETRATION further.
        assertTrue(tunneling.impactY > standard.impactY,
                "tunneling should penetrate deeper: standard=" + standard.impactY + " tunneling=" + tunneling.impactY);
        double penetration = tunneling.impactY - standard.impactY;
        // Tolerance is generous (not +5) because discretized stepping can
        // overshoot the cap by a whole step's vy*DT, and vy underground at
        // TUNNELING_MAX_PENETRATION=160 is large enough for that overshoot
        // to be tens of units, not a handful.
        assertTrue(penetration > 15.0 && penetration <= ProjectileSim.TUNNELING_MAX_PENETRATION + 30.0,
                "unexpected penetration depth: " + penetration);
    }

    @Test
    void bouncingFlatTierReachesFiveBounces() {
        // Redesigned mechanic (per user feedback: "it will always bounce...
        // between 3 and 5 bounces depending on angle"): a flat/skimming
        // first ground contact (<=25deg incidence) gets the 5-bounce
        // ceiling. A 15deg launch, close to the ground, lands at ~18deg
        // incidence -- flat tier.
        Terrain terrain = flatTerrain(500);
        ProjectileSim.Result result = ProjectileSim.simulate(50, 450, 15, 70, 0, terrain,
                Collections.emptyList(), com.brutaltank.domain.weapon.WeaponDef.Behavior.BOUNCING, 1.0, 1.0, false);

        assertEquals(5, result.bounceCount, "flat first contact should reach the 5-bounce ceiling");

        // A bouncing shot should travel farther than an equivalent standard shot
        // fired at the same shallow angle, since it skips instead of stopping.
        Terrain standardTerrain = flatTerrain(500);
        ProjectileSim.Result standard = ProjectileSim.simulate(50, 450, 15, 70, 0, standardTerrain,
                Collections.emptyList(), com.brutaltank.domain.weapon.WeaponDef.Behavior.STANDARD, 1.0, 1.0, false);
        assertTrue(result.impactX > standard.impactX,
                "bouncing shot should travel farther: bounced=" + result.impactX + " standard=" + standard.impactX);
    }

    @Test
    void bouncingModerateTierReachesFourBounces() {
        // A moderate arc (45deg launch, 100 units above ground) lands at
        // ~47deg incidence -- the middle tier (25 < incidence <= 60 -> 4).
        Terrain terrain = flatTerrain(500);
        ProjectileSim.Result result = ProjectileSim.simulate(200, 400, 45, 60, 0, terrain,
                Collections.emptyList(), com.brutaltank.domain.weapon.WeaponDef.Behavior.BOUNCING, 1.0, 1.0, false);

        assertEquals(4, result.bounceCount, "moderate first contact should reach the 4-bounce middle tier");
    }

    @Test
    void bouncingSteepTierAlwaysBouncesAtLeastThreeTimesAndTerminatesPromptly() {
        // Core of the redesign: even a steep, near-vertical, max-power shot
        // (previously unreachable under the old <35deg gate, since it would
        // never have bounced at all) must still bounce (the 3-bounce floor)
        // and must terminate well within the simulation's step cap -- this
        // is the direct regression test for the MAX_BOUNCE_VY clamp, which
        // exists specifically to keep a steep bounce's hangtime bounded.
        Terrain terrain = flatTerrain(500);
        ProjectileSim.Result result = ProjectileSim.simulate(200, 400, 85, 100, 0, terrain,
                Collections.emptyList(), com.brutaltank.domain.weapon.WeaponDef.Behavior.BOUNCING, 1.0, 1.0, false);

        assertEquals(3, result.bounceCount, "steep first contact should bounce exactly the 3-bounce floor");
        assertTrue(result.impactY >= 499 && result.impactY <= 510,
                "expected the shot to settle at ground level, got impactY=" + result.impactY);
        assertTrue(result.rawPath.size() < 1200,
                "expected the shot to terminate well within the MAX_STEPS cap, used " + result.rawPath.size() + " steps");
    }

    @Test
    void stopAtApexTerminatesWithNearZeroVerticalVelocity() {
        Terrain terrain = flatTerrain(500);
        ProjectileSim.Result apex = ProjectileSim.simulate(200, 400, 60, 55, 0, terrain,
                Collections.emptyList(), com.brutaltank.domain.weapon.WeaponDef.Behavior.STANDARD, 1.0, 1.0, true);

        assertTrue(apex.stoppedAtApex, "expected the shot to stop at its apex");
        assertTrue(apex.finalVy >= 0 && apex.finalVy < ProjectileSim.GRAVITY * ProjectileSim.DT,
                "apex vy should be ~0, was " + apex.finalVy);
        // Apex should be strictly above the launch height (smaller y == higher, since y grows downward).
        assertTrue(apex.impactY < 400, "apex should be above launch point: " + apex.impactY);
    }
}
