package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
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
import java.util.function.DoublePredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** One grounded block contact driven by an authored stroke, with the caller's transaction at its marker. */
public final class Shove implements Action {
    private final BlockPos block;
    private final Direction side;
    private final String clip;
    private final ClipMarkers.Clip timing;
    private final int launch, touch, transfer, length;
    private final double touchAt, transferAt;
    private final BooleanSupplier targetStillPresent;
    private final DoublePredicate commit;
    private RamStroke stroke;
    private int phase;
    private long startTick;

    public Shove(BlockPos block, Direction side, String clip, String touchMarker, String transferMarker,
                 BooleanSupplier targetStillPresent, DoublePredicate commit) {
        this.block = block;
        this.side = side;
        this.clip = clip;
        timing = ClipMarkers.of(clip);
        launch = timing.ticks("launch");
        touch = timing.ticks(touchMarker);
        transfer = timing.ticks(transferMarker);
        length = timing.ticks("settled");
        touchAt = timing.markers().get(touchMarker);
        transferAt = timing.markers().get(transferMarker);
        this.targetStillPresent = targetStillPresent;
        this.commit = commit;
    }

    @Override public String id() { return "shove"; }

    @Override public Feasibility check(YukkuriEntity body) {
        if (!body.onGround() || body.isPassenger() || body.isInWaterOrBubble())
            return Feasibility.of(Reason.NO_SUPPORT, 0, "dry_ground_required");
        var hit = RamStroke.contact(body, block);
        if (!targetStillPresent.getAsBoolean() || hit.getType() != HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(block) || hit.getDirection() != side)
            return Feasibility.of(Reason.NO_CONTACT, 0, "shove_face_missing");
        if (Math.abs(Mth.wrapDegrees(RamStroke.square(Vec3.atLowerCornerOf(side.getNormal())) - body.yBodyRot)) > Face.FACE_ARRIVED)
            return Feasibility.of(Reason.NO_CONTACT, 0, "face_block_first");
        return Feasibility.pass();
    }

    @Override public void start(YukkuriEntity body) {
        body.getNavigation().stop();
        startTick = body.level().getGameTime();
        stroke = new RamStroke(body, RamStroke.contact(body, block), clip);
    }

    @Override public Progress tick(YukkuriEntity body) {
        if (!body.onGround() || body.isPassenger() || body.isInWaterOrBubble())
            return Outcome.of(Reason.NO_SUPPORT, 0, "dry_ground_required");
        if (phase <= transfer && !targetStillPresent.getAsBoolean())
            return Outcome.of(Reason.NO_CONTACT, 0, "shove_face_missing");
        stroke.face(body);
        double seconds = phase == transfer ? transferAt : phase == touch ? touchAt : phase == launch ? timing.markers().get("launch")
                : phase == length - 1 ? timing.markers().get("settled") : phase / 20.0;
        var frame = stroke.move(body, seconds, touchAt, id());
        if (phase == transfer) {
            if (!frame.followed()) return Outcome.of(Reason.NO_CONTACT, frame.pathError(), "stroke_obstructed");
            if (!commit.test(frame.travel())) return Outcome.of(Reason.NO_CONTACT, 0, "transfer_refused");
        }
        if (++phase == length) return new Outcome.Done("stroke_settled");
        return new Running(length - phase, TravelMode.PASS);
    }

    @Override public MotionPose pose() { return new MotionPose(clip, startTick, false); }
    @Override public String startDetail() { return "block=" + block + " side=" + side + " clip=" + clip; }
}
