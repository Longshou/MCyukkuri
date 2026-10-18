package io.github.longshou.mcyukkuri.settlement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;

/** Surveys existing cavities. This class never places or excavates terrain. */
public final class NaturalShelter {
    /** Survey bound around the doorway cell, and the most cells one home can own. */
    public static final int RADIUS=5, MAX_CELLS=20, MIN_CELLS_FOR_STORE=5;
    public record Site(List<BlockPos> beds, BlockPos entrance, List<BlockPos> sleeping, BlockPos pile, BlockPos pileStand) {}
    public static boolean earth(Level level, BlockPos pos) {
        if(!level.hasChunkAt(pos)||level.getBlockEntity(pos)!=null)return false;
        var state=level.getBlockState(pos);
        return (state.is(BlockTags.DIRT)||state.is(BlockTags.BASE_STONE_OVERWORLD)||state.is(BlockTags.LOGS)
                ||state.is(net.minecraft.world.level.block.Blocks.MOSS_BLOCK))
                &&state.isFaceSturdy(level,pos,Direction.UP);
    }
    public static boolean stand(Level level, BlockPos pos) {
        SearchBudget.probe(level);
        return level.hasChunkAt(pos)&&level.getFluidState(pos).isEmpty()&&level.getFluidState(pos.below()).isEmpty()
                &&level.getBlockState(pos).getCollisionShape(level,pos).isEmpty()
                &&level.getBlockState(pos.below()).isFaceSturdy(level,pos.below(),Direction.UP);
    }
    public static boolean roof(Level level, BlockPos pos) {
        for(int y=1;y<=3;y++)if(earth(level,pos.above(y)))return true;
        return false;
    }
    public static boolean enclosed(Level level, BlockPos pos) {
        SearchBudget.probe(level);
        if(!stand(level,pos))return false;
        int banks=0;
        for(var facing:Direction.Plane.HORIZONTAL) {
            for(int distance=1;distance<=3;distance++)if(earth(level,pos.relative(facing,distance))){banks++;break;}
        }
        return roof(level,pos)?banks>=2:banks>=3;
    }
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
