package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.*;
import io.github.longshou.mcyukkuri.motor.geom.Effort;
import io.github.longshou.mcyukkuri.motor.geom.RamContact;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.EventHooks;

/** A grounded adult rams one pane; authored part geometry sets the stroke and vanilla resolves its travel. */
public final class Bash implements Action {
    public static final String ID = "bash";
    private static final String CLIP = RamContact.CLIP;
    /** The caller owns this damage record across actions; a stroke is recorded exactly once. */
    public static final class Damage {
        private int hits;
        private double broken;
        public Damage(int hits, double broken) { this.hits=hits; this.broken=broken; }
        public int hits() { return hits; }
        public double broken() { return broken; }
        private void strike(double effort) { hits++; broken+=effort; }
        private int crack() { return hits==0 ? -1 : Math.min(9,(int)(broken*10)); }
        /** Display only. The claim's damage is the sole authority; client crack entries expire after 400 ticks. */
        public void display(Level level, BlockPos pane, int id) {
            if(level.hasChunkAt(pane))level.destroyBlockProgress(id,pane,
                    broken<1 && level.getBlockState(pane).is(Tags.Blocks.GLASS_PANES)?crack():-1);
        }
    }
    private final BlockPos pane;
    private final Damage damage;
    private final int crackId;
    private int ticks;
    private long startTick;
    private final int length = ClipMarkers.of(CLIP).length();
    private final int impactTick = ClipMarkers.of(CLIP).ticks("impact", 0);
    private final int launchTick = ClipMarkers.of(CLIP).ticks("launch", 0);
    private Vec3 face, outward, stand;

    private static double scale(YukkuriEntity body) { return body.growthScale()*body.getScale()/16; }
    public static double standOff(YukkuriEntity body) { return RamContact.of(body.assetId()).turn()*scale(body); }

    public Bash(BlockPos pane, Damage damage, int crackId) { this.pane = pane.immutable(); this.damage=damage; this.crackId=crackId; }
    public BlockPos pane() { return pane; }
    public int hits() { return damage.hits(); }
    @Override public String id() { return ID; }

    /** Whether this actor may destroy panes; a denied permission is not a personality choice. */
    public static Feasibility permission(YukkuriEntity body) {
        if (!body.status().identity().free() || Custody.protectedAnimal(body))
            return Feasibility.of(Reason.NO_TOOL, 0, "protected_animal");
        if (!EventHooks.canEntityGrief((ServerLevel) body.level(), body))
            return Feasibility.of(Reason.NO_TOOL, 0, "mob_griefing_denied");
        return Feasibility.pass();
    }

    /** Also used during route choice: permissions are checked again on every stroke. */
    public static Feasibility allowed(YukkuriEntity body, BlockPos pane) {
        if (!body.level().getBlockState(pane).is(Tags.Blocks.GLASS_PANES))
            return Feasibility.of(Reason.NO_TOOL, 0, "not_glass_pane");
        var permission = permission(body);
        if (!permission.ok()) return permission;
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

    /** The collider face the body's centre line meets on its level way to the pane. */
    private static BlockHitResult contact(YukkuriEntity body, BlockPos pane) {
        var from = body.position().add(0, body.getBbHeight() / 2, 0);
        Vec3 aim = Vec3.atCenterOf(pane);
        return body.level().clip(new ClipContext(from, new Vec3(aim.x, from.y, aim.z),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body));
    }
    private static float square(Vec3 outward) { return SurfaceApproach.yaw(Vec3.ZERO, outward.reverse()); }
    /** Squared up to the pane face: the authored straight-ahead reach is then the reach into the pane, even off its centre line. */
    public static float squareYaw(YukkuriEntity body, BlockPos pane) {
        return square(Vec3.atLowerCornerOf(contact(body, pane).getDirection().getNormal()));
    }

    @Override public Feasibility check(YukkuriEntity body) {
        var allowed = allowed(body, pane);
        if (!allowed.ok()) return allowed;
        var hit = contact(body, pane);
        var normal = Vec3.atLowerCornerOf(hit.getDirection().getNormal());
        if (Math.abs(Mth.wrapDegrees(square(normal) - body.yBodyRot)) > Face.FACE_ARRIVED)
            return Feasibility.of(Reason.NO_CONTACT, 0, "face_pane_first");
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pane))
            return Feasibility.of(Reason.NO_CONTACT, 0, "front_obstructed");
        // PaneOpening adds half the walk arrival margin outside the rotational envelope, along the face normal.
        double gap = body.position().subtract(hit.getLocation()).dot(normal) - standOff(body) - Walk.STAND_ARRIVED/2;
        return gap <= Walk.STAND_ARRIVED ? Feasibility.pass()
                : Feasibility.of(Reason.NO_CONTACT, gap, "approach_pane_first");
    }

    @Override public void start(YukkuriEntity body) {
        body.getNavigation().stop();
        startTick = body.level().getGameTime();
        var hit=contact(body,pane);
        outward=Vec3.atLowerCornerOf(hit.getDirection().getNormal());
        face=new Vec3(pane.getX()+.5,body.getY(),pane.getZ()+.5);
        face=face.add(outward.scale(hit.getLocation().subtract(face).dot(outward)));
        stand=body.position();
        damage.display(body.level(),pane,crackId);
    }

    @Override public Progress tick(YukkuriEntity body) {
        // The stroke is a physical, bounded launch/recoil. A competing need takes over at its settled marker.
        Motor.face(body, body.yBodyRot + (float) Effort.turn(body.yBodyRot, square(outward), BodyAbility.of(body).turnRate()));
        boolean opened=damage.broken()>=1 && !body.level().getBlockState(pane).is(Tags.Blocks.GLASS_PANES);
        if (!opened) {
            var feasible = check(body);
            if (!feasible.ok()) return Outcome.of(feasible.reason(), feasible.shortfall(), feasible.detail());
        }
        int phase=ticks%length;
        var contact=RamContact.of(body.assetId());
        // The impact marker falls between server ticks: that tick lands at the marker's exact contact pose.
        double seconds=phase==impactTick?contact.strike():phase==launchTick?contact.launch():phase==length-1?contact.settled():phase/20.0;
        double standDistance=stand.subtract(face).dot(outward);
        double distance=contact.distance(standDistance/scale(body),seconds)*scale(body);
        var target=stand.add(outward.scale(distance-standDistance));
        Motor.velocity(body,new Vec3(0,body.getDeltaMovement().y,0),"bash_stroke");
        Motor.move(body,target.subtract(body.position()).multiply(1,0,1),"bash_stroke");
        ticks++;
        if (phase == impactTick && !opened) {
            double gap=body.position().subtract(face).dot(outward)-contact.impact()*scale(body);
            // Sub-milliblock arithmetic tolerance; authored contact, not vanilla's smaller body box, is the hit.
            if (Math.abs(gap) > 1.0/1024) return Outcome.of(Reason.NO_CONTACT, Math.abs(gap), "stroke_obstructed");
            var level = (ServerLevel) body.level();
            var state = level.getBlockState(pane);
            double resistance = state.getDestroySpeed(level, pane);
            damage.strike(Effort.bash(BodyAbility.bashPower(body), resistance));
            damage.display(level,pane,crackId);
            var sound = state.getSoundType(level, pane, body);
            level.playSound(null, pane, sound.getHitSound(), SoundSource.BLOCKS, sound.getVolume(), sound.getPitch());
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), pane.getX()+.5, body.getY()+body.getBbHeight()/2,
                    pane.getZ()+.5, 6, .1, .1, .1, 0);
            float before = body.getHealth();
            body.hurtInternal(body.damageSources().generic(), (float) resistance);
            body.showReaction("teary", "hurt", length);
            GameplayLog.event(body, "action", "hit id=bash block={} hits={} progress={} health={} -> {} contactGap={} strokeTravel={}",
                    pane, hits(), damage.broken(), before, body.getHealth(),gap,standDistance-distance);
            if (damage.broken() >= 1) {
                if (!level.destroyBlock(pane, false, body)) return Outcome.of(Reason.NO_TOOL, 0, "break_denied");
                GameplayLog.event(body, "action", "break id=bash block={} hits={}", pane, hits());
            }
        }
        if (phase==length-1 && damage.broken()>=1 && !body.level().getBlockState(pane).is(Tags.Blocks.GLASS_PANES))
            return new Outcome.Done("pane_broken hits="+hits());
        return new Running(Math.max(0, 1 - damage.broken()) + (length - ticks % length) / (double) length, TravelMode.PASS);
    }
    @Override public boolean interruptible() { int phase=ticks%length; return phase<=launchTick; }

    @Override public void finished(YukkuriEntity body, Outcome outcome) {
        damage.display(body.level(),pane,crackId);
        if (!(outcome instanceof Outcome.Done))
            GameplayLog.event(body, "action", "blocked id=bash block={} hits={} outcome={}", pane, hits(), outcome);
    }
    @Override public MotionPose pose() { return new MotionPose(CLIP, startTick+(Math.max(0,ticks-1)/length)*length, false); }
    @Override public String startDetail() { return "block=" + pane + " strokeTicks=" + length + " hits=" + hits() + " crack=" + damage.crack(); }
}
