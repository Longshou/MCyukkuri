package io.github.longshou.mcyukkuri.settlement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;

/** Surveys existing cavities. This class never places or excavates terrain. */
public final class NaturalShelter {
    public record Site(List<BlockPos> beds, BlockPos entrance) {}
    public static boolean earth(Level level, BlockPos pos) {
        if(!level.hasChunkAt(pos)||level.getBlockEntity(pos)!=null)return false;
        var state=level.getBlockState(pos);
        return (state.is(BlockTags.DIRT)||state.is(BlockTags.BASE_STONE_OVERWORLD)||state.is(BlockTags.LOGS)
                ||state.is(net.minecraft.world.level.block.Blocks.MOSS_BLOCK))
                &&state.isFaceSturdy(level,pos,Direction.UP);
    }
    public static boolean stand(Level level, BlockPos pos) {
        return level.hasChunkAt(pos)&&level.getFluidState(pos).isEmpty()&&level.getFluidState(pos.below()).isEmpty()
                &&level.getBlockState(pos).getCollisionShape(level,pos).isEmpty()
                &&level.getBlockState(pos.below()).isFaceSturdy(level,pos.below(),Direction.UP);
    }
    public static boolean roof(Level level, BlockPos pos) {
        for(int y=1;y<=3;y++)if(earth(level,pos.above(y)))return true;
        return false;
    }
    public static boolean enclosed(Level level, BlockPos pos) {
        if(!stand(level,pos))return false;
        int banks=0;
        for(var facing:Direction.Plane.HORIZONTAL) {
            for(int distance=1;distance<=3;distance++)if(earth(level,pos.relative(facing,distance))){banks++;break;}
        }
        return roof(level,pos)?banks>=2:banks>=3;
    }
    public static Site survey(Level level, BlockPos center, Direction facing) {
        if(!enclosed(level,center))return null;
        var entrance=center.relative(facing);
        // Caves have a level mouth; a shallow pit may instead be reached over a one-block rim.
        if(!stand(level,entrance)||enclosed(level,entrance)) {
            entrance=entrance.above();
            if(roof(level,center)||!stand(level,entrance)||enclosed(level,entrance))return null;
        }
        var queue=new ArrayDeque<BlockPos>();var seen=new HashSet<BlockPos>();var beds=new ArrayList<BlockPos>();
        queue.add(center);seen.add(center);
        while(!queue.isEmpty()&&beds.size()<16) {
            var pos=queue.removeFirst();
            if(!enclosed(level,pos))continue;
            var owner=ShelterBlock.shelter(level,pos);
            if(owner!=null&&!owner.getBlockPos().equals(center))continue;
            if(level.getBlockEntity(pos)!=null&&!pos.equals(center))continue;
            beds.add(pos);
            for(var dir:Direction.Plane.HORIZONTAL) {
                var next=pos.relative(dir);
                if(Math.abs(next.getX()-center.getX())<=3&&Math.abs(next.getZ()-center.getZ())<=3&&seen.add(next))queue.add(next);
            }
        }
        return beds.isEmpty()?null:new Site(List.copyOf(beds),entrance);
    }
    private NaturalShelter() {}
}
