package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Prop;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Locale;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Set a prop down at a stand: play the prop's own clip and make the one irreversible transfer at its {@code release} marker, the braid carrying it from the {@code grip} marker. */
public final class PlaceProp implements Action {
    public static final String ID = "place_prop";
    /** The clip marker the transfer happens at; a clip without it uses its last tick. */
    public static final String RELEASE_MARKER = "release";

    private final Prop prop;
    private final Vec3 at;
    private final float yaw;
    private long startTick;
    private boolean holding;

    public PlaceProp(Prop prop, Vec3 at, float yaw) {
        if (prop == null) throw new IllegalArgumentException("PlaceProp needs the prop");
        if (at == null) throw new IllegalArgumentException("PlaceProp needs a stand");
        this.prop = prop;
        this.at = at;
        this.yaw = yaw;
    }

    @Override public String id() { return ID; }

    @Override public Feasibility check(YukkuriEntity body) {
        if (!prop.worn()) return Feasibility.of(Reason.NO_TOOL, 0, "prop_not_worn");
        // The hat never moves with children on its brim or in the braid that would carry it.
        if (ParentCarry.braidBusy(body)) return Feasibility.of(Reason.NO_ROOM, 0, "carrying");
        return prop.fits(at, yaw);
    }

    @Override public void start(YukkuriEntity body) {
        startTick = body.level().getGameTime();
    }

    @Override public Progress tick(YukkuriEntity body) {
        int release = releaseTicks();
        long elapsed = body.level().getGameTime() - startTick;
        holding = elapsed >= ClipMarkers.of(clip()).ticks(TakeProp.GRIP_MARKER, 0);
        if (elapsed < release) return new Running(release - elapsed, TravelMode.PASS);
        // The world may have changed during the clip: the same refusal the start check gives, before the one transfer.
        Feasibility still = check(body);
        if (!still.ok()) return Outcome.of(still.reason(), still.shortfall(), still.detail());
        Entity placed = prop.setDown(at, yaw);
        if (placed == null) return Outcome.of(Reason.NO_ROOM, 0, "set_down_refused");
        return new Outcome.Done("prop=" + prop.id() + " entity=" + placed.getUUID() + " at=" + GameplayLog.at(at));
    }

    /** Once the braid holds the prop the clip runs on to the stand; before that any request may replace it. */
    @Override public boolean interruptible() { return !holding; }

    @Override public MotionPose pose() { return new MotionPose(clip(), startTick, false); }

    @Override public String startDetail() {
        return String.format(Locale.ROOT, "prop=%s at=%s yaw=%.1f release=%d", prop.id(), GameplayLog.at(at), yaw,
                releaseTicks());
    }

    /** The clip's {@code release} marker in ticks, or its last tick when the clip authors none. */
    public int releaseTicks() {
        ClipMarkers.Clip clip = ClipMarkers.of(clip());
        return clip.ticks(RELEASE_MARKER, Math.max(0, clip.length() - 1));
    }

    private String clip() { return prop.clip(false); }
}
