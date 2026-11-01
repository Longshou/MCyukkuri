package io.github.longshou.mcyukkuri.motor.geom;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.BiFunction;

/** Shortest sequence of physically feasible hops through supported points; no reservations. */
public final class HopPath {
    public record Leg(Vec at, ArcSolver.Plan flight) {}
    private record Node(Vec at, Leg first) {}
    private HopPath() {}

    public static Leg first(Vec from, Vec to, List<Vec> stops, BiFunction<Vec, Vec, ArcSolver.Result> solve) {
        var points = new ArrayList<Vec>();
        points.add(to);
        points.addAll(stops);
        var seen = new HashSet<Vec>();
        seen.add(from);
        var queue = new ArrayDeque<Node>();
        queue.add(new Node(from, null));
        while (!queue.isEmpty()) {
            var node = queue.remove();
            for (var next : points) {
                if (seen.contains(next) || !(solve.apply(node.at(), next) instanceof ArcSolver.Plan plan)) continue;
                var first = node.first() == null ? new Leg(next, plan) : node.first();
                if (next.equals(to)) return first;
                seen.add(next);
                queue.add(new Node(next, first));
            }
        }
        return null;
    }
}
