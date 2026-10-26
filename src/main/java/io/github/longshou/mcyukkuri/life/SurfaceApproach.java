package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriGroundNavigation;
import io.github.longshou.mcyukkuri.status.Reactions;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Last-step steering is permitted only on a continuous, clear, supported surface. */
public final class SurfaceApproach {
    private SurfaceApproach() {}
    private record Route(Path path,double length) {}
    private record Approach(UUID peer, AABB body, Vec3 destination, Path path, long retryAt, long touched) {}
    private enum Step { ARRIVED, MOVING, BLOCKED }
    /** Transient physical routes only; each domain still owns its agreement, deadline and outcome. */
    private static final Map<YukkuriEntity,Approach> APPROACHES=new WeakHashMap<>();

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
        return actor.onGround()&&clearLastStep(actor,actor.position(),destination);
    }

    private static boolean clearLastStep(YukkuriEntity actor,Vec3 start,Vec3 destination){
        if (Math.abs(start.y-destination.y) > .25 || start.distanceToSqr(destination) > 2.25) return false;
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
        return Double.isFinite(routeLength(actor,destination,null));
    }

    /** Read-only planning, including the last step from a vanilla node to the exact physical stand. */
    public static double routeLength(YukkuriEntity actor,Vec3 destination,AABB obstacle){
        var route=plan(actor,destination,obstacle);
        return route==null?Double.POSITIVE_INFINITY:route.length();
    }

    /** Physical contact uses square body boxes. A point on the centre-to-centre diagonal can be inside both bodies. */
    public static Vec3 beside(YukkuriEntity actor, Entity other, double distance, double maxHeight) {
        return beside(actor,other,distance,maxHeight,Double.POSITIVE_INFINITY);
    }

    /** Some callers use a spherical reach. A step-height difference must leave room inside that same envelope. */
    public static Vec3 beside(YukkuriEntity actor,Entity other,double distance,double maxHeight,double maxDistance){
        var route=APPROACHES.get(actor);
        if(route!=null&&Objects.equals(route.peer(),other.getUUID())&&sameBody(route.body(),other.getBoundingBox())
                &&actor.level().getGameTime()-route.touched()<200){
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
        for(var at:candidates)if(Double.isFinite(routeLength(actor,at,other.getBoundingBox())))return at;
        GameplayLog.limited(actor,"approach","stand",200,"contact stand unavailable peer={} distance={} maxHeight={}",other.getUUID(),distance,maxHeight);
        return null;
    }

    public static boolean crossesBody(YukkuriEntity actor, Vec3 destination, AABB body) {
        return crossesBody(actor,actor.position(),destination,body);
    }

    public static boolean crossesBody(YukkuriEntity actor,Vec3 from,Vec3 to,AABB body){
        double half=actor.getBbWidth()*.5+.015;
        var blocked=new AABB(body.minX-half,body.minY-actor.getBbHeight()+.01,body.minZ-half,
                body.maxX+half,body.maxY-.01,body.maxZ+half);
        // Real overlap can happen after a push. Permit a retreat, never a route through the other's centre.
        if(blocked.contains(from))return blocked.contains(to)
                ||to.subtract(body.getCenter()).multiply(1,0,1).dot(from.subtract(body.getCenter()).multiply(1,0,1))<=0;
        return blocked.contains(to)||blocked.clip(from,to).isPresent();
    }

    private static boolean clearOf(YukkuriEntity actor,Vec3 from,Vec3 to,AABB body){
        return body==null||!crossesBody(actor,from,to,body);
    }

    private static boolean sameBody(AABB a,AABB b){
        return a==null?b==null:b!=null&&a.getCenter().distanceToSqr(b.getCenter())<.0025
                &&Math.abs(a.getXsize()-b.getXsize())<.02&&Math.abs(a.getYsize()-b.getYsize())<.02;
    }

    private static Route plan(YukkuriEntity actor,Vec3 destination,AABB body){
        if(!supported(actor,destination)||TravelRange.keepBabyHome(actor)&&!TravelRange.babyDestination(actor,BlockPos.containing(destination)))return null;
        if(clearOf(actor,actor.position(),destination,body)&&(arrived(actor,destination)||clearLastStep(actor,destination)))
            return new Route(null,actor.position().distanceTo(destination));
        // A containing block is not an exact stand. Offer only node centres with a clear, supported final segment.
        double offset=Mth.floor(actor.getBbWidth()+1)*.5;
        int x=Mth.floor(destination.x-offset+.5),z=Mth.floor(destination.z-offset+.5);
        var targets=new LinkedHashSet<BlockPos>();
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
            var at=floor(actor,new Vec3(x+dx+offset,destination.y,z+dz+offset));
            if(at!=null&&clearOf(actor,at,destination,body)&&clearLastStep(actor,at,destination))
                targets.add(new BlockPos(x+dx,Mth.floor(at.y+.5),z+dz));
        }
        if(targets.isEmpty())return null;
        var navigation=actor.getNavigation();
        var path=navigation instanceof YukkuriGroundNavigation ground?ground.createApproachPath(targets,body):navigation.createPath(targets,0);
        if(path==null||!path.canReach()||path.getNodeCount()==0)return null;
        var first=floor(actor,path.getEntityPosAtNode(actor,0));
        if(first!=null&&actor.onGround()&&Math.abs(first.y-actor.getY())<=.25&&!clearLastStep(actor,first))return null;
        Vec3 before=actor.position();double length=0;
        for(int i=0;i<path.getNodeCount();i++){
            var next=path.getEntityPosAtNode(actor,i);
            if(!actor.level().getFluidState(path.getNodePos(i)).isEmpty()||!clearOf(actor,before,next,body))return null;
            length+=before.distanceTo(next);before=next;
        }
        // Recheck the chosen endpoint: vanilla may return a partial route, or a node at a different height.
        var end=floor(actor,before);
        if(end==null||!clearOf(actor,end,destination,body)||!clearLastStep(actor,end,destination))return null;
        return new Route(path,length+end.distanceTo(destination));
    }

    /** Plan the complete route around blocks and the partner together; never alternate isolated greedy corners. */
    public static boolean walkAround(YukkuriEntity actor,Vec3 destination,Entity other,double speed){
        return walkStep(actor,destination,other==actor?null:other,speed)==Step.ARRIVED;
    }

    private static void remember(YukkuriEntity actor,Approach route){
        if(APPROACHES.size()>=256&&!APPROACHES.containsKey(actor))APPROACHES.clear();
        APPROACHES.put(actor,route);
    }

    /** Never substitute a straight line for a route around a step or obstruction. */
    public static boolean walk(YukkuriEntity actor, Vec3 destination, double speed) {
        return walkStep(actor,destination,null,speed)==Step.ARRIVED;
    }

    private static Step walkStep(YukkuriEntity actor,Vec3 destination,Entity other,double speed){
        long started=System.nanoTime();
        try{return walkStepNow(actor,destination,other,speed);}
        finally{
            long millis=(System.nanoTime()-started)/1_000_000;
            if(millis>=50)GameplayLog.limited(actor,"approach","slow",200,
                    "slow surface approach target={} elapsedMs={} pathDone={} horizontalCollision={}",
                    destination,millis,actor.getNavigation().isDone(),actor.horizontalCollision);
        }
    }

    private static Step walkStepNow(YukkuriEntity actor,Vec3 destination,Entity other,double speed){
        var target=BlockPos.containing(destination);
        if (TravelRange.keepBabyHome(actor) && !TravelRange.babyDestination(actor,target)) { halt(actor); return Step.BLOCKED; }
        if (!Reactions.Weather.allowsStep(actor,target)) { halt(actor); return Step.BLOCKED; }
        var body=other==null?null:other.getBoundingBox();var peer=other==null?null:other.getUUID();
        long now=actor.level().getGameTime();
        if (arrived(actor, destination)) { APPROACHES.remove(actor);halt(actor);return Step.ARRIVED; }
        if (clearOf(actor,actor.position(),destination,body)&&clearLastStep(actor, destination)) {
            remember(actor,new Approach(peer,body,destination,null,now,now));
            steer(actor,destination,speed);return Step.MOVING;
        }
        var navigation=actor.getNavigation();
        var cached=APPROACHES.get(actor);
        boolean same=cached!=null&&Objects.equals(peer,cached.peer())&&sameBody(body,cached.body())
                &&cached.destination().distanceToSqr(destination)<.0025&&now-cached.touched()<200;
        if(same&&cached.path()==null&&now<cached.retryAt()){stopWalking(actor);return Step.BLOCKED;}
        if(same&&cached.path()!=null&&(navigation.getPath()==cached.path()||cached.path().isDone()&&navigation.isDone())){
            remember(actor,new Approach(cached.peer(),cached.body(),cached.destination(),cached.path(),now,now));
            if(!navigation.isDone()){navigation.setSpeedModifier(speed);return Step.MOVING;}
            // Vanilla finishes within its node tolerance; finish that node physically before the exact last step.
            var end=floor(actor,cached.path().getEntityPosAtNode(actor,cached.path().getNodeCount()-1));
            if(end!=null&&!arrived(actor,end)&&clearOf(actor,actor.position(),end,body)&&clearLastStep(actor,end)){
                steer(actor,end,speed);
                return Step.MOVING;
            }
        }
        var route=plan(actor,destination,body);
        if(route!=null&&route.path()!=null&&navigation.moveTo(route.path(),speed)){
            remember(actor,new Approach(peer,body,destination,navigation.getPath(),now,now));
            GameplayLog.limited(actor,"approach","route",200,
                    "surface route started peer={} destination={} nodes={} length={}",peer,destination,route.path().getNodeCount(),route.length());
            return Step.MOVING;
        }
        stopWalking(actor);
        remember(actor,new Approach(peer,body,destination,null,now+20,now));
        GameplayLog.limited(actor,"approach","path_failed",200,
                "surface path unavailable peer={} target={} reason=no_complete_surface_route retryAfter={}",peer,destination,now+20);
        return Step.BLOCKED;
    }

    private static void steer(YukkuriEntity actor,Vec3 destination,double speed){
        actor.getNavigation().stop();
        double remaining=actor.position().distanceTo(destination);
        // Ground travel multiplies forward input by speed again; keep the last few centimetres from slowing quadratically.
        double movement=actor.getAttributeValue(Attributes.MOVEMENT_SPEED);
        double modifier=Math.sqrt(Math.min(.02,remaining*.20))/Math.max(.0001,movement);
        actor.getMoveControl().setWantedPosition(destination.x,destination.y,destination.z,Math.min(speed,modifier));
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
