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
 *   <li>{@link WeaponDef.Behavior#BOUNCING}: reflects {@code vy} (×{@link
 *       #BOUNCING_ENERGY_RETENTION}) and decays {@code vx} (×{@link
 *       #BOUNCING_HORIZONTAL_RETENTION}) on every terrain hit — no angle
 *       gate, it always bounces off the ground, and visibly slows down
 *       (not just loses height) over its bounce sequence. The bounce
 *       budget (3-5, see {@link #computeMaxBounces}) is fixed once at the
 *       very first ground contact from that contact's incidence angle:
 *       flatter first hits get more bounces, steeper ones get fewer. Only
 *       the terrain check has this behavior; a direct mid-air tank hit
 *       (within {@link #TANK_HITBOX_RADIUS}, checked every step before the
 *       terrain check) still terminates immediately with a full detonation
 *       and no bounce, same as every other weapon — a close in-flight pass
 *       can cut the bounce sequence short this way, by design (confirmed
 *       with the user, not a bug). The reflected {@code vy} is clamped to
 *       {@link #MAX_BOUNCE_VY} so a steep, high-power shot's hangtime can't
 *       approach {@link #MAX_STEPS}.</li>
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

    // Baby Missile terminal homing (user feedback: "a little bit of homing
    // just in the end, 6%"): only kicks in once descending (past apex) and
    // only once a live target is within this many world-units, so long
    // misses are unaffected and it reads as a small last-moment correction
    // rather than a lock-on.
    public static final double HOMING_ACTIVATION_RADIUS = 300.0;

    // Was 40 — too shallow to read as a distinct tunnel once the final,
    // wider detonation crater sits right next to it (per user feedback: "I
    // see no tunnel... what I see is nothing"). 160 gives real elongated
    // travel underground before the final explosion.
    public static final double TUNNELING_MAX_PENETRATION = 160.0;
    // Bounce budget ceiling/floor and the angle tiers that pick between them
    // (per user feedback: "it will always bounce... between 3 and 5 bounces
    // depending on angle" — replaces the old <35deg shallow-angle gate,
    // which made bounces almost unreachable under normal arcing play: a
    // symmetric 45deg shot lands at ~45deg incidence, never qualifying).
    // First-pass thresholds, same "tune after playtest" spirit as
    // BOUNCE_DAMAGE_FRACTION in Match.java.
    public static final int BOUNCING_MAX_BOUNCES = 5;
    public static final int BOUNCING_MIN_BOUNCES = 3;
    public static final double BOUNCING_FLAT_TIER_DEG = 25.0;
    public static final double BOUNCING_STEEP_TIER_DEG = 60.0;
    public static final double BOUNCING_ENERGY_RETENTION = 0.6;
    // Horizontal speed also bleeds off each bounce (per user feedback: "the
    // speed of the bounce must diminish after each hit"; 0.85 "still going
    // too quickly" on playtest — dropped to match the vertical loss (0.6)
    // for a much steeper, clearly-noticeable slowdown: ~22% of original
    // speed left after 3 bounces, ~8% after 5, vs. 0.85's much gentler
    // ~61%/~44%).
    public static final double BOUNCING_HORIZONTAL_RETENTION = 0.6;
    // Clamps the reflected |vy| after each bounce. Without this, a steep
    // first-contact angle at high power (vy0 ~= power*POWER_SCALE) reflects
    // most of its speed straight up, and worst-case hangtime across 3
    // steep-tier bounces can approach/exceed MAX_STEPS — previously
    // impossible, since steep shots never bounced at all under the old
    // gate. 400 keeps worst-case total hangtime for 3 bounces well under
    // the 20s cap (400 + 240 + 144 -> summed hangtime ~7s).
    public static final double MAX_BOUNCE_VY = 400.0;

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
        /**
         * Every {@code (x, y)} step while "underground" (TUNNELING only,
         * between {@link #tunnelEntryX}/{@link #tunnelEntryY} and the final
         * impact; empty otherwise) — the shot keeps accelerating under
         * gravity/wind while tunneling, so this is a real curved path, not a
         * straight line between entry and exit. Lets {@code Match} carve a
         * trench that actually follows the trajectory instead of a few
         * sparse interpolated dots.
         */
        public final List<double[]> undergroundPath;

        Result(List<double[]> rawPath, List<double[]> resampledTrajectory,
               double impactX, double impactY, String hitPlayerId,
               boolean stoppedAtApex, double finalVx, double finalVy, int bounceCount,
               double tunnelEntryX, double tunnelEntryY, List<double[]> bouncePoints,
               List<double[]> undergroundPath) {
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
            this.undergroundPath = undergroundPath;
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
     * Delegates to the full overload with no homing and the standard
     * tunneling depth cap.
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
        return simulate(startX, startY, angleDeg, power, windStrength, terrain, targets,
                behavior, powerScaleMultiplier, gravityMultiplier, stopAtApex,
                TUNNELING_MAX_PENETRATION, 0.0);
    }

    /**
     * Full entry point with per-weapon tunneling depth and terminal homing.
     *
     * @param maxPenetration  TUNNELING-only: cumulative underground depth before detonating
     *                        (Tunneling Shot uses {@link #TUNNELING_MAX_PENETRATION}; Digger
     *                        uses its own shallower cap — see {@code Match}).
     * @param homingStrength  0.0 for pure ballistic (every weapon but Baby Missile). Above
     *                        0, once descending (past apex) and within
     *                        {@link #HOMING_ACTIVATION_RADIUS} of a live target, blends the
     *                        velocity DIRECTION (speed unchanged) toward that target by this
     *                        fraction per simulation step.
     */
    public static Result simulate(double startX, double startY, double angleDeg, double power,
                                   int windStrength, Terrain terrain, List<TankTarget> targets,
                                   WeaponDef.Behavior behavior, double powerScaleMultiplier,
                                   double gravityMultiplier, boolean stopAtApex,
                                   double maxPenetration, double homingStrength) {
        double angleRad = Math.toRadians(angleDeg);
        double vx = power * Math.cos(angleRad) * POWER_SCALE * powerScaleMultiplier;
        double vy = -power * Math.sin(angleRad) * POWER_SCALE * powerScaleMultiplier;
        double x = startX;
        double y = startY;
        // Per user feedback, 2026-08-22: "wind should play a part on weight of
        // the weapon, heavy weapons are less effected" — reuses the same
        // gravityMultiplier already driving each weapon's weight class/star
        // rating (WeaponDef.java) rather than adding a separate knob: a
        // heavier weapon (gravityMultiplier > 1, e.g. Nuke/Digger/Heavy
        // Cannonball) gets proportionally less wind push; a lighter one (e.g.
        // Baby Missile at 0.85) gets more. gravityMultiplier=1.0 (most of the
        // roster) leaves wind exactly as it was.
        double windAccel = windStrength * WIND_ACCEL_PER_STRENGTH / gravityMultiplier;
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
        int maxBounces = -1; // fixed at first ground contact, then constant for this shot
        List<double[]> bouncePoints = new ArrayList<>();
        List<double[]> undergroundPath = new ArrayList<>();

        for (int step = 0; step < MAX_STEPS; step++) {
            double vyBefore = vy;
            vx += windAccel * DT;
            vy += gravity * DT;

            if (stopAtApex && vyBefore < 0 && vy >= 0) {
                terminated = true;
                apexHit = true;
                break;
            }

            // Terminal homing (Baby Missile): only once descending, only
            // near a live target, only rotates the velocity direction —
            // speed/magnitude is preserved.
            if (homingStrength > 0.0 && vy >= 0 && !targets.isEmpty()) {
                TankTarget nearest = null;
                double nearestDistSq = Double.MAX_VALUE;
                for (TankTarget t : targets) {
                    double dx = t.x() - x;
                    double dy = t.y() - y;
                    double distSq = dx * dx + dy * dy;
                    if (distSq < nearestDistSq) {
                        nearestDistSq = distSq;
                        nearest = t;
                    }
                }
                if (nearest != null && nearestDistSq <= HOMING_ACTIVATION_RADIUS * HOMING_ACTIVATION_RADIUS) {
                    double speed = Math.hypot(vx, vy);
                    double distToTarget = Math.sqrt(nearestDistSq);
                    if (speed > 1e-6 && distToTarget > 1e-6) {
                        double dirX = vx / speed;
                        double dirY = vy / speed;
                        double targetDirX = (nearest.x() - x) / distToTarget;
                        double targetDirY = (nearest.y() - y) / distToTarget;
                        double blendedX = dirX * (1 - homingStrength) + targetDirX * homingStrength;
                        double blendedY = dirY * (1 - homingStrength) + targetDirY * homingStrength;
                        double blendedLen = Math.hypot(blendedX, blendedY);
                        if (blendedLen > 1e-6) {
                            vx = (blendedX / blendedLen) * speed;
                            vy = (blendedY / blendedLen) * speed;
                        }
                    }
                }
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
                // Tunneling: keep integrating "underground" until cumulative
                // penetration depth passes the cap. Still check for a tank
                // hit each step (a tank whose own ground gave way can end up
                // sitting inside the tunnel's depth range) — previously this
                // branch skipped the tank check entirely, letting a
                // tunneling shot (Digger/Tunneling Shot) pass straight
                // through a tank underground without registering a hit.
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
                undergroundPath.add(new double[] {x, y});
                if (Math.abs(y - penetrationEntryY) >= maxPenetration) {
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
                    undergroundPath.add(new double[] {x, y});
                    continue;
                }
                if (bouncing) {
                    if (maxBounces < 0) {
                        // First ground contact fixes this shot's bounce
                        // budget for good — flatter first hits get more
                        // bounces, steeper ones get fewer. No angle gate:
                        // every ground contact bounces until the budget is
                        // spent.
                        double incidenceDeg = Math.toDegrees(Math.atan2(Math.abs(vy), Math.abs(vx) + 1e-9));
                        maxBounces = computeMaxBounces(incidenceDeg);
                    }
                    if (bounceCount < maxBounces) {
                        bounceCount++;
                        bouncePoints.add(new double[] {x, groundY});
                        vy = -vy * BOUNCING_ENERGY_RETENTION;
                        // Clamp reflected speed so a steep, high-power bounce
                        // can't rack up hangtime anywhere near MAX_STEPS —
                        // see MAX_BOUNCE_VY's javadoc.
                        vy = Math.max(-MAX_BOUNCE_VY, vy);
                        // Horizontal speed also decays each bounce, so the
                        // whole shot visibly slows down over its sequence,
                        // not just loses bounce height.
                        vx *= BOUNCING_HORIZONTAL_RETENTION;
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
                penetrationEntryX, penetrationEntryY, bouncePoints, undergroundPath);
    }

    /**
     * Bounce budget for a BOUNCING shot, fixed from its first ground
     * contact's incidence angle. Flatter (more horizontal) first hits get
     * more bounces; steeper (more vertical) hits get fewer. First-pass
     * thresholds — see BOUNCING_FLAT_TIER_DEG/BOUNCING_STEEP_TIER_DEG.
     */
    private static int computeMaxBounces(double incidenceDeg) {
        if (incidenceDeg <= BOUNCING_FLAT_TIER_DEG) {
            return BOUNCING_MAX_BOUNCES; // 5
        }
        if (incidenceDeg <= BOUNCING_STEEP_TIER_DEG) {
            return 4;
        }
        return BOUNCING_MIN_BOUNCES; // 3
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
