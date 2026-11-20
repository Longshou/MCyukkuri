package io.github.longshou.mcyukkuri.settlement;

/** An opaque cross-section must block every ray from the eye to the arriving body's box. */
final class StraySight {
    interface Terrain {
        boolean opaque(int x, int y, int z);
    }

    static boolean hidden(Terrain terrain, double eyeX, double eyeY, double eyeZ,
                          double x, double y, double z, double halfWidth, double halfHeight) {
        double dx = eyeX - x, dy = eyeY - y, dz = eyeZ - z;
        int steps = (int)Math.ceil(2 * Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))));
        // Stop looking for cover after 128 blocks. An unproven sightline vetoes admission.
        for (int i = 1; i < steps && i <= 256; i++) {
            double t = (double)i / steps;
            double cx = x + t * dx, cy = y + t * dy, cz = z + t * dz;
            if (!terrain.opaque(floor(cx), floor(cy), floor(cz))) continue;
            double w = halfWidth * (1 - t), h = halfHeight * (1 - t);
            if (covered(terrain, cx, cy, cz, w, h)) return true;
        }
        return false;
    }

    private static boolean covered(Terrain terrain, double x, double y, double z, double w, double h) {
        for (int bx = floor(x - w); bx <= floor(x + w); bx++)
            for (int by = floor(y - h); by <= floor(y + h); by++)
                for (int bz = floor(z - w); bz <= floor(z + w); bz++)
                    if (!terrain.opaque(bx, by, bz)) return false;
        return true;
    }

    private static int floor(double coordinate) { return (int)Math.floor(coordinate); }
    private StraySight() {}
}
