package io.github.longshou.mcyukkuri.motor.geom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** Clear supported positions, in host units. No reservation or persistent layout. */
public final class FloorSearch {
    public static final double GRID_CELL = .25;
    public interface Surface { double rest(double x, double z, BodyFootprint body); }
    public record Placed(BodyFootprint body, Vec at) {}

    private FloorSearch() {}

    /** Nearest reachable contact in a cone, including its two edges. The route owns support and clearance. */
    public static List<Vec> coneRoute(Vec from, Vec centre, double bearing, double inner, double outer, double halfAngle,
                                      Function<Vec, List<Vec>> route) {
        var candidates = new ArrayList<Vec>();
        double toward = Math.atan2(from.z() - centre.z(), from.x() - centre.x()) - bearing;
        double nearest = Math.clamp(Math.atan2(Math.sin(toward), Math.cos(toward)), -halfAngle, halfAngle);
        double closest = Math.clamp(from.sub(centre).hlen(), inner, outer);
        candidates.add(centre.add(new Vec(Math.cos(bearing + nearest) * closest, 0, Math.sin(bearing + nearest) * closest)));
        var radii = new ArrayList<Double>();
        radii.add(outer);
        for (double radius = inner; radius < outer; radius += GRID_CELL) radii.add(radius);
        for (double radius : radii) {
            int steps = (int) Math.ceil(2 * halfAngle * radius / GRID_CELL);
            for (int i = 0; i <= steps; i++) {
                double angle = bearing - halfAngle + 2 * halfAngle * i / steps;
                candidates.add(centre.add(new Vec(Math.cos(angle) * radius, 0, Math.sin(angle) * radius)));
            }
        }
        candidates.sort(Comparator.comparingDouble(at -> at.sub(from).hlen()));
        for (var at : candidates) {
            var path = route.apply(at);
            if (!path.isEmpty()) return path;
        }
        return List.of();
    }

    public static boolean clear(BodyFootprint body, Vec at, List<Placed> others, double gap) {
        for (var other : others) if (body.overlaps(at.x(), at.y(), at.z(), other.body(),
                other.at().x(), other.at().y(), other.at().z(), gap)) return false;
        return true;
    }

    /** The body's extent along a unit direction, including its current yaw. */
    public static double extent(BodyFootprint body, double x, double z) {
        return body.columns().stream().mapToDouble(p -> p.x() * x + p.z() * z).max().orElseThrow();
    }

    /** Anchor first, then distance-ordered rings; floor positions precede raised ones. The floor margin is
     *  explicit because the caller converts the shared block margin into its host units. A short push includes
     *  its outer ring even when its entire one-tick reach is smaller than a grid cell. */
    public static List<Vec> spots(Surface surface, double floorY, double floorMargin, BodyFootprint body,
                                  List<Placed> others, Vec anchor, double within, boolean floorFirst, double gap, int limit) {
        var out = new ArrayList<Vec>();
        var raised = new ArrayList<Vec>();
        double step = Math.min(body.radius(), GRID_CELL);
        int rings = (int) Math.ceil(within / step);
        for (int ring = 0; ring <= rings; ring++) {
            double radius = Math.min(ring * step, within);
            int around = ring == 0 ? 1 : Math.max(8, (int) Math.ceil(2 * Math.PI * radius / step));
            for (int i = 0; i < around; i++) {
                double angle = i * 2 * Math.PI / around;
                double x = anchor.x() + radius * Math.cos(angle), z = anchor.z() + radius * Math.sin(angle);
                double y = surface.rest(x, z, body);
                if (!Double.isFinite(y)) continue;
                var at = new Vec(x, y, z);
                if (ring != 0 && floorFirst && y - floorY > floorMargin) raised.add(at);
                else if (clear(body, at, others, gap)) out.add(at);
                if (out.size() == limit) return out;
            }
        }
        for (var at : raised) {
            if (clear(body, at, others, gap)) out.add(at);
            if (out.size() == limit) break;
        }
        return out;
    }
}
