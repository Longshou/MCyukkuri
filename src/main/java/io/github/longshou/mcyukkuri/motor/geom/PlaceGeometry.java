package io.github.longshou.mcyukkuri.motor.geom;

import java.util.List;

/** Geometry of supported places; all arguments share the caller's local units. */
public final class PlaceGeometry {
    private PlaceGeometry() {}
    public static boolean cabin(Disc hat, double wall,
                                List<FloorSearch.Placed> captain, List<Disc> riders, double gap) {
        for (double distance : cabinGaps(hat, wall, captain, riders, gap)) if (distance < 0) return false;
        return true;
    }

    /** An already overlapping soft hat can move out, but cannot deepen any overlap or enter a new one. */
    public static boolean cabinStep(Disc from, double fromWall, Disc to, double toWall,
                                     List<FloorSearch.Placed> captain, List<Disc> riders, double gap) {
        var before = cabinGaps(from, fromWall, captain, riders, gap);
        var after = cabinGaps(to, toWall, captain, riders, gap);
        for (int i = 0; i < after.length; i++)
            if (after[i] < 0 && after[i] <= Math.min(0, before[i])) return false;
        return true;
    }

    private static double[] cabinGaps(Disc hat, double wall, List<FloorSearch.Placed> captain, List<Disc> riders, double gap) {
        var gaps = new double[1 + captain.size() + riders.size()];
        gaps[0] = wall - Math.hypot(hat.x(), hat.z()) - hat.r();
        int i = 1;
        for (var p : captain) {
            double distance = Double.POSITIVE_INFINITY;
            for (var column : p.body().columns())
                if (Disc.level(p.at().y() + column.bottom() - gap, p.at().y() + column.top() + gap, hat.bottom(), hat.top()))
                    distance = Math.min(distance, Math.hypot(p.at().x() + column.x() - hat.x(), p.at().z() + column.z() - hat.z()) - hat.r() - gap);
            gaps[i++] = distance;
        }
        for (var other : riders) gaps[i++] = Disc.level(hat.bottom(), hat.top(), other.bottom(), other.top())
                ? Math.hypot(hat.x() - other.x(), hat.z() - other.z()) - hat.r() - other.r() - gap : Double.POSITIVE_INFINITY;
        return gaps;
    }
    /** Cell boundaries join into a cap; the margin applies to the outside of the whole sole. */
    public static double head(List<double[]> supports, double x, double z, double radius, double margin) {
        double height = Double.NEGATIVE_INFINITY;
        for (int i = 0; i <= 24; i++) {
            double px = x + (i == 24 ? 0 : (radius + margin) * Math.cos(i * Math.PI / 12));
            double pz = z + (i == 24 ? 0 : (radius + margin) * Math.sin(i * Math.PI / 12));
            double top = Double.NEGATIVE_INFINITY;
            for (var cell : supports) if (px >= cell[0] && px <= cell[2] && pz >= cell[1] && pz <= cell[3]) top = Math.max(top, cell[4]);
            if (!Double.isFinite(top)) return Double.NaN;
            height = Math.max(height, top);
        }
        return height;
    }
}
