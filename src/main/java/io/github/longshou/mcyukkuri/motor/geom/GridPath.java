package io.github.longshou.mcyukkuri.motor.geom;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/** A* over a {@link HeightGrid} (spec 6): 8-way, edges need two supported cells within {@code stepUp}. */
public final class GridPath {
    private GridPath() {}

    /**
     * Waypoints {@code {x,z}} from the start cell centre to the goal cell centre, empty when unreachable.
     * Cost is distance in cells; entering a rim cell (supported, with an unsupported 8-neighbour) adds {@code rimWeight}.
     */
    public static List<double[]> find(HeightGrid g, double sx, double sz, double gx, double gz, double stepUp, double rimWeight) {
        int nx = g.nx(), n = nx * g.nz();
        int s = g.cellZ(sz) * nx + g.cellX(sx), t = g.cellZ(gz) * nx + g.cellX(gx);
        if (!g.supported(s % nx, s / nx) || !g.supported(t % nx, t / nx)) return List.of();
        double[] cost = new double[n];
        int[] from = new int[n];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        Arrays.fill(from, -1);
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        cost[s] = 0;
        open.add(new double[]{heuristic(s, t, nx), s});
        while (!open.isEmpty()) {
            int c = (int) open.poll()[1];
            if (c == t) break;
            int ci = c % nx, cj = c / nx;
            for (int dj = -1; dj <= 1; dj++)
                for (int di = -1; di <= 1; di++) {
                    int i = ci + di, j = cj + dj;
                    if ((di == 0 && dj == 0) || !g.supported(i, j) || Math.abs(g.height(i, j) - g.height(ci, cj)) > stepUp) continue;
                    double nc = cost[c] + (di != 0 && dj != 0 ? Math.sqrt(2) : 1) + (rim(g, i, j) ? rimWeight : 0);
                    int k = j * nx + i;
                    if (nc < cost[k]) {
                        cost[k] = nc;
                        from[k] = c;
                        open.add(new double[]{nc + heuristic(k, t, nx), k});
                    }
                }
        }
        if (from[t] < 0 && s != t) return List.of();
        List<double[]> out = new ArrayList<>();
        for (int c = t; c >= 0; c = from[c]) out.add(new double[]{g.centreX(c % nx), g.centreZ(c / nx)});
        Collections.reverse(out);
        return out;
    }

    /** Octile distance, admissible because every step costs at least its length. */
    private static double heuristic(int a, int b, int nx) {
        int dx = Math.abs(a % nx - b % nx), dz = Math.abs(a / nx - b / nx);
        return Math.max(dx, dz) + (Math.sqrt(2) - 1) * Math.min(dx, dz);
    }

    private static boolean rim(HeightGrid g, int i, int j) {
        for (int dj = -1; dj <= 1; dj++)
            for (int di = -1; di <= 1; di++) if (!g.supported(i + di, j + dj)) return true;
        return false;
    }
}
