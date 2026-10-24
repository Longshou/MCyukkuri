package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.world.SearchBudget.Purpose;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;

/** A visible, interruptible harvest of one reachable plant block into one carried bedding portion. */
public final class PlantBedding {
    private record Target(BlockPos block, BlockState state, Vec3 stand, Vec3 touch) {}
    private final YukkuriEntity entity;
    private Target target;
    private ForageClaims.Claim claim;
    private int contactTicks, elapsed;
    private int candidates, supportedSites, reachableSites;
    public PlantBedding(YukkuriEntity entity){this.entity=entity;}
    private boolean refused;
    /** The last {@link #find} gave up only because the shared per-tick search budget was spent; asking again later is fine. */
    public boolean refused(){return refused;}
    public boolean active(){return target!=null;}
    /** The plant block currently being approached or harvested, for logs; null without a target. */
    public BlockPos source(){return target==null?null:target.block();}
    public boolean harvesting(){return contactTicks>0;}
    public CompoundTag debug(){
        var tag=new CompoundTag();tag.putInt("Candidates",candidates);tag.putInt("Supported",supportedSites);tag.putInt("Reachable",reachableSites);
        tag.putInt("Contact",contactTicks);tag.putInt("Elapsed",elapsed);
        if(target!=null){
            tag.putString("Source",target.block().toShortString());tag.putString("Stand",target.stand().toString());
            tag.putBoolean("Arrived",SurfaceApproach.arrived(entity,target.stand()));
            tag.putBoolean("ClearLastStep",SurfaceApproach.clearLastStep(entity,target.stand()));
            tag.putString("Wanted",new Vec3(entity.getMoveControl().getWantedX(),entity.getMoveControl().getWantedY(),entity.getMoveControl().getWantedZ()).toString());
            tag.putDouble("SpeedModifier",entity.getMoveControl().getSpeedModifier());
        }return tag;
    }
    public void stop(){
        if(entity.level() instanceof ServerLevel level)SearchBudget.cancel(level,entity,Purpose.BEDDING,0);
        clearTarget();
    }
    private void clearTarget(){
        ForageClaims.release(claim);claim=null;
        target=null;contactTicks=elapsed=0;
    }
    public static ItemStack material(BlockState state){
        if(state.is(BlockTags.LEAVES))return new ItemStack(state.getBlock());
        if(state.is(Blocks.AZALEA)||state.is(Blocks.FLOWERING_AZALEA))return new ItemStack(Items.AZALEA_LEAVES);
        if(Grazing.edible(state))return Grazing.yield(state);
        return ItemStack.EMPTY;
    }
    public boolean find(BlockPos center,int radius,Predicate<BlockPos> allowed){
        clearTarget();candidates=supportedSites=reachableSites=0;refused=false;if(!EventHooks.canEntityGrief(entity.level(),entity))return false;
        // A crowd must not search in one tick, nor all pick the same tuft: shared search budget and soft claims.
        if(!(entity.level() instanceof ServerLevel server))return false;
        if(!SearchBudget.take(server,entity,Purpose.BEDDING)){refused=true;return false;}
        var plants=new ArrayList<BlockPos>();
        for(var pos:BlockPos.betweenClosed(center.offset(-radius,-1,-radius),center.offset(radius,2,radius)))
            if(allowed.test(pos)&&entity.level().hasChunkAt(pos)&&!material(entity.level().getBlockState(pos)).isEmpty())plants.add(pos.immutable());
        plants.sort(Comparator.comparingDouble(pos->entity.distanceToSqr(Vec3.atCenterOf(pos))));
        candidates=plants.size();
        int paths=0;
        for(var plant:plants){
            if(ForageClaims.taken(server,plant,entity.getUUID()))continue;
            if(paths>=8)break;
            var state=entity.level().getBlockState(plant);
            for(var direction:Direction.Plane.HORIZONTAL)for(int down=0;down<=1;down++){
                double reach=.5+entity.getBbWidth()*.5+.045;
                Vec3 stand=Vec3.atBottomCenterOf(plant).add(direction.getStepX()*reach,-down,direction.getStepZ()*reach);
                double mouth=stand.y+entity.getBbHeight()*.45;
                if(mouth<plant.getY()-.12||mouth>plant.getY()+1.15)continue;
                Vec3 touch=new Vec3(plant.getX()+.5+direction.getStepX()*.505,Math.clamp(mouth,plant.getY()+.05,plant.getY()+.9),plant.getZ()+.5+direction.getStepZ()*.505);
                if(!SurfaceApproach.supported(entity,stand))continue;supportedSites++;
                if(++paths>8)break;
                if(!entity.life().reachable(BlockPos.containing(stand),false))continue;reachableSites++;
                if(visible(stand,touch,plant)){
                    claim=ForageClaims.claim(server,plant,entity.getUUID());
                    if(claim!=null){target=new Target(plant,state,stand,touch);return true;}
                }
            }
        }
        return false;
    }
    private boolean visible(Vec3 feet,Vec3 point,BlockPos source){
        var hit=entity.level().clip(new ClipContext(feet.add(0,entity.getBbHeight()*.45,0),point,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,entity));
        return hit.getType()==HitResult.Type.MISS||hit.getBlockPos().equals(source);
    }
    public ItemStack tick(){
        if(target==null)return ItemStack.EMPTY;
        var level=(ServerLevel)entity.level();
        if(++elapsed>300||!ForageClaims.owns(level,target.block(),claim)||!EventHooks.canEntityGrief(level,entity)||!level.hasChunkAt(target.block())
                ||!level.getBlockState(target.block()).equals(target.state())||!SurfaceApproach.supported(entity,target.stand())){stop();return ItemStack.EMPTY;}
        if(!SurfaceApproach.walk(entity,target.stand(),.65)){contactTicks=0;return ItemStack.EMPTY;}
        SurfaceApproach.face(entity,SurfaceApproach.yaw(entity.position(),target.touch()));
        if(!visible(entity.position(),target.touch(),target.block())){stop();return ItemStack.EMPTY;}
        float desired=SurfaceApproach.yaw(entity.position(),target.touch());
        if(Math.abs(Mth.wrapDegrees(desired-entity.getYRot()))>15){contactTicks=0;return ItemStack.EMPTY;}
        if(++contactTicks%12==0)level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK,target.state()),target.touch().x,target.touch().y,target.touch().z,3,.025,.025,.025,.01);
        if(contactTicks<36)return ItemStack.EMPTY;
        ItemStack result=material(target.state());
        // Consume exactly the touched block. No scripted ItemEntity appears between plant and mouth.
        // Plants step back one growth stage and grow again (Grazing); only leaves and azaleas are actually cut away.
        if(!ForageClaims.owns(level,target.block(),claim)||!(Grazing.edible(target.state())?Grazing.graze(level,target.block()):level.setBlock(target.block(),
                Blocks.AIR.defaultBlockState(),3))){stop();return ItemStack.EMPTY;}
        level.levelEvent(2001,target.block(),Block.getId(target.state()));
        stop();return result;
    }
}
