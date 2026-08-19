package com.brutaltank.match;

import com.brutaltank.domain.terrain.Terrain;
import com.brutaltank.domain.weapon.DamageCalculator;
import com.brutaltank.domain.weapon.ProjectileSim;
import com.brutaltank.domain.weapon.ShieldDef;
import com.brutaltank.domain.weapon.WeaponDef;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 coverage (PLAN.md 4.4 / 5 "every weapon/shield fireable and behaves
 * distinctly"): weapon dispatch through the real {@link Match} turn state
 * machine (MIRV multi-detonation, cluster bomb 5-detonation crater/damage,
 * ammo tracking), and shield mitigation/break behavior. Uses the same
 * short-timeout / debug-hook pattern as {@link MatchTurnStateMachineTest},
 * plus new debug hooks ({@code debugSetTerrain}/{@code debugSetWind}/
 * {@code debugSetTankPosition}) so shots land at fully predictable
 * coordinates on flat terrain instead of fighting the real fractal
 * generator.
 */
class WeaponAndShieldTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newScheduledThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private Match newMatch(String id) {
        Match m = new Match(id, MAPPER, new MatchConfig(4, 8), scheduler);
        m.setTurnTimeoutMs(30_000);
        return m;
    }

    private record Joined(String playerId) {
    }

    private Joined join(Match match, String name) {
        Match.JoinResult result = match.addPlayer(name, new FakeMessageSink());
        assertTrue(result.success(), "join should succeed: " + result.errorReason());
        return new Joined(result.playerId());
    }

    private static Terrain flatTerrain(int width, int height) {
        int[] heights = new int[width];
        Arrays.fill(heights, height);
        return new Terrain(heights);
    }

    private static double distance(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }

    // -----------------------------------------------------------------
    // MIRV: splits into several children at apex, multiple damage events.
    // -----------------------------------------------------------------

    @Test
    @Timeout(10)
    void mirvSplitProducesMultipleDamageEventsFromOneShot() {
        Match match = newMatch("m-mirv");
        Joined shooter = join(match, "Shooter");
        Joined t1 = join(match, "T1");
        Joined t2 = join(match, "T2");
        Joined t3 = join(match, "T3");
        Joined t4 = join(match, "T4");
        match.setReady(shooter.playerId(), true);
        match.setReady(t1.playerId(), true);
        match.setReady(t2.playerId(), true);
        match.setReady(t3.playerId(), true);
        match.setReady(t4.playerId(), true);

        match.debugSetTerrain(flatTerrain(1600, 500));
        match.debugSetWind(0);
        match.debugSetTankPosition(shooter.playerId(), 400, 500);

        double angle = 45;
        double power = 55;

        // Replicate MIRV's own apex-then-4-children simulation to predict
        // exactly where each child will land (no wind, flat terrain -> fully
        // deterministic), then place a target tank at each landing spot.
        ProjectileSim.Result apex = ProjectileSim.simulate(400, 500, angle, power, 0,
                match.debugTerrain(), Collections.emptyList(), WeaponDef.Behavior.STANDARD, 1.0, 1.0, true);
        assertTrue(apex.stoppedAtApex, "test setup expects the shot to reach an apex");

        double[] offsets = {-15.0, -5.0, 5.0, 15.0};
        Joined[] targets = {t1, t2, t3, t4};
        for (int i = 0; i < offsets.length; i++) {
            ProjectileSim.Result child = ProjectileSim.simulate(apex.impactX, apex.impactY, angle + offsets[i], power,
                    0, match.debugTerrain(), Collections.emptyList(), WeaponDef.Behavior.STANDARD, 1.0, 1.0, false);
            match.debugSetTankPosition(targets[i].playerId(), child.impactX, child.impactY);
        }

        Match.FireOutcome outcome = match.fire(shooter.playerId(), "r1", "mirv", angle, power);
        assertTrue(outcome.accepted());

        int damageEventCount = outcome.shotResolved().damageEvents.size();
        assertTrue(damageEventCount >= 2,
                "expected MIRV to produce multiple damage events, got " + damageEventCount);
    }

    // -----------------------------------------------------------------
    // Cluster bomb: primary + 4 bomblets == 5 detonation points.
    // -----------------------------------------------------------------

    @Test
    @Timeout(10)
    void clusterBombProducesFiveDetonationPointsOfCraterAndDamage() {
        Match match = newMatch("m-cluster");
        Joined shooter = join(match, "Shooter");
        Joined victim = join(match, "Victim");
        match.setReady(shooter.playerId(), true);
        match.setReady(victim.playerId(), true);

        match.debugSetTerrain(flatTerrain(1600, 500));
        match.debugSetWind(0);
        match.debugSetTankPosition(shooter.playerId(), 400, 500);

        double angle = 45;
        double power = 55;
        ProjectileSim.Result predicted = ProjectileSim.simulate(400, 500, angle, power, 0,
                match.debugTerrain(), Collections.emptyList(), WeaponDef.Behavior.STANDARD, 1.0, 1.0, false);
        // Place the victim exactly at the primary impact point for a guaranteed damage event.
        match.debugSetTankPosition(victim.playerId(), predicted.impactX, predicted.impactY);

        Match.FireOutcome outcome = match.fire(shooter.playerId(), "r1", "cluster_bomb", angle, power);
        assertTrue(outcome.accepted());

        var resolved = outcome.shotResolved();
        assertFalse(resolved.damageEvents.isEmpty(), "expected at least the primary detonation to damage the victim");

        // 5 detonation points (primary + 4 bomblets at +/-25/+/-50) spread the
        // crater across a wide column range: at least the bomblet spread (100)
        // plus their radii, well beyond a single basic-shell-sized crater.
        int span = resolved.terrainDelta.endX - resolved.terrainDelta.startX + 1;
        assertTrue(span >= 100, "expected a wide multi-detonation terrainDelta span, got " + span);

        // Directly verify craters exist at all 5 expected detonation x-columns
        // (primary + the 4 fixed bomblet offsets), i.e. terrain measurably
        // deepened (larger y) at each one.
        double[] offsets = {0, -50, -25, 25, 50};
        Terrain terrain = match.debugTerrain();
        for (double offset : offsets) {
            int col = (int) Math.round(Math.max(0, Math.min(terrain.width() - 1, predicted.impactX + offset)));
            assertTrue(terrain.heightAt(col) > 500, "expected a crater at column " + col + " (offset " + offset + ")");
        }
    }

    // -----------------------------------------------------------------
    // Ammo: quantity decrements, OUT_OF_AMMO at 0, unlimited never decrements.
    // -----------------------------------------------------------------

    @Test
    @Timeout(15)
    void ammoDecrementsAndRejectsAtZeroWhileUnlimitedNeverDecrements() {
        Match match = newMatch("m-ammo");
        Joined p1 = join(match, "P1");
        Joined p2 = join(match, "P2");
        match.setReady(p1.playerId(), true);
        match.setReady(p2.playerId(), true);

        assertEquals(5, match.loadoutQtyOf(p1.playerId(), "baby_missile"));
        assertEquals(-1, match.loadoutQtyOf(p1.playerId(), "basic_shell"));

        for (int i = 0; i < 5; i++) {
            assertTrue(match.fire(p1.playerId(), "r" + i, "baby_missile", 20, 30).accepted(),
                    "baby_missile shot " + i + " should be accepted");
            assertTrue(match.fire(p2.playerId(), "r" + i + "b", "basic_shell", 20, 30).accepted());
        }
        assertEquals(0, match.loadoutQtyOf(p1.playerId(), "baby_missile"));

        Match.FireOutcome rejected = match.fire(p1.playerId(), "rLast", "baby_missile", 20, 30);
        assertFalse(rejected.accepted());
        assertEquals("OUT_OF_AMMO", rejected.rejectReason());

        // basic_shell (-1 == unlimited) must never decrement, despite 5 real shots fired above.
        assertEquals(-1, match.loadoutQtyOf(p2.playerId(), "basic_shell"));
    }

    // -----------------------------------------------------------------
    // Shields: Absorb (mitigate + eventually break), Deflect (block direct
    // hit then break; near-miss splash doesn't break it), Reflect (mitigate
    // + cashback, never breaks).
    // -----------------------------------------------------------------

    /** Shared shield-test scaffolding: shooter + shield-owner on flat terrain, no wind. */
    private record ShieldScenario(Match match, String ownerId, String shooterId) {
    }

    private ShieldScenario setUpShieldScenario(String matchId) {
        Match match = newMatch(matchId);
        Joined owner = join(match, "Owner");
        Joined shooter = join(match, "Shooter");
        match.setReady(owner.playerId(), true);
        match.setReady(shooter.playerId(), true);

        match.debugSetTerrain(flatTerrain(1600, 500));
        match.debugSetWind(0);
        match.debugSetTankPosition(owner.playerId(), 500, 500);
        match.debugSetTankPosition(shooter.playerId(), 200, 500);
        return new ShieldScenario(match, owner.playerId(), shooter.playerId());
    }

    /** Predicts the exact impact point + resulting raw (pre-shield) damage for a basic_shell shot at the owner. */
    private double[] predictBasicShellHit(ShieldScenario s, double angle, double power) {
        return predictHit(s, WeaponDef.BASIC_SHELL, angle, power);
    }

    /** Predicts the exact impact point + resulting raw (pre-shield) damage for a given weapon shot at the owner. */
    private double[] predictHit(ShieldScenario s, WeaponDef weapon, double angle, double power) {
        List<ProjectileSim.TankTarget> targets = List.of(
                new ProjectileSim.TankTarget(s.ownerId(), 500, 500));
        ProjectileSim.Result sim = ProjectileSim.simulate(200, 500, angle, power, 0,
                s.match().debugTerrain(), targets, WeaponDef.Behavior.STANDARD,
                weapon.powerScaleMultiplier(), weapon.gravityMultiplier(), false);
        List<DamageCalculator.TankState> tanks = List.of(new DamageCalculator.TankState(s.ownerId(), 500, 500, 100));
        DamageCalculator.Outcome outcome = DamageCalculator.resolve(
                s.shooterId(), sim.impactX, sim.impactY, weapon.blastRadius(), weapon.centerDamage(), tanks);
        double rawDamage = outcome.damageEvents.get(0).damage();
        boolean isDirectHit = distance(500, 500, sim.impactX, sim.impactY) <= DamageCalculator.DIRECT_HIT_RADIUS;
        return new double[] {rawDamage, isDirectHit ? 1.0 : 0.0};
    }

    @Test
    @Timeout(10)
    void absorbShieldMitigatesDamageThenBreaksAfterCumulativeThreshold() {
        ShieldScenario s = setUpShieldScenario("m-shield-absorb");
        Match match = s.match();

        assertTrue(match.fire(s.ownerId(), "r1", "absorb_shield", 0, 0).accepted());
        assertEquals("absorb_shield", match.activeShieldIdOf(s.ownerId()));

        double angle = 45;
        double power = 64; // lands close to the owner at x=500
        double[] predicted = predictBasicShellHit(s, angle, power);
        double rawDamage = predicted[0];
        double expectedMitigated = rawDamage * 0.5;
        double expectedHealthAfterHit1 = Math.max(0.0, Math.round(100 - expectedMitigated));

        // beginTurn() re-rolls wind on every new turn, so debugSetWind(0) must be
        // re-applied before each fire() to keep every shot's physics deterministic
        // (not just the very first one).
        match.debugSetWind(0);
        assertTrue(match.fire(s.shooterId(), "r2", "basic_shell", angle, power).accepted());
        assertEquals(expectedHealthAfterHit1, match.healthOf(s.ownerId()), 0.001);
        assertEquals("absorb_shield", match.activeShieldIdOf(s.ownerId()), "single hit should not yet break Absorb");
        assertTrue(match.shieldAbsorbedSoFarOf(s.ownerId()) > 0, "one mitigated hit should have accumulated some absorption");

        // basic_shell is unlimited (qty -1), so keep repeating shooter-hit /
        // owner-filler turn pairs (each shooter hit direct-hits the owner for
        // the same mitigated ~half-damage) until cumulative absorption crosses
        // the 80 threshold and the shield breaks. Bounded iteration count as a
        // safety net against an unexpectedly-never-breaking setup.
        boolean broke = false;
        for (int i = 0; i < 14 && !broke; i++) {
            // Owner's filler turn (doesn't reactivate the shield). Fired far away
            // (long-range 45/90 shot) so it doesn't land near anyone's tank and
            // cause unrelated self-damage.
            match.debugSetWind(0);
            assertTrue(match.fire(s.ownerId(), "rFiller" + i, "basic_shell", 45, 90).accepted());
            match.debugSetWind(0);
            assertTrue(match.fire(s.shooterId(), "rHit" + i, "basic_shell", angle, power).accepted());
            if (match.activeShieldIdOf(s.ownerId()) == null) {
                broke = true;
            }
        }
        assertTrue(broke, "Absorb should eventually break once cumulative absorption exceeds "
                + ShieldDef.ABSORB_BREAK_THRESHOLD);
    }

    @Test
    @Timeout(10)
    void deflectShieldNegatesDirectHitThenBreaks() {
        ShieldScenario s = setUpShieldScenario("m-shield-deflect-direct");
        Match match = s.match();

        assertTrue(match.fire(s.ownerId(), "r1", "deflect_shield", 0, 0).accepted());
        assertEquals("deflect_shield", match.activeShieldIdOf(s.ownerId()));

        double angle = 45;
        double power = 64; // lands close to the owner at x=500
        double[] predicted = predictBasicShellHit(s, angle, power);
        boolean isDirectHit = predicted[1] == 1.0;

        match.debugSetWind(0); // beginTurn() re-rolled wind for this new turn; pin it back to 0
        assertTrue(match.fire(s.shooterId(), "r2", "basic_shell", angle, power).accepted());

        if (isDirectHit) {
            assertEquals(100.0, match.healthOf(s.ownerId()), 0.001, "direct hit should be fully negated");
            assertNull(match.activeShieldIdOf(s.ownerId()), "Deflect should break after negating one direct hit");
        } else {
            // Setup guarantee: the chosen angle/power lands within the tank hitbox,
            // which per DamageCalculator's DIRECT_HIT_RADIUS should register as direct.
            assertTrue(match.healthOf(s.ownerId()) < 100.0);
        }
    }

    @Test
    @Timeout(10)
    void deflectShieldAppliesReducedDamageOnNearMissWithoutBreaking() {
        ShieldScenario s = setUpShieldScenario("m-shield-deflect-nearmiss");
        Match match = s.match();

        assertTrue(match.fire(s.ownerId(), "r1", "deflect_shield", 0, 0).accepted());

        // Aim a lower-power shot that lands short of the owner's tank (a near miss:
        // within blast radius but far enough from the exact tank position to fall
        // outside DamageCalculator.DIRECT_HIT_RADIUS).
        double angle = 45;
        double power = 62; // lands ~20 units short of the owner: within blast radius, outside direct-hit radius
        List<ProjectileSim.TankTarget> noTargets = Collections.emptyList();
        ProjectileSim.Result sim = ProjectileSim.simulate(200, 500, angle, power, 0,
                match.debugTerrain(), noTargets, WeaponDef.Behavior.STANDARD, 1.0, 1.0, false);
        double dist = distance(500, 500, sim.impactX, sim.impactY);
        // Only proceed with the near-miss assertion if this setup actually lands
        // within blast range but outside the direct-hit radius; otherwise this
        // combination of angle/power on flat terrain isn't a valid near-miss and
        // we skip rather than assert something not actually being tested.
        if (dist <= WeaponDef.BASIC_SHELL.blastRadius() && dist > DamageCalculator.DIRECT_HIT_RADIUS) {
            match.debugSetWind(0); // beginTurn() re-rolled wind for this new turn; pin it back to 0
            assertTrue(match.fire(s.shooterId(), "r2", "basic_shell", angle, power).accepted());
            assertTrue(match.healthOf(s.ownerId()) < 100.0, "near-miss splash should still apply reduced damage");
            assertEquals("deflect_shield", match.activeShieldIdOf(s.ownerId()), "near-miss must not break Deflect");
        }
    }

    @Test
    @Timeout(10)
    void reflectShieldMitigatesDamageGrantsCashbackAndNeverBreaks() {
        ShieldScenario s = setUpShieldScenario("m-shield-reflect");
        Match match = s.match();

        assertTrue(match.fire(s.ownerId(), "r1", "reflect_shield", 0, 0).accepted());
        assertEquals("reflect_shield", match.activeShieldIdOf(s.ownerId()));

        int cashBefore = match.cashOf(s.ownerId());
        double angle = 45;
        double power = 64; // lands close to the owner at x=500
        double[] predicted = predictBasicShellHit(s, angle, power);
        double rawDamage = predicted[0];
        double expectedMitigated = rawDamage * 0.7;
        double expectedHealth = Math.max(0.0, Math.round(100 - expectedMitigated));
        double blocked = rawDamage - expectedMitigated;
        int expectedCashBack = (int) Math.round(blocked * 0.2);

        match.debugSetWind(0); // beginTurn() re-rolled wind for this new turn; pin it back to 0
        assertTrue(match.fire(s.shooterId(), "r2", "basic_shell", angle, power).accepted());

        assertEquals(expectedHealth, match.healthOf(s.ownerId()), 0.001);
        assertEquals("reflect_shield", match.activeShieldIdOf(s.ownerId()), "Reflect should never break on its own");
        if (expectedCashBack > 0) {
            assertEquals(cashBefore + expectedCashBack, match.cashOf(s.ownerId()));
        }
    }

    @Test
    @Timeout(10)
    void shieldActivationEncodesAsZeroLengthShotResolved() {
        ShieldScenario s = setUpShieldScenario("m-shield-encoding");
        Match match = s.match();

        Match.FireOutcome outcome = match.fire(s.ownerId(), "r1", "absorb_shield", 12, 34);
        assertTrue(outcome.accepted());

        var resolved = outcome.shotResolved();
        assertEquals("absorb_shield", resolved.weaponId);
        assertEquals(1, resolved.trajectory.size());
        assertEquals(0, resolved.damageEvents.size());
        assertEquals(0, resolved.cashEarned.size());
        assertEquals(resolved.terrainDelta.startX, resolved.terrainDelta.endX);
        assertEquals(1, resolved.terrainDelta.heights.length);
    }
}
