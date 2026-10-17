package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Where a hull lies along a bank, and where each rider steps off it. This is the one shore hand-off geometry used by
 * landing, by the captain putting her hat back on and by the scenarios, instead of exact per-rider stopping points.
 *
 * <p>The hull lies <b>parallel</b> to the bank with its near brim touching the wall. The bank cell in front of the water cell
 * is the captain's own standing place: the hat then lies exactly where the authored hat clips (hat_on_self_aq) put it,
 * a quarter turn from her facing and one stride before her. Every other rider steps over the near brim onto the first
 * dry ground beyond the wall, at any free place along it. Nothing here depends on a rider stopping at a precise point.
 */
public final class HatDock {
    /** A bank cell, the calm water beside it and the hull pose that matches both. */
    public record Plan(BlockPos bank,BlockPos water,Direction toWater,Vec3 stand,Vec3 center,float yaw,Vec3 inland,Vec3 along,double cost){
        /** The wall plane point in front of the captain's cell (midway between the bank cell and the water cell). */
        public Vec3 wall(){return stand.add(inland.scale(-.5));}
    }

    /** A free standing place for this body just beyond the wall, the {@code skip}-th best by distance from the rider; null when none is free. */
    public static Vec3 spotFor(Plan plan,YukkuriEntity actor,Vec3 deckAt,List<AABB> used,int skip){
        double width=Math.max(actor.getBbWidth(),.2);
        var found=new java.util.ArrayList<Object[]>();
        for(int k=-6;k<=6;k++){
            double t=k*Math.max(.3,width*.9);
            for(double depth:new double[]{.12,.22,.34}){
                var at=plan.wall().add(plan.along().scale(t)).add(plan.inland().scale(width*.5+depth));
                at=snapToGround(actor,at);
                if(at==null||!standable(actor,at))continue;
                var box=actor.getBoundingBox().move(at.subtract(actor.position())).inflate(.03);
                boolean clash=false;
                for(var other:used)if(box.intersects(other)){clash=true;break;}
                if(clash)continue;
                double distance=deckAt==null?Math.abs(t):Math.hypot(at.x-deckAt.x,at.z-deckAt.z);
                found.add(new Object[]{distance+depth*.5+Math.abs(t)*.02,at});
            }
        }
        if(found.isEmpty())return null;
        found.sort(java.util.Comparator.comparingDouble(row->(Double)row[0]));
        return (Vec3)found.get(Math.min(Math.max(0,skip),found.size()-1))[1];
    }

    /** The ground top under a point on the bank row, or null when the column is not solid ground. */
    public static Vec3 snapToGround(YukkuriEntity actor,Vec3 at){
        var level=actor.level();
        var hit=level.clip(new ClipContext(at.add(0,.55,0),at.add(0,-.55,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,actor));
        if(hit.getType()!=HitResult.Type.BLOCK||!level.getFluidState(BlockPos.containing(hit.getLocation().x,hit.getLocation().y+.01,hit.getLocation().z)).isEmpty())return null;
        return new Vec3(at.x,hit.getLocation().y,at.z);
    }

    /** Free, dry, supported footing for this whole body at these feet (the vehicle it rides is ignored by the collision query). */
    public static boolean standable(YukkuriEntity actor,Vec3 feet){
        var level=actor.level();var cell=BlockPos.containing(feet.x,feet.y+.01,feet.z);
        if(!level.hasChunkAt(cell)||!level.getFluidState(cell).isEmpty()||!level.getFluidState(cell.below()).isEmpty())return false;
        var box=actor.getBoundingBox().move(feet.subtract(actor.position()));
        if(level.containsAnyLiquid(box)||!level.noCollision(actor,box))return false;
        var hit=level.clip(new ClipContext(feet.add(0,.08,0),feet.add(0,-.14,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,actor));
        return hit.getType()==HitResult.Type.BLOCK&&Math.abs(hit.getLocation().y-feet.y)<.06;
    }

    /** Best bank for this hull: the cheapest reachable pose with room along the wall for the crew; null when no bank qualifies. */
    public static Plan plan(HatBoatEntity boat,YukkuriEntity owner,int crew,BlockPos hint){
        var level=boat.level();
        var placement=HatVessel.placement(true,HatVessel.Role.SELF);
        double forward=-placement.offset().z*owner.growthScale()*owner.getScale()/16;
        var origin=boat.blockPosition();
        Plan best=null;
        for(var cell:BlockPos.betweenClosed(origin.offset(-8,-2,-8),origin.offset(8,2,8))){
            var bank=cell.immutable();
            if(!level.hasChunkAt(bank)||!owner.life().dryStand(bank))continue;
            for(var toWater:Direction.Plane.HORIZONTAL){
                var water=bank.relative(toWater).below();
                if(!level.getFluidState(bank.relative(toWater)).isEmpty()||!HatBoatRoute.calmSurface(level,water))continue;
                var nWater=new Vec3(toWater.getStepX(),0,toWater.getStepZ());
                var stand=Vec3.atBottomCenterOf(bank);
                var surface=HatBoatRoute.surface(level,water);
                var center=new Vec3(stand.x+nWater.x*forward,surface.y,stand.z+nWater.z*forward);
                var under=BlockPos.containing(center.x,center.y-.12,center.z);
                if(!HatBoatRoute.calmSurface(level,under)||!level.getWorldBorder().isWithinBounds(under))continue;
                if(!level.noCollision(boat,boat.getBoundingBox().move(center.subtract(boat.position()))))continue;
                float ownerYaw=(float)Math.toDegrees(Math.atan2(-nWater.x,nWater.z));
                float yaw=Mth.wrapDegrees(ownerYaw+placement.yaw());
                var along=new Vec3(-nWater.z,0,nWater.x);
                // Room for the crew: dry ground cells along the wall at this level.
                int room=0;
                for(int k=-2;k<=2;k++){
                    var side=bank.relative(Direction.getNearest(along.x*k,0,along.z*k));
                    if(k==0||level.hasChunkAt(side)&&owner.life().dryStand(side)||level.hasChunkAt(side.above())&&owner.life().dryStand(side.above()))room++;
                }
                double cost=center.distanceTo(boat.position())+Math.abs(Mth.wrapDegrees(yaw-boat.getYRot()))/90.0
                        +(room<crew+1?100:0)-(hint!=null&&hint.equals(bank)?20:hint!=null&&hint.distSqr(bank)<=4?4:0);
                if(best==null||cost<best.cost())best=new Plan(bank,water,toWater,stand,center,yaw,nWater.scale(-1),along,cost);
            }
        }
        return best;
    }

    /** The nearest the near rim of the hull in this pose comes to the wall plane, in blocks (negative: slipped under the bank). */
    public static double rimGap(HatBoatEntity boat,Plan plan){
        var toWall=plan.wall().subtract(boat.position());
        double across=toWall.x*plan.inland().x+toWall.z*plan.inland().z; // distance from hull centre to the wall, positive toward the bank
        var dir=plan.inland();
        return across-boat.brimReachToward(dir);
    }
    private HatDock(){}
}
