package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.motor.geom.Effort;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Locale;
import java.util.function.DoublePredicate;
import java.util.function.Supplier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Paddle the host one rides toward a point: facing its bow, in the {@code boat_paddle} power stroke it drives the water at its swim pace and turns the hull at its turn rate, paid in energy. */
public final class Paddle implements Action {
    public static final String ID = "paddle";
    private static final String CLIP = YukkuriAction.BOAT_PADDLE.id();
    /** A stroke's energy: the old helm's one point per 80 ticks over a 1.6 s stroke. */
    private static final float COST = .4f;
    /** The widest angle (degrees) between the rower and her hull's bow at which a stroke reaches the water: the stick goes over the
     *  beam, as lore/recipe.json paddle_shared.captain_yaw gates the paddle clips against every hull form. 项目设计 */
    public static final double FACING = ClipMarkers.of(CLIP).parameters().get("captain_yaw");

    private final Supplier<Vec3> target;
    private final DoublePredicate arrived;
    private SurfaceHost host;
    private double pace, turnRate, from, distance, facing;
    private int t, start, end, length, turnTicks, skipped, strokes;
    private long startTick;
    private boolean turning;

    public Paddle(Vec3 target, double arrive) { this(() -> target, distance -> distance <= arrive); }

    /** A moving destination, reached when the caller's actual contact condition holds. */
    public Paddle(Supplier<Vec3> target, DoublePredicate arrived) { this.target = target; this.arrived = arrived; }

    @Override public String id() { return ID; }

    /** A published stick-use pose grips a real available stick; beckoning uses the bare braid. */
    public static boolean stickGrip(YukkuriEntity body) {
        String clip = body.getAnimationId();
        boolean grips = clip.equals(CLIP) || clip.equals(YukkuriAction.HAT_BRIM_OFFER.id())
                || clip.equals(YukkuriAction.BOAT_NUDGE_YOUNG.id());
        return grips && !(body.motor().current() instanceof Beckon) && (body.life().mouth().is(Items.STICK)
                || body.life().custody().kit().is(Items.STICK)
                || body.getVehicle() instanceof PlacedHatEntity hat && hat.kit().is(Items.STICK));
    }

    @Override public Feasibility check(YukkuriEntity body) {
        if (!(Attach.of(body) instanceof AttachState.OnHost on)) return Feasibility.of(Reason.NO_SUPPORT, 0, "not_on_host");
        host = on.host();
        if (!body.getMouthItem().is(Items.STICK) && !host.holds(Items.STICK)) return Feasibility.of(Reason.NO_TOOL, 0, "no_stick");
        return body.life().energy() < COST ? Feasibility.of(Reason.EXHAUSTED, 0, "energy=" + body.life().energy()) : Feasibility.pass();
    }

    @Override public MotionPose pose() {
        return new MotionPose(turning ? YukkuriAction.BOAT_DRIFT.id() : CLIP, startTick, true);
    }

    @Override public void start(YukkuriEntity body) {
        startTick = body.level().getGameTime();
        from = distance = target.get().subtract(host.entity().position()).horizontalDistance();
        var ability = BodyAbility.of(body, host.load());
        pace = ability.swimSpeed();
        turnRate = ability.turnRate();
        facing = bowOff(body);
        turning = Math.abs(facing) > FACING;
        var clip = ClipMarkers.of(CLIP);
        length = clip.length();
        start = clip.ticks("power_start", 4);
        end = clip.ticks("power_end", 20);
    }

    @Override public Progress tick(YukkuriEntity body) {
        if (!(Attach.of(body) instanceof AttachState.OnHost on) || on.host() != host) return Outcome.of(Reason.NO_SUPPORT, 0, "left_host");
        Vec3 to = target.get().subtract(host.entity().position()).multiply(1, 0, 1);
        distance = to.length();
        if (arrived.test(distance)) return new Outcome.Done(String.format(Locale.ROOT, "arrived strokes=%d ticks=%d", strokes, t));
        if (distance < .05) return Outcome.of(Reason.NO_CONTACT, 0, "destination_reached_without_contact");
        // She keeps facing the bow at her turn rate, so the stick reaches the water over the beam; turned away, no stroke lands.
        double off = bowOff(body);
        if (Math.abs(off) > FACING) {
            turning = true;
            skipped++;
            GameplayLog.limited(body, "action", "paddle.turning", 200, "paddle turning bow_off={} turn_rate={} stroke_tick={}", off, turnRate, t);
            if (turnRate == 0 || ++turnTicks >= Math.min(progressWindow() - 1, Math.ceil(180 / turnRate))) {
                return Outcome.of(Reason.NO_PROGRESS, 0, "facing_stalled bow_off=" + off);
            }
        }
        if (Math.abs(off) > Face.FACE_ARRIVED) Motor.face(body, body.getYRot() + (float) Math.copySign(Math.min(Math.abs(off), turnRate), off));
        if (Math.abs(off) > FACING) return new Running(distance + Math.abs(off) / FACING, TravelMode.CONSUMED);
        if (turning) {
            turning = false;
            startTick = body.level().getGameTime() - t;
            GameplayLog.limited(body, "action", "paddle.facing_ready", 200,
                    "paddle facing ready bow_off={} turn_ticks={} stroke_tick={}", bowOff(body), turnTicks, t);
            turnTicks = 0;
        }
        double power = Effort.stroke(t, start, end, length);
        boolean charge = t % length == start;
        if (charge && body.life().energy() < COST) return Outcome.of(Reason.EXHAUSTED, 0, "energy=" + body.life().energy());
        // Effort.stroke is 0 or 1: hull steering never outruns the rower's turn rate.
        double turn = Effort.turn(host.entity().getYRot(), Mth.atan2(to.z, to.x) * Mth.RAD_TO_DEG - 90, turnRate * power);
        if (!host.thrust(body, to.scale(pace * power / distance), (float) turn)) return Outcome.of(Reason.NO_SUPPORT, 0, "not_afloat");
        if (charge) { body.life().spendEnergy(COST); strokes++; }
        t++;
        return new Running(distance, TravelMode.CONSUMED);
    }

    /** Degrees from the rower's facing to her hull's bow (its yaw: the hull travels toward its -Z). */
    private double bowOff(YukkuriEntity body) { return Mth.wrapDegrees(host.entity().getYRot() - body.getYRot()); }

    @Override public String startDetail() {
        var at = target.get();
        return String.format(Locale.ROOT, "to=(%.1f,%.1f) pace=%.3f turn=%.1f stroke=%d..%d/%d bow_off=%.0f", at.x, at.z, pace, turnRate, start, end, length, facing);
    }

    @Override public String endDetail(Outcome outcome) {
        return String.format(Locale.ROOT, "distance_before=%.3f distance_after=%.3f strokes=%d skipped_ticks=%d", from, distance, strokes, skipped);
    }
}
