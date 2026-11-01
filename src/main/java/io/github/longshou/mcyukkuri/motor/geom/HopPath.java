package io.github.longshou.mcyukkuri.motor.geom;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.BiFunction;

/** Shortest sequence of physically feasible hops through supported points; no reservations. */
public final class HopPath {
    public static final int MAX_STOPS = 12;
    public record Leg(Vec at, ArcSolver.Plan flight) {}
    private record Node(Vec at, List<Leg> legs) {}
    private HopPath() {}

    public static List<Leg> solve(Vec from, Vec to, List<Vec> stops, BiFunction<Vec, Vec, ArcSolver.Result> solve) {
        var points = new ArrayList<Vec>();
        points.add(to);
        int count = Math.min(MAX_STOPS, stops.size());
        for (int i = 0; i < count; i++) points.add(stops.get(i * stops.size() / count));
        var seen = new HashSet<Vec>();
        seen.add(from);
        var queue = new ArrayDeque<Node>();
        queue.add(new Node(from, List.of()));
        while (!queue.isEmpty()) {
            var node = queue.remove();
            for (var next : points) {
                if (seen.contains(next) || !(solve.apply(node.at(), next) instanceof ArcSolver.Plan plan)) continue;
                var legs = new ArrayList<>(node.legs());
                legs.add(new Leg(next, plan));
                if (next.equals(to)) return List.copyOf(legs);
                seen.add(next);
                queue.add(new Node(next, legs));
            }
        }
        return null;
    }
}
