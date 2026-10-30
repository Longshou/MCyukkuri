package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.motor.geom.Detour;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Reactions;
import io.github.longshou.mcyukkuri.life.TravelRange;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
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
 * Placed hats and standing bodies are footprints ({@link Detour}): a search keeps the foot off a hat's brim and prefers to
 * miss standing bodies; every step heads round the first footprint on the way, on the side it took. A walker that stops
 * making progress against one plans round it once (solid for a while), then asks an idle body to make room.
 * Bodies never close the path finder for good.
 */
public final class YukkuriGroundNavigation extends GroundPathNavigation {
    /** Stall sample window (ticks); how close (blocks) to where it heads a body counts as arriving, not held up. */
    private static final int WINDOW = 15;
    private static final double ARRIVING = .3;
    /** How long (ticks) a blocker stays solid for planning, how many at once, and the widest stretch (blocks) a search looks for footprints over. */
    private static final int SOLID_TICKS = 100, SOLID_MAX = 8, PLAN_SPAN = 48;
    /** A footprint a walker keeps its foot off: plane box, the height it stands at, the hat or body, and whether nothing may stand in it. */
    public record Obstacle(Entity entity, Detour.Box box, double y, boolean solid) {}
    private record Blocker(Obstacle obstacle, long until) {}
    private Vec3 sample, aimed;
    /** The length of the way round to where the last step was aimed. */
    private double way;
    private long sampleTick, turnId, turnTick = -1000, steerTick = -1000, stallTick = -1000;
    private int blockedWindows, turnSide, replans;
    private final Map<Long, Blocker> blockers = new LinkedHashMap<>();
    private List<Obstacle> planning = List.of();
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
        if(!forceSearch&&planningObstacle==null&&path!=null&&!path.isDone()&&path.canReach()
                &&walkingQuery!=null&&walkingQuery.obstacle()==null
                &&targets.contains(path.getTarget())&&fits(walkingQuery)&&accuracy>=walkingQuery.accuracy())return path;
        long started=System.nanoTime();
        level.getProfiler().push("pathfind");
        Path result;
        try {
            var centre=above?mob.blockPosition().above():mob.blockPosition();
            int span=(int)(range+margin);
            var region=new PathNavigationRegion(level,centre.offset(-span,-span,-span),centre.offset(span,span,span));
            planning=planned(targets);
            result=planner.findPath(region,mob,targets,range,accuracy,searchMultiplier);
        } finally { planning=List.of();level.getProfiler().pop(); }
        if(result!=null){
            if(queries.size()>=128)queries.clear();
            queries.put(result,query(accuracy));
        }
        long millis=(System.nanoTime()-started)/1_000_000;
        if(millis>=50)GameplayLog.limited(mob,"move","path_slow",200,
                "slow ground path search sampleTarget={} candidates={} accuracy={} elapsedMs={} reached={} nodes={} solid={}",
                targets.iterator().next(),targets.size(),accuracy,millis,result!=null&&result.canReach(),result==null?0:result.getNodeCount(),blockers.size());
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
        walkingTarget=requested.getTarget();walkingQuery=planned==null?query(0):planned;crawlCheckPos=null;crawlCheckTick=0;
        timeoutCachedNode=Vec3i.ZERO;timeoutTimer=0;timeoutLimit=0;lastTimeoutCheck=level.getGameTime();
        hasDelayedRecomputation=false;stalled=false;resetBlock();
        return true;
    }

    @Override public void stop(){
        super.stop();crawlCheckPos=null;crawlCheckTick=0;walkingTarget=null;walkingQuery=null;hasDelayedRecomputation=false;stalled=false;resetBlock();
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
                    target,at,String.format(Locale.ROOT,"%.3f",at.distanceTo(crawlCheckPos)),
                    String.format(Locale.ROOT,"%.3f",crawler.crawlSpeed()*(now-crawlCheckTick)));
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
                // A wide mob's node covers several cells, with its centre at node + width/2, not node + .5: a foot there over a
                // solid footprint (a hat's brim, a blocker) is blocked, over a standing body it costs as a danger.
                if(!planning.isEmpty()&&type!=PathType.OPEN&&mob.getPathfindingMalus(type)>=0){
                    double cx=x+entityWidth*.5,cz=z+entityDepth*.5,clear=Occupancy.foot(mob.getBbWidth());
                    boolean costly=false;
                    for(var o:planning)if(o.y()>=y-.5&&o.y()<y+1&&o.box().gap(cx,cz)<clear){
                        if(o.solid())return PathType.BLOCKED;
                        costly=true;
                    }
                    if(costly&&type==PathType.WALKABLE)type=PathType.DANGER_OTHER;
                }
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
        if (mob instanceof YukkuriEntity animal && !animal.level().isClientSide) watch(animal);
        super.tick();
        // The route knew the footprints as they were when it was planned: each step to the next node heads round them now,
        // where the way round stands on supported, open ground (never off a ledge or into a wall).
        var control = mob.getMoveControl();
        if (!isDone() && control.hasWanted() && mob instanceof YukkuriEntity walker) {
            var wanted = new Vec3(control.getWantedX(), control.getWantedY(), control.getWantedZ());
            var aim = aim(wanted, true);
            if (aim != wanted && SurfaceApproach.supported(walker, aim)) control.setWantedPosition(aim.x, aim.y, aim.z, control.getSpeedModifier());
        }
    }

    /** Placed hats (their brims, solid) and, unless {@code hatsOnly}, other free bodies (their boxes) in {@code area}, standing within a block of height {@code y} (NaN: any). */
    public static List<Obstacle> obstacles(YukkuriEntity walker, AABB area, double y, boolean hatsOnly) {
        var out = new ArrayList<Obstacle>();
        for (var e : walker.level().getEntities(walker, area.inflate(1), x -> x instanceof PlacedHatEntity || !hatsOnly && x instanceof YukkuriEntity)) {
            if (!e.isAlive() || e.isPassenger() || walker.getVehicle() == e || Math.abs(e.getY() - y) >= 1) continue;
            if (e instanceof PlacedHatEntity hat) {
                float[] brim = HatGeometry.brim();
                double unit = hat.unit(), turn = hat.turn();
                out.add(new Obstacle(e, new Detour.Box(e.getId(), e.getX(), e.getZ(), brim[0] * unit, brim[1] * unit, Math.cos(turn), -Math.sin(turn)), e.getY(), true));
            } else {
                var box = e.getBoundingBox();
                out.add(new Obstacle(e, Detour.Box.aligned(e.getId(), box.minX, box.minZ, box.maxX, box.maxZ), e.getY(), false));
            }
        }
        return out;
    }

    /** What a search plans round: the hats and standing bodies between the walker and its targets, and the blockers it was held up by lately (solid). */
    private List<Obstacle> planned(Set<BlockPos> targets) {
        if (!(mob instanceof YukkuriEntity walker)) return List.of();
        var area = new AABB(mob.blockPosition());
        for (var target : targets) area = area.minmax(new AABB(target));
        if (area.getXsize() > PLAN_SPAN || area.getZsize() > PLAN_SPAN) area = mob.getBoundingBox().inflate(PLAN_SPAN / 2.0);
        var out = new ArrayList<Obstacle>();
        for (var o : obstacles(walker, area.inflate(2), Double.NaN, false))
            if (o.solid() || o.entity().getDeltaMovement().horizontalDistanceSqr() < 1e-4) out.add(o);
        long now = level.getGameTime();
        blockers.values().removeIf(s -> s.until() <= now);
        for (var s : blockers.values()) out.add(s.obstacle());
        return out;
    }

    /** Where to head now on the way to {@code to}: past the first footprint the straight line crosses, on the side kept ({@link Detour}). {@code moving}: the step is being taken (it keeps the side and counts for the stall watch); otherwise only a look. */
    public Vec3 aim(Vec3 to, boolean moving) {
        if (!(mob instanceof YukkuriEntity walker)) return to;
        var at = mob.position();
        long now = level.getGameTime();
        if (moving) { steerTick = now; aimed = to; way = at.distanceTo(to); }
        double clear = Occupancy.foot(mob.getBbWidth());
        var near = obstacles(walker, new AABB(at, to).inflate(clear + .5), at.y, false);
        if (near.isEmpty()) return to;
        var turn = Detour.steer(at.x, at.z, to.x, to.z, clear, near.stream().map(Obstacle::box).toList(), turnId, turnSide);
        if (turn.side() == 0) return to;
        if (moving) {
            way = turn.way();
            if (turn.id() != turnId || turn.side() != turnSide) near.stream().filter(o -> o.box().id() == turn.id()).findFirst().ifPresent(o ->
                    GameplayLog.limited(mob, "move", "detour", 100, "walking round {} {} #{} side={} at={} to={}", kind(o),
                            o.entity().getUUID(), o.entity().getId(), turn.side(), GameplayLog.at(at), GameplayLog.at(to)));
            turnId = turn.id(); turnSide = turn.side(); turnTick = now;
        }
        return new Vec3(turn.x(), to.y, turn.z());
    }

    /** A direct steer stalled against a footprint just now: the surface approach then plans a route round it. */
    public boolean takeStall() { boolean recent = level.getGameTime() - stallTick <= 2; stallTick = -1000; return recent; }

    /** The way left to {@code to} by the turn just taken (a walk's honest metric while it steers round a footprint), or NaN when it is not being steered there. */
    public double way(Vec3 to) { return aimed != null && aimed.distanceToSqr(to) < 1e-6 && level.getGameTime() - steerTick <= 2 ? way : Double.NaN; }

    /** How many times a stall made this walker plan round a blocker: a walk that sees it grow measures progress anew. */
    public int replans() { return replans; }

    private static String kind(Obstacle o) { return o.entity() instanceof PlacedHatEntity ? "hat" : "yukkuri"; }

    private void resetBlock() { sample = null; blockedWindows = 0; }

    /** Samples progress every WINDOW ticks while walking (a path or a direct steer); only a walker held up by a footprint ahead escalates, so nothing runs per tick. */
    private void watch(YukkuriEntity animal) {
        long now = animal.level().getGameTime();
        boolean walking = path != null && !path.isDone() || now - steerTick <= 2;
        if (!walking || !animal.onGround() || animal.isPassenger() || animal.isCommandPlaying()) {
            resetBlock();
            return;
        }
        if (sample == null) { sample = animal.position(); sampleTick = now; return; }
        if (now - sampleTick < WINDOW) return;
        double moved = Math.hypot(animal.getX() - sample.x, animal.getZ() - sample.z);
        sample = animal.position(); sampleTick = now;
        if (moved >= (animal.isCrawling() ? .02 : .12) || aimed == null || animal.position().distanceTo(aimed) < ARRIVING) { blockedWindows = 0; return; }
        Vec3 heading = new Vec3(aimed.x - animal.getX(), 0, aimed.z - animal.getZ()).normalize();
        var blocker = blocker(animal, heading, now);
        if (blocker == null) { blockedWindows = 0; return; }
        int stage = ++blockedWindows;
        String action = stage == 1 ? replan(blocker, now) ? "replanned" : "no_alternative"
                : stage == 2 && blocker.entity() instanceof YukkuriEntity other ? yieldRequest(animal, other, heading) ? "blocker_yields" : "blocker_busy"
                : "waiting";
        GameplayLog.limited(animal, "move", "body_block_" + action, 100,
                "blocked by {} {} #{} action={} window={} to={}", kind(blocker),
                blocker.entity().getUUID(), blocker.entity().getId(), action, stage, GameplayLog.at(aimed));
    }

    /** What holds the walker up: the footprint it was turning round, if still touching, else the nearest touching one ahead; never a body it is meeting. */
    private Obstacle blocker(YukkuriEntity animal, Vec3 heading, long now) {
        double clear = Occupancy.foot(animal.getBbWidth()), nearest = Double.MAX_VALUE;
        Obstacle best = null;
        for (var o : obstacles(animal, animal.getBoundingBox().inflate(.6), animal.getY(), false)) {
            if (o.entity() instanceof YukkuriEntity other && animal.careApproach().cooperatingWith(other)) continue;
            var b = o.box();
            double gap = b.gap(animal.getX(), animal.getZ());
            boolean ahead = gap < 0 || b.id() == turnId && now - turnTick <= WINDOW
                    || (b.x() - animal.getX()) * heading.x + (b.z() - animal.getZ()) * heading.z > 0;
            if (gap <= clear + .1 && ahead && gap < nearest) { best = o; nearest = gap; }
        }
        return best;
    }

    /** Plans round the blocker once, solid for a while: false when it was planned round already or no other route reaches. A direct steer leaves the route to the surface approach. */
    private boolean replan(Obstacle blocker, long now) {
        blockers.values().removeIf(s -> s.until() <= now);
        long id = blocker.box().id();
        if (blockers.containsKey(id) || blockers.size() >= SOLID_MAX) return false;
        blockers.put(id, new Blocker(new Obstacle(blocker.entity(), blocker.box(), blocker.y(), true), now + SOLID_TICKS));
        if (path == null || path.isDone()) { stallTick = now; replans++; return true; }
        BlockPos target = getTargetPos();
        if (target == null) return false;
        Path detour;
        forceSearch=true;
        planningObstacle=walkingQuery==null?null:walkingQuery.obstacle();
        try { detour = createPath(target, walkingQuery==null?0:walkingQuery.accuracy()); }
        finally { forceSearch=false;planningObstacle=null; }
        if (detour == null || !detour.canReach() || detour.sameAs(path) || !moveTo(detour, speedModifier)) return false;
        replans++;
        return true;
    }

    private static Vec3 right(Vec3 heading) { return new Vec3(-heading.z, 0, heading.x); }

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
