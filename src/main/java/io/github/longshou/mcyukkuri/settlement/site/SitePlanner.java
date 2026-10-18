package io.github.longshou.mcyukkuri.settlement.site;

import io.github.longshou.mcyukkuri.settlement.site.SiteGeom.Kind;
import io.github.longshou.mcyukkuri.settlement.site.SiteGeom.Part;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Direction;

/**
 * Procedural, rotation-aware layout of a settlement site (project-original; the wiki only supports the loose elements:
 * burrows dug into earth, a trodden open ground they face, stores, a little brush that stops nothing). The burrows are
 * gathered on one side of the clearing (the "hill side"), the latrine and the pool lie on the opposite, low side.
 * Pure function of its inputs; the layout is made in a local frame (clearing centre at 0,0, z south, hill to the north)
 * and rotated by quarter turns afterwards. The terrain only decides the rotation ({@link #bestRotation}), the standing
 * level of every part and which parts fit.
 */
public final class SitePlanner {
    /** Natural ground level (first free y) of a column. */
    public interface Ground { int at(int x, int z); }

    private static final class Draft {
        final SiteTier tier; final SiteRng rng;
        final double rp, limit;
        final List<Part> parts = new ArrayList<>();
        final Set<Long> taken = new HashSet<>();
        final List<double[]> avoid = new ArrayList<>();
        Draft(SiteTier tier, SiteRng rng, double rp) {
            this.tier = tier; this.rng = rng; this.rp = rp;
            this.limit = tier == SiteTier.DEMO ? 1e9 : tier.hedge - 1.5;
        }
        boolean place(Part p) {
            var cells = SiteGeom.cells(p);
            var lane = SiteGeom.lane(p);
            for (long k : cells) {
                int x = SiteGeom.keyX(k), z = SiteGeom.keyZ(k);
                if (Math.hypot(x, z) > limit) return false;
                if (!lane.contains(k) && Math.hypot(x, z) <= rp + 1.3) return false;
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (taken.contains(SiteGeom.key(x + dx, z + dz))) return false;
            }
            for (var a : avoid) if (Math.hypot(p.x() - a[0], p.z() - a[1]) < a[2]) return false;
            parts.add(p); taken.addAll(cells);
            return true;
        }
        void force(Part p) { parts.add(p); taken.addAll(SiteGeom.cells(p)); }
    }

    public static Direction toward(double vx, double vz) {
        return Math.abs(vx) >= Math.abs(vz) ? (vx > 0 ? Direction.EAST : Direction.WEST) : (vz > 0 ? Direction.SOUTH : Direction.NORTH);
    }

    /**
     * @param ground samples natural ground for the terrain checks; null skips them (every level is then 64)
     * @param strict true for generated sites: refuse cliffs; false for deliberate placement (commands, museum)
     * @return null when no layout fits the terrain
     */
    public static SitePlan plan(SiteTier tier, long seed, int rotation, int ox, int oz, int palette, Ground ground, boolean strict) {
        rotation = Math.floorMod(rotation, 4);
        var profile = SiteProfile.byOrdinal(palette);
        if (strict && ground != null && tier != SiteTier.DEMO) {
            int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
            int reach = (int)tier.hedge;
            for (int dx = -reach; dx <= reach; dx += 3) for (int dz = -reach; dz <= reach; dz += 3) {
                if (Math.hypot(dx, dz) > tier.hedge) continue;
                int h = ground.at(ox + dx, oz + dz);
                low = Math.min(low, h); high = Math.max(high, h);
            }
            if (high - low > 14) return null;
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            var rng = new SiteRng(seed * 31 + attempt * 7919L + 17);
            var local = tier == SiteTier.DEMO ? demo(rng, profile) : layout(tier, rng, profile);
            if (local == null) continue;
            var parts = new ArrayList<Part>();
            for (var p : local.parts()) {
                int[] t = turn(p.x(), p.z(), rotation);
                var facing = p.facing();
                for (int i = 0; i < rotation; i++) facing = facing.getClockWise();
                parts.add(new Part(p.kind(), ox + t[0], oz + t[1], facing, p.a(), p.b(), p.c()));
            }
            int level = ground == null ? 64 : ground.at(ox, oz);
            int[] levels = new int[parts.size()];
            for (int i = 0; i < levels.length; i++) levels[i] = ground == null ? level : partLevel(parts.get(i), ground);
            return new SitePlan(tier, seed, rotation, ox, oz, level, palette, parts, turn(local.paths(), rotation, ox, oz),
                    turn(local.works(), rotation, ox, oz), 0, tier.bound - 1.5, levels);
        }
        return null;
    }

    /** The turn (0..3) that puts the burrows on the highest ground and the pool on the lowest; 0 when nothing fits. */
    public static int bestRotation(SiteTier tier, long seed, int ox, int oz, int palette, Ground ground) {
        int best = 0;
        double bestScore = -1e9;
        for (int rotation = 0; rotation < 4; rotation++) {
            var plan = plan(tier, seed, rotation, ox, oz, palette, ground, true);
            if (plan == null) continue;
            double score = score(plan, ground);
            if (score > bestScore) { bestScore = score; best = rotation; }
        }
        return best;
    }

    private static double score(SitePlan plan, Ground ground) {
        double score = 0;
        for (int i = 0; i < plan.parts.size(); i++) {
            var p = plan.parts.get(i);
            if (!plan.used(i)) { score -= 1; continue; }
            if (!p.mound()) continue;
            // Uphill behind the door is a hillside to dig into; a door far above or below the clearing is a poor address.
            int rise = ground.at(SiteGeom.fx(p, 0, 4), SiteGeom.fz(p, 0, 4)) - plan.levelAt(i);
            score += Math.max(-2, Math.min(5, rise)) * (p.kind() == Kind.HALL ? 1.5 : 1.0);
            score -= Math.max(0, Math.abs(plan.levelAt(i) - plan.level) - 2);
        }
        return score;
    }

    /** The standing level of one part on this ground, or {@link SiteGeom#SKIP} when the ground does not suit it. */
    private static int partLevel(Part p, Ground ground) {
        switch (p.kind()) {
            case HOME, HALL, GRANARY -> { return ground.at(p.x(), p.z()); }
            case PLAZA -> { return ground.at(p.x(), p.z() + 1); }
            case NURSERY -> { return ground.at(p.x(), p.z()); }
            case POOL -> {
                int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
                for (long key : SiteGeom.cells(p)) {
                    int h = ground.at(SiteGeom.keyX(key), SiteGeom.keyZ(key));
                    low = Math.min(low, h); high = Math.max(high, h);
                }
                // A pond needs near-level banks; on a slope the settlers simply use the stream.
                return high - low > 2 ? SiteGeom.SKIP : low;
            }
            case LATRINE -> {
                var heights = new ArrayList<Integer>();
                for (long key : SiteGeom.cells(p)) heights.add(ground.at(SiteGeom.keyX(key), SiteGeom.keyZ(key)));
                heights.sort(null);
                return heights.get(heights.size() / 2);
            }
            case KNOLL -> {
                int high = Integer.MIN_VALUE;
                for (long key : SiteGeom.cells(p)) {
                    int dx = SiteGeom.keyX(key) - p.x(), dz = SiteGeom.keyZ(key) - p.z();
                    if (dx * dx + dz * dz <= 2) high = Math.max(high, ground.at(SiteGeom.keyX(key), SiteGeom.keyZ(key)));
                }
                return high;
            }
        }
        return ground.at(p.x(), p.z());
    }

    private record Local(List<Part> parts, Map<Long, Byte> paths, Map<Long, Byte> works) {}

    static int[] turn(int x, int z, int quarter) {
        for (int i = 0; i < quarter; i++) { int t = x; x = -z; z = t; }
        return new int[]{x, z};
    }
    private static Map<Long, Byte> turn(Map<Long, Byte> cells, int quarter, int ox, int oz) {
        var out = new HashMap<Long, Byte>();
        cells.forEach((k, v) -> {
            int[] t = turn(SiteGeom.keyX(k), SiteGeom.keyZ(k), quarter);
            out.put(SiteGeom.key(ox + t[0], oz + t[1]), v);
        });
        return out;
    }

    // ---- the fixed exhibit: longhouse north, two family burrows west and east, granary south
    private static Local demo(SiteRng rng, SiteProfile profile) {
        var d = new Draft(SiteTier.DEMO, rng, 2.5);
        int deep = profile.deep;
        d.force(new Part(Kind.PLAZA, 0, 0, Direction.SOUTH, 20, 0, rng.nextInt(628)));
        d.force(new Part(Kind.HALL, 0, -4, Direction.SOUTH, rng.nextInt(1 << 16), deep, 0));
        d.force(new Part(Kind.HOME, -3, 0, Direction.EAST, rng.nextInt(1 << 16), deep, 0));
        d.force(new Part(Kind.HOME, 3, 0, Direction.WEST, rng.nextInt(1 << 16), deep, 0));
        d.force(new Part(Kind.GRANARY, 0, 4, Direction.NORTH, rng.nextInt(1 << 16), 0, 0));
        d.force(new Part(Kind.POOL, -5, 5, Direction.SOUTH, 13, 10, rng.nextInt(628)));
        d.force(new Part(Kind.LATRINE, 6, 6, Direction.NORTH, 0, 2, 0));
        d.force(new Part(Kind.NURSERY, 6, -6, Direction.SOUTH, 1, 1, 0));
        d.force(new Part(Kind.KNOLL, -6, -6, Direction.SOUTH, 30, 0, 0));
        var paths = new HashMap<Long, Byte>();
        var blocked = new HashSet<Long>(d.taken);
        connect(d, paths, blocked);
        return new Local(d.parts, paths, new HashMap<>());
    }

    // ---- bands and villages
    private static Local layout(SiteTier tier, SiteRng rng, SiteProfile profile) {
        boolean village = tier == SiteTier.VILLAGE;
        double rp = village ? 3.5 : 2.5, hedge = tier.hedge;
        int deep = profile.deep;
        var d = new Draft(tier, rng, rp);
        d.parts.add(new Part(Kind.PLAZA, 0, 0, Direction.SOUTH, (int)Math.round(rp * 10), 0, rng.nextInt(628)));
        // The hill side is north in the local frame; the latrine and the pond lie downhill, opposite it.
        double hill = -Math.PI / 2, low = Math.PI / 2;
        Part latrine = null;
        for (int tries = 0; tries < 30 && latrine == null; tries++) {
            double angle = low + rng.range(-.5, .5), r = hedge - (village ? 5.5 : 4.8) + rng.range(-1, 1);
            int x = (int)Math.floor(r * Math.cos(angle) + .5), z = (int)Math.floor(r * Math.sin(angle) + .5);
            var p = new Part(Kind.LATRINE, x, z, toward(-x, -z), village ? 1 : 0, village ? 3 : 2, 0);
            if (d.place(p)) latrine = p;
        }
        if (latrine == null) return null;
        d.avoid.add(new double[]{latrine.x(), latrine.z(), village ? 10 : 8});
        if (tier.hall) {
            Part hall = null;
            for (int tries = 0; tries < 30 && hall == null; tries++) {
                double angle = hill + rng.range(-.45, .45), r = rp + 3.2 + tries * .15;
                var p = mound(Kind.HALL, angle, r, rng, deep);
                if (d.place(p)) hall = p;
            }
            if (hall == null) return null;
        }
        // Stores first (kept to the hill side so the stock stays dry), then the pond, then the homes fill the room that is left.
        for (int i = 0; i < (village ? 2 : 1); i++) {
            Part granary = null;
            for (int tries = 0; tries < 120 && granary == null; tries++) {
                double angle = hill + rng.range(-1.6, 1.6), r = rp + 3.2 + rng.range(0, village ? 7 : 5);
                var p = mound(Kind.GRANARY, angle, r, rng, 0);
                if (d.place(p)) granary = p;
            }
            if (granary == null) return null;
        }
        int[] pool = village ? new int[]{19, 14} : new int[]{14, 11};
        Part water = null;
        d.avoid.clear();
        d.avoid.add(new double[]{latrine.x(), latrine.z(), village ? 11 : 8});
        for (int tries = 0; tries < 100 && water == null; tries++) {
            double angle = low + rng.range(-1.3, 1.3), r = hedge * rng.range(.4, .75);
            int x = (int)Math.floor(r * Math.cos(angle) + .5), z = (int)Math.floor(r * Math.sin(angle) + .5);
            var p = new Part(Kind.POOL, x, z, Direction.SOUTH, pool[0], pool[1], rng.nextInt(628));
            if (d.place(p)) water = p;
        }
        if (water == null) return null;
        d.avoid.clear();
        d.avoid.add(new double[]{latrine.x(), latrine.z(), village ? 8 : 6});
        for (int i = 0; i < tier.homes; i++) {
            Part home = null;
            for (int tries = 0; tries < 200 && home == null; tries++) {
                double spread = tries < 100 ? 1.7 : 2.6;
                double angle = hill + rng.range(-spread, spread), r = rp + 3.2 + rng.nextDouble() * rng.nextDouble() * (village ? 12 : 8);
                var p = mound(Kind.HOME, angle, r, rng, deep);
                if (d.place(p)) home = p;
            }
            if (home == null) return null;
        }
        int half = village ? 2 : 1;
        Part nursery = null;
        for (int tries = 0; tries < 60 && nursery == null; tries++) {
            double angle = hill + rng.range(-1.5, 1.5), r = rp + 4.5 + rng.range(0, village ? 7 : 4);
            int x = (int)Math.floor(r * Math.cos(angle) + .5), z = (int)Math.floor(r * Math.sin(angle) + .5);
            var p = new Part(Kind.NURSERY, x, z, Direction.SOUTH, half, half, 0);
            if (d.place(p)) nursery = p;
        }
        if (nursery == null) return null;
        if (village) {
            Part knoll = null;
            for (int tries = 0; tries < 40 && knoll == null; tries++) {
                double angle = low + (rng.nextInt(2) == 0 ? 1 : -1) * (Math.PI / 2 + rng.range(-.5, .5)), r = hedge - 5;
                int x = (int)Math.floor(r * Math.cos(angle) + .5), z = (int)Math.floor(r * Math.sin(angle) + .5);
                var p = new Part(Kind.KNOLL, x, z, Direction.SOUTH, 30, 0, 0);
                if (d.place(p)) knoll = p;
            }
            if (knoll == null) return null;
        }
        var paths = new HashMap<Long, Byte>();
        var blocked = new HashSet<Long>(d.taken);
        blocked.addAll(SiteGeom.cells(d.parts.getFirst()));
        // A few trodden ways leave the clearing; the loose cover between them is only a scatter of clumps, never a ring.
        double g0 = rng.range(0, Math.PI * 2);
        double[] gaps = {g0, g0 + 2.1 + rng.range(-.3, .3), g0 + 4.2 + rng.range(-.3, .3)};
        var works = new HashMap<Long, Byte>();
        int clumps = village ? 11 : 6;
        for (int i = 0; i < clumps; i++) {
            double theta = rng.range(0, Math.PI * 2);
            boolean nearGap = false;
            for (double g : gaps) if (Math.abs(Math.atan2(Math.sin(theta - g), Math.cos(theta - g))) < .3) nearGap = true;
            double r = hedge + rng.range(-3, 2.5);
            int kind = rng.nextInt(4), size = 1 + rng.nextInt(3);
            double tx = -Math.sin(theta), tz = Math.cos(theta);
            for (int k = 0; k < size && !nearGap; k++) {
                int x = (int)Math.floor(r * Math.cos(theta) + tx * k + .5), z = (int)Math.floor(r * Math.sin(theta) + tz * k + .5);
                if (!blocked.contains(SiteGeom.key(x, z))) works.putIfAbsent(SiteGeom.key(x, z), (byte)kind);
            }
        }
        connect(d, paths, blocked);
        for (double g : gaps) {
            double ex = Math.cos(g) * (hedge + 3), ez = Math.sin(g) * (hedge + 3);
            curve(paths, rng, ex, ez, Math.cos(g) * rp, Math.sin(g) * rp, blocked);
        }
        return new Local(d.parts, paths, works);
    }

    private static Part mound(Kind kind, double angle, double r, SiteRng rng, int deep) {
        int x = (int)Math.floor(r * Math.cos(angle) + .5), z = (int)Math.floor(r * Math.sin(angle) + .5);
        return new Part(kind, x, z, toward(-x, -z), rng.nextInt(1 << 16), deep, 0);
    }

    /** Paths from the clearing edge to every door lane end, the pool, the latrine, the knoll and the nursery. */
    private static void connect(Draft d, Map<Long, Byte> paths, Set<Long> blocked) {
        double rp = d.rp;
        for (var p : d.parts) {
            double tx, tz;
            double len0 = Math.max(Math.hypot(p.x(), p.z()), 1);
            switch (p.kind()) {
                case HOME, HALL, GRANARY -> { tx = p.x() + p.facing().getStepX() * 4; tz = p.z() + p.facing().getStepZ() * 4; }
                case POOL -> {
                    double reach = Math.max(p.a(), p.b()) / 10.0 + 2.2;
                    tx = p.x() - p.x() / len0 * reach; tz = p.z() - p.z() / len0 * reach;
                }
                case LATRINE -> { tx = p.x() + p.facing().getStepX() * 1.5; tz = p.z() + p.facing().getStepZ() * 1.5; }
                case KNOLL -> {
                    double reach = p.a() / 10.0 + 1.5;
                    tx = p.x() - p.x() / len0 * reach; tz = p.z() - p.z() / len0 * reach;
                }
                case NURSERY -> {
                    double reach = Math.max(p.a(), p.b()) + 1.8;
                    tx = p.x() - p.x() / len0 * reach; tz = p.z() - p.z() / len0 * reach;
                }
                default -> { continue; }
            }
            double len = Math.hypot(tx, tz);
            if (len <= rp + .8) continue;
            curve(paths, d.rng, tx, tz, tx / len * rp, tz / len * rp, blocked);
        }
    }

    /** A gently bent trodden strip, mostly one block wide with occasional widening. */
    private static void curve(Map<Long, Byte> out, SiteRng rng, double x0, double z0, double x1, double z1, Set<Long> blocked) {
        double dx = x1 - x0, dz = z1 - z0, len = Math.hypot(dx, dz);
        if (len < 1) return;
        double bend = (rng.nextDouble() - .5) * .45 * len;
        double cx = (x0 + x1) / 2 - dz / len * bend, cz = (z0 + z1) / 2 + dx / len * bend;
        int steps = (int)Math.ceil(len * 2.5);
        for (int i = 0; i <= steps; i++) {
            double t = i / (double)steps, mt = 1 - t;
            double x = mt * mt * x0 + 2 * mt * t * cx + t * t * x1, z = mt * mt * z0 + 2 * mt * t * cz + t * t * z1;
            double tx = 2 * mt * (cx - x0) + 2 * t * (x1 - cx), tz = 2 * mt * (cz - z0) + 2 * t * (z1 - cz);
            int ix = (int)Math.floor(x + .5), iz = (int)Math.floor(z + .5);
            walk(out, rng, ix, iz, blocked);
            double tl = Math.hypot(tx, tz);
            if (rng.nextDouble() < .25 && tl > 0) walk(out, rng, ix + (int)Math.floor(-tz / tl + .5), iz + (int)Math.floor(tx / tl + .5), blocked);
        }
    }
    private static void walk(Map<Long, Byte> out, SiteRng rng, int x, int z, Set<Long> blocked) {
        int m = rng.nextInt(100);
        long key = SiteGeom.key(x, z);
        if (!blocked.contains(key)) out.putIfAbsent(key, (byte)(m < 70 ? 0 : m < 85 ? 1 : 2));
    }
    private SitePlanner() {}
}
