package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Put a carried body down (spec 9.4 and 5.4): a child on the brim or a tail is first lowered to the ground with the braid or
 * the tail ({@code ParentCarry.lower}, a stretch that is not cut short), then the dismount keeps the measured feet and hands
 * the rest to vanilla physics. Never moves the body to a landing spot; the check is made where it would be let go, and when
 * there is no room the caller turns or steps and tries again. Every dismount ends in {@code ParentCarry.released}.
 */
public final class Release implements Action {
    public static final String ID = "release";
    /** How far below the let-go feet support or water has to exist (blocks). */
    private static final double DROP = 1.5;

    private final YukkuriEntity child;
    private YukkuriEntity carrier;

    public Release(YukkuriEntity child) {
        if (child == null) throw new IllegalArgumentException("Release needs the carried body");
        this.child = child;
    }

    @Override public String id() { return ID; }

    /** The body being set down. */
    public YukkuriEntity child() { return child; }

    @Override public Feasibility check(YukkuriEntity carrier) {
        if (!child.isAlive()) return Feasibility.of(Reason.TARGET_GONE, 0, "child_gone");
        if (child.getVehicle() != carrier) return Feasibility.of(Reason.NO_CONTACT, 0, "not_held");
        Vec3 at = ParentCarry.letGoPoint(carrier, child);
        if (child.level().getBlockCollisions(child, child.getDimensions(child.getPose()).makeBoundingBox(at)).iterator().hasNext())
            return Feasibility.of(Reason.NO_ROOM, 0, "blocks");
        if (!ground(child, at)) return Feasibility.of(Reason.NO_ROOM, 0, "no_support");
        return Feasibility.pass();
    }

    @Override public void start(YukkuriEntity carrier) {
        this.carrier = carrier;
        ParentCarry.lower(carrier, child);
    }

    @Override public Progress tick(YukkuriEntity carrier) {
        if (child.getVehicle() != carrier) return new Outcome.Done("already_free");
        float down = ParentCarry.lowering(carrier, child, 0);
        if (down >= 0 && down < 1) return new Running(1 - down, TravelMode.CONSUMED);
        let(carrier, child, "release");
        return new Outcome.Done("released");
    }

    /** A child on its way down with the braid or the tail is not put back: the lowering runs to the ground. */
    @Override public boolean interruptible() { return carrier == null || ParentCarry.lowering(carrier, child, 0) < 0; }

    /**
     * The same release at once, for an interruption, a death or an ended intent ({@code forced}: there is no decision left to
     * turn or step in, so the room check is skipped and the body comes down where it is, spec 5.4). Returns the check that
     * applied; nothing moves when it refused.
     */
    public static Feasibility now(YukkuriEntity carrier, YukkuriEntity child, boolean forced, String cause) {
        var room = forced ? Feasibility.pass() : new Release(child).check(carrier);
        if (room.ok() && child.getVehicle() == carrier) let(carrier, child, cause);
        return room;
    }

    /**
     * The carry side of the MOTOR interrupt bridge: an interrupted carrier lets every child go where it is, and an interrupted
     * held child (anything but a fright) lets go of its carrier.
     */
    public static void interrupted(YukkuriEntity body, String why) {
        for (var rider : body.getPassengers()) if (rider instanceof YukkuriEntity child) let(body, child, "carry_" + why);
        if (!why.equals("fright") && body.getVehicle() instanceof YukkuriEntity carrier) let(carrier, body, "carry_child_" + why);
    }

    private static void let(YukkuriEntity carrier, YukkuriEntity child, String cause) {
        Motor.dismount(child, child.position(), Vec3.ZERO, cause);
        ParentCarry.released(carrier, child, cause);
    }

    /** Whether support or water is within {@link #DROP} below the let-go feet: the released body lands or falls into water. */
    private static boolean ground(YukkuriEntity child, Vec3 feet) {
        Vec3 below = feet.subtract(0, DROP, 0);
        HitResult hit = child.level().clip(new ClipContext(feet, below, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, child));
        return hit.getType() != HitResult.Type.MISS || child.level().containsAnyLiquid(new AABB(below, feet));
    }
}
