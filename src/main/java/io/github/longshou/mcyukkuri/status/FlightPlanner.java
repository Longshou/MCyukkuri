package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriGroundNavigation;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.TravelRange;
import io.github.longshou.mcyukkuri.motor.geom.FlightCourse;
import io.github.longshou.mcyukkuri.settlement.DenBarrier;
import io.github.longshou.mcyukkuri.motor.geom.FlightCourse.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.util.GoalUtils;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.ai.util.RandomPos;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/** Selects and holds a dry route that keeps clear of the pursuer. */
public final class FlightPlanner {
    private static final int[] FAN = {0, 25, -25, 50, -50, 80, -80, 100, -100};
    private static final double[] STRIDES = {1, .6, .3};
    private static final double CLEARANCE = 3.8;
    private final YukkuriEntity body;
    private final FailedRetreat<Vec3> failed = new FailedRetreat<>();
    private float failedWidth, failedHeight;
    private LivingEntity source;
    private Direction heading;
    private long plannedAt = Long.MIN_VALUE / 2;
    private Path route;
    private Vec3 checkedDanger, shielded;
    private LivingEntity shieldedFrom;

    FlightPlanner(YukkuriEntity body) { this.body = body; }

    private Direction away(Vec3 danger) {
        return FlightCourse.awayUnit(body.getX(), body.getZ(), danger.x, danger.z, Math.toRadians(body.getYRot()));
    }

    private Path routeTo(Vec3 stand, Vec3 danger) {
        if (stand == null || FlightCourse.passesNearThreat(body.getX(), body.getZ(), stand.x, stand.z, danger.x, danger.z, CLEARANCE))
            return null;
        var path = body.getNavigation().createPath(BlockPos.containing(stand), 0);
        if (path == null || !path.canReach()) return null;
        if (safeRoute(path, danger)) return path;
        // In its starting cell the runner can already be farther from danger than the node centre.
        if (path.getNextNodeIndex() != 0 || path.getNodeCount() < 2 || !path.getNodePos(0).equals(body.blockPosition())
                || !SurfaceApproach.clearLastStep(body, path.getEntityPosAtNode(body, 1))) return null;
        var direct = path.copy();
        direct.advance();
        return safeRoute(direct, danger) ? direct : null;
    }

    private boolean safeRoute(Path path, Vec3 danger) {
        return clearsThreat(path, danger) && SurfaceApproach.safeRetreatPath(body, path, null);
    }

    boolean clearsThreat(Path path, Vec3 danger) {
        var from = body.position();
        double clearance = FlightCourse.corridorClearance(Math.hypot(from.x - danger.x, from.z - danger.z), CLEARANCE);
        for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
            var next = path.getEntityPosAtNode(body, i);
            if (FlightCourse.segmentDistance(from.x, from.z, next.x, next.z, danger.x, danger.z) < clearance) return false;
            from = next;
        }
        return true;
    }

    private Path fan(Vec3 danger, double reach, Direction direction, BlockPos stalled, Vec3 home) {
        Path best = null;
        double score = Double.NEGATIVE_INFINITY;
        for (int angle : FAN) {
            var ray = direction.rotate(angle);
            for (double stride : STRIDES) {
                double distance = reach * stride;
                var near = body.position().add(ray.x() * distance, 0, ray.z() * distance);
                var stand = SurfaceApproach.retreatFloor(body, near);
                if (stand == null || BlockPos.containing(stand).equals(stalled)) continue;
                double candidate = FlightCourse.scoreCandidate(body.getX(), body.getZ(), stand.x, stand.z,
                        danger.x, danger.z, direction);
                if (candidate <= score) continue;
                var path = routeTo(stand, danger);
                if (path != null && (home == null || !FlightCourse.passesNearThreat(body.getX(), body.getZ(),
                        stand.x, stand.z, home.x, home.z, CLEARANCE) && clearsThreat(path, home))) {
                    best = path; score = candidate;
                }
            }
        }
        return best;
    }

    public Vec3 destination(Vec3 danger, double reach) {
        var path = retreatRoute(danger, reach);
        return path == null ? null : Vec3.atBottomCenterOf(path.getTarget());
    }

    public Path retreatRoute(Vec3 danger, double reach) {
        return fan(danger, reach, away(danger), null, null);
    }

    public void retreatToward(Vec3 danger, Vec3 anchor) {
        double distance = body.position().distanceTo(anchor);
        if (distance < 1.5) return;
        if (holdsFailure(null, danger, anchor)) return;
        failed.clear();
        source = null;
        plannedAt = body.level().getGameTime();
        var direction = FlightCourse.awayUnit(anchor.x, anchor.z, body.getX(), body.getZ(), Math.toRadians(body.getYRot()));
        var path = fan(danger, Math.min(9, distance), direction, null, null);
        if (path == null || !SurfaceApproach.startRetreat(body, path, 1)) { noExit(danger, anchor); return; }
        route = body.getNavigation().getPath();
    }

    private Path refuge(LivingEntity danger) {
        if (shieldsHomeFrom(danger)) return null;
        var parent = ParentCarry.flightEscort(body, danger);
        if (parent != null) {
            var path = routeTo(parent.position(), danger.position());
            if (path != null) return path;
        }
        var keeper = keeper(danger);
        if (keeper != null) {
            var path = routeTo(keeper.position(), danger.position());
            if (path != null) return path;
        }
        return routeTo(refugeStand(), danger.position());
    }

    private LivingEntity keeper(LivingEntity danger) {
        var owner = body.pet().owner();
        return owner != null && ((ServerLevel) body.level()).getEntity(owner) instanceof LivingEntity keeper
                && keeper.isAlive() && !keeper.isSpectator() && keeper.distanceToSqr(body) <= 256 && keeper.distanceToSqr(danger) > 36
                ? keeper : null;
    }

    private Vec3 refugeStand() {
        var shelter = body.habitat().shelter();
        if (shelter != null && !shelter.contains(body.blockPosition()) && DenBarrier.sealed(shelter))
            return Vec3.atBottomCenterOf(shelter.entrance().relative(shelter.facing()));
        var home = body.life().homeStand();
        return home == null ? null : Vec3.atBottomCenterOf(home);
    }

    boolean hasExit(LivingEntity danger) {
        if (body.getNavigation() instanceof YukkuriGroundNavigation ground && !ground.canPlanApproach()) {
            var landing = SurfaceApproach.landingStand(body);
            if (landing == null) return false;
            var away = away(danger.position());
            for (int angle : FAN) {
                var ray = away.rotate(angle);
                var step = SurfaceApproach.retreatFloor(body, landing.add(ray.x(), 0, ray.z()));
                if (step != null && !FlightCourse.passesNearThreat(landing.x, landing.z, step.x, step.z,
                        danger.getX(), danger.getZ(), CLEARANCE) && SurfaceApproach.clearLastStep(body, landing, step)
                        && SurfaceApproach.safeRetreatStep(body, landing, step)) return true;
            }
            return false;
        }
        return refuge(danger) != null || destination(danger.position(), 5) != null;
    }

    void shield(LivingEntity danger, Vec3 den) {
        shieldedFrom = danger;
        shielded = den;
        source = null; // Discard a cached route home as soon as the parent chooses the decoy.
    }

    public boolean shieldsHomeFrom(LivingEntity danger) { return danger != null && danger == shieldedFrom; }

    public void lead(LivingEntity danger, Vec3 den) { run(danger, den); }

    public boolean flee(LivingEntity danger) {
        run(danger, null);
        return route != null && !route.isDone() && body.getNavigation().getPath() == route;
    }

    public void fleeWithoutSource() {
        var navigation = body.getNavigation();
        if (!navigation.isDone()) return;
        if (holdsFailure(null, null, null)) return;
        if (navigation instanceof YukkuriGroundNavigation ground && !ground.canPlanApproach()) return;
        failed.clear();
        source = null;
        plannedAt = body.level().getGameTime();
        // Vanilla's ten random stand candidates, with the actual escape route tested for each.
        boolean restricted = GoalUtils.mobRestricted(body, 6);
        for (int i = 0; i < 10; i++) {
            var offset = RandomPos.generateRandomDirection(body.getRandom(), 6, 3);
            var stand = LandRandomPos.generateRandomPosTowardDirection(body, 6, restricted, offset);
            if (stand == null || stand.equals(body.blockPosition()) || GoalUtils.hasMalus(body, stand)) continue;
            var path = navigation.createPath(stand, 0);
            if (path != null && path.canReach() && SurfaceApproach.startRetreat(body, path, 1.2)) {
                route = navigation.getPath();
                return;
            }
        }
        // Ten random stands can all miss in a tight yard; sweep the compass, away from a burning block when one is close.
        var hot = hotSpot();
        var origin = hot != null ? hot : body.position().add(body.getRandom().nextDouble() - .5, 0, body.getRandom().nextDouble() - .5);
        var heading = away(origin);
        for (double stride : STRIDES) {
            for (int angle = 0; angle < 360; angle += 45) {
                var ray = heading.rotate(angle);
                var stand = SurfaceApproach.retreatFloor(body, body.position().add(ray.x() * 6 * stride, 0, ray.z() * 6 * stride));
                if (stand == null || BlockPos.containing(stand).equals(body.blockPosition())
                        || hot != null && FlightCourse.passesNearThreat(body.getX(), body.getZ(), stand.x, stand.z, hot.x, hot.z, 1.5)) continue;
                var path = navigation.createPath(BlockPos.containing(stand), 0);
                if (path != null && path.canReach() && SurfaceApproach.startRetreat(body, path, 1.2)) {
                    route = navigation.getPath();
                    return;
                }
            }
        }
        noExit(null, null);
    }

    /** The nearest fire or lava cell within three blocks, which a burnt body runs away from. */
    private Vec3 hotSpot() {
        Vec3 nearest = null;
        double best = Double.MAX_VALUE;
        for (var pos : BlockPos.betweenClosed(body.blockPosition().offset(-3, -1, -3), body.blockPosition().offset(3, 2, 3))) {
            var state = body.level().getBlockState(pos);
            if (!(state.getBlock() instanceof BaseFireBlock) && !state.getFluidState().is(FluidTags.LAVA)) continue;
            var centre = Vec3.atCenterOf(pos);
            double distance = centre.distanceToSqr(body.position());
            if (distance < best) { best = distance; nearest = centre; }
        }
        return nearest;
    }

    public boolean cornered(LivingEntity danger) {
        if (!sameFailedConditions(danger)) return false;
        return danger == null ? failed.cornered(null, body.position(), null)
                : danger.isAlive() && danger.level() == body.level() && body.distanceToSqr(danger) <= 576
                    && failed.cornered(danger.getUUID(), body.position(), danger.position());
    }

    boolean fleeBlocked(LivingEntity danger) {
        return holdsFailure(danger, danger.position(), null);
    }

    private boolean holdsFailure(LivingEntity threat, Vec3 danger, Vec3 destination) {
        return sameFailedConditions(threat) && failed.holds(threat == null ? null : threat.getUUID(), body.position(), danger, destination);
    }

    private boolean sameFailedConditions(LivingEntity threat) {
        if (failed.feet() == null || body.getBbWidth() != failedWidth || body.getBbHeight() != failedHeight) return false;
        var keeper = threat == null ? null : keeper(threat);
        return failed.stillApplies(body.level().getGameTime(), keeper == null ? null : keeper.getUUID(), threat == null ? null : refugeStand());
    }

    /** Vanilla notifies even idle navigations when a block's collision changes, including opening a door. */
    public void collisionChanged(BlockPos pos) {
        var feet = failed.feet();
        if (feet != null && pos.closerToCenterThan(feet, TravelRange.PATH_RANGE)) failed.clear();
    }

    public void forgetRoute() {
        // A panic/goal handoff can still be the same pursuer. Anonymous panics and evictions end with their goal.
        if (failed.cornered(null, body.position(), null)) failed.clear();
        source = null;
        route = null;
        plannedAt = Long.MIN_VALUE / 2;
    }

    void threatLost() { failed.clear(); }

    private void noExit(Vec3 danger, Vec3 destination) {
        failedWidth = body.getBbWidth();
        failedHeight = body.getBbHeight();
        var keeper = source == null ? null : keeper(source);
        failed.remember(source == null ? null : source.getUUID(), body.position(), danger, destination,
                body.level().getGameTime(), keeper == null ? null : keeper.getUUID(), source == null ? null : refugeStand(),
                source == null && danger == null && destination == null ? FailedRetreat.SOURCELESS_RETRY_TICKS : FailedRetreat.RETRY_TICKS);
        route = null;
        SurfaceApproach.halt(body);
        GameplayLog.limited(body, "threat", "flight.cornered", 100, "flight cornered danger={}", source == null ? null : source.getUUID());
    }

    private void run(LivingEntity danger, Vec3 den) {
        long now = body.level().getGameTime();
        var dangerAt = danger.position();
        var navigation = body.getNavigation();
        if (danger != shieldedFrom) { shieldedFrom = null; shielded = null; }
        if (holdsFailure(danger, dangerAt, den)) return;
        boolean changed = source != danger;
        var away = away(den == null ? dangerAt : dangerAt.lerp(den, .5));
        if (!changed && now - plannedAt < 40 && route != null && !navigation.isDone() && route == navigation.getPath()) {
            if (dangerAt.distanceToSqr(checkedDanger) < .25) return;
            if (!FlightCourse.shouldReplan(now - plannedAt, false, false, heading, away)
                    && clearsThreat(route, dangerAt)) {
                checkedDanger = dangerAt;
                return;
            }
        }
        // Knockback can leave a grounded runner airborne between deciding to flee and starting the goal.
        if (navigation instanceof YukkuriGroundNavigation ground && !ground.canPlanApproach()) return;
        failed.clear();
        if (changed || heading.dot(away) < .25) heading = away;
        var stalled = !changed && route != null && navigation.isStuck() ? route.getTarget() : null;
        source = danger;
        checkedDanger = dangerAt;
        plannedAt = now;
        route = den == null ? refuge(danger) : null;
        if (route == null || route.getTarget().equals(stalled)) route = fan(dangerAt, 9, heading, stalled, shielded);
        if (route == null && heading.dot(away) < .99) route = fan(dangerAt, 9, away, stalled, shielded);
        if (route == null || !SurfaceApproach.startRetreat(body, route, body.newbornStage() ? .8 : 1.2)) { noExit(dangerAt, den); return; }
        var end = Vec3.atBottomCenterOf(route.getTarget());
        heading = FlightCourse.awayUnit(end.x, end.z, body.getX(), body.getZ(), Math.toRadians(body.getYRot()));
        route = navigation.getPath();
        GameplayLog.limited(body, "threat", "flight.away", 100, "flight away stand={} danger={} distance={}", () -> new Object[]{
                    BlockPos.containing(end), danger.getUUID(), Math.round(danger.distanceTo(body))});
    }
}
