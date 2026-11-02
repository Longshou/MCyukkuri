package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Locale;

/** A real initial brim grip, then one authored turn. Ordinary drift and water damage do not break the accepted clip. */
public final class Right implements Action {
    public static final String ID = "right";
    private static final String CLIP = "water_hat_right";
    private final SurfaceHost host;
    private final String cause;
    private double progress;
    private int t, pushStart, pushEnd, length;
    private long startTick;

    public Right(SurfaceHost host, String cause) { this.host = host; this.cause = cause; }
    public SurfaceHost host() { return host; }
    @Override public String id() { return ID; }

    @Override public Feasibility check(YukkuriEntity body) {
        if (host.entity().isRemoved()) return Feasibility.of(Reason.TARGET_GONE, 0, "host_gone");
        if (ContactGeometry.reachesHull(body, host.entity(), true, true)) return Feasibility.pass();
        var rim = ContactGeometry.nearestRim(body, host).orElse(null);
        return rim == null ? Feasibility.of(Reason.NO_CONTACT, 0, "no_rim")
                : Feasibility.of(Reason.NO_CONTACT, ContactGeometry.gripGap(body, rim), "rim=" + GameplayLog.at(rim));
    }

    @Override public void start(YukkuriEntity body) {
        var clip = ClipMarkers.of(CLIP);
        length = clip.length();
        pushStart = Math.round(clip.markers().get("push_start") * 20);
        pushEnd = Math.round(clip.markers().get("push_end") * 20);
        startTick = body.level().getGameTime();
    }
    @Override public MotionPose pose() { return new MotionPose(CLIP, startTick, false); }

    @Override public Progress tick(YukkuriEntity body) {
        if (!body.isAlive() || host.entity().isRemoved() || body.level() != host.entity().level()) {
            host.stopRight(body);
            return new Outcome.Interrupted(body.isAlive() ? "host_gone" : "death");
        }
        if (t <= pushEnd) {
            progress = Math.clamp((t - pushStart) / (double) (pushEnd - pushStart), 0, 1);
            if (!host.right(body, progress, cause)) {
                host.stopRight(body);
                return new Outcome.Interrupted("host_left");
            }
        }
        if (++t >= length) return new Outcome.Done("upright");
        return new Running(length - t, TravelMode.PASS);
    }
    @Override public void abort(YukkuriEntity body, String why) { host.stopRight(body); }
    @Override public int progressWindow() { return length; }
    @Override public String startDetail() {
        return String.format(Locale.ROOT, "host=%s push=%d..%d/%d cause=%s", host.entity().getUUID(), pushStart, pushEnd, length, cause);
    }
    @Override public String endDetail(Outcome outcome) { return String.format(Locale.ROOT, "progress=%.2f ticks=%d", progress, t); }
}
