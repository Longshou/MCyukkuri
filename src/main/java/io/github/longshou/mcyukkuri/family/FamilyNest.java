package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.life.NestResting;
import io.github.longshou.mcyukkuri.life.LargeNestBlock;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import java.util.UUID;
import java.util.HashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Physical shared bedding, rather than merely membership of the same cave. */
public final class FamilyNest {
    private FamilyNest(){}
    public static String describe(YukkuriEntity e){
        var c=care(e);var home=e.habitat().shelter();
        return c!=null?c.getBlockPos()+" "+c.occupancy():home!=null?"wild="+home.bed(e.getUUID())+" large="+home.largeBed(e.getUUID()):"none";
    }
    public static CareBlockEntity care(YukkuriEntity e){
        var home=e.life().home();
        return home!=null&&home.dimension().equals(e.level().dimension())&&e.level().hasChunkAt(home.pos())
                &&e.level().getBlockEntity(home.pos()) instanceof CareBlockEntity c&&c.owns(e.getUUID())?c:null;
    }
    public static boolean sameNest(YukkuriEntity a,YukkuriEntity b){
        var first=care(a);if(first!=null)return first==care(b);
        var shelter=a.habitat().shelter();
        return shelter!=null&&shelter==b.habitat().shelter()&&shelter.bed(a.getUUID())!=null&&shelter.bed(a.getUUID()).equals(shelter.bed(b.getUUID()));
    }
    public static boolean atAssignedNest(YukkuriEntity e){
        var c=care(e);var wild=e.habitat().shelter();
        BlockPos pos=c!=null?c.getBlockPos():wild!=null?wild.bed(e.getUUID()):null;
        return pos!=null&&e.distanceToSqr(c!=null?c.centre():Vec3.atBottomCenterOf(pos))<2.25&&!e.isInWaterOrBubble()&&!e.isOnFire();
    }
    public static boolean large(YukkuriEntity e){
        var c=care(e);if(c!=null)return c.largeNest();
        var shelter=e.habitat().shelter();return shelter!=null&&shelter.largeBed(e.getUUID());
    }
    public static Vec3 restPosition(YukkuriEntity e,CareBlockEntity c){
        return SurfaceApproach.restingFloor(e,c.centre().add(c.restOffset(e.getUUID())));
    }
    /** The floor in the middle of this bed (its 2x2 corner or its block centre), even where the body would not fully stand. */
    public static Vec3 bedMiddle(YukkuriEntity e,BlockPos bed){
        var c=care(e);
        var middle=c!=null&&c.getBlockPos().equals(bed)?c.centre():Vec3.atBottomCenterOf(bed);
        return SurfaceApproach.surface(e,middle.add(0,.1,0));
    }
    public static Vec3 restPosition(YukkuriEntity e,BlockPos bed){
        var c=care(e);if(c!=null&&c.getBlockPos().equals(bed))return restPosition(e,c);
        var home=e.habitat().shelter();
        return home!=null&&bed.equals(home.bed(e.getUUID()))?Vec3.atBottomCenterOf(bed).add(home.restOffset(e.getUUID())):Vec3.atBottomCenterOf(bed);
    }
    /** Feet on the level floor of the assigned 2x2 nest, inside its rim (not standing on the rim or its entrance shoulders). */
    public static boolean onLargeFloor(YukkuriEntity e){
        var centre=largeCentre(e);
        if(centre==null)return false;
        double reach=NestResting.LARGE.box()-e.getBbWidth()*.5+NestResting.RIM_PRESS;
        return Math.abs(e.getX()-centre.x)<=reach&&Math.abs(e.getZ()-centre.z)<=reach&&e.getY()<centre.y+FLOOR_TOP;
    }
    /** Above the highest pad or floor of a nest bed (woven pad and hem 1.75-2.0, pet cushion pad 1.75, wild leaf floor 0.5)
     *  and below every rim or bolster top (wild pressed rim 2.6, its entrance shoulders 2.3, woven wall, pet bolster). */
    private static final double FLOOR_TOP=2.1/16;
    private record Bed(Vec3 centre,double contactHeight,CareBlockEntity nest){}
    /** Resolve all occupied nest quarters to their owner, then confirm the actual collision top under the feet. */
    private static java.util.List<Bed> beds(YukkuriEntity e,boolean birthContact){
        var found=new java.util.ArrayList<Bed>();
        var visited=new HashSet<BlockPos>();var box=e.getBoundingBox();
        var impact=birthContact&&e.family().awaitingLanding()&&e.yo>e.getY()+1e-6
                ?box.move(e.xo-e.getX(),0,e.zo-e.getZ()):null;
        if(impact!=null)box=box.minmax(impact);
        // A cushion overhangs its own cell; a 2x2 nest stores its block entity only in the north-west quarter.
        for(var pos:BlockPos.betweenClosed(BlockPos.containing(box.minX-1,e.getY()-.01,box.minZ-1),
                BlockPos.containing(box.maxX+1,e.getY(),box.maxZ+1))){
            if(!e.level().hasChunkAt(pos))continue;
            var state=e.level().getBlockState(pos);
            var origin=state.getBlock() instanceof LargeNestBlock?LargeNestBlock.origin(state,pos):pos.immutable();
            if(!visited.add(origin)||!(e.level().getBlockEntity(origin) instanceof CareBlockEntity c)
                    ||c.kind()!=CareBlock.Kind.NEST||c.getBlockState().is(ModBlocks.PREDATOR_NEST.get()))continue;
            double contact=contactHeight(e,c,e.getBoundingBox());
            // Vanilla resolves the downward move before its horizontal components. A newborn can hit the soft
            // wall and slide partly past its edge in that same tick; the final centre alone is not the impact point.
            if(impact!=null)contact=Math.max(contact,contactHeight(e,c,impact));
            if(Double.isFinite(contact))found.add(new Bed(c.centre(),contact,c));
        }
        return found;
    }
    /** A body centre may be beyond the rim while its real footprint is still resting on its edge. */
    private static double contactHeight(YukkuriEntity e,CareBlockEntity nest,AABB feet){
        var origin=nest.getBlockPos();double highest=Double.NEGATIVE_INFINITY;
        for(int part=0;part<(nest.largeNest()?4:1);part++){
            var at=origin.offset(part&1,0,part>>1);
            if(!e.level().hasChunkAt(at))continue;
            var state=e.level().getBlockState(at);
            if(nest.largeNest()&&(!(state.getBlock() instanceof LargeNestBlock)||!LargeNestBlock.origin(state,at).equals(origin)))continue;
            for(var local:state.getCollisionShape(e.level(),at,CollisionContext.of(e)).toAabbs()){
                var top=local.move(at);
                if(Math.abs(top.maxY-e.getY())<=.002&&top.maxX>feet.minX+1e-6&&top.minX<feet.maxX-1e-6
                        &&top.maxZ>feet.minZ+1e-6&&top.minZ<feet.maxZ-1e-6)highest=Math.max(highest,top.maxY-origin.getY());
            }
        }
        return highest;
    }
    /**
     * A real contact with soft bedding cancels only the birth fall, never water or other hazards: the newborn's feet on the
     * level pad or floor of a nest (a 2x2 nest, a pet cushion nest, or its family's large wild bed), inside the rim. A kit
     * resting on the rim itself is not in the nest (see {@link #rimCentre}).
     */
    public static boolean cushions(YukkuriEntity baby){
        if(!baby.onGround()||baby.isInWaterOrBubble())return false;
        for(var bed:beds(baby,true))if(bed.contactHeight()<FLOOR_TOP)return true;
        // Burrow bedding is drawn on the real floor, without a CareBlock collision shell.
        if(care(baby)!=null)return false;
        var centre=largeCentre(baby);
        double floor=NestResting.LARGE.box();
        return centre!=null&&Math.abs(baby.getX()-centre.x)<floor&&Math.abs(baby.getZ()-centre.z)<floor&&Math.abs(baby.getY()-centre.y)<.4;
    }
    /** The centre of the nest whose soft rim or bolster a landed kit is resting on (above the floor, within the rim), else null. */
    public static Vec3 rimCentre(YukkuriEntity baby){
        if(!baby.onGround()||baby.isInWaterOrBubble())return null;
        for(var bed:beds(baby,true))if(bed.contactHeight()>=FLOOR_TOP)return bed.centre();
        return null;
    }
    /** The pet nest whose soft rim or bolster a landed baby is resting on (see {@link #rimCentre}), else null. */
    public static CareBlockEntity rimNest(YukkuriEntity baby){
        if(!baby.onGround()||baby.isInWaterOrBubble())return null;
        for(var bed:beds(baby,false))if(bed.contactHeight()>=FLOOR_TOP)return bed.nest();
        return null;
    }
    /** The common corner of the real 2x2 nest this actor is assigned to; null without a large nest. */
    public static Vec3 largeCentre(YukkuriEntity e){
        var c=care(e);if(c!=null)return c.largeNest()?c.centre():null;
        var shelter=e.habitat().shelter();
        if(shelter==null||!shelter.largeBed(e.getUUID()))return null;
        var bed=shelter.bed(e.getUUID());
        return bed==null?null:Vec3.atBottomCenterOf(bed);
    }
    public static void tick(YukkuriEntity e){
        var c=care(e);
        if(e.family().brood()!=null&&!large(e)&&e.tickCount%200==0){
            for(var pos:BlockPos.betweenClosed(e.blockPosition().offset(-8,-1,-8),e.blockPosition().offset(8,1,8))){
                if(e.level().hasChunkAt(pos)&&e.level().getBlockEntity(pos) instanceof CareBlockEntity nest&&nest.largeNest()&&nest.canClaim(e)
                        &&e.life().assignHome(GlobalPos.of(e.level().dimension(),pos.immutable()))){c=nest;break;}
            }
        }
        if(e.family().adult()&&e.family().partner()!=null&&((ServerLevel)e.level()).getEntity(e.family().partner()) instanceof YukkuriEntity mate){
            var shared=care(mate);
            if(shared!=null&&shared.largeNest()&&shared!=c&&e.distanceToSqr(mate)<64&&shared.canClaim(e)&&e.life().assignHome(mate.life().home()))c=shared;
        }
        if(c!=null&&!c.claim(e.getUUID())){
            GameplayLog.event(e,"home","nest no longer fits stage/body old={} stage={}",c.getBlockPos(),e.family().stage());
            e.life().releaseHome();c=null;
        }
        var shelter=e.habitat().shelter();
        if(shelter!=null)shelter.enforceBedCapacity(e);
        if(c==null&&e.family().stage()==FamilyLife.Stage.BABY&&!e.family().awaitingLanding()&&!e.isPassenger()){
            for(var id:new UUID[]{e.family().mother(),e.family().father()})if(id!=null&&((ServerLevel)e.level()).getEntity(id) instanceof YukkuriEntity parent){
                var nest=care(parent);if(nest!=null&&nest.largeNest()&&nest.canClaim(e)&&e.distanceToSqr(parent)<64){e.life().assignHome(parent.life().home());break;}
            }
        }
        if(!large(e)||!atAssignedNest(e))return;
        var peers=e.level().getEntitiesOfClass(YukkuriEntity.class,e.getBoundingBox().inflate(3),other->other!=e&&other.isAlive()&&sameNest(e,other)&&atAssignedNest(other));
        if(peers.isEmpty())return;
        boolean babies=e.family().stage()==FamilyLife.Stage.BABY||peers.stream().anyMatch(other->other.family().stage()==FamilyLife.Stage.BABY);
        e.mind().add(babies?-.1f:-.05f,"shared_nest");
        if(e.tickCount%(babies?600:1200)==0)for(var peer:peers){
            YukkuriWorldData.get((ServerLevel)e.level()).relate(e.getUUID(),peer.getUUID(),1);
            GameplayLog.event(e,"family","shared nest closeness peer={} change=1 babies={}",peer.getUUID(),babies);
        }
    }
}
