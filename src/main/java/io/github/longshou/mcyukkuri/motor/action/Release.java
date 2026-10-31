package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.family.ParentCarry.Hold;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.ContactGeometry.Reach;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Turn a mouth hold to clear ground if needed, lower the child, then dismount at its measured feet. */
public final class Release implements Action {
    public static final String ID = "release";
    /** How far below the let-go feet support or water has to exist (blocks). */
    private static final double DROP = 1.5;

    private final YukkuriEntity child;
    private YukkuriEntity carrier;
    private float facing;
    private Face turn;
    private Feasibility planned;

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
        if (planned == null) planned = plan(carrier);
        return planned;
    }

    private Feasibility plan(YukkuriEntity carrier) {
        facing = carrier.bodyYaw();
        var room = room(carrier, facing);
        if (room.ok() || ParentCarry.hold(carrier, child) != Hold.MOUTH) return room;
        // The mouth keeps the child in view while the shared Face turns toward the nearest clear landing.
        for (int i = 1; i <= 24; i++) {
            float yaw = carrier.bodyYaw() + (i + 1) / 2 * (i % 2 == 0 ? 15 : -15);
            if (room(carrier, yaw).ok() && clearTurn(carrier, yaw)) { facing = yaw; return Feasibility.pass(); }
        }
        return room;
    }

    private Feasibility room(YukkuriEntity carrier, float yaw) {
        Vec3 at = ParentCarry.letGoPoint(carrier, child, yaw);
        if (child.level().getBlockCollisions(child, child.getDimensions(child.getPose()).makeBoundingBox(at)).iterator().hasNext())
            return Feasibility.of(Reason.NO_ROOM, 0, "blocks");
        if (ParentCarry.hold(carrier, child) == Hold.MOUTH) {
            var host = ParentCarry.carryHost(carrier, child);
            if (host != null && carrier.getVehicle() != host.entity() && ParentCarry.pinned(carrier, child, at) > at.y)
                return Feasibility.of(Reason.NO_ROOM, 0, "carry_host");
            if (!carrier.level().getEntitiesOfClass(YukkuriEntity.class, child.getDimensions(child.getPose()).makeBoundingBox(at),
                    other -> other != carrier && other != child && !carrier.hasPassenger(other)).isEmpty()) return Feasibility.of(Reason.NO_ROOM, 0, "occupied");
        }
        if (!ground(child, at)) return Feasibility.of(Reason.NO_ROOM, 0, "no_support");
        return Feasibility.pass();
    }

    private boolean clearTurn(YukkuriEntity carrier, float yaw) {
        float span = Mth.wrapDegrees(yaw - carrier.bodyYaw());
        for (float step = 0; step <= Math.abs(span); step += 5) {
            var at = ParentCarry.mouthAt(carrier, child, carrier.bodyYaw() + Math.copySign(step, span));
            if (child.level().getBlockCollisions(child, child.getDimensions(child.getPose()).makeBoundingBox(at)).iterator().hasNext()) return false;
        }
        return true;
    }

    @Override public void start(YukkuriEntity carrier) {
        this.carrier = carrier;
        if (ParentCarry.hold(carrier, child) == Hold.MOUTH) { turn = new Face(facing); turn.start(carrier); }
        else ParentCarry.lower(carrier, child);
    }

    @Override public Progress tick(YukkuriEntity carrier) {
        if (child.getVehicle() != carrier) return new Outcome.Done("already_free");
        if (turn != null) {
            if (turn.tick(carrier) instanceof Running running) return running;
            var room = room(carrier, carrier.bodyYaw());
            if (!room.ok()) return Outcome.of(room.reason(), room.shortfall(), room.detail());
            turn = null;
            ParentCarry.lower(carrier, child);
        }
        float down = ParentCarry.lowering(carrier, child, 0);
        double remaining = child.position().distanceTo(ParentCarry.letGoPoint(carrier, child));
        if (down < 1 || remaining > .02) return new Running(Math.max(1 - down, remaining), TravelMode.CONSUMED);
        let(carrier, child, "release");
        return new Outcome.Done("released");
    }

    /** A child set down with the braid plays the braid clip's own set-down (place to release): the braid lowers it, opens and comes back. */
    @Override public MotionPose pose() {
        long since = carrier == null ? -1 : ParentCarry.setDownTick(carrier, child);
        return since < 0 || ParentCarry.hold(carrier, child).reach() != Reach.BRAID ? MotionPose.NONE
                : new MotionPose(Grab.BRAID_LIFT, since - ClipMarkers.of(Grab.BRAID_LIFT).ticks("place", 38), false);
    }

    /** A child on its way down with the braid or the tail is not put back: the lowering runs to the ground. */
    @Override public boolean interruptible() { return carrier == null || ParentCarry.lowering(carrier, child, 0) < 0; }

    /** The same release at once, for an interruption, a death or an ended intent ({@code forced}: the room check is skipped and the body comes down where it is). Returns that check. */
    public static Feasibility now(YukkuriEntity carrier, YukkuriEntity child, boolean forced, String cause) {
        var room = forced ? Feasibility.pass() : new Release(child).room(carrier, carrier.bodyYaw());
        if (room.ok() && child.getVehicle() == carrier) let(carrier, child, cause);
        return room;
    }

    /** The carry side of the interrupt bridge: an interrupted carrier lets every child go where it is, and an interrupted held child (bar a fright) lets go of its carrier. */
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
