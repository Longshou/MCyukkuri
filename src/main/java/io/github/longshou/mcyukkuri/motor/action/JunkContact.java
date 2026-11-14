package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.JunkHeadwear;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import java.util.function.BooleanSupplier;

/** Play the visible beat, then transfer the real piece at contact. */
public final class JunkContact implements Action {
    private final YukkuriAction clip;
    private final String face, line;
    private final BooleanSupplier transfer;
    private final int length, contact;
    private long started;
    private int elapsed;

    public JunkContact(YukkuriAction clip, String face, String line, BooleanSupplier transfer) {
        this.clip = clip;
        this.face = face;
        this.line = line;
        this.transfer = transfer;
        length = ClipMarkers.of(clip.id()).length();
        // Release after the shake's strong turns. A hesitant fit waits for the whole look-down.
        contact = clip == YukkuriAction.SHAKE ? length / 2 : length;
    }

    @Override public String id() { return clip.id(); }
    @Override public Feasibility check(YukkuriEntity body) { return Feasibility.pass(); }

    @Override public void start(YukkuriEntity body) {
        started = body.level().getGameTime();
        body.showReaction(face, line, length);
    }

    @Override public Progress tick(YukkuriEntity body) {
        if (!JunkHeadwear.available(body) || !JunkHeadwear.onDryGround(body)) return new Outcome.Interrupted("left_dry_contact");
        if (++elapsed == contact && !transfer.getAsBoolean()) return Outcome.of(Reason.NO_CONTACT, 0, "junk_transfer_refused");
        if (elapsed >= length) return new Outcome.Done("junk_contact");
        return new Running(length - elapsed, TravelMode.CONSUMED);
    }

    @Override public MotionPose pose() { return new MotionPose(clip.id(), started, false); }
}
