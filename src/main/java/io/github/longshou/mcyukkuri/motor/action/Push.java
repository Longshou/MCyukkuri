package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.motor.geom.Effort;
import java.util.Locale;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Push a body or a hat away for one {@code push} clip (spec 9.4, Q5): touching it (mouth reach beyond the two envelopes), each
 * tick it gains the pusher's pace times the pusher's share of the two box volumes, along the line between them. A body takes it
 * through {@link Motor}; anything else only when it is pushable (a hat afloat, whose behaviour integrates it), else
 * {@code TOO_HEAVY}.
 */
public final class Push implements Action {
    public static final String ID = "push";

    private final Entity target;
    private double gain;
    private int t, length;

    public Push(Entity target) { this.target = target; }

    @Override public String id() { return ID; }

    @Override public Feasibility check(YukkuriEntity body) {
        if (!target.isAlive() || target.level() != body.level()) return Feasibility.of(Reason.TARGET_GONE, 0, "target_gone");
        if (!(target instanceof YukkuriEntity) && !target.isPushable()) return Feasibility.of(Reason.TOO_HEAVY, 0, "not_pushable");
        double gap = ContactGeometry.mouthGap(body, target) - ContactGeometry.GRAB_MARGIN;
        if (gap > 0) return Feasibility.of(Reason.NO_CONTACT, gap, String.format(Locale.ROOT, "gap=%.2f", gap));
        gain = BodyAbility.of(body).pace(body.isInWaterOrBubble()) * Effort.share(Right.mass(body), Right.mass(target));
        return Feasibility.pass();
    }

    @Override public void start(YukkuriEntity body) { length = ClipMarkers.of(ID).length(); }

    @Override public Progress tick(YukkuriEntity body) {
        if (!target.isAlive()) return Outcome.of(Reason.TARGET_GONE, 0, "target_gone");
        if (++t > length) return new Outcome.Done("pushed ticks=" + length);
        Vec3 away = target.position().subtract(body.position()).multiply(1, 0, 1);
        Vec3 push = away.lengthSqr() < 1E-8 ? Vec3.ZERO : away.normalize().scale(gain);
        if (target instanceof YukkuriEntity other) Motor.velocity(other, other.getDeltaMovement().add(push), "pushed");
        else target.push(push.x, 0, push.z);
        return new Running(length - t, TravelMode.PASS);
    }

    @Override public String startDetail() { return String.format(Locale.ROOT, "target=%s gain=%.3f", target.getUUID(), gain); }
}
