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
import io.github.longshou.mcyukkuri.subspecies.boat.ShoreBerth;
import java.util.ArrayList;
import java.util.Comparator;

/**
 * Where each rider steps off a hull lying along a bank. The berth itself (which bank, where the hull lies) is
 * {@link ShoreBerth}'s single answer; this class only finds free standing places beyond its wall and measures the rim gap.
 *
 * <p>The hull lies <b>parallel</b> to the bank with its near brim touching the wall. The bank cell in front of the water cell
 * is the captain's own standing place. Every other rider steps over the near brim onto the first dry ground beyond the wall,
 * at any free place along it. Nothing here depends on a rider stopping at a precise point.
 */
public final class HatDock {
    /** A free standing place for this body just beyond the wall, the {@code skip}-th best by distance from the rider; null when none is free. */
    public static Vec3 spotFor(ShoreBerth.Berth plan,YukkuriEntity actor,Vec3 deckAt,List<AABB> used,int skip){
        double width=Math.max(actor.getBbWidth(),.2);
        var found=new ArrayList<Object[]>();
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
        found.sort(Comparator.comparingDouble(row->(Double)row[0]));
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

    /** The nearest the near rim of the hull in this pose comes to the wall plane, in blocks (negative: slipped under the bank). */
    public static double rimGap(HatBoatEntity boat,ShoreBerth.Berth plan){
        var toWall=plan.wall().subtract(boat.position());
        double across=toWall.x*plan.inland().x+toWall.z*plan.inland().z; // distance from hull centre to the wall, positive toward the bank
        var dir=plan.inland();
        return across-boat.brimReachToward(dir);
    }
    private HatDock(){}
}
