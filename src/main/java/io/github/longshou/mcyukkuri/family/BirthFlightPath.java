package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** One read-only body sweep for the unborn outlet, committed birth arc and actual falling contact. */
final class BirthFlightPath {
    record Body(YukkuriEntity context,UUID id,YukkuriEntity mother,FamilyData.Child unborn,float yaw) {
        List<AABB> envelope(Vec3 feet) {
            return unborn==null?BraidRescueGeometry.boardingEnvelope(context,feet,0,0,yaw)
                    :BraidRescueGeometry.birthEnvelope(unborn,feet,yaw);
        }
        double support(HatBoatEntity hat,Vec3 feet) {
            return unborn==null?BraidRescueGeometry.poseSupport(context,feet,0,0,yaw,hat,null)
                    :BraidRescueGeometry.birthSupport(unborn,feet,yaw,hat);
        }
        double width() { return unborn==null?context.getBbWidth():LiveBirthGeometry.childDimensions(unborn,1).width(); }
    }

    static Body unborn(YukkuriEntity mother,FamilyData.Child child,float yaw) {
        return new Body(mother,child.id,mother,child,yaw);
    }
    static Body actual(YukkuriEntity child) {
        var mother=child.family().mother()!=null&&child.level() instanceof ServerLevel level
                &&level.getEntity(child.family().mother()) instanceof YukkuriEntity parent?parent:null;
        return new Body(child,child.getUUID(),mother,null,child.yBodyRot);
    }

    /** Within the existing birth impulse budget; no valid duration is a refusal, never an unchecked maximum arc. */
    static int plan(Body body,HatBoatEntity hat,Vec3 from,Vec3 to,int minimum) {
        if(body.support(hat,to)>to.y+.002)return -1;
        int start=HatVessel.solve(from,to,hat,HatVessel.radius(body.width()),minimum);
        for(int ticks=start;ticks<=HatVessel.TMAX;ticks++)if(path(body,hat,from,to,ticks).isEmpty())return ticks;
        return -1;
    }
    static String path(Body body,HatBoatEntity hat,Vec3 from,Vec3 to,int ticks) {
        Vec3 previous=from;
        // Actual travel integrates one straight segment per tick between these parabola points.
        // Sweep those same chords; checking only the ideal sub-tick curve misses their lower clearance.
        for(int tick=1;tick<=ticks;tick++) {
            var at=HatVessel.arc(from,to,ticks,tick);
            String refusal=sweep(body,hat,previous,at,false);
            if(!refusal.isEmpty())return refusal;
            previous=at;
        }
        return "";
    }
    static String sweep(Body body,HatBoatEntity hat,Vec3 from,Vec3 to,boolean dropping) {
        var level=body.context().level();
        int steps=Math.max(1,(int)Math.ceil(from.distanceTo(to)/.025));
        for(int i=1;i<=steps;i++) {
            var feet=from.lerp(to,(double)i/steps);
            if(!level.hasChunkAt(BlockPos.containing(feet)))return "chunk_unloaded";
            if(hat!=null) {
                double support=body.support(hat,feet);
                if(support>feet.y+.002)return "hull clearance "+support+" > y "+feet.y;
            }
            for(var shape:body.envelope(feet)) {
                var box=shape.deflate(.002);
                if(!level.getWorldBorder().isWithinBounds(box))return "border";
                if(level.getBlockCollisions(body.context(),box).iterator().hasNext())return "block at "+feet;
                for(var other:level.getEntities(body.context(),box)) {
                    if(other.getUUID().equals(body.id())||other==body.mother()||other==hat||!other.isAlive()||other.isSpectator())continue;
                    if(dropping&&hat!=null&&other.getUUID().equals(hat.ownerYukkuri()))continue;
                    if(other instanceof YukkuriEntity y) {
                        // Existing seat admission separates siblings; the mother's body contains the real outlet.
                        if(hat!=null&&y.getVehicle()==hat)continue;
                        for(var part:BraidRescueGeometry.envelope(y,y.position()))if(box.intersects(part))return "yukkuri "+y.getId();
                    }else if(other.canBeCollidedWith()&&box.intersects(other.getBoundingBox()))return "entity "+other.getId();
                }
            }
        }
        return "";
    }
    private BirthFlightPath() {}
}
