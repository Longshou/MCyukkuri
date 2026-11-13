package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.motor.geom.Detour;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.settlement.GroupPits;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Reactions;
import io.github.longshou.mcyukkuri.life.TravelRange;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.NestDoorway;
import io.github.longshou.mcyukkuri.motor.Motor;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Dry-area routines may route around an exposed gap; hunger and danger can release the preference.
 * Placed hats and standing bodies are footprints ({@link Detour}): a search keeps the foot off a hat's brim and prefers to
 * miss standing bodies, never closer than the start and the goal leave them; every step heads round the first footprint on
 * the way, on the side it took. The body a walk's goal belongs to and bodies walking themselves are not steered round. A
 * walker held up by one plans round it (solid for a while), then gives way once (two walking into each other: only the lower
 * id; an idle blocker is asked to move), and plans again when the mark lapses. Bodies never close the path finder for good.
 */
public final class YukkuriGroundNavigation extends GroundPathNavigation {
    /** Stall sample window (ticks); how close (blocks) to where it heads a body counts as arriving, not held up; how far past its
     *  foot (blocks) from the walk's goal a body is the one the goal belongs to; how far round the walker the footprint scan reaches. */
    private static final int WINDOW = 15;
    private static final double ARRIVING = .3, CONTACT = .3, SCAN = 3;
    /** Ticks a blocker stays solid for planning and how many at once; the widest stretch (blocks) a search looks for footprints
     *  over; ticks the footprint scan round a walker is reused, and a side taken round one is kept. */
    private static final int SOLID_TICKS = 100, SOLID_MAX = 8, PLAN_SPAN = 48, SCAN_TICKS = 5, KEEP_TICKS = 40;
    /** A footprint a walker keeps its foot off: plane box, the height it stands at, the hat or body, and whether nothing may stand in it. */
    public record Obstacle(Entity entity, Detour.Box box, double y, boolean solid) {}
    /** A footprint during one search and the clearance it is kept by there ({@link Detour#room} from the start to the goal). */
    private record Footing(Detour.Box box, double y, boolean solid, double clear) {}
    /** A blocker planned round, solid until {@code until}; {@code asked}: this round's give-way step is done. */
    private record Blocker(Obstacle obstacle, long until, boolean asked) {}
    /** The last stall sample, where the last step was aimed, the surface approach's exact goal, and the goal of a search running. */
    private Vec3 sample, aimed, goal, planGoal;
    private Vec3 observedGround; // Crossing observation only; never drives movement.
    /** The length of the way round to where the last step was aimed. */
    private double way;
    private long sampleTick, turnId, turnTick = -1000, steerTick = -1000, stallTick = -1000, goalTick = -1000, scanTick = -1000, yieldUntil;
    private int blockedWindows, turnSide, replans, solidPlanned;
    private final Map<Long, Blocker> blockers = new LinkedHashMap<>();
    private List<Footing> planning = List.of();
    private List<Entity> scan = List.of();
    /** Assigned by the parent constructor's createPathFinder call: no field initializer. */
    private PathFinder planner;
    private record Query(int accuracy, int width, int height, AABB obstacle, Vec3 approach) {}
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
    private BlockPos surveyStart;
    private int followingNode=-1;
    private boolean closingCorner;
    private float searchMultiplier = 1;

    YukkuriGroundNavigation(YukkuriEntity animal, Level level) { super(animal, level); }

    /** A body standing on solid ground may plan even when vanilla's ground flag is clear: an action that moved it without gravity (a grab, a
     *  carry) leaves the flag false until the next physics tick, and a walk planned in that tick was refused as having no route. */
    @Override protected boolean canUpdatePath() {
        return super.canUpdatePath() || mob instanceof YukkuriEntity body && SurfaceApproach.stands(body, body.position());
    }

    @Override protected Path createPath(Set<BlockPos> targets, int margin, boolean above, int accuracy) {
        return createPath(targets, margin, above, accuracy, TravelRange.PATH_RANGE);
    }

    /** Planning must not change the installed route's target or reset its stuck clocks. Vanilla createPath does both. */
    @Override protected Path createPath(Set<BlockPos> targets, int margin, boolean above, int accuracy, float range) {
        if(targets.isEmpty()||surveyStart==null&&(mob.getY()<level.getMinBuildHeight()||!canUpdatePath()))return null;
        if(surveyStart==null&&!forceSearch&&planningObstacle==null&&path!=null&&!path.isDone()&&path.canReach()
                &&walkingQuery!=null&&walkingQuery.obstacle()==null&&walkingQuery.approach()==null
                &&targets.contains(path.getTarget())&&fits(walkingQuery)&&accuracy>=walkingQuery.accuracy())return path;
        long started=System.nanoTime();
        level.getProfiler().push("pathfind");
        Path result;
        try {
            var centre=surveyStart!=null?surveyStart:above?mob.blockPosition().above():mob.blockPosition();
            int span=(int)(range+margin);
            var region=new PathNavigationRegion(level,centre.offset(-span,-span,-span),centre.offset(span,span,span));
            planning=surveyStart==null?planned(targets):List.of();
            result=planner.findPath(region,mob,targets,range,accuracy,searchMultiplier);
        } finally { planning=List.of();level.getProfiler().pop(); }
        if(result!=null){
            if(queries.size()>=128)queries.clear();
            queries.put(result,query(accuracy));
        }
        long millis=(System.nanoTime()-started)/1_000_000;
        if(millis>=50)GameplayLog.limited(mob,"move","path_slow",200,
                "slow ground path search sampleTarget={} candidates={} accuracy={} elapsedMs={} reached={} nodes={} solid={}",
                targets.iterator().next(),targets.size(),accuracy,millis,result!=null&&result.canReach(),result==null?0:result.getNodeCount(),solidPlanned);
        return result;
    }

    /** Movement readiness is separate from a failed search; this does not calculate a path. */
    public boolean canPlanApproach(){return canUpdatePath();}

    /** A whole route between loaded travel sites, with room to go around terrain beyond the local path range. */
    public Path createTravelPath(Set<BlockPos> targets) {
        return travelPath(mob.blockPosition(), targets);
    }

    /** Survey terrain with this body's abilities from a known standing cell. */
    public Path surveyTravelPath(BlockPos from, Set<BlockPos> targets) {
        surveyStart = from;
        try { return travelPath(from, targets); }
        finally { surveyStart = null; }
    }

    private Path travelPath(BlockPos from, Set<BlockPos> targets) {
        float range = TravelRange.PATH_RANGE;
        for (var target : targets) range = Math.max(range, (float)Math.sqrt(target.distSqr(from)) + 16);
        return createPath(targets, 8, false, 0, range);
    }

    /** Whole vanilla routes to physically usable last-step nodes, with the interaction partner treated as an obstacle; footprints are kept clear as far as {@code goal} allows. */
    public Path createApproachPath(Set<BlockPos> targets,AABB obstacle,Vec3 goal){
        var previous=planningObstacle;boolean forced=forceSearch;var end=planGoal;
        planningObstacle=obstacle;forceSearch=true;planGoal=goal;
        try{return createPath(targets,8,false,0);}
        finally{planningObstacle=previous;forceSearch=forced;planGoal=end;}
    }

    /** Solid footprints the last search kept clear of (a NO_PATH reason). */
    public int solidPlanned(){return solidPlanned;}

    private Query query(int accuracy){
        return new Query(accuracy,Mth.floor(mob.getBbWidth()+1),Mth.floor(mob.getBbHeight()+1),planningObstacle,planGoal);
    }
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
        var replacement = rebuildPath(walkingTarget);
        timeLastRecompute=level.getGameTime();hasDelayedRecomputation=false;
        moveTo(replacement,speedModifier);
    }

    /** Preserve the exact approach and its partner when replacing an installed route. */
    private Path rebuildPath(BlockPos target) {
        var query = walkingQuery;
        forceSearch = true;
        planningObstacle = query == null ? null : query.obstacle();
        planGoal = query == null ? null : query.approach();
        try { return createPath(target, query == null ? 0 : query.accuracy()); }
        finally { forceSearch = false; planningObstacle = null; planGoal = null; }
    }

    @Override protected void doStuckDetection(Vec3 at){
        // This hook runs after vanilla advances, but before it samples node progress and timeout.
        String held=null;
        if(path!=null&&!path.isDone()&&followingNode>=0&&path.getNextNodeIndex()>followingNode&&walkingQuery!=null){
            var next = path.getNextEntityPos(mob);
            held=crossesObstacle(mob.position(),next,walkingQuery.obstacle())?"body"
                    :mob instanceof YukkuriEntity actor&&NestDoorway.blocksLine(actor,mob.position(),next)?"doorway":null;
            if (held == null && walkingQuery.approach() != null && finishesLevelCorner(path.getEntityPosAtNode(mob, followingNode), next)) {
                held = "corner";
                closingCorner = true;
            }
            if(held!=null)path.setNextNodeIndex(followingNode);
        }
        boolean walking=!isDone();
        var target=walkingTarget;
        // What the walk was heading for when it gives up (the path is gone once vanilla stops it).
        var node=walking?path.getNextNodePos():null;
        String route=walking?path.getNextNodeIndex()+"/"+path.getNodeCount():null;
        if(mob instanceof YukkuriEntity crawler&&crawler.isCrawling()){crawlStuckDetection(crawler,at,walking,target);return;}
        crawlCheckPos=null;
        super.doStuckDetection(at);
        if(walking&&isDone()){
            stalled=true;
            GameplayLog.limited(mob,"move","path_stalled",200,"ground path stopped reason=no_progress target={} at={} node={} index={} held={} nodeBlock={} nodeBelow={} nodeFluid={} collided={} speed={} onGround={} motor={}",
                    target,at,node,route,held,node==null?null:level.getBlockState(node).getBlock(),node==null?null:level.getBlockState(node.below()).getBlock(),node==null?null:level.getFluidState(node).getType(),
                    mob.horizontalCollision,mob.getSpeed(),mob.onGround(),mob instanceof YukkuriEntity body?body.motor().current():null);
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
            double moved=at.distanceTo(crawlCheckPos),expected=crawler.crawlSpeed()*(now-crawlCheckTick);
            stop();stalled=true;
            GameplayLog.limited(mob,"move","path_stalled",200,"ground path stopped reason=no_progress gait=crawl target={} at={} moved={} expected={}",
                    target,at,String.format(Locale.ROOT,"%.3f",moved),String.format(Locale.ROOT,"%.3f",expected));
        }
        crawlCheckTick=now;crawlCheckPos=at;
    }

    @Override protected void followThePath(){
        followingNode=path.getNextNodeIndex();
        try{super.followThePath();}finally{followingNode=-1;}
    }

    /** A shortcut clips a level corner that the two planned legs can clear. */
    private boolean finishesLevelCorner(Vec3 node, Vec3 next) {
        var from = mob.position();
        double sole = getGroundY(node);
        if (!mob.onGround() || Math.abs(from.y - sole) > 1.0E-5 || Math.abs(sole - getGroundY(next)) > 1.0E-5) return false;
        node = new Vec3(node.x, from.y, node.z);
        next = new Vec3(next.x, from.y, next.z);
        return blocksLevelStep(from, next) && !blocksLevelStep(from, node) && !blocksLevelStep(node, next);
    }

    /** Sweep the actual collision box along a flat segment, including the sides of full blocks. */
    private boolean blocksLevelStep(Vec3 from, Vec3 to) {
        var body = mob.getBoundingBox().move(from.subtract(mob.position())).deflate(1.0E-7);
        double half = body.getXsize() * .5;
        for (var shape : level.getBlockCollisions(mob, body.expandTowards(to.subtract(from)))) {
            for (var box : shape.toAabbs()) {
                var blocked = new AABB(box.minX - half, box.minY - mob.getBbHeight() + 1.0E-7, box.minZ - half,
                        box.maxX + half, box.maxY - 1.0E-7, box.maxZ + half);
                if (blocked.contains(from) || blocked.contains(to) || blocked.clip(from, to).isPresent()) return true;
            }
        }
        return false;
    }

    private boolean crossesObstacle(Vec3 from,Vec3 to,AABB obstacle){
        return obstacle!=null&&mob instanceof YukkuriEntity animal&&SurfaceApproach.crossesBody(animal,from,to,obstacle);
    }

    @Override protected PathFinder createPathFinder(int nodes) {
        nodeEvaluator = new WalkNodeEvaluator() {
            private boolean keepDry, keepHome;
            private List<BlockPos> avoidedPit;
            private final Long2ObjectOpenHashMap<PathType> pathTypes = new Long2ObjectOpenHashMap<>();
            @Override public void prepare(PathNavigationRegion region, Mob mob) {
                // PathFinder can return before done() if its start node is missing.
                pathTypes.clear();
                super.prepare(region, mob);
                keepDry = mob instanceof YukkuriEntity animal && Reactions.Weather.keepDry(animal);
                keepHome = mob instanceof YukkuriEntity animal && TravelRange.keepBabyHome(animal);
                setCanPassDoors(!(mob instanceof YukkuriEntity animal && TravelRange.staysBehindDoors(animal)));
                avoidedPit = mob instanceof YukkuriEntity animal ? GroupPits.avoidedCells(animal) : List.of();
            }
            @Override public void done() {
                pathTypes.clear();
                super.done();
            }
            @Override public Node getStart(){
                if(surveyStart!=null)return getStartNode(surveyStart);
                if(mob instanceof YukkuriEntity actor){
                    var bed=NestDoorway.at(actor,actor.position());
                    if(bed==null)bed=NestDoorway.doorstep(actor);
                    var door=bed==null?null:NestDoorway.stand(actor,bed);
                    if(door!=null){
                        // The node on the stand's side of the opening: rounded outward along the entrance axis.
                        var out=NestDoorway.shape(bed).facing();
                        int x=out.getAxis()==Direction.Axis.X?(out.getStepX()>0?Mth.ceil(door.x-entityWidth*.5):Mth.floor(door.x-entityWidth*.5))
                                :Mth.floor(door.x-entityWidth*.5+.5);
                        int z=out.getAxis()==Direction.Axis.Z?(out.getStepZ()>0?Mth.ceil(door.z-entityDepth*.5):Mth.floor(door.z-entityDepth*.5))
                                :Mth.floor(door.z-entityDepth*.5+.5);
                        return getStartNode(new BlockPos(x,Mth.floor(door.y+.5),z));
                    }
                }
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
                long key = BlockPos.asLong(x, y, z);
                var type = pathTypes.get(key);
                if (type != null) return type;
                type = pathType(x, y, z);
                pathTypes.put(key, type);
                return type;
            }

            private PathType pathType(int x, int y, int z) {
                PathType type = super.getCachedPathType(x, y, z);
                var feet=new Vec3(x+entityWidth*.5,y,z+entityDepth*.5);
                if(mob instanceof YukkuriEntity actor&&NestDoorway.blocksLine(actor,feet,feet))return PathType.FENCE;
                if(planningObstacle!=null&&mob.getDimensions(mob.getPose()).makeBoundingBox(
                        x+entityWidth*.5,y,z+entityDepth*.5).inflate(.02,0,.02).intersects(planningObstacle))return PathType.BLOCKED;
                // A wide mob's node covers several cells, with its centre at node + width/2, not node + .5: a foot there over a
                // solid footprint (a hat's brim, a blocker) is blocked, over a standing body it costs as a danger.
                if(!planning.isEmpty()&&type!=PathType.OPEN&&mob.getPathfindingMalus(type)>=0){
                    double cx=x+entityWidth*.5,cz=z+entityDepth*.5;
                    boolean costly=false;
                    for(var o:planning)if(o.y()>=y-.5&&o.y()<y+1&&o.box().gap(cx,cz)<o.clear()){
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
                var from=new Vec3(node.x+entityWidth*.5,node.y,node.z+entityDepth*.5);
                // An actor already in the dip can walk across its floor to an exit; a route outside cannot enter.
                boolean outsidePit = !avoidedPit.isEmpty() && !GroupPits.inCells(avoidedPit, BlockPos.containing(from));
                int kept=0;
                for(int i=0;i<count;i++){
                    var next=neighbors[i];
                    var to=new Vec3(next.x+entityWidth*.5,next.y,next.z+entityDepth*.5);
                    if((!outsidePit || !GroupPits.inCells(avoidedPit, BlockPos.containing(to)))
                            &&!crossesObstacle(from,to,planningObstacle)
                            &&!(mob instanceof YukkuriEntity actor&&(NestDoorway.blocksLine(actor,from,to)
                                    ||!SurfaceApproach.dryStep(actor,from,to))))
                        neighbors[kept++]=next;
                }
                return kept;
            }
        };
        nodeEvaluator.setCanPassDoors(true);
        return planner=new PathFinder(nodeEvaluator, Math.max(nodes, TravelRange.PATH_RANGE * 16));
    }

    @Override public void tick() {
        if(mob instanceof YukkuriEntity actor){
            if (passage != null) tickPassage(actor);
            if(observedGround!=null&&actor.onGround()&&!actor.isPassenger())
                NestDoorway.observe(actor,observedGround,actor.position(),level.getGameTime()-steerTick<=1?aimed:null);
            observedGround=actor.onGround()&&!actor.isPassenger()?actor.position():null;
            if(!isDone()&&exitBed(speedModifier)){
                ++tick;doStuckDetection(actor.position());
                return;
            }
        }
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
        closingCorner = false;
        super.tick();
        // The route knew the footprints as they were when it was planned: each step to the next node heads round them now,
        // where the way round stands on supported, open ground (never off a ledge or into a wall).
        var control = mob.getMoveControl();
        if (!isDone() && control.hasWanted() && mob instanceof YukkuriEntity walker) {
            var wanted = new Vec3(control.getWantedX(), control.getWantedY(), control.getWantedZ());
            var aim = aim(wanted, true);
            boolean align = closingCorner && aim == wanted;
            if (aim != null && aim != wanted && !NestDoorway.blocksLine(walker,walker.position(),aim)
                    && SurfaceApproach.supported(walker, aim)) {
                control.setWantedPosition(aim.x, aim.y, aim.z, control.getSpeedModifier());
                wanted = aim;
            }
            // A route's nodes are dry, but the straight step to one can still cross a corner of water: the step stops at the land.
            var dry = SurfaceApproach.dryAim(walker, wanted);
            // A body perched on a brink (a drinker at the bank) may find every direct step toward the node putting more of it over
            // the water: it first centres on its own cell, which only lessens the water under it, and then goes on along the shore.
            if (dry.distanceToSqr(walker.position()) < 1.0E-8) dry = SurfaceApproach.dryAim(walker, new Vec3(walker.getBlockX() + .5, walker.getY(), walker.getBlockZ() + .5));
            if (dry.distanceToSqr(wanted) > 1.0E-8) control.setWantedPosition(dry.x, dry.y, dry.z, control.getSpeedModifier());
            else if (align && SurfaceApproach.clearLastStep(walker, wanted))
                step(wanted, control.getSpeedModifier());
        }
    }

    /** Every route leaves the bed before vanilla follows its first outside node. Direct goal walks use the same exit. */
    public boolean exitBed(double speed) {
        var actor=(YukkuriEntity)mob;var bed=NestDoorway.at(actor,actor.position());
        boolean inside=bed!=null;
        if(bed==null)bed=NestDoorway.doorstep(actor);
        if(bed==null)return false;
        var door=NestDoorway.stand(actor,bed);
        if(door==null){stop();GameplayLog.limited(actor,"home","exit_blocked",200,"nest exit stopped reason=doorway_unreachable nest={}",bed.getBlockPos());return true;}
        if(SurfaceApproach.at(actor,door))return false;
        step(inside&&Math.abs(actor.getX()-door.x)>SurfaceApproach.ARRIVAL?NestDoorway.inner(actor,bed,door):door,speed);
        return true;
    }

    /** A fixed straight step, outside the block route; retains the same walk's progress/watchdog. */
    public boolean step(Vec3 to,double speed) {
        steerTick=level.getGameTime();aimed=to;way=mob.position().distanceTo(to);
        return Motor.step((YukkuriEntity)mob,to,speed);
    }

    /** The footprint of a placed hat (its brim on its yaw, solid) or a free body (its box). */
    private static Obstacle footprint(Entity e) {
        if (e instanceof PlacedHatEntity hat) {
            float[] brim = hat.profile().brim();
            double unit = hat.unit(), turn = hat.turn();
            return new Obstacle(e, new Detour.Box(e.getId(), e.getX(), e.getZ(), brim[0] * unit, brim[1] * unit, Math.cos(turn), -Math.sin(turn)), e.getY(), true);
        }
        var box = e.getBoundingBox();
        return new Obstacle(e, Detour.Box.aligned(e.getId(), box.minX, box.minZ, box.maxX, box.maxZ), e.getY(), false);
    }

    /** A hat or body a walker can meet: on the ground (not carried, not aboard), not what it rides, within a block of height {@code y} (NaN: any). */
    private static boolean meets(YukkuriEntity walker, Entity e, double y) {
        if (e instanceof PlacedHatEntity hat && !hat.blocksWalking(y)) return false;
        return (e instanceof PlacedHatEntity || e instanceof YukkuriEntity) && e != walker && e.isAlive() && !e.isPassenger()
                && walker.getVehicle() != e && !(Math.abs(e.getY() - y) >= 1);
    }

    /** Placed hats and, unless {@code hatsOnly}, other free bodies in {@code area}. */
    public static List<Obstacle> obstacles(YukkuriEntity walker, AABB area, double y, boolean hatsOnly) {
        var out = new ArrayList<Obstacle>();
        for (var e : walker.level().getEntities(walker, area.inflate(1), x -> x instanceof PlacedHatEntity || !hatsOnly && x instanceof YukkuriEntity))
            if (meets(walker, e, y)) out.add(footprint(e));
        return out;
    }

    /** The hats and bodies round this walker, scanned once every SCAN_TICKS ticks; their footprints are read live. */
    private List<Entity> near() {
        long now = level.getGameTime();
        if (now - scanTick >= SCAN_TICKS) {
            scanTick = now;
            scan = level.getEntities(mob, mob.getBoundingBox().inflate(SCAN), x -> x instanceof PlacedHatEntity || x instanceof YukkuriEntity);
        }
        return scan;
    }

    /** A body walking now (a route or a steer in hand, or carried along at speed): met by collision and give-way, not steered round. */
    private static boolean moving(Entity e) {
        return e.getDeltaMovement().horizontalDistanceSqr() > 1e-4 || e instanceof Mob m && m.getNavigation() instanceof YukkuriGroundNavigation g && g.walking();
    }

    /** Walking now: a route or a direct steer in hand, and not holding still to give way. */
    public boolean walking() {
        long now = level.getGameTime();
        return now >= yieldUntil && (path != null && !path.isDone() || now - steerTick <= 2);
    }

    /** The surface approach names the exact point its walk ends at: what the walk's contact partner is judged by. */
    public void heading(Vec3 at) { goal = at; goalTick = level.getGameTime(); }

    /** The point the walk ends at: the surface approach's goal, else the route's target, else where this step heads. */
    private Vec3 goal(Vec3 to) {
        return level.getGameTime() - goalTick <= 2 ? goal : path != null && !path.isDone() ? Vec3.atBottomCenterOf(path.getTarget()) : to;
    }

    /** What the walk is about: a body (never a hat) within contact range of its goal is the one the goal belongs to (fed, cared for, met, fought, carried). */
    private static boolean partner(Obstacle o, Vec3 goal, double clear) { return !o.solid() && o.box().gap(goal.x, goal.z) < clear + CONTACT; }

    /** What a search plans round: hats, standing bodies but the goal's partner, and the blockers it was held up by lately (solid). */
    private List<Footing> planned(Set<BlockPos> targets) {
        solidPlanned = 0;
        if (!(mob instanceof YukkuriEntity walker)) return List.of();
        var area = new AABB(mob.blockPosition());
        for (var target : targets) area = area.minmax(new AABB(target));
        if (area.getXsize() > PLAN_SPAN || area.getZsize() > PLAN_SPAN) area = mob.getBoundingBox().inflate(PLAN_SPAN / 2.0);
        Vec3 at = mob.position(), end = planGoal != null ? planGoal : Vec3.atBottomCenterOf(targets.iterator().next());
        double clear = Occupancy.foot(mob.getBbWidth());
        var out = new ArrayList<Footing>();
        for (var o : obstacles(walker, area.inflate(2), Double.NaN, false))
            if (o.solid() || !moving(o.entity()) && !partner(o, end, clear)) footing(out, o, o.solid(), at, end, clear);
        long now = level.getGameTime();
        blockers.values().removeIf(s -> s.until() <= now);
        for (var s : blockers.values()) footing(out, s.obstacle(), true, at, end, clear);
        return out;
    }

    /** Kept clear by no more than the start and the goal leave it ({@link Detour#room}): a walker pressed against it is not sealed in and a
     *  stand beside it is reached; one the start or the goal lies inside does not count (the hat a walk boards, a blocker it overlaps). */
    private void footing(List<Footing> out, Obstacle o, boolean solid, Vec3 at, Vec3 end, double clear) {
        double room = Detour.room(o.box(), at.x, at.z, end.x, end.z, clear);
        if (room < 0) return;
        out.add(new Footing(o.box(), o.y(), solid, room));
        if (solid) solidPlanned++;
    }

    /** Where to head now on the way to {@code to}: past the first footprint the straight line crosses, on the side kept ({@link Detour});
     *  null when no way round is in sight (a planned route must find one). {@code moving}: the step is being taken (it keeps the side,
     *  holds still while giving way and counts for the stall watch); otherwise only a look, with {@code to} as the goal. */
    public Vec3 aim(Vec3 to, boolean moving) {
        if (!(mob instanceof YukkuriEntity walker)) return to;
        var at = mob.position();
        long now = level.getGameTime();
        if (moving) {
            steerTick = now; aimed = to; way = at.distanceTo(to);
            if (now < yieldUntil) return at;
        }
        double clear = Occupancy.foot(mob.getBbWidth());
        var end = moving ? goal(to) : to;
        var boxes = new ArrayList<Detour.Box>();
        for (var e : near()) {
            if (!meets(walker, e, at.y)) continue;
            var o = footprint(e);
            if (o.solid() || !moving(e) && !partner(o, end, clear)) boxes.add(o.box());
        }
        if (boxes.isEmpty()) return to;
        var turn = Detour.steer(at.x, at.z, to.x, to.z, clear, boxes, turnId, now - turnTick <= KEEP_TICKS ? turnSide : 0);
        if (turn.shut()) return null;
        if (turn.side() == 0) return to;
        if (moving) {
            way = turn.way();
            if ((turn.id() != turnId || turn.side() != turnSide) && level.getEntity((int) turn.id()) instanceof Entity e)
                GameplayLog.limited(mob, "move", "detour", 100, "walking round {} {} #{} side={} at={} to={}", kind(e),
                        e.getUUID(), e.getId(), turn.side(), GameplayLog.at(at), GameplayLog.at(to));
            turnId = turn.id(); turnSide = turn.side(); turnTick = now;
        }
        return new Vec3(turn.x(), to.y, turn.z());
    }

    /** A direct steer stalled against a footprint just now: the surface approach then plans a route round it. */
    public boolean takeStall() { boolean recent = level.getGameTime() - stallTick <= 2; stallTick = -1000; return recent; }

    /** The way left to {@code to} by the turn just taken (a walk's honest metric while it steers round a footprint), or NaN when it is not being steered there. */
    public double way(Vec3 to) { return aimed != null && aimed.distanceToSqr(to) < 1e-6 && level.getGameTime() - steerTick <= 2 ? way : Double.NaN; }

    /** How many times a stall made this walker plan round a blocker: a walk that sees it grow restarts its watchdog window. */
    public int replans() { return replans; }

    private static String kind(Entity e) { return e instanceof PlacedHatEntity ? "hat" : "yukkuri"; }

    private void resetBlock() { sample = null; blockedWindows = 0; }

    /**
     * Samples progress every WINDOW ticks while walking (a path or a direct steer); only a walker held up by a footprint ahead
     * escalates, so nothing runs per tick. Against a body walking into it, the higher id keeps its line and the lower one gives
     * way; otherwise the round is: plan round it (solid SOLID_TICKS), give way once (hold still a window before a walking body,
     * ask an idle one to move), wait, and plan again once the mark lapses.
     */
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
        if (now < yieldUntil || moved >= (animal.isCrawling() ? .02 : .12) || aimed == null || animal.position().distanceTo(aimed) < ARRIVING) {
            blockedWindows = 0;
            return;
        }
        Vec3 heading = new Vec3(aimed.x - animal.getX(), 0, aimed.z - animal.getZ()).normalize();
        var blocker = blocker(animal, heading, now);
        if (blocker == null) { blockedWindows = 0; return; }
        int stage = ++blockedWindows;
        var other = blocker.entity();
        long id = blocker.box().id();
        var round = blockers.get(id);
        boolean oncoming = moving(other);
        String action;
        if (oncoming && animal.getId() > other.getId()) action = "keeps_line";
        else if (round == null || round.until() <= now) action = replan(blocker, now) ? "replanned" : "no_alternative";
        else if (round.asked()) action = "waiting";
        else {
            blockers.put(id, new Blocker(round.obstacle(), round.until(), true));
            if (oncoming) { yieldUntil = now + WINDOW; stepAside(animal, other, heading); action = "gives_way"; }
            else if (other instanceof YukkuriEntity body) action = yieldRequest(animal, body, heading) ? "blocker_yields" : "blocker_busy";
            else action = "waiting";
        }
        GameplayLog.limited(animal, "move", "body_block_" + action, 100,
                "blocked by {} {} #{} action={} window={} to={}", kind(other), other.getUUID(), other.getId(), action, stage, GameplayLog.at(aimed));
    }

    /** What holds the walker up: the footprint it was turning round, if still touching, else the nearest touching one ahead; never a body it is meeting. */
    private Obstacle blocker(YukkuriEntity animal, Vec3 heading, long now) {
        double clear = Occupancy.foot(animal.getBbWidth()), nearest = Double.MAX_VALUE;
        Obstacle best = null;
        for (var e : near()) {
            if (!meets(animal, e, animal.getY()) || e instanceof YukkuriEntity other && animal.careApproach().cooperatingWith(other)) continue;
            var o = footprint(e);
            var b = o.box();
            double gap = b.gap(animal.getX(), animal.getZ());
            boolean ahead = gap < 0 || b.id() == turnId && now - turnTick <= WINDOW
                    || (b.x() - animal.getX()) * heading.x + (b.z() - animal.getZ()) * heading.z > 0;
            if (gap <= clear + .1 && ahead && gap < nearest) { best = o; nearest = gap; }
        }
        return best;
    }

    /** Marks the blocker solid for a while and plans round it: false when no other route reaches. A direct steer leaves the route to the surface approach. */
    private boolean replan(Obstacle blocker, long now) {
        blockers.values().removeIf(s -> s.until() <= now);
        if (blockers.size() >= SOLID_MAX) return false;
        blockers.put(blocker.box().id(), new Blocker(new Obstacle(blocker.entity(), blocker.box(), blocker.y(), true), now + SOLID_TICKS, false));
        if (path == null || path.isDone()) { stallTick = now; replans++; return true; }
        BlockPos target = getTargetPos();
        if (target == null) return false;
        var detour = rebuildPath(target);
        if (detour == null || !detour.canReach() || detour.sameAs(path) || !moveTo(detour, speedModifier)) return false;
        replans++;
        return true;
    }

    private static Vec3 right(Vec3 heading) { return new Vec3(-heading.z, 0, heading.x); }
    private static final double YIELD_PACE = .16;
    private static final int YIELD_NOTICE = 3, YIELD_LIMIT = 14;
    private static final double PASSAGE_RANGE = 1.75;
    private record PlayerPosition(Level level, Vec3 position) {}
    private static final Map<ServerPlayer, PlayerPosition> PLAYER_POSITIONS = new WeakHashMap<>();
    private static final class Passage {
        final UUID walker;
        final Vec3 stand;
        final long began;
        final boolean mayInterruptWalk;
        boolean stepped, done;

        Passage(UUID walker, Vec3 stand, long began, boolean mayInterruptWalk) {
            this.walker = walker;
            this.stand = stand;
            this.began = began;
            this.mayInterruptWalk = mayInterruptWalk;
            this.done = stand == null;
        }
    }
    private Passage passage;

    /**
     * Giving way is a step aside, not only standing still: two bodies that meet on a one-cell shore each stop on the other's line,
     * the one that keeps its line pushes into a body that only waits, and neither ever gets past (they stood there until they
     * starved). {@code body} takes one short pace off {@code other}'s line, on the side away from it; the motor makes the move, so
     * vanilla resolves the collision instead of a body being put into a wall. True when it really moved.
     */
    private static boolean stepAside(YukkuriEntity body, Entity other, Vec3 heading) {
        Vec3 side = right(heading);
        double away = Math.signum((body.getX() - other.getX()) * side.x + (body.getZ() - other.getZ()) * side.z);
        if (away == 0) away = 1;
        return Motor.move(body, side.scale(away * YIELD_PACE), "give_way").lengthSqr() > 1e-6;
    }

    /** The server player moves via packets. Sample actual positions instead of using server velocity. */
    public static void playerPassage(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || (player.tickCount & 1) != 0) return;
        var before = PLAYER_POSITIONS.put(player, new PlayerPosition(player.level(), player.position()));
        if (before != null && before.level() == player.level() && !player.isSpectator() && !player.getAbilities().flying)
            noticePassage(player, before.position());
    }

    /** A moving player or resident asks the first idle body across their line to step aside. */
    public static void noticePassage(LivingEntity walker, Vec3 before) {
        if (!walker.onGround() || walker.isPassenger() || walker.isInWater() || walker.hurtTime > 0) return;
        var travel = walker.position().subtract(before).multiply(1, 0, 1);
        if (travel.lengthSqr() < .001 || travel.lengthSqr() > 1 || Math.abs(walker.getY() - before.y) > .5) return;
        var heading = travel.normalize();
        var area = walker.getBoundingBox().move(before.subtract(walker.position()))
                .expandTowards(walker.position().subtract(before).add(heading.scale(PASSAGE_RANGE))).inflate(.6, 0, .6);
        var blockers = walker.level().getEntitiesOfClass(YukkuriEntity.class, area, y -> {
            if (!idleForPassage(y) || !canGiveWay(y)) return false;
            var offset = y.position().subtract(walker.position());
            var temperament = y.life().personality();
            double notice = .55 + .3 * (100 - temperament.boldness() + temperament.friendliness()) / 100;
            return offset.dot(heading) >= -.1 && offset.dot(heading) <= notice
                    && Math.abs(offset.dot(right(heading))) < passingWidth(y, walker) && walker.hasLineOfSight(y);
        });
        blockers.sort(Comparator.comparingDouble(walker::distanceToSqr));
        for (var y : blockers) if (giveWay(y, walker, walker.position().add(heading), false)) return;
    }

    private static boolean idleForPassage(YukkuriEntity body) {
        return body.getNavigation().isDone() && !body.getMoveControl().hasWanted();
    }

    private static boolean canGiveWay(YukkuriEntity body) {
        return body.isAlive() && !body.isNoAi() && !body.isCommandPlaying() && !body.operations().active()
                && !body.life().isSleeping() && body.nudgeable() && !body.isPanicking() && !body.pet().busy()
                && !body.life().protectsStep() && body.motor().current() == null;
    }

    private static double passingWidth(YukkuriEntity body, Entity walker) {
        return (walker.getBbWidth() + body.getBbWidth()) / 2 + .1;
    }

    /** Warn and take one short, bounded sidestep. The same encounter cannot restart until the walker leaves. */
    public static boolean giveWay(YukkuriEntity blocker, Entity walker, Vec3 toward) {
        return giveWay(blocker, walker, toward, true);
    }

    private static boolean giveWay(YukkuriEntity blocker, Entity walker, Vec3 toward, boolean mayInterruptWalk) {
        if (!(blocker.directNavigation() instanceof YukkuriGroundNavigation nav) || !(blocker.level() instanceof ServerLevel)
                || !canGiveWay(blocker) || blocker.distanceToSqr(walker) > PASSAGE_RANGE * PASSAGE_RANGE) return false;
        if (nav.passage != null) return !nav.passage.done && nav.passage.walker.equals(walker.getUUID());
        var line = toward.subtract(walker.position()).multiply(1, 0, 1);
        if (line.lengthSqr() < 1e-6) return false;
        var side = right(line.normalize());
        var offset = blocker.position().subtract(walker.position());
        if (offset.dot(side) < 0) side = side.reverse();
        double clearance = passingWidth(blocker, walker);
        if (offset.dot(side) >= clearance) return false;
        for (var direction : List.of(side, side.reverse())) {
            double distance = clearance - offset.dot(direction);
            var stand = blocker.position().add(direction.scale(distance));
            if (!clearYieldStand(blocker, stand)) continue;
            blocker.getLookControl().setLookAt(walker, 30, 30);
            if (!blocker.hasReaction()) blocker.showReaction("surprise", "", 12);
            nav.passage = new Passage(walker.getUUID(), stand, blocker.level().getGameTime(), mayInterruptWalk);
            return true;
        }
        nav.passage = new Passage(walker.getUUID(), null, blocker.level().getGameTime(), mayInterruptWalk);
        return false;
    }

    private static boolean clearYieldStand(YukkuriEntity body, Vec3 stand) {
        var box = body.getBoundingBox().move(stand.subtract(body.position()));
        return SurfaceApproach.clearLastStep(body, stand) && body.level().noCollision(body, box)
                && body.level().getEntitiesOfClass(LivingEntity.class, box, other -> other != body && other.isAlive()
                        && !other.isSpectator()).isEmpty();
    }

    private void tickPassage(YukkuriEntity body) {
        if (!(level instanceof ServerLevel server)) return;
        var walker = server.getEntity(passage.walker);
        if (walker == null || !walker.isAlive() || body.distanceToSqr(walker) > PASSAGE_RANGE * PASSAGE_RANGE) {
            passage = null;
            return;
        }
        if (passage.done) return;
        long elapsed = level.getGameTime() - passage.began;
        if (!canGiveWay(body) || !passage.mayInterruptWalk && !idleForPassage(body) || elapsed > YIELD_LIMIT) {
            passage.done = true;
            return;
        }
        if (elapsed < YIELD_NOTICE || !body.onGround()) return;
        var delta = passage.stand.subtract(body.position()).multiply(1, 0, 1);
        if (delta.horizontalDistanceSqr() < .0025 || !clearYieldStand(body, passage.stand)) {
            passage.done = true;
            return;
        }
        SurfaceApproach.stopWalking(body);
        var step = delta.normalize().scale(Math.min(delta.length(), YIELD_PACE));
        if (Motor.move(body, step, "give_way").dot(step) < step.lengthSqr() * .5) {
            passage.done = true;
        } else if (!passage.stepped) {
            passage.stepped = true;
            GameplayLog.event(body, "move", "action=give_way walker={} walker_type={} stand={}",
                    walker.getUUID(), walker.getType(), GameplayLog.at(passage.stand));
        }
    }

    /** An idle, awake blocker moves out of the way; sleeping, busy or boarded ones keep their place. */
    private boolean yieldRequest(YukkuriEntity animal, YukkuriEntity blocker, Vec3 heading) {
        if (!blocker.getNavigation().isDone() || blocker.isCommandPlaying() || blocker.operations().active()
                || blocker.life().isSleeping() || !blocker.nudgeable()) return false;
        return stepAside(blocker, animal, heading);
    }
}
