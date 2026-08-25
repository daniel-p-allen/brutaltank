package com.brutaltank.match;

import com.brutaltank.domain.terrain.Terrain;
import com.brutaltank.domain.weapon.ProjectileSim;
import com.brutaltank.domain.weapon.ShieldDef;
import com.brutaltank.domain.weapon.WeaponDef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Pure decision logic for a bot's turn: which target/weapon to use and what
 * angle/power to fire at, or whether to activate a shield instead. Stateless
 * and side-effect-free (never touches a {@link Match}) so it's independently
 * unit-testable — {@link BotController} is the only piece that actually
 * calls into a live match.
 */
final class BotAimPlanner {

    private BotAimPlanner() {
    }

    /** angleDeg/power are meaningless when activatesShield is true (Match.resolveShieldActivation ignores them). */
    record Plan(String weaponId, double angleDeg, double power, boolean activatesShield) {
    }

    private static final double LOW_HEALTH_THRESHOLD = 35.0;
    private static final double[] ANGLE_CANDIDATES = buildRange(5, 175, 2);
    private static final double[] POWER_CANDIDATES = buildRange(5, 100, 2);

    static Plan plan(Terrain terrain, Match.TankSnapshot self, List<Match.TankSnapshot> allOpponents,
                      int windStrength, Map<String, Integer> loadout, BotProfile profile, Random rng) {
        List<Match.TankSnapshot> opponents = allOpponents.stream()
                .filter(t -> t.alive() && !t.playerId().equals(self.playerId()))
                .toList();

        // Defensive option: low health, an un-activated shield still in stock,
        // profile-scaled chance to spend the turn shielding instead of attacking.
        String availableShield = availableShield(loadout);
        if (self.health() <= LOW_HEALTH_THRESHOLD && availableShield != null
                && !availableShield.equals(self.activeShieldId())
                && rng.nextDouble() < profile.shieldCaution()) {
            return new Plan(availableShield, 45, 50, true);
        }

        if (opponents.isEmpty()) {
            // No live target (shouldn't normally happen on a bot's own turn,
            // but stay safe): fire basic_shell harmlessly rather than not firing.
            return new Plan("basic_shell", 45, 50, false);
        }

        Match.TankSnapshot target = (rng.nextDouble() < profile.targetFocus())
                ? opponents.stream().min(Comparator.comparingDouble(Match.TankSnapshot::health)).orElse(opponents.get(0))
                : opponents.get(rng.nextInt(opponents.size()));

        String weaponId = chooseWeapon(loadout, profile, rng);
        WeaponDef weapon = WeaponDef.byId(weaponId);

        int effectiveWind = profile.windAware() ? windStrength : 0;
        double[] ideal = findBestShot(terrain, self, target, effectiveWind, weapon, rng);

        double angle = ideal[0] + gaussian(rng) * profile.maxAngleErrorDeg();
        double power = ideal[1] + gaussian(rng) * profile.maxPowerError();

        if (rng.nextDouble() < profile.wildMissChance()) {
            angle = 5 + rng.nextDouble() * 170;
            power = 10 + rng.nextDouble() * 90;
        }

        angle = clamp(angle, 0, 180);
        power = clamp(power, 0, 100);
        return new Plan(weaponId, angle, power, false);
    }

    /** Grid search over angle/power for the candidate that lands closest to (ideally directly on) the target. */
    private static double[] findBestShot(Terrain terrain, Match.TankSnapshot self, Match.TankSnapshot target,
                                          int windStrength, WeaponDef weapon, Random rng) {
        double bestAngle = 45;
        double bestPower = 60;
        double bestDist = Double.MAX_VALUE;
        boolean bestHit = false;

        List<ProjectileSim.TankTarget> targets = List.of(
                new ProjectileSim.TankTarget(target.playerId(), target.x(), target.y()));

        for (double angleDeg : ANGLE_CANDIDATES) {
            double angleRad = Math.toRadians(angleDeg);
            double turretY = self.y() - Match.TANK_WORLD_HEIGHT;
            double startX = self.x() + Math.cos(angleRad) * Match.BARREL_LENGTH;
            double startY = turretY - Math.sin(angleRad) * Match.BARREL_LENGTH;
            for (double power : POWER_CANDIDATES) {
                ProjectileSim.Result result = ProjectileSim.simulate(startX, startY, angleDeg, power,
                        windStrength, terrain, targets, weapon.behavior(),
                        weapon.powerScaleMultiplier(), weapon.gravityMultiplier(), false);
                boolean hit = target.playerId().equals(result.hitPlayerId);
                double dist = Math.hypot(result.impactX - target.x(), result.impactY - target.y());
                if (hit && !bestHit) {
                    bestHit = true;
                    bestDist = dist;
                    bestAngle = angleDeg;
                    bestPower = power;
                } else if (hit == bestHit && dist < bestDist) {
                    bestDist = dist;
                    bestAngle = angleDeg;
                    bestPower = power;
                }
            }
        }
        return new double[] {bestAngle, bestPower};
    }

    private static String chooseWeapon(Map<String, Integer> loadout, BotProfile profile, Random rng) {
        List<String> candidates = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : loadout.entrySet()) {
            String id = entry.getKey();
            int qty = entry.getValue();
            if (qty == 0 || WeaponDef.byId(id) == null) {
                continue; // no ammo, or it's a shield id (handled separately)
            }
            candidates.add(id);
            weights.add(profile.weaponPreference().getOrDefault(id, 1.0));
        }
        if (candidates.isEmpty()) {
            return "basic_shell"; // always unlimited (defaultQty -1), always a safe fallback
        }
        double totalWeight = weights.stream().mapToDouble(Double::doubleValue).sum();
        double roll = rng.nextDouble() * totalWeight;
        double cumulative = 0;
        for (int i = 0; i < candidates.size(); i++) {
            cumulative += weights.get(i);
            if (roll <= cumulative) {
                return candidates.get(i);
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    private static String availableShield(Map<String, Integer> loadout) {
        for (String shieldId : ShieldDef.all().keySet()) {
            if (loadout.getOrDefault(shieldId, 0) > 0) {
                return shieldId;
            }
        }
        return null;
    }

    private static double[] buildRange(double lo, double hi, double step) {
        List<Double> values = new ArrayList<>();
        for (double v = lo; v <= hi; v += step) {
            values.add(v);
        }
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    private static double gaussian(Random rng) {
        return rng.nextGaussian();
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
