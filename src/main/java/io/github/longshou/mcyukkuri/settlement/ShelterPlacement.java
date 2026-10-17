package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.registry.ModBlocks;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** Small shelter footprints never excavate, replace solid blocks or force-load chunks. */
public final class ShelterPlacement {
    public record Plan(BlockPos center,ShelterBlock.Kind kind,Direction facing) {
        public BlockPos entrance(ServerLevel level) {
            if(kind==ShelterBlock.Kind.BURROW){var site=NaturalShelter.survey(level,center,facing);if(site!=null)return site.entrance();}
            return center.relative(facing,kind.radius+1);
        }
        public List<BlockPos> beds(ServerLevel level) {
            if(kind==ShelterBlock.Kind.CARDBOARD)return List.of(center);
            var site=NaturalShelter.survey(level,center,facing);return site==null?List.of():site.beds();
        }
    }
    public record Placed(ShelterBlockEntity shelter,Map<BlockPos,BlockState> before) {
        public void rollback(ServerLevel level) { shelter.rollback();before.forEach((pos,state)->level.setBlock(pos,state,3)); }
    }
    public static List<BlockPos> positions(BlockPos center,ShelterBlock.Kind kind) {
        var result=new ArrayList<BlockPos>();
        for(int y=0;y<kind.height;y++)for(int z=-kind.radius;z<=kind.radius;z++)for(int x=-kind.radius;x<=kind.radius;x++)result.add(center.offset(x,y,z));
        return result;
    }
    public static boolean canPlace(ServerLevel level,Plan plan) {
        // Granary hollows, plazas and nurseries of a planned site are never a home, however well they survey.
        if(plan.kind==ShelterBlock.Kind.BURROW&&SettlementData.get(level).reserved(plan.center))return false;
        if(plan.kind==ShelterBlock.Kind.BURROW&&NaturalShelter.survey(level,plan.center,plan.facing)==null)return false;
        for(var pos:positions(plan.center,plan.kind)) {
            if(!level.hasChunkAt(pos)||!level.getFluidState(pos).isEmpty()||level.getBlockEntity(pos)!=null)return false;
            var state=level.getBlockState(pos);
            if(!state.isAir()&&!state.is(net.minecraft.world.level.block.Blocks.SHORT_GRASS)&&!state.is(net.minecraft.world.level.block.Blocks.FERN)
                    &&!state.is(net.minecraft.world.level.block.Blocks.SNOW))return false;
            if(pos.getY()==plan.center.getY() && (!level.getFluidState(pos.below()).isEmpty()
                    ||!level.getBlockState(pos.below()).isFaceSturdy(level,pos.below(),Direction.UP)))return false;
        }
        var entry=plan.entrance(level);
        if(!level.hasChunkAt(entry)||!level.getFluidState(entry).isEmpty()||!level.getBlockState(entry).getCollisionShape(level,entry).isEmpty()
                ||!level.getBlockState(entry.below()).isFaceSturdy(level,entry.below(),Direction.UP))return false;
        var bounds=new net.minecraft.world.phys.AABB(plan.center).inflate(plan.kind.radius,0,plan.kind.radius).expandTowards(0,plan.kind.height-1,0);
        for(var occupant:level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,bounds,entity->entity.isAlive()&&!entity.isSpectator())) {
            var body=net.minecraft.world.phys.shapes.Shapes.create(occupant.getBoundingBox());
            for(var pos:positions(plan.center,plan.kind)) {
                var offset=pos.subtract(plan.center);
                var shell=ShelterGeometry.shape(plan.kind,plan.facing,ShelterBlock.part(offset.getX(),offset.getY(),offset.getZ())).move(pos.getX(),pos.getY(),pos.getZ());
                if(net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(shell,body,net.minecraft.world.phys.shapes.BooleanOp.AND))return false;
            }
        }
        return true;
    }
    public static Placed place(ServerLevel level,Plan plan) {
        if(!canPlace(level,plan))return null;
        var before=new LinkedHashMap<BlockPos,BlockState>();
        var block=plan.kind==ShelterBlock.Kind.BURROW?ModBlocks.FOREST_BURROW.get():ModBlocks.CARDBOARD_SHELTER.get();
        for(var pos:positions(plan.center,plan.kind))before.put(pos,level.getBlockState(pos));
        for(var pos:before.keySet()) {
            var delta=pos.subtract(plan.center);
            if(!level.setBlock(pos,block.defaultBlockState().setValue(ShelterBlock.PART,ShelterBlock.part(delta.getX(),delta.getY(),delta.getZ()))
                    .setValue(ShelterBlock.FACING,plan.facing),3)) {
                var core=ShelterBlock.shelter(level,plan.center);if(core!=null)core.rollback();
                before.forEach((at,state)->level.setBlock(at,state,3));return null;
            }
        }
        var core=ShelterBlock.shelter(level,plan.center);
        if(core==null){before.forEach((at,state)->level.setBlock(at,state,3));return null;}
        if(plan.kind==ShelterBlock.Kind.BURROW)core.setNaturalSite(NaturalShelter.survey(level,plan.center,plan.facing));
        return new Placed(core,before);
    }
    public static void latrine(ServerLevel level,ShelterBlockEntity home) {
        for(int side:new int[]{2,-2}) {
            var pos=home.entrance().relative(home.facing().getClockWise(),side);
            if(level.hasChunkAt(pos)&&level.getFluidState(pos).isEmpty()&&level.getBlockEntity(pos)==null
                    &&(level.getBlockState(pos).isAir()||level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.SHORT_GRASS))
                    &&level.getBlockState(pos.below()).isFaceSturdy(level,pos.below(),Direction.UP)) {
                level.setBlock(pos,ModBlocks.WILD_LATRINE.get().defaultBlockState(),3);return;
            }
        }
    }
    private ShelterPlacement() {}
}
