package com.brutaltank.domain.terrain;

/**
 * The server-side mutable ground truth for a round's destructible heightmap.
 * One {@code int} height per world column (higher value == lower on screen,
 * i.e. "height" here is a Y-coordinate measured from the top, matching the
 * {@code terrain.heights} convention in shared/protocol.md's MatchStateSync
 * example, where a *smaller* number means higher terrain).
 *
 * <p>Bounds: world floor {@link #FLOOR} (lowest terrain reaches, i.e. tallest
 * numeric value) and ceiling {@link #CEILING} (highest terrain reaches, i.e.
 * smallest numeric value), per PLAN.md 4.1 (clamp to 80-550).
 */
public final class Terrain {

    public static final int CEILING = 80;
    // Near the client's WORLD_HEIGHT=700 canvas mapping (coords.ts), not the
    // generator's default baseline (see TerrainGenerator.BASELINE_HEIGHT,
    // deliberately decoupled from FLOOR): this is purely how deep a crater
    // is allowed to dig, so a powerful-enough weapon can visibly plunge to
    // the true bottom of the screen instead of clamping flat well short of
    // it. Was 550.
    public static final int FLOOR = 690;

    private final int[] heights;

    public Terrain(int[] heights) {
        this.heights = heights;
    }

    public int width() {
        return heights.length;
    }

    public int heightAt(int x) {
        if (x < 0 || x >= heights.length) {
            return FLOOR;
        }
        return heights[x];
    }

    /** Returns a defensive copy of the full heightmap (for MatchStateSync). */
    public int[] snapshotHeights() {
        return heights.clone();
    }

    /**
     * Returns a defensive copy of {@code heights[startX..endX]} inclusive,
     * clamped to valid column bounds. Used by {@code Match} to build one
     * merged {@code terrainDelta} spanning several detonation points from a
     * single multi-impact weapon (MIRV/cluster bomb) in one {@code ShotResolved}.
     */
    public int[] heightsInRange(int startX, int endX) {
        int s = Math.max(0, startX);
        int e = Math.min(heights.length - 1, endX);
        if (e < s) {
            return new int[0];
        }
        int[] out = new int[e - s + 1];
        System.arraycopy(heights, s, out, 0, out.length);
        return out;
    }

    /** Direct (non-defensive) access for internal simulation code. */
    int[] rawHeights() {
        return heights;
    }

    /**
     * Applies a parabolic crater centered at {@code centerX} with radius
     * {@code radius}, per PLAN.md 4.3: max depth ~{@code radius*0.8} tapering
     * to 0 at the edge, clamped to world bounds. Returns the affected column
     * range and new heights so callers can build {@code ShotResolved.terrainDelta}
     * directly.
     */
    public CraterResult applyCrater(int centerX, double radius) {
        return applyCrater(centerX, radius, 1.0);
    }

    /**
     * Overload accepting a crater depth multiplier (M3: Digger's ×1.8 crater
     * depth, PLAN.md 4.4). {@code depthMultiplier} of 1.0 is identical to the
     * base {@link #applyCrater(int, double)} behavior.
     */
    public CraterResult applyCrater(int centerX, double radius, double depthMultiplier) {
        int r = (int) Math.ceil(radius);
        int startX = Math.max(0, centerX - r);
        int endX = Math.min(heights.length - 1, centerX + r);
        double maxDepth = radius * 0.8 * depthMultiplier;

        for (int x = startX; x <= endX; x++) {
            double dist = Math.abs(x - centerX);
            if (dist > radius) {
                continue;
            }
            // Parabolic dig profile: full depth at center, 0 at the edge.
            double falloff = 1.0 - (dist / radius) * (dist / radius);
            double depth = maxDepth * Math.max(0.0, falloff);
            int newHeight = (int) Math.round(heights[x] + depth);
            heights[x] = clamp(newHeight);
        }

        int[] delta = new int[endX - startX + 1];
        System.arraycopy(heights, startX, delta, 0, delta.length);
        return new CraterResult(startX, endX, delta);
    }

    /**
     * Carves the surface down to follow a specific point along a tunneling
     * weapon's actual underground path, instead of digging a fixed depth
     * relative to whatever the terrain height already is there.
     *
     * <p>{@link #applyCrater} is additive ({@code heights[x] += depth}), which
     * is right for a single blast but wrong for a dense sequence of
     * overlapping calls along a path (Digger/Tunneling Shot's bore track,
     * one call per raw simulation step underground): overlapping additive
     * digs either compound into an over-deep pit where columns are hit
     * multiple times, or leave gaps where they aren't, never cleanly
     * tracing the real curve — the reported "tunnel doesn't follow where
     * the projectile went" (live playtest, 2026-08-25).
     *
     * <p>This takes the <em>max</em> of the column's current height and a
     * falloff-blended {@code targetY} (this segment's actual world-Y — a
     * larger value, since this convention's "deeper" is numerically larger)
     * instead. Repeated overlapping calls along a descending path then
     * converge on exactly "the deepest the path got near this column" —
     * the surface literally traces the trajectory's descending envelope,
     * with no over-dig from overlap (max is idempotent) and no gaps
     * (adjacent segments' falloff radii cover the columns between them).
     */
    public CraterResult carveTunnelSegment(int centerX, double targetY, double radius) {
        int r = (int) Math.ceil(radius);
        int startX = Math.max(0, centerX - r);
        int endX = Math.min(heights.length - 1, centerX + r);

        for (int x = startX; x <= endX; x++) {
            double dist = Math.abs(x - centerX);
            if (dist > radius) {
                continue;
            }
            double falloff = 1.0 - (dist / radius) * (dist / radius);
            double blendedTarget = heights[x] + (targetY - heights[x]) * Math.max(0.0, falloff);
            int newHeight = (int) Math.round(Math.max(heights[x], blendedTarget));
            heights[x] = clamp(newHeight);
        }

        int[] delta = new int[endX - startX + 1];
        System.arraycopy(heights, startX, delta, 0, delta.length);
        return new CraterResult(startX, endX, delta);
    }

    private static final int SETTLE_MAX_SLOPE = 10;
    private static final int SETTLE_PADDING = 60;
    private static final int SETTLE_MAX_ITERATIONS = 40;

    /**
     * Heightmap-appropriate stand-in for Scorched Earth's real per-pixel
     * "unsupported dirt falls until it lands" rule: a 1D heightmap can't
     * represent a floating overhang, but it can represent (and correct) an
     * implausibly steep cliff a crater just cut into flat ground. Erodes
     * (deepens) whichever side of each too-steep adjacent-column pair is
     * higher, repeating until every step in the padded range is within
     * {@link #SETTLE_MAX_SLOPE} world-units of its neighbor or the
     * iteration cap is hit. Net loss of material (no fill-back-in) is a
     * deliberate simplification, consistent with this codebase's other
     * approximated weapon behaviors.
     *
     * @return the actual [startX, endX] column range touched, for the
     *         caller to fold into its terrainDelta.
     */
    public int[] settleSlopes(int startX, int endX) {
        int lo = Math.max(0, startX - SETTLE_PADDING);
        int hi = Math.min(heights.length - 1, endX + SETTLE_PADDING);

        boolean changed = true;
        for (int iter = 0; iter < SETTLE_MAX_ITERATIONS && changed; iter++) {
            changed = false;
            for (int x = lo; x < hi; x++) {
                int diff = heights[x + 1] - heights[x];
                if (diff > SETTLE_MAX_SLOPE) {
                    heights[x] = clamp(heights[x] + (diff - SETTLE_MAX_SLOPE));
                    changed = true;
                } else if (-diff > SETTLE_MAX_SLOPE) {
                    heights[x + 1] = clamp(heights[x + 1] + (-diff - SETTLE_MAX_SLOPE));
                    changed = true;
                }
            }
        }
        return new int[] {lo, hi};
    }

    static int clamp(int height) {
        if (height < CEILING) {
            return CEILING;
        }
        if (height > FLOOR) {
            return FLOOR;
        }
        return height;
    }

    /** Result of {@link #applyCrater}: the affected column range and new heights. */
    public record CraterResult(int startX, int endX, int[] heights) {
    }
}
