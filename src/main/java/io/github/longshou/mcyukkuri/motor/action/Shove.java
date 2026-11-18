package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import java.util.function.DoubleConsumer;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** One grounded stroke that pushes against a block without damaging the block or the body. */
public final class Shove implements Action {
    private final BlockPos block;
    private final Direction side;
    private final BooleanSupplier targetStillPresent;
    private final DoubleConsumer impact;
    private RamStroke stroke;
    private int phase;
    private long startTick;

    public Shove(BlockPos block, Direction side, BooleanSupplier targetStillPresent, DoubleConsumer impact) {
        this.block = block;
        this.side = side;
        this.targetStillPresent = targetStillPresent;
        this.impact = impact;
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
        stroke = new RamStroke(body, RamStroke.contact(body, block));
    }

    @Override public Progress tick(YukkuriEntity body) {
        if (phase <= RamStroke.IMPACT && !targetStillPresent.getAsBoolean())
            return Outcome.of(Reason.NO_CONTACT, 0, "shove_face_missing");
        stroke.face(body);
        var frame = stroke.move(body, phase, id());
        if (phase == RamStroke.IMPACT) {
            if (!frame.reached()) return Outcome.of(Reason.NO_CONTACT, Math.abs(frame.gap()), "stroke_obstructed");
            impact.accept(frame.travel());
        }
        if (++phase == RamStroke.LENGTH) return new Outcome.Done("stroke_settled");
        return new Running(RamStroke.LENGTH - phase, TravelMode.PASS);
    }

    @Override public MotionPose pose() { return new MotionPose(RamStroke.CLIP, startTick, false); }
    @Override public String startDetail() { return "block=" + block + " side=" + side; }
}
