package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Reactions;
import io.github.longshou.mcyukkuri.life.TravelRange;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

/**
 * Dry-area routines may route around an exposed gap; hunger and danger can release the preference.
 * A walker that stops making progress with another yukkuri in front of it first steps aside, then re-plans around
 * that body, and finally asks an idle blocker to make room. Bodies never close the path finder for good.
 */
public final class YukkuriGroundNavigation extends GroundPathNavigation {
    private static final int WINDOW = 15, REPLAN_GAP = 60;
    private Vec3 sample;
    private long sampleTick, lastReplan;
    private int blockedWindows;
    private Set<BlockPos> avoidCells = Set.of();
    /** Assigned by the parent constructor's createPathFinder call: no field initializer. */
    private PathFinder planner;
    private record Query(int accuracy, int width, int height, AABB obstacle) {}
    private final Map<Path, Query> queries = new WeakHashMap<>();
    private BlockPos walkingTarget;
    private Query walkingQuery;
    private boolean forceSearch, stalled;
    /** Crawl progress window (ticks, as vanilla) and the share of the gait's own travel below which a crawler counts as stuck. */
    private static final int CRAWL_WINDOW=100;
    private static final double CRAWL_SHARE=.25;
    private long crawlCheckTick;
    private Vec3 crawlCheckPos;
    private AABB planningObstacle;
    private int followingNode=-1;
    private float searchMultiplier = 1;

    YukkuriGroundNavigation(YukkuriEntity animal, Level level) { super(animal, level); }

    @Override protected Path createPath(Set<BlockPos> targets, int margin, boolean above, int accuracy) {
        return createPath(targets, margin, above, accuracy, TravelRange.PATH_RANGE);
    }

    /** Planning must not change the installed route's target or reset its stuck clocks. Vanilla createPath does both. */
    @Override protected Path createPath(Set<BlockPos> targets, int margin, boolean above, int accuracy, float range) {
        if(targets.isEmpty()||mob.getY()<level.getMinBuildHeight()||!canUpdatePath())return null;
        if(!forceSearch&&planningObstacle==null&&avoidCells.isEmpty()&&path!=null&&!path.isDone()&&path.canReach()
                &&walkingQuery!=null&&walkingQuery.obstacle()==null
                &&targets.contains(path.getTarget())&&fits(walkingQuery)&&accuracy>=walkingQuery.accuracy())return path;
        long started=System.nanoTime();
        level.getProfiler().push("pathfind");
        Path result;
        try {
            var centre=above?mob.blockPosition().above():mob.blockPosition();
            int span=(int)(range+margin);
            var region=new PathNavigationRegion(level,centre.offset(-span,-span,-span),centre.offset(span,span,span));
            result=planner.findPath(region,mob,targets,range,accuracy,searchMultiplier);
        } finally { level.getProfiler().pop(); }
        if(result!=null){
            if(queries.size()>=128)queries.clear();
            queries.put(result,query(accuracy));
        }
        long millis=(System.nanoTime()-started)/1_000_000;
        if(millis>=50)GameplayLog.limited(mob,"move","path_slow",200,
                "slow ground path search sampleTarget={} candidates={} accuracy={} elapsedMs={} reached={} nodes={} avoiding={}",
                targets.iterator().next(),targets.size(),accuracy,millis,result!=null&&result.canReach(),result==null?0:result.getNodeCount(),avoidCells.size());
        return result;
    }

    /** Movement readiness is separate from a failed search; this does not calculate a path. */
    public boolean canPlanApproach(){return canUpdatePath();}

    /** Whole vanilla routes to physically usable last-step nodes, with the interaction partner treated as an obstacle. */
    public Path createApproachPath(Set<BlockPos> targets,AABB obstacle){
        var previous=planningObstacle;boolean forced=forceSearch;
        planningObstacle=obstacle;forceSearch=true;
        try{return createPath(targets,8,false,0);}
        finally{planningObstacle=previous;forceSearch=forced;}
    }

    private Query query(int accuracy){return new Query(accuracy,Mth.floor(mob.getBbWidth()+1),Mth.floor(mob.getBbHeight()+1),planningObstacle);}
    private boolean fits(Query query){return query!=null&&query.width()==Mth.floor(mob.getBbWidth()+1)&&query.height()==Mth.floor(mob.getBbHeight()+1);}
    @Override public void setMaxVisitedNodesMultiplier(float multiplier){super.setMaxVisitedNodesMultiplier(multiplier);searchMultiplier=multiplier;}
    @Override public void resetMaxVisitedNodesMultiplier(){super.resetMaxVisitedNodesMultiplier();searchMultiplier=1;}
    @Override public BlockPos getTargetPos(){return walkingTarget;}
    @Override public boolean isStuck(){return stalled;}

    /** Reissuing a walk is not progress: preserve the node index and timeout when the route is unchanged. */
    @Override public boolean moveTo(Path requested,double speed){
        if(requested==null){stop();return false;}
        var planned=queries.get(requested);
        if(path!=null&&!path.isDone()&&fits(walkingQuery)&&requested.getTarget().equals(walkingTarget)
                &&requested.sameAs(path)&&requested.canReach()==path.canReach()){
            speedModifier=speed;
            if(planned!=null)walkingQuery=planned;
            return true;
        }
        // Vanilla sameAs compares nodes only, not the requested target or search accuracy.
        path=null;
        if(!super.moveTo(requested,speed)){stop();return false;}
        walkingTarget=requested.getTarget();walkingQuery=planned==null?query(0):planned;
        timeoutCachedNode=Vec3i.ZERO;timeoutTimer=0;timeoutLimit=0;lastTimeoutCheck=level.getGameTime();
        hasDelayedRecomputation=false;stalled=false;resetBlock();
        return true;
    }

    @Override public void stop(){
        super.stop();walkingTarget=null;walkingQuery=null;hasDelayedRecomputation=false;stalled=false;resetBlock();
    }

    /** Rebuild the installed destination, never the last point inspected by a different goal. */
    @Override public void recomputePath(){
        if(walkingTarget==null||isDone()){hasDelayedRecomputation=false;return;}
        if(!canUpdatePath()||fits(walkingQuery)&&level.getGameTime()-timeLastRecompute<=20){hasDelayedRecomputation=true;return;}
        var target=walkingTarget;int accuracy=walkingQuery==null?0:walkingQuery.accuracy();
        Path replacement;
        forceSearch=true;
        planningObstacle=walkingQuery==null?null:walkingQuery.obstacle();
        try{replacement=createPath(target,accuracy);}finally{forceSearch=false;planningObstacle=null;}
        timeLastRecompute=level.getGameTime();hasDelayedRecomputation=false;
        moveTo(replacement,speedModifier);
    }

    @Override protected void doStuckDetection(Vec3 at){
        // This hook runs after vanilla advances, but before it samples node progress and timeout.
        if(path!=null&&!path.isDone()&&followingNode>=0&&path.getNextNodeIndex()>followingNode&&walkingQuery!=null
                &&crossesObstacle(mob.position(),path.getNextEntityPos(mob),walkingQuery.obstacle()))
            path.setNextNodeIndex(followingNode);
        boolean walking=!isDone();
        var target=walkingTarget;
        if(mob instanceof YukkuriEntity crawler&&crawler.isCrawling()){crawlStuckDetection(crawler,at,walking,target);return;}
        crawlCheckPos=null;
        super.doStuckDetection(at);
        if(walking&&isDone()){
            stalled=true;
            GameplayLog.limited(mob,"move","path_stalled",200,"ground path stopped reason=no_progress target={} at={}",target,at);
        }
    }

    /**
     * Vanilla judges progress (and node timeouts) from the attribute speed of a walking body; a crawl covers a small fraction of
     * that, so a steady crawl was stopped as stuck every 100 ticks (E11-3). A crawler is judged against its own gait instead:
     * stuck when it covered less than a quarter of what its stroke would have carried it in the same window.
     */
    private void crawlStuckDetection(YukkuriEntity crawler,Vec3 at,boolean walking,BlockPos target){
        long now=level.getGameTime();
        if(crawlCheckPos!=null&&now-crawlCheckTick<=CRAWL_WINDOW)return;
        if(walking&&crawlCheckPos!=null&&at.distanceTo(crawlCheckPos)<crawler.crawlSpeed()*(now-crawlCheckTick)*CRAWL_SHARE){
            stop();stalled=true;
            GameplayLog.limited(mob,"move","path_stalled",200,"ground path stopped reason=no_progress gait=crawl target={} at={} moved={} expected={}",
                    target,at,String.format(java.util.Locale.ROOT,"%.3f",at.distanceTo(crawlCheckPos)),
                    String.format(java.util.Locale.ROOT,"%.3f",crawler.crawlSpeed()*(now-crawlCheckTick)));
        }
        crawlCheckTick=now;crawlCheckPos=at;
    }

    @Override protected void followThePath(){
        followingNode=path.getNextNodeIndex();
        try{super.followThePath();}finally{followingNode=-1;}
    }

    private boolean crossesObstacle(Vec3 from,Vec3 to,AABB obstacle){
        return obstacle!=null&&mob instanceof YukkuriEntity animal&&SurfaceApproach.crossesBody(animal,from,to,obstacle);
    }

    @Override protected PathFinder createPathFinder(int nodes) {
        nodeEvaluator = new WalkNodeEvaluator() {
            private boolean keepDry, keepHome;
            @Override public void prepare(PathNavigationRegion region, Mob mob) {
                super.prepare(region, mob);
                keepDry = mob instanceof YukkuriEntity animal && Reactions.Weather.keepDry(animal);
                keepHome = mob instanceof YukkuriEntity animal && TravelRange.keepBabyHome(animal);
            }
            @Override public Node getStart(){
                var start=super.getStart();
                if(!forceSearch||!(mob instanceof YukkuriEntity animal)||!animal.onGround())return start;
                var nativeFeet=SurfaceApproach.floor(animal,new Vec3(start.x+entityWidth*.5,start.y,start.z+entityDepth*.5));
                if(start.costMalus>=0&&nativeFeet!=null&&!crossesObstacle(mob.position(),nativeFeet,planningObstacle)
                        &&SurfaceApproach.clearLastStep(animal,nativeFeet))return start;
                // A wide body's vanilla start may sit on a soft rim or across a partner. Enter by a real short step.
                double offset=entityWidth*.5;
                int x=Mth.floor(mob.getX()-offset+.5),z=Mth.floor(mob.getZ()-offset+.5);
                BlockPos best=null;double distance=Double.POSITIVE_INFINITY;
                for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
                    var at=SurfaceApproach.floor(animal,new Vec3(x+dx+offset,mob.getY(),z+dz+offset));
                    if(at==null||crossesObstacle(mob.position(),at,planningObstacle)||!SurfaceApproach.clearLastStep(animal,at))continue;
                    var cell=new BlockPos(x+dx,Mth.floor(at.y+.5),z+dz);
                    double travel=mob.position().distanceToSqr(at);
                    if(travel<distance&&canStartAt(cell)){best=cell;distance=travel;}
                }
                return best==null?start:getStartNode(best);
            }
            @Override protected PathType getCachedPathType(int x, int y, int z) {
                PathType type = super.getCachedPathType(x, y, z);
                if(planningObstacle!=null&&mob.getDimensions(mob.getPose()).makeBoundingBox(
                        x+entityWidth*.5,y,z+entityDepth*.5).inflate(.02,0,.02).intersects(planningObstacle))return PathType.BLOCKED;
                // A wide mob's node covers several cells, with its centre at node + width/2, not node + .5.
                for(var cell:avoidCells)if(cell.getX()>=x&&cell.getX()<x+entityWidth&&cell.getY()>=y&&cell.getY()<y+entityHeight
                        &&cell.getZ()>=z&&cell.getZ()<z+entityDepth)return PathType.BLOCKED;
                if (keepHome && mob instanceof YukkuriEntity animal
                        && !TravelRange.babyDestination(animal,new BlockPos(x,y,z))) return PathType.BLOCKED;
                if (keepDry && mob instanceof YukkuriEntity animal
                        && Reactions.Weather.rainAt(animal, new BlockPos(x, y, z))) return PathType.BLOCKED;
                return type;
            }
            @Override public int getNeighbors(Node[] neighbors,Node node){
                int count=super.getNeighbors(neighbors,node);
                if(planningObstacle==null)return count;
                var from=new Vec3(node.x+entityWidth*.5,node.y,node.z+entityDepth*.5);
                int kept=0;
                for(int i=0;i<count;i++){
                    var next=neighbors[i];
                    if(!crossesObstacle(from,new Vec3(next.x+entityWidth*.5,next.y,next.z+entityDepth*.5),planningObstacle))
                        neighbors[kept++]=next;
                }
                return kept;
            }
        };
        nodeEvaluator.setCanPassDoors(true);
        return planner=new PathFinder(nodeEvaluator, Math.max(nodes, TravelRange.PATH_RANGE * 16));
    }

    @Override public void tick() {
        // Path nodes change their centre offset at whole-block body widths. Never follow an old-width route after growth.
        if(path!=null&&!path.isDone()&&!fits(walkingQuery)){
            recomputePath();
            if(path!=null&&!path.isDone()&&!fits(walkingQuery)){
                mob.getMoveControl().setWantedPosition(mob.getX(),mob.getY(),mob.getZ(),0);mob.setSpeed(0);return;
            }
        }
        if (mob instanceof YukkuriEntity animal && Reactions.Weather.keepDry(animal) && path != null && !path.isDone()) {
            var next = path.getNextNodePos();
            if (Reactions.Weather.rainAt(animal, next)) stop();
        }
        if (mob instanceof YukkuriEntity animal && !animal.level().isClientSide) watchBodyBlock(animal);
        super.tick();
    }

    private void resetBlock() { sample = null; blockedWindows = 0; }

    /** Samples progress every WINDOW ticks; only a stalled walker with a body ahead escalates, so nothing runs per tick. */
    private void watchBodyBlock(YukkuriEntity animal) {
        long now = animal.level().getGameTime();
        if (path == null || path.isDone() || !animal.onGround() || animal.isPassenger() || animal.isCommandPlaying()) {
            resetBlock();
            return;
        }
        if (sample == null) { sample = animal.position(); sampleTick = now; return; }
        if (now - sampleTick < WINDOW) return;
        double moved = Math.hypot(animal.getX() - sample.x, animal.getZ() - sample.z);
        sample = animal.position(); sampleTick = now;
        if (moved >= (animal.isCrawling() ? .02 : .12)) { blockedWindows = 0; return; }
        Vec3 heading = heading(animal);
        YukkuriEntity blocker = bodyAhead(animal, heading);
        if (blocker == null) { blockedWindows = 0; return; }
        int stage = ++blockedWindows;
        String action;
        if (stage == 1 || stage == 4) action = sidestep(animal, blocker, heading) ? "sidestep" : "no_room";
        else if (stage == 2) action = replan(animal, blocker, now) ? "replanned" : "no_alternative";
        else action = yieldRequest(animal, blocker, heading) ? "blocker_yields" : "blocker_busy";
        if (stage >= 4) blockedWindows = 0;
        GameplayLog.limited(animal, "move", "body_block_" + action, 100,
                "blocked by yukkuri {} #{} action={} window={} target={}",
                blocker.getUUID(), blocker.getId(), action, stage, getTargetPos());
    }

    private Vec3 heading(YukkuriEntity animal) {
        Vec3 next = path.getNextEntityPos(animal);
        Vec3 flat = new Vec3(next.x - animal.getX(), 0, next.z - animal.getZ());
        if (flat.lengthSqr() < 1e-4) flat = new Vec3(animal.getLookAngle().x, 0, animal.getLookAngle().z);
        return flat.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : flat.normalize();
    }

    /** The nearest touching body ahead; a final path node is not evidence that every nearby body is the target. */
    private YukkuriEntity bodyAhead(YukkuriEntity animal, Vec3 heading) {
        YukkuriEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (YukkuriEntity other : animal.level().getEntitiesOfClass(YukkuriEntity.class,
                animal.getBoundingBox().inflate(.6, .1, .6),
                o -> o != animal && o.isAlive() && !o.isPassenger() && !animal.isPassengerOfSameVehicle(o))) {
            if (animal.careApproach().cooperatingWith(other) || other.family().crew().holdsBody()) continue;
            if (!animal.getBoundingBox().inflate(.08,.05,.08).intersects(other.getBoundingBox())) continue;
            double dx = other.getX() - animal.getX(), dz = other.getZ() - animal.getZ(), distance = Math.hypot(dx, dz);
            double touching = (animal.getBbWidth() + other.getBbWidth()) * .5;
            boolean ahead = distance < touching * .5 || (dx * heading.x + dz * heading.z) / Math.max(distance, 1e-4) > .25;
            if (ahead && distance < touching + .5 && distance < bestDistance) { best = other; bestDistance = distance; }
        }
        return best;
    }

    private static Vec3 right(Vec3 heading) { return new Vec3(-heading.z, 0, heading.x); }

    /** One short walking step to the freer side (own right first, so opposing traffic parts instead of mirroring). */
    private boolean sidestep(YukkuriEntity animal, YukkuriEntity blocker, Vec3 heading) {
        Vec3 right = right(heading);
        double span = (animal.getBbWidth() + blocker.getBbWidth()) * .6;
        for (int sign : new int[]{1, -1}) {
            Vec3 shift = right.scale(sign * span);
            var box = animal.getBoundingBox().move(shift.x, 0, shift.z);
            if (!animal.level().noCollision(animal, box)) continue;
            if (!isStableDestination(BlockPos.containing(animal.getX() + shift.x, animal.getY(), animal.getZ() + shift.z))) continue;
            animal.nudge(right.x * sign * .1, right.z * sign * .1);
            return true;
        }
        return false;
    }

    /** Rebuilds the route with the blocker's cells closed; the old route is kept when no other one reaches. */
    private boolean replan(YukkuriEntity animal, YukkuriEntity blocker, long now) {
        BlockPos target = getTargetPos();
        if (target == null || now - lastReplan < REPLAN_GAP) return false;
        lastReplan = now;
        var box = blocker.getBoundingBox();
        Set<BlockPos> cells = new HashSet<>();
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(box.minX+.001, box.minY+.001, box.minZ+.001),
                BlockPos.containing(box.maxX-.001, box.maxY-.001, box.maxZ-.001))) cells.add(cell.immutable());
        cells.remove(animal.blockPosition());
        avoidCells = cells;
        Path detour;
        forceSearch=true;
        planningObstacle=walkingQuery==null?null:walkingQuery.obstacle();
        try { detour = createPath(target, walkingQuery==null?0:walkingQuery.accuracy()); }
        finally { avoidCells = Set.of();forceSearch=false;planningObstacle=null; }
        if (detour == null || !detour.canReach() || detour.sameAs(path)) return false;
        return moveTo(detour, speedModifier);
    }

    /** An idle, awake blocker moves out of the way; sleeping, busy or boarded ones keep their place. */
    private boolean yieldRequest(YukkuriEntity animal, YukkuriEntity blocker, Vec3 heading) {
        if (!blocker.getNavigation().isDone() || blocker.isCommandPlaying() || blocker.operations().active()
                || blocker.life().isSleeping() || !blocker.nudgeable()) return false;
        Vec3 right = right(heading);
        double side = Math.signum((blocker.getX() - animal.getX()) * right.x + (blocker.getZ() - animal.getZ()) * right.z);
        if (side == 0) side = 1;
        blocker.nudge(right.x * side * .16, right.z * side * .16);
        return true;
    }
}
