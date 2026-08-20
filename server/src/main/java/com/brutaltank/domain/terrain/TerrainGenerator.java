package com.brutaltank.domain.terrain;

import java.util.SplittableRandom;

/**
 * 1D midpoint-displacement fractal terrain generator, per PLAN.md 4.1.
 *
 * <p>Seeds two endpoints at a random base height, recursively displaces
 * midpoints with a decreasing random offset (halving each level, damped by
 * an exponent), clamps to world bounds, then applies a light smoothing pass
 * and flattens small windows around fixed tank spawn positions.
 */
public final class TerrainGenerator {

    public static final int WORLD_WIDTH = 1600;

    /** M1's two hardcoded spawn x-positions (PLAN.md/task spec). */
    public static final int SPAWN_X_1 = 200;
    public static final int SPAWN_X_2 = 1400;

    private static final int SPAWN_PAD = 15;
    private static final double INITIAL_DISPLACEMENT = 120.0;
    private static final double DAMPING_EXPONENT = 0.6;
    private static final int MIDPOINT_LEVELS = 10; // 2^10 = 1024 >= enough resolution below WORLD_WIDTH

    // Deliberately an absolute constant, not a fraction of (CEILING, FLOOR):
    // FLOOR was extended well past this so deep craters have room to reach
    // the true screen bottom, but the *default* terrain silhouette should
    // stay exactly where it was dialled in (054a019) — this is that
    // baseline's old effective value (lerp(80, 550, 0.9)) kept as-is.
    private static final double BASELINE_HEIGHT = 503.0;

    private TerrainGenerator() {
    }

    /** Generates a fresh, deterministic terrain for the given seed (M1's fixed 2-tank layout). */
    public static Terrain generate(long seed) {
        return generate(seed, WORLD_WIDTH, new int[] {SPAWN_X_1, SPAWN_X_2});
    }

    public static Terrain generate(long seed, int width) {
        return generate(seed, width, new int[] {SPAWN_X_1, SPAWN_X_2});
    }

    /**
     * Generates a fresh, deterministic terrain for the given seed with an
     * arbitrary set of spawn x-positions (M2: up to 8 players). Each spawn
     * gets the same ±{@link #SPAWN_PAD}-column flattening treatment as the
     * original 2-tank layout; the underlying midpoint-displacement algorithm
     * is unchanged.
     */
    public static Terrain generate(long seed, int width, int[] spawnXs) {
        SplittableRandom rng = new SplittableRandom(seed);

        int size = 1;
        while (size < width) {
            size <<= 1;
        }
        double[] work = new double[size + 1];

        // Baseline sits low (near BASELINE_HEIGHT), not at the vertical
        // midpoint: flat-ish terrain should only occupy roughly the bottom
        // 10% of the screen, leaving headroom above to see weapon arcs and,
        // later, for hills/mountains to rise up toward CEILING without the
        // whole map feeling like a wall of dirt by default.
        double baseHeight = BASELINE_HEIGHT;
        work[0] = baseHeight + (rng.nextDouble() - 0.5) * INITIAL_DISPLACEMENT;
        work[size] = baseHeight + (rng.nextDouble() - 0.5) * INITIAL_DISPLACEMENT;

        midpointDisplace(work, 0, size, INITIAL_DISPLACEMENT, rng);

        // Resample the (size+1)-point fractal down to `width` world columns.
        int[] heights = new int[width];
        for (int x = 0; x < width; x++) {
            double t = (double) x / (width - 1) * size;
            int lo = (int) Math.floor(t);
            int hi = Math.min(size, lo + 1);
            double frac = t - lo;
            double h = lerp(work[lo], work[hi], frac);
            heights[x] = Terrain.clamp((int) Math.round(h));
        }

        smooth(heights);

        Terrain terrain = new Terrain(heights);
        for (int spawnX : spawnXs) {
            flattenSpawn(terrain, spawnX);
        }
        return terrain;
    }

    /**
     * Evenly distributes {@code count} spawn x-positions across the world
     * width with margin at both edges, per PLAN.md 4.1 ("spawn positions
     * evenly distributed with jitter and minimum spacing" — M2 keeps this
     * deterministic/simple: even spacing, no jitter, since minimum spacing
     * is trivially satisfied by even distribution for up to 8 players).
     */
    public static int[] computeSpawnXs(int count) {
        if (count <= 0) {
            return new int[0];
        }
        int margin = 150;
        int usable = WORLD_WIDTH - margin * 2;
        int[] spawnXs = new int[count];
        if (count == 1) {
            spawnXs[0] = WORLD_WIDTH / 2;
            return spawnXs;
        }
        for (int i = 0; i < count; i++) {
            spawnXs[i] = margin + (int) Math.round(usable * (i / (double) (count - 1)));
        }
        return spawnXs;
    }

    private static void midpointDisplace(double[] work, int lo, int hi, double displacement, SplittableRandom rng) {
        if (hi - lo < 2) {
            return;
        }
        int mid = (lo + hi) / 2;
        double avg = (work[lo] + work[hi]) / 2.0;
        double offset = (rng.nextDouble() - 0.5) * displacement;
        work[mid] = clampDouble(avg + offset);

        double nextDisplacement = displacement * Math.pow(0.5, DAMPING_EXPONENT);
        midpointDisplace(work, lo, mid, nextDisplacement, rng);
        midpointDisplace(work, mid, hi, nextDisplacement, rng);
    }

    private static double clampDouble(double h) {
        if (h < Terrain.CEILING) {
            return Terrain.CEILING;
        }
        if (h > Terrain.FLOOR) {
            return Terrain.FLOOR;
        }
        return h;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** 3-tap moving average smoothing pass. */
    private static void smooth(int[] heights) {
        int[] copy = heights.clone();
        for (int x = 0; x < heights.length; x++) {
            int prev = x > 0 ? copy[x - 1] : copy[x];
            int next = x < heights.length - 1 ? copy[x + 1] : copy[x];
            heights[x] = Math.round((prev + copy[x] + next) / 3.0f);
        }
    }

    /** Flattens a ±SPAWN_PAD-column window around a spawn x to a single height. */
    private static void flattenSpawn(Terrain terrain, int spawnX) {
        int[] raw = terrain.rawHeights();
        int start = Math.max(0, spawnX - SPAWN_PAD);
        int end = Math.min(raw.length - 1, spawnX + SPAWN_PAD);
        int level = raw[Math.max(0, Math.min(raw.length - 1, spawnX))];
        for (int x = start; x <= end; x++) {
            raw[x] = level;
        }
    }
}
