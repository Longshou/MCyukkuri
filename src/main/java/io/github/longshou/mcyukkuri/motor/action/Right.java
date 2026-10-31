package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Feasibility;
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
import net.minecraft.world.entity.Entity;

/** Turn a capsized host back over: holding its brim it pushes through the clip's push window, turning the hull {@link Effort#righting} each tick; losing the brim is {@code SLIPPED}. */
public final class Right implements Action, LoadedContact {
    public static final String ID = "right";
    private static final String CLIP = "water_hat_right";
    private static final double HOST_SPEED = .1;
    /** The share of a floating hull's weight the body must turn: the water carries the rest. */
    private static final double FLOAT_WEIGHT = .5;
    /** How far past a firm hold (blocks) the brim may drift from the mouth before the hold is lost; the check admits a hold up to the margin, so the slack sits on top of it. */
    private static final double SLACK = .25;

    private final SurfaceHost host;
    private final String cause;
    private double progress, step, reach;
    private int t, pushStart, pushEnd, length;
    private long startTick;

    public Right(SurfaceHost host, String cause) { this.host = host; this.cause = cause; }

    @Override public String id() { return ID; }

    @Override public Contact loadedContact(YukkuriEntity body) {
        return ContactGeometry.nearestRim(body, host).map(point -> new Contact(host, point, 0)).orElse(null);
    }

    @Override public Feasibility check(YukkuriEntity body) {
        if (host.entity().isRemoved()) return Feasibility.of(Reason.TARGET_GONE, 0, "host_gone");
        var rim = ContactGeometry.nearestRim(body, host).orElse(null);
        double gap = ContactGeometry.gripGap(body, rim);
        if (rim == null || gap > 0) return Feasibility.of(Reason.NO_CONTACT, rim == null ? 0 : gap, "rim=" + GameplayLog.at(rim));
        reach = rim.subtract(host.entity().position()).horizontalDistance();
        step = Effort.righting(BodyAbility.of(body).pace(body.isInWaterOrBubble()), Effort.share(mass(body), mass(host.entity()) * FLOAT_WEIGHT), reach);
        return Feasibility.pass();
    }

    @Override public void start(YukkuriEntity body) {
        var clip = ClipMarkers.of(CLIP);
        length = clip.length();
        pushStart = clip.ticks("push_start", 14);
        pushEnd = clip.ticks("push_end", 24);
        startTick = body.level().getGameTime();
    }

    /** The righting clip (bite the brim, flip, push), started again with every push cycle so it stays on the push windows. */
    @Override public MotionPose pose() { return new MotionPose(CLIP, startTick + (long) t / Math.max(1, length) * length, false); }

    @Override public Progress tick(YukkuriEntity body) {
        if (host.entity().isRemoved()) return Outcome.of(Reason.TARGET_GONE, 0, "host_gone");
        double tilt = Math.max(Math.abs(host.frame().rollDeg()), Math.abs(host.frame().pitchDeg()));
        if (!host.capsized()) return new Outcome.Done(String.format(Locale.ROOT, "past_upright tilt=%.1f", tilt));
        var rim = ContactGeometry.nearestRim(body, host).orElse(null);
        double off = rim == null ? Double.POSITIVE_INFINITY : ContactGeometry.gripGap(body, rim);
        double speed = host.entity().getDeltaMovement().horizontalDistance();
        if (off > SLACK || speed > HOST_SPEED)
            return Outcome.of(Reason.SLIPPED, Math.clamp(off, 0, 9), String.format(Locale.ROOT, "off=%.2f host_speed=%.3f", off, speed));
        if (progress < 1 && !host.right(body, progress = Math.min(1, progress + step * Effort.stroke(t, pushStart, pushEnd, length)), cause))
            return Outcome.of(Reason.NO_SUPPORT, 0, "cannot_right");
        t++;
        // pushed full, the hull turns on its own toward upright: the tilt still falls every tick, so the watchdog stays quiet
        return new Running(1 - progress + tilt / 180, TravelMode.CONSUMED);
    }

    /** Two clip cycles: between pushes the hull rests and the tilt may swing back, so a window shorter than a cycle reads a working righter as stuck. */
    @Override public int progressWindow() { return Math.max(40, 2 * length); }

    @Override public String startDetail() {
        return String.format(Locale.ROOT, "host=%s step=%.3f reach=%.2f push=%d..%d/%d cause=%s", host.entity().getUUID(), step, reach,
                pushStart, pushEnd, length, cause);
    }

    @Override public String endDetail(Outcome outcome) { return String.format(Locale.ROOT, "progress=%.2f ticks=%d", progress, t); }

    static double mass(Entity e) { return e.getBbWidth() * e.getBbWidth() * e.getBbHeight(); }
}
