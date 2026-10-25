package io.github.longshou.mcyukkuri.settlement.site;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Direction;

/** Footprint geometry shared by planner, plan and builder. All coordinates are world X/Z once a plan is rotated. */
public final class SiteGeom {
    public enum Kind { HOME, HALL, GRANARY, PLAZA, POOL, PIT, NURSERY, KNOLL }
    /**
     * x,z: door cell for burrows, the centre otherwise.
     * a,b,c: shape numbers (burrow: variant bits, extra depth rows; plaza: radius*10, unused, phase; pool and pit: axes*10, phase;
     * nursery/knoll: size).
     */
    public record Part(Kind kind, int x, int z, Direction facing, int a, int b, int c) {
        public boolean mound() { return kind == Kind.HOME || kind == Kind.HALL || kind == Kind.GRANARY; }
    }
    /** Mound dimensions in the burrow frame: half width, depth to the back wall, cavity half width, cavity depth. */
    public static int[] dims(Kind kind) {
        return switch (kind) {
            case HALL -> new int[]{3, 4, 2, 3};
            case GRANARY -> new int[]{2, 2, 1, 1};
            default -> new int[]{2, 4, 1, 3};
        };
    }
    /** Dimensions of one mound: {@code b} extra rows of depth (cold country digs deeper), never for granary hollows. */
    public static int[] dims(Part p) {
        int[] d = dims(p.kind());
        if (p.kind() != Kind.GRANARY && p.b() > 0) { d[1] += p.b(); d[3] += p.b(); }
        return d;
    }
    /** The part is left out of this site (its ground did not suit it, e.g. a pool on a slope). */
    public static final int SKIP = Integer.MIN_VALUE;
    public static long key(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }
    public static int keyX(long key) { return (int)(key >> 32); }
    public static int keyZ(long key) { return (int)key; }
    /** Burrow frame: u along the door row (right of the facing), w steps from the door row towards the back wall. */
    public static int fx(Part p, int u, int w) { return p.x() - p.facing().getStepX() * w + p.facing().getClockWise().getStepX() * u; }
    public static int fz(Part p, int u, int w) { return p.z() - p.facing().getStepZ() * w + p.facing().getClockWise().getStepZ() * u; }

    public static double plazaRadius(Part p, double dx, double dz) {
        double base = p.a() / 10.0;
        return base * (1 + .18 * Math.sin(3 * Math.atan2(dz, dx) + p.c() / 100.0) + .08 * Math.sin(5 * Math.atan2(dz, dx) + p.c() / 37.0)) + .2;
    }
    public static boolean inPlaza(Part p, int dx, int dz) {
        return Math.sqrt(dx * (double)dx + dz * (double)dz) <= plazaRadius(p, dx, dz);
    }
    /** A ragged ellipse: the pool edge wanders with the part's phase instead of drawing a clean oval. */
    public static boolean inPool(Part p, int dx, int dz) {
        double a = p.a() / 10.0, b = p.b() / 10.0, angle = Math.atan2(dz, dx);
        double wobble = 1 + .16 * Math.sin(2 * angle + p.c() / 100.0) + .08 * Math.sin(5 * angle + p.c() / 41.0);
        return dx * dx / (a * a) + dz * dz / (b * b) <= wobble * wobble + .0001;
    }
    /** Water cells of a pool part. */
    public static List<long[]> poolWater(Part p) {
        var out = new ArrayList<long[]>();
        int r = (int)Math.ceil(Math.max(p.a(), p.b()) / 10.0 * 1.3);
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) if (inPool(p, dx, dz)) out.add(new long[]{p.x() + dx, p.z() + dz});
        return out;
    }
    /** Shelf cells around the water (the drinking edge, one block lower than the meadow). */
    public static Set<Long> poolShelf(Part p) {
        var water = new HashSet<Long>();
        for (var cell : poolWater(p)) water.add(key((int)cell[0], (int)cell[1]));
        var shelf = new HashSet<Long>();
        for (var cell : poolWater(p)) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            long k = key((int)cell[0] + dx, (int)cell[1] + dz);
            if (!water.contains(k)) shelf.add(k);
        }
        return shelf;
    }
    /**
     * The dip of a waste pit: a ragged shallow hollow one block below the ground around it. It is only bare earth that the
     * settlers keep using; nothing is built in or around it.
     */
    public static List<long[]> pitDip(Part p) {
        var inside = new HashSet<Long>();
        int r = (int)Math.ceil(Math.max(p.a(), p.b()) / 10.0 * 1.3);
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) if (inPool(p, dx, dz)) inside.add(key(p.x() + dx, p.z() + dz));
        // a scraped hollow has no straight edges: some edge cells stay ground, so its outline frays
        var out = new ArrayList<long[]>();
        for (long k : inside) {
            int x = keyX(k), z = keyZ(k), around = 0;
            for (int[] s : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) if (inside.contains(key(x + s[0], z + s[1]))) around++;
            double fray = around <= 2 ? .55 : around == 3 ? .2 : 0;
            if (!(x == p.x() && z == p.z()) && SiteRng.hash(p.c(), x, z, 91) < fray) continue;
            out.add(new long[]{x, z});
        }
        out.sort(Comparator.<long[]>comparingLong(c -> c[0]).thenComparingLong(c -> c[1]));
        return out;
    }
    /** The trodden ground around the dip: its side neighbours, and only some of its corners, so the rim is ragged too. */
    public static Set<Long> pitRim(Part p) {
        var dip = new HashSet<Long>();
        for (var cell : pitDip(p)) dip.add(key((int)cell[0], (int)cell[1]));
        var rim = new HashSet<Long>();
        for (var cell : pitDip(p)) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            int x = (int)cell[0] + dx, z = (int)cell[1] + dz;
            long k = key(x, z);
            if (dip.contains(k) || dx != 0 && dz != 0 && SiteRng.hash(p.c(), x, z, 92) < .55) continue;
            rim.add(k);
        }
        return rim;
    }
    /** Every column a part levels (its footprint, including the door lane of a mound). */
    public static Set<Long> cells(Part p) {
        var out = new HashSet<Long>();
        switch (p.kind()) {
            case HOME, HALL, GRANARY -> {
                int[] d = dims(p);
                for (int u = -d[0]; u <= d[0]; u++) for (int w = 0; w <= d[1]; w++) out.add(key(fx(p, u, w), fz(p, u, w)));
                for (int k = 1; k <= 3; k++) for (int u = -1; u <= 1; u++) out.add(key(fx(p, u, -k), fz(p, u, -k)));
            }
            case PLAZA -> {
                int r = p.a() / 10 + 2;
                for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) if (inPlaza(p, dx, dz)) out.add(key(p.x() + dx, p.z() + dz));
            }
            case POOL -> {
                for (var cell : poolWater(p)) out.add(key((int)cell[0], (int)cell[1]));
                out.addAll(poolShelf(p));
            }
            case PIT -> {
                for (var cell : pitDip(p)) out.add(key((int)cell[0], (int)cell[1]));
                out.addAll(pitRim(p));
            }
            case NURSERY -> {
                for (int dx = -p.a(); dx <= p.a(); dx++) for (int dz = -p.b(); dz <= p.b(); dz++) out.add(key(p.x() + dx, p.z() + dz));
            }
            case KNOLL -> {
                int r = (int)Math.ceil(p.a() / 10.0);
                double radius = p.a() / 10.0;
                for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) if (dx * dx + dz * dz <= radius * radius) out.add(key(p.x() + dx, p.z() + dz));
            }
        }
        return out;
    }
    /** The three levelled columns in front of a mound door, one wide on each side of the step. */
    public static Set<Long> lane(Part p) {
        var out = new HashSet<Long>();
        if (p.mound()) for (int k = 1; k <= 3; k++) for (int u = -1; u <= 1; u++) out.add(key(fx(p, u, -k), fz(p, u, -k)));
        return out;
    }
    /** Inclusive bounding rectangle {minX, minZ, maxX, maxZ} of a set of keyed columns. */
    public static int[] bounds(Set<Long> cells) {
        int[] r = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (long k : cells) {
            int x = keyX(k), z = keyZ(k);
            r[0] = Math.min(r[0], x); r[1] = Math.min(r[1], z); r[2] = Math.max(r[2], x); r[3] = Math.max(r[3], z);
        }
        return r;
    }
    private SiteGeom() {}
}
