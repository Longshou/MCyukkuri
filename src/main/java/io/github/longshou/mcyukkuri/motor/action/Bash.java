package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.*;
import io.github.longshou.mcyukkuri.motor.geom.Effort;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.EventHooks;

/** A grounded adult presses its front against one pane; vanilla collision resolves every stroke and recoil. */
public final class Bash implements Action {
    public static final String ID = "bash";
    // Replace this existing rough push with the motion track's ram composite here.
    private static final String CLIP = "push";
    private final BlockPos pane;
    private int ticks, hits;
    private double broken;
    private long startTick;
    private final int length = ClipMarkers.of(CLIP).length();

    public Bash(BlockPos pane) { this.pane = pane.immutable(); }
    public BlockPos pane() { return pane; }
    public int hits() { return hits; }
    @Override public String id() { return ID; }

    /** Also used during route choice: permissions are checked again on every stroke. */
    public static Feasibility allowed(YukkuriEntity body, BlockPos pane) {
        if (!body.level().getBlockState(pane).is(Tags.Blocks.GLASS_PANES))
            return Feasibility.of(Reason.NO_TOOL, 0, "not_glass_pane");
        if (!Custody.view(body).unassigned() || Custody.protectedAnimal(body))
            return Feasibility.of(Reason.NO_TOOL, 0, "protected_animal");
        if (!EventHooks.canEntityGrief((ServerLevel) body.level(), body))
            return Feasibility.of(Reason.NO_TOOL, 0, "mob_griefing_denied");
        if (BodyAbility.bashPower(body) <= 0)
            return Feasibility.of(Reason.EXHAUSTED, 0, "adult_strength_required");
        if (!body.onGround() || body.isPassenger() || body.isInWaterOrBubble())
            return Feasibility.of(Reason.NO_SUPPORT, 0, "dry_ground_required");
        double resistance = body.level().getBlockState(pane).getDestroySpeed(body.level(), pane);
        if (Effort.bash(BodyAbility.bashPower(body), resistance) <= 0)
            return Feasibility.of(Reason.NO_TOOL, 0, "unbreakable_pane");
        // A weak or already injured animal can leave or let a stronger one do it, rather than ram itself to death.
        if (body.getHealth() <= resistance / Effort.bash(BodyAbility.bashPower(body), resistance))
            return Feasibility.of(Reason.EXHAUSTED, 0, "cannot_bear_impacts");
        return Feasibility.pass();
    }

    @Override public Feasibility check(YukkuriEntity body) {
        var allowed = allowed(body, pane);
        if (!allowed.ok()) return allowed;
        Vec3 aim = Vec3.atCenterOf(pane);
        float yaw = SurfaceApproach.yaw(body.position(), aim);
        if (Math.abs(Mth.wrapDegrees(yaw - body.yBodyRot)) > Face.FACE_ARRIVED)
            return Feasibility.of(Reason.NO_CONTACT, 0, "face_pane_first");
        var from = body.position().add(0, body.getBbHeight() / 2, 0);
        var hit = body.level().clip(new ClipContext(from, new Vec3(aim.x, from.y, aim.z),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pane))
            return Feasibility.of(Reason.NO_CONTACT, 0, "front_obstructed");
        double gap = hit.getLocation().distanceTo(from) - body.getBbWidth() / 2;
        return gap <= ContactGeometry.GRAB_MARGIN ? Feasibility.pass()
                : Feasibility.of(Reason.NO_CONTACT, gap, "approach_pane_first");
    }

    @Override public void start(YukkuriEntity body) {
        body.getNavigation().stop();
        startTick = body.level().getGameTime();
    }

    @Override public Progress tick(YukkuriEntity body) {
        // Contact slides along the pane as the body presses; keep facing that actual contact at its own turn rate.
        float heading = SurfaceApproach.yaw(body.position(), Vec3.atCenterOf(pane));
        Motor.face(body, body.yBodyRot + (float) Effort.turn(body.yBodyRot, heading, BodyAbility.of(body).turnRate()));
        var feasible = check(body);
        if (!feasible.ok()) return Outcome.of(feasible.reason(), feasible.shortfall(), feasible.detail());
        Vec3 toward = Vec3.atBottomCenterOf(pane).subtract(body.position()).multiply(1, 0, 1).normalize();
        Motor.velocity(body, toward.scale(BodyAbility.of(body).walkSpeed()).add(0, body.getDeltaMovement().y, 0), "bash_press");
        if (++ticks % length == 0) {
            if (!body.horizontalCollision) return Outcome.of(Reason.NO_CONTACT, 0, "stroke_without_contact");
            var level = (ServerLevel) body.level();
            var state = level.getBlockState(pane);
            double resistance = state.getDestroySpeed(level, pane);
            broken += Effort.bash(BodyAbility.bashPower(body), resistance);
            hits++;
            level.destroyBlockProgress(body.getId(), pane, Math.min(9, (int) (broken * 10)));
            var sound = state.getSoundType(level, pane, body);
            level.playSound(null, pane, sound.getHitSound(), SoundSource.BLOCKS, sound.getVolume(), sound.getPitch());
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), pane.getX()+.5, body.getY()+body.getBbHeight()/2,
                    pane.getZ()+.5, 6, .1, .1, .1, 0);
            float before = body.getHealth();
            body.hurtInternal(body.damageSources().generic(), (float) resistance);
            body.showReaction("teary", "hurt", length);
            Motor.velocity(body, toward.scale(-BodyAbility.of(body).walkSpeed()).add(0, body.getDeltaMovement().y, 0), "bash_recoil");
            GameplayLog.event(body, "action", "hit id=bash block={} hits={} progress={} health={} -> {}", pane, hits, broken, before, body.getHealth());
            if (broken >= 1) {
                if (!level.destroyBlock(pane, false, body)) return Outcome.of(Reason.NO_TOOL, 0, "break_denied");
                GameplayLog.event(body, "action", "break id=bash block={} hits={}", pane, hits);
                return new Outcome.Done("pane_broken hits=" + hits);
            }
        }
        return new Running(Math.max(0, 1 - broken) + (length - ticks % length) / (double) length, TravelMode.PASS);
    }

    @Override public void finished(YukkuriEntity body, Outcome outcome) {
        body.level().destroyBlockProgress(body.getId(), pane, -1);
        if (!(outcome instanceof Outcome.Done))
            GameplayLog.event(body, "action", "blocked id=bash block={} hits={} outcome={}", pane, hits, outcome);
    }
    @Override public MotionPose pose() { return new MotionPose(CLIP, startTick, true); }
    @Override public String startDetail() { return "block=" + pane + " strokeTicks=" + length; }
}
