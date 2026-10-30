package io.github.longshou.mcyukkuri.motor.geom;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Linking, detours, disconnection and the rim penalty. */
class GridPathTest {
    private static final double STEP = .3;

    private static HeightGrid grid(int n, HeightGrid.Sampler s) {
        return new HeightGrid(0, 0, n, n, 1, s);
    }

    private static double[] last(List<double[]> p) { return p.get(p.size() - 1); }

    @Test
    void straightLineVisitsEachCell() {
        List<double[]> p = GridPath.find(grid(5, (x, z) -> 0), .5, .5, 4.5, .5, STEP, 0);
        assertEquals(5, p.size());
        assertEquals(.5, p.get(0)[0], 1e-9);
        assertEquals(4.5, last(p)[0], 1e-9);
        for (double[] w : p) assertEquals(.5, w[1], 1e-9);
    }

    @Test
    void stepAboveStepUpIsNotLinked() {
        HeightGrid g = grid(5, (x, z) -> x < 2 ? 0 : .5);
        assertTrue(GridPath.find(g, .5, .5, 4.5, .5, STEP, 0).isEmpty());
        assertEquals(5, GridPath.find(g, .5, .5, 4.5, .5, .5, 0).size());
    }

    @Test
    void pathGoesAroundUnsupportedCell() {
        HeightGrid g = grid(5, (x, z) -> (x > 2 && x < 3 && z < 3) ? Double.NaN : 0);
        List<double[]> p = GridPath.find(g, .5, .5, 4.5, .5, STEP, 0);
        assertTrue(p.size() > 5);
        for (double[] w : p) assertTrue(!(w[0] > 2 && w[0] < 3 && w[1] < 3));
        assertEquals(4.5, last(p)[0], 1e-9);
    }

    @Test
    void disconnectedRegionsGiveEmptyPath() {
        HeightGrid g = grid(5, (x, z) -> (x > 2 && x < 3) ? Double.NaN : 0);
        assertTrue(GridPath.find(g, .5, .5, 4.5, .5, STEP, 0).isEmpty());
    }

    @Test
    void unsupportedGoalGivesEmptyPath() {
        HeightGrid g = grid(5, (x, z) -> (x > 4) ? Double.NaN : 0);
        assertTrue(GridPath.find(g, .5, .5, 4.5, .5, STEP, 0).isEmpty());
    }

    @Test
    void rimPenaltyPrefersInnerRoute() {
        // 7x7 floor: the outer ring of cells is rim, rows 1..5 are inner only away from the ring
        HeightGrid g = grid(7, (x, z) -> 0);
        List<double[]> free = GridPath.find(g, .5, .5, 6.5, .5, STEP, 0);
        List<double[]> pen = GridPath.find(g, .5, .5, 6.5, .5, STEP, 5);
        // the free path hugs the rim row, the penalised one leaves it for the inner rows
        assertEquals(7, free.size());
        boolean inner = false;
        for (double[] w : pen) inner |= w[1] > 1 && w[1] < 6 && w[0] > 1 && w[0] < 6;
        assertTrue(inner);
    }

    @Test
    void nearestSnapsToASupportedNeighbour() {
        HeightGrid g = grid(5, (x, z) -> x < 1 ? Double.NaN : 0);
        int[] at = g.nearest(.9, 2.5);
        assertEquals(1, at[0]);
        assertEquals(2, at[1]);
        HeightGrid none = grid(5, (x, z) -> x > 3 ? 0 : Double.NaN);
        assertEquals(null, none.nearest(.5, .5));
    }
}
