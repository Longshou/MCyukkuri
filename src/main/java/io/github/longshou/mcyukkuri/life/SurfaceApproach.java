package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.Reactions;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Last-step steering is permitted only on a continuous, clear, supported surface. */
public final class SurfaceApproach {
    private SurfaceApproach() {}
    private record Detour(UUID peer, Vec3 peerAt, Vec3 destination, Vec3 waypoint, long until) {}
    /** Short-lived physical waypoints only; each domain still owns its agreement, deadline and outcome. */
    private static final Map<YukkuriEntity,Detour> DETOURS=new WeakHashMap<>();

    /** Resolve the real sole height on a low nest or adjacent floor, retaining its collision shell. */
    public static Vec3 floor(YukkuriEntity actor, Vec3 near) {
        return floor(actor,near,false);
    }
    /** A planned rest target ignores temporary occupants; walking still checks real collisions. */
    public static Vec3 restingFloor(YukkuriEntity actor, Vec3 near) {
        return floor(actor,near,true);
    }
    /** The highest collision top under the body's square footprint within half a block of {@code near}, unchecked. */
    public static Vec3 surface(YukkuriEntity actor, Vec3 near) { return top(actor,near); }
    private static Vec3 top(YukkuriEntity actor, Vec3 near) {
        if(!actor.level().hasChunkAt(BlockPos.containing(near)))return null;
        double half=actor.getBbWidth()*.5-.005, top=Double.NEGATIVE_INFINITY;
        var probe=new AABB(near.x-half,near.y-.5,near.z-half,near.x+half,near.y+.5,near.z+half);
        for(var shape:actor.level().getBlockCollisions(actor,probe))for(var box:shape.toAabbs())
            if(box.maxX>probe.minX&&box.minX<probe.maxX&&box.maxZ>probe.minZ&&box.minZ<probe.maxZ
                    &&box.maxY>=probe.minY&&box.maxY<=probe.maxY)top=Math.max(top,box.maxY);
        return Double.isFinite(top)?new Vec3(near.x,top,near.z):null;
    }
    private static Vec3 floor(YukkuriEntity actor, Vec3 near, boolean planning) {
        var at=top(actor,near);
        return at!=null&&supported(actor,at,planning)?at:null;
    }

    public static boolean supported(YukkuriEntity actor, Vec3 feet) {
        return supported(actor,feet,false);
    }
    private static boolean supported(YukkuriEntity actor, Vec3 feet, boolean planning) {
        var level = actor.level();
        if (!planning && !Reactions.Weather.allowsStep(actor,BlockPos.containing(feet))) return false;
        if (!level.hasChunkAt(BlockPos.containing(feet)) || !level.getFluidState(BlockPos.containing(feet)).isEmpty()) return false;
        AABB body = actor.getBoundingBox().move(feet.subtract(actor.position())).deflate(.005);
        if (planning ? level.getBlockCollisions(actor,body).iterator().hasNext() : !level.noCollision(actor, body)) return false;
        double radius = actor.getBbWidth() * .42;
        for (int x : new int[]{-1, 1}) for (int z : new int[]{-1, 1}) {
            double px = feet.x + x * radius, pz = feet.z + z * radius;
            var foot = new AABB(px-.015, feet.y-.08, pz-.015, px+.015, feet.y+.005, pz+.015);
            if (!level.getBlockCollisions(actor, foot).iterator().hasNext()) return false;
        }
        return true;
    }

    public static boolean clearLastStep(YukkuriEntity actor, Vec3 destination) {
        Vec3 start = actor.position();
        if (!actor.onGround() || Math.abs(start.y-destination.y) > .25 || start.distanceToSqr(destination) > 2.25) return false;
        if (!supported(actor, destination)) return false;
        int samples = Math.max(1, (int)Math.ceil(start.distanceTo(destination)/Math.min(.05, actor.getBbWidth()*.25)));
        double previous=start.y;
        for (int i=0; i<=samples; i++) {
            var sample=start.lerp(destination,(double)i/samples);
            if(supportedStep(actor,sample)){previous=sample.y;continue;}
            // Partly over an edge (a nest rim or step it is stepping down from): the top under part of the sole is enough
            // for this sample; requiring all four feet here stranded wide bodies on a rim above the floor they walk to.
            var surface=top(actor,sample);
            if(surface==null||Math.abs(surface.y-previous)>.25||!supportedStep(actor,surface))return false;
            previous=surface.y;
        }
        return true;
    }

    /** A step may begin with the body partly over the edge it just climbed onto. */
    private static boolean supportedStep(YukkuriEntity actor, Vec3 feet) {
        var level = actor.level();
        if (!Reactions.Weather.allowsStep(actor,BlockPos.containing(feet))) return false;
        if (!level.hasChunkAt(BlockPos.containing(feet)) || !level.getFluidState(BlockPos.containing(feet)).isEmpty()) return false;
        AABB body = actor.getBoundingBox().move(feet.subtract(actor.position())).deflate(.005);
        if (!level.noCollision(actor, body)) return false;
        // Match the physical feet: vanilla can land while only part of the bounding box touches
        // the bank. Requiring all four inner corners here prevents walking further onto that bank.
        // Every sample still needs actual support, and the final standing place needs all corners.
        var sole = new AABB(body.minX, feet.y-.08, body.minZ, body.maxX, feet.y+.005, body.maxZ);
        return level.getBlockCollisions(actor, sole).iterator().hasNext();
    }

    public static boolean arrived(YukkuriEntity actor, Vec3 destination) {
        // Two actors may stop on opposite edges of this tolerance. Keep their combined error
        // inside the shared mouth envelope, rather than accepting two individually "near" bodies.
        return Math.abs(actor.getY()-destination.y)<.08 && actor.position().subtract(destination).horizontalDistanceSqr()<.0004;
    }

    public static boolean canApproach(YukkuriEntity actor, Vec3 destination) {
        return arrived(actor,destination)||clearLastStep(actor,destination)
                ||actor.life().reachable(BlockPos.containing(destination),false);
    }

    /** Physical contact uses square body boxes. A point on the centre-to-centre diagonal can be inside both bodies. */
    public static Vec3 beside(YukkuriEntity actor, Entity other, double distance, double maxHeight) {
        return beside(actor,other,distance,maxHeight,Double.POSITIVE_INFINITY);
    }

    /** Some callers use a spherical reach. A step-height difference must leave room inside that same envelope. */
    public static Vec3 beside(YukkuriEntity actor,Entity other,double distance,double maxHeight,double maxDistance){
        var route=DETOURS.get(actor);
        if(route!=null&&route.peer().equals(other.getUUID())&&route.peerAt().distanceToSqr(other.position())<.0004
                &&actor.level().getGameTime()<route.until()){
            var at=route.destination();
            double flat=at.subtract(other.position()).horizontalDistance();
            double desired=Math.min(distance,Math.sqrt(Math.max(0,maxDistance*maxDistance-Math.pow(at.y-other.getY(),2)))-.025);
            if(Math.abs(flat-desired)<.01&&Math.abs(at.y-other.getY())<=maxHeight
                    &&!actor.getBoundingBox().move(at.subtract(actor.position())).deflate(.005).intersects(other.getBoundingBox())
                    &&supported(actor,at))return at;
        }
        var directions=new ArrayList<Vec3>();
        var toward=actor.position().subtract(other.position()).multiply(1,0,1);
        if(toward.horizontalDistanceSqr()>.0001)directions.add(toward.normalize());
        directions.add(new Vec3(1,0,0));directions.add(new Vec3(-1,0,0));
        directions.add(new Vec3(0,0,1));directions.add(new Vec3(0,0,-1));
        var candidates=new ArrayList<Vec3>();
        for(var direction:directions){
            var near=other.position().add(direction.scale(distance));
            var at=floor(actor,near);
            if(at==null||Math.abs(at.y-other.getY())>maxHeight)continue;
            if(Double.isFinite(maxDistance)){
                double flat=Math.min(distance,Math.sqrt(Math.max(0,maxDistance*maxDistance-Math.pow(at.y-other.getY(),2)))-.025);
                if(flat<=0)continue;
                at=floor(actor,other.position().add(direction.scale(flat)));
                if(at==null||Math.abs(at.y-other.getY())>maxHeight||at.distanceTo(other.position())>maxDistance)continue;
            }
            var body=actor.getBoundingBox().move(at.subtract(actor.position())).deflate(.005);
            if(body.intersects(other.getBoundingBox()))continue;
            candidates.add(at);
        }
        candidates.sort(Comparator.comparingDouble(at->actor.position().distanceToSqr(at)));
        for(var at:candidates)if(canApproach(actor,at))return at;
        GameplayLog.limited(actor,"approach","stand",200,"contact stand unavailable peer={} distance={} maxHeight={}",other.getUUID(),distance,maxHeight);
        return null;
    }

    public static boolean crossesBody(YukkuriEntity actor, Vec3 destination, AABB body) {
        return crossesBody(actor,actor.position(),destination,body);
    }

    private static boolean crossesBody(YukkuriEntity actor,Vec3 from,Vec3 to,AABB body){
        double half=actor.getBbWidth()*.5+.015;
        var blocked=new AABB(body.minX-half,body.minY-actor.getBbHeight()+.01,body.minZ-half,
                body.maxX+half,body.maxY-.01,body.maxZ+half);
        // Real overlap can happen after a push. Permit a retreat, never a route through the other's centre.
        if(blocked.contains(from))return blocked.contains(to)
                ||to.subtract(body.getCenter()).multiply(1,0,1).dot(from.subtract(body.getCenter()).multiply(1,0,1))<=0;
        return blocked.contains(to)||blocked.clip(from,to).isPresent();
    }

    /** Vanilla block paths ignore living partners. Check their swept body footprint as well as the final stand. */
    private static boolean pathCrosses(YukkuriEntity actor,AABB body){
        var path=actor.getNavigation().getPath();
        if(path==null||path.isDone())return false;
        Vec3 before=actor.position();
        for(int i=path.getNextNodeIndex();i<path.getNodeCount();i++){
            var next=path.getEntityPosAtNode(actor,i);
            if(crossesBody(actor,before,next,body))return true;
            before=next;
        }
        return false;
    }

    private static boolean walkLeg(YukkuriEntity actor,Vec3 at,Entity other,double speed){
        if(crossesBody(actor,at,other.getBoundingBox()))return false;
        boolean close=clearLastStep(actor,at);
        walk(actor,at,speed);
        if(pathCrosses(actor,other.getBoundingBox())){halt(actor);return false;}
        return close||arrived(actor,at)||!actor.getNavigation().isDone();
    }

    /** Walk around the partner using real supported corners; no body relocation or successful-contact substitution. */
    public static boolean walkAround(YukkuriEntity actor,Vec3 destination,Entity other,double speed){
        if(other==null||other==actor)return walk(actor,destination,speed);
        if(arrived(actor,destination)){
            DETOURS.remove(actor);halt(actor);return true;
        }
        long now=actor.level().getGameTime();
        var route=DETOURS.get(actor);
        boolean same=route!=null&&route.peer().equals(other.getUUID())&&route.destination().distanceToSqr(destination)<.0025
                &&route.peerAt().distanceToSqr(other.position())<.01&&now<route.until();
        if(same&&route.waypoint()==null){halt(actor);return false;}
        if(same&&!arrived(actor,route.waypoint())&&walkLeg(actor,route.waypoint(),other,speed))return false;
        DETOURS.remove(actor);
        actor.getNavigation().stop();
        if(walkLeg(actor,destination,other,speed)){
            rememberDetour(actor,new Detour(other.getUUID(),other.position(),destination,destination,now+40));
            return false;
        }
        var body=other.getBoundingBox();
        var corners=new ArrayList<Vec3>();
        // The larger ring also keeps coarse path-node centres outside the partner's collision footprint.
        for(double margin:new double[]{.12,.65}){
            double half=actor.getBbWidth()*.5+margin;
            for(double x:new double[]{body.minX-half,body.maxX+half})for(double z:new double[]{body.minZ-half,body.maxZ+half}){
                var at=floor(actor,new Vec3(x,actor.getY(),z));
                if(at!=null&&actor.position().distanceToSqr(at)>.01&&!crossesBody(actor,at,body))corners.add(at);
            }
        }
        corners.sort(Comparator.comparingDouble(at->actor.position().distanceTo(at)+at.distanceTo(destination)));
        for(var at:corners){
            actor.getNavigation().stop();
            if(!walkLeg(actor,at,other,speed))continue;
            rememberDetour(actor,new Detour(other.getUUID(),other.position(),destination,at,now+40));
            GameplayLog.limited(actor,"approach","detour",200,"partner detour peer={} destination={} waypoint={}",other.getUUID(),destination,at);
            return false;
        }
        halt(actor);rememberDetour(actor,new Detour(other.getUUID(),other.position(),destination,null,now+10));
        GameplayLog.limited(actor,"approach","blocked",200,"partner approach blocked peer={} destination={} reason=no_supported_body_clear_route",other.getUUID(),destination);
        return false;
    }

    private static void rememberDetour(YukkuriEntity actor,Detour route){
        if(DETOURS.size()>=256)DETOURS.clear();
        DETOURS.put(actor,route);
    }

    /** Never substitute a straight line for a route around a step or obstruction. */
    public static boolean walk(YukkuriEntity actor, Vec3 destination, double speed) {
        if (TravelRange.keepBabyHome(actor) && !TravelRange.babyDestination(actor,BlockPos.containing(destination))) { halt(actor); return false; }
        if (!Reactions.Weather.allowsStep(actor, BlockPos.containing(destination))) { halt(actor); return false; }
        if (arrived(actor, destination)) { halt(actor); return true; }
        if (clearLastStep(actor, destination)) {
            actor.getNavigation().stop();
            double remaining = actor.position().distanceTo(destination);
            // Mob.setSpeed also sets its forward input; ground travel multiplies that input by
            // speed again. Linear modifiers made the last few centimetres quadratically slow.
            double movement = actor.getAttributeValue(Attributes.MOVEMENT_SPEED);
            double modifier = Math.sqrt(Math.min(.02, remaining*.20))/Math.max(.0001, movement);
            actor.getMoveControl().setWantedPosition(destination.x, destination.y, destination.z,
                    Math.min(speed, modifier));
        } else if (actor.getNavigation().isDone() || actor.tickCount%10==0) {
            actor.life().navigate(BlockPos.containing(destination), speed, false);
        }
        return false;
    }

    /** Cancel walking input without cancelling a real push (for example, a sumo exchange). */
    public static void stopWalking(YukkuriEntity actor) {
        actor.getNavigation().stop();
        actor.getMoveControl().setWantedPosition(actor.getX(), actor.getY(), actor.getZ(), 0);
        actor.setSpeed(0);
    }

    public static void halt(YukkuriEntity actor) {
        stopWalking(actor);
        if (actor.onGround()) {
            Vec3 velocity = actor.getDeltaMovement();
            actor.setDeltaMovement(0, velocity.y, 0);
        }
    }

    public static void face(YukkuriEntity actor, float yaw) {
        float turn = Mth.approachDegrees(actor.getYRot(), yaw, 12);
        actor.setYRot(turn); actor.yBodyRot = turn; actor.setYHeadRot(turn);
    }

    public static float yaw(Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        return (float)(Math.toDegrees(Math.atan2(delta.z, delta.x))-90);
    }
}
