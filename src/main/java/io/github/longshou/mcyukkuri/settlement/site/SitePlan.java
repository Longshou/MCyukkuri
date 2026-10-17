package io.github.longshou.mcyukkuri.settlement.site;

import io.github.longshou.mcyukkuri.settlement.site.SiteGeom.Kind;
import io.github.longshou.mcyukkuri.settlement.site.SiteGeom.Part;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * The finished layout of one site in world coordinates. Pure data: it is computed once from (tier, seed, rotation, origin,
 * profile, ground samples) and can be re-derived at any time. The layout itself never depends on the terrain; what the
 * terrain decides is the standing level of every part ({@link #levelOf}) and which parts the ground allows. Those levels are
 * the only terrain data that has to be stored with a site.
 */
public final class SitePlan {
    public final SiteTier tier;
    public final long seed;
    public final int rotation, ox, oz, level, palette;
    /** The gathering cell is {@code (ox, oz)}; everything else is placed around it. */
    public final List<Part> parts;
    /** Worn paths: column key to material code (0 coarse dirt, 1 dirt path, 2 podzol). */
    public final Map<Long, Byte> paths;
    /** Loose low cover at the edge of the ground: column key to kind (0 brush, 1 stones, 2 fallen branch, 3 leaf heap). */
    public final Map<Long, Byte> works;
    public final double meadowInner, meadowOuter;
    public final int minX, minZ, maxX, maxZ;
    public final Set<Long> flat = new HashSet<>();
    private final int[] levels;
    private List<SiteSlot> slots;
    private List<int[]> reserved;

    public SitePlan(SiteTier tier, long seed, int rotation, int ox, int oz, int level, int palette, List<Part> parts,
                    Map<Long, Byte> paths, Map<Long, Byte> works, double meadowInner, double meadowOuter, int[] levels) {
        this.tier = tier; this.seed = seed; this.rotation = rotation; this.ox = ox; this.oz = oz; this.level = level; this.palette = palette;
        this.parts = List.copyOf(parts); this.paths = paths; this.works = works; this.meadowInner = meadowInner; this.meadowOuter = meadowOuter;
        this.levels = new int[parts.size()];
        Arrays.fill(this.levels, level);
        if (levels != null && levels.length == this.levels.length) System.arraycopy(levels, 0, this.levels, 0, levels.length);
        for (int i = 0; i < this.parts.size(); i++) if (this.levels[i] != SiteGeom.SKIP) flat.addAll(SiteGeom.cells(this.parts.get(i)));
        minX = ox - tier.bound; maxX = ox + tier.bound; minZ = oz - tier.bound; maxZ = oz + tier.bound;
    }

    /** The same layout with the standing levels that were stored when the site was built. */
    public SitePlan withLevels(int[] stored) {
        return new SitePlan(tier, seed, rotation, ox, oz, level, palette, parts, paths, works, meadowInner, meadowOuter, stored);
    }
    public SiteProfile profile() { return SiteProfile.byOrdinal(palette); }
    public BlockPos center() { return new BlockPos(ox, level, oz); }
    public Part plaza() { for (var part : parts) if (part.kind() == Kind.PLAZA) return part; return null; }
    /** Standing level of the part: the first free block above its ground (a burrow door's floor is this level). */
    public int levelOf(Part part) { int i = parts.indexOf(part); return i < 0 ? level : levels[i]; }
    public int levelAt(int index) { return levels[index]; }
    public boolean used(int index) { return levels[index] != SiteGeom.SKIP; }
    /** A copy of the stored levels (one per part, {@link SiteGeom#SKIP} for parts the ground did not allow). */
    public int[] levels() { return levels.clone(); }

    public synchronized List<SiteSlot> slots() {
        if (slots != null) return slots;
        var out = new ArrayList<SiteSlot>();
        for (int i = 0; i < parts.size(); i++) {
            if (levels[i] == SiteGeom.SKIP) continue;
            var p = parts.get(i);
            int y = levels[i];
            switch (p.kind()) {
                case HOME, HALL -> out.add(new SiteSlot(p.kind() == Kind.HALL ? SiteSlot.Kind.HALL : SiteSlot.Kind.HOME,
                        new BlockPos(SiteGeom.fx(p, 0, 1), y, SiteGeom.fz(p, 0, 1)), p.facing(), Math.min(16, SiteGeom.dims(p)[3] * (p.kind() == Kind.HALL ? 5 : 3))));
                case GRANARY -> {
                    out.add(new SiteSlot(SiteSlot.Kind.GRANARY, new BlockPos(p.x(), y, p.z()), p.facing(), 0));
                    Direction right = p.facing().getClockWise();
                    out.add(new SiteSlot(SiteSlot.Kind.STORE, new BlockPos(SiteGeom.fx(p, -1, 1), y, SiteGeom.fz(p, -1, 1)), right, 0));
                    out.add(new SiteSlot(SiteSlot.Kind.STORE, new BlockPos(SiteGeom.fx(p, 1, 1), y, SiteGeom.fz(p, 1, 1)), right.getOpposite(), 0));
                }
                case PLAZA -> out.add(new SiteSlot(SiteSlot.Kind.PLAZA, new BlockPos(p.x(), y, p.z() + 1), Direction.SOUTH, 0));
                case POOL -> {
                    var water = new ArrayList<long[]>(SiteGeom.poolWater(p));
                    water.sort(Comparator.comparingDouble((long[] c) -> (c[0] - ox) * (double)(c[0] - ox) + (c[1] - oz) * (double)(c[1] - oz)).thenComparingLong(c -> c[0]).thenComparingLong(c -> c[1]));
                    for (int k = 0; k < Math.min(3, water.size()); k++)
                        out.add(new SiteSlot(SiteSlot.Kind.WATER, new BlockPos((int)water.get(k)[0], y - 2, (int)water.get(k)[1]), Direction.UP, 0));
                }
                case LATRINE -> {
                    out.add(new SiteSlot(SiteSlot.Kind.LATRINE, new BlockPos(SiteGeom.fx(p, -1, 1), y, SiteGeom.fz(p, -1, 1)), p.facing(), 0));
                    out.add(new SiteSlot(SiteSlot.Kind.LATRINE, new BlockPos(SiteGeom.fx(p, 1, 1), y, SiteGeom.fz(p, 1, 1)), p.facing(), 0));
                    if (p.a() > 0) out.add(new SiteSlot(SiteSlot.Kind.LATRINE, new BlockPos(SiteGeom.fx(p, 0, 2), y, SiteGeom.fz(p, 0, 2)), p.facing(), 0));
                }
                case KNOLL -> out.add(new SiteSlot(SiteSlot.Kind.LOOKOUT, new BlockPos(p.x() + 1, y + 2, p.z()), Direction.UP, 0));
                case NURSERY -> out.add(new SiteSlot(SiteSlot.Kind.NURSERY, new BlockPos(p.x(), y, p.z()), Direction.UP, 0));
            }
        }
        slots = List.copyOf(out);
        return slots;
    }

    /** Columns that ordinary shelter hunting must never claim: granary hollows, the plaza and the nursery. {minX,minZ,maxX,maxZ}. */
    public synchronized List<int[]> reserved() {
        if (reserved != null) return reserved;
        var out = new ArrayList<int[]>();
        for (int i = 0; i < parts.size(); i++) {
            var p = parts.get(i);
            if (levels[i] != SiteGeom.SKIP && (p.kind() == Kind.GRANARY || p.kind() == Kind.PLAZA || p.kind() == Kind.NURSERY)) out.add(SiteGeom.bounds(SiteGeom.cells(p)));
        }
        reserved = List.copyOf(out);
        return reserved;
    }
}
