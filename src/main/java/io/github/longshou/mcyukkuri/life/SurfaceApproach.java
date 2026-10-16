package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Last-step steering is permitted only on a continuous, clear, supported surface. */
public final class SurfaceApproach {
    private SurfaceApproach() {}

    public static boolean supported(YukkuriEntity actor, Vec3 feet) {
        var level = actor.level();
        if (!level.hasChunkAt(BlockPos.containing(feet)) || !level.getFluidState(BlockPos.containing(feet)).isEmpty()) return false;
        AABB body = actor.getBoundingBox().move(feet.subtract(actor.position())).deflate(.005);
        if (!level.noCollision(actor, body)) return false;
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
        if (!actor.onGround() || Math.abs(start.y-destination.y) > .08 || start.distanceToSqr(destination) > 2.25) return false;
        int samples = Math.max(1, (int)Math.ceil(start.distanceTo(destination)/.10));
        for (int i=0; i<=samples; i++) if (!supported(actor, start.lerp(destination, (double)i/samples))) return false;
        return true;
    }

    public static boolean arrived(YukkuriEntity actor, Vec3 destination) {
        // Two actors may stop on opposite edges of this tolerance. Keep their combined error
        // inside the shared mouth envelope, rather than accepting two individually "near" bodies.
        return Math.abs(actor.getY()-destination.y)<.08 && actor.position().subtract(destination).horizontalDistanceSqr()<.0004;
    }

    /** Never substitute a straight line for a route around a step or obstruction. */
    public static boolean walk(YukkuriEntity actor, Vec3 destination, double speed) {
        if (arrived(actor, destination)) { halt(actor); return true; }
        if (clearLastStep(actor, destination)) {
            actor.getNavigation().stop();
            double remaining = actor.position().distanceTo(destination);
            actor.getMoveControl().setWantedPosition(destination.x, destination.y, destination.z,
                    Math.min(speed, Math.max(.12, remaining*2)));
        } else if (actor.getNavigation().isDone() || actor.tickCount%10==0) {
            actor.life().navigate(BlockPos.containing(destination), speed, false);
        }
        return false;
    }

    public static void halt(YukkuriEntity actor) {
        actor.getNavigation().stop();
        actor.getMoveControl().setWantedPosition(actor.getX(), actor.getY(), actor.getZ(), 0);
        actor.setSpeed(0);
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
