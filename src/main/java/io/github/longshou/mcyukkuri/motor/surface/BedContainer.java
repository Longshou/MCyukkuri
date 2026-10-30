package io.github.longshou.mcyukkuri.motor.surface;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.life.LargeNestBlock;
import io.github.longshou.mcyukkuri.life.NestResting;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.geom.FloorShape;
import io.github.longshou.mcyukkuri.motor.geom.OccupancyMath;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** A nest bed as a container: the rounded hollow a body may lie in, its floor and the rim a footprint may rest on by accident; a shelter cell without a bed is no container. */
public final class BedContainer implements Container {
    private final Vec3 centre;
    private final FloorShape floor;
    private final double floorY,rimY;

    private BedContainer(Vec3 centre,FloorShape floor,double floorY,double rimY){
        this.centre=centre;this.floor=floor;this.floorY=floorY;this.rimY=rimY;
    }

    /** The nest bed at {@code bed}, or null when that block is not a nest bed. */
    public static BedContainer of(YukkuriEntity e,BlockPos bed){
        if(e==null||bed==null||e.level().isClientSide||!e.level().hasChunkAt(bed))return null;
        var level=e.level();
        var state=level.getBlockState(bed);
        // A 2x2 nest stores its block entity only in the north-west quarter.
        var origin=state.getBlock() instanceof LargeNestBlock?LargeNestBlock.origin(state,bed):bed;
        if(level.getBlockEntity(origin) instanceof CareBlockEntity care&&care.kind()==CareBlock.Kind.NEST){
            var hollow=care.bed();
            var centre=care.centre();
            double base=centre.y,floorY=floorAt(e,centre,base);
            return new BedContainer(centre,lying(hollow),floorY,
                    Math.max(floorY,base+shellTop(level,origin,care.largeNest())));
        }
        var shelter=ShelterBlock.shelter(level,bed);
        if(shelter==null||!shelter.beds().contains(bed))return null;
        // Bedding drawn on a burrow's real floor has no collision rim (NestResting marks it soft): its floor is its rim.
        var hollow=shelter.largeBeds().contains(bed)?NestResting.LARGE_BED:NestResting.LEAF_BED;
        var centre=Vec3.atBottomCenterOf(bed);
        double floorY=floorAt(e,centre,centre.y);
        return new BedContainer(centre,lying(hollow),floorY,floorY);
    }

    /** The outline a body may lie within: the hollow plus the soft wall a rounded body may press into ({@link NestResting#RIM_PRESS}), so every place NestResting assigns is inside. */
    private static FloorShape lying(NestResting.Bed bed){
        double half=Math.min(bed.half()+NestResting.RIM_PRESS,bed.box()+(bed.softBox()?NestResting.RIM_PRESS:0));
        return new FloorShape.RoundedRect(half,Math.min(half,bed.corner()+NestResting.RIM_PRESS));
    }

    /** Whether a body of {@code width} standing on the level floor at plane point (x,z) is fully supported inside the hollow. */
    public boolean standsInside(double x,double z,float width){
        return OccupancyMath.classify(x,z,floorY,Occupancy.foot(width),floor,floorY,rimY,true)==OccupancyMath.Result.INSIDE_SUPPORTED;
    }

    @Override public FloorShape floor(){return floor;}
    @Override public double floorY(){return floorY;}
    @Override public double rimY(){return rimY;}
    @Override public Vec3 toPlane(Vec3 world){return new Vec3(world.x-centre.x,world.y,world.z-centre.z);}

    /** A bed stands on block collision; its level floor carries only a body actually resting on it. */
    @Override public boolean supports(YukkuriEntity body){return body.onGround()&&!body.isInWaterOrBubble();}

    /** The real collision top at the bed's middle; a bed in an unloaded or empty cell falls back to its own block height. */
    private static double floorAt(YukkuriEntity e,Vec3 centre,double base){
        var top=SurfaceApproach.surface(e,centre.add(0,.1,0));
        return top==null?base:top.y;
    }

    /** The highest collision top of the bed's own cells: the rim a footprint may come down on (the pad stays below it). */
    private static double shellTop(Level level,BlockPos origin,boolean large){
        double top=0;
        for(int part=0;part<(large?4:1);part++){
            var at=origin.offset(part&1,0,part>>1);
            if(!level.hasChunkAt(at))continue;
            var shell=level.getBlockState(at).getCollisionShape(level,at,CollisionContext.empty());
            if(!shell.isEmpty())top=Math.max(top,shell.max(Direction.Axis.Y));
        }
        return top;
    }
}
