package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.ParentCarry;
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

/** Take a prop back onto the body: at the clip's {@code grip} marker the braid takes it and the one transfer happens; the clip then carries it to the head by {@code release}. */
public final class TakeProp implements Action {
    public static final String ID = "take_prop";
    public static final String RELEASE_MARKER = "release";
    /** The clip marker the braid takes the prop at; a clip without it takes it on its first tick. */
    public static final String GRIP_MARKER = "grip";

    private final Prop prop;
    private long startTick;
    private boolean holding;

    public TakeProp(Prop prop) {
        if (prop == null) throw new IllegalArgumentException("TakeProp needs the prop");
        this.prop = prop;
    }

    @Override public String id() { return ID; }

    @Override public Feasibility check(YukkuriEntity body) {
        if (!prop.deployed()) return Feasibility.of(Reason.NO_TOOL, 0, "prop_not_deployed");
        if (prop.riders()) return Feasibility.of(Reason.NO_ROOM, 0, "riders_aboard");
        // The braid that would carry the hat holds a child.
        if (ParentCarry.braidBusy(body)) return Feasibility.of(Reason.NO_ROOM, 0, "carrying");
        Entity deployed = prop.entity();
        if (deployed == null) return Feasibility.of(Reason.TARGET_GONE, 0, "prop_gone");
        boolean brim = prop.byBrim() && deployed instanceof SurfaceHost host && ContactGeometry.nearestRim(body, host).filter(rim -> ContactGeometry.canGrip(body, rim)).isPresent();
        // Otherwise the mouth or the braid has to reach it, the braid upright or in its low lean.
        if (!brim && !ContactGeometry.inMouthReach(body, deployed) && !ContactGeometry.inBraidReach(body, deployed)) {
            double gap = Math.min(Math.max(0, ContactGeometry.mouthGap(body, deployed) - ContactGeometry.GRAB_MARGIN),
                    Math.max(0, ContactGeometry.braidGap(body, deployed, true)));
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
        if (!holding) {
            if (elapsed < gripTicks()) return new Running(release - elapsed + 1, TravelMode.PASS);
            // The world may have changed while the braid reached: the same refusal the start check gives, before the one transfer.
            Feasibility still = check(body);
            if (!still.ok()) return Outcome.of(still.reason(), still.shortfall(), still.detail());
            if (!prop.takeBack()) return Outcome.of(Reason.NO_CONTACT, 0, "take_back_refused");
            holding = true;
        }
        if (elapsed < release) return new Running(release - elapsed, TravelMode.PASS);
        return new Outcome.Done("prop=" + prop.id() + " worn=" + prop.worn());
    }

    /** Once the braid holds the prop the clip runs on to the head; before that any request may replace it. */
    @Override public boolean interruptible() { return !holding; }

    @Override public MotionPose pose() { return new MotionPose(clip(), startTick, false); }

    @Override public String startDetail() {
        return String.format(Locale.ROOT, "prop=%s at=%s grip=%d release=%d", prop.id(),
                prop.entity() == null ? "none" : GameplayLog.at(prop.entity().position()), gripTicks(), releaseTicks());
    }

    /** The clip's {@code release} marker in ticks, or its last tick when the clip authors none. */
    public int releaseTicks() {
        ClipMarkers.Clip clip = ClipMarkers.of(clip());
        return clip.ticks(RELEASE_MARKER, Math.max(0, clip.length() - 1));
    }

    /** The clip's {@code grip} marker in ticks, or its first tick when the clip authors none. */
    public int gripTicks() { return ClipMarkers.of(clip()).ticks(GRIP_MARKER, 0); }

    private String clip() { return prop.clip(true); }
}
