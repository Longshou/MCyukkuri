package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Prop;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Locale;
import net.minecraft.world.entity.Entity;

/**
 * Take a prop back onto the body (spec 9.4 {@code TakeProp}): play the prop's own clip and make the one irreversible
 * transfer at the clip's {@code release} marker. Refuses {@code NO_ROOM} while riders remain, and {@code NO_CONTACT}
 * while neither the mouth reaches the prop nor, for a prop one stands on (a hat), mouth or braid grips its nearest brim
 * ({@link ContactGeometry}, spec 9.4 "嘴或辫在 reach 内"), so a method can walk closer or clear the prop first instead of
 * retrying the same clip.
 */
public final class TakeProp implements Action {
    public static final String ID = "take_prop";
    public static final String RELEASE_MARKER = "release";

    private final Prop prop;
    private long startTick;

    public TakeProp(Prop prop) {
        if (prop == null) throw new IllegalArgumentException("TakeProp needs the prop");
        this.prop = prop;
    }

    @Override public String id() { return ID; }

    @Override public Feasibility check(YukkuriEntity body) {
        if (!prop.deployed()) return Feasibility.of(Reason.NO_TOOL, 0, "prop_not_deployed");
        if (prop.riders()) return Feasibility.of(Reason.NO_ROOM, 0, "riders_aboard");
        Entity deployed = prop.entity();
        if (deployed == null) return Feasibility.of(Reason.TARGET_GONE, 0, "prop_gone");
        boolean brim = deployed instanceof SurfaceHost host && ContactGeometry.nearestRim(body, host).filter(rim -> ContactGeometry.canGrip(body, rim)).isPresent();
        if (!brim && !ContactGeometry.inMouthReach(body, deployed)) {
            double gap = Math.max(0, ContactGeometry.mouthGap(body, deployed) - ContactGeometry.GRAB_MARGIN);
            return Feasibility.of(Reason.NO_CONTACT, gap, "prop_out_of_reach");
        }
        return Feasibility.pass();
    }

    @Override public void start(YukkuriEntity body) {
        startTick = body.level().getGameTime();
    }

    @Override public Progress tick(YukkuriEntity body) {
        int release = releaseTicks();
        long elapsed = body.level().getGameTime() - startTick;
        if (elapsed < release) return new Running(release - elapsed, TravelMode.PASS);
        // The world may have changed during the clip: the same refusal the start check gives, before the one transfer.
        Feasibility still = check(body);
        if (!still.ok()) return Outcome.of(still.reason(), still.shortfall(), still.detail());
        if (!prop.takeBack()) return Outcome.of(Reason.NO_CONTACT, 0, "take_back_refused");
        return new Outcome.Done("prop=" + prop.id() + " worn=" + prop.worn());
    }

    @Override public MotionPose pose() { return new MotionPose(clip(), startTick, false); }

    @Override public String startDetail() {
        return String.format(Locale.ROOT, "prop=%s at=%s release=%d", prop.id(),
                prop.entity() == null ? "none" : GameplayLog.at(prop.entity().position()), releaseTicks());
    }

    /** The clip's {@code release} marker in ticks, or its last tick when the clip authors none (spec 9.4). */
    public int releaseTicks() {
        ClipMarkers.Clip clip = ClipMarkers.of(clip());
        return clip.ticks(RELEASE_MARKER, Math.max(0, clip.length() - 1));
    }

    private String clip() { return prop.clip(true); }
}
