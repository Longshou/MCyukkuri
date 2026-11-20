package io.github.longshou.mcyukkuri.life;

import java.util.function.BooleanSupplier;

/** A collision face can support a body at its centre or further onto an adjoining level tread. */
final class StandFace {
    interface Probe<T> { T stand(double x, double y, double z); }
    interface Sole { boolean supported(double x, double z); }

    static boolean supports(double x, double z, double radius, double wetArea, BooleanSupplier clear, Sole sole) {
        return wetArea == 0 && clear.getAsBoolean()
                && sole.supported(x - radius, z - radius) && sole.supported(x - radius, z + radius)
                && sole.supported(x + radius, z - radius) && sole.supported(x + radius, z + radius);
    }

    static <T> T stand(double x0, double x1, double y, double z0, double z1, double halfWidth, Probe<T> probe) {
        double x = (x0 + x1) / 2, z = (z0 + z1) / 2;
        T centre = probe.stand(x, y, z);
        if (centre != null) return centre;
        for (double px : new double[]{x, x0 + halfWidth, x1 - halfWidth})
            for (double pz : new double[]{z, z0 + halfWidth, z1 - halfWidth}) {
                if (px == x && pz == z || px < x0 || px > x1 || pz < z0 || pz > z1) continue;
                T at = probe.stand(px, y, pz);
                if (at != null) return at;
            }
        return null;
    }

    private StandFace() {}
}
