package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.WaterDistress;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.motor.geom.Effort;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Swim toward a target (spec 9.4, Q2: a weak struggle; an aquatic body is stronger only through its ability): horizontal effort
 * at the ability's swim speed, turning at its turn rate; the vertical is the water's lift ({@code BodyDriver}). Never a jump.
 * Paid in the struggle's rhythm: a burst for its energy, then a rest; an exhausted body pays less per cycle and keeps paddling
 * weakly through the rest. No energy is {@code EXHAUSTED}. {@link #to} ends with the target in grip reach, {@link #toward}
 * presses on until strength or progress gives out; out of the water both are done.
 */
public final class Swim implements Action {
    public static final String ID = "swim";
    /** The water_struggle rhythm (ticks; the cycle is its 1.6 s clip) and its energy, rested and exhausted. */
    private static final int BURST = 8, REST = 24, WEAK_BURST = 3, WEAK_REST = 61;
    private static final float COST = .65f, WEAK_COST = .3f;

    private final Supplier<Vec3> target;
    private final boolean grip;
    private final int maxTicks;
    private double speed, turnRate, paid;
    private int ticks, cycle, burst;

    private Swim(Supplier<Vec3> target, boolean grip, int maxTicks) { this.target = target; this.grip = grip; this.maxTicks = maxTicks; }

    /** Until the (moving) target is in grip reach; past {@code maxTicks} (0: never) it is {@code TOO_FAR}. */
    public static Swim to(Supplier<Vec3> target, int maxTicks) { return new Swim(target, true, maxTicks); }

    /** Pressing toward a point until strength or progress gives out. */
    public static Swim toward(Vec3 point) { return new Swim(() -> point, false, 0); }

    /** In a paid burst (the presentation's effort flag). */
    public boolean pushing() { return burst > 0; }

    @Override public String id() { return ID; }

    @Override public Feasibility check(YukkuriEntity body) {
        if (target.get() == null) return Feasibility.of(Reason.TARGET_GONE, 0, "no_target");
        if (!body.isInWaterOrBubble()) return Feasibility.of(Reason.NO_SUPPORT, 0, "not_in_water");
        return body.life().energy() < cost(body) ? Feasibility.of(Reason.EXHAUSTED, 0, "energy=" + body.life().energy()) : Feasibility.pass();
    }

    @Override public void start(YukkuriEntity body) {
        var ability = BodyAbility.of(body);
        speed = ability.swimSpeed() * BodyAbility.carrying(body);
        turnRate = ability.turnRate();
    }

    @Override public Progress tick(YukkuriEntity body) {
        Vec3 at = target.get();
        if (at == null) return Outcome.of(Reason.TARGET_GONE, 0, "target_gone");
        if (!body.isInWaterOrBubble()) return new Outcome.Done("out_of_water at=" + GameplayLog.at(body.position()));
        if (grip && ContactGeometry.canGrip(body, at)) return new Outcome.Done("grip at=" + GameplayLog.at(at));
        Vec3 to = at.subtract(body.position()).multiply(1, 0, 1);
        double distance = to.length();
        if (maxTicks > 0 && ++ticks > maxTicks) return Outcome.of(Reason.TOO_FAR, distance, "swum=" + maxTicks);
        boolean weak = body.waterDistress().phase() == WaterDistress.Phase.EXHAUSTED;
        if (--cycle < 0) {
            if (!body.life().spendEnergy(cost(body))) return Outcome.of(Reason.EXHAUSTED, 0, "energy=" + body.life().energy());
            paid += cost(body);
            burst = weak ? WEAK_BURST : BURST;
            cycle = burst + (weak ? WEAK_REST : REST) - 1;
        }
        if (distance > 1E-4) {
            Motor.face(body, body.getYRot() + (float) Effort.turn(body.getYRot(), Mth.atan2(to.z, to.x) * Mth.RAD_TO_DEG - 90, turnRate));
            Vec3 v = to.scale(Math.min(speed, distance) / distance);
            if (burst-- > 0 || weak) Motor.velocity(body, new Vec3(v.x, body.getDeltaMovement().y, v.z), "swim");
        }
        return new Running(distance, TravelMode.PASS);
    }

    @Override public String startDetail() {
        return String.format(Locale.ROOT, "to=%s swim=%.3f turn=%.1f grip=%b max=%d", GameplayLog.at(target.get()), speed, turnRate, grip, maxTicks);
    }

    @Override public String endDetail(Outcome outcome) { return String.format(Locale.ROOT, "paid=%.2f", paid); }

    private static float cost(YukkuriEntity body) { return body.waterDistress().phase() == WaterDistress.Phase.EXHAUSTED ? WEAK_COST : COST; }
}
