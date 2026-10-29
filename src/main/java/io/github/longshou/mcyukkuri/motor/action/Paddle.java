package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.motor.geom.Effort;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import java.util.Locale;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Paddle the host one rides toward a point (spec 9.4): on the host, with a stick in the mouth or kept by the host. In the
 * {@code boat_paddle} clip's power stroke it drives the water toward the point at its swim pace and turns the hull at its turn
 * rate; the host's behaviour integrates both against the hull's mass and drag ({@link Effort#drive}). Each stroke costs energy;
 * none left is {@code EXHAUSTED}. Done once the host is within {@code arrive} of the point.
 */
public final class Paddle implements Action {
    public static final String ID = "paddle";
    private static final String CLIP = "boat_paddle";
    /** A stroke's energy: the old helm's one point per 80 ticks over a 1.6 s stroke. */
    private static final float COST = .4f;

    private final Vec3 target;
    private final double arrive;
    private SurfaceHost host;
    private double pace, turnRate;
    private int t, start, end, length;

    public Paddle(Vec3 target, double arrive) { this.target = target; this.arrive = arrive; }

    @Override public String id() { return ID; }

    @Override public Feasibility check(YukkuriEntity body) {
        if (!(Attach.of(body) instanceof AttachState.OnHost on)) return Feasibility.of(Reason.NO_SUPPORT, 0, "not_on_host");
        host = on.host();
        if (!body.getMouthItem().is(Items.STICK) && !host.holds(Items.STICK)) return Feasibility.of(Reason.NO_TOOL, 0, "no_stick");
        return body.life().energy() < COST ? Feasibility.of(Reason.EXHAUSTED, 0, "energy=" + body.life().energy()) : Feasibility.pass();
    }

    @Override public void start(YukkuriEntity body) {
        var ability = BodyAbility.of(body, host.load());
        pace = ability.swimSpeed();
        turnRate = ability.turnRate();
        var clip = ClipMarkers.of(CLIP);
        length = clip.length();
        start = clip.ticks("power_start", 4);
        end = clip.ticks("power_end", 20);
    }

    @Override public Progress tick(YukkuriEntity body) {
        if (!(Attach.of(body) instanceof AttachState.OnHost on) || on.host() != host) return Outcome.of(Reason.NO_SUPPORT, 0, "left_host");
        Vec3 to = target.subtract(host.entity().position()).multiply(1, 0, 1);
        double distance = to.length(), power = Effort.stroke(t, start, end, length);
        if (distance <= arrive) return new Outcome.Done(String.format(Locale.ROOT, "arrived strokes=%d", t / length));
        if (t++ % length == 0 && !body.life().spendEnergy(COST)) return Outcome.of(Reason.EXHAUSTED, 0, "energy=" + body.life().energy());
        double turn = Effort.turn(host.entity().getYRot(), Mth.atan2(to.z, to.x) * Mth.RAD_TO_DEG - 90, turnRate * power);
        if (!host.thrust(body, to.scale(pace * power / distance), (float) turn)) return Outcome.of(Reason.NO_SUPPORT, 0, "not_afloat");
        return new Running(distance, TravelMode.CONSUMED);
    }

    @Override public String startDetail() {
        return String.format(Locale.ROOT, "to=(%.1f,%.1f) pace=%.3f turn=%.1f stroke=%d..%d/%d", target.x, target.z, pace, turnRate, start, end, length);
    }
}
