package io.github.longshou.mcyukkuri.client;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PoseNeighborGridTest {
    @Test void interpolatedGridMatchesTheTrackedActorScan() {
        var grid = new PoseNeighborGrid();
        var random = new Random(8139);
        var positions = new float[256][3];
        for (int i = 0; i < positions.length; i++) {
            grid.reserve(i + 1);
            for (int axis = 0; axis < 3; axis++) positions[i][axis] = random.nextInt(-48, 49) / 4f;
        }
        positions[0] = new float[]{-6, 0, 2};
        positions[1] = new float[]{-4, 0, 2};
        positions[2] = new float[]{-6, 2, 2};
        positions[3] = new float[]{-6, Math.nextUp(2f), 2};
        positions[4] = new float[]{29_999_984, 0, 4};
        positions[5] = new float[]{29_999_986, 0, 4};
        grid.begin(1, .5f);
        for (int i = 0; i < positions.length; i++) grid.add(i, positions[i][0], positions[i][1], positions[i][2]);

        for (float[] center : positions) {
            var expected = new HashSet<Integer>();
            for (int i = 0; i < positions.length; i++) {
                double dx = (double)positions[i][0] - center[0];
                double dy = (double)positions[i][1] - center[1];
                double dz = (double)positions[i][2] - center[2];
                if (dx * dx + dy * dy + dz * dz <= 4) expected.add(i);
            }
            assertEquals(expected, found(grid, center[0], center[1], center[2]));
        }
    }

    @Test void rebuildDropsActorsAndMovesTheirCells() {
        var grid = new PoseNeighborGrid();
        grid.begin(1, .25f);
        grid.add(0, 1, 1, 1);
        grid.add(1, 2, 1, 1);
        assertTrue(grid.matches(1, .25f));
        assertFalse(grid.matches(2, .25f));
        assertFalse(grid.matches(1, .75f));
        assertEquals(Set.of(0, 1), found(grid, 1, 1, 1));

        grid.invalidate();
        assertFalse(grid.matches(1, .25f));
        grid.begin(1, .25f);
        grid.add(0, -8, 1, 1);
        assertEquals(Set.of(), found(grid, 1, 1, 1));
        assertEquals(Set.of(0), found(grid, -8, 1, 1));
    }

    @Test void keepsNeighborsWhoseDoubleDifferenceRoundsToTheRange() {
        var grid = new PoseNeighborGrid();
        grid.begin(1, .5f);
        grid.add(0, -1e-17f, 0, 0);
        grid.add(1, 2f, 0, 0);
        assertEquals(2d, 2d - (double)-1e-17f);
        assertEquals(Set.of(0, 1), found(grid, 2f, 0, 0));
    }

    @Test void scaledHeadwearCanTouchBeyondTheOrdinaryTwoBlockNeighborhood() {
        var grid = new PoseNeighborGrid();
        grid.begin(1, .5f);
        grid.add(0, 0, 0, 0);
        grid.add(1, 3, 0, 0);
        grid.includeRadius(2.5);
        assertEquals(Set.of(0), found(grid, 0, 0, 0));
        assertEquals(2, grid.findLoose(0, 0, 0, .75));
        grid.begin(2, .5f);
        grid.add(0, 0, 0, 0);
        grid.add(1, 3, 0, 0);
        grid.includeRadius(.75);
        assertEquals(1, grid.findLoose(0, 0, 0, .75));
    }

    private static Set<Integer> found(PoseNeighborGrid grid, float x, float y, float z) {
        int count = grid.find(x, y, z);
        var result = new HashSet<Integer>();
        for (int i = 0; i < count; i++) assertTrue(result.add(grid.actor(i)));
        return result;
    }
}
