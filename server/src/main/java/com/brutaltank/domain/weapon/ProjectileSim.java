package com.brutaltank.domain.weapon;

import com.brutaltank.domain.terrain.Terrain;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-authoritative kinematic simulation for the Basic Shell (M1 scope;
 * the other 9 weapons' behavior hooks — MIRV split, bounce, tunneling — are
 * later milestones). Fixed-timestep integration per PLAN.md 4.2.
 */
public final class ProjectileSim {

    public static final double GRAVITY = 220.0; // units/s^2
    public static final double WIND_ACCEL_PER_STRENGTH = 4.0;
    public static final double DT = 1.0 / 60.0;
    public static final double POWER_SCALE = 4.0;
    public static final double TANK_HITBOX_RADIUS = 14.0;
    public static final int RESAMPLE_POINTS = 36;

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

        Result(List<double[]> rawPath, List<double[]> resampledTrajectory,
               double impactX, double impactY, String hitPlayerId) {
            this.rawPath = rawPath;
            this.resampledTrajectory = resampledTrajectory;
            this.impactX = impactX;
            this.impactY = impactY;
            this.hitPlayerId = hitPlayerId;
        }
    }

    public static Result simulate(double startX, double startY, double angleDeg, double power,
                                   int windStrength, Terrain terrain, List<TankTarget> targets) {
        double angleRad = Math.toRadians(angleDeg);
        double vx = power * Math.cos(angleRad) * POWER_SCALE;
        double vy = -power * Math.sin(angleRad) * POWER_SCALE;
        double x = startX;
        double y = startY;
        double windAccel = windStrength * WIND_ACCEL_PER_STRENGTH;

        List<double[]> path = new ArrayList<>();
        path.add(new double[] {x, y});

        String hitPlayerId = null;
        boolean terminated = false;

        for (int step = 0; step < MAX_STEPS; step++) {
            vx += windAccel * DT;
            vy += GRAVITY * DT;
            x += vx * DT;
            y += vy * DT;
            path.add(new double[] {x, y});

            // Out of bounds (either side or far below floor).
            if (x < 0 || x >= terrain.width() || y > Terrain.FLOOR + 50) {
                terminated = true;
                break;
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
                terminated = true;
                break;
            }
        }

        if (!terminated) {
            // Safety-cap fallback: treat last point as impact.
        }

        double[] last = path.get(path.size() - 1);
        List<double[]> resampled = resample(path, RESAMPLE_POINTS);
        return new Result(path, resampled, last[0], last[1], hitPlayerId);
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
