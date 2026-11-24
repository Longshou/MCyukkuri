package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriGroundNavigation;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.motor.action.StepIn;
import io.github.longshou.mcyukkuri.motor.action.Swim;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.motor.geom.Detour;
import io.github.longshou.mcyukkuri.motor.geom.ContactStandSearch;
import io.github.longshou.mcyukkuri.motor.geom.FlightCourse;
import io.github.longshou.mcyukkuri.motor.geom.Gait;
import io.github.longshou.mcyukkuri.motor.surface.CollisionBoxes;
import io.github.longshou.mcyukkuri.status.Reactions;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Per-entity approach requests and supported last-step steering; navigation owns the actual path. */
public final class SurfaceApproach {
    /** Shared horizontal arrival tolerance (blocks); vertical slack allows the physical surface's last small step. */
    public static final double ARRIVAL = .02;
    /** Ticks between two {@code walk.sample} detail lines; the retry gate of a blocked request; how often a walk rechecks the hats round its goal. */
    private static final int SAMPLE_TICKS = 20, RETRY = 20, HATS_CHECK = 15;
    /** A refused contact-stand ask holds RETRY, then twice, then four times as long (20, 40, 80 ticks) while it stays unchanged:
     *  the world round a still pair rarely changes in a second or two, and the wait ends the tick the peer or this body moves. */
    private static final int CONTACT_HOLD_DOUBLINGS = 2;
    /** One second for a completed ground path to settle after a step or knockback. */
    private static final int LANDING_TICKS = 20;
    private static final double SAME_STAND_SQR = .0025;
    public static final double BODY_CLEARANCE = .015;
    /** How far past the probe a collision query reaches so a top exactly on the probe's edge is returned. */
    private static final double TOP_TOUCH = .001;
    /** How far under a rejected top the next lower top is looked for. */
    private static final double LOWER_TOP = .01;
    /** How many tops the fallback checks (this top and the lower ones under it): a bench board over the ground is one of them,
     *  while a rejection that repeats down the column (rain over a keepDry body, a wet shore) stops here instead of min build height. */
    private static final int LOWER_TOPS = 3;
    SurfaceApproach() {}
    private record Route(Path path,double length) {}
    public enum Result { ARRIVED, MOVING, WAITING, BLOCKED }
    /** Owned by this entity's life component. Navigation alone owns the path and its node progress. */
    private static final class Approach {
        final PathNavigation navigation;
        final UUID peer;
        final AABB body;
        final Vec3 destination;
        BlockPos target;
        /** Where it is walked to: the destination, or the nearest point just clear of a hat's brim it lies under. */
        Vec3 goal,lastStep;
        /** The hats round the destination when the goal was found (signature, and when last compared). */
        long retryAt,touched,hatsSig,hatsChecked,landBy;
        String why;
        /** The straight approach stalled against a footprint, or its way round is no clear step: a planned route until it is walked. */
        boolean routed;
        Result result;
        Approach(YukkuriEntity actor,Entity other,Vec3 destination){
            navigation=actor.getNavigation();peer=other==null?null:other.getUUID();
            body=other==null?null:other.getBoundingBox();this.destination=destination;
        }
    }
    private Approach current;
    private record FailedStep(Vec3 from, Vec3 destination, Vec3 node, Vec3 blockedStep, AABB body) {}
    private final List<FailedStep> failedSteps = new ArrayList<>();
    /** The last destination found to have no stand clear of a hat, and until when that answer holds. */
    private Vec3 noStand;
    private long noStandUntil;
    /** A contact-stand request that found no stand: the peer and reach it was asked for, the peer's body and this body's cell then. */
    private record NoContact(UUID peer, AABB body, BlockPos cell, double distance, double maxHeight, double maxDistance, long until, int refusals) {
        boolean sameAsk(Entity other, double distance, double maxHeight, double maxDistance) {
            return peer.equals(other.getUUID()) && this.distance == distance && this.maxHeight == maxHeight && this.maxDistance == maxDistance;
        }
        boolean unchanged(YukkuriEntity actor, Entity other) {
            return sameBody(body, other.getBoundingBox()) && cell.equals(actor.blockPosition());
        }
    }
    /** How many refused contact-stand asks a body remembers (a caller walking outward through rings asks several at once). */
    private static final int REFUSED_ASKS = 8;
    /** The refused contact-stand asks: the same ask answers the same until its hold ends, which grows with each refusal. */
    private final List<NoContact> noContact = new ArrayList<>();

    private static SurfaceApproach controller(YukkuriEntity actor){return actor.life().approach();}

    /** Observe the most recent executed request only; never probe or start a route from a status read. */
    public static Result lastResult(YukkuriEntity actor,Vec3 destination){
        var request=controller(actor).current;
        return request!=null&&request.navigation==actor.getNavigation()&&actor.level().getGameTime()-request.touched<=2
                &&request.destination.distanceToSqr(destination)<.0025?request.result:null;
    }

    Vec3 walkingStand(YukkuriEntity actor) {
        return current != null && current.peer == null && lastResult(actor, current.destination) == Result.MOVING
                && current.goal.distanceToSqr(current.destination) < ARRIVAL*ARRIVAL ? current.goal : null;
    }

    /** Resolve the real sole height on a low nest or adjacent floor, retaining its collision shell. */
    public static Vec3 floor(YukkuriEntity actor, Vec3 near) {
        return floor(actor,near,false);
    }
    /** A planned rest target ignores temporary occupants; walking still checks real collisions. */
    public static Vec3 restingFloor(YukkuriEntity actor, Vec3 near) {
        return floor(actor,near,true);
    }

    private static double safeDrop(YukkuriEntity actor) {
        return FlightCourse.safeDrop(actor.getAttributeValue(Attributes.SAFE_FALL_DISTANCE),
                actor.growthScale() * actor.getScale(), actor.maxUpStep());
    }

    /** The first dry collider under the runner's centre, including slab and stair tops. */
    private static double dryLanding(YukkuriEntity actor, double x, double z, double top, double bottom) {
        var from = new Vec3(x, top + .025, z);
        var to = new Vec3(x, bottom - .025, z);
        var level = actor.level();
        if (!level.hasChunkAt(BlockPos.containing(from))) return Double.NEGATIVE_INFINITY;
        var hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, actor));
        if (hit.getType() == HitResult.Type.MISS) return Double.NEGATIVE_INFINITY;
        var pos = hit.getBlockPos();
        if (!level.getFluidState(pos).isEmpty()) {
            // A waterlogged block can still have a dry top above the fluid.
            var solid = level.getBlockState(pos).getCollisionShape(level, pos, CollisionContext.of(actor)).clip(from, to, pos);
            if (solid == null || solid.getLocation().y < hit.getLocation().y) return Double.NEGATIVE_INFINITY;
        }
        return hit.getLocation().y;
    }

    public static Vec3 retreatFloor(YukkuriEntity actor, Vec3 near) {
        if (!(actor.getNavigation() instanceof YukkuriGroundNavigation) || actor.isInLiquid()) return restingFloor(actor, near);
        double drop = safeDrop(actor);
        double y = dryLanding(actor, near.x, near.z, near.y + .5, near.y - drop);
        return near.y - y <= drop ? restingFloor(actor, new Vec3(near.x, y, near.z)) : null;
    }

    /** Survey each leg from the previous real sole height, even when the path finder rounds a node to a whole block. */
    private static double retreatLanding(YukkuriEntity actor, Vec3 from, Vec3 to, double drop) {
        double top = Math.max(from.y, to.y);
        return FlightCourse.landingAfter(from.x, from.y, from.z, to.x, to.z, drop,
                (x, z) -> dryLanding(actor, x, z, top, from.y - drop));
    }

    public static boolean canSteerOver(YukkuriEntity actor, Vec3 to) {
        var from = actor.position();
        double drop = safeDrop(actor);
        double landing = retreatLanding(actor, from, to, drop);
        return from.y + actor.fallDistance - landing <= drop;
    }

    public static boolean safeRetreatStep(YukkuriEntity actor, Vec3 from, Vec3 to) {
        return Double.isFinite(retreatLanding(actor, from, to, safeDrop(actor)));
    }

    /** Include the exact final step when the escape is a surface approach rather than a vanilla node target. */
    public static boolean safeRetreatPath(YukkuriEntity actor, Path path, Vec3 destination) {
        if (!(actor.getNavigation() instanceof YukkuriGroundNavigation) || actor.isInLiquid()) return true;
        var from = actor.position();
        double drop = safeDrop(actor);
        if (path != null) {
            for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
                var next = path.getEntityPosAtNode(actor, i);
                double y = retreatLanding(actor, from, next, drop);
                if (!Double.isFinite(y)) return false;
                from = new Vec3(next.x, y, next.z);
            }
        }
        return destination == null || Double.isFinite(retreatLanding(actor, from, destination, drop));
    }

    public static boolean startRetreat(YukkuriEntity actor, Path path, double speed) {
        var navigation = actor.getNavigation();
        return navigation instanceof YukkuriGroundNavigation ground
                ? ground.moveToRetreat(path, speed) : navigation.moveTo(path, speed);
    }

    /** Start a route with its goal, so a route cut short by a blocker is still known as the same walk. */
    private static boolean startRoute(YukkuriEntity actor, Path path, double speed, Vec3 destination) {
        var navigation = actor.getNavigation();
        return navigation instanceof YukkuriGroundNavigation ground
                ? ground.moveTo(path, speed, destination) : navigation.moveTo(path, speed);
    }
    /** The highest collision top under the body's square footprint within half a block of {@code near}, unchecked. */
    public static Vec3 surface(YukkuriEntity actor, Vec3 near) { return top(actor,near); }
    private static Vec3 top(YukkuriEntity actor, Vec3 near) {
        return top(actor.level(), actor, actor.getBbWidth(), near);
    }
    public static Vec3 top(LevelReader level, Entity actor, double width, Vec3 near) {
        if(!level.hasChunkAt(BlockPos.containing(near)))return null;
        return collisionTop(level, actor, width, near);
    }
    public static Vec3 collisionTop(CollisionGetter level, Entity actor, double width, Vec3 near) {
        double half=width*.5-.005, top=Double.NEGATIVE_INFINITY;
        var probe=new AABB(near.x-half,near.y-.5,near.z-half,near.x+half,near.y+.5,near.z+half);
        // A top exactly at the probe's floor (the ground half a block under a bench seat) only touches the probe and is not a
        // collision of it; the height test below admits it, so the query reaches that far too.
        for (var shape : level.getBlockCollisions(actor, probe.inflate(0, TOP_TOUCH, 0))) {
            var x = shape.getCoords(Direction.Axis.X);
            var y = shape.getCoords(Direction.Axis.Y);
            var z = shape.getCoords(Direction.Axis.Z);
            if (x.size() == 2 && y.size() == 2 && z.size() == 2) {
                double height = y.getDouble(1);
                if (height >= probe.minY && height <= probe.maxY
                        && x.getDouble(1) > probe.minX && x.getDouble(0) < probe.maxX
                        && z.getDouble(1) > probe.minZ && z.getDouble(0) < probe.maxZ) top = Math.max(top, height);
                continue;
            }
            for (var box : CollisionBoxes.of(shape)) {
                double height = y.getDouble(box.maxY());
                if (height <= top || height < probe.minY || height > probe.maxY) continue;
                if (x.getDouble(box.maxX()) > probe.minX && x.getDouble(box.minX()) < probe.maxX
                        && z.getDouble(box.maxZ()) > probe.minZ && z.getDouble(box.minZ()) < probe.maxZ) top = height;
            }
        }
        return Double.isFinite(top)?new Vec3(near.x,top,near.z):null;
    }

    /** The highest collision top near {@code near} the body can stand on. A higher top it cannot stand on (a bench board beside the
     *  ground it walks) does not hide the ground under it. */
    private static Vec3 floor(YukkuriEntity actor, Vec3 near, boolean planning) {
        var at=top(actor,near);
        for(int left=LOWER_TOPS;at!=null;left--){
            if(supported(actor,at,planning))return at;
            if(left==1)break;
            at=top(actor,new Vec3(near.x,at.y-.5-LOWER_TOP,near.z));
        }
        return null;
    }

    /** The same terrain stand check with a body size, for placement before any resident exists. */
    public static Vec3 restingFloor(LevelReader level, EntityDimensions body, Vec3 near) {
        var bounds = body.makeBoundingBox(near);
        if (!level.hasChunksAt(BlockPos.containing(bounds.minX, near.y, bounds.minZ),
                BlockPos.containing(bounds.maxX, near.y, bounds.maxZ))) return null;
        var at = top(level, null, body.width(), near);
        return at != null && supported(level, null, body.width(), body.makeBoundingBox(at), at, true) ? at : null;
    }

    public static boolean supported(YukkuriEntity actor, Vec3 feet) {
        return supported(actor,feet,false);
    }
    /** The stand itself, terrain only: what the body rests on, whatever the weather and whoever stands beside it. */
    public static boolean stands(YukkuriEntity actor, Vec3 feet) {
        return supported(actor,feet,true);
    }
    /** Dry stands on the actual collision tops of a block, including individual stair treads. */
    static void collectStands(YukkuriEntity actor, BlockPos block, List<Vec3> out) {
        var level = actor.level();
        var shape = level.getBlockState(block).getCollisionShape(level, block);
        if (shape.isEmpty()) return;
        var above = block.above();
        if (!level.getFluidState(above).isEmpty() || level.getBlockState(above).isCollisionShapeFullBlock(level, above)) return;
        StandFace.Probe<Vec3> probe = (x, y, z) -> {
            var feet = new Vec3(block.getX() + x, block.getY() + y, block.getZ() + z);
            return stands(actor, feet) ? feet : null;
        };
        var xs = shape.getCoords(Direction.Axis.X);
        var zs = shape.getCoords(Direction.Axis.Z);
        for (int i = 1; i < xs.size(); i++) for (int j = 1; j < zs.size(); j++) {
            double x0 = xs.getDouble(i - 1), x1 = xs.getDouble(i);
            double z0 = zs.getDouble(j - 1), z1 = zs.getDouble(j);
            // For the Y axis vanilla takes the horizontal coordinates in Z, X order.
            double y = shape.max(Direction.Axis.Y, (z0 + z1) / 2, (x0 + x1) / 2);
            if (!Double.isFinite(y)) continue;
            Vec3 at = StandFace.stand(x0, x1, y, z0, z1, actor.getBbWidth() / 2, probe);
            if (at != null) out.add(at);
        }
    }
    /** Terrain and weather only; carry planning checks occupants separately to report the actual blocker. */
    public static boolean supportedGround(YukkuriEntity actor, Vec3 feet) {
        return supported(actor,feet,true) && Reactions.Weather.allowsStep(actor,BlockPos.containing(feet));
    }
    /** Distance from the centre to the sole samples required by walking, including a bank-edge stand. */
    public static double supportRadius(YukkuriEntity actor) { return supportRadius(actor.getBbWidth()); }
    private static double supportRadius(double width) { return width * .42; }
    private static boolean supported(YukkuriEntity actor, Vec3 feet, boolean planning) {
        if (!planning && !Reactions.Weather.allowsStep(actor,BlockPos.containing(feet))) return false;
        var body = actor.getBoundingBox().move(feet.subtract(actor.position()));
        return supported(actor.level(), actor, actor.getBbWidth(), body, feet, planning);
    }
    private static boolean supported(LevelReader level, Entity actor, double width, AABB body, Vec3 feet, boolean planning) {
        if (!level.hasChunkAt(BlockPos.containing(feet))) return false;
        AABB clearance = body.deflate(.005);
        return StandFace.supports(feet.x, feet.z, supportRadius(width), wetArea(level, body, feet),
                () -> planning ? !level.getBlockCollisions(actor, clearance).iterator().hasNext() : level.noCollision(actor, clearance),
                (x, z) -> soleSupported(level, actor, x, feet.y, z));
    }

    private static boolean soleSupported(LevelReader level, Entity actor, double x, double y, double z) {
        var foot = new AABB(x-.015, y-.08, z-.015, x+.015, y+.005, z+.015);
        return level.getBlockCollisions(actor, foot).iterator().hasNext();
    }

    /** Near water, a set-down needs level support for one child width past the child's sole. */
    public static boolean hasDryBankBuffer(YukkuriEntity child, Vec3 feet, double drop) {
        double radius = child.getBbWidth() + supportRadius(child);
        var level = child.level();
        boolean nearWater = false;
        var min = BlockPos.containing(feet.x-radius, feet.y-drop, feet.z-radius);
        var max = BlockPos.containing(feet.x+radius, feet.y, feet.z+radius);
        for (var cell : BlockPos.betweenClosed(min, max)) {
            if (level.getFluidState(cell).isEmpty()) continue;
            if (cell.getY() == max.getY()) return false;
            nearWater = true;
        }
        if (!nearWater) return true;
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            if (!soleSupported(level, child, feet.x + x * radius, feet.y, feet.z + z * radius)) return false;
        }
        return true;
    }

    /**
     * Water area under the body's own footprint at {@code feet}: the water a land body cannot stand in, which the path finder
     * refuses as a step ({@code PathType.WATER} is impassable, {@link YukkuriEntity} sets the malus to -1). The whole box
     * counts, not only the cell under the feet: beside a bank level with the water the rim of a dry block is still water under
     * half the body. The overlap area distinguishes moving farther over one water cell from moving inland (see {@link #dryAim}).
     * {@code touching} includes the footprint's grazed edge, which is what a walker perched on a shore's boundary really
     * stands over (a stand itself uses the uninflated footprint).
     */
    private static double wetArea(YukkuriEntity actor, Vec3 feet, boolean touching) {
        var box = actor.getBoundingBox().move(feet.subtract(actor.position()));
        if (touching) box = box.inflate(.02);
        return wetArea(actor.level(), box, feet);
    }
    private static double wetArea(LevelReader level, AABB box, Vec3 feet) {
        double wet = 0;
        int sole = Mth.floor(feet.y);
        for (var cell : BlockPos.betweenClosed(BlockPos.containing(box.minX, feet.y - .005, box.minZ),
                BlockPos.containing(box.maxX - .005, feet.y, box.maxZ - .005)))
            if (!level.getFluidState(cell).isEmpty() && (cell.getY() == sole
                    || level.getBlockState(cell).getCollisionShape(level, cell).isEmpty()))
                wet += (Math.min(box.maxX, cell.getX() + 1) - Math.max(box.minX, cell.getX()))
                        * (Math.min(box.maxZ, cell.getZ() + 1) - Math.max(box.minZ, cell.getZ()));
        return wet;
    }

    /** The straight step to {@code to} never puts more of the body over water than it already is (sampled every .1 blocks). */
    public static boolean dryStep(YukkuriEntity actor, Vec3 from, Vec3 to) {
        // No fluid anywhere over the two footprints' hull: every sample is dry, so the step is dry.
        var level = actor.level();
        var shift = actor.getBoundingBox().move(from.subtract(actor.position()));
        var hull = shift.minmax(shift.move(to.subtract(from))).inflate(.02);
        boolean fluid = false;
        for (var cell : BlockPos.betweenClosed(BlockPos.containing(hull.minX, Math.min(from.y, to.y) - .005, hull.minZ),
                BlockPos.containing(hull.maxX, Math.max(from.y, to.y), hull.maxZ)))
            if (!level.getFluidState(cell).isEmpty()) { fluid = true; break; }
        if (!fluid) return true;
        return dryAim(actor, from, to).distanceToSqr(to) < 1.0E-8;
    }

    /** Where a walker may aim on its way to {@code to}: as far as the way there never puts more of its footprint over water than where it stands. A dry body therefore never steps onto water, while a body already standing on a shore level with the pond walks along it and out to the land — cutting the step at the water itself left it frozen where it stood until it starved. */
    public static Vec3 dryAim(YukkuriEntity actor, Vec3 to) { return dryAim(actor, actor.position(), to); }

    private static Vec3 dryAim(YukkuriEntity actor, Vec3 from, Vec3 to) {
        double start = wetArea(actor, from, true);
        int samples = Math.max(1, (int)Math.ceil(from.distanceTo(to) / .1));
        for (int i = 1; i <= samples; i++) {
            if (wetArea(actor, from.lerp(to, (double)i / samples), true) <= start + 1.0E-8) continue;
            return i == 1 ? from : from.lerp(to, (double)(i - 1) / samples);
        }
        return to;
    }

    public static boolean clearLastStep(YukkuriEntity actor, Vec3 destination) {
        return actor.onGround()&&clearLastStep(actor,actor.position(),destination);
    }

    /** Close a short, clear segment without circling it under the ground steering turn cap. */
    public static boolean tryStraightStep(YukkuriEntity actor, Vec3 destination, double speed) {
        if (!(actor.getNavigation() instanceof YukkuriGroundNavigation ground)
                || !clearLastStep(actor, destination) || ground.aim(destination, true) != destination) return false;
        ground.stop();
        ground.step(destination, speed);
        return true;
    }

    /** A terrain change can put a wall through the stance a walker loaded at. Step clear of that overlap on level ground. */
    public static boolean tryStepOutOfOverlap(YukkuriEntity actor, Vec3 destination, double speed) {
        var start = actor.position();
        if (!(actor.getNavigation() instanceof YukkuriGroundNavigation ground) || !actor.onGround()
                || Math.abs(destination.y - start.y) >= .005 || !supported(actor, destination)
                || start.distanceToSqr(destination) > Mth.square(reach(actor)) || !dryStep(actor, start, destination)
                || !Reactions.Weather.allowsStep(actor, BlockPos.containing(destination))) return false;
        var body = actor.getBoundingBox().deflate(.005);
        var sweep = body.expandTowards(destination.subtract(start));
        for (var shape : actor.level().getBlockCollisions(actor, sweep))
            for (var box : shape.toAabbs())
                if (box.intersects(sweep) && !box.intersects(body)) return false;
        if (ground.aim(destination, true) != destination) return false;
        ground.stop();
        ground.step(destination, speed);
        return true;
    }

    private static double reach(YukkuriEntity actor){return Math.max(1.5,actor.getBbWidth()+.5);}

    public static boolean clearLastStep(YukkuriEntity actor,Vec3 start,Vec3 destination){
        double reach=reach(actor),step=actor.maxUpStep();
        if (destination.y-start.y > step || start.y-destination.y > 1 || start.distanceToSqr(destination) > reach*reach) return false;
        if(NestDoorway.blocksLine(actor,start,destination))return false;
        if (!supported(actor, destination)) return false;
        int samples = Math.max(1, (int)Math.ceil(start.distanceTo(destination)/Math.min(.05, actor.getBbWidth()*.25)));
        double wet = wetArea(actor, start, false);
        double previous=start.y;
        for (int i=0; i<=samples; i++) {
            var sample=start.lerp(destination,(double)i/samples);
            if(supportedStep(actor,sample,wet)){previous=sample.y;continue;}
            // Partly over an edge (a nest rim or step it is stepping down from): the top under part of the sole is enough
            // for this sample; requiring all four feet here stranded wide bodies on a rim above the floor they walk to.
            var surface=top(actor,sample);
            if(surface==null||surface.y-previous>step||previous-surface.y>1||!supportedStep(actor,surface,wet))return false;
            previous=surface.y;
        }
        return true;
    }

    /** A step may begin with the body partly over the edge it just climbed onto. */
    private static boolean supportedStep(YukkuriEntity actor, Vec3 feet, double wet) {
        var level = actor.level();
        if (!Reactions.Weather.allowsStep(actor,BlockPos.containing(feet))) return false;
        if (!level.hasChunkAt(BlockPos.containing(feet)) || wetArea(actor, feet, false) > wet + 1.0E-8) return false;
        AABB body = actor.getBoundingBox().move(feet.subtract(actor.position())).deflate(.005);
        if (!level.noCollision(actor, body)) return false;
        // Match the physical feet: vanilla can land while only part of the bounding box touches
        // the bank. Requiring all four inner corners here prevents walking further onto that bank.
        // Every sample still needs actual support, and the final standing place needs all corners.
        var sole = new AABB(body.minX, feet.y-.08, body.minZ, body.maxX, feet.y+.005, body.maxZ);
        return level.getBlockCollisions(actor, sole).iterator().hasNext();
    }

    /** At the destination, or at the point clear of a hat's brim the latest request for it was moved to. */
    public static boolean arrived(YukkuriEntity actor, Vec3 destination) {
        var goal=goal(actor,destination);
        return at(actor,destination)||goal!=null&&goal!=destination&&at(actor,goal);
    }

    public static boolean at(YukkuriEntity actor, Vec3 destination) {
        // Two actors may stop on opposite edges of this tolerance. Keep their combined error
        // inside the shared mouth envelope, rather than accepting two individually "near" bodies.
        return Math.abs(actor.getY()-destination.y)<.08 && actor.position().subtract(destination).horizontalDistanceSqr()<ARRIVAL*ARRIVAL;
    }

    public static boolean canApproach(YukkuriEntity actor, Vec3 destination) {
        return Double.isFinite(routeLength(actor,destination,null));
    }

    /** A terrain access survey for a claim made during a jump. It does not start a walk or move the actor. */
    static boolean canApproachAfterLanding(YukkuriEntity actor, Vec3 destination) {
        if (!(actor.getNavigation() instanceof YukkuriGroundNavigation ground) || ground.canPlanApproach()
                || actor.isPassenger() || actor.isInWaterOrBubble()) return false;
        var landing = landingStand(actor);
        var stand = outside(actor, destination, null);
        return landing != null && stand != null && plan(actor, stand, null, true, landing) != null;
    }

    /** Dry supported feet beneath an airborne body, for surveying the ground it can reach on landing. */
    public static Vec3 landingStand(YukkuriEntity actor) {
        var from = actor.position();
        var hit = actor.level().clip(new ClipContext(from, from.add(0, -actor.getMaxFallDistance(), 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, actor));
        return hit.getType() == HitResult.Type.BLOCK && hit.getDirection() == Direction.UP && stands(actor, hit.getLocation())
                ? hit.getLocation() : null;
    }

    /** Read-only planning, including the last step from a vanilla node to the exact physical stand. */
    public static double routeLength(YukkuriEntity actor,Vec3 destination,AABB obstacle){
        return routeLength(actor,destination,obstacle,false);
    }

    public static boolean canRetreatTo(YukkuriEntity actor,Vec3 destination,AABB obstacle){
        return Double.isFinite(routeLength(actor,destination,obstacle,true));
    }

    private static double routeLength(YukkuriEntity actor,Vec3 destination,AABB obstacle,boolean retreat){
        var goal=outside(actor,destination,obstacle);
        var bed=goal==null?null:NestDoorway.at(actor,goal);
        if(bed!=null){
            if(NestDoorway.at(actor,actor.position())==bed)return actor.position().distanceTo(goal);
            goal=NestDoorway.stand(actor,bed,goal);
        }
        var route=goal==null?null:plan(actor,goal,obstacle,true);
        return route==null||retreat&&!safeRetreatPath(actor,route.path(),goal)?Double.POSITIVE_INFINITY:route.length();
    }

    /** Where the latest request for {@code destination} is walked to (moved clear of a hat's brim), or null without a recent one. */
    public static Vec3 goal(YukkuriEntity actor,Vec3 destination){
        var request=recent(actor,destination);
        return request==null?null:request.goal;
    }

    /** Why the latest request for {@code destination} is blocked, or null. */
    public static String why(YukkuriEntity actor,Vec3 destination){
        var request=recent(actor,destination);
        return request==null||request.result!=Result.BLOCKED?null:request.why;
    }

    private static Approach recent(YukkuriEntity actor,Vec3 destination){
        var request=controller(actor).current;
        return request!=null&&actor.level().getGameTime()-request.touched<200&&request.destination.distanceToSqr(destination)<.0025?request:null;
    }

    /**
     * Where a walk to {@code destination} ends: the destination itself, or, when its middle lies under a placed hat's brim (no
     * body stands there: the hat eases it out), the nearest supported, reachable point just clear of the brim; it stays in
     * contact range of that hat, never pushed off by the foot. Null when there is none, remembered until the retry time.
     */
    public static Vec3 stand(YukkuriEntity actor,Vec3 destination){return outside(actor,destination,null);}

    private static Vec3 outside(YukkuriEntity actor,Vec3 destination,AABB body){
        var boxes=new ArrayList<Detour.Box>();
        for(var hat:YukkuriGroundNavigation.obstacles(actor,new AABB(destination,destination).inflate(1),destination.y,true))boxes.add(hat.box());
        if(boxes.isEmpty())return destination;
        var exits=Detour.outside(destination.x,destination.z,0,boxes);
        if(exits.size()==1&&exits.get(0)[0]==destination.x&&exits.get(0)[1]==destination.z)return destination;
        var self=controller(actor);long now=actor.level().getGameTime();
        if(self.noStand!=null&&now<self.noStandUntil&&self.noStand.distanceToSqr(destination)<.0025)return null;
        // cheap support first, a full route only for the best two supported exits
        int routed=0;
        for(var p:exits){
            var at=floor(actor,new Vec3(p[0],destination.y,p[1]));
            if(at==null)continue;
            if(plan(actor,at,body,true)!=null)return at;
            if(++routed>=2)break;
        }
        self.noStand=destination;self.noStandUntil=now+RETRY;
        return null;
    }

    /** The hats round a destination as they lie now (which, where, how turned): a walk finds its stand again when they change. */
    private static long hats(YukkuriEntity actor,Vec3 destination){
        long signature=0;
        for(var hat:YukkuriGroundNavigation.obstacles(actor,new AABB(destination,destination).inflate(1),destination.y,true)){
            var e=hat.entity();
            signature+=Objects.hash(e.getId(),Math.round(e.getX()*20),Math.round(e.getZ()*20),Math.round(e.getYRot()));
        }
        return signature;
    }

    /** No other body stands where {@code actor} would stand at {@code at} (the peer it is going to is not counted): stands keep apart. */
    public static boolean roomAt(YukkuriEntity actor, Vec3 at, Entity peer) {
        double clear = actor.getBbWidth() * .5;
        return actor.level().getEntitiesOfClass(YukkuriEntity.class, actor.getBoundingBox().inflate(4), y -> y != actor && y != peer && y.isAlive()
                && y.position().subtract(at).horizontalDistance() < clear + y.getBbWidth() * .5 + .3).isEmpty();
    }

    /** A supported, reachable stand around a fixed contact point, admitted by the caller's shared contact ability. */
    public static Vec3 beside(YukkuriEntity actor, Vec3 point, double distance, Predicate<Vec3> contact) {
        var candidates = new ArrayList<Vec3>();
        for (var direction : bearings(actor, point)) {
            var at = floor(actor, point.add(direction.scale(distance)));
            if (at != null && contact.test(at)) candidates.add(at);
        }
        return nearestReachable(actor, candidates, null);
    }

    /** The same around a live peer: selection and the walk ({@link #walkAround}) route round that same body. */
    public static Vec3 beside(YukkuriEntity actor, Vec3 point, double distance, Entity peer, Predicate<Vec3> contact) {
        return nearestReachable(actor, contactStands(actor, point, distance, peer, contact), peer.getBoundingBox());
    }

    /** A supported contact stand and its access from this body's current floor. */
    public static final class ContactRoute {
        private final Vec3 feet, takeoff;
        private final float yaw;
        private final double length;
        private Hop flight;

        private ContactRoute(Vec3 feet, float yaw, Vec3 takeoff, double length) {
            this.feet = feet; this.yaw = yaw; this.takeoff = takeoff; this.length = length;
        }
        public Vec3 feet() { return feet; }
        public float yaw() { return yaw; }
        public double length() { return length; }
    }

    private record ContactStand(Vec3 feet, float yaw) {}

    /** Try the near side first, then the other supported bearings around the stationary peer. */
    public static ContactRoute contactRoute(YukkuriEntity actor, Entity peer, double level, Function<Float, Vec3> pose) {
        return ContactStandSearch.firstReachable(bearings(actor, peer.position()), level, direction -> {
            float yaw = yaw(Vec3.ZERO, direction) + 90;
            var near = pose.apply(yaw);
            var at = near == null ? null : floor(actor, near);
            return at == null ? null : new ContactStand(at, yaw);
        }, stand -> stand.feet.y, stand -> contactRoute(actor, peer, stand));
    }

    private static ContactRoute contactRoute(YukkuriEntity actor, Entity peer, ContactStand stand) {
        var feet = stand.feet;
        var obstacle = peer.getBoundingBox();
        double length = exactContactWalk(actor, feet, obstacle);
        if (Double.isFinite(length)) return new ContactRoute(feet, stand.yaw, null, length);
        var ability = BodyAbility.of(actor);
        double rise = feet.y - actor.getY();
        if (rise <= 0 || rise > ability.hopHeight() || !ability.canJump()) return null;
        var hop = new Hop(feet);
        var search = Hop.Search.admission();
        var from = actor.position();
        if (hop.checkFrom(actor, from, search).ok())
            return new ContactRoute(feet, stand.yaw, from, from.distanceTo(feet));
        double reach = ability.runningReach(0, rise);
        // Find a walk-up on the approach side of the ledge, inside the body's horizontal hop reach.
        for (var bearing : bearings(actor, feet)) {
            for (double distance : new double[]{reach * .5, reach}) {
                var near = feet.add(bearing.scale(distance));
                var at = floor(actor, new Vec3(near.x, from.y, near.z));
                if (at == null || !ContactStandSearch.sameLevel(at.y, from.y) || !hop.checkFrom(actor, at, search).ok()) continue;
                length = exactContactWalk(actor, at, obstacle);
                if (Double.isFinite(length)) return new ContactRoute(feet, stand.yaw, at, length + at.distanceTo(feet));
            }
        }
        return null;
    }

    private static double exactContactWalk(YukkuriEntity actor, Vec3 stand, AABB obstacle) {
        if (outside(actor, stand, obstacle) != stand) return Double.POSITIVE_INFINITY;
        return routeLength(actor, stand, obstacle, stand.y < actor.getY());
    }

    /** Execute the surveyed walk or walk-up and one hop. A refused hop ends this route. */
    public static Result moveToContact(YukkuriEntity actor, ContactRoute route, Entity peer, double speed, MotorOwner owner) {
        if (route.takeoff != null) {
            if (route.flight == null) {
                var approach = walkToContact(actor, route.takeoff, peer, speed);
                if (approach != Result.ARRIVED) return approach;
                if (!actor.onGround()) return Result.WAITING;
                route.flight = new Hop(route.feet);
                return actor.motor().run(owner, route.flight) ? Result.MOVING : Result.BLOCKED;
            }
            if (actor.motor().current() == route.flight) return Result.MOVING;
            if (!route.flight.contacted()) return Result.BLOCKED;
        }
        return walkToContact(actor, route.feet, peer, speed);
    }

    /** Finish the last walking tolerance against world collision before a posed contact is counted. */
    public static boolean atContact(YukkuriEntity actor, Vec3 feet) {
        return ContactStandSearch.sameLevel(actor.getY(), feet.y)
                && ContactStandSearch.insideMovementResolution(actor.position().subtract(feet).horizontalDistanceSqr());
    }

    /** Settle at a stand solved by SideContact. A living obstruction can yield before an established pose takes its next step. */
    public static Result settleContact(YukkuriEntity actor, Vec3 feet, YukkuriEntity peer, boolean established) {
        boolean at = atContact(actor, feet);
        if (established) {
            if (!supportedGround(actor, feet)) return Result.BLOCKED;
            var clearance = contactClearance(actor, feet, peer);
            if (clearance != Result.ARRIVED) return clearance;
        } else if (!at && (!supported(actor, feet) || crossesBody(actor, actor.position(), feet, peer.getBoundingBox())))
            return Result.BLOCKED;
        if (at) return Result.ARRIVED;
        Motor.move(actor, feet.subtract(actor.position()).multiply(1, 0, 1), "side_contact");
        // The pre-step steering target is now behind the mover. Retire it before MoveControl ticks and turns toward it.
        haltInput(actor);
        return atContact(actor, feet) ? Result.ARRIVED : Result.BLOCKED;
    }

    private static Result contactClearance(YukkuriEntity actor, Vec3 feet, YukkuriEntity posedPartner) {
        var from = actor.position();
        var anchor = posedPartner.position();
        double margin = BODY_CLEARANCE + YukkuriGroundNavigation.YIELD_MARGIN;
        var sweep = actor.getBoundingBox().expandTowards(feet.subtract(from)).minmax(posedPartner.getBoundingBox())
                .inflate(margin, 0, margin);
        var bodies = actor.level().getEntities(actor, sweep,
                other -> actor.canCollideWith(other) || other instanceof LivingEntity && other.isAlive() && !other.isSpectator());
        Predicate<AABB> blocks = box -> crossesBody(actor, from, feet, box) || crossesBody(posedPartner, anchor, anchor, box);
        for (var body : bodies) {
            if (body instanceof YukkuriEntity intruder && intruder != posedPartner
                    && blocks.test(YukkuriGroundNavigation.approachingFootprint(intruder)))
                YukkuriGroundNavigation.giveWayForContact(intruder, posedPartner, actor, feet);
        }
        var blocker = ContactStandSearch.blockingFootprint(bodies, posedPartner, other -> blocks.test(other.getBoundingBox()));
        if (blocker == null) return Result.ARRIVED;
        return blocker instanceof LivingEntity ? Result.WAITING : Result.BLOCKED;
    }

    /**
     * The supported stands {@code distance} from a contact point on the bearings of {@link #beside}, each resolved where a walk
     * there actually ends (clear of a hat's brim, round {@code peer}) and admitted by {@code contact} there; nearest the actor
     * first, routes not planned (a caller bounds how many it plans).
     */
    public static List<Vec3> contactStands(YukkuriEntity actor, Vec3 point, double distance, Entity peer, Predicate<Vec3> contact) {
        var candidates = new ArrayList<Vec3>();
        for (var direction : bearings(actor, point)) {
            var raw = floor(actor, point.add(direction.scale(distance)));
            var at = raw == null ? null : outside(actor, raw, peer == null ? null : peer.getBoundingBox());
            if (at != null && !candidates.contains(at) && contact.test(at)) candidates.add(at);
        }
        candidates.sort(Comparator.comparingDouble(actor::distanceToSqr));
        return candidates;
    }

    /** The point's bearing to the actor first, then sixteen bearings round it. */
    private static List<Vec3> bearings(YukkuriEntity actor, Vec3 point) {
        var directions = new ArrayList<Vec3>();
        var toward = actor.position().subtract(point).multiply(1, 0, 1);
        if (toward.horizontalDistanceSqr() > .0001) directions.add(toward.normalize());
        for (int i = 0; i < 16; i++) {
            double angle = i * Math.PI / 8;
            directions.add(new Vec3(Math.cos(angle), 0, Math.sin(angle)));
        }
        return directions;
    }

    /** Physical contact uses square body boxes. A point on the centre-to-centre diagonal can be inside both bodies. */
    public static Vec3 beside(YukkuriEntity actor, Entity other, double distance, double maxHeight) {
        return beside(actor,other,distance,maxHeight,Double.POSITIVE_INFINITY);
    }

    /** Some callers use a spherical reach. A step-height difference must leave room inside that same envelope. */
    public static Vec3 beside(YukkuriEntity actor,Entity other,double distance,double maxHeight,double maxDistance){
        return beside(actor, other, distance, maxHeight, maxDistance, at -> true);
    }

    /** A contact ability may narrow the ordinary cached stand search with its actual reach/sight test. */
    public static Vec3 beside(YukkuriEntity actor,Entity other,double distance,double maxHeight,double maxDistance,Predicate<Vec3> contact){
        var route=controller(actor).current;
        if(route!=null&&route.navigation==actor.getNavigation()&&Objects.equals(route.peer,other.getUUID())&&sameBody(route.body,other.getBoundingBox())
                &&actor.level().getGameTime()-route.touched<200
                &&(route.result!=Result.BLOCKED||actor.level().getGameTime()<route.retryAt)){
            var at=route.destination;
            double flat=at.subtract(other.position()).horizontalDistance();
            double desired=Math.min(distance,Math.sqrt(Math.max(0,maxDistance*maxDistance-Math.pow(at.y-other.getY(),2)))-.025);
            if(Math.abs(flat-desired)<.01&&Math.abs(at.y-other.getY())<=maxHeight
                    &&!actor.getBoundingBox().move(at.subtract(actor.position())).deflate(.005).intersects(other.getBoundingBox())
                    &&supported(actor,at)&&contact.test(at))return at;
        }
        // Asking again with nothing changed (the peer's body, this body's cell, the reach) cannot find a stand the last search did not:
        // the refusal holds a real interval, longer each time it is met unchanged, and a changed ask searches at once.
        var self=controller(actor);
        long now=actor.level().getGameTime();
        var refused=self.noContact.stream().filter(r->r.sameAsk(other,distance,maxHeight,maxDistance)).findFirst().orElse(null);
        boolean unchanged=refused!=null&&refused.unchanged(actor,other);
        if(unchanged&&now<refused.until)return null;
        var candidates=new ArrayList<Vec3>();
        var why=new StringBuilder();
        for(var direction:bearings(actor,other.position())){
            var near=other.position().add(direction.scale(distance));
            var at=floor(actor,near);
            if(at==null){why.append(" ").append(noFloor(actor,near)).append("@").append(GameplayLog.at(near));continue;}
            if(Math.abs(at.y-other.getY())>maxHeight){why.append(" height@").append(GameplayLog.at(at));continue;}
            if(Double.isFinite(maxDistance)){
                double flat=Math.min(distance,Math.sqrt(Math.max(0,maxDistance*maxDistance-Math.pow(at.y-other.getY(),2)))-.025);
                if(flat<=0){why.append(" reach");continue;}
                at=floor(actor,other.position().add(direction.scale(flat)));
                if(at==null||Math.abs(at.y-other.getY())>maxHeight||at.distanceTo(other.position())>maxDistance){why.append(" reach@").append(GameplayLog.at(near));continue;}
            }
            var body=actor.getBoundingBox().move(at.subtract(actor.position())).deflate(.005);
            if(body.intersects(other.getBoundingBox())){why.append(" overlap@").append(GameplayLog.at(at));continue;}
            if(!contact.test(at)){why.append(" contact@").append(GameplayLog.at(at));continue;}
            candidates.add(at);
        }
        var stand=nearestReachable(actor,candidates,other.getBoundingBox());
        self.noContact.remove(refused);
        if(stand!=null)return stand;
        int refusals=unchanged?refused.refusals+1:1;
        if(self.noContact.size()==REFUSED_ASKS)self.noContact.removeFirst();
        self.noContact.add(new NoContact(other.getUUID(),other.getBoundingBox(),actor.blockPosition(),distance,maxHeight,maxDistance,
                now+((long)RETRY<<Math.min(refusals-1,CONTACT_HOLD_DOUBLINGS)),refusals));
        for(var at:candidates)why.append(" noroute@").append(GameplayLog.at(at));
        GameplayLog.limited(actor,"approach","stand",200,"contact stand unavailable peer={} at={} box={} distance={} maxHeight={} rejected:{}",
                other.getUUID(),GameplayLog.at(other.position()),other.getBoundingBox(),distance,maxHeight,why);
        return null;
    }

    /** Why {@link #floor} found no stand at {@code near}, for the rejection log: no collision top in reach, or the top it found fails one support test. */
    private static String noFloor(YukkuriEntity actor,Vec3 near){
        var at=top(actor,near);
        if(at==null)return "notop";
        if(!Reactions.Weather.allowsStep(actor,BlockPos.containing(at)))return "weather";
        var body=actor.getBoundingBox().move(at.subtract(actor.position()));
        if(wetArea(actor.level(),body,at)>0)return "wet";
        if(!actor.level().noCollision(actor,body.deflate(.005)))return "blocked";
        return "sole";
    }

    /** The next shared movement to a loose item: a supported walk to a stand beside it on dry ground, or, by species ability, from
     *  the bank stand nearest it a step into the water and a swim to it. Null when this body has no way there. */
    public static Action approach(YukkuriEntity actor, ItemEntity item) { return approach(actor, item, Set.of()); }

    /** The same, never from a bank stand in {@code avoid}: one an earlier step-in and swim left without reaching the item. */
    public static Action approach(YukkuriEntity actor, ItemEntity item, Set<BlockPos> avoid) {
        if (actor.isInWaterOrBubble()) return actor.profile().aquatic() ? Swim.to(item::position, 0) : null;
        if (!item.isInWater()) {
            var stand = beside(actor, item, actor.getBbWidth() / 2 + .2, ContactGeometry.GRAB_HEIGHT);
            if (stand != null) return Walk.stand(stand, 1);
        }
        if (!actor.profile().aquatic()) return null;
        var level = actor.level();
        var centre = item.blockPosition();
        Vec3 stand = null, entry = null;
        double best = Double.POSITIVE_INFINITY;
        for (var p : BlockPos.betweenClosed(centre.offset(-8, -1, -8), centre.offset(8, 2, 8))) {
            if (!level.hasChunkAt(p) || avoid.contains(p) || !actor.life().dryStand(p)) continue;
            var at = Vec3.atBottomCenterOf(p);
            for (var direction : Direction.Plane.HORIZONTAL) {
                var wet = p.relative(direction).below();
                var fluid = level.getFluidState(wet);
                if (!fluid.is(FluidTags.WATER) || !level.getBlockState(wet.above()).getCollisionShape(level, wet.above()).isEmpty()) continue;
                var water = new Vec3(wet.getX() + .5, wet.getY() + fluid.getHeight(level, wet), wet.getZ() + .5);
                double distance = at.distanceToSqr(item.position());
                if (distance >= best || !canApproach(actor, at)
                        || level.clip(new ClipContext(water, item.position(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, actor)).getType() != HitResult.Type.MISS) continue;
                best = distance; stand = at; entry = water;
            }
        }
        return stand == null ? null : arrived(actor, stand) ? new StepIn(entry) : new Walk(stand);
    }

    private static Vec3 nearestReachable(YukkuriEntity actor,List<Vec3> candidates,AABB obstacle){
        candidates.sort(Comparator.comparingDouble(actor::distanceToSqr));
        for(var at:candidates)if(Double.isFinite(routeLength(actor,at,obstacle)))return at;
        return null;
    }

    public static boolean crossesBody(YukkuriEntity actor,Vec3 from,Vec3 to,AABB body){
        double half=actor.getBbWidth()*.5+BODY_CLEARANCE;
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

    private void rememberStep(YukkuriEntity actor, Vec3 destination, Vec3 node, Vec3 step, AABB body) {
        failedSteps.removeIf(failure -> failure.from.distanceToSqr(actor.position()) >= SAME_STAND_SQR);
        if (failedSteps.size() == 16) failedSteps.removeFirst();
        failedSteps.add(new FailedStep(actor.position(), destination, node, step, body));
    }

    private boolean failedNode(YukkuriEntity actor, Vec3 destination, Vec3 node, AABB body) {
        for (var failure : failedSteps) {
            if (failure.from.distanceToSqr(actor.position()) < SAME_STAND_SQR
                    && failure.destination.distanceToSqr(destination) < SAME_STAND_SQR
                    && failure.node.distanceToSqr(node) < SAME_STAND_SQR && sameBody(failure.body, body)
                    && !(direct(actor, failure.blockedStep, body) && walkable(actor, failure.blockedStep))) return true;
        }
        return false;
    }

    /** {@code straight}: a direct last step (or the way round the first footprint on it) may serve instead of a route. */
    private static Route plan(YukkuriEntity actor,Vec3 destination,AABB body,boolean straight){
        return plan(actor,destination,body,straight,null);
    }

    private static Route plan(YukkuriEntity actor,Vec3 destination,AABB body,boolean straight,Vec3 landing){
        if(!supported(actor,destination)||TravelRange.keepBabyHome(actor)&&!TravelRange.babyDestination(actor,BlockPos.containing(destination)))return null;
        if(landing!=null&&straight&&clearLastStep(actor,landing,destination))return new Route(null,landing.distanceTo(destination));
        if(landing==null&&straight&&clearOf(actor,actor.position(),destination,body)&&(arrived(actor,destination)||clearLastStep(actor,destination)&&walkable(actor,destination)))
            return new Route(null,actor.position().distanceTo(destination));
        // A containing block is not an exact stand. Offer only node centres with a clear, supported final segment.
        double offset=Mth.floor(actor.getBbWidth()+1)*.5;
        int x=Mth.floor(destination.x-offset+.5),z=Mth.floor(destination.z-offset+.5);
        var targets=new LinkedHashSet<BlockPos>();
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
            var at=floor(actor,new Vec3(x+dx+offset,destination.y,z+dz+offset));
            // The body may stop up to ARRIVAL short of this node: its remaining step must still fit inside the clear-step reach.
            if(at!=null&&at.distanceTo(destination)<=reach(actor)-ARRIVAL&&clearOf(actor,at,destination,body)
                    &&clearLastStep(actor,at,destination)&&(landing!=null||!controller(actor).failedNode(actor,destination,at,body)))
                targets.add(new BlockPos(x+dx,Mth.floor(at.y+.5),z+dz));
        }
        if(targets.isEmpty())return null;
        var navigation=actor.getNavigation();
        Path path;
        if(landing!=null){
            var from=new BlockPos(Mth.floor(landing.x-offset+.5),Mth.floor(landing.y+.5),Mth.floor(landing.z-offset+.5));
            path=((YukkuriGroundNavigation)navigation).surveyTravelPath(from,targets);
        }else{
            path=navigation instanceof YukkuriGroundNavigation ground?ground.createApproachPath(targets,body,destination):navigation.createPath(targets,0);
        }
        if(path==null||!path.canReach()||path.getNodeCount()==0)return null;
        var first=floor(actor,path.getEntityPosAtNode(actor,0));
        if(landing!=null){
            if(first==null||!clearLastStep(actor,landing,first))return null;
        }else if(first!=null&&actor.onGround()&&NestDoorway.at(actor,actor.position())==null&&Math.abs(first.y-actor.getY())<=.25&&!clearLastStep(actor,first))return null;
        Vec3 before=landing!=null?landing:actor.position();double length=0;
        for(int i=0;i<path.getNodeCount();i++){
            var next=path.getEntityPosAtNode(actor,i);
            if(!clearOf(actor,before,next,body)||!dryStep(actor,before,next))return null;
            length+=before.distanceTo(next);before=next;
        }
        // Recheck the chosen endpoint: vanilla may return a partial route, or a node at a different height.
        var end=floor(actor,before);
        if(end==null||!clearOf(actor,end,destination,body)||!clearLastStep(actor,end,destination))return null;
        return new Route(path,length+end.distanceTo(destination));
    }

    /** Plan the complete route around blocks and the partner together; never alternate isolated greedy corners. Called every tick
     *  while the step is wanted, as {@link #walk}. */
    public static boolean walkAround(YukkuriEntity actor,Vec3 destination,Entity other,double speed){
        return walkResult(actor,destination,other,speed)==Result.ARRIVED;
    }

    /** Drive one step and give the owner the actual outcome, including a terminal refusal it can act on. */
    public static Result walkResult(YukkuriEntity actor, Vec3 destination, Entity other, double speed) {
        return controller(actor).walkStep(actor,destination,other==actor?null:other,speed,false);
    }

    public static Result retreatResult(YukkuriEntity actor, Vec3 destination, Entity other, double speed) {
        return controller(actor).walkStep(actor,destination,other,speed,true);
    }

    /** A live contact stand can move by less than the ordinary route cache's slack. */
    public static Result walkToContact(YukkuriEntity actor, Vec3 destination, Entity other, double speed) {
        var approach = controller(actor);
        var request = approach.current;
        if (request != null && (request.destination.distanceToSqr(destination) >= ARRIVAL * ARRIVAL
                || request.goal == request.destination && at(actor, request.destination) && !at(actor, destination)))
            approach.current = null;
        return walkResult(actor, destination, other, speed);
    }

    /** Never substitute a straight line for a route around a step or obstruction. Call it every tick while the step is wanted: once
     *  the path ends, the last step is one MoveControl input that vanilla zeroes on the next tick, so throttling the call produces
     *  stop-go movement. */
    public static boolean walk(YukkuriEntity actor, Vec3 destination, double speed) {
        return walkResult(actor,destination,null,speed)==Result.ARRIVED;
    }

    private Result walkStep(YukkuriEntity actor,Vec3 destination,Entity other,double speed,boolean retreat){
        long started=System.nanoTime();
        Result result=walkStepNow(actor,destination,other,speed,retreat);
        if(retreat&&result==Result.MOVING&&actor.getNavigation() instanceof YukkuriGroundNavigation ground)
            ground.retreatSteering();
        long millis=(System.nanoTime()-started)/1_000_000;
        if(millis>=50)GameplayLog.limited(actor,"approach","slow",200,
                "slow surface approach target={} elapsedMs={} pathDone={} horizontalCollision={}",
                destination,millis,actor.getNavigation().isDone(),actor.horizontalCollision);
        sample(actor,destination,result);
        return result;
    }

    /**
     * {@code walk.sample} for every ground approach, not only for the Walk action: the infant feeding path asks
     * this shared layer directly. Position, destination, navigation state, nodes left, installed target and whether the body is
     * being driven or held up, every 20 ticks with the detail switch on (E11-3).
     */
    private static void sample(YukkuriEntity actor,Vec3 destination,Result result){
        if(!GameplayLog.detailEnabled())return;
        // The Walk action prints its own walk.sample for the same ticks.
        if(actor.motor().current() instanceof Walk)return;
        int suppressed = GameplayLog.admit(actor, "walk", "sample", SAMPLE_TICKS);
        if (suppressed < 0) return;
        var navigation=actor.getNavigation();
        var path=navigation.getPath();
        int nodes=path==null?0:Math.max(0,path.getNodeCount()-path.getNextNodeIndex());
        var owner=actor.motor().owner();
        GameplayLog.limitedEvent(actor,"walk",suppressed,
                "sample layer=approach at={} dest={} pathTarget={} navDone={} nodesLeft={} metric={} speed={} collided={} crawl={} owner={} result={}",
                GameplayLog.at(actor.position()),GameplayLog.at(destination),navigation.getTargetPos(),navigation.isDone(),nodes,
                String.format(Locale.ROOT,"%.2f",actor.position().distanceTo(destination)),
                String.format(Locale.ROOT,"%.3f",actor.getSpeed()),actor.horizontalCollision,actor.isCrawling(),
                owner==null?"none":owner.ownerId(),result);
    }

    private Result walkStepNow(YukkuriEntity actor,Vec3 destination,Entity other,double speed,boolean retreat){
        var navigation=actor.getNavigation();
        var body=other==null?null:other.getBoundingBox();var peer=other==null?null:other.getUUID();
        long now=actor.level().getGameTime();
        if(current==null||current.navigation!=navigation||!Objects.equals(peer,current.peer)||!sameBody(body,current.body)
                ||current.destination.distanceToSqr(destination)>=.0025||now-current.touched>=200){
            stopInput(actor);current=new Approach(actor,other,destination);
        }
        if(current.goal!=null&&now-current.hatsChecked>=HATS_CHECK){
            current.hatsChecked=now;
            long hats=hats(actor,destination);
            if(hats!=current.hatsSig){
                var was=current.goal;
                current.hatsSig=hats;current.goal=outside(actor,destination,body);
                if(current.goal==null||current.goal.distanceToSqr(was)>1e-6){current.target=null;current.lastStep=null;}
            }
        }
        if(current.goal==null){
            if(current.result==Result.BLOCKED&&now<current.retryAt){stopInput(actor);return observed(actor,Result.BLOCKED);}
            current.hatsSig=hats(actor,destination);current.hatsChecked=now;
            current.goal=outside(actor,destination,body);
            if(current.goal==null)return blocked(actor,"under_hat");
            if (current.goal != destination) {
                int suppressed = GameplayLog.admit(actor, "approach", "outside_hat", 200);
                if (suppressed >= 0) GameplayLog.limitedEvent(actor,"approach",suppressed,
                        "destination under a hat's brim moved clear peer={} destination={} goal={}",peer,GameplayLog.at(destination),GameplayLog.at(current.goal));
            }
        }
        destination=current.goal;
        if(navigation instanceof YukkuriGroundNavigation ground){
            // The open-door node advances only when the trailing edge clears. A direct bed step stops that path.
            if (current.target != null && current.target.equals(navigation.getTargetPos()) && !navigation.isDone()
                    && navigation.getPath().canReach() && navigation.getPath().getNextNode().type == PathType.DOOR_OPEN) {
                navigation.setSpeedModifier(speed);
                return observed(actor, Result.MOVING);
            }
            var bed=NestDoorway.at(actor,destination);var here=NestDoorway.at(actor,actor.position());
            if((bed==null||here!=null&&here!=bed)&&ground.exitBed(speed))return observed(actor,Result.MOVING);
            if(bed!=null){
                var door=NestDoorway.stand(actor,bed,destination);
                if(door==null)return blocked(actor,"doorway_unreachable");
                var inner=NestDoorway.inner(actor,bed,door);
                // Finish the already-issued straight step; a rim position alone never starts entry.
                if(here==bed||at(actor,door)||Double.isFinite(ground.way(inner))){
                    navigation.stop();current.target=null;current.lastStep=null;
                    boolean arrived=ground.step(here==bed?destination:inner,speed);
                    return observed(actor,here==bed&&arrived?Result.ARRIVED:Result.MOVING);
                }
                // A nearby release or rest stand can shift the doorway alignment sideways. The
                // capped ground turn can circle a close target, so close this clear segment
                // with the same collision-resolved straight step as the crossing itself.
                if(clearOf(actor,actor.position(),door,body)&&tryStraightStep(actor,door,speed)){
                    current.target=null;current.lastStep=null;
                    return observed(actor,Result.MOVING);
                }
                destination=door;
            }
        }
        if(navigation instanceof YukkuriGroundNavigation ground)ground.heading(destination, other);
        var target=BlockPos.containing(destination);
        if(TravelRange.keepBabyHome(actor)&&!TravelRange.babyDestination(actor,target))return blocked(actor,"outside_home");
        if(!Reactions.Weather.allowsStep(actor,target))return blocked(actor,"weather");
        if(arrived(actor,destination)){
            haltInput(actor);current.target=null;current.lastStep=null;
            return observed(actor,Result.ARRIVED);
        }
        var active=current.target==null?null:navigation.getPath();
        boolean completed=active!=null&&active.isDone()&&active.canReach()&&current.target.equals(active.getTarget());
        boolean ready=!(navigation instanceof YukkuriGroundNavigation ground)||ground.canPlanApproach();
        // Only an owned, completed route has a last-step deadline. A fall before planning still waits for a stand.
        if(!ready&&!completed&&current.lastStep==null)return observed(actor,Result.WAITING);
        if(ready&&navigation instanceof YukkuriGroundNavigation ground&&ground.takeStall()&&!current.routed){
            current.routed=true;current.target=null;current.lastStep=null;
        }
        if(ready&&!current.routed&&direct(actor,destination,body)){
            if(steer(actor,destination,speed,retreat)){current.target=null;current.lastStep=null;return observed(actor,Result.MOVING);}
            // the way round is no clear step: a planned route first, not a straight try every other tick
            current.routed=true;
        }
        if(current.result==Result.BLOCKED&&now<current.retryAt){stopInput(actor);return observed(actor,Result.BLOCKED);}
        if(current.target!=null){
            // Another owner stopping the route (a wild beat, a lesson) took the path away; it is not a refused way, so plan it again.
            if(active==null){current.target=null;current.lastStep=null;current.routed=false;}
            else {
                if(!current.target.equals(active.getTarget()))return blocked(actor,"route_stopped");
                if(!active.canReach())return blocked(actor,"route_unreachable");
                // A navigation replan may replace Path while retaining this destination. Follow its replacement.
                if(!active.isDone()){navigation.setSpeedModifier(speed);return observed(actor,Result.MOVING);}
                current.lastStep=floor(actor,active.getEntityPosAtNode(actor,active.getNodeCount()-1));
                current.target=null;current.routed=false;
                if(current.lastStep==null)return blocked(actor,"last_node_unsupported");
                current.landBy=now+LANDING_TICKS;
            }
        }
        if(current.lastStep!=null){
            // Natural path completion hands over one physical endpoint; an interrupted path never reaches here.
            if(navigation instanceof YukkuriGroundNavigation&&!actor.onGround()){
                if(now<current.landBy&&!actor.isInWaterOrBubble()&&!actor.onClimbable()&&!actor.isPassenger())
                    return observed(actor,Result.WAITING);
                return blocked(actor,"last_step_no_ground_contact");
            }
            var node=current.lastStep;
            if(direct(actor,destination,body)&&steer(actor,destination,speed,retreat)){
                current.lastStep=null;current.routed=false;
                return observed(actor,Result.MOVING);
            }
            if(arrived(actor,node)){
                // The node is reached, but the requested stand can still lie beyond it.
                rememberStep(actor,destination,node,destination,body);
                return blocked(actor,"last_segment_blocked");
            }
            if(direct(actor,node,body)&&steer(actor,node,speed,retreat))
                return observed(actor,Result.MOVING);
            rememberStep(actor,destination,node,node,body);
            return blocked(actor,"last_step_blocked");
        }
        if(current.result==Result.BLOCKED&&now<current.retryAt)return observed(actor,Result.BLOCKED);
        var route=plan(actor,destination,body,!current.routed);
        if(route!=null&&route.path()!=null&&(retreat?startRetreat(actor,route.path(),speed):startRoute(actor,route.path(),speed,destination))){
            current.target=navigation.getTargetPos();
            int suppressed = GameplayLog.admit(actor, "approach", "route", 200);
            if (suppressed >= 0) GameplayLog.limitedEvent(actor, "approach", suppressed,
                    "surface route started peer={} destination={} nodes={} length={}",peer,destination,route.path().getNodeCount(),route.length());
            return observed(actor,Result.MOVING);
        }
        return blocked(actor,navigation instanceof YukkuriGroundNavigation ground&&ground.solidPlanned()>0
                ?"no_complete_surface_route solid="+ground.solidPlanned():"no_complete_surface_route");
    }

    private Result observed(YukkuriEntity actor,Result result){
        current.result=result;current.touched=actor.level().getGameTime();return result;
    }

    private Result blocked(YukkuriEntity actor,String reason){
        stopInput(actor);current.target=null;current.lastStep=null;current.retryAt=actor.level().getGameTime()+RETRY;
        if (current.result != Result.BLOCKED || !reason.equals(current.why)) {
            GameplayLog.limited(actor,"approach","path_failed",200,
                    "surface path unavailable peer={} target={} reason={} retryAfter={}",current.peer,current.destination,reason,current.retryAt);
        }
        current.why=reason;
        return observed(actor,Result.BLOCKED);
    }

    /** The straight last step to {@code destination} is clear of blocks and of the partner. */
    private static boolean direct(YukkuriEntity actor,Vec3 destination,AABB body){
        return clearOf(actor,actor.position(),destination,body)&&clearLastStep(actor,destination);
    }

    private static Vec3 aim(YukkuriEntity actor,Vec3 destination,boolean moving){
        return actor.getNavigation() instanceof YukkuriGroundNavigation ground?ground.aim(destination,moving):destination;
    }

    /** The way round the first footprint on a clear last step is itself a clear step (or there is none to go round); a look, not a move. */
    private static boolean walkable(YukkuriEntity actor,Vec3 destination){
        var aim=aim(actor,destination,false);
        return aim==destination||aim!=null&&clearLastStep(actor,aim);
    }

    /** Heads for the destination, round the first footprint on the way; false when that way round is not a clear step. */
    private static boolean steer(YukkuriEntity actor,Vec3 destination,double speed,boolean retreat){
        var aim=aim(actor,destination,true);
        if(aim==null||aim!=destination&&!clearLastStep(actor,aim))return false;
        if(retreat&&!safeRetreatPath(actor,null,aim))return false;
        var navigation = actor.getNavigation();
        var from = actor.position();
        double remaining = from.distanceTo(destination);
        if (aim == destination && navigation instanceof YukkuriGroundNavigation) {
            float heading = yaw(from, destination);
            if (Gait.needsStandingTurn(remaining, actor.getBbWidth(), heading - actor.yBodyRotO, BodyAbility.of(actor).turnRate())) {
                haltInput(actor);
                // A stationary body's yaw follows its head. Aim at this tick's capped heading.
                var forward = Vec3.directionFromRotation(0, Motor.turnYaw(actor, heading));
                actor.getLookControl().setLookAt(from.x + forward.x, actor.getEyeY(), from.z + forward.z, 180, 0);
                return true;
            }
        }
        navigation.stop();
        // Ground travel multiplies forward input by speed again; keep the last few centimetres from slowing quadratically.
        double movement=actor.getAttributeValue(Attributes.MOVEMENT_SPEED);
        double modifier=Math.sqrt(Math.min(.02,remaining*.20))/Math.max(.0001,movement);
        // A running request (a parent racing to a child in danger) does not dock slowly: it keeps its gait until the last half block.
        if(Gait.fast(speed)&&remaining>.5)modifier=speed;
        actor.getMoveControl().setWantedPosition(aim.x,aim.y,aim.z,Math.min(speed,modifier));
        return true;
    }

    /** Cancel walking input without cancelling a real push (for example, a sumo exchange). */
    public static void stopWalking(YukkuriEntity actor) {
        controller(actor).current=null;
        stopInput(actor);
    }

    private static void stopInput(YukkuriEntity actor){
        actor.getNavigation().stop();
        actor.getMoveControl().setWantedPosition(actor.getX(), actor.getY(), actor.getZ(), 0);
        actor.setSpeed(0);
    }

    public static void halt(YukkuriEntity actor) {
        controller(actor).current=null;
        haltInput(actor);
    }

    /** Stop walking input and kill the horizontal speed, without ending the walk request (a body holding still on purpose). */
    public static void haltInput(YukkuriEntity actor){
        stopInput(actor);
        if (actor.onGround()) {
            Vec3 velocity = actor.getDeltaMovement();
            // Only a real horizontal motion needs the write; a standing body would otherwise mark an impulse every tick.
            if (velocity.x * velocity.x + velocity.z * velocity.z > 1E-8)
                Motor.velocity(actor, new Vec3(0, velocity.y, 0), "halt");
        }
    }

    public static void face(YukkuriEntity actor, float yaw) {
        float turn = Mth.approachDegrees(actor.getYRot(), yaw, 12);
        Motor.face(actor, turn);
    }

    public static float yaw(Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        return (float)(Math.toDegrees(Math.atan2(delta.z, delta.x))-90);
    }
}
