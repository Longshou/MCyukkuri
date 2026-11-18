package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriGroundNavigation;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.geom.FlightCourse;
import io.github.longshou.mcyukkuri.motor.geom.FlightCourse.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/** Selects and holds a dry route that keeps clear of the pursuer. */
public final class FlightPlanner {
    private static final int[] FAN = {0, 25, -25, 50, -50, 80, -80, 100, -100};
    private static final double[] STRIDES = {1, .6, .3};
    private static final double CLEARANCE = 3.8;
    private final YukkuriEntity body;
    private LivingEntity source;
    private Direction heading;
    private long plannedAt;
    private Path route;
    private Vec3 checkedDanger;

    FlightPlanner(YukkuriEntity body) { this.body = body; }

    private Direction away(Vec3 danger) {
        return FlightCourse.awayUnit(body.getX(), body.getZ(), danger.x, danger.z, Math.toRadians(body.getYRot()));
    }

    private Path routeTo(Vec3 stand, Vec3 danger) {
        if (stand == null || FlightCourse.passesNearThreat(body.getX(), body.getZ(), stand.x, stand.z, danger.x, danger.z, CLEARANCE))
            return null;
        var path = body.getNavigation().createPath(BlockPos.containing(stand), 0);
        if (path == null || !path.canReach()) return null;
        if (clearsThreat(path, danger)) return path;
        // In its starting cell the runner can already be farther from danger than the node centre.
        if (path.getNextNodeIndex() != 0 || path.getNodeCount() < 2 || !path.getNodePos(0).equals(body.blockPosition())
                || !SurfaceApproach.clearLastStep(body, path.getEntityPosAtNode(body, 1))) return null;
        var direct = path.copy();
        direct.advance();
        return clearsThreat(direct, danger) ? direct : null;
    }

    boolean clearsThreat(Path path, Vec3 danger) {
        double x = body.getX(), z = body.getZ();
        double clearance = FlightCourse.corridorClearance(Math.hypot(x - danger.x, z - danger.z), CLEARANCE);
        for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
            var next = path.getEntityPosAtNode(body, i);
            if (FlightCourse.segmentDistance(x, z, next.x, next.z, danger.x, danger.z) < clearance) return false;
            x = next.x;
            z = next.z;
        }
        return true;
    }

    private Path fan(Vec3 danger, double reach, Direction direction, BlockPos stalled) {
        Path best = null;
        double score = Double.NEGATIVE_INFINITY;
        for (int angle : FAN) {
            var ray = direction.rotate(angle);
            for (double stride : STRIDES) {
                double distance = reach * stride;
                var near = body.position().add(ray.x() * distance, 0, ray.z() * distance);
                var stand = SurfaceApproach.restingFloor(body, near);
                if (stand == null || BlockPos.containing(stand).equals(stalled)) continue;
                double candidate = FlightCourse.scoreCandidate(body.getX(), body.getZ(), stand.x, stand.z,
                        danger.x, danger.z, direction);
                if (candidate <= score) continue;
                var path = routeTo(stand, danger);
                if (path != null) { best = path; score = candidate; }
            }
        }
        return best;
    }

    public Vec3 destination(Vec3 danger, double reach) {
        var path = retreatRoute(danger, reach);
        return path == null ? null : Vec3.atBottomCenterOf(path.getTarget());
    }

    public Path retreatRoute(Vec3 danger, double reach) {
        return fan(danger, reach, away(danger), null);
    }

    public void retreatToward(Vec3 danger, Vec3 anchor) {
        double distance = body.position().distanceTo(anchor);
        if (distance < 1.5) return;
        var direction = FlightCourse.awayUnit(anchor.x, anchor.z, body.getX(), body.getZ(), Math.toRadians(body.getYRot()));
        var path = fan(danger, Math.min(9, distance), direction, null);
        if (path != null) body.getNavigation().moveTo(path, 1.2);
    }

    private Path refuge(LivingEntity danger) {
        var parent = ParentCarry.flightEscort(body, danger);
        if (parent != null) {
            var path = routeTo(parent.position(), danger.position());
            if (path != null) return path;
        }
        var ownerId = body.pet().owner();
        if (ownerId != null && ((ServerLevel) body.level()).getEntity(ownerId) instanceof LivingEntity keeper
                && keeper.isAlive() && !keeper.isSpectator() && keeper.distanceToSqr(body) <= 256 && keeper.distanceToSqr(danger) > 36) {
            var path = routeTo(keeper.position(), danger.position());
            if (path != null) return path;
        }
        var home = body.life().homeStand();
        return home == null ? null : routeTo(Vec3.atBottomCenterOf(home), danger.position());
    }

    boolean hasExit(LivingEntity danger) {
        if (body.getNavigation() instanceof YukkuriGroundNavigation ground && !ground.canPlanApproach()) {
            var landing = SurfaceApproach.landingStand(body);
            if (landing == null) return false;
            var away = away(danger.position());
            for (int angle : FAN) {
                var ray = away.rotate(angle);
                var step = SurfaceApproach.restingFloor(body, landing.add(ray.x(), 0, ray.z()));
                if (step != null && !FlightCourse.passesNearThreat(landing.x, landing.z, step.x, step.z,
                        danger.getX(), danger.getZ(), CLEARANCE) && SurfaceApproach.clearLastStep(body, landing, step)) return true;
            }
            return false;
        }
        return refuge(danger) != null || destination(danger.position(), 5) != null;
    }

    public void flee(LivingEntity danger) {
        long now = body.level().getGameTime();
        var dangerAt = danger.position();
        var navigation = body.getNavigation();
        boolean changed = source != danger;
        if (!changed && now - plannedAt < 40 && (route == null || !navigation.isDone() && route == navigation.getPath())) {
            if (dangerAt.distanceToSqr(checkedDanger) < .25) return;
            var away = away(dangerAt);
            if (!FlightCourse.shouldReplan(now - plannedAt, false, false, heading, away)
                    && (route == null || clearsThreat(route, dangerAt))) {
                checkedDanger = dangerAt;
                return;
            }
        }
        // Knockback can leave a grounded runner airborne between deciding to flee and starting the goal.
        if (navigation instanceof YukkuriGroundNavigation ground && !ground.canPlanApproach()) return;
        var away = away(dangerAt);
        if (changed || heading.dot(away) < .25) heading = away;
        var stalled = !changed && route != null && navigation.isStuck() ? route.getTarget() : null;
        source = danger;
        checkedDanger = dangerAt;
        plannedAt = now;
        route = refuge(danger);
        if (route == null || route.getTarget().equals(stalled)) route = fan(dangerAt, 9, heading, stalled);
        if (route == null && heading.dot(away) < .99) route = fan(dangerAt, 9, away, stalled);
        if (route == null) { navigation.stop(); return; }
        var end = Vec3.atBottomCenterOf(route.getTarget());
        heading = FlightCourse.awayUnit(end.x, end.z, body.getX(), body.getZ(), Math.toRadians(body.getYRot()));
        if (!navigation.moveTo(route, body.newbornStage() ? .8 : 1.2)) { route = null; return; }
        route = navigation.getPath();
        GameplayLog.limited(body, "threat", "flight.away", 100, "flight away stand={} danger={} distance={}",
                BlockPos.containing(end), danger.getUUID(), Math.round(danger.distanceTo(body)));
    }
}
