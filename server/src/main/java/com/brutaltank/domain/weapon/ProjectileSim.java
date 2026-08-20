package com.brutaltank.domain.weapon;

import com.brutaltank.domain.terrain.Terrain;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-authoritative kinematic simulation for all weapon behaviors
 * (PLAN.md 4.2). Fixed-timestep integration, extended for M3's
 * behavior-specific hooks:
 * <ul>
 *   <li>{@link WeaponDef.Behavior#TUNNELING}: doesn't terminate on the first
 *       terrain hit — keeps integrating "underground" until cumulative
 *       penetration depth exceeds {@link #TUNNELING_MAX_PENETRATION}.</li>
 *   <li>{@link WeaponDef.Behavior#BOUNCING}: reflects {@code vy} (×0.6) on a
 *       shallow-angle terrain hit (&lt;35&deg; from horizontal), up to
 *       {@link #BOUNCING_MAX_BOUNCES} times, then detonates on the next
 *       hit.</li>
 *   <li>Apex detection ({@code stopAtApex}): used by MIRV — {@code Match}
 *       calls {@link #simulate} once with {@code stopAtApex=true} to find the
 *       split point, then simulates each child from there. Apex is the step
 *       where {@code vy} transitions from negative to non-negative (this
 *       codebase's convention: {@code vy} negative == moving up, since
 *       gravity increases {@code vy} over time and {@code y} grows
 *       downward).</li>
 * </ul>
 */
public final class ProjectileSim {

    public static final double GRAVITY = 220.0; // units/s^2
    public static final double WIND_ACCEL_PER_STRENGTH = 4.0;
    public static final double DT = 1.0 / 60.0;
    // 12.0 doubles the old 6.0 (per user feedback: today's max power should
    // become the new 50% mark) — max-power/45deg vacuum range is now ~6544
    // units, well past the ~1300-unit worst-case spawn separation and the
    // 1600-unit map width, so shots can wrap the screen more than once at
    // full power. Was 4.0 (~727 range) in M1/M2, then 6.0 (~1636 range).
    public static final double POWER_SCALE = 12.0;
    public static final double TANK_HITBOX_RADIUS = 14.0;
    public static final int RESAMPLE_POINTS = 36;

    public static final double TUNNELING_MAX_PENETRATION = 40.0;
    public static final int BOUNCING_MAX_BOUNCES = 3;
    public static final double BOUNCING_ENERGY_RETENTION = 0.6;
    public static final double BOUNCING_SHALLOW_ANGLE_DEG = 35.0;

    private static final int MAX_STEPS = 20 * 60; // 20s safety cap

    private ProjectileSim() {
    }

    /** One target tank's position, for hit detection. */
    public record TankTarget(String playerId, double x, double y) {
    }

    /** Simulation output: raw step path, resampled trajectory, and the terminal outcome. */
    public static final class Result {
        public final List<double[]> rawPath;
        public final List<double[]> resampledTrajectory;
        public final double impactX;
        public final double impactY;
        public final String hitPlayerId; // null if terrain/out-of-bounds hit
        /** True when this result stopped at the trajectory apex rather than a real impact (MIRV). */
        public final boolean stoppedAtApex;
        /** Velocity at the terminal point — MIRV children re-launch from here. */
        public final double finalVx;
        public final double finalVy;
        /** Number of terrain bounces this shot performed (BOUNCING behavior; 0 otherwise). */
        public final int bounceCount;
        /**
         * Where this shot first penetrated the ground (TUNNELING only; NaN
         * otherwise) — lets {@code Match} carve a visible bore track from
         * here down to the final {@code impactX}/{@code impactY}, instead of
         * just a single detached crater at the bottom of the tunnel.
         */
        public final double tunnelEntryX;
        public final double tunnelEntryY;
        /**
         * The {@code (x, groundY)} of each terrain bounce this shot made
         * (BOUNCING only; empty otherwise) — lets {@code Match} leave a small
         * "skip mark" at each one, distinct from the final detonation.
         */
        public final List<double[]> bouncePoints;

        Result(List<double[]> rawPath, List<double[]> resampledTrajectory,
               double impactX, double impactY, String hitPlayerId,
               boolean stoppedAtApex, double finalVx, double finalVy, int bounceCount,
               double tunnelEntryX, double tunnelEntryY, List<double[]> bouncePoints) {
            this.rawPath = rawPath;
            this.resampledTrajectory = resampledTrajectory;
            this.impactX = impactX;
            this.impactY = impactY;
            this.hitPlayerId = hitPlayerId;
            this.stoppedAtApex = stoppedAtApex;
            this.finalVx = finalVx;
            this.finalVy = finalVy;
            this.bounceCount = bounceCount;
            this.tunnelEntryX = tunnelEntryX;
            this.tunnelEntryY = tunnelEntryY;
            this.bouncePoints = bouncePoints;
        }
    }

    /** Original M1/M2 entry point: plain ballistic arc, no weapon-specific behavior. */
    public static Result simulate(double startX, double startY, double angleDeg, double power,
                                   int windStrength, Terrain terrain, List<TankTarget> targets) {
        return simulate(startX, startY, angleDeg, power, windStrength, terrain, targets,
                WeaponDef.Behavior.STANDARD, 1.0, 1.0, false);
    }

    /**
     * Full entry point used by weapon-specific dispatch in {@code Match}.
     *
     * @param behavior              drives TUNNELING/BOUNCING in-flight handling; MIRV/CLUSTER/DIGGER
     *                              are resolved by the caller around a STANDARD-shaped simulation
     *                              (see class javadoc and Match.resolveShot).
     * @param powerScaleMultiplier  per-weapon launch-velocity multiplier (e.g. baby_missile ~1.15).
     * @param gravityMultiplier     per-weapon gravity multiplier (e.g. heavy_cannonball ~1.1).
     * @param stopAtApex            when true, terminate at the trajectory apex instead of on impact.
     */
    public static Result simulate(double startX, double startY, double angleDeg, double power,
                                   int windStrength, Terrain terrain, List<TankTarget> targets,
                                   WeaponDef.Behavior behavior, double powerScaleMultiplier,
                                   double gravityMultiplier, boolean stopAtApex) {
        double angleRad = Math.toRadians(angleDeg);
        double vx = power * Math.cos(angleRad) * POWER_SCALE * powerScaleMultiplier;
        double vy = -power * Math.sin(angleRad) * POWER_SCALE * powerScaleMultiplier;
        double x = startX;
        double y = startY;
        double windAccel = windStrength * WIND_ACCEL_PER_STRENGTH;
        double gravity = GRAVITY * gravityMultiplier;

        List<double[]> path = new ArrayList<>();
        path.add(new double[] {x, y});

        String hitPlayerId = null;
        boolean terminated = false;
        boolean apexHit = false;
        boolean tunneling = behavior == WeaponDef.Behavior.TUNNELING;
        boolean bouncing = behavior == WeaponDef.Behavior.BOUNCING;
        boolean inPenetration = false;
        double penetrationEntryX = Double.NaN;
        double penetrationEntryY = 0;
        int bounceCount = 0;
        List<double[]> bouncePoints = new ArrayList<>();

        for (int step = 0; step < MAX_STEPS; step++) {
            double vyBefore = vy;
            vx += windAccel * DT;
            vy += gravity * DT;

            if (stopAtApex && vyBefore < 0 && vy >= 0) {
                terminated = true;
                apexHit = true;
                break;
            }

            x += vx * DT;
            y += vy * DT;
            path.add(new double[] {x, y});

            // Screen wrap: a shot flying off one edge continues from the
            // other side instead of despawning (world is horizontally
            // cyclic), so it can still cross the map and hit a tank there.
            double width = terrain.width();
            if (x < 0 || x >= width) {
                x = ((x % width) + width) % width;
            }

            // Out of bounds vertically (far below floor) still ends the shot.
            if (y > Terrain.FLOOR + 50) {
                terminated = true;
                break;
            }

            if (inPenetration) {
                // Tunneling: keep integrating "underground" (no further terrain/tank
                // checks) until cumulative penetration depth passes the cap.
                if (Math.abs(y - penetrationEntryY) >= TUNNELING_MAX_PENETRATION) {
                    terminated = true;
                    break;
                }
                continue;
            }

            // Tank hit check.
            for (TankTarget t : targets) {
                double dx = x - t.x();
                double dy = y - t.y();
                if (Math.sqrt(dx * dx + dy * dy) <= TANK_HITBOX_RADIUS) {
                    hitPlayerId = t.playerId();
                    terminated = true;
                    break;
                }
            }
            if (terminated) {
                break;
            }

            // Terrain hit check.
            double groundY = terrain.heightAt((int) Math.round(x));
            if (y >= groundY) {
                if (tunneling) {
                    inPenetration = true;
                    penetrationEntryX = x;
                    penetrationEntryY = y;
                    continue;
                }
                if (bouncing && bounceCount < BOUNCING_MAX_BOUNCES) {
                    double incidenceDeg = Math.toDegrees(Math.atan2(Math.abs(vy), Math.abs(vx) + 1e-9));
                    if (incidenceDeg < BOUNCING_SHALLOW_ANGLE_DEG) {
                        bounceCount++;
                        bouncePoints.add(new double[] {x, groundY});
                        vy = -vy * BOUNCING_ENERGY_RETENTION;
                        y = groundY - 1; // nudge back above ground to avoid immediate re-trigger
                        continue;
                    }
                }
                terminated = true;
                break;
            }
        }

        double[] last = path.get(path.size() - 1);
        List<double[]> resampled = resample(path, RESAMPLE_POINTS);
        return new Result(path, resampled, last[0], last[1], hitPlayerId, apexHit, vx, vy, bounceCount,
                penetrationEntryX, penetrationEntryY, bouncePoints);
    }

    private static List<double[]> resample(List<double[]> path, int targetCount) {
        List<double[]> out = new ArrayList<>();
        int n = path.size();
        if (n <= targetCount) {
            return new ArrayList<>(path);
        }
        for (int i = 0; i < targetCount; i++) {
            double t = (double) i / (targetCount - 1) * (n - 1);
            int lo = (int) Math.floor(t);
            int hi = Math.min(n - 1, lo + 1);
            double frac = t - lo;
            double[] a = path.get(lo);
            double[] b = path.get(hi);
            out.add(new double[] {
                    a[0] + (b[0] - a[0]) * frac,
                    a[1] + (b[1] - a[1]) * frac
            });
        }
        return out;
    }
}
