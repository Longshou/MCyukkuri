package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.life.LargeNestBlock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;

/** Small shelter footprints never excavate, replace solid blocks or force-load chunks. */
public final class ShelterPlacement {
    public record Plan(BlockPos center,ShelterBlock.Kind kind,Direction facing) {
        public BlockPos entrance(ServerLevel level) {
            if(kind==ShelterBlock.Kind.BURROW){var site=NaturalShelter.survey(level,center,facing);if(site!=null)return site.entrance();}
            return center.relative(facing,kind.radius+1);
        }
        public List<BlockPos> beds(ServerLevel level) {
            if(kind.found())return List.of(center);
            var site=NaturalShelter.survey(level,center,facing);return site==null?List.of():site.sleeping();
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
        SearchBudget.probe(level);
        // Granary hollows, plazas, nurseries and the waste pit of a planned site are never a home, however well they survey.
        if(plan.kind==ShelterBlock.Kind.BURROW&&SettlementData.get(level).reserved(plan.center))return false;
        // A home is a recognised area: a roofed cavity with a way in and room enough for a nest and a store.
        if(plan.kind==ShelterBlock.Kind.BURROW) {
            var site=NaturalShelter.survey(level,plan.center,plan.facing);
            if(site==null||site.beds().size()<Burrowing.MIN_CELLS)return false;
        }
        for(var pos:positions(plan.center,plan.kind)) {
            if(!level.hasChunkAt(pos)||!level.getFluidState(pos).isEmpty()||level.getBlockEntity(pos)!=null)return false;
            var state=level.getBlockState(pos);
            if(!state.isAir()&&!state.is(Blocks.SHORT_GRASS)&&!state.is(Blocks.FERN)
                    &&!state.is(Blocks.SNOW))return false;
            if(pos.getY()==plan.center.getY() && (!level.getFluidState(pos.below()).isEmpty()
                    ||!level.getBlockState(pos.below()).isFaceSturdy(level,pos.below(),Direction.UP)))return false;
        }
        var entry=plan.entrance(level);
        if(!level.hasChunkAt(entry)||!level.getFluidState(entry).isEmpty()||!level.getBlockState(entry).getCollisionShape(level,entry).isEmpty()
                ||!level.getBlockState(entry.below()).isFaceSturdy(level,entry.below(),Direction.UP))return false;
        var bounds=new AABB(plan.center).inflate(plan.kind.radius,0,plan.kind.radius).expandTowards(0,plan.kind.height-1,0);
        if (plan.kind == ShelterBlock.Kind.CARDBOARD) {
            var side = plan.facing.getClockWise().getNormal();
            double x = Math.abs(side.getX()) * ShelterBlock.CARTON_SIDE_OVERHANG;
            double z = Math.abs(side.getZ()) * ShelterBlock.CARTON_SIDE_OVERHANG;
            var left = x > 0 ? new AABB(bounds.minX-x,bounds.minY,bounds.minZ,bounds.minX,bounds.maxY,bounds.maxZ)
                    : new AABB(bounds.minX,bounds.minY,bounds.minZ-z,bounds.maxX,bounds.maxY,bounds.minZ);
            var right = x > 0 ? new AABB(bounds.maxX,bounds.minY,bounds.minZ,bounds.maxX+x,bounds.maxY,bounds.maxZ)
                    : new AABB(bounds.minX,bounds.minY,bounds.maxZ,bounds.maxX,bounds.maxY,bounds.maxZ+z);
            for (var edge : new AABB[]{left, right}) {
                if (!level.hasChunkAt(BlockPos.containing(edge.getCenter())) || level.getBlockCollisions(null, edge).iterator().hasNext()) return false;
            }
            bounds = bounds.inflate(x, 0, z);
        }
        for(var occupant:level.getEntitiesOfClass(LivingEntity.class,bounds,entity->entity.isAlive()&&!entity.isSpectator())) {
            var body=Shapes.create(occupant.getBoundingBox());
            for(var pos:positions(plan.center,plan.kind)) {
                var offset=pos.subtract(plan.center);
                var shell=ShelterGeometry.shape(plan.kind,plan.facing,ShelterBlock.part(offset.getX(),offset.getY(),offset.getZ())).move(pos.getX(),pos.getY(),pos.getZ());
                if(Shapes.joinIsNotEmpty(shell,body,BooleanOp.AND))return false;
            }
        }
        return true;
    }
    public static Placed place(ServerLevel level,Plan plan) {
        SearchBudget.probe(level);
        if(!canPlace(level,plan))return null;
        var before=new LinkedHashMap<BlockPos,BlockState>();
        var block=switch(plan.kind) {
            case BURROW -> ModBlocks.FOREST_BURROW.get();
            case CARDBOARD -> ModBlocks.CARDBOARD_SHELTER.get();
            case CRATE -> ModBlocks.CRATE_SHELTER.get();
        };
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
        Homes.registerLoaded(level,core);
        return new Placed(core,before);
    }
    /** A dry, level patch with standing room at the low southern entrance. */
    static boolean surfaceFootprint(ServerLevel level, BlockPos origin) {
        for (var cell : BlockPos.betweenClosed(origin, origin.offset(1, 0, 2))) {
            if (!NaturalShelter.stand(level, cell) || !level.getBlockState(cell).isAir() || !level.getBlockState(cell.above()).isAir()
                    || SettlementData.get(level).reserved(cell)) return false;
        }
        return true;
    }

    /** Something solid or leafy overhead of every cell of the 2x2 bed (a canopy, an overhang), as the rest check reads cover. */
    static boolean surfaceCovered(ServerLevel level, BlockPos origin) {
        for (var cell : BlockPos.betweenClosed(origin, origin.offset(1, 0, 1)))
            if (level.getHeight(Heightmap.Types.MOTION_BLOCKING, cell.getX(), cell.getZ()) <= cell.getY()) return false;
        return true;
    }

    static boolean placeSurfaceNest(ServerLevel level, BlockPos origin) {
        if (!surfaceFootprint(level, origin) || !level.getEntitiesOfClass(LivingEntity.class,
                new AABB(origin).expandTowards(1, 0, 1), entity -> entity.isAlive() && !entity.isSpectator()).isEmpty()) return false;
        var state = ModBlocks.LARGE_WILD_NEST.get().defaultBlockState();
        for (int part = 0; part < 4; part++) {
            if (!level.setBlock(origin.offset(part & 1, 0, part >> 1), state.setValue(LargeNestBlock.PART, part),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) {
                removeSurfaceNest(level, origin);
                return false;
            }
        }
        return true;
    }

    static void removeSurfaceNest(ServerLevel level, BlockPos origin) {
        for (var cell : BlockPos.betweenClosed(origin, origin.offset(1, 0, 1)))
            if (level.getBlockState(cell).is(ModBlocks.LARGE_WILD_NEST.get())) level.removeBlock(cell, false);
    }
    private ShelterPlacement() {}
}
