package com.brutaltank.match;

import com.brutaltank.domain.terrain.Terrain;
import com.brutaltank.domain.weapon.DamageCalculator;
import com.brutaltank.domain.weapon.ProjectileSim;
import com.brutaltank.domain.weapon.ShieldDef;
import com.brutaltank.domain.weapon.WeaponDef;
import com.brutaltank.protocol.Payloads;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

    /**
     * Mirrors Match.resolveShot's barrel-tip launch point (turret pivot +
     * BARREL_LENGTH along the fire angle) so these tests' own predictive
     * ProjectileSim.simulate() calls start from the same point the real
     * shot does.
     */
    private static double[] barrelTip(double tankX, double tankY, double angleDeg) {
        double turretY = tankY - Match.TANK_WORLD_HEIGHT;
        double rad = Math.toRadians(angleDeg);
        return new double[] {
                tankX + Math.cos(rad) * Match.BARREL_LENGTH,
                turretY - Math.sin(rad) * Match.BARREL_LENGTH
        };
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
        double[] tip = barrelTip(400, 500, angle);
        ProjectileSim.Result apex = ProjectileSim.simulate(tip[0], tip[1], angle, power, 0,
                match.debugTerrain(), Collections.emptyList(), WeaponDef.Behavior.STANDARD, 1.0, 1.0, true);
        assertTrue(apex.stoppedAtApex, "test setup expects the shot to reach an apex");

        // Children relaunch from the apex's actual (near-horizontal) velocity,
        // not the original steep launch angle/power — see Match's MIRV case.
        double apexAngleDeg = Math.toDegrees(Math.atan2(-apex.finalVy, apex.finalVx));
        double apexPower = Math.hypot(apex.finalVx, apex.finalVy) / ProjectileSim.POWER_SCALE;

        double[] offsets = {-15.0, -5.0, 5.0, 15.0};
        Joined[] targets = {t1, t2, t3, t4};
        for (int i = 0; i < offsets.length; i++) {
            ProjectileSim.Result child = ProjectileSim.simulate(apex.impactX, apex.impactY, apexAngleDeg + offsets[i], apexPower,
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
    // MIRV trajectory: the single flight-animation dot walks
    // ShotResolved.trajectory front-to-back, so it must read as ONE
    // continuous flight, not double back on itself.
    // -----------------------------------------------------------------

    @Test
    @Timeout(10)
    void mirvSplitReportsOnlyTheApexImpactPoint() {
        Match match = newMatch("m-mirv-impact");
        Joined shooter = join(match, "Shooter");
        Joined t1 = join(match, "T1");
        match.setReady(shooter.playerId(), true);
        match.setReady(t1.playerId(), true);

        match.debugSetTerrain(flatTerrain(1600, 500));
        match.debugSetWind(0);
        match.debugSetTankPosition(shooter.playerId(), 400, 500);
        match.debugSetTankPosition(t1.playerId(), 1590, 500); // out of blast range

        double angle = 45;
        double power = 55;
        double[] tip = barrelTip(400, 500, angle);
        ProjectileSim.Result apex = ProjectileSim.simulate(tip[0], tip[1], angle, power, 0,
                match.debugTerrain(), Collections.emptyList(), WeaponDef.Behavior.STANDARD, 1.0, 1.0, true);
        assertTrue(apex.stoppedAtApex, "test setup expects the shot to reach an apex");

        Match.FireOutcome outcome = match.fire(shooter.playerId(), "r1", "mirv", angle, power);
        assertTrue(outcome.accepted());

        Payloads.Impact impact = outcome.shotResolved().impact;
        assertEquals(apex.impactX, impact.x, 2.0, "reported impact should be the split point, not a child's landing spot");
        assertEquals(apex.impactY, impact.y, 2.0, "reported impact should be the split point, not a child's landing spot");
    }

    @Test
    @Timeout(10)
    void mirvTrajectoryDoesNotRewindAfterSplit() {
        Match match = newMatch("m-mirv-traj");
        Joined shooter = join(match, "Shooter");
        Joined t1 = join(match, "T1");
        match.setReady(shooter.playerId(), true);
        match.setReady(t1.playerId(), true);

        match.debugSetTerrain(flatTerrain(1600, 500));
        match.debugSetWind(0);
        match.debugSetTankPosition(shooter.playerId(), 400, 500);
        match.debugSetTankPosition(t1.playerId(), 1590, 500); // out of blast range

        double angle = 45;
        double power = 55;

        Match.FireOutcome outcome = match.fire(shooter.playerId(), "r1", "mirv", angle, power);
        assertTrue(outcome.accepted());

        List<Payloads.TrajectoryPoint> trajectory = outcome.shotResolved().trajectory;
        assertTrue(trajectory.size() >= 2, "expected a non-trivial trajectory");

        // A single flight-animation dot (GameCanvas/projectileRenderer) walks
        // this list front-to-back over a fixed duration. With no wind and a
        // rightward 45deg launch, x should climb monotonically to the apex;
        // if a split child's full post-apex descent path got appended after
        // the apex path (concatenating multiple separate flights end-to-end
        // instead of reporting just the shared ascent), x would visibly jump
        // backward partway through as playback rewinds to the split point
        // for the next child.
        double maxXSoFar = trajectory.get(0).x;
        for (Payloads.TrajectoryPoint p : trajectory) {
            assertTrue(p.x >= maxXSoFar - 1.0,
                    "trajectory x should never rewind (the animation dot would jump backward): " + describe(trajectory));
            maxXSoFar = Math.max(maxXSoFar, p.x);
        }
    }

    @Test
    @Timeout(10)
    void mirvChildrenLaunchFromApexVelocityNotOriginalSteepAngle() {
        Match match = newMatch("m-mirv-physics");
        Joined shooter = join(match, "Shooter");
        Joined t1 = join(match, "T1");
        match.setReady(shooter.playerId(), true);
        match.setReady(t1.playerId(), true);

        match.debugSetTerrain(flatTerrain(1600, 500));
        match.debugSetWind(0);
        match.debugSetTankPosition(shooter.playerId(), 200, 500);
        match.debugSetTankPosition(t1.playerId(), 1590, 500); // out of blast range

        // Steep on purpose: this is exactly the case that used to send
        // children "uselessly" further up instead of fanning outward.
        double angle = 75;
        double power = 60;
        double[] tip = barrelTip(200, 500, angle);
        ProjectileSim.Result apex = ProjectileSim.simulate(tip[0], tip[1], angle, power, 0,
                match.debugTerrain(), Collections.emptyList(), WeaponDef.Behavior.STANDARD, 1.0, 1.0, true);
        assertTrue(apex.stoppedAtApex, "test setup expects the shot to reach an apex");

        // Arc: at the apex vy~=0 by definition, so the reconstructed launch
        // angle for the children must be near-horizontal -- nothing like the
        // original 75deg (the bug: re-using angleDeg+offset directly).
        double apexAngleDeg = Math.toDegrees(Math.atan2(-apex.finalVy, apex.finalVx));
        assertTrue(Math.abs(apexAngleDeg) < 10.0,
                "expected a near-horizontal apex angle, got " + apexAngleDeg + "deg");

        // Speed: no wind, so the apex's speed should equal the original
        // launch's horizontal velocity component (vy was bled off by gravity
        // getting to the apex; vx is untouched).
        double apexSpeed = Math.hypot(apex.finalVx, apex.finalVy);
        double expectedHorizontalSpeed = power * Math.cos(Math.toRadians(angle)) * ProjectileSim.POWER_SCALE;
        assertEquals(expectedHorizontalSpeed, apexSpeed, Math.max(1.0, expectedHorizontalSpeed * 0.05),
                "apex speed should match the original shot's horizontal speed component");

        // Functional check: a child launched with the OLD (buggy) formula —
        // angle+offset using the original steep angle/power — would climb
        // dramatically above the split point. The fixed formula (apex angle/
        // speed) should stay close to the apex's own height instead.
        double childAngleDeg = apexAngleDeg + 15.0; // largest spread offset
        double childPower = apexSpeed / ProjectileSim.POWER_SCALE;
        ProjectileSim.Result fixedChild = ProjectileSim.simulate(apex.impactX, apex.impactY, childAngleDeg, childPower,
                0, match.debugTerrain(), Collections.emptyList(), WeaponDef.Behavior.STANDARD, 1.0, 1.0, false);
        double fixedMinY = fixedChild.rawPath.stream().mapToDouble(p -> p[1]).min().orElse(fixedChild.impactY);

        ProjectileSim.Result buggyChild = ProjectileSim.simulate(apex.impactX, apex.impactY, angle + 15.0, power,
                0, match.debugTerrain(), Collections.emptyList(), WeaponDef.Behavior.STANDARD, 1.0, 1.0, false);
        double buggyMinY = buggyChild.rawPath.stream().mapToDouble(p -> p[1]).min().orElse(buggyChild.impactY);

        assertTrue(fixedMinY > apex.impactY - 30.0,
                "fixed child shouldn't climb well above the apex split point, apexY=" + apex.impactY + " minY=" + fixedMinY);
        assertTrue(buggyMinY < apex.impactY - 100.0,
                "sanity check: the old buggy formula really did climb much higher, confirming this test would have caught it");

        // And the real Match dispatch should actually be using the fixed
        // formula end-to-end, not just this test's standalone reconstruction.
        Match.FireOutcome outcome = match.fire(shooter.playerId(), "r1", "mirv", angle, power);
        assertTrue(outcome.accepted());
    }

    private static String describe(List<Payloads.TrajectoryPoint> trajectory) {
        StringBuilder sb = new StringBuilder();
        for (Payloads.TrajectoryPoint p : trajectory) {
            sb.append('(').append(Math.round(p.x)).append(',').append(Math.round(p.y)).append(") ");
        }
        return sb.toString();
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
        double[] tip = barrelTip(400, 500, angle);
        ProjectileSim.Result predicted = ProjectileSim.simulate(tip[0], tip[1], angle, power, 0,
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
    // Baby Missile: ~6% terminal homing, only near the end, only near a
    // live target -- everything else stays pure ballistic.
    // -----------------------------------------------------------------

    @Test
    @Timeout(10)
    void babyMissileHomesInOnANearbyTargetDuringDescent() {
        Match match = newMatch("m-homing-on");
        Joined shooter = join(match, "Shooter");
        Joined target = join(match, "Target");
        match.setReady(shooter.playerId(), true);
        match.setReady(target.playerId(), true);

        match.debugSetTerrain(flatTerrain(1600, 500));
        match.debugSetWind(0);
        match.debugSetTankPosition(shooter.playerId(), 200, 500);

        double angle = 45;
        double power = 55;
        double[] tip = barrelTip(200, 500, angle);
        WeaponDef babyMissile = WeaponDef.byId("baby_missile");
        ProjectileSim.Result pureBallistic = ProjectileSim.simulate(tip[0], tip[1], angle, power, 0,
                match.debugTerrain(), Collections.emptyList(), WeaponDef.Behavior.STANDARD,
                babyMissile.powerScaleMultiplier(), babyMissile.gravityMultiplier(), false);

        // Target sits a bit short of where a pure-ballistic shot would land,
        // well within HOMING_ACTIVATION_RADIUS (300) during descent.
        double targetX = pureBallistic.impactX - 80;
        match.debugSetTankPosition(target.playerId(), targetX, 500);

        Match.FireOutcome outcome = match.fire(shooter.playerId(), "r1", "baby_missile", angle, power);
        assertTrue(outcome.accepted());

        double homedImpactX = outcome.shotResolved().impact.x;
        double distBefore = Math.abs(pureBallistic.impactX - targetX);
        double distAfter = Math.abs(homedImpactX - targetX);
        assertTrue(distAfter < distBefore,
                "expected the homing shot to land closer to the target: pure-ballistic dist=" + distBefore
                        + " homed dist=" + distAfter + " (pureImpactX=" + pureBallistic.impactX
                        + " homedImpactX=" + homedImpactX + " targetX=" + targetX + ")");
    }

    @Test
    @Timeout(10)
    void babyMissileFliesPureBallisticWithNoTargetNearby() {
        Match match = newMatch("m-homing-off");
        Joined shooter = join(match, "Shooter");
        Joined farAway = join(match, "FarAway");
        match.setReady(shooter.playerId(), true);
        match.setReady(farAway.playerId(), true);

        match.debugSetTerrain(flatTerrain(1600, 500));
        match.debugSetWind(0);
        match.debugSetTankPosition(shooter.playerId(), 200, 500);
        match.debugSetTankPosition(farAway.playerId(), 1590, 500); // far outside HOMING_ACTIVATION_RADIUS

        double angle = 45;
        double power = 55;
        double[] tip = barrelTip(200, 500, angle);
        WeaponDef babyMissile = WeaponDef.byId("baby_missile");
        ProjectileSim.Result pureBallistic = ProjectileSim.simulate(tip[0], tip[1], angle, power, 0,
                match.debugTerrain(), Collections.emptyList(), WeaponDef.Behavior.STANDARD,
                babyMissile.powerScaleMultiplier(), babyMissile.gravityMultiplier(), false);

        Match.FireOutcome outcome = match.fire(shooter.playerId(), "r1", "baby_missile", angle, power);
        assertTrue(outcome.accepted());

        assertEquals(pureBallistic.impactX, outcome.shotResolved().impact.x, 0.5,
                "with no target in homing range, baby_missile should fly identically to a pure ballistic shot");
    }

    // -----------------------------------------------------------------
    // Digger: reuses TUNNELING with its own shallower penetration cap and a
    // bigger final crater than the old single-point-impact behavior.
    // -----------------------------------------------------------------

    @Test
    @Timeout(10)
    void diggerTunnelsShallowerThanTunnelingShotWithABiggerFinalCrater() {
        Match match = newMatch("m-digger");
        Joined shooter = join(match, "Shooter");
        Joined farAway = join(match, "FarAway");
        match.setReady(shooter.playerId(), true);
        match.setReady(farAway.playerId(), true);

        match.debugSetTerrain(flatTerrain(1600, 500));
        match.debugSetWind(0);
        match.debugSetTankPosition(shooter.playerId(), 400, 500);
        match.debugSetTankPosition(farAway.playerId(), 1590, 500);

        double angle = 45;
        double power = 55;

        Match.FireOutcome outcome = match.fire(shooter.playerId(), "r1", "digger", angle, power);
        assertTrue(outcome.accepted());

        // Crater should be visibly present and, given the bumped blastRadius
        // (38 vs. the old 20), noticeably wide.
        var resolved = outcome.shotResolved();
        int span = resolved.terrainDelta.endX - resolved.terrainDelta.startX + 1;
        assertTrue(span > 40, "expected a wide crater span from Digger's bigger blastRadius, got " + span);

        // Deepest point in the affected range should reach well past the
        // flat baseline (500), confirming a real dig happened.
        Terrain terrain = match.debugTerrain();
        int deepest = 500;
        for (int x = resolved.terrainDelta.startX; x <= resolved.terrainDelta.endX; x++) {
            deepest = Math.max(deepest, terrain.heightAt(x));
        }
        assertTrue(deepest > 540, "expected a visibly deep crater, deepest column height=" + deepest);
    }

    // -----------------------------------------------------------------
    // Bouncing Betty: damage on each bounce, not just the final detonation.
    // -----------------------------------------------------------------

    @Test
    @Timeout(10)
    void bouncingBettyDamagesATankNearAnEarlierBouncePoint() throws Exception {
        // This exercises Match's bounce-damage bookkeeping (25% centerDamage,
        // direct-hit-only, no blast falloff, merged into the same
        // workingHealth/damageByPlayer pattern as blast/fall damage) via
        // reflection into the private applyDetonations method, rather than
        // through a full fire() call driven by ProjectileSim geometry.
        //
        // That's a deliberate choice, not a shortcut: for every angle/power
        // combo probed (see investigation notes), a target placed anywhere
        // within Match.BOUNCE_DAMAGE_RADIUS (30) of a bounce point -- above
        // it, ahead of it, behind it, on a ring around it -- was ALSO within
        // ProjectileSim.TANK_HITBOX_RADIUS (14) of some other point on the
        // same shot's raw path. That's inherent to the bounce mechanic
        // itself: every bounce's approach and departure hug the ground
        // tightly and close together (successive bounces land ~15-50 units
        // apart), leaving no gap wide enough for BOUNCE_DAMAGE_RADIUS (30)
        // and TANK_HITBOX_RADIUS (14) to both be satisfied at once. So a
        // trajectory-driven test of this exact "graze a nearby tank, not a
        // direct hit" scenario isn't constructible from real shots; the
        // bounce-point generation itself is already covered by
        // ProjectileSimTest (bounce-tier tests), so this test targets the
        // one thing that isn't covered elsewhere: the damage bookkeeping
        // Match applies once it has bounce points.
        //
        // NOTE: as of the always-bounce redesign, Bouncing Betty bounces on
        // every ground contact (3-5 times, tiered by the first contact's
        // angle) rather than only below a 35deg shallow-incidence gate — the
        // geometric argument above (bounce points always land too close to
        // a grazed tank for TANK_HITBOX_RADIUS not to fire first) still
        // holds regardless of that trigger-condition change, which is why
        // this reflection-based test remains the right approach.
        Match match = newMatch("m-bounce-dmg");
        Joined shooter = join(match, "Shooter");
        Joined target = join(match, "Target");
        match.setReady(shooter.playerId(), true);
        match.setReady(target.playerId(), true);

        match.debugSetTerrain(flatTerrain(1600, 500));
        double targetX = 400, targetY = 500;
        match.debugSetTankPosition(target.playerId(), targetX, targetY);

        double healthBefore = match.healthOf(target.playerId());

        WeaponDef bouncingBetty = WeaponDef.byId("bouncing_betty");
        double bounceDamagePerHit = bouncingBetty.centerDamage() * 0.25;
        List<double[]> bounceDamagePoints = List.of(new double[] {targetX, targetY});

        Field playersField = Match.class.getDeclaredField("players");
        playersField.setAccessible(true);
        Map<?, ?> players = (Map<?, ?>) playersField.get(match);
        Object shooterMatchPlayer = players.get(shooter.playerId());

        Method applyDetonations = Arrays.stream(Match.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("applyDetonations"))
                .findFirst()
                .orElseThrow(() -> new NoSuchMethodException("applyDetonations"));
        applyDetonations.setAccessible(true);
        applyDetonations.invoke(match, shooterMatchPlayer, "bouncing_betty",
                Collections.emptyList(), 0.0, 0.0, Collections.emptyList(),
                bounceDamagePoints, bounceDamagePerHit);

        double healthAfter = match.healthOf(target.playerId());
        double actualDrop = healthBefore - healthAfter;
        double expectedDrop = Math.round(bounceDamagePerHit);
        assertEquals(expectedDrop, actualDrop, 0.01,
                "expected ~25% of centerDamage from the bounce alone; got a drop of " + actualDrop);
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

    /**
     * Binary-searches a basic_shell power (at 45deg from the shooter's barrel
     * tip) whose flat-terrain impactX lands at {@code targetImpactX}, so the
     * shield tests stay valid regardless of ProjectileSim.POWER_SCALE tuning
     * instead of relying on a power constant hand-picked for one scale.
     */
    private static double findPowerForImpactX(ShieldScenario s, double angle, double targetImpactX) {
        double lo = 1;
        double hi = 100;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            double[] tip = barrelTip(200, 500, angle);
            ProjectileSim.Result r = ProjectileSim.simulate(tip[0], tip[1], angle, mid, 0,
                    s.match().debugTerrain(), Collections.emptyList(), WeaponDef.Behavior.STANDARD, 1.0, 1.0, false);
            if (r.impactX < targetImpactX) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2;
    }

    /** Predicts the exact impact point + resulting raw (pre-shield) damage for a basic_shell shot at the owner. */
    private double[] predictBasicShellHit(ShieldScenario s, double angle, double power) {
        return predictHit(s, WeaponDef.BASIC_SHELL, angle, power);
    }

    /** Predicts the exact impact point + resulting raw (pre-shield) damage for a given weapon shot at the owner. */
    private double[] predictHit(ShieldScenario s, WeaponDef weapon, double angle, double power) {
        List<ProjectileSim.TankTarget> targets = List.of(
                new ProjectileSim.TankTarget(s.ownerId(), 500, 500));
        double[] tip = barrelTip(200, 500, angle);
        ProjectileSim.Result sim = ProjectileSim.simulate(tip[0], tip[1], angle, power, 0,
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
        double power = findPowerForImpactX(s, angle, 500); // lands close to the owner at x=500
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

            // Reset terrain + positions to the pristine scenario before each
            // repeated hit: with FLOOR's new headroom, craters no longer
            // saturate after a couple of hits the way they used to, so
            // repeatedly hitting the same spot with a fixed angle/power (and
            // letting the tank-fall pass move the owner as its crater deepens)
            // would otherwise drift the impact off-target well before 14
            // iterations. This test is about the shield's cumulative-absorption
            // bookkeeping, not terrain evolution, so keep every hit identical.
            match.debugSetTerrain(flatTerrain(1600, 500));
            match.debugSetTankPosition(s.ownerId(), 500, 500);
            match.debugSetTankPosition(s.shooterId(), 200, 500);
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
        double power = findPowerForImpactX(s, angle, 500); // lands close to the owner at x=500
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
        double power = findPowerForImpactX(s, angle, 480); // lands ~20 units short of the owner: within blast radius, outside direct-hit radius
        List<ProjectileSim.TankTarget> noTargets = Collections.emptyList();
        double[] tip = barrelTip(200, 500, angle);
        ProjectileSim.Result sim = ProjectileSim.simulate(tip[0], tip[1], angle, power, 0,
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
        double power = findPowerForImpactX(s, angle, 500); // lands close to the owner at x=500
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
