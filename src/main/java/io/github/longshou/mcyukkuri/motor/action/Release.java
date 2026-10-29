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
 * the tail ({@code ParentCarry.lower}), then the dismount keeps the measured feet and hands the rest to vanilla physics.
 * Never moves the body to a landing spot; when there is no room the caller turns or steps and tries again.
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

    @Override public void start(YukkuriEntity carrier) { ParentCarry.lower(carrier, child); }

    @Override public Progress tick(YukkuriEntity carrier) {
        if (child.getVehicle() != carrier) return new Outcome.Done("already_free");
        float down = ParentCarry.lowering(carrier, child, 0);
        if (down >= 0 && down < 1) return new Running(1 - down, TravelMode.CONSUMED);
        Motor.dismount(child, child.position(), Vec3.ZERO, "release");
        return new Outcome.Done("released");
    }

    /**
     * The same release at once, for a carrier that sets several riders down in one decision, or for an interruption, a death
     * or an ended intent ({@code forced}: there is no decision left to turn or step in, so the room check is skipped and the
     * body comes down where it hangs, spec 5.4). Returns the check that applied; nothing moves when it refused.
     */
    public static Feasibility now(YukkuriEntity carrier, YukkuriEntity child, boolean forced, String cause) {
        var room = forced ? Feasibility.pass() : new Release(child).check(carrier);
        if (room.ok() && child.getVehicle() == carrier) Motor.dismount(child, child.position(), Vec3.ZERO, cause);
        return room;
    }

    /** Whether support or water is within {@link #DROP} below the carried feet: the released body lands or falls into water. */
    private static boolean ground(YukkuriEntity child) {
        Vec3 feet = child.position();
        Vec3 below = feet.subtract(0, DROP, 0);
        HitResult hit = child.level().clip(new ClipContext(feet, below, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, child));
        return hit.getType() != HitResult.Type.MISS || child.level().containsAnyLiquid(new AABB(below, feet));
    }
}
