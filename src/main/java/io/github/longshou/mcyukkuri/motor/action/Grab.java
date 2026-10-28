package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import java.util.Locale;
import net.minecraft.world.phys.Vec3;

/**
 * Take a body into the carrier's hold (spec 9.4): the mouth must reach it, the carrier must be able to hold it at all
 * ({@code ParentCarry.capacityProblem}, unchanged), and it must not be in the air or in someone else's hold. After the
 * lift pose the target becomes a real passenger; nothing is moved to a seat, the vanilla passenger relation does that.
 */
public final class Grab implements Action {
    public static final String ID = "grab";
    /** How far the target may drift during the lift before the grab gives up (blocks). */
    private static final double DRIFT = .3;

    private final YukkuriEntity target;
    private Vec3 at;
    private int ticks;
    private long startTick;

    public Grab(YukkuriEntity target) {
        if (target == null) throw new IllegalArgumentException("Grab needs a target");
        this.target = target;
    }

    @Override public String id() { return ID; }

    @Override public Feasibility check(YukkuriEntity carrier) {
        if (!target.isAlive()) return Feasibility.of(Reason.TARGET_GONE, 0, "target_gone");
        if (target.level() != carrier.level()) return Feasibility.of(Reason.TARGET_GONE, 0, "target_level");
        AttachState attach = Attach.of(target);
        if (attach instanceof AttachState.Held held && held.carrier() != carrier)
            return Feasibility.of(Reason.NO_ROOM, 0, "target_held");
        if (attach instanceof AttachState.Airborne) return Feasibility.of(Reason.NO_CONTACT, 0, "target_airborne");
        String problem = ParentCarry.capacityProblem(carrier, target, false, false);
        if (problem != null) return refusal(problem);
        double gap = ContactGeometry.mouthGap(carrier, target);
        if (!ContactGeometry.inMouthReach(carrier, target))
            return Feasibility.of(Reason.NO_CONTACT, Math.max(0, gap - ContactGeometry.GRAB_MARGIN),
                    String.format(Locale.ROOT, "mouth_gap=%.2f", gap));
        return Feasibility.pass();
    }

    @Override public void start(YukkuriEntity carrier) {
        at = target.position();
        ticks = 0;
        startTick = carrier.level().getGameTime();
    }

    @Override public Progress tick(YukkuriEntity carrier) {
        double moved = target.position().distanceTo(at);
        if (moved > DRIFT) return Outcome.of(Reason.TARGET_MOVED, moved, String.format(Locale.ROOT, "moved=%.2f", moved));
        if (++ticks < ParentCarry.LIFT_TICKS) return new Running(ParentCarry.LIFT_TICKS - ticks, TravelMode.CONSUMED);
        if (!Motor.mount(target, carrier, "grab")) return Outcome.of(Reason.NO_CONTACT, 0, "mount_refused");
        target.fallDistance = 0;
        return new Outcome.Done("mounted target=" + target.getUUID());
    }

    /** The lift pose moves nothing, so the grab can be abandoned until the mount actually happens. */
    @Override public boolean interruptible() { return true; }

    @Override public void abort(YukkuriEntity carrier, String why) {}

    @Override public MotionPose pose() { return MotionPose.NONE; }

    /** The structural refusal vocabulary of {@code ParentCarry}, mapped onto the fixed outcome classes of spec 9.2. */
    private static Feasibility refusal(String problem) {
        Reason reason = switch (problem) {
            case "dead", "level" -> Reason.TARGET_GONE;
            case "too_big", "stage", "carrier_kind", "pregnant" -> Reason.TOO_HEAVY;
            default -> Reason.NO_ROOM;
        };
        return Feasibility.of(reason, 0, problem);
    }
}
