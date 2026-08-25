package com.brutaltank.domain.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainTest {

    @Test
    void craterAffectsExpectedColumnRangeAndDigsDeepestAtCenter() {
        int[] flat = new int[400];
        java.util.Arrays.fill(flat, 300);
        Terrain terrain = new Terrain(flat);

        Terrain.CraterResult result = terrain.applyCrater(200, 30);

        assertEquals(170, result.startX());
        assertEquals(230, result.endX());
        assertEquals(61, result.heights().length);

        int centerHeight = terrain.heightAt(200);
        int edgeHeight = terrain.heightAt(170);
        assertTrue(centerHeight > edgeHeight, "center should dig deeper (larger y) than edge");
        // Max depth ~radius*0.8 = 24, so center should be close to 300+24=324.
        assertTrue(centerHeight >= 318 && centerHeight <= 325, "unexpected center depth: " + centerHeight);
    }

    @Test
    void craterClampsToFloor() {
        int[] flat = new int[400];
        java.util.Arrays.fill(flat, Terrain.FLOOR - 5);
        Terrain terrain = new Terrain(flat);

        terrain.applyCrater(200, 30);

        for (int x = 170; x <= 230; x++) {
            assertTrue(terrain.heightAt(x) <= Terrain.FLOOR);
        }
    }

    @Test
    void carveTunnelSegmentDigsToTargetAtCenterAndTapersAtEdge() {
        int[] flat = new int[400];
        java.util.Arrays.fill(flat, 300);
        Terrain terrain = new Terrain(flat);

        terrain.carveTunnelSegment(200, 400, 10);

        assertEquals(400, terrain.heightAt(200), "center column should be carved exactly down to the segment's target Y");
        assertEquals(300, terrain.heightAt(210), "at the radius edge the segment shouldn't change anything");
        assertEquals(300, terrain.heightAt(190));
    }

    @Test
    void carveTunnelSegmentNeverRaisesTerrainThatsAlreadyDeeperThanTheTarget() {
        int[] flat = new int[400];
        java.util.Arrays.fill(flat, 450); // already deeper than the segment we're about to carve
        Terrain terrain = new Terrain(flat);

        terrain.carveTunnelSegment(200, 400, 10);

        assertEquals(450, terrain.heightAt(200), "carving toward a shallower target must not fill terrain back in");
    }

    @Test
    void repeatedOverlappingCarvesConvergeOnTheDeepestNearbyTargetNoOverDig() {
        int[] flat = new int[400];
        java.util.Arrays.fill(flat, 300);
        Terrain terrain = new Terrain(flat);

        // Two overlapping segments a few columns apart, simulating a
        // descending path -- x=195 should end up carved by whichever
        // segment's falloff-blended target reaches deepest there, not by
        // both additively (the old bug: overlapping additive digs at
        // adjacent columns compounded into an over-deep, jagged pit instead
        // of tracing the path).
        terrain.carveTunnelSegment(190, 350, 10);
        terrain.carveTunnelSegment(200, 400, 10);

        int overlapHeight = terrain.heightAt(195);
        assertTrue(overlapHeight <= 400, "overlap column shouldn't dig deeper than the deepest nearby segment's own target: " + overlapHeight);

        // Calling the exact same segment again must be a no-op (idempotent),
        // not an additional dig on top of what's already there.
        int before = terrain.heightAt(200);
        terrain.carveTunnelSegment(200, 400, 10);
        assertEquals(before, terrain.heightAt(200), "re-carving the same segment must not dig any deeper");
    }

    @Test
    void craterNearEdgeOfWorldClampsColumnRange() {
        int[] flat = new int[400];
        java.util.Arrays.fill(flat, 300);
        Terrain terrain = new Terrain(flat);

        Terrain.CraterResult result = terrain.applyCrater(5, 30);

        assertEquals(0, result.startX());
        assertEquals(35, result.endX());
    }

    @Test
    void digCraterDepthMultiplierDigsDeeperAtSameRadius() {
        // M3 Digger weapon: craterDepthMultiplier 1.8 vs the default 1.0,
        // same radius -> a visibly deeper crater at the center column.
        int[] flatA = new int[400];
        java.util.Arrays.fill(flatA, 300);
        Terrain normal = new Terrain(flatA);
        normal.applyCrater(200, 20, 1.0);

        int[] flatB = new int[400];
        java.util.Arrays.fill(flatB, 300);
        Terrain dug = new Terrain(flatB);
        dug.applyCrater(200, 20, 1.8);

        int normalCenterHeight = normal.heightAt(200);
        int dugCenterHeight = dug.heightAt(200);
        assertTrue(dugCenterHeight > normalCenterHeight,
                "digger multiplier should dig deeper: normal=" + normalCenterHeight + " dug=" + dugCenterHeight);
    }

    @Test
    void heightsInRangeReturnsClampedSlice() {
        int[] flat = new int[400];
        java.util.Arrays.fill(flat, 300);
        Terrain terrain = new Terrain(flat);

        int[] slice = terrain.heightsInRange(-10, 5);
        assertEquals(6, slice.length); // clamped to [0,5]

        int[] full = terrain.heightsInRange(0, 399);
        assertEquals(400, full.length);
    }
}
