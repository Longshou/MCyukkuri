package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
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

/**
 * Set a prop down at a stand (spec 9.4 {@code PlaceProp}): play the prop's own clip and make the one irreversible
 * transfer at the clip's {@code release} marker (fallback: the clip's last tick). Feasibility is checked once, before the
 * action starts ({@link #check}); the body then commits to the clip instead of re-validating every tick, and a world that
 * changed underneath is the prop's own refusal at the marker ({@code NO_ROOM}).
 *
 * <p>The action never moves the body: the stand and yaw are the role's own aim, and playing the clip is a presentation.
 * The prop supplies everything species-specific (clip ids, self variant, land or floating mode); the motor layer stays
 * free of {@code subspecies/}.
 */
public final class PlaceProp implements Action {
    public static final String ID = "place_prop";
    /** The clip marker the transfer happens at (spec 9.4); a clip without it uses its last tick. */
    public static final String RELEASE_MARKER = "release";

    private final Prop prop;
    private final Vec3 at;
    private final float yaw;
    private long startTick;

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
        return prop.fits(at, yaw);
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
        Entity placed = prop.setDown(at, yaw);
        if (placed == null) return Outcome.of(Reason.NO_ROOM, 0, "set_down_refused");
        return new Outcome.Done("prop=" + prop.id() + " entity=" + placed.getUUID() + " at=" + GameplayLog.at(at));
    }

    @Override public MotionPose pose() { return new MotionPose(clip(), startTick, false); }

    @Override public String startDetail() {
        return String.format(Locale.ROOT, "prop=%s at=%s yaw=%.1f release=%d", prop.id(), GameplayLog.at(at), yaw,
                releaseTicks());
    }

    /** The clip's {@code release} marker in ticks, or its last tick when the clip authors none (spec 9.4). */
    public int releaseTicks() {
        ClipMarkers.Clip clip = ClipMarkers.of(clip());
        return clip.ticks(RELEASE_MARKER, Math.max(0, clip.length() - 1));
    }

    private String clip() { return prop.clip(false); }
}
