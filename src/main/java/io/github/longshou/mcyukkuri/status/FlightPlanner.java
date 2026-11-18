package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.geom.FlightCourse;
import io.github.longshou.mcyukkuri.motor.geom.FlightCourse.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/** Selects a dry, reachable retreat. A moving threat only breaks the commitment when it flanks the runner. */
public final class FlightPlanner {
    private static final int[] FAN = {0, 25, -25, 50, -50, 80, -80};
    private final YukkuriEntity body;
    private LivingEntity source;
    private Direction heading;
    private long plannedAt;
    private Path route;

    FlightPlanner(YukkuriEntity body) { this.body = body; }

    private Direction away(Vec3 danger) {
        return FlightCourse.awayUnit(body.getX(), body.getZ(), danger.x, danger.z, Math.toRadians(body.getYRot()));
    }

    private Path routeTo(Vec3 stand, Vec3 danger) {
        if (stand == null || FlightCourse.passesNearThreat(body.getX(), body.getZ(), stand.x, stand.z, danger.x, danger.z, 3.8))
            return null;
        var path = body.getNavigation().createPath(BlockPos.containing(stand), 0);
        if (path == null || !path.canReach()) return null;
        var previous = body.position();
        double clearance = Math.min(3.8, Math.hypot(previous.x - danger.x, previous.z - danger.z) - .35);
        for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
            var next = path.getEntityPosAtNode(body, i);
            if (FlightCourse.segmentDistance(previous.x, previous.z, next.x, next.z, danger.x, danger.z) < clearance)
                return null;
            previous = next;
        }
        return path;
    }

    private Path fan(Vec3 danger, double reach, Direction direction) {
        Path best = null;
        double score = Double.NEGATIVE_INFINITY;
        for (int angle : FAN) {
            var ray = direction.rotate(angle);
            for (double distance : new double[]{reach, reach * .6}) {
                var near = body.position().add(ray.x() * distance, 0, ray.z() * distance);
                var stand = SurfaceApproach.restingFloor(body, near);
                if (stand == null) continue;
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
        var path = fan(danger, reach, away(danger));
        return path == null ? null : Vec3.atBottomCenterOf(path.getTarget());
    }

    public void retreatToward(Vec3 danger, Vec3 anchor) {
        var direction = FlightCourse.awayUnit(anchor.x, anchor.z, body.getX(), body.getZ(), Math.toRadians(body.getYRot()));
        var path = fan(danger, Math.min(9, body.position().distanceTo(anchor)), direction);
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
        return refuge(danger) != null || destination(danger.position(), 5) != null;
    }

    public void flee(LivingEntity danger) {
        long now = body.level().getGameTime();
        var away = away(danger.position());
        boolean changed = source != danger;
        if (!changed && !FlightCourse.shouldReplan(now - plannedAt, route != null && route.isDone(),
                route != null && body.getNavigation().isDone(), heading, away)) return;
        if (changed || heading.dot(away) < .25) heading = away;
        source = danger;
        plannedAt = now;
        route = refuge(danger);
        if (route == null) route = fan(danger.position(), 9, heading);
        if (route == null) { body.getNavigation().stop(); return; }
        var end = Vec3.atBottomCenterOf(route.getTarget());
        heading = FlightCourse.awayUnit(end.x, end.z, body.getX(), body.getZ(), Math.toRadians(body.getYRot()));
        body.getNavigation().moveTo(route, body.newbornStage() ? .8 : 1.2);
        GameplayLog.limited(body, "threat", "flight.away", 100, "flight away stand={} danger={} distance={}",
                route.getTarget(), danger.getUUID(), Math.round(danger.distanceTo(body)));
    }
}
