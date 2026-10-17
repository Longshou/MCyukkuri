package io.github.longshou.mcyukkuri.settlement.site;

import io.github.longshou.mcyukkuri.settlement.site.SiteGeom.Kind;
import io.github.longshou.mcyukkuri.settlement.site.SiteGeom.Part;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * The finished layout of one site in world coordinates. Pure data: it is computed once from (tier, seed, rotation, origin,
 * ground samples) and can be re-derived at any time, so a structure piece only has to store those inputs.
 */
public final class SitePlan {
    public final SiteTier tier;
    public final long seed;
    public final int rotation, ox, oz, level, palette;
    /** The gathering cell is {@code (ox, oz)} (the speaker's stump); everything else is placed around it. */
    public final List<Part> parts;
    /** Worn paths: column key to material code (0 coarse dirt, 1 dirt path, 2 podzol). */
    public final Map<Long, Byte> paths;
    /** Hedge columns: 0 one block high, 1 two blocks high. */
    public final Map<Long, Byte> hedge;
    public final double meadowInner, meadowOuter;
    public final int minX, minZ, maxX, maxZ;
    public final Set<Long> flat = new HashSet<>();
    private Map<Long, Integer> skirt;
    private List<SiteSlot> slots;
    private List<int[]> reserved;

    public SitePlan(SiteTier tier, long seed, int rotation, int ox, int oz, int level, int palette, List<Part> parts,
                    Map<Long, Byte> paths, Map<Long, Byte> hedge, double meadowInner, double meadowOuter) {
        this.tier = tier; this.seed = seed; this.rotation = rotation; this.ox = ox; this.oz = oz; this.level = level; this.palette = palette;
        this.parts = List.copyOf(parts); this.paths = paths; this.hedge = hedge; this.meadowInner = meadowInner; this.meadowOuter = meadowOuter;
        for (var part : parts) flat.addAll(SiteGeom.cells(part));
        minX = ox - tier.bound; maxX = ox + tier.bound; minZ = oz - tier.bound; maxZ = oz + tier.bound;
    }

    public BlockPos center() { return new BlockPos(ox, level, oz); }
    public Part plaza() { for (var part : parts) if (part.kind() == Kind.PLAZA) return part; return null; }

    /** Columns within three blocks of a levelled part: they ease from the natural height to the site level. */
    public synchronized Map<Long, Integer> skirt() {
        if (skirt != null) return skirt;
        skirt = new HashMap<>();
        var frontier = new ArrayList<Long>(flat);
        var seen = new HashSet<Long>(flat);
        for (int ring = 1; ring <= 3; ring++) {
            var next = new ArrayList<Long>();
            for (long key : frontier) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                long near = SiteGeom.key(SiteGeom.keyX(key) + dx, SiteGeom.keyZ(key) + dz);
                if (seen.add(near)) { skirt.put(near, ring); next.add(near); }
            }
            frontier = next;
        }
        return skirt;
    }

    public synchronized List<SiteSlot> slots() {
        if (slots != null) return slots;
        var out = new ArrayList<SiteSlot>();
        for (var p : parts) {
            switch (p.kind()) {
                case HOME, HALL -> out.add(new SiteSlot(p.kind() == Kind.HALL ? SiteSlot.Kind.HALL : SiteSlot.Kind.HOME,
                        new BlockPos(SiteGeom.fx(p, 0, 1), level, SiteGeom.fz(p, 0, 1)), p.facing(), p.kind() == Kind.HALL ? 15 : 9));
                case GRANARY -> {
                    out.add(new SiteSlot(SiteSlot.Kind.GRANARY, new BlockPos(p.x(), level, p.z()), p.facing(), 0));
                    Direction right = p.facing().getClockWise();
                    out.add(new SiteSlot(SiteSlot.Kind.STORE, new BlockPos(SiteGeom.fx(p, -1, 1), level, SiteGeom.fz(p, -1, 1)), right, 0));
                    out.add(new SiteSlot(SiteSlot.Kind.STORE, new BlockPos(SiteGeom.fx(p, 1, 1), level, SiteGeom.fz(p, 1, 1)), right.getOpposite(), 0));
                }
                case PLAZA -> out.add(new SiteSlot(SiteSlot.Kind.PLAZA, new BlockPos(p.x(), level, p.z() + 1), Direction.SOUTH, 0));
                case POOL -> {
                    var water = new ArrayList<long[]>(SiteGeom.poolWater(p));
                    water.sort(Comparator.comparingDouble((long[] c) -> (c[0] - ox) * (double)(c[0] - ox) + (c[1] - oz) * (double)(c[1] - oz)).thenComparingLong(c -> c[0]).thenComparingLong(c -> c[1]));
                    for (int i = 0; i < Math.min(3, water.size()); i++)
                        out.add(new SiteSlot(SiteSlot.Kind.WATER, new BlockPos((int)water.get(i)[0], level - 2, (int)water.get(i)[1]), Direction.UP, 0));
                }
                case LATRINE -> {
                    out.add(new SiteSlot(SiteSlot.Kind.LATRINE, new BlockPos(SiteGeom.fx(p, -1, 1), level, SiteGeom.fz(p, -1, 1)), p.facing(), 0));
                    out.add(new SiteSlot(SiteSlot.Kind.LATRINE, new BlockPos(SiteGeom.fx(p, 1, 1), level, SiteGeom.fz(p, 1, 1)), p.facing(), 0));
                    if (p.a() > 0) out.add(new SiteSlot(SiteSlot.Kind.LATRINE, new BlockPos(SiteGeom.fx(p, 0, 2), level, SiteGeom.fz(p, 0, 2)), p.facing(), 0));
                }
                case KNOLL -> out.add(new SiteSlot(SiteSlot.Kind.LOOKOUT, new BlockPos(p.x() + (p.a() >= 30 ? 1 : 0), level + 2, p.z()), Direction.UP, 0));
                case NURSERY -> out.add(new SiteSlot(SiteSlot.Kind.NURSERY, new BlockPos(p.x(), level, p.z()), Direction.UP, 0));
            }
        }
        slots = List.copyOf(out);
        return slots;
    }

    /** Columns that ordinary shelter hunting must never claim: granary hollows, the plaza and the nursery. {minX,minZ,maxX,maxZ}. */
    public synchronized List<int[]> reserved() {
        if (reserved != null) return reserved;
        var out = new ArrayList<int[]>();
        for (var p : parts) if (p.kind() == Kind.GRANARY || p.kind() == Kind.PLAZA || p.kind() == Kind.NURSERY) out.add(SiteGeom.bounds(SiteGeom.cells(p)));
        reserved = List.copyOf(out);
        return reserved;
    }
}
