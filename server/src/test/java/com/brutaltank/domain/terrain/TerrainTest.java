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
    void craterNearEdgeOfWorldClampsColumnRange() {
        int[] flat = new int[400];
        java.util.Arrays.fill(flat, 300);
        Terrain terrain = new Terrain(flat);

        Terrain.CraterResult result = terrain.applyCrater(5, 30);

        assertEquals(0, result.startX());
        assertEquals(35, result.endX());
    }
}
