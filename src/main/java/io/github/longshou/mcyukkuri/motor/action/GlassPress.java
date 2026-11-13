package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.GlassSight;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.motor.geom.RamContact;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Face squashed on a pane (project design): a body that has walked up to a glass cell with someone or something it wants just
 * beyond it presses its face flat against the glass once, then peels back. The clip's own skinned front sweep (RamContact over
 * {@code glass_press}) places the body each tick so the face lands on the glass plane at {@code contact}, stays on it until
 * {@code impact} and returns to the stand by {@code settled}; vanilla collision still resolves the travel. Only the arrival of an
 * approach offers it ({@link #toward}); afterwards the caller's own behaviour carries on.
 */
public final class GlassPress implements Action {
    public static final String ID = "glass_press";
    public static final String CLIP = "glass_press";
    /** The face may start this far (blocks) off the glass plane: the approach stand plus its arrival envelope. */
    private static final double ADJACENT = .35;
    /** Squaring up to the pane may take this long before the press is given up (ticks). */
    private static final int SQUARE_TICKS = 20;

    private final BlockPos glass;
    private final Entity wanted;
    private final ClipMarkers.Clip marks = ClipMarkers.of(CLIP);
    private final int length = marks.length();
    private final int contactTick = Math.round(marks.markers().get("contact") * 20);
    private final int impactTick = Math.round(marks.markers().get("impact") * 20);
    private Vec3 outward, stand;
    private double plane;
    private float square;
    private int ticks, turning;
    private long startTick;
    private double closest = Double.POSITIVE_INFINITY;

    private GlassPress(BlockPos glass, Entity wanted) {
        this.glass = glass.immutable();
        this.wanted = wanted;
    }

    /** A press toward {@code wanted} when exactly one glass cell is between them and that cell is right in front of the face, else null. */
    public static GlassPress toward(YukkuriEntity body, Entity wanted) {
        if (body == null || wanted == null) return null;
        var hit = GlassSight.glassBetween(body, body.getEyePosition(), wanted);
        if (hit == null) return null;
        var press = new GlassPress(hit.getBlockPos(), wanted);
        try {
            return press.check(body).ok() ? press : null;
        } catch (IllegalArgumentException e) {
            // A form whose posed contact geometry does not load cannot place its face on the glass; it pleads where it stands.
            GameplayLog.limited(body, "action", "glass_press.geometry", 6000, "glass press unavailable model={} why={}", body.assetId(), e.getMessage());
            return null;
        }
    }

    /** The longest a press holds the body: squaring up, then the whole clip (ticks). */
    public static int maxTicks() { return SQUARE_TICKS + ClipMarkers.of(CLIP).length() + 2; }

    private static double scale(YukkuriEntity body) { return body.growthScale() * body.getScale() / 16; }

    /** The collider face the body's centre line meets on its level way to the glass (the same probe as {@link Bash}). */
    private static BlockHitResult contact(YukkuriEntity body, BlockPos glass) {
        var from = body.position().add(0, body.getBbHeight() / 2, 0);
        Vec3 aim = Vec3.atCenterOf(glass);
        return body.level().clip(new ClipContext(from, new Vec3(aim.x, from.y, aim.z), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body));
    }

    @Override public String id() { return ID; }

    @Override public Feasibility check(YukkuriEntity body) {
        if (!body.onGround() || body.isPassenger() || body.isInWaterOrBubble())
            return Feasibility.of(Reason.NO_SUPPORT, 0, "dry_ground_required");
        var hit = contact(body, glass);
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(glass))
            return Feasibility.of(Reason.NO_CONTACT, 0, "front_obstructed");
        var normal = Vec3.atLowerCornerOf(hit.getDirection().getNormal());
        double gap = body.position().subtract(hit.getLocation()).dot(normal) - RamContact.of(body.assetId(), CLIP).front()[0] * scale(body);
        if (gap < 0) return Feasibility.of(Reason.NO_CONTACT, -gap, "stand_too_close_to_glass");
        return gap <= ADJACENT ? Feasibility.pass() : Feasibility.of(Reason.NO_CONTACT, gap, "glass_not_at_face");
    }

    @Override public void start(YukkuriEntity body) {
        body.getNavigation().stop();
        var hit = contact(body, glass);
        outward = Vec3.atLowerCornerOf(hit.getDirection().getNormal());
        plane = hit.getLocation().dot(outward);
        stand = body.position();
        square = SurfaceApproach.yaw(Vec3.ZERO, outward.reverse());
        ticks = 0; turning = 0;
        startTick = body.level().getGameTime();
    }

    @Override public Progress tick(YukkuriEntity body) {
        if (!body.onGround() || body.isPassenger() || body.isInWaterOrBubble())
            return Outcome.of(Reason.NO_SUPPORT, 0, "dry_ground_required");
        var sight = wanted.isAlive() && wanted.level() == body.level()
                ? GlassSight.glassBetween(body, body.getEyePosition(), wanted) : null;
        if (sight == null || !sight.getBlockPos().equals(glass))
            return Outcome.of(Reason.NO_CONTACT, 0, "glass_target_changed");
        var hit = contact(body, glass);
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(glass)
                || !outward.equals(Vec3.atLowerCornerOf(hit.getDirection().getNormal()))
                || Math.abs(hit.getLocation().dot(outward) - plane) > 1e-7)
            return Outcome.of(Reason.NO_CONTACT, 0, "glass_face_changed");
        Motor.face(body, square);
        if (ticks == 0) {
            // Square up to the pane first, so the clip's straight-ahead front is the reach into the glass.
            if (Math.abs(Mth.wrapDegrees(square - body.yBodyRot)) > Face.FACE_ARRIVED) {
                if (++turning > SQUARE_TICKS) return Outcome.of(Reason.NO_CONTACT, 0, "cannot_square_to_glass");
                return new Running(length + SQUARE_TICKS - turning, TravelMode.PASS);
            }
            startTick = body.level().getGameTime();
        }
        // The clip clock: tick n of the press draws the clip at n/20 s from startTick.
        if (ticks > length) return new Outcome.Done("block=" + glass);
        var sweep = RamContact.of(body.assetId(), CLIP);
        int frame = ticks++;
        double k = scale(body), seconds = Math.min(frame / 20.0, marks.seconds());
        double from = stand.dot(outward) - plane, distance = sweep.distance(from / k, seconds) * k;
        // Lean in from the stand rather than step: until contact the body only closes on the press distance as the face reaches out.
        float launch = marks.markers().get("launch"), touch = marks.markers().get("contact");
        if (seconds < touch)
            distance = Math.max(distance, Mth.lerp(smooth((seconds - launch) / (touch - launch)), from, distance));
        var target = stand.add(outward.scale(distance - from));
        if (!SurfaceApproach.stands(body, target))
            return Outcome.of(Reason.NO_SUPPORT, 0, "glass_press_stand_lost");
        Motor.velocity(body, new Vec3(0, body.getDeltaMovement().y, 0), "glass_press");
        var step = target.subtract(body.position()).multiply(1, 0, 1);
        if (step.horizontalDistanceSqr() > 0) Motor.move(body, step, "glass_press");
        if (frame >= contactTick && frame <= impactTick)
            closest = Math.min(closest, body.position().dot(outward) - plane - sweep.frontAt(seconds) * k);
        // Let vanilla settle the vertical axis after the horizontal stroke, as it does for Bash.
        return new Running(length - ticks, TravelMode.PASS);
    }

    private static double smooth(double x) { x = Math.clamp(x, 0, 1); return x * x * (3 - 2 * x); }

    @Override public MotionPose pose() { return ticks == 0 ? MotionPose.NONE : new MotionPose(CLIP, startTick, false); }

    @Override public String startDetail() { return "block=" + glass + " clipTicks=" + length; }

    @Override public String endDetail(Outcome outcome) {
        return Double.isInfinite(closest) ? "" : String.format(Locale.ROOT, "faceGap=%.3f", closest);
    }
}
