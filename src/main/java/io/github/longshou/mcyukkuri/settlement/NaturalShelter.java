package io.github.longshou.mcyukkuri.settlement;

import static io.github.longshou.mcyukkuri.settlement.EnclosureCache.CHECK_SHIFT;
import static io.github.longshou.mcyukkuri.settlement.EnclosureCache.LIVE_SHAPE;
import static io.github.longshou.mcyukkuri.settlement.EnclosureCache.PASS;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/** Surveys existing cavities. This class never places or excavates terrain. */
public final class NaturalShelter {
    /** Survey bound around the doorway cell, and the most cells one home can own. */
    public static final int RADIUS=5, MAX_CELLS=20, MIN_CELLS_FOR_STORE=5;
    private static final int BANK_REACH = 3;
    private static volatile long tagsVersion;
    public record Site(List<BlockPos> beds, BlockPos entrance, List<BlockPos> sleeping, BlockPos pile, BlockPos pileStand) {}
    public static boolean earth(Level level, BlockPos pos) {
        return passed(earthCheck(level, pos));
    }
    private static int earthCheck(Level level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return 0;
        var blockEntity = level.getBlockEntity(pos);
        var state=level.getBlockState(pos);
        if (!(state.is(BlockTags.DIRT)||state.is(BlockTags.BASE_STONE_OVERWORLD)||state.is(BlockTags.LOGS)
                ||state.is(Blocks.MOSS_BLOCK))) return 0;
        // A data pack can tag a block whose entity or shape changes without a block-state change.
        int live = state.hasBlockEntity() || state.getBlock().hasDynamicShape() ? LIVE_SHAPE : 0;
        return live | (blockEntity == null && state.isFaceSturdy(level,pos,Direction.UP) ? PASS : 0);
    }
    public static boolean stand(Level level, BlockPos pos) {
        SearchBudget.probe(level);
        return passed(standCheck(level, pos));
    }
    private static int standCheck(Level level, BlockPos pos) {
        if (!level.hasChunkAt(pos) || !level.getFluidState(pos).isEmpty()) return 0;
        var floorPos = pos.below();
        if (!level.getFluidState(floorPos).isEmpty()) return 0;
        var state = level.getBlockState(pos);
        int live = state.getBlock().hasDynamicShape() ? LIVE_SHAPE : 0;
        if (!state.getCollisionShape(level,pos).isEmpty()) return live;
        var floor = level.getBlockState(floorPos);
        if (floor.getBlock().hasDynamicShape()) live = LIVE_SHAPE;
        return live | (floor.isFaceSturdy(level,floorPos,Direction.UP) ? PASS : 0);
    }
    public static boolean roof(Level level, BlockPos pos) {
        return passed(roofCheck(level, pos));
    }
    private static int roofCheck(Level level, BlockPos pos) {
        int live = 0;
        for (int y = 1; y <= 3; y++) {
            int check = earthCheck(level, pos.above(y));
            live |= check & LIVE_SHAPE;
            if (passed(check)) return live | PASS;
        }
        return live;
    }
    public static boolean enclosed(Level level, BlockPos pos) { return passed(enclosureCheck(level, pos)); }

    private static boolean passed(int check) { return (check & PASS) != 0; }
    private static int enclosureCheck(Level level, BlockPos pos) {
        SearchBudget.probes(level, 2);
        int stand = standCheck(level,pos);
        if (!passed(stand)) return stand;
        int live = stand & LIVE_SHAPE;
        int banks=0;
        for(var facing:Direction.Plane.HORIZONTAL) {
            for (int distance = 1; distance <= BANK_REACH; distance++) {
                int check = earthCheck(level, pos.relative(facing,distance));
                live |= check & LIVE_SHAPE;
                if (passed(check)) { banks++; break; }
            }
        }
        int roof = roofCheck(level, pos);
        return live | (roof & LIVE_SHAPE) | (banks >= (passed(roof) ? 2 : 3) ? PASS : 0);
    }

    static boolean burrowEnclosed(Level level, BlockPos core, List<BlockPos> beds, EnclosureCache cache) {
        if (!(level instanceof ServerLevel server)) return passed(burrowCheck(level, core, beds));
        // Surveyed beds are within five blocks of the core, and banks can extend three beyond that.
        // Each axis spans sixteen blocks from its first to last position, touching two chunks.
        int x = (core.getX() - RADIUS - BANK_REACH) >> 4, z = (core.getZ() - RADIUS - BANK_REACH) >> 4;
        long tags = tagsVersion;
        long nw = chunkVersion(server, x, z), ne = chunkVersion(server, x + 1, z);
        long sw = chunkVersion(server, x, z + 1), se = chunkVersion(server, x + 1, z + 1);
        int checks = cache.lookup(beds, tags, nw, ne, sw, se);
        if (checks != 0) {
            SearchBudget.probes(level, 2 * Math.abs(checks));
            return checks > 0;
        }
        int result = burrowCheck(level, core, beds);
        cache.remember(beds, tags, nw, ne, sw, se, result);
        return passed(result);
    }

    private static int burrowCheck(Level level, BlockPos core, List<BlockPos> beds) {
        int live = 0;
        for (int i = 0; i < beds.size(); i++) {
            int check = enclosureCheck(level, beds.get(i));
            live |= check & LIVE_SHAPE;
            if (!passed(check)) return ((i + 1) << CHECK_SHIFT) | live;
        }
        return ((beds.size() + 1) << CHECK_SHIFT) | live | enclosureCheck(level, core);
    }

    private static long chunkVersion(ServerLevel level, int x, int z) {
        var source = level.getChunkSource();
        if (!source.hasChunk(x, z)) return 0;
        var chunk = source.getChunkNow(x, z);
        return chunk == null ? 0 : ((EnclosureTerrain) chunk).mcyukkuri$enclosureVersion();
    }

    static void tagsReloaded() { tagsVersion++; }
    /** A surveyed cavity is a home candidate. Cells are every enclosed standing cell reachable from the doorway cell {@code center};
     *  {@code sleeping} is the residential part (deepest first; the doorway cell and the store cell are kept clear), {@code pile}
     *  the cell for the forage pile (inside, off the walking line) and {@code pileStand} the cell the mouth touches it from. */
    public static Site survey(Level level, BlockPos center, Direction facing) { return survey(level, center, facing, null); }
    /** {@code knownPile} is an already placed forage pile of this home: its cell still counts as part of the cavity. */
    public static Site survey(Level level, BlockPos center, Direction facing, BlockPos knownPile) {
        SearchBudget.survey(level);
        if(!enclosed(level,center))return null;
        var entrance=center.relative(facing);
        // Caves have a level mouth; a shallow pit may instead be reached over a one-block rim.
        if(!stand(level,entrance)||enclosed(level,entrance)) {
            entrance=entrance.above();
            if(roof(level,center)||!stand(level,entrance)||enclosed(level,entrance))return null;
        }
        var queue=new ArrayDeque<BlockPos>();var seen=new HashSet<BlockPos>();var cells=new ArrayList<BlockPos>();var depth=new HashMap<BlockPos,Integer>();
        queue.add(center);seen.add(center);depth.put(center,0);
        while(!queue.isEmpty()&&cells.size()<MAX_CELLS) {
            var pos=queue.removeFirst();
            boolean known=pos.equals(knownPile);
            if(!known&&!enclosed(level,pos))continue;
            var owner=ShelterBlock.shelter(level,pos);
            if(owner!=null&&!owner.getBlockPos().equals(center))continue;
            if(!known&&level.getBlockEntity(pos)!=null&&!pos.equals(center))continue;
            cells.add(pos);
            for(var dir:Direction.Plane.HORIZONTAL) {
                var next=pos.relative(dir);
                if(Math.abs(next.getX()-center.getX())<=RADIUS&&Math.abs(next.getZ()-center.getZ())<=RADIUS&&seen.add(next)){queue.add(next);depth.put(next,depth.get(pos)+1);}
            }
        }
        if(cells.isEmpty())return null;
        return layout(center,entrance,cells,depth,knownPile);
    }
    /** Decide where things go in a cavity: sleeping deep, the store in a side cell that does not cut the way in. */
    static Site layout(BlockPos center,BlockPos entrance,List<BlockPos> cells,Map<BlockPos,Integer> depth,BlockPos knownPile) {
        BlockPos pile=null,pileStand=null;
        if(cells.size()>=MIN_CELLS_FOR_STORE) {
            if(knownPile!=null&&cells.contains(knownPile)&&!knownPile.equals(center))pile=knownPile;
            else {
                int bestNeighbours=99,bestDepth=-1;
                for(var cell:cells) {
                    if(cell.equals(center)||!connectedWithout(cells,center,cell))continue;
                    int neighbours=0;for(var dir:Direction.Plane.HORIZONTAL)if(cells.contains(cell.relative(dir)))neighbours++;
                    int d=depth.getOrDefault(cell,0);
                    if(neighbours<bestNeighbours||neighbours==bestNeighbours&&d>bestDepth){pile=cell;bestNeighbours=neighbours;bestDepth=d;}
                }
            }
            if(pile!=null) {
                int shallow=99;
                for(var dir:Direction.Plane.HORIZONTAL) {
                    var next=pile.relative(dir);
                    if(cells.contains(next)&&depth.getOrDefault(next,0)<shallow){pileStand=next;shallow=depth.getOrDefault(next,0);}
                }
                if(pileStand==null)pile=null;
            }
        }
        var sleeping=new ArrayList<BlockPos>();
        for(var cell:cells)if(!cell.equals(pile)&&(cells.size()<MIN_CELLS_FOR_STORE||!cell.equals(center)))sleeping.add(cell);
        // Deepest first; the cell the store is reached from is the walkway to it, so it is the last one anybody sleeps in.
        final BlockPos walkway=pileStand;
        sleeping.sort(Comparator.<BlockPos>comparingInt(cell->cell.equals(walkway)?1:0).thenComparingInt(cell->-depth.getOrDefault(cell,0)));
        return new Site(List.copyOf(cells),entrance,List.copyOf(sleeping),pile,pileStand);
    }
    /** True when all cells except {@code removed} stay connected to {@code start}. */
    private static boolean connectedWithout(List<BlockPos> cells,BlockPos start,BlockPos removed) {
        var seen=new HashSet<BlockPos>();var queue=new ArrayDeque<BlockPos>();queue.add(start);seen.add(start);
        while(!queue.isEmpty()) {
            var pos=queue.removeFirst();
            for(var dir:Direction.Plane.HORIZONTAL){var next=pos.relative(dir);if(!next.equals(removed)&&cells.contains(next)&&seen.add(next))queue.add(next);}
        }
        return seen.size()==cells.size()-1;
    }
    private NaturalShelter() {}
}
