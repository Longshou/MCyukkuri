package io.github.longshou.mcyukkuri.settlement.site;

import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.settlement.NaturalShelter;
import io.github.longshou.mcyukkuri.settlement.site.SiteGeom.Part;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Turns a {@link SitePlan} into blocks. Written against {@link LevelAccessor} and a clip box so the same code runs in
 * world generation (one chunk at a time, no reads outside the clip) and from commands (whole footprint, with a
 * before-map for reset). All blocks are computed first and written last, in one pass, with flag 2 (no neighbour
 * cascades). Burrow cores (the shelter blocks) are never placed here: residents claim them.
 */
public final class SiteBuilder {
    /** Natural ground level (first free y) of a column, read before anything is changed. */
    public interface Natural { int at(int x, int z); }
    public record Result(Map<BlockPos, BlockState> before, int blocks) {}

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    public static Result build(LevelAccessor level, SitePlan plan, BoundingBox clip, Natural natural, boolean clearTrees, boolean recordBefore) {
        var job = new Job(level, plan, clip, natural, clearTrees);
        job.run();
        var before = new LinkedHashMap<BlockPos, BlockState>();
        for (var entry : job.blocks.entrySet()) {
            var pos = BlockPos.of(entry.getKey());
            if (recordBefore) {
                var current = level.getBlockState(pos);
                if (current == entry.getValue()) continue;
                before.put(pos, current);
            }
            level.setBlock(pos, entry.getValue(), Block.UPDATE_CLIENTS);
            var fluid = entry.getValue().getFluidState();
            if (!fluid.isEmpty()) level.scheduleTick(pos, fluid.getType(), 0);
        }
        return new Result(before, job.blocks.size());
    }

    /** True when every column of the plan's bounds lies in a loaded chunk. */
    public static boolean loaded(LevelAccessor level, SitePlan plan) {
        for (int x = plan.minX; x <= plan.maxX + 15; x += 16) for (int z = plan.minZ; z <= plan.maxZ + 15; z += 16)
            if (!level.hasChunk(Math.min(x, plan.maxX) >> 4, Math.min(z, plan.maxZ) >> 4)) return false;
        return true;
    }

    /** The reason a freshly built site would not work, or null: survey every dwelling and walk plaza to every door. */
    public static String validate(ServerLevel level, SitePlan plan) {
        for (var slot : plan.slots()) {
            if (!slot.dwelling()) continue;
            var site = NaturalShelter.survey(level, slot.anchor(), slot.facing());
            if (site == null) return slot.kind() + " at " + slot.anchor().toShortString() + " fails the burrow survey";
            if (site.beds().size() < Math.min(slot.capacity(), 9)) return slot.kind() + " at " + slot.anchor().toShortString() + " has only " + site.beds().size() + " beds";
        }
        var start = plan.slots().stream().filter(s -> s.kind() == SiteSlot.Kind.PLAZA).findFirst().orElse(null);
        if (start == null) return "no plaza";
        var seen = new HashSet<BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        queue.add(start.anchor()); seen.add(start.anchor());
        while (!queue.isEmpty() && seen.size() < 8000) {
            var at = queue.poll();
            for (var side : Direction.Plane.HORIZONTAL) for (int dy = 1; dy >= -2; dy--) {
                var next = at.relative(side).above(dy);
                if (Math.abs(next.getX() - plan.ox) > plan.tier.bound || Math.abs(next.getZ() - plan.oz) > plan.tier.bound) continue;
                if (walkable(level, next) && seen.add(next)) { queue.add(next); break; }
            }
        }
        for (var slot : plan.slots()) {
            if (!slot.dwelling() && slot.kind() != SiteSlot.Kind.GRANARY) continue;
            var door = slot.kind() == SiteSlot.Kind.GRANARY ? slot.anchor() : slot.door();
            if (!seen.contains(door)) return slot.kind() + " door " + door.toShortString() + " cannot be reached from the plaza";
        }
        return null;
    }
    private static boolean walkable(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos) || !level.getFluidState(pos).isEmpty()) return false;
        if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() || !level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()) return false;
        var below = level.getBlockState(pos.below());
        return below.is(Blocks.DIRT_PATH) || below.isFaceSturdy(level, pos.below(), Direction.UP);
    }

    // ---- terrain samplers
    /** Ground sampler for loaded worlds (commands, exhibits): skips trees, plants and water. */
    public static final class LevelTerrain implements SitePlanner.Ground, Natural {
        private final LevelAccessor level;
        private final Map<Long, Integer> cache = new HashMap<>();
        public LevelTerrain(LevelAccessor level) { this.level = level; }
        @Override public int at(int x, int z) {
            long key = SiteGeom.key(x, z);
            var known = cache.get(key);
            if (known != null) return known;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), min = level.getMinBuildHeight() + 1;
            var pos = new BlockPos.MutableBlockPos();
            while (y > min) {
                var state = level.getBlockState(pos.set(x, y - 1, z));
                if (state.getFluidState().isEmpty() && !state.isAir() && !state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES)
                        && !state.getCollisionShape(level, pos).isEmpty()) break;
                y--;
            }
            cache.put(key, y);
            return y;
        }
    }

    /** A coarse height grid captured at structure start; deterministic input for every later rebuild. */
    public static final class GridTerrain implements SitePlanner.Ground {
        private final int x0, z0, step, nx, nz;
        private final int[] heights;
        public GridTerrain(int x0, int z0, int step, int nx, int nz, int[] heights) {
            this.x0 = x0; this.z0 = z0; this.step = step; this.nx = nx; this.nz = nz; this.heights = heights;
        }
        @Override public int at(int x, int z) {
            int ix = Math.max(0, Math.min(nx - 1, Math.floorDiv(x - x0 + step / 2, step)));
            int iz = Math.max(0, Math.min(nz - 1, Math.floorDiv(z - z0 + step / 2, step)));
            return heights[iz * nx + ix];
        }
    }

    // ---- the builder proper
    private static final class Job {
        final LevelAccessor level; final SitePlan plan; final BoundingBox clip; final Natural natural; final boolean clearTrees;
        final Map<Long, BlockState> blocks = new LinkedHashMap<>();
        final Map<Long, Integer> heights = new HashMap<>();
        final int G;
        Job(LevelAccessor level, SitePlan plan, BoundingBox clip, Natural natural, boolean clearTrees) {
            this.level = level; this.plan = plan; this.clip = clip; this.natural = natural; this.clearTrees = clearTrees; this.G = plan.level;
        }
        boolean inClip(int x, int z) { return x >= clip.minX() && x <= clip.maxX() && z >= clip.minZ() && z <= clip.maxZ(); }
        void set(int x, int y, int z, BlockState state) {
            if (inClip(x, z) && y >= clip.minY() && y <= clip.maxY()) blocks.put(BlockPos.asLong(x, y, z), state);
        }
        BlockState get(int x, int y, int z) {
            var known = blocks.get(BlockPos.asLong(x, y, z));
            if (known != null) return known;
            return inClip(x, z) && y >= level.getMinBuildHeight() && y < level.getMaxBuildHeight() ? level.getBlockState(new BlockPos(x, y, z)) : AIR;
        }
        int nat(int x, int z) { return heights.computeIfAbsent(SiteGeom.key(x, z), key -> natural.at(x, z)); }
        double hash(int x, int z, int salt) { return SiteRng.hash(plan.seed, x, z, salt); }

        /** Where things stand: the site level on levelled columns, an eased height on the skirt, else the natural ground. */
        int levelAt(int x, int z) {
            long key = SiteGeom.key(x, z);
            if (plan.flat.contains(key)) return G;
            var ring = plan.skirt().get(key);
            if (ring != null) return Math.max(G - ring, Math.min(G + ring, nat(x, z)));
            return nat(x, z);
        }

        void run() {
            for (long key : plan.flat) {
                int x = SiteGeom.keyX(key), z = SiteGeom.keyZ(key);
                if (inClip(x, z)) level(x, z, G, Blocks.GRASS_BLOCK.defaultBlockState());
            }
            for (var entry : plan.skirt().entrySet()) {
                int x = SiteGeom.keyX(entry.getKey()), z = SiteGeom.keyZ(entry.getKey());
                if (!inClip(x, z)) continue;
                int n = nat(x, z), t = Math.max(G - entry.getValue(), Math.min(G + entry.getValue(), n));
                if (t != n) level(x, z, t, hash(x, z, 3) < .18 ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState());
            }
            for (var part : plan.parts) {
                switch (part.kind()) {
                    case HOME, HALL, GRANARY -> mound(part);
                    case PLAZA -> plaza(part);
                    case POOL -> pool(part);
                    case LATRINE -> latrine(part);
                    case NURSERY -> nursery(part);
                    case KNOLL -> knoll(part);
                }
            }
            paths();
            hedge();
            meadow();
        }

        /** Cut or fill one column so its top block (at target - 1) is {@code top}; everything above is cleared of growth. */
        void level(int x, int z, int target, BlockState top) {
            int h = nat(x, z);
            if (h > target) for (int y = target; y < h; y++) set(x, y, z, AIR);
            else for (int y = h; y < target - 1; y++) set(x, y, z, Blocks.DIRT.defaultBlockState());
            set(x, target - 1, z, top);
            if (clearTrees) {
                for (int y = Math.max(h, target); y <= target + 14 && y < level.getMaxBuildHeight(); y++) {
                    var state = get(x, y, z);
                    if (!state.isAir() && (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.canBeReplaced() || state.is(BlockTags.FLOWERS)
                            || state.is(Blocks.VINE) || state.is(BlockTags.REPLACEABLE_BY_TREES))) set(x, y, z, AIR);
                }
            }
        }

        BlockState leaves() {
            var block = plan.palette == 1 ? Blocks.BIRCH_LEAVES : Blocks.OAK_LEAVES;
            return block.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        }
        BlockState strippedLog(Direction.Axis axis) {
            var block = plan.palette == 1 ? Blocks.STRIPPED_BIRCH_LOG : Blocks.STRIPPED_OAK_LOG;
            return block.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis);
        }
        BlockState wallBlock(double h) {
            return (h < .34 ? Blocks.DIRT : h < .6 ? Blocks.COARSE_DIRT : h < .82 ? Blocks.ROOTED_DIRT : Blocks.PODZOL).defaultBlockState();
        }
        BlockState flower(double h) {
            Block[] mixed = {Blocks.DANDELION, Blocks.POPPY, Blocks.AZURE_BLUET, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER, Blocks.LILY_OF_THE_VALLEY, Blocks.RED_TULIP, Blocks.PINK_TULIP};
            Block[] quiet = {Blocks.DANDELION, Blocks.AZURE_BLUET, Blocks.LILY_OF_THE_VALLEY};
            var list = plan.palette == 3 || plan.palette == 2 ? mixed : quiet;
            return list[Math.min(list.length - 1, (int)(h * list.length))].defaultBlockState();
        }

        // ---- burrow mounds: longhouse, family burrows and granary hollows (geometry keeps NaturalShelter.survey true)
        void mound(Part p) {
            int[] d = SiteGeom.dims(p.kind());
            int halfW = d[0], depth = d[1], cavHalf = d[2], cavDepth = d[3];
            boolean granary = p.kind() == SiteGeom.Kind.GRANARY, hall = p.kind() == SiteGeom.Kind.HALL;
            for (int u = -halfW; u <= halfW; u++) for (int w = 0; w <= depth; w++) {
                int x = SiteGeom.fx(p, u, w), z = SiteGeom.fz(p, u, w);
                if (!inClip(x, z)) continue;
                double h = hash(x, z, 11);
                boolean door = w == 0 && u == 0, cavity = Math.abs(u) <= cavHalf && w >= 1 && w <= cavDepth;
                boolean corner = Math.abs(u) == halfW && (w == 0 || w == depth);
                if (door) {
                    set(x, G - 1, z, Blocks.GRAVEL.defaultBlockState());
                    for (int y = 0; y < 3; y++) set(x, G + y, z, AIR);
                    continue;
                }
                if (cavity) {
                    set(x, G - 1, z, Blocks.MOSS_BLOCK.defaultBlockState());
                    set(x, G, z, AIR); set(x, G + 1, z, AIR);
                    set(x, G + 2, z, Blocks.GRASS_BLOCK.defaultBlockState());
                } else {
                    set(x, G - 1, z, wallBlock(h));
                    set(x, G, z, wallBlock(hash(x, z, 12))); set(x, G + 1, z, wallBlock(hash(x, z, 13)));
                    if (!corner) set(x, G + 2, z, Math.abs(u) == halfW && hash(x, z, 14) < .3 ? leaves() : Blocks.GRASS_BLOCK.defaultBlockState());
                    else set(x, G + 2, z, AIR);
                }
                // the rounded back of the mound
                boolean crown = Math.abs(u) <= cavHalf && w >= 1 && w <= depth - 1;
                if (crown) {
                    set(x, G + 3, z, granary && hash(x, z, 15) < .6 ? leaves() : (hash(x, z, 16) < .5 ? Blocks.ROOTED_DIRT : Blocks.MOSS_BLOCK).defaultBlockState());
                    boolean top = !granary && (hall ? Math.abs(u) <= 1 && w == 2 : u == 0 && w == 2);
                    if (top) set(x, G + 4, z, Blocks.MOSS_BLOCK.defaultBlockState());
                }
            }
            // door posts and a root-log in the side wall; both are logs, which still count as earth
            if ((p.a() & 1) != 0) for (int side = -1; side <= 1; side += 2) {
                int x = SiteGeom.fx(p, side, 0), z = SiteGeom.fz(p, side, 0);
                set(x, G, z, strippedLog(Direction.Axis.Y)); set(x, G + 1, z, strippedLog(Direction.Axis.Y));
            }
            if ((p.a() & 2) != 0 && !granary) {
                int side = (p.a() & 4) != 0 ? 1 : -1;
                set(SiteGeom.fx(p, side * halfW, 2), G, SiteGeom.fz(p, side * halfW, 2), strippedLog(p.facing().getAxis()));
            }
            for (int k = 1; k <= 3; k++) for (int u = -1; u <= 1; u++) {
                int x = SiteGeom.fx(p, u, -k), z = SiteGeom.fz(p, u, -k);
                if (!inClip(x, z)) continue;
                double h = hash(x, z, 17);
                BlockState lane = h < .45 ? Blocks.COARSE_DIRT.defaultBlockState() : h < .8 || u == 0 ? Blocks.GRAVEL.defaultBlockState() : Blocks.DIRT_PATH.defaultBlockState();
                set(x, G - 1, z, lane);
                for (int y = 0; y < 3; y++) set(x, G + y, z, AIR);
            }
            if (granary) {
                var pile = ModBlocks.FORAGE_PILE.get().defaultBlockState();
                for (int side = -1; side <= 1; side += 2) set(SiteGeom.fx(p, side, 1), G, SiteGeom.fz(p, side, 1), pile);
            }
        }

        // ---- shared ground
        void plaza(Part p) {
            double rp = p.a() / 10.0;
            for (long key : SiteGeom.cells(p)) {
                int x = SiteGeom.keyX(key), z = SiteGeom.keyZ(key);
                if (!inClip(x, z)) continue;
                int dx = x - p.x(), dz = z - p.z();
                double h = hash(x, z, 21);
                BlockState top;
                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) top = (h < .55 ? Blocks.GRAVEL : Blocks.COARSE_DIRT).defaultBlockState();
                else top = (h < .45 ? Blocks.COARSE_DIRT : h < .62 ? Blocks.PODZOL : h < .88 ? Blocks.DIRT_PATH : Blocks.GRAVEL).defaultBlockState();
                set(x, G - 1, z, top);
                set(x, G, z, AIR);
            }
            // the speaker's stump and a loose ring of mossy seat stones, never next to the stump itself
            set(p.x(), G, p.z(), Blocks.OAK_SLAB.defaultBlockState());
            double seatR = Math.max(1.6, rp - .9), phase = p.c() / 100.0;
            for (int i = 0; i < 8; i++) {
                int x = p.x() + (int)Math.floor(seatR * Math.cos(phase + i * Math.PI / 4) + .5), z = p.z() + (int)Math.floor(seatR * Math.sin(phase + i * Math.PI / 4) + .5);
                if (Math.abs(x - p.x()) + Math.abs(z - p.z()) <= 1 || !SiteGeom.inPlaza(p, x - p.x(), z - p.z()) || hash(x, z, 22) < .22) continue;
                set(x, G, z, Blocks.MOSSY_COBBLESTONE_SLAB.defaultBlockState());
            }
        }

        void pool(Part p) {
            var water = SiteGeom.poolWater(p);
            for (var cell : water) {
                int x = (int)cell[0], z = (int)cell[1];
                if (!inClip(x, z)) continue;
                set(x, G - 3, z, Blocks.MUD.defaultBlockState());
                set(x, G - 2, z, Blocks.WATER.defaultBlockState());
                set(x, G - 1, z, hash(x, z, 31) < .22 ? Blocks.LILY_PAD.defaultBlockState() : AIR);
                set(x, G, z, AIR);
            }
            for (long key : SiteGeom.poolShelf(p)) {
                int x = SiteGeom.keyX(key), z = SiteGeom.keyZ(key);
                if (!inClip(x, z)) continue;
                set(x, G - 2, z, (hash(x, z, 32) < .25 ? Blocks.CLAY : Blocks.MUD).defaultBlockState());
                set(x, G - 1, z, AIR);
                set(x, G, z, AIR);
            }
        }

        void latrine(Part p) {
            int b = p.b(), depth = SiteGeom.latrineDepth(p);
            for (int u = -b; u <= b; u++) for (int w = 0; w <= depth; w++) {
                int x = SiteGeom.fx(p, u, w), z = SiteGeom.fz(p, u, w);
                if (!inClip(x, z)) continue;
                double h = hash(x, z, 41);
                boolean hedge = w == depth || Math.abs(u) == b;
                set(x, G - 1, z, (h < .45 ? Blocks.PODZOL : h < .75 ? Blocks.COARSE_DIRT : Blocks.GRAVEL).defaultBlockState());
                set(x, G, z, hedge ? (hash(x, z, 42) < .3 ? Blocks.AZALEA_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true) : leaves()) : AIR);
                if (hedge && hash(x, z, 43) < .3) set(x, G + 1, z, leaves());
                else set(x, G + 1, z, AIR);
            }
            var toilet = ModBlocks.WILD_LATRINE.get().defaultBlockState();
            set(SiteGeom.fx(p, -1, 1), G, SiteGeom.fz(p, -1, 1), toilet);
            set(SiteGeom.fx(p, 1, 1), G, SiteGeom.fz(p, 1, 1), toilet);
            if (p.a() > 0 && depth == 3) set(SiteGeom.fx(p, 0, 2), G, SiteGeom.fz(p, 0, 2), toilet);
        }

        void nursery(Part p) {
            for (int dx = -p.a(); dx <= p.a(); dx++) for (int dz = -p.b(); dz <= p.b(); dz++) {
                int x = p.x() + dx, z = p.z() + dz;
                if (!inClip(x, z)) continue;
                double h = hash(x, z, 51), g = hash(x, z, 52);
                set(x, G - 1, z, (h < .55 ? Blocks.MOSS_BLOCK : Blocks.GRASS_BLOCK).defaultBlockState());
                set(x, G, z, g < .38 ? Blocks.SHORT_GRASS.defaultBlockState() : g < .62 ? flower(hash(x, z, 53)) : g < .74 ? Blocks.FERN.defaultBlockState() : AIR);
            }
            // a fallen log to climb along, and a mossy stone
            set(p.x() - 1, G, p.z() + p.b(), Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
            set(p.x(), G, p.z() + p.b(), Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
            set(p.x() + p.a(), G, p.z() - p.b(), Blocks.MOSSY_COBBLESTONE.defaultBlockState());
        }

        void knoll(Part p) {
            double r = p.a() / 10.0, r2 = r * .5 + .2;
            for (long key : SiteGeom.cells(p)) {
                int x = SiteGeom.keyX(key), z = SiteGeom.keyZ(key);
                if (!inClip(x, z)) continue;
                int dx = x - p.x(), dz = z - p.z();
                boolean upper = dx * dx + dz * dz <= r2 * r2;
                var dirt = (hash(x, z, 61) < .3 ? Blocks.COARSE_DIRT : Blocks.DIRT).defaultBlockState();
                set(x, G - 1, z, dirt);
                if (upper) {
                    set(x, G, z, dirt); set(x, G + 1, z, Blocks.GRASS_BLOCK.defaultBlockState()); set(x, G + 2, z, AIR);
                    if (p.a() >= 30 && dx == 0 && dz == 0) set(x, G + 2, z, (plan.palette == 1 ? Blocks.BIRCH_LOG : Blocks.OAK_LOG).defaultBlockState());
                } else {
                    set(x, G, z, Blocks.GRASS_BLOCK.defaultBlockState()); set(x, G + 1, z, AIR);
                }
            }
        }

        // ---- loose surface work, always on whatever the ground is after levelling
        void paths() {
            for (var entry : plan.paths.entrySet()) {
                int x = SiteGeom.keyX(entry.getKey()), z = SiteGeom.keyZ(entry.getKey());
                if (!inClip(x, z) || plan.flat.contains(entry.getKey())) continue;
                int y = levelAt(x, z);
                var ground = get(x, y - 1, z);
                if (!ground.is(BlockTags.DIRT)) continue;
                byte code = entry.getValue();
                set(x, y - 1, z, (code == 1 ? Blocks.DIRT_PATH : code == 2 ? Blocks.PODZOL : Blocks.COARSE_DIRT).defaultBlockState());
                var above = get(x, y, z);
                if (!above.isAir() && above.canBeReplaced()) set(x, y, z, AIR);
            }
        }

        void hedge() {
            for (var entry : plan.hedge.entrySet()) {
                int x = SiteGeom.keyX(entry.getKey()), z = SiteGeom.keyZ(entry.getKey());
                if (!inClip(x, z) || plan.flat.contains(entry.getKey())) continue;
                int y = levelAt(x, z);
                var here = get(x, y, z);
                if (!here.isAir() && !here.canBeReplaced()) continue;
                double h = hash(x, z, 71);
                boolean bush = h < .55;
                BlockState state = bush ? leaves() : h < .72 ? Blocks.AZALEA_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true)
                        : h < .82 ? Blocks.FLOWERING_AZALEA_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true)
                        : h < .93 ? Blocks.FERN.defaultBlockState() : Blocks.DEAD_BUSH.defaultBlockState();
                set(x, y, z, state);
                if ((bush || h < .82) && (entry.getValue() == 1 || hash(x, z, 72) < .35)) set(x, y + 1, z, leaves());
            }
        }

        void meadow() {
            double inner = plan.tier.hedge;
            int lo = (int)Math.max(clip.minX(), plan.minX), hi = (int)Math.min(clip.maxX(), plan.maxX);
            for (int x = lo; x <= hi; x++) for (int z = Math.max(clip.minZ(), plan.minZ); z <= Math.min(clip.maxZ(), plan.maxZ); z++) {
                double dist = Math.hypot(x - plan.ox, z - plan.oz);
                long key = SiteGeom.key(x, z);
                if (dist > plan.meadowOuter || plan.flat.contains(key) || plan.paths.containsKey(key) || plan.hedge.containsKey(key)) continue;
                double density = inner == 0 ? .26 : dist <= inner ? .14 : .36;
                if (hash(x, z, 81) >= density) continue;
                int y = levelAt(x, z);
                if (!get(x, y - 1, z).is(Blocks.GRASS_BLOCK) || !get(x, y, z).isAir()) continue;
                double pick = hash(x, z, 82);
                double grass = plan.palette == 3 ? .45 : plan.palette == 2 ? .7 : .65, fern = plan.palette == 3 ? .55 : plan.palette == 2 ? .78 : .9;
                set(x, y, z, pick < grass ? Blocks.SHORT_GRASS.defaultBlockState() : pick < fern ? Blocks.FERN.defaultBlockState() : flower(hash(x, z, 83)));
            }
        }
    }
    private SiteBuilder() {}
}
