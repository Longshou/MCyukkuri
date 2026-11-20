package io.github.longshou.mcyukkuri.life;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Whether a body on the ground is shut in, and the one ledge it can strain over to get out. Pure: the terrain is asked for the
 * highest stand of a column inside a height band. The ground it can walk is the columns joined by steps within its walk rise
 * (the larger of a step and a hop) and drops within its safe drop; a region that grows past {@link #CAP} columns or
 * {@link #RADIUS} blocks is open ground and nobody is stranded on it. A closed region has a way out only over a ledge no
 * higher than its scramble rise ({@link io.github.longshou.mcyukkuri.motor.geom.AbilityMath#scrambleRise}) onto ground that
 * is larger than the spit it leaves.
 */
public final class Stranding {
    private Stranding() {}

    /** Columns a closed region may hold (a 3 x 5 spit is 15; a yard is not); blocks from the start a region may reach. */
    public static final int CAP = 64, RADIUS = 10;
    private static final double EPS = 1e-6;
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** The world as columns: the highest stand (feet height) of column {@code (x, z)} within {@code lo..hi}, or NaN. */
    public interface Terrain { double stand(int x, int z, double lo, double hi); }

    public record Cell(int x, int z, double y) {}

    /** A ledge from a stand of the region ({@code from}) onto the stand it leads to ({@code to}). */
    public record Exit(Cell from, Cell to) {
        public double rise() { return to.y - from.y; }
        public int dx() { return to.x - from.x; }
        public int dz() { return to.z - from.z; }
    }

    /** {@code size} columns of walkable ground; {@code open}: it is not closed; {@code exit}: the easiest way over a ledge, or null. */
    public record Verdict(int size, boolean open, Exit exit) {
        public boolean stranded() { return !open; }
    }

    private record Region(Map<Long, Cell> cells, boolean open) {}

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    private static Region flood(Terrain terrain, Cell start, double walkRise, double drop) {
        var cells = new HashMap<Long, Cell>();
        var queue = new ArrayDeque<Cell>();
        cells.put(key(start.x(), start.z()), start);
        queue.add(start);
        while (!queue.isEmpty()) {
            var at = queue.poll();
            for (var side : SIDES) {
                int x = at.x() + side[0], z = at.z() + side[1];
                if (cells.containsKey(key(x, z))) continue;
                if (Math.abs(x - start.x()) > RADIUS || Math.abs(z - start.z()) > RADIUS) return new Region(cells, true);
                double y = terrain.stand(x, z, at.y() - drop, at.y() + walkRise);
                if (Double.isNaN(y)) continue;
                var cell = new Cell(x, z, y);
                cells.put(key(x, z), cell);
                if (cells.size() > CAP) return new Region(cells, true);
                queue.add(cell);
            }
        }
        return new Region(cells, false);
    }

    /** The verdict for a body standing at {@code start} (column and feet height). */
    public static Verdict assess(Terrain terrain, Cell start, double walkRise, double scrambleRise, double drop) {
        var region = flood(terrain, start, walkRise, drop);
        if (region.open()) return new Verdict(region.cells().size(), true, null);
        Exit best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        Set<Long> refused = new HashSet<>();
        for (var from : region.cells().values()) {
            for (var side : SIDES) {
                int x = from.x() + side[0], z = from.z() + side[1];
                long k = key(x, z);
                if (region.cells().containsKey(k) || refused.contains(k)) continue;
                double y = terrain.stand(x, z, from.y() + walkRise + EPS, from.y() + scrambleRise);
                if (Double.isNaN(y)) continue;
                var to = new Cell(x, z, y);
                var beyond = flood(terrain, to, walkRise, drop);
                // the ground beyond counts without the spit itself, which a drop back leads to: it must be more ground than the spit
                long fresh = beyond.cells().keySet().stream().filter(c -> !region.cells().containsKey(c)).count();
                if (!beyond.open() && fresh <= region.cells().size()) { refused.add(k); continue; }
                var exit = new Exit(from, to);
                // the lowest ledge first, then the nearest the body already stands
                double score = exit.rise() * 100 + Math.hypot(from.x() - start.x(), from.z() - start.z());
                if (score < bestScore) { best = exit; bestScore = score; }
            }
        }
        return new Verdict(region.cells().size(), false, best);
    }
}
