package io.github.longshou.mcyukkuri.motor;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.geom.ArcSolver;
import io.github.longshou.mcyukkuri.motor.geom.Gait;
import io.github.longshou.mcyukkuri.play.PlayBlock;
import io.github.longshou.mcyukkuri.play.PlayGeometry;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Facility approach and interior movement driven through the body's own gait in {@code motor/}.
 * Never slides backwards or strafes on a sharp reversal: turns first, then moves forward.
 */
public final class PlayMotion {
    /** Maximum heading mismatch (degrees) before moving forward; wider angles turn in place first. */
    private static final double TURN_FIRST_DEG = 35.0;
    private static final double SLIDE_TURN_DEG = 45.0;
    /** How far in front of the mouth the ground navigation stops, a little over a body width of open ground. */
    private static final double STANDOFF = 1.0;

    private PlayMotion() {}

    /**
     * Builds shell-clearing ground waypoints from {@code body}'s current position to an open ground standoff
     * {@link #STANDOFF} in front of the mouth, outside the facility footprint {@code [-halfW, +halfW] x [-halfD, +halfD]} and
     * aligned with {@code entryLocal}. Ground navigation refuses a last step beside the shell, so it never aims there: callers
     * append {@code entryLocal} and the interior route, which the body's own gait ({@link #routeStep}) walks from the standoff.
     */
    public static List<Vec3> approachWaypoints(YukkuriEntity body, BlockPos root, Direction facing,
                                               double halfW, double halfD, Vec3 entryLocal) {
        var out = new ArrayList<Vec3>();
        Vec3 loc = PlayGeometry.local(root, facing, body.position());
        double margin = body.getBbWidth() * 0.5 + 0.55;
        double padW = halfW + margin;
        double padD = halfD + margin;
        // Ensure the final ground-navigation waypoint sits outside any non-pathfindable cell and clears the facility shell.
        Vec3 entryWorld = PlayGeometry.world(root, facing, entryLocal.x, entryLocal.y, entryLocal.z);
        boolean entryClipped = PlayBlock.facility(body.level(), BlockPos.containing(entryWorld)) != null
                || !body.level().noCollision(body, body.getBoundingBox().move(entryWorld.subtract(body.position())));
        double outerZ = entryClipped
                ? (entryLocal.z >= 0 ? 1.0 : -1.0) * (halfD + margin)
                : entryLocal.z;
        Vec3 outerLocal = new Vec3(entryLocal.x, 0, outerZ + (entryLocal.z >= 0 ? STANDOFF : -STANDOFF));
        boolean needDetour = segmentHitsBox(loc.x, loc.z, outerLocal.x, outerLocal.z, padW - 0.15, padD - 0.15);
        if (needDetour) {
            double side = loc.x >= 0 ? 1.0 : -1.0;
            boolean farEnd = (loc.z * outerLocal.z < 0) || Math.abs(loc.z) < padD;
            if (farEnd && Math.abs(loc.x) < padW) {
                addIfClear(body, out, PlayGeometry.world(root, facing, side * padW, 0, Math.clamp(loc.z, -padD, padD)));
            }
            addIfClear(body, out, PlayGeometry.world(root, facing, side * padW, 0, outerLocal.z));
        }
        out.add(PlayGeometry.world(root, facing, outerLocal.x, outerLocal.y, outerLocal.z));
        return out;
    }

    private static void addIfClear(YukkuriEntity body, List<Vec3> out, Vec3 candidate) {
        var level = body.level();
        BlockPos pos = BlockPos.containing(candidate);
        if (!level.hasChunkAt(pos)) return;
        if (!level.getFluidState(pos).isEmpty()) return;
        if (!level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) return;
        var box = body.getBoundingBox().move(candidate.subtract(body.position()));
        if (level.noCollision(body, box)) out.add(candidate);
    }

    /** 2D Liang-Barsky slab intersection of segment (x0,z0)->(x1,z1) with [-hx,+hx] x [-hz,+hz]. */
    private static boolean segmentHitsBox(double x0, double z0, double x1, double z1, double hx, double hz) {
        double dx = x1 - x0, dz = z1 - z0;
        double t0 = 0.0, t1 = 1.0;
        double[] p = {-dx, dx, -dz, dz};
        double[] q = {x0 + hx, hx - x0, z0 + hz, hz - z0};
        for (int i = 0; i < 4; i++) {
            if (Math.abs(p[i]) < 1e-9) {
                if (q[i] < 0) return false;
            } else {
                double t = q[i] / p[i];
                if (p[i] < 0) { if (t > t1) return false; if (t > t0) t0 = t; }
                else { if (t < t0) return false; if (t < t1) t1 = t; }
            }
        }
        return t0 <= t1;
    }

    /**
     * One tick along an authored interior facility route: turns to face {@code target} first (never strafes or slides
     * backwards) and moves at the body's own walk speed scaled by {@code effort}. Assists low-lip step-up when needed.
     * Returns {@code true} while still turning in place before stepping forward.
     */
    public static boolean routeStep(YukkuriEntity body, Vec3 target, double effort) {
        body.getNavigation().stop();
        body.getMoveControl().setWantedPosition(body.getX(), body.getY(), body.getZ(), 0);
        Vec3 delta = target.subtract(body.position()).multiply(1, 0, 1);
        double dist = delta.length();
        if (dist <= 1e-4) return false;
        float targetYaw = SurfaceApproach.yaw(body.position(), target);
        Motor.face(body, targetYaw);
        double yawGap = Math.abs(Mth.wrapDegrees(targetYaw - body.yBodyRot));
        if (yawGap > TURN_FIRST_DEG) {
            Motor.velocity(body, new Vec3(0, body.getDeltaMovement().y, 0), "play_turn");
            return true;
        }
        double align = Math.cos(Math.toRadians(yawGap));
        double pace = BodyAbility.of(body).walkSpeed() * BodyAbility.carrying(body) * effort * align
                * body.species().stride(effort);
        double speed = Math.min(Math.min(pace, Gait.HUMAN_WALK), dist);
        Vec3 step = delta.scale(speed / dist);
        body.motor().propulsion(step.x, 0, step.z, Math.min(pace, Gait.HUMAN_WALK));
        if (body.onGround() && body.horizontalCollision && target.y > body.getY() - 0.02 && !BodyAbility.cannotSpring(body)) {
            double rise = Math.clamp((target.y - body.getY()) + 0.22, 0.22, BodyAbility.of(body).hopHeight());
            Motor.takeoff(body, new Vec3(step.x, ArcSolver.vyFor(rise), step.z), "play_step_up");
            return false;
        }
        Motor.velocity(body, new Vec3(step.x, body.getDeltaMovement().y, step.z), "play_route");
        return false;
    }

    /** Downhill slide glide along the chute, turning forward first and capped by the body's gait ceiling. */
    public static boolean slideDescent(YukkuriEntity body, Vec3 target) {
        body.getNavigation().stop();
        body.getMoveControl().setWantedPosition(body.getX(), body.getY(), body.getZ(), 0);
        Vec3 delta = target.subtract(body.position()).multiply(1, 0, 1);
        double dist = delta.length();
        if (dist <= 1e-4) return false;
        float targetYaw = SurfaceApproach.yaw(body.position(), target);
        Motor.face(body, targetYaw);
        double yawGap = Math.abs(Mth.wrapDegrees(targetYaw - body.yBodyRot));
        if (yawGap > SLIDE_TURN_DEG) {
            Motor.velocity(body, new Vec3(0, body.getDeltaMovement().y, 0), "play_turn");
            return true;
        }
        double pace = Math.min(Gait.HUMAN_WALK, Math.max(0.13, BodyAbility.of(body).walkSpeed() * 1.35));
        Vec3 step = delta.scale(Math.min(pace, dist) / dist);
        double vy = body.onGround() && body.horizontalCollision ? 0.14 : body.getDeltaMovement().y;
        Motor.velocity(body, new Vec3(step.x, vy, step.z), "slide_momentum");
        return false;
    }

    /** Turn smoothly to face {@code target} in place without moving. */
    public static void faceToward(YukkuriEntity body, Vec3 target) {
        Vec3 delta = target.subtract(body.position()).multiply(1, 0, 1);
        if (delta.lengthSqr() > 1e-4) Motor.face(body, SurfaceApproach.yaw(body.position(), target));
    }
}
