package io.github.longshou.mcyukkuri.motor.geom;

import java.util.List;

/** Geometry of supported places; all arguments share the caller's local units. */
public final class PlaceGeometry {
    private PlaceGeometry() {}
    public static boolean cabin(Disc hat, double wall,
                                List<FloorSearch.Placed> captain, List<Disc> riders, double gap) {
        if (Math.hypot(hat.x(), hat.z()) + hat.r() > wall) return false;
        for (var p : captain) if (p.body().overlaps(p.at().x(), p.at().y(), p.at().z(), hat, gap)) return false;
        for (var other : riders) if (hat.overlaps(other, -gap)) return false;
        return true;
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
