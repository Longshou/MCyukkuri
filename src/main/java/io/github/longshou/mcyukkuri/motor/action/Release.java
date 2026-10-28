package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Put a carried body down where it actually hangs (spec 9.4 and 5.4): the one-tick dismount that keeps the measured feet
 * and hands the rest to vanilla physics. Never moves the body to a landing spot; when there is no room the caller turns
 * or steps and tries again.
 */
public final class Release implements Action {
    public static final String ID = "release";
    /** How far below the carried feet support or water has to exist (blocks). */
    private static final double DROP = 1.5;

    private final YukkuriEntity child;

    public Release(YukkuriEntity child) {
        if (child == null) throw new IllegalArgumentException("Release needs the carried body");
        this.child = child;
    }

    @Override public String id() { return ID; }

    @Override public Feasibility check(YukkuriEntity carrier) {
        if (!child.isAlive()) return Feasibility.of(Reason.TARGET_GONE, 0, "child_gone");
        if (child.getVehicle() != carrier) return Feasibility.of(Reason.NO_CONTACT, 0, "not_held");
        if (child.level().getBlockCollisions(child, child.getBoundingBox()).iterator().hasNext())
            return Feasibility.of(Reason.NO_ROOM, 0, "blocks");
        if (!ground(child)) return Feasibility.of(Reason.NO_ROOM, 0, "no_support");
        return Feasibility.pass();
    }

    @Override public void start(YukkuriEntity carrier) {}

    @Override public Progress tick(YukkuriEntity carrier) {
        if (child.getVehicle() != carrier) return new Outcome.Done("already_free");
        Motor.dismount(child, child.position(), Vec3.ZERO, "release");
        return new Outcome.Done("released");
    }

    @Override public boolean interruptible() { return true; }

    @Override public void abort(YukkuriEntity carrier, String why) {}

    @Override public MotionPose pose() { return MotionPose.NONE; }

    /** Whether support or water is within {@link #DROP} below the carried feet: the released body lands or falls into water. */
    private static boolean ground(YukkuriEntity child) {
        Vec3 feet = child.position();
        Vec3 below = feet.subtract(0, DROP, 0);
        HitResult hit = child.level().clip(new ClipContext(feet, below, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, child));
        return hit.getType() != HitResult.Type.MISS || child.level().containsAnyLiquid(new AABB(below, feet));
    }
}
