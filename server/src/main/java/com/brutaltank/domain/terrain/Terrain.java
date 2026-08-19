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
    public static final int FLOOR = 550;

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
