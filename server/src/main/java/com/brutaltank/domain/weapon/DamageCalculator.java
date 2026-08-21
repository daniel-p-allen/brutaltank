package com.brutaltank.domain.weapon;

import java.util.ArrayList;
import java.util.List;

/**
 * Blast damage + cash reward calculation, per PLAN.md 4.3. M1 scope: Basic
 * Shell only, no shields/self-damage nuance (no shop yet), but the method
 * shape (per-target inputs, explicit shooter) is kept sane for later
 * extension rather than hardcoded to two players.
 */
public final class DamageCalculator {

    public static final double DIRECT_HIT_RADIUS = 8.0;
    public static final double DIRECT_HIT_MULTIPLIER = 1.3;
    public static final int CASH_PER_DAMAGE = 5;
    // A tank was treated as a bare point for blast falloff (distance to its
    // exact x,y), with no allowance for its actual physical footprint —
    // inconsistent with ProjectileSim.TANK_HITBOX_RADIUS (14), already used
    // for in-flight direct hits. Fixed per user report: a shot landing
    // visually right next to a tank (within its sprite footprint) but more
    // than blastRadius from its exact center point dealt zero damage, which
    // felt wrong given how large the tank actually looks on screen. Blast
    // distance is now measured from the tank's edge, not its center.
    public static final double TANK_RADIUS = 14.0;

    private DamageCalculator() {
    }

    /** A tank in blast range prior to this shot's resolution. */
    public record TankState(String playerId, double x, double y, double health) {
    }

    /** Damage dealt to one tank by this shot. */
    public record DamageResult(String playerId, double damage, double newHealth, boolean eliminated) {
    }

    /** Cash credited to one player by this shot. */
    public record CashResult(String playerId, int amount) {
    }

    public static final class Outcome {
        public final List<DamageResult> damageEvents;
        public final List<CashResult> cashEarned;

        Outcome(List<DamageResult> damageEvents, List<CashResult> cashEarned) {
            this.damageEvents = damageEvents;
            this.cashEarned = cashEarned;
        }
    }

    /**
     * Computes damage to every tank within {@code blastRadius} of the impact
     * point, plus the shooter's cash reward for damage dealt to others.
     */
    public static Outcome resolve(String shooterId, double impactX, double impactY,
                                   double blastRadius, double centerDamage,
                                   List<TankState> tanks) {
        List<DamageResult> damageEvents = new ArrayList<>();
        int cashFromDamage = 0;

        for (TankState tank : tanks) {
            double dx = tank.x() - impactX;
            double dy = tank.y() - impactY;
            double rawDistance = Math.sqrt(dx * dx + dy * dy);
            double distance = Math.max(0.0, rawDistance - TANK_RADIUS);
            if (distance > blastRadius) {
                continue;
            }

            double falloff = smoothstepFalloff(distance / blastRadius);
            double damage = centerDamage * falloff;

            if (distance <= DIRECT_HIT_RADIUS) {
                damage *= DIRECT_HIT_MULTIPLIER;
            }

            // Round to whole HP so health never drifts into fractional
            // display values (e.g. "78.34") on the client.
            double roundedDamage = Math.round(damage);
            double newHealth = Math.max(0.0, Math.round(tank.health() - roundedDamage));
            boolean eliminated = newHealth <= 0.0;
            damageEvents.add(new DamageResult(tank.playerId(), roundedDamage, newHealth, eliminated));

            if (!tank.playerId().equals(shooterId)) {
                cashFromDamage += (int) (roundedDamage * CASH_PER_DAMAGE);
            }
        }

        List<CashResult> cashEarned = new ArrayList<>();
        if (cashFromDamage > 0) {
            cashEarned.add(new CashResult(shooterId, cashFromDamage));
        }

        return new Outcome(damageEvents, cashEarned);
    }

    /**
     * Smoothstep falloff: 1.0 at t=0 (center), 0.0 at t>=1 (edge of blast),
     * per PLAN.md 4.3 ("damage = D * smoothstepFalloff(distance/R)").
     */
    static double smoothstepFalloff(double t) {
        double clamped = Math.max(0.0, Math.min(1.0, t));
        double smooth = clamped * clamped * (3 - 2 * clamped); // 0 at t=0, 1 at t=1
        return 1.0 - smooth;
    }
}
