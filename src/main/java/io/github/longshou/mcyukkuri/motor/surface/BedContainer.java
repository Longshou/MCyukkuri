package io.github.longshou.mcyukkuri.motor.surface;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.life.LargeNestBlock;
import io.github.longshou.mcyukkuri.life.NestResting;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.geom.FloorShape;
import io.github.longshou.mcyukkuri.motor.geom.BedSpace;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import io.github.longshou.mcyukkuri.headwear.HeadTop;
import java.util.ArrayList;
import java.util.List;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;

/** A nest bed as a container: the rounded hollow a body may lie in, its floor and the rim a footprint may rest on by accident; a shelter cell without a bed is no container. */
public final class BedContainer implements Container {
    private final Vec3 centre;
    private final YukkuriEntity body;
    private final CollisionGetter collisions;
    private List<BedSpace.Box> space;
    private double reach, brim, top;
    private boolean yieldingBrim;
    private final FloorShape floor;
    private final double floorY,rimY;

    private BedContainer(YukkuriEntity body,CollisionGetter collisions,Vec3 centre,FloorShape floor,double floorY,double rimY){
        this.collisions=collisions;
        this.body=body;this.centre=centre;this.floor=floor;this.floorY=floorY;this.rimY=rimY;
    }

    /** The nest bed at {@code bed}, or null when that block is not a nest bed. */
    public static BedContainer of(YukkuriEntity e,BlockPos bed){
        if(e==null||bed==null||e.level().isClientSide||!e.level().hasChunkAt(bed))return null;
        var level=e.level();
        var state=level.getBlockState(bed);
        // All four quarters resolve to the transformed core.
        var origin=state.getBlock() instanceof LargeNestBlock?LargeNestBlock.origin(state,bed):bed;
        if(level.getBlockEntity(origin) instanceof CareBlockEntity care&&care.kind()==CareBlock.Kind.NEST){
            var hollow=care.bed();
            var centre=care.centre();
            double base=centre.y,floorY=floorAt(e,centre,base);
            return new BedContainer(e,level,centre,hollow.floor(),floorY,
                    Math.max(floorY,base+shellTop(level,care.northWest(),care.largeNest())));
        }
        var shelter=ShelterBlock.shelter(level,bed);
        if(shelter==null||!shelter.beds().contains(bed))return null;
        // Bedding drawn on a burrow's real floor has no collision rim (NestResting marks it soft): its floor is its rim.
        var hollow=shelter.largeBeds().contains(bed)?NestResting.LARGE:NestResting.LEAF_BED;
        var centre=shelter.restSpot(bed);
        double floorY=shelter.kind()==ShelterBlock.Kind.BURROW?centre.y:floorAt(e,centre,centre.y);
        return new BedContainer(e,level,centre,hollow.floor(),floorY,floorY);
    }

    /** Probe a proposed bed against its installed shell and surroundings before changing any world blocks. */
    public static BedContainer planned(YukkuriEntity body, CollisionGetter blocks, Vec3 centre, NestResting.Bed bed) {
        var floor = SurfaceApproach.collisionTop(blocks, body, body.getBbWidth(), centre.add(0, .1, 0));
        return new BedContainer(body, blocks, centre, bed.floor(), floor.y, floor.y);
    }

    /** Test floor support, the body and overhead headwear clearance at the proposed feet. Side walls press the brim. */
    public boolean standsInside(double x,double z){
        return Occupancy.at(this,new Vec3(centre.x+x,floorY,centre.z+z),body.getBbWidth(),true)==Occupancy.INSIDE_SUPPORTED;
    }

    boolean spaceFits(double x,double z){
        if(space==null){
            reach=HeadTop.reach(body);brim=HeadTop.brimBottom(body);top=HeadTop.height(body);
            yieldingBrim=body.life().hasAccessory() && HatGeometry.brim(body)!=null;
            double half=((FloorShape.RoundedRect)floor).half()+reach;
            var bounds=new AABB(centre.x-half,floorY-.001,centre.z-half,centre.x+half,floorY+top,centre.z+half);
            space=new ArrayList<>();
            for(var shape:collisions.getBlockCollisions(body,bounds))for(var b:shape.toAabbs())
                space.add(new BedSpace.Box(b.minX-centre.x,b.minY-floorY,b.minZ-centre.z,b.maxX-centre.x,b.maxY-floorY,b.maxZ-centre.z));
        }
        var level=body.level();
        return level.hasChunksAt(BlockPos.containing(centre.x+x-reach,floorY,centre.z+z-reach),
                BlockPos.containing(centre.x+x+reach,floorY+top,centre.z+z+reach))
                && BedSpace.fits(space,x,z,body.getBbWidth(),body.getBbHeight(),reach,brim,top,yieldingBrim);
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
