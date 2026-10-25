package io.github.longshou.mcyukkuri.subspecies.boat;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.BraidRescueGeometry;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.headwear.HatCustody;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.AquaDiag;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.GripRim;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.SelfRescueDeployment;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.State;
import io.github.longshou.mcyukkuri.subspecies.HatBoatRoute;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import io.github.longshou.mcyukkuri.subspecies.SelfRescueHatGeometry;
import io.github.longshou.mcyukkuri.subspecies.crew.CrewTransfer;
import io.github.longshou.mcyukkuri.subspecies.crew.CrewTransfers;
import io.github.longshou.mcyukkuri.subspecies.crew.WaterIncidents;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import static io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.CAPSIZE_RELEASE_TICK;

/**
 * Everything that can happen to the hull itself, with one counter set: water over the rim (flooding), the capsize and the release of
 * its crew and goods, righting, waterlogging and sinking of a capsized hat, burning, and the squeeze-out of a rider the growing crew
 * leaves no seat. It replaces the former capsize helper, the loss helper and the flooding counter of the entity.
 *
 * <p>A hull turns over only for a real cause: a player's shake or blow (the agitation of {@code HatHullMotion.shake}), water coming
 * over the rim, a heavy load rocked by the current ({@link #overloadRoll}), or damage that is not fire. The crew is thrown (the
 * {@code boat_thrown} pose), food and the stick scatter as real floating items, and the hull ends crown-up. Each thrown rider opens a
 * {@link WaterIncidents} incident; the captain decides once for her kin ({@link WaterIncidents#onCapsize}).
 */
public final class HullHazards {
    private static final float BURN_DAMAGE = 6;
    /** A capsized cloth hull has thirty seconds to be righted before waterlogging overcomes its buoyancy. */
    public static final int FLOOD_TICKS = 600, SUBMERGED_TICKS = 200, RIM_FLOOD_LIMIT = 60, SQUEEZE_GRACE = 200;
    private final HatBoatEntity boat;
    /** The one counter set. */
    private float burned;
    private int submerged, flooded, flooding, overflow, squeezeFailures;
    private double floodFraction;
    private boolean capsizeReleased, ownerDied;
    private String cause = "unknown";

    public HullHazards(HatBoatEntity boat) { this.boat = boat; }

    // ================================================================= reads
    /** Water over the rim of an upright hull, 0..100 (100 while capsized). */
    public int flooding() { return flooding; }
    public boolean capsizeReleased() { return capsizeReleased; }
    public void capsizeReleased(boolean released) { capsizeReleased = released; }
    /** The capsized hat has taken on water for good: it sinks. */
    public boolean sinking() { return boat.state() == State.CAPSIZED && flooded >= FLOOD_TICKS; }
    public String detail() { return "flooding=" + flooding + " flooded=" + flooded + " submerged=" + submerged + " burned=" + burned + " overflow=" + overflow + " cause=" + cause; }

    // ================================================================= fire and sinking
    public boolean fireHit(DamageSource source, float amount) {
        if (!source.is(DamageTypeTags.IS_FIRE)) return false;
        burned = Math.min(BURN_DAMAGE, burned + amount);
        if (burned >= BURN_DAMAGE) destroy("burned");
        return true;
    }

    /** Positive physical destruction of the deployed hat (burned, sunk, killed). Unloading and ordinary cleanup are not loss events. */
    public void destroy(String why) {
        if (!(boat.level() instanceof ServerLevel server) || boat.state() == State.RESCUED || boat.state() == State.REMOVED) return;
        var ownerId = boat.ownerYukkuri();
        var token = boat.launchToken();
        // Invalidate the vessel before callbacks can try to reclaim its hat or release its inventory a second time.
        boat.state(State.REMOVED);
        boat.launchToken(null);
        boat.helm().stop("lost");
        boat.cargo().spillAt(boat.position());
        GameplayLog.event(boat, "aquatic", "hazard destroyed cause={} owner={} passengers={} flooding={} flooded={}", why, ownerId, boat.getPassengers().size(), flooding, flooded);
        for (var passenger : List.copyOf(boat.getPassengers())) {
            var at = passenger.position();
            passenger.stopRiding();
            passenger.setPos(at);
            if (passenger instanceof YukkuriEntity y && y.isInWaterOrBubble()) y.waterDistress().plunge(boat.getUUID(), null, WaterIncidents.Cause.HULL_LOST);
        }
        if (ownerId != null && token != null) {
            YukkuriEntity owner = null;
            for (var dimension : server.getServer().getAllLevels())
                if (dimension.getEntity(ownerId) instanceof YukkuriEntity candidate) { owner = candidate; break; }
            if (owner != null) owner.headwear().deployedDestroyed(ownerId, token, why);
            else YukkuriWorldData.get(server).accessoryDestroyed(ownerId, token, why);
        }
        boat.discard();
    }

    // ================================================================= the clock
    /** Once per tick, first thing: waterlogging of a capsized hat, then (each second) the load's roll and the squeeze-out watch. */
    public void tick() {
        if (boat.isRemoved() || boat.state() == State.RESCUED || boat.state() == State.REMOVED) return;
        var waterline = BlockPos.containing(boat.getX(), boat.getY() - .12, boat.getZ());
        if (boat.state() == State.CAPSIZED && boat.level().getFluidState(waterline).is(FluidTags.WATER)) flooded = Math.min(FLOOD_TICKS, flooded + 1);
        else flooded = 0;
        var above = BlockPos.containing(boat.getX(), boat.getBoundingBox().maxY + .1, boat.getZ());
        if (!boat.level().hasChunkAt(above)) submerged = 0;
        else if (boat.state() == State.CAPSIZED && boat.level().getFluidState(above).is(FluidTags.WATER)) {
            if (++submerged >= SUBMERGED_TICKS) destroy("sunk");
        } else submerged = 0;
        if (boat.isRemoved() || boat.tickCount % 20 != 7 || boat.level().isClientSide) return;
        overloadRoll();
        squeezeWatch();
        custodyWatch();
    }

    /** Water over the rim of an upright hull comes in faster the more the hull is loaded; at the limit the hat turns over. */
    public void rimWater(boolean overRim) {
        if (boat.state() == State.CAPSIZED) return;
        if (overRim) {
            floodFraction += boat.rollFactor();
            int whole = (int) floodFraction;
            floodFraction -= whole;
            flooding += whole;
            if (flooding >= RIM_FLOOD_LIMIT) capsize("flooded");
        } else { flooding = Math.max(0, flooding - 1); floodFraction = 0; }
    }

    /**
     * A heavy load is a real risk when the hull is disturbed: while it rocks, or in a current, a full hull may roll over. The chance
     * grows with the share of the load limit above 85 percent and with the current's strength (project design, ecology A3).
     */
    private void overloadRoll() {
        if (!boat.isAfloat() || boat.catches() || boat.getPassengers().isEmpty()) return;
        double max = boat.maxLoad();
        if (max <= 0) return;
        double share = boat.capacityUsed() / max;
        if (share <= .85 || !(boat.rocking() || boat.currentStrength() >= .3f)) return;
        double chance = Math.clamp((share - .85) / .15, 0, 1) * .04 * (1 + boat.currentStrength());
        double roll = boat.level().random.nextDouble();
        if (roll >= chance) return;
        GameplayLog.event(boat, "aquatic", "hazard overload_roll load={}/{} current={} chance={} roll={}", boat.capacityUsed(), max, boat.currentStrength(), chance, roll);
        capsize("overload");
    }

    /**
     * Growth can leave a rider no legal seat. The deck gets a grace period to arrange it; if the conflict stands, the largest rider that
     * can get itself out is squeezed out: one hop over the rim into the water beside the hull, an ordinary swimmer after that.
     */
    private void squeezeWatch() {
        if (!boat.isAfloat() || boat.unsteadyMotion() || boat.hasIncoming()) { overflow = 0; return; }
        if (!boat.deck().anyOverflow()) { overflow = 0; return; }
        overflow += 20;
        if (overflow < SQUEEZE_GRACE) return;
        YukkuriEntity pick = null;
        for (var rider : boat.getPassengers()) {
            if (!(rider instanceof YukkuriEntity y) || y.getUUID().equals(boat.ownerYukkuri())) continue;
            var placed = boat.deck().get(y.getUUID());
            if (placed == null || !placed.overflow() || !CrewTransfer.canStepOut(y) || y.family().crew().active()) continue;
            if (pick == null || y.growthScale() > pick.growthScale()) pick = y;
        }
        if (pick == null) return;
        // The rider has no seat to step from, so the hop may be refused again and again; after three refusals the crowd pushes it over the rim.
        var result = squeezeFailures >= 3 ? null : CrewTransfers.squeezeOut(pick, boat);
        if (result == null) {
            boolean fell = boat.squeezeFall(pick);
            GameplayLog.event(boat, "aquatic", "hazard squeeze_out child={} growth={} mode=pushed fell={} deck={}", pick.getUUID(), pick.growthScale(), fell, boat.assignmentText());
            if (fell) { overflow = 0; squeezeFailures = 0; }
            return;
        }
        GameplayLog.event(boat, "aquatic", "hazard squeeze_out child={} growth={} mode=hop opened={} reason={} deck={}", pick.getUUID(), pick.growthScale(), result.ok(),
                result.ok() ? "-" : result.refusal() + ":" + result.reason(), boat.assignmentText());
        if (result.ok()) { overflow = 0; squeezeFailures = 0; } else squeezeFailures++;
    }

    /**
     * The owner's place in the hull's custody record: aboard it is DEPLOYED; off it while the voyage is not a shore transaction she is
     * ADRIFT. (Boarding is also a real event; this only keeps the record true when it happened without one of the old call sites.)
     */
    private void custodyWatch() {
        var ownerId = boat.ownerYukkuri();
        if (ownerId == null || ownerDied || boat.catches()) return;
        var owner = boat.owner();
        if (owner == null || !owner.isAlive()) {
            // A captain who is not in the world is not dead until the family ledger says so; one whose body has died is.
            boolean dead = owner != null || boat.level() instanceof ServerLevel server
                    && FamilyData.get(server).person(ownerId) != null && FamilyData.get(server).person(ownerId).status == FamilyData.Status.DEAD;
            if (dead) ownerDied(owner);
            return;
        }
        if (boat.launchToken() == null) return;
        var custody = owner.life().custody();
        if (!owner.life().matchesAccessory(boat.ownerYukkuri(), boat.launchToken())) return;
        if (owner.getVehicle() == boat) {
            if (custody.state() != HatCustody.State.DEPLOYED || !boat.getUUID().equals(custody.boat())) custody.board(boat.getUUID(), "aboard");
            return;
        }
        var phase = owner.aquaticLife().voyage().phase();
        if (custody.state() == HatCustody.State.DEPLOYED && !phase.launching() && !phase.landing()
                && (boat.state() == State.AFLOAT || boat.state() == State.CAPSIZED || boat.state() == State.BEACHED))
            custody.adrift(boat.getUUID(), "owner_off_hull");
    }

    /**
     * The captain died. Her hat is no longer anyone's: the custody record ends (LOST, owner_died; a dead owner's hat is never worn by
     * another), the hull keeps only a drift, and her young aboard are left to the kin and neighbours who take them in by the care
     * formula (the family life's refuge for children on an ownerless hull). The hull stays afloat; her body is the remains the death
     * placed (it does not ride the hull yet: see the aquatic doc).
     */
    private void ownerDied(YukkuriEntity owner) {
        ownerDied = true;
        if (owner != null) owner.life().custody().lose("owner_died", false);
        boat.helm().stop("owner_died");
        int young = 0;
        for (var rider : boat.getPassengers()) if (rider instanceof YukkuriEntity y && !y.getUUID().equals(boat.ownerYukkuri())) {
            young++;
            y.mind().felt(io.github.longshou.mcyukkuri.mind.EmotionEvent.FRIGHTENED);
            y.showReaction("teary", "afraid", 80);
        }
        GameplayLog.event(boat, "aquatic", "hazard owner_died owner={} state={} passengers={} young={} cause=owner_died",
                boat.ownerYukkuri(), boat.state(), boat.getPassengers().size(), young);
    }
    public boolean ownerDied() { return ownerDied; }

    // ================================================================= grips and righting
    public boolean ownsPhysicalHat(YukkuriEntity actor) {
        return actor != null && actor.getUUID().equals(boat.ownerYukkuri()) && actor.life().matchesAccessory(boat.ownerYukkuri(), boat.launchToken());
    }

    public Vec3 rimPoint(Vec3 local) { return tiltedWorld(local).subtract(0, boat.rimLift(local), 0); }

    public Vec3 nearestGrip(YukkuriEntity actor) {
        if (actor == null || !(ownsPhysicalHat(actor) || boat.young(actor)) || !(boat.isAfloat() || boat.state() == State.CAPSIZED) || boat.capsizing() || boat.deploying()) return null;
        Vec3 best = null;
        double score = Double.POSITIVE_INFINITY;
        for (var local : GripRim.points(boat.mode())) {
            var point = rimPoint(local);
            double gap = actor.getBoundingBox().getCenter().distanceToSqr(point);
            if (gap < score) { score = gap; best = point; }
        }
        return best;
    }

    public Vec3 selfRescueGripLocal(YukkuriEntity actor) {
        var world = selfRescueGrip(actor);
        if (world == null) return null;
        for (var local : GripRim.points(boat.mode())) if (rimPoint(local).distanceToSqr(world) < 1e-8) return local;
        return null;
    }

    public Vec3 selfRescueGrip(YukkuriEntity actor, Vec3 localGrip) {
        if (localGrip == null || GripRim.points(boat.mode()).stream().noneMatch(p -> p.distanceToSqr(localGrip) < 1e-8)
                || !(ownsPhysicalHat(actor) || boat.young(actor)) || boat.capsizing() || boat.deploying()) return null;
        return reachableGrip(actor, rimPoint(localGrip));
    }

    public Vec3 selfRescueGrip(YukkuriEntity actor) {
        if (nearestGrip(actor) == null) return null;
        Vec3 best = null;
        double score = Double.POSITIVE_INFINITY;
        var mouth = BraidRescueGeometry.mouthPoint(actor, actor.position(), actor.yBodyRot);
        for (var local : GripRim.points(boat.mode())) {
            var grip = rimPoint(local);
            if (reachableGrip(actor, grip) == null) continue;
            double distance = mouth.distanceToSqr(grip);
            if (distance < score) { best = grip; score = distance; }
        }
        return best;
    }

    public Vec3 reachableGrip(YukkuriEntity actor, Vec3 grip) {
        if (grip == null || !actor.getBoundingBox().inflate(.12).contains(grip)) return null;
        var hit = boat.level().clip(new ClipContext(actor.getEyePosition(), grip, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, actor));
        return hit.getType() == HitResult.Type.MISS ? grip : null;
    }

    public Vec3 ownGripApproach(YukkuriEntity actor) {
        var grip = nearestGrip(actor);
        if (grip == null) return null;
        var out = actor.position().subtract(grip).multiply(1, 0, 1).normalize();
        if (out.horizontalDistanceSqr() < .01) out = grip.subtract(boat.position()).multiply(1, 0, 1).normalize();
        return new Vec3(grip.x + out.x * (actor.getBbWidth() * .5 + .03), actor.getY(), grip.z + out.z * (actor.getBbWidth() * .5 + .03));
    }

    /** One step of righting: the mouth holds the brim at {@code localGrip} and flips it while the braid pushes the far side. */
    public boolean setSelfRighting(YukkuriEntity actor, float progress, Vec3 localGrip) {
        if (boat.level().isClientSide || !ownsPhysicalHat(actor) || boat.state() != State.CAPSIZED || !capsizeReleased || boat.isVehicle() || boat.hasIncoming()
                || selfRescueGrip(actor, localGrip) == null || !HatBoatRoute.calmSurface(boat.level(), BlockPos.containing(boat.position().add(0, -.12, 0)))) return false;
        var m = boat.motionData().copy();
        float previous = m.getFloat("RightProgress");
        if (!m.contains("RightGripX")) {
            HatBoatEntity.point(m, "RightGrip", localGrip);
            HatBoatEntity.point(m, "RightAnchor", tiltedWorld(localGrip));
            // A previously dropped hat has finished its free tumble before a new real grip is accepted.
            m.remove("LooseAt"); m.remove("LoosePitch"); m.remove("LooseSquash");
        } else if (HatBoatEntity.point(m, "RightGrip").distanceToSqr(localGrip) > 1e-8) return false;
        m.putFloat("RightPrevious", previous);
        m.putFloat("RightProgress", Math.clamp(progress, 0, 1));
        m.putLong("RightTouched", boat.level().getGameTime());
        var before = boat.motionData();
        boat.syncMotion(m);
        var offset = tiltedWorld(localGrip).subtract(boat.position());
        var centre = HatBoatEntity.point(m, "RightAnchor").subtract(offset);
        var movement = centre.subtract(boat.position());
        if (movement.lengthSqr() > .12 * .12 || !boat.level().noCollision(boat, boat.getBoundingBox().move(movement))) { boat.syncMotion(before); return false; }
        // Rotating around the actually held rim translates the same boat centre. Clients apply
        // motionRoll/Pitch at that synced centre, yielding exactly this world-space grasp pivot.
        boat.setPos(centre);
        boat.setDeltaMovement(Vec3.ZERO);
        boat.helm().stop("capsize_reset");
        return true;
    }

    public boolean finishSelfRighting(YukkuriEntity actor) {
        if (boat.level().isClientSide || !ownsPhysicalHat(actor) || !boat.selfRighting() || boat.motionData().getFloat("RightProgress") < 1
                || selfRescueGrip(actor, HatBoatEntity.point(boat.motionData(), "RightGrip")) == null) return false;
        boat.state(State.AFLOAT);
        flooding = 0;
        flooded = 0;
        boat.syncMotion(new CompoundTag());
        capsizeReleased = true;
        boat.helm().stop("righted");
        GameplayLog.event(boat, "aquatic", "hazard righted actor={} state=AFLOAT flooding=0 cause={}", actor.getUUID(), cause);
        return true;
    }

    public void tickSelfRighting() {
        var m = boat.motionData();
        if (!boat.selfRighting() || boat.level().getGameTime() - m.getLong("RightTouched") <= 1) return;
        var copy = m.copy();
        float progress = m.getFloat("RightProgress");
        copy.putFloat("RightPrevious", progress);
        copy.putFloat("RightProgress", Math.max(0, progress - .04f));
        if (progress <= .04) {
            copy.remove("RightProgress"); copy.remove("RightPrevious"); copy.remove("RightTouched");
            for (var axis : new String[]{"X", "Y", "Z"}) { copy.remove("RightGrip" + axis); copy.remove("RightAnchor" + axis); }
        }
        boat.syncMotion(copy);
    }

    // ================================================================= taking the hat off the head to right it
    public static SelfRescueDeployment planSelfRescueDeployment(YukkuriEntity actor) {
        if (!(actor.level() instanceof ServerLevel level) || !actor.profile().aquatic() || !actor.isAlive() || !actor.isInWaterOrBubble()
                || actor.isPassenger() || actor.life().custody().custodied() || !actor.life().hasAccessory()) return null;
        var water = BlockPos.containing(actor.position());
        if (!HatBoatRoute.calmSurface(level, water)) { AquaDiag.log(actor, "self.surface." + actor.getId(), 40, "not a calm surface at " + water); return null; }
        var path = SelfRescueHatGeometry.plan(actor, HatBoatRoute.surface(level, water).y,
                candidate -> selfRescueDeploymentReachable(actor, new SelfRescueDeployment(candidate.finalCentre(), candidate.boatYaw(), candidate)));
        return path == null ? null : new SelfRescueDeployment(path.finalCentre(), path.boatYaw(), path);
    }

    public static boolean selfRescueDeploymentReachable(YukkuriEntity actor, SelfRescueDeployment plan) {
        if (plan == null || !(actor.level() instanceof ServerLevel level)) return false;
        if (plan.path() == null) return false;
        var water = BlockPos.containing(plan.position().add(0, -.12, 0));
        boolean inside = level.getWorldBorder().isWithinBounds(water);
        boolean flat = Math.abs(HatBoatRoute.surface(level, water).y - plan.position().y) < .08;
        var fit = HullFootprint.fits(level, actor, null, plan.position(), plan.yaw(), HatVessel.hatScale(actor) * actor.getScale(), 0);
        if (!(inside && flat && fit.ok())) AquaDiag.log(actor, "self.spot." + actor.getId(), 40, "hat spot " + AquaDiag.at(plan.position()) + " bounds="
                + inside + " level=" + flat + " fit=" + fit.reason() + " " + fit.detail());
        return inside && flat && fit.ok();
    }

    public static HatBoatEntity deployForSelfRescue(YukkuriEntity actor, SelfRescueDeployment plan) {
        return deployForSelfRescue(actor, plan, null, SelfRescueHatGeometry.REMOVE_TICKS);
    }

    public static HatBoatEntity deployForSelfRescue(YukkuriEntity actor, SelfRescueDeployment plan, SelfRescueHatGeometry.Pose heldPose, float elapsed) {
        if (!(actor.level() instanceof ServerLevel) || !selfRescueDeploymentReachable(actor, plan)) return null;
        var water = BlockPos.containing(plan.position().add(0, -.12, 0));
        var boat = HullFactory.boatOf(HullFactory.deploy(actor, new HullFactory.Spec(HullFactory.Origin.SWIMMING, plan.position(), plan.yaw(),
                HatVessel.hatScale(actor) * actor.getScale(), water, null, 0)));
        if (boat == null) return null;
        // The held head hat has already turned and lowered to this exact upright pose. Never flip it a second time.
        boat.syncMotion(new CompoundTag());
        boat.setDeltaMovement(actor.getDeltaMovement().multiply(.65, 0, .65));
        if (heldPose != null && !boat.setSelfRescueCarryPose(actor, heldPose, elapsed)) boat.releaseSelfRescueCarry();
        return boat;
    }

    public boolean setSelfRescueCarryPose(YukkuriEntity actor, SelfRescueHatGeometry.Pose pose, float elapsed) {
        if (boat.level().isClientSide || !ownsPhysicalHat(actor) || boat.isVehicle() || boat.hasIncoming() || pose == null) return false;
        double q = Math.clamp((elapsed - SelfRescueHatGeometry.SHAKE) / SelfRescueHatGeometry.TURN, 0, 1);
        q = q * q * (3 - 2 * q);
        var offset = pose.rotation().transform(new Vector3f(0, (float) (HatVessel.freeboard(boat.mode()) * boat.unit()), 0));
        var centre = pose.origin().subtract(new Vec3(offset.x, offset.y, offset.z));
        var m = boat.motionData().copy();
        m.putFloat("PreviousPitch", m.getBoolean("SelfHeld") ? m.getFloat("HeldPitch") : (float) (-180 * (1 - q)));
        m.putFloat("PreviousSquash", m.getBoolean("SelfHeld") ? m.getFloat("HeldSquash") : pose.squash());
        m.putBoolean("SelfHeld", true);
        m.putFloat("HeldPitch", (float) (-180 * (1 - q)));
        m.putFloat("HeldSquash", pose.squash());
        m.putLong("HeldTouched", boat.level().getGameTime());
        HatBoatEntity.point(m, "PreviousCentre", m.contains("HeldCentreX") ? HatBoatEntity.point(m, "HeldCentre") : centre);
        HatBoatEntity.point(m, "HeldCentre", centre);
        boat.setPos(centre);
        boat.syncMotion(m);
        boat.setDeltaMovement(Vec3.ZERO);
        boat.helm().stop("capsize_reset");
        return true;
    }

    public void finishSelfRescueCarry(YukkuriEntity actor) {
        if (boat.level().isClientSide || !ownsPhysicalHat(actor) || !boat.selfRescueHeld()) return;
        boat.syncMotion(new CompoundTag());
        boat.setDeltaMovement(actor.getDeltaMovement().multiply(.65, 0, .65));
    }

    public void releaseSelfRescueCarry() {
        if (boat.level().isClientSide || !boat.selfRescueHeld()) return;
        float pitch = boat.motionPitch(1), squash = boat.hullSquash(1);
        boat.syncMotion(new CompoundTag());
        startCapsize(1, "dropped_while_held");
        var m = boat.motionData().copy();
        m.putLong("LooseAt", boat.level().getGameTime());
        HatBoatEntity.point(m, "HeldCentre", boat.position());
        HatBoatEntity.point(m, "PreviousCentre", boat.position());
        m.putFloat("LoosePitch", pitch);
        m.putFloat("LooseSquash", squash);
        boat.syncMotion(m);
    }

    // ================================================================= capsize
    /** The hull turns over; {@code why} is the real cause (shaken, struck, flooded, overload, current). */
    public void capsize(String why) {
        int side = boat.motionData().contains("Side") ? boat.motionData().getInt("Side") : ((boat.getId() & 1) == 0 ? 1 : -1);
        startCapsize(side, why);
    }

    public void startCapsize(int side, String why) {
        if (boat.level().isClientSide || boat.state() == State.CAPSIZED || boat.state() == State.RESCUED || boat.state() == State.REMOVED || boat.catches()) return;
        boolean wasHeld = boat.selfRescueHeld();
        float heldPitch = wasHeld ? boat.motionPitch(1) : 0, heldSquash = wasHeld ? boat.hullSquash(1) : (float) HatVessel.squash(boat.mode());
        boat.freezeSeatsForCapsize();
        var m = new CompoundTag();
        m.putLong("CapAt", boat.level().getGameTime());
        m.putInt("Side", side < 0 ? -1 : 1);
        if (wasHeld) {
            m.putLong("LooseAt", boat.level().getGameTime());
            m.putFloat("LoosePitch", heldPitch);
            m.putFloat("LooseSquash", heldSquash);
            HatBoatEntity.point(m, "HeldCentre", boat.position());
            HatBoatEntity.point(m, "PreviousCentre", boat.position());
        }
        boat.syncMotion(m);
        capsizeReleased = false;
        cause = why;
        boat.clearStations();
        boat.state(State.CAPSIZED);
        flooding = 100;
        boat.helm().stop("capsized");
        boat.setDeltaMovement(boat.getDeltaMovement().scale(.25));
        GameplayLog.event(boat, "aquatic", "hazard capsize cause={} owner={} side={} passengers={} cargo={} paddle={} load={}/{} agitation={}",
                why, boat.ownerYukkuri(), side, boat.getPassengers().size(), boat.cargo().count(), boat.cargo().paddle(), boat.capacityUsed(), boat.maxLoad(), boat.agitation());
    }

    /** Same yaw * roll * pitch transform as the client, used for each real release point. */
    public Vec3 tiltedWorld(Vec3 local) {
        float current = boat.selfRighting() ? 1 : 0;
        double roll = Math.toRadians(boat.motionRoll(current)), pitch = Math.toRadians(boat.motionPitch(current));
        var p = local.scale(boat.unit());
        double py = p.y * Math.cos(pitch) - p.z * Math.sin(pitch), pz = p.y * Math.sin(pitch) + p.z * Math.cos(pitch);
        return boat.position().add(new Vec3(p.x * Math.cos(roll) - py * Math.sin(roll), p.x * Math.sin(roll) + py * Math.cos(roll), pz).yRot(boat.turn()));
    }

    /**
     * The release, a few ticks into the roll: every rider is thrown into the water (an incident each, opened by {@code plunge}), the
     * larder and the stick scatter as real floating items, and the captain decides once for her kin.
     */
    public void tickCapsize() {
        if (boat.state() != State.CAPSIZED || capsizeReleased || !boat.motionData().contains("CapAt")
                || boat.motionAge(boat.motionData(), "CapAt", 0) < CAPSIZE_RELEASE_TICK) return;
        // Mark before mutating/spawning: re-entry and subsequent damage cannot spill these items twice.
        capsizeReleased = true;
        int side = boat.motionData().getInt("Side") < 0 ? -1 : 1;
        var downhill = new Vec3(-side, 0, 0).yRot(boat.turn());
        var along = new Vec3(0, 0, 1).yRot(boat.turn());
        var passengers = List.copyOf(boat.getPassengers());
        var positions = new ArrayList<Vec3>();
        var thrown = new ArrayList<YukkuriEntity>();
        var releasedYoung = new ArrayList<UUID>();
        for (var p : passengers) positions.add(tiltedWorld(boat.toLocal(p.position())));
        var incidentCause = "shaken".equals(this.cause) ? WaterIncidents.Cause.SHAKEN : WaterIncidents.Cause.CAPSIZE;
        for (int i = 0; i < passengers.size(); i++) {
            var p = passengers.get(i);
            var at = positions.get(i).add(downhill.scale(.035 + i * .008));
            p.stopRiding();
            p.setPos(at.x, at.y, at.z);
            p.setDeltaMovement(boat.getDeltaMovement().add(downhill.scale(.07 + i * .013))
                    .add(along.scale((i - (passengers.size() - 1) * .5) * .018)).add(0, .025 - i * .006, 0));
            p.hasImpulse = true;
            if (p instanceof YukkuriEntity y) {
                y.waterDistress().plunge(boat.getUUID(), null, incidentCause);
                thrown.add(y);
                if (boat.young(y)) releasedYoung.add(y.getUUID());
            }
            if (boat.level() instanceof ServerLevel server) server.sendParticles(ParticleTypes.SPLASH,
                    at.x, at.y, at.z, Math.max(4, (int) (p.getBbWidth() * 12)), p.getBbWidth() * .3, .04, p.getBbWidth() * .3, .08);
        }
        var owner = boat.owner();
        if (owner != null && thrown.contains(owner)) owner.life().custody().adrift(boat.getUUID(), "capsized");
        WaterIncidents.onCapsize(boat, owner, thrown);
        var spillZones = boat.capsizeSpillZones();
        var drops = new ArrayList<ItemStack>(boat.cargo().portions());
        var paddle = boat.cargo().paddleItem();
        if (!paddle.isEmpty()) drops.add(paddle);
        int paddleDrop = drops.size() - 1;
        boat.cargo().load(new CompoundTag());
        boat.syncCargo(boat.cargo().save());
        for (int i = 0; i < drops.size(); i++) {
            var local = spillZones.isEmpty() ? new Vec3(0, HatVessel.ownerY(boat.mode()), 0) : spillZones.get(i % spillZones.size()).position();
            var at = tiltedWorld(local).add(downhill.scale(.025));
            var item = new ItemEntity(boat.level(), at.x, at.y, at.z, drops.get(i));
            item.setDeltaMovement(boat.getDeltaMovement().add(downhill.scale(.08 + i * .008))
                    .add(along.scale((i - (drops.size() - 1) * .5) * .014)).add(0, .03, 0));
            item.setDefaultPickUpDelay();
            boat.level().addFreshEntity(item);
            if (!paddle.isEmpty() && i == paddleDrop) boat.cargo().notePaddleDrop(item);
        }
        GameplayLog.event(boat, "aquatic", "hazard released cause={} owner={} passengers={} young={} cargoDropAttempts={} paddle={} cargoNow={}",
                this.cause, boat.ownerYukkuri(), passengers.size(), releasedYoung, drops.size(), !paddle.isEmpty(), boat.cargo().count());
    }

    // ================================================================= persistence
    public void save(CompoundTag tag) {
        tag.putFloat("HullBurnDamage", burned);
        tag.putInt("HullSubmergedTicks", submerged);
        tag.putInt("HullFloodedTicks", flooded);
        tag.putInt("Flooding", flooding);
        if (ownerDied) tag.putBoolean("OwnerDied", true);
    }

    public void load(CompoundTag tag) {
        burned = Math.clamp(tag.getFloat("HullBurnDamage"), 0, BURN_DAMAGE);
        submerged = Math.clamp(tag.getInt("HullSubmergedTicks"), 0, SUBMERGED_TICKS);
        flooded = Math.clamp(tag.getInt("HullFloodedTicks"), 0, FLOOD_TICKS);
        flooding = Math.clamp(tag.getInt("Flooding"), 0, 100);
        ownerDied = tag.getBoolean("OwnerDied");
    }
}
