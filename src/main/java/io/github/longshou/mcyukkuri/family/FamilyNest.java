package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

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
    public static Vec3 restPosition(YukkuriEntity e,BlockPos bed){
        var c=care(e);if(c!=null&&c.getBlockPos().equals(bed))return restPosition(e,c);
        var home=e.habitat().shelter();
        return home!=null&&bed.equals(home.bed(e.getUUID()))?Vec3.atBottomCenterOf(bed).add(home.restOffset(e.getUUID())):Vec3.atBottomCenterOf(bed);
    }
    /** A real contact with soft bedding cancels only the birth fall, never water or other hazards. */
    public static boolean cushions(YukkuriEntity baby){
        if(!baby.onGround()||baby.isInWaterOrBubble())return false;
        for(var pos:BlockPos.betweenClosed(baby.blockPosition().offset(-1,-1,-1),baby.blockPosition().offset(1,0,1)))
            if(baby.level().getBlockEntity(pos) instanceof CareBlockEntity c&&c.largeNest()
                    &&Math.abs(baby.getX()-c.centre().x)<1&&Math.abs(baby.getZ()-c.centre().z)<1
                    &&baby.getY()>=pos.getY()&&baby.getY()<pos.getY()+.3)return true;
        var centre=largeCentre(baby);
        return centre!=null&&Math.abs(baby.getX()-centre.x)<1&&Math.abs(baby.getZ()-centre.z)<1&&Math.abs(baby.getY()-centre.y)<.4;
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
