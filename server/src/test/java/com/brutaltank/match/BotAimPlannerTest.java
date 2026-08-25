package com.brutaltank.match;

import com.brutaltank.domain.terrain.Terrain;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BotAimPlanner is a pure function (no Match involved), so these tests drive
 * it directly with hand-built snapshots/profiles -- covering the behaviors
 * the live-playtest bot request actually cares about: skilled bots land
 * close to their target, unskilled bots don't, wind-blind bots systematically
 * mis-aim when real wind is present, and a bot never fires a weapon it has
 * no ammo for.
 */
class BotAimPlannerTest {

    private static Terrain flatTerrain(int height) {
        int[] heights = new int[1600];
        java.util.Arrays.fill(heights, height);
        return new Terrain(heights);
    }

    private static Map<String, Integer> fullLoadout() {
        Map<String, Integer> loadout = new LinkedHashMap<>();
        loadout.put("basic_shell", -1);
        loadout.put("baby_missile", 5);
        loadout.put("heavy_cannonball", 3);
        return loadout;
    }

    private static BotProfile profile(Difficulty difficulty, double angleErr, double powerErr,
                                       boolean windAware, double wildMissChance) {
        return new BotProfile(difficulty, angleErr, powerErr, windAware, wildMissChance,
                0.5, 0.5, Map.of(), 0.0, 0.0);
    }

    @Test
    void skilledBotLandsCloseToTargetOnFlatTerrainNoWind() {
        Terrain terrain = flatTerrain(500);
        Match.TankSnapshot self = new Match.TankSnapshot("bot", 200, 500, 100, true, null);
        Match.TankSnapshot target = new Match.TankSnapshot("human", 900, 500, 100, true, null);
        BotProfile hard = profile(Difficulty.HARD, 2, 1, true, 0.0);
        Random rng = new Random(42);

        double totalDist = 0;
        int trials = 20;
        for (int i = 0; i < trials; i++) {
            BotAimPlanner.Plan plan = BotAimPlanner.plan(terrain, self, List.of(target), 0, fullLoadout(), hard, rng);
            double dist = impactDistance(terrain, self, target, 0, plan);
            totalDist += dist;
        }
        double avgDist = totalDist / trials;
        // This game's physics is very sensitive to power (POWER_SCALE=12,
        // multi-second flight times) -- even a "skilled" bot's small power
        // noise can swing the landing point noticeably, so this is a loose
        // bound confirming "usually roughly on target", not "always exact".
        assertTrue(avgDist < 150, "HARD-tier bot should land reasonably close to target on average, got avg=" + avgDist);
    }

    @Test
    void unskilledBotMissesByMoreThanASkilledBotOnAverage() {
        Terrain terrain = flatTerrain(500);
        Match.TankSnapshot self = new Match.TankSnapshot("bot", 200, 500, 100, true, null);
        Match.TankSnapshot target = new Match.TankSnapshot("human", 900, 500, 100, true, null);
        BotProfile easy = profile(Difficulty.EASY, 20, 10, false, 0.12);
        BotProfile hard = profile(Difficulty.HARD, 2, 1, true, 0.0);
        Random rng = new Random(7);

        double easyTotal = 0;
        double hardTotal = 0;
        int trials = 30;
        for (int i = 0; i < trials; i++) {
            BotAimPlanner.Plan easyPlan = BotAimPlanner.plan(terrain, self, List.of(target), 0, fullLoadout(), easy, rng);
            easyTotal += impactDistance(terrain, self, target, 0, easyPlan);
            BotAimPlanner.Plan hardPlan = BotAimPlanner.plan(terrain, self, List.of(target), 0, fullLoadout(), hard, rng);
            hardTotal += impactDistance(terrain, self, target, 0, hardPlan);
        }
        double easyAvg = easyTotal / trials;
        double hardAvg = hardTotal / trials;
        assertTrue(easyAvg > hardAvg,
                "EASY-tier should miss by more on average than HARD-tier: easy=" + easyAvg + " hard=" + hardAvg);
    }

    @Test
    void windBlindBotMisaimsWhenRealWindIsPresentButWindAwareBotDoesNot() {
        Terrain terrain = flatTerrain(500);
        Match.TankSnapshot self = new Match.TankSnapshot("bot", 200, 500, 100, true, null);
        Match.TankSnapshot target = new Match.TankSnapshot("human", 900, 500, 100, true, null);
        int realWind = 15;

        BotProfile windBlind = profile(Difficulty.MEDIUM, 1, 1, false, 0.0);
        BotProfile windAware = profile(Difficulty.MEDIUM, 1, 1, true, 0.0);
        Random rng = new Random(3);

        double blindTotal = 0;
        double awareTotal = 0;
        int trials = 15;
        for (int i = 0; i < trials; i++) {
            BotAimPlanner.Plan blindPlan = BotAimPlanner.plan(terrain, self, List.of(target), realWind, fullLoadout(), windBlind, rng);
            blindTotal += impactDistance(terrain, self, target, realWind, blindPlan);
            BotAimPlanner.Plan awarePlan = BotAimPlanner.plan(terrain, self, List.of(target), realWind, fullLoadout(), windAware, rng);
            awareTotal += impactDistance(terrain, self, target, realWind, awarePlan);
        }
        double blindAvg = blindTotal / trials;
        double awareAvg = awareTotal / trials;
        assertTrue(blindAvg > awareAvg,
                "wind-blind bot should miss by more under real wind than a wind-aware bot: blind=" + blindAvg + " aware=" + awareAvg);
    }

    @Test
    void neverPicksAWeaponWithNoAmmo() {
        Terrain terrain = flatTerrain(500);
        Match.TankSnapshot self = new Match.TankSnapshot("bot", 200, 500, 100, true, null);
        Match.TankSnapshot target = new Match.TankSnapshot("human", 900, 500, 100, true, null);
        Map<String, Integer> loadout = new LinkedHashMap<>();
        loadout.put("basic_shell", -1);
        loadout.put("baby_missile", 0); // out of ammo
        loadout.put("heavy_cannonball", 0); // out of ammo
        BotProfile hard = profile(Difficulty.HARD, 2, 3, true, 0.0);
        Random rng = new Random(11);

        for (int i = 0; i < 10; i++) {
            BotAimPlanner.Plan plan = BotAimPlanner.plan(terrain, self, List.of(target), 0, loadout, hard, rng);
            assertEquals("basic_shell", plan.weaponId());
        }
    }

    @Test
    void lowHealthBotWithShieldCautionOneActivatesShieldInsteadOfAttacking() {
        Terrain terrain = flatTerrain(500);
        Match.TankSnapshot self = new Match.TankSnapshot("bot", 200, 500, 20, true, null); // low health
        Match.TankSnapshot target = new Match.TankSnapshot("human", 900, 500, 100, true, null);
        Map<String, Integer> loadout = fullLoadout();
        loadout.put("absorb_shield", 1);
        BotProfile alwaysShield = new BotProfile(Difficulty.MEDIUM, 5, 5, true, 0.0, 0.5, 0.5, Map.of(), 1.0, 0.0);
        Random rng = new Random(5);

        BotAimPlanner.Plan plan = BotAimPlanner.plan(terrain, self, List.of(target), 0, loadout, alwaysShield, rng);
        assertTrue(plan.activatesShield());
        assertEquals("absorb_shield", plan.weaponId());
    }

    private static double impactDistance(Terrain terrain, Match.TankSnapshot self, Match.TankSnapshot target,
                                          int windStrength, BotAimPlanner.Plan plan) {
        double angleRad = Math.toRadians(plan.angleDeg());
        double turretY = self.y() - Match.TANK_WORLD_HEIGHT;
        double startX = self.x() + Math.cos(angleRad) * Match.BARREL_LENGTH;
        double startY = turretY - Math.sin(angleRad) * Match.BARREL_LENGTH;
        // Must simulate with the *same* weapon's behavior/multipliers the
        // planner itself searched with -- otherwise this verification would
        // silently compare against a different (STANDARD/1.0x) ballistic arc
        // than the one the plan actually optimized for.
        var weapon = com.brutaltank.domain.weapon.WeaponDef.byId(plan.weaponId());
        var result = com.brutaltank.domain.weapon.ProjectileSim.simulate(
                startX, startY, plan.angleDeg(), plan.power(), windStrength, terrain,
                List.of(new com.brutaltank.domain.weapon.ProjectileSim.TankTarget(target.playerId(), target.x(), target.y())),
                weapon.behavior(), weapon.powerScaleMultiplier(), weapon.gravityMultiplier(), false);
        return Math.hypot(result.impactX - target.x(), result.impactY - target.y());
    }
}
