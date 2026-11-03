package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
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
import io.github.longshou.mcyukkuri.motor.surface.Floor;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Locale;
import java.util.function.DoublePredicate;
import java.util.function.Supplier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Drive the ridden host continuously with a stick or bare braid, facing its bow and paying energy each tick. */
public final class Paddle implements Action {
    public static final String ID = "paddle";
    private static final String CLIP = YukkuriAction.BOAT_PADDLE.id();
    /** Energy per tick, preserving the original one point per 80 ticks; a bare braid costs twice as much. */
    private static final float COST = 1f / 80, BRAID_COST = 1f / 40;
    private static final double BRAID_PACE = .35;
    /** The widest angle (degrees) between the rower and her hull's bow at which a stroke reaches the water: the stick goes over the
     *  beam, as lore/recipe.json paddle_shared.captain_yaw gates the paddle clips against every hull form. 项目设计 */
    public static final double FACING = ClipMarkers.of(CLIP).parameters().get("captain_yaw");

    private final Supplier<Vec3> target;
    private final DoublePredicate arrived;
    private SurfaceHost host;
    private double pace, turnRate, from, distance, facing, advanced;
    private Vec3 previous;
    private int t, turnTicks, skipped;
    private long startTick;
    private boolean turning, stick;

    public Paddle(Vec3 target, double arrive) { this(() -> target, distance -> distance <= arrive); }

    /** A moving destination, or null when its target is gone; reached at the caller's actual contact condition. */
    public Paddle(Supplier<Vec3> target, DoublePredicate arrived) { this.target = target; this.arrived = arrived; }

    @Override public String id() { return ID; }

    /** A published stick-use pose grips a real available stick. */
    public static boolean stickGrip(YukkuriEntity body) {
        String clip = body.getAnimationId();
        boolean grips = clip.equals(CLIP) || clip.equals(YukkuriAction.HAT_BRIM_OFFER.id())
                || clip.equals(YukkuriAction.BOAT_NUDGE_YOUNG.id());
        return grips && (body.life().mouth().is(Items.STICK)
                || body.life().custody().kit().is(Items.STICK)
                || body.getVehicle() instanceof PlacedHatEntity hat && hat.kit().is(Items.STICK));
    }

    @Override public Feasibility check(YukkuriEntity body) {
        if (target.get() == null) return Feasibility.of(Reason.TARGET_GONE, 0, "target_gone");
        if (!(Attach.of(body) instanceof AttachState.OnHost on)) return Feasibility.of(Reason.NO_SUPPORT, 0, "not_on_host");
        host = on.host();
        stick = body.getMouthItem().is(Items.STICK) || host.holds(Items.STICK);
        return toolCheck(body, host);
    }

    static Feasibility toolCheck(YukkuriEntity body, SurfaceHost host) {
        boolean stick = body.getMouthItem().is(Items.STICK) || host.holds(Items.STICK);
        if (!stick && (!ContactGeometry.braided(body) || ParentCarry.braidBusy(body)))
            return Feasibility.of(Reason.NO_TOOL, 0, "no_free_braid");
        float cost = cost(body, host);
        return body.life().energy() < cost ? Feasibility.of(Reason.EXHAUSTED, 0, "energy=" + body.life().energy()) : Feasibility.pass();
    }
    static float cost(YukkuriEntity body, SurfaceHost host) {
        return body.getMouthItem().is(Items.STICK) || host.holds(Items.STICK) ? COST : BRAID_COST;
    }

    @Override public MotionPose pose() {
        return new MotionPose(turning ? YukkuriAction.BOAT_DRIFT.id() : CLIP, startTick, true);
    }

    @Override public void start(YukkuriEntity body) {
        startTick = body.level().getGameTime();
        previous = host.entity().position();
        from = distance = target.get().subtract(previous).horizontalDistance();
        var ability = BodyAbility.of(body);
        pace = ability.swimSpeed() * (stick ? 1 : BRAID_PACE);
        turnRate = ability.turnRate();
        facing = bowOff(body);
        turning = Math.abs(facing) > FACING;
    }

    @Override public Progress tick(YukkuriEntity body) {
        if (!(Attach.of(body) instanceof AttachState.OnHost on) || on.host() != host) return Outcome.of(Reason.NO_SUPPORT, 0, "left_host");
        Vec3 at = target.get(), here = host.entity().position();
        if (at == null) return Outcome.of(Reason.TARGET_GONE, 0, "target_gone");
        Vec3 to = at.subtract(here).multiply(1, 0, 1);
        distance = to.length();
        if (arrived.test(distance)) return new Outcome.Done("arrived ticks=" + t);
        if (distance < .05) return Outcome.of(Reason.NO_CONTACT, 0, "destination_reached_without_contact");
        advanced += Effort.approach(previous.x, previous.z, here.x, here.z, at.x, at.z);
        previous = here;
        // She keeps facing the bow at her turn rate, so the stroke reaches the water over the beam; turned away, no stroke lands.
        double off = bowOff(body);
        if (Math.abs(off) > FACING) {
            turning = true;
            skipped++;
            GameplayLog.limited(body, "action", "paddle.turning", 200, "paddle turning bow_off={} turn_rate={} stroke_tick={}", off, turnRate, t);
            if (turnRate == 0 || ++turnTicks >= Math.min(progressWindow() - 1, Math.ceil(180 / turnRate))) {
                return Outcome.of(Reason.NO_PROGRESS, 0, "facing_stalled bow_off=" + off);
            }
        }
        if (Math.abs(off) > Face.FACE_ARRIVED) Floor.turn(host, body, (float) Math.copySign(Math.min(Math.abs(off), turnRate), off));
        if (Math.abs(off) > FACING) return new Running(-advanced + Math.abs(off) / FACING, TravelMode.CONSUMED);
        if (turning) {
            turning = false;
            startTick = body.level().getGameTime() - t;
            GameplayLog.limited(body, "action", "paddle.facing_ready", 200,
                    "paddle facing ready bow_off={} turn_ticks={} stroke_tick={}", bowOff(body), turnTicks, t);
            turnTicks = 0;
        }
        float cost = stick ? COST : BRAID_COST;
        if (body.life().energy() < cost) return Outcome.of(Reason.EXHAUSTED, 0, "energy=" + body.life().energy());
        pace = BodyAbility.of(body).swimSpeed() * (stick ? 1 : BRAID_PACE);
        double turn = Effort.turn(host.entity().getYRot(), Mth.atan2(to.z, to.x) * Mth.RAD_TO_DEG - 90, turnRate);
        if (!host.thrust(body, to.scale(Math.min(pace, distance) / distance), (float) turn)) return Outcome.of(Reason.NO_SUPPORT, 0, "not_afloat");
        body.life().spendEnergy(cost);
        t++;
        return new Running(-advanced, TravelMode.CONSUMED);
    }

    /** Degrees from the rower's facing to her hull's bow (its yaw: the hull travels toward its -Z). */
    private double bowOff(YukkuriEntity body) { return Mth.wrapDegrees(host.entity().getYRot() - body.getYRot()); }

    @Override public String startDetail() {
        var at = target.get();
        return String.format(Locale.ROOT, "to=(%.1f,%.1f) pace=%.3f turn=%.1f bow_off=%.0f tool=%s", at.x, at.z, pace, turnRate, facing, stick ? "stick" : "braid");
    }

    @Override public String endDetail(Outcome outcome) {
        return String.format(Locale.ROOT, "distance_before=%.3f distance_after=%.3f advanced=%.3f paid=%.3f skipped_ticks=%d", from, distance, advanced, t * (stick ? COST : BRAID_COST), skipped);
    }
}
