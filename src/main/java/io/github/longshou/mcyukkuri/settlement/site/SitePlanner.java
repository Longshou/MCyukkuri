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
 * Procedural, rotation-aware layout of a settlement site (project-original; the wiki supports the loose elements: burrows
 * dug into banks and under roots, a trodden open ground they face, stores, a little brush that stops nothing). The burrows
 * are dug into the slope on one side of the clearing (the "hill side"), every mouth facing down the slope; the waste pit
 * and, where water belongs, a pond lie on the opposite, low side.
 * Pure function of its inputs; the layout is made in a local frame (clearing centre at 0,0, z south, hill to the north)
 * and rotated by quarter turns afterwards. The terrain only decides where the layout goes ({@link #choose},
 * {@link #bestRotation}), the standing level of every part and which parts fit; it never moves a part.
 */
public final class SitePlanner {
    /** Natural ground level (first free y) of a column. */
    public interface Ground { int at(int x, int z); }

    private static final class Draft {
        final SiteTier tier; final SiteRng rng;
        final double rp, limit, digLimit;
        final List<Part> parts = new ArrayList<>();
        final Set<Long> taken = new HashSet<>();
        final List<double[]> avoid = new ArrayList<>();
        Draft(SiteTier tier, SiteRng rng, double rp) {
            this.tier = tier; this.rng = rng; this.rp = rp;
            this.limit = tier == SiteTier.DEMO ? 1e9 : tier.hedge - 1.5;
            // Burrows run into the bank behind the clearing, so their backs may reach past the trodden ground.
            this.digLimit = tier == SiteTier.DEMO ? 1e9 : tier.bound - 2;
        }
        boolean place(Part p) { return place(p, limit); }
        boolean dig(Part p) { return place(p, digLimit); }
        private boolean place(Part p, double reach) {
            var cells = SiteGeom.cells(p);
            var lane = SiteGeom.lane(p);
            for (long k : cells) {
                int x = SiteGeom.keyX(k), z = SiteGeom.keyZ(k);
                if (Math.hypot(x, z) > reach) return false;
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
     * @param strict true for generated sites: the clearing must lie at the foot of a slope that most burrows can be dug
     *               into; false for deliberate placement (commands, museum), where a missing bank is heaped up instead
     * @return null when no layout fits the terrain ({@link #lastFailure} says why)
     */
    public static SitePlan plan(SiteTier tier, long seed, int rotation, int ox, int oz, int palette, Ground ground, boolean strict) {
        rotation = Math.floorMod(rotation, 4);
        var profile = SiteProfile.byOrdinal(palette);
        FAILURE.remove();
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
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
            for (int i = 0; i < levels.length; i++) {
                levels[i] = ground == null ? level : partLevel(parts.get(i), ground);
                // Water gathers in low ground only: a pond above the clearing would be a made thing.
                if (parts.get(i).kind() == Kind.POOL && levels[i] != SiteGeom.SKIP && levels[i] > level) levels[i] = SiteGeom.SKIP;
                // A home whose mouth would open far up the slope or down a drop is left out: the others are the settlement.
                if (ground != null && tier != SiteTier.DEMO && parts.get(i).kind() == Kind.HOME && Math.abs(levels[i] - level) > MAX_DOOR_RISE)
                    levels[i] = SiteGeom.SKIP;
            }
            var plan = new SitePlan(tier, seed, rotation, ox, oz, level, palette, parts, turn(local.paths(), rotation, ox, oz),
                    turn(local.works(), rotation, ox, oz), 0, tier.bound - 1.5, levels);
            if (strict && ground != null && tier != SiteTier.DEMO) {
                String bad = unfit(plan, ground);
                if (bad != null) { FAILURE.set(bad); return null; }
            }
            return plan;
        }
        if (FAILURE.get() == null) FAILURE.set("no layout");
        return null;
    }

    /** Highest a home door may stand above (or below) the clearing; the longhouse and stores must keep within it too. */
    private static final int MAX_DOOR_RISE = 8;
    /**
     * A burrow counts as dug into the slope when the natural ground at its back wall stands this high above its door: then
     * at most the front of its roof is made up (SiteBuilder.Job.burrow needs the surface three blocks over the floor).
     */
    private static final int DUG_RISE = 3;
    /** Layout draws are cheap and independent; a village fails a single draw fairly often, so many are made before giving up. */
    private static final int ATTEMPTS = 64;
    private static final ThreadLocal<String> FAILURE = new ThreadLocal<>();
    /** Why the last {@link #plan} or {@link #choose} on this thread returned null (the part that never found room, or the ground). */
    public static String lastFailure() { String s = FAILURE.get(); return s == null ? "no layout" : s; }
    private static Local why(String part) { FAILURE.set("no room for the " + part + " in " + ATTEMPTS + " layouts"); return null; }

    /**
     * Why a generated site would not be a hill-foot settlement, or null: the clearing must be near-level, the burrow doors
     * near the clearing level, the longhouse and at least three in five burrows backed by a slope at least {@link #DUG_RISE} blocks high.
     */
    private static String unfit(SitePlan plan, Ground ground) {
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (long key : SiteGeom.cells(plan.plaza())) {
            int h = ground.at(SiteGeom.keyX(key), SiteGeom.keyZ(key));
            low = Math.min(low, h); high = Math.max(high, h);
        }
        if (high - low > 3) return "the clearing is not level (" + (high - low) + " blocks)";
        int burrows = 0, dug = 0, homes = 0;
        boolean hall = !plan.tier.hall;
        for (int i = 0; i < plan.parts.size(); i++) {
            var p = plan.parts.get(i);
            if (!p.mound() || !plan.used(i)) continue;
            int door = plan.levelAt(i), rise = backRise(p, door, ground);
            if (Math.abs(door - plan.level) > MAX_DOOR_RISE) return p.kind() + " door stands " + (door - plan.level) + " blocks from the clearing";
            if (p.kind() == Kind.GRANARY) continue;
            if (p.kind() == Kind.HOME) homes++;
            if (rise > 12) return p.kind() + " lies under a cliff (" + rise + " blocks)";
            burrows++;
            if (rise >= DUG_RISE) { dug++; if (p.kind() == Kind.HALL) hall = true; }
        }
        if (homes < (plan.tier.hall ? 4 : 2)) return "only " + homes + " homes fit below the slope";
        if (!hall) return "no slope behind the longhouse";
        if (dug * 5 < burrows * 3) return "only " + dug + " of " + burrows + " burrows have a slope behind them";
        return null;
    }
    /** How far the natural ground at the back wall of the burrow rises above the door's standing level. */
    private static int backRise(Part p, int door, Ground ground) { return ground.at(SiteGeom.fx(p, 0, 4), SiteGeom.fz(p, 0, 4)) - door; }

    /**
     * Where a generated site goes: the plaza is moved by up to {@code shift} blocks around {@code (cx, cz)} and the layout
     * turned so that the hill side faces up the slope. Candidates whose probe finds no slope are not planned at all; of the
     * rest the best scoring hill-foot plan is taken. Null when none fits.
     */
    public static SitePlan choose(SiteTier tier, long seed, int cx, int cz, int palette, Ground ground, int shift) {
        SitePlan best = null;
        double bestScore = -1e9;
        String why = null;
        int step = Math.max(1, shift);
        for (int dx = -shift; dx <= shift; dx += step) for (int dz = -shift; dz <= shift; dz += step) for (int rotation = 0; rotation < 4; rotation++) {
            if (!slopes(ground, cx + dx, cz + dz, rotation)) continue;
            var plan = plan(tier, seed, rotation, cx + dx, cz + dz, palette, ground, true);
            if (plan == null) { why = lastFailure(); continue; }
            double score = score(plan, ground);
            if (score > bestScore) { bestScore = score; best = plan; }
        }
        if (best == null) FAILURE.set(why == null ? "no slope to dig into" : why);
        return best;
    }
    /** A cheap look before planning: the ground behind the clearing rises, the ground in front of it does not. */
    private static boolean slopes(Ground ground, int ox, int oz, int rotation) {
        int level = ground.at(ox, oz), rise = 0;
        int[][] back = {{0, -9}, {-6, -8}, {6, -8}};
        for (var b : back) { int[] t = turn(b[0], b[1], rotation); rise += ground.at(ox + t[0], oz + t[1]) - level; }
        int[] front = turn(0, 8, rotation);
        return rise >= 6 && ground.at(ox + front[0], oz + front[1]) <= level + 1;
    }

    /** The turn (0..3) that puts the burrows against the highest ground and the pool on the lowest; 0 when nothing fits. */
    public static int bestRotation(SiteTier tier, long seed, int ox, int oz, int palette, Ground ground) {
        int best = 0;
        double bestScore = -1e9;
        for (int rotation = 0; rotation < 4; rotation++) {
            var plan = plan(tier, seed, rotation, ox, oz, palette, ground, false);
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
            if (!plan.used(i)) { score -= p.kind() == Kind.POOL ? .5 : 1; continue; }
            if (!p.mound()) continue;
            // Uphill behind the door is a bank to dig into; a door far above or below the clearing is a poor address.
            int rise = backRise(p, plan.levelAt(i), ground);
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
            case PIT -> {
                var heights = new ArrayList<Integer>();
                for (var cell : SiteGeom.pitDip(p)) heights.add(ground.at((int)cell[0], (int)cell[1]));
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
        if (profile.pond) d.force(new Part(Kind.POOL, -5, 5, Direction.SOUTH, 13, 10, rng.nextInt(628)));
        d.force(new Part(Kind.PIT, 6, 6, Direction.SOUTH, 14, 10, rng.nextInt(628)));
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
        // The hill side is north in the local frame; the waste pit lies downhill at the edge of the trodden ground.
        double hill = -Math.PI / 2, low = Math.PI / 2;
        Part pit = null;
        for (int tries = 0; tries < 30 && pit == null; tries++) {
            double angle = low + rng.range(-.6, .6), r = hedge - (village ? 4.5 : 3.8) + rng.range(-1, 1);
            int x = (int)Math.floor(r * Math.cos(angle) + .5), z = (int)Math.floor(r * Math.sin(angle) + .5);
            var p = new Part(Kind.PIT, x, z, Direction.SOUTH, village ? 24 : 20, village ? 17 : 14, rng.nextInt(628));
            if (d.place(p)) pit = p;
        }
        if (pit == null) return why("waste pit");
        d.avoid.add(new double[]{pit.x(), pit.z(), village ? 10 : 8});
        // Every burrow is dug into the bank behind the clearing, its mouth facing down the slope.
        if (tier.hall) {
            Part hall = null;
            for (int tries = 0; tries < 30 && hall == null; tries++) {
                int x = (int)Math.floor(rng.range(-2.5, 2.5) + .5), z = -(int)Math.floor(rp + 2.2 + tries * .15 + rng.range(0, 1.2) + .5);
                var p = burrow(Kind.HALL, x, z, rng, deep);
                if (d.dig(p)) hall = p;
            }
            if (hall == null) return why("hall");
        }
        // Stores first (kept in the bank so the stock stays dry), then the homes fill the slope that is left.
        for (int i = 0; i < (village ? 2 : 1); i++) {
            Part granary = null;
            for (int tries = 0; tries < 120 && granary == null; tries++) {
                var p = burrow(Kind.GRANARY, footX(rng, village), footZ(rng, rp, village, false), rng, 0);
                if (d.dig(p)) granary = p;
            }
            if (granary == null) return why("granary");
        }
        for (int i = 0; i < tier.homes; i++) {
            Part home = null;
            for (int tries = 0; tries < 300 && home == null; tries++) {
                var p = burrow(Kind.HOME, footX(rng, village), footZ(rng, rp, village, village), rng, deep);
                if (d.dig(p)) home = p;
            }
            if (home == null) return why("home");
        }
        // The nursery is open grass beside the clearing, under the burrows and out of the way of the pit.
        int half = village ? 2 : 1;
        Part nursery = null;
        for (int tries = 0; tries < 60 && nursery == null; tries++) {
            double angle = hill + (rng.nextInt(2) == 0 ? 1 : -1) * rng.range(.9, 1.7), r = rp + 3 + rng.range(0, village ? 6 : 4);
            int x = (int)Math.floor(r * Math.cos(angle) + .5), z = (int)Math.floor(r * Math.sin(angle) + .5);
            var p = new Part(Kind.NURSERY, x, z, Direction.SOUTH, half, half, 0);
            if (d.place(p)) nursery = p;
        }
        if (nursery == null) return why("nursery");
        // A pond only where water belongs; without one the settlers drink at the nearest real water.
        if (profile.pond) {
            d.avoid.clear();
            d.avoid.add(new double[]{pit.x(), pit.z(), village ? 11 : 8});
            int[] pool = village ? new int[]{19, 14} : new int[]{14, 11};
            for (int tries = 0; tries < 100; tries++) {
                double angle = low + rng.range(-1.3, 1.3), r = hedge * rng.range(.45, .8);
                int x = (int)Math.floor(r * Math.cos(angle) + .5), z = (int)Math.floor(r * Math.sin(angle) + .5);
                if (d.place(new Part(Kind.POOL, x, z, Direction.SOUTH, pool[0], pool[1], rng.nextInt(628)))) break;
            }
        }
        d.avoid.clear();
        if (village) {
            // The lookout is the rise above the burrows; failing room there, a low knoll at the side of the clearing.
            Part knoll = null;
            for (int tries = 0; tries < 40 && knoll == null; tries++) {
                double angle = hill + rng.range(-.7, .7), r = hedge - 2.5 + rng.range(-1.5, 1.5);
                int x = (int)Math.floor(r * Math.cos(angle) + .5), z = (int)Math.floor(r * Math.sin(angle) + .5);
                var p = new Part(Kind.KNOLL, x, z, Direction.SOUTH, 30, 0, 0);
                if (d.dig(p)) knoll = p;
            }
            for (int tries = 0; tries < 40 && knoll == null; tries++) {
                double angle = low + (rng.nextInt(2) == 0 ? 1 : -1) * (Math.PI / 2 + rng.range(-.5, .5)), r = hedge - 5;
                int x = (int)Math.floor(r * Math.cos(angle) + .5), z = (int)Math.floor(r * Math.sin(angle) + .5);
                var p = new Part(Kind.KNOLL, x, z, Direction.SOUTH, 30, 0, 0);
                if (d.place(p)) knoll = p;
            }
            if (knoll == null) return why("knoll");
        }
        var paths = new HashMap<Long, Byte>();
        var blocked = new HashSet<Long>(d.taken);
        blocked.addAll(SiteGeom.cells(d.parts.getFirst()));
        // The trails out run down the slope and along its foot, never up over the burrow roofs; the loose cover between them
        // is only a scatter of clumps, never a ring.
        double[] gaps = {low + rng.range(-.7, .7), rng.range(-.5, .4), Math.PI + rng.range(-.4, .5)};
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

    /**
     * Burrow mouths lie along the foot of the slope: spread sideways along it, most within a few blocks of the clearing's
     * edge and only a few a little way up the bank (a slope rises fast; doors high up it are left out by the terrain).
     */
    private static int footX(SiteRng rng, boolean village) { return (int)Math.floor(rng.range(-1, 1) * (village ? 21 : 10) + .5); }
    private static int footZ(SiteRng rng, double rp, boolean village, boolean upper) {
        // a village is too wide for one row: some of its homes open a row higher up the bank (stores stay at the foot)
        double up = upper && rng.nextDouble() < .4 ? 7 + rng.range(0, 2) : rng.nextDouble() * rng.nextDouble() * (village ? 4 : 5);
        return -(int)Math.floor(rp + 2 + up + .5);
    }
    private static Part burrow(Kind kind, int x, int z, SiteRng rng, int deep) {
        return new Part(kind, x, z, Direction.SOUTH, rng.nextInt(1 << 16), deep, 0);
    }

    /** Paths from the clearing edge to every door lane end, the pool, the waste pit, the knoll and the nursery. */
    private static void connect(Draft d, Map<Long, Byte> paths, Set<Long> blocked) {
        double rp = d.rp;
        for (var p : d.parts) {
            double tx, tz;
            double len0 = Math.max(Math.hypot(p.x(), p.z()), 1);
            switch (p.kind()) {
                case HOME, HALL, GRANARY -> { tx = p.x() + p.facing().getStepX() * 4; tz = p.z() + p.facing().getStepZ() * 4; }
                case POOL, PIT -> {
                    double reach = Math.max(p.a(), p.b()) / 10.0 + 2.2;
                    tx = p.x() - p.x() / len0 * reach; tz = p.z() - p.z() / len0 * reach;
                }
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
