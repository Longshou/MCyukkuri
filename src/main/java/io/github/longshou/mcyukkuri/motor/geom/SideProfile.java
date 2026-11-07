package io.github.longshou.mcyukkuri.motor.geom;

/**
 * How far one body reaches toward one of its sides, per cell of its height and depth (model units, the depth front -Z to back
 * +Z), over a whole clip it plays side by side with another (yukkuri_presentation/side_profile.json). Two such reaches added
 * cell by cell give the centre distance at which the two meet and nothing of the one passes through the other.
 */
public final class SideProfile {
    private final double band, z0;
    private final double[][] reach;

    /** {@code reach[height][depth]}: the farthest point toward the side in the cell at height {@code height * band} and depth
     *  {@code z0 + depth * band}; 0 where the body has nothing. */
    public SideProfile(double band, double z0, double[][] reach) {
        if (!(band > 0)) throw new IllegalArgumentException("band");
        this.band = band; this.z0 = z0; this.reach = reach;
    }

    /** Where the second body stands beside the first, both facing the same way: the centre distance across and how far behind
     *  the first it stands (blocks; negative: in front). */
    public record Stand(double across, double behind) {}

    /** The closest stand among the offsets {@code behind} (blocks): a little in front or behind lets bows and hair pass each
     *  other; on a tie the smaller offset. Scales are blocks per model unit; {@code dy}: the second's feet above the first's. */
    public static Stand closest(SideProfile a, double scaleA, SideProfile b, double scaleB, double dy, double[] behind) {
        Stand best = null;
        for (double offset : behind) {
            double across = touching(a, scaleA, b, scaleB, dy, offset);
            if (best == null || across < best.across() - 1e-9
                    || Math.abs(across - best.across()) <= 1e-9 && Math.abs(offset) < Math.abs(best.behind())) best = new Stand(across, offset);
        }
        return best;
    }

    /** Centre distance (blocks) at which {@code b}, its feet {@code dy} above {@code a}'s and {@code behind} blocks behind it, just
     *  meets {@code a}; 0 when no cell of one shares a height and depth with the other. */
    public static double touching(SideProfile a, double scaleA, SideProfile b, double scaleB, double dy, double behind) {
        double out = 0;
        for (int y = 0; y < a.reach.length; y++) {
            double h0 = y * a.band * scaleA, h1 = h0 + a.band * scaleA;
            for (int z = 0; z < a.reach[y].length; z++) {
                double ra = a.reach[y][z];
                if (ra <= 0) continue;
                double d0 = (a.z0 + z * a.band) * scaleA, d1 = d0 + a.band * scaleA;
                double rb = b.farthest((h0 - dy) / scaleB, (h1 - dy) / scaleB, (d0 - behind) / scaleB, (d1 - behind) / scaleB);
                if (rb > 0) out = Math.max(out, ra * scaleA + rb * scaleB);
            }
        }
        return out;
    }

    /** The farthest reach over the cells overlapping heights [h0, h1) and depths [d0, d1) (model units), 0 when none. */
    double farthest(double h0, double h1, double d0, double d1) {
        int y0 = Math.max(0, (int) Math.floor(h0 / band)), y1 = Math.min(reach.length - 1, (int) Math.ceil(h1 / band) - 1);
        double out = 0;
        for (int y = y0; y <= y1; y++) {
            int z0i = Math.max(0, (int) Math.floor((d0 - z0) / band)), z1i = Math.min(reach[y].length - 1, (int) Math.ceil((d1 - z0) / band) - 1);
            for (int z = z0i; z <= z1i; z++) out = Math.max(out, reach[y][z]);
        }
        return out;
    }
}
