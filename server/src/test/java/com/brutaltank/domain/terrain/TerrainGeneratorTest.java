package com.brutaltank.domain.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainGeneratorTest {

    @Test
    void sameSeedProducesIdenticalTerrain() {
        Terrain a = TerrainGenerator.generate(42L);
        Terrain b = TerrainGenerator.generate(42L);
        assertArrayEquals(a.snapshotHeights(), b.snapshotHeights());
    }

    @Test
    void differentSeedsProduceDifferentTerrain() {
        Terrain a = TerrainGenerator.generate(1L);
        Terrain b = TerrainGenerator.generate(2L);
        assertTrue(!java.util.Arrays.equals(a.snapshotHeights(), b.snapshotHeights()));
    }

    @Test
    void heightsAreWithinWorldBounds() {
        Terrain t = TerrainGenerator.generate(7L);
        for (int h : t.snapshotHeights()) {
            assertTrue(h >= Terrain.CEILING && h <= Terrain.FLOOR,
                    "height " + h + " out of bounds [" + Terrain.CEILING + "," + Terrain.FLOOR + "]");
        }
    }

    @Test
    void widthMatchesWorldWidth() {
        Terrain t = TerrainGenerator.generate(3L);
        assertEquals(TerrainGenerator.WORLD_WIDTH, t.width());
    }

    @Test
    void spawnWindowsAreFlat() {
        Terrain t = TerrainGenerator.generate(99L);
        int[] heights = t.snapshotHeights();

        int level1 = heights[TerrainGenerator.SPAWN_X_1];
        for (int x = TerrainGenerator.SPAWN_X_1 - 15; x <= TerrainGenerator.SPAWN_X_1 + 15; x++) {
            assertEquals(level1, heights[x], "spawn 1 window not flat at x=" + x);
        }

        int level2 = heights[TerrainGenerator.SPAWN_X_2];
        for (int x = TerrainGenerator.SPAWN_X_2 - 15; x <= TerrainGenerator.SPAWN_X_2 + 15; x++) {
            assertEquals(level2, heights[x], "spawn 2 window not flat at x=" + x);
        }
    }
}
