package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Feasibility;
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

/**
 * Turn a capsized host back over (spec 9.4): holding its brim ({@link ContactGeometry#canGrip}) the body pushes through the
 * clip's push window, each push tick turning it {@link Effort#righting} further (its pace and its share of the two box
 * volumes over half a round of the brim). The host's behaviour holds it there and, once it is full, turns it the rest of the
 * way; the action ends when it lies steady. The body holds still meanwhile. Off the brim, or a host faster than 0.1 block a
 * tick, is {@code SLIPPED}; letting go leaves the host to its own physics.
 */
public final class Right implements Action {
    public static final String ID = "right";
    private static final String CLIP = "water_hat_right";
    private static final double HOST_SPEED = .1;

    private final SurfaceHost host;
    private final String cause;
    private double progress, step, reach;
    private int t, pushStart, pushEnd, length;

    public Right(SurfaceHost host, String cause) { this.host = host; this.cause = cause; }

    @Override public String id() { return ID; }

    @Override public Feasibility check(YukkuriEntity body) {
        if (host.entity().isRemoved()) return Feasibility.of(Reason.TARGET_GONE, 0, "host_gone");
        var rim = ContactGeometry.nearestRim(body, host).orElse(null);
        double gap = ContactGeometry.gripGap(body, rim);
        if (rim == null || gap > 0) return Feasibility.of(Reason.NO_CONTACT, rim == null ? 0 : gap, "rim=" + GameplayLog.at(rim));
        reach = rim.subtract(host.entity().position()).horizontalDistance();
        step = Effort.righting(BodyAbility.of(body).pace(body.isInWaterOrBubble()), Effort.share(mass(body), mass(host.entity())), reach);
        return Feasibility.pass();
    }

    @Override public void start(YukkuriEntity body) {
        var clip = ClipMarkers.of(CLIP);
        length = clip.length();
        pushStart = clip.ticks("push_start", 14);
        pushEnd = clip.ticks("push_end", 24);
    }

    @Override public Progress tick(YukkuriEntity body) {
        if (host.entity().isRemoved()) return Outcome.of(Reason.TARGET_GONE, 0, "host_gone");
        double tilt = Math.max(Math.abs(host.frame().rollDeg()), Math.abs(host.frame().pitchDeg()));
        if (!host.capsized() && (t == 0 || progress >= 1 && host.steady())) return new Outcome.Done(String.format(Locale.ROOT, "upright tilt=%.1f", tilt));
        double off = body.position().subtract(host.entity().position()).horizontalDistance() - reach - body.getBbWidth() / 2;
        double speed = host.entity().getDeltaMovement().horizontalDistance();
        if (off > ContactGeometry.GRAB_MARGIN || speed > HOST_SPEED)
            return Outcome.of(Reason.SLIPPED, Math.max(0, off), String.format(Locale.ROOT, "off=%.2f host_speed=%.3f", off, speed));
        if (progress < 1 && !host.right(body, progress = Math.min(1, progress + step * Effort.stroke(t, pushStart, pushEnd, length)), cause))
            return Outcome.of(Reason.NO_SUPPORT, 0, "cannot_right");
        t++;
        return new Running(1 - progress + tilt / 180, TravelMode.CONSUMED);
    }

    @Override public String startDetail() {
        return String.format(Locale.ROOT, "host=%s step=%.3f reach=%.2f push=%d..%d/%d cause=%s", host.entity().getUUID(), step, reach,
                pushStart, pushEnd, length, cause);
    }

    @Override public String endDetail(Outcome outcome) { return String.format(Locale.ROOT, "progress=%.2f ticks=%d", progress, t); }

    static double mass(Entity e) { return e.getBbWidth() * e.getBbWidth() * e.getBbHeight(); }
}
