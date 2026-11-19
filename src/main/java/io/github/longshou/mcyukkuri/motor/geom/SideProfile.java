package io.github.longshou.mcyukkuri.motor.geom;

/**
 * How far one body reaches toward one of its sides, per cell of its height and depth (model units, the depth front -Z to back
 * +Z), over a whole clip it plays side by side with another (yukkuri_presentation/side_profile.json). Two such reaches added
 * cell by cell give the centre distance at which the two meet and nothing of the one passes through the other.
 */
public final class SideProfile {
    private final double heightBand, depthBand, z0;
    private final double[][] reach;

    /** {@code reach[height][depth]}: the farthest point toward the side in the cell at height {@code height * band} and depth
     *  {@code z0 + depth * band}; 0 where the body has nothing. */
    public SideProfile(double band, double z0, double[][] reach) {
        this(band, band, z0, reach);
    }

    public SideProfile(double heightBand, double depthBand, double z0, double[][] reach) {
        if (!(heightBand > 0 && depthBand > 0)) throw new IllegalArgumentException("band");
        this.heightBand = heightBand; this.depthBand = depthBand; this.z0 = z0; this.reach = reach;
    }

    /** Where the second body stands beside the first, both facing the same way: the centre distance across and how far behind
     *  the first it stands (blocks; negative: in front). */
    public record Stand(double across, double behind) {}

    /** The shared height and depth of the limiting cells, in blocks relative to the first body's feet. */
    public record Contact(double across, double height, double depth) {
        public boolean within(double separation, double arrival) {
            double gap = separation - across;
            return gap >= -1e-9 && gap <= arrival;
        }
    }

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
        var contact = contact(a, scaleA, b, scaleB, dy, behind);
        return contact == null ? 0 : contact.across();
    }

    public static Contact contact(SideProfile a, double scaleA, SideProfile b, double scaleB, double dy, double behind) {
        double out = 0;
        double height = 0, depth = 0;
        for (int y = 0; y < a.reach.length; y++) {
            double h0 = y * a.heightBand * scaleA, h1 = h0 + a.heightBand * scaleA;
            for (int z = 0; z < a.reach[y].length; z++) {
                double ra = a.reach[y][z];
                if (ra <= 0) continue;
                double d0 = (a.z0 + z * a.depthBand) * scaleA, d1 = d0 + a.depthBand * scaleA;
                int by0 = Math.max(0, (int) Math.floor((h0 - dy) / (b.heightBand * scaleB)));
                int by1 = Math.min(b.reach.length - 1, (int) Math.ceil((h1 - dy) / (b.heightBand * scaleB)) - 1);
                int bz0 = Math.max(0, (int) Math.floor(((d0 - behind) / scaleB - b.z0) / b.depthBand));
                for (int by = by0; by <= by1; by++) {
                    int bz1 = Math.min(b.reach[by].length - 1, (int) Math.ceil(((d1 - behind) / scaleB - b.z0) / b.depthBand) - 1);
                    for (int bz = bz0; bz <= bz1; bz++) {
                        double rb = b.reach[by][bz], across = ra * scaleA + rb * scaleB;
                        if (rb <= 0 || across <= out) continue;
                        double bh = by * b.heightBand * scaleB + dy;
                        double bd = (b.z0 + bz * b.depthBand) * scaleB + behind;
                        height = (Math.max(h0, bh) + Math.min(h1, bh + b.heightBand * scaleB)) * .5;
                        depth = (Math.max(d0, bd) + Math.min(d1, bd + b.depthBand * scaleB)) * .5;
                        out = across;
                    }
                }
            }
        }
        return out == 0 ? null : new Contact(out, height, depth);
    }
}
