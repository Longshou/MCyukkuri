package io.github.longshou.mcyukkuri.subspecies.boat;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.BraidRescueGeometry;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.headwear.HatCustody;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.life.YukkuriLocomotion;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.GripRim;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.SelfRescueDeployment;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.State;
import io.github.longshou.mcyukkuri.subspecies.HatBoatRoute;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import io.github.longshou.mcyukkuri.subspecies.SelfRescueHatGeometry;
import io.github.longshou.mcyukkuri.subspecies.crew.CrewTransfer;
import io.github.longshou.mcyukkuri.subspecies.crew.CrewTransfers;
import io.github.longshou.mcyukkuri.subspecies.crew.CrewReach;
import io.github.longshou.mcyukkuri.subspecies.crew.WaterIncidents;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.MoverType;
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
    /** The largest physical hull translation a single self-righting tick may make. */
    private static final double SELF_RIGHT_STEP = .12, RIGHT_PROGRESS_EPSILON = 1e-4;
    /** A capsized cloth hull has thirty seconds to be righted before waterlogging overcomes its buoyancy. */
    public static final int FLOOD_TICKS = 600, SUBMERGED_TICKS = 200, RIM_FLOOD_LIMIT = 60, SQUEEZE_GRACE = 200;
    private final HatBoatEntity boat;
    /** The one counter set. */
    private float burned;
    private int submerged, flooded, flooding, overflow, squeezeFailures;
    private double floodFraction;
    private boolean capsizeReleased, ownerDied;
    private String cause = "unknown";
    /** The last rejected righting precondition, exposed only as a read-only diagnostic for the active swimmer. */
    private String selfRightingRefusal = "none";
    private long rightedAt = -1;
    private double rightedY;
    private record Separation(Vec3 outward, long until) {}
    private final Map<UUID, Separation> separating = new HashMap<>();

    public HullHazards(HatBoatEntity boat) { this.boat = boat; }

    // ================================================================= reads
    /** Water over the rim of an upright hull, 0..100 (100 while capsized). */
    public int flooding() { return flooding; }
    public boolean capsizeReleased() { return capsizeReleased; }
    public void capsizeReleased(boolean released) { capsizeReleased = released; }
    public String selfRightingRefusal() { return selfRightingRefusal; }
    /** The capsized hat has taken on water for good: it sinks. */
    public boolean sinking() { return boat.state() == State.CAPSIZED && flooded >= FLOOD_TICKS; }
    public String detail() { return "flooding=" + flooding + " flooded=" + flooded + " submerged=" + submerged + " burned=" + burned + " overflow=" + overflow + " cause=" + cause; }

    // ================================================================= fire and sinking
    public boolean fireHit(DamageSource source, float amount) {
        if (!source.is(DamageTypeTags.IS_FIRE)) return false;
        if ((boat.isAfloat() || boat.state() == State.CAPSIZED)
                && boat.level().getFluidState(BlockPos.containing(boat.getX(), boat.getY() - .12, boat.getZ())).is(FluidTags.WATER)) {
            GameplayLog.limited(boat, "aquatic", "fire.quenched", 200, "hazard fire quenched state={} damage={} burned={}", boat.state(), amount, burned);
            return true;
        }
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
            if (passenger instanceof YukkuriEntity body) YukkuriLocomotion.detach(body);
            else passenger.stopRiding();
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
        separateSwimmers();
        observeRise();
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
     * ADRIFT. (Boarding is also a real event; this only keeps the record true when it happened without one.)
     */
    private void custodyWatch() {
        var ownerId = boat.ownerYukkuri();
        if (ownerId == null || ownerDied) return;
        var owner = boat.owner();
        if (owner == null || !owner.isAlive()) {
            // A captain who is not in the world is not dead until the family ledger says so; one whose body has died is.
            boolean dead = owner != null || boat.level() instanceof ServerLevel server
                    && FamilyData.get(server).person(ownerId) != null && FamilyData.get(server).person(ownerId).status == FamilyData.Status.DEAD;
            if (dead) ownerDied(owner);
            return;
        }
        // A cradle or moored catch hat never participates in voyage custody reconciliation, but its
        // owner-death transition above must still run so it cannot retain a dead captain's custody.
        if (boat.catches()) return;
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
        if (custody.adrift()) custody.requestReclaim("observed_adrift");
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
        var local = nearestGripLocal(actor);
        return local == null ? null : rimPoint(local);
    }

    private Vec3 nearestGripLocal(YukkuriEntity actor) {
        if (actor == null || !(ownsPhysicalHat(actor) || boat.young(actor)) || !(boat.isAfloat() || boat.state() == State.CAPSIZED) || boat.capsizing() || boat.deploying()) return null;
        Vec3 best = null;
        double score = Double.POSITIVE_INFINITY;
        for (var local : GripRim.points(boat.mode())) {
            var point = rimPoint(local);
            double gap = actor.getBoundingBox().getCenter().distanceToSqr(point);
            if (gap < score) { score = gap; best = local; }
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
        var grip = rimPoint(localGrip);
        // Requiring the water-facing side prevents a first bite through the overturned crown.  Once this exact rim
        // point is the synchronized righting hold, rotation necessarily takes its outward normal through the swimmer;
        // ongoing contact is instead checked by reachableGrip below.
        if (boat.state() == State.CAPSIZED && !lockedRightGrip(localGrip) && !outsideCapsizedRim(actor, localGrip, grip)) return null;
        return reachableGrip(actor, grip);
    }

    private boolean lockedRightGrip(Vec3 localGrip) {
        var motion = boat.motionData();
        return boat.selfRighting() && motion.contains("RightGripX")
                && HatBoatEntity.point(motion, "RightGrip").distanceToSqr(localGrip) <= 1e-8;
    }

    public Vec3 selfRescueGrip(YukkuriEntity actor) {
        if (nearestGrip(actor) == null) return null;
        Vec3 best = null;
        double score = Double.POSITIVE_INFINITY;
        var mouth = BraidRescueGeometry.mouthPoint(actor, actor.position(), actor.yBodyRot);
        for (var local : GripRim.points(boat.mode())) {
            var grip = rimPoint(local);
            if (boat.state() == State.CAPSIZED && !outsideCapsizedRim(actor, local, grip)) continue;
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
        var local = nearestGripLocal(actor);
        if (local == null) return null;
        var grip = rimPoint(local);
        var out = brimOutward(local, grip);
        return new Vec3(grip.x + out.x * (actor.getBbWidth() * .5 + .03), actor.getY(), grip.z + out.z * (actor.getBbWidth() * .5 + .03));
    }

    /**
     * The swimmer approaches the outward side of the actual tilted brim.  Actor-to-grip is deliberately not used:
     * after a capsize the actor can be below the crown, where that vector points back into the overturned hat.
     */
    private Vec3 brimOutward(Vec3 local, Vec3 grip) {
        var radial = new Vec3(local.x, 0, local.z);
        if (radial.horizontalDistanceSqr() < 1e-8) radial = new Vec3(0, 0, -1);
        var transformed = tiltedWorld(local.add(radial.normalize())).subtract(grip).multiply(1, 0, 1);
        if (transformed.horizontalDistanceSqr() < 1e-8) transformed = radial.yRot(boat.turn()).multiply(1, 0, 1);
        return transformed.normalize();
    }

    /** A capsized swimmer must reach the water-facing side of the brim before it can bite; no under-crown grip is valid. */
    private boolean outsideCapsizedRim(YukkuriEntity actor, Vec3 local, Vec3 grip) {
        if (actor == null) return false;
        var fromRim = actor.position().subtract(grip).multiply(1, 0, 1);
        return fromRim.dot(brimOutward(local, grip)) >= -.01;
    }

    /** One step of righting: the mouth holds the brim at {@code localGrip} and flips it while the braid pushes the far side. */
    public boolean setSelfRighting(YukkuriEntity actor, float progress, Vec3 localGrip) {
        selfRightingRefusal = "none";
        if (boat.level().isClientSide) return refuseSelfRighting(actor, "client", progress, 0, Vec3.ZERO);
        if (!ownsPhysicalHat(actor) && !mayReclaim(actor)) return refuseSelfRighting(actor, "physical_hat_lost", progress, 0, Vec3.ZERO);
        if (boat.state() != State.CAPSIZED) return refuseSelfRighting(actor, "not_capsized", progress, 0, Vec3.ZERO);
        if (!capsizeReleased) return refuseSelfRighting(actor, "release_pending", progress, 0, Vec3.ZERO);
        if (boat.isVehicle()) return refuseSelfRighting(actor, "passengers_aboard", progress, 0, Vec3.ZERO);
        if (boat.hasIncoming()) return refuseSelfRighting(actor, "boarding_in_progress", progress, 0, Vec3.ZERO);
        if (!rightingGrip(actor, localGrip)) return refuseSelfRighting(actor, "grip_lost", progress, 0, Vec3.ZERO);
        var m = boat.motionData().copy();
        float previous = m.getFloat("RightProgress");
        float requested = Math.clamp(progress, 0, 1);
        if (requested + RIGHT_PROGRESS_EPSILON < previous)
            return refuseSelfRighting(actor, "progress_reversed", requested, previous, Vec3.ZERO);
        if (!m.contains("RightGripX")) {
            var water = BlockPos.containing(boat.position().add(0, -.12 + boat.sinkBlocks(), 0));
            if (!HatBoatRoute.calmSurface(boat.level(), water))
                return refuseSelfRighting(actor, "surface_unsteady", progress, previous, Vec3.ZERO);
            HatBoatEntity.point(m, "RightGrip", localGrip);
            HatBoatEntity.point(m, "RightAnchor", rimPoint(localGrip));
            m.putInt("RightWaterY", water.getY());
            m.putDouble("RightSurfaceY", HatBoatRoute.surface(boat.level(), water).y);
            // A previously dropped hat has finished its free tumble before a new real grip is accepted.
            m.remove("LooseAt"); m.remove("LoosePitch"); m.remove("LooseSquash");
        } else if (HatBoatEntity.point(m, "RightGrip").distanceToSqr(localGrip) > 1e-8)
            return refuseSelfRighting(actor, "grip_changed", requested, previous, Vec3.ZERO);
        var anchor = HatBoatEntity.point(m, "RightAnchor");
        var full = rightingCandidate(actor, m, anchor, localGrip, requested);
        if (full.ok()) return applySelfRighting(actor, m, previous, requested, full.centre());
        if (!full.reason().equals("movement_cap"))
            return refuseSelfRighting(actor, full.reason(), requested, previous, full.movement());

        // The cap is a real per-tick distance limit, not permission to teleport through it in several invisible substeps.
        // Find the furthest progress that can be reached from this exact centre in this tick; a collision leaves no partial gain.
        float low = previous, high = requested;
        String limit = full.reason();
        for (int i = 0; i < 14 && high - low > RIGHT_PROGRESS_EPSILON; i++) {
            float probe = (low + high) * .5f;
            var centre = anchor.subtract(boat.selfRightingOffset(localGrip, probe)).add(0, boat.rimLift(localGrip), 0);
            if (centre.distanceToSqr(boat.position()) <= SELF_RIGHT_STEP * SELF_RIGHT_STEP) low = probe;
            else high = probe;
        }
        if (low <= previous + RIGHT_PROGRESS_EPSILON)
            return refuseSelfRighting(actor, limit, requested, previous, full.movement());
        var partial = rightingCandidate(actor, m, anchor, localGrip, low);
        if (!partial.ok()) return refuseSelfRighting(actor, partial.reason(), requested, previous, partial.movement());
        GameplayLog.limited(boat, "aquatic", "righting.limited." + limit, 200,
                "self righting limited actor={} requested={} reached={} reason={} movement={} cap={}",
                actor.getUUID(), requested, low, limit, GameplayLog.at(partial.movement()), SELF_RIGHT_STEP);
        selfRightingRefusal = "progress_limited";
        return applySelfRighting(actor, m, previous, low, partial.centre());
    }

    private record RightingCandidate(boolean ok, String reason, Vec3 centre, Vec3 movement) {}

    private RightingCandidate rightingCandidate(YukkuriEntity actor, CompoundTag motion, Vec3 anchor, Vec3 localGrip, float progress) {
        var heldOffset = boat.selfRightingOffset(localGrip, progress).subtract(0, boat.rimLift(localGrip), 0);
        var centre = anchor.subtract(heldOffset);
        var movement = centre.subtract(boat.position());
        if (movement.lengthSqr() > SELF_RIGHT_STEP * SELF_RIGHT_STEP)
            return new RightingCandidate(false, "movement_cap", centre, movement);
        if (!SelfRescueHatGeometry.rightingClear(actor, boat, centre, progress))
            return new RightingCandidate(false, "skin_" + SelfRescueHatGeometry.lastFailure(), centre, movement);
        // The holding body's broad box extends beyond its face. Only this verified skin sweep may substitute for
        // that one pair's broad collision; blocks, other bodies and other hats still obstruct the physical move.
        actor.family().crew().beginHullSweep(boat);
        try {
            if (!boat.level().noCollision(boat, boat.getBoundingBox().move(movement)))
                return new RightingCandidate(false, "collision", centre, movement);
            if (!boat.level().noCollision(boat, boat.getBoundingBox().expandTowards(movement)))
                return new RightingCandidate(false, "swept_collision", centre, movement);
        } finally { actor.family().crew().endHullSweep(); }
        // Rotation raises or submerges the entity centre around the wet grip. The water layer belongs to the initial
        // contact, not that moving centre; check the future floating footprint without moving the hull to it.
        var water = BlockPos.containing(centre.x, motion.getInt("RightWaterY"), centre.z);
        if (!HatBoatRoute.calmSurface(boat.level(), water)
                || Math.abs(HatBoatRoute.surface(boat.level(), water).y - motion.getDouble("RightSurfaceY")) > .08)
            return new RightingCandidate(false, "righting_surface", centre, movement);
        var afloat = new Vec3(centre.x, motion.getDouble("RightSurfaceY") - boat.sinkBlocks(), centre.z);
        var fit = HullFootprint.fits((ServerLevel) boat.level(), actor, boat, afloat, boat.getYRot(), boat.hullScale(), 0);
        if (!fit.ok()) return new RightingCandidate(false, fit.reason(), centre, movement);
        return new RightingCandidate(true, "none", centre, movement);
    }

    private boolean applySelfRighting(YukkuriEntity actor, CompoundTag motion, float previous, float reached, Vec3 centre) {
        actor.family().crew().beginHullSweep(boat);
        try { boat.move(MoverType.SELF, centre.subtract(boat.position())); }
        finally { actor.family().crew().endHullSweep(); }
        if (boat.position().distanceToSqr(centre) > 1e-8) {
            selfRightingRefusal = "swept_collision";
            return false;
        }
        motion.putFloat("RightPrevious", previous);
        motion.putFloat("RightProgress", reached);
        motion.putLong("RightTouched", boat.level().getGameTime());
        boat.syncMotion(motion);
        // Rotating around the actually held rim translates the same boat centre.  The client gets this exact synced
        // progress and centre, so the visual grip and the server's collision-constrained grip remain one transform.
        boat.setDeltaMovement(Vec3.ZERO);
        boat.helm().stop("capsize_reset");
        return true;
    }

    private boolean refuseSelfRighting(YukkuriEntity actor, String reason, float requested, float reached, Vec3 movement) {
        selfRightingRefusal = reason;
        if (!boat.level().isClientSide) GameplayLog.limited(boat, "aquatic", "righting.refused." + reason, 200,
                "self righting refused actor={} reason={} requested={} reached={} movement={} cap={} state={} released={} passengers={} incoming={}",
                actor == null ? null : actor.getUUID(), reason, requested, reached, GameplayLog.at(movement), SELF_RIGHT_STEP,
                boat.state(), capsizeReleased, boat.getPassengers().size(), boat.hasIncoming());
        return false;
    }

    public boolean finishSelfRighting(YukkuriEntity actor) {
        selfRightingRefusal = "none";
        if (boat.level().isClientSide) return refuseSelfRighting(actor, "client", 1, 0, Vec3.ZERO);
        if (!ownsPhysicalHat(actor) && !mayReclaim(actor)) return refuseSelfRighting(actor, "physical_hat_lost", 1, 0, Vec3.ZERO);
        if (!boat.selfRighting()) return refuseSelfRighting(actor, "righting_not_active", 1, 0, Vec3.ZERO);
        float progress = boat.motionData().getFloat("RightProgress");
        if (progress < 1 - RIGHT_PROGRESS_EPSILON) return refuseSelfRighting(actor, "progress_incomplete", 1, progress, Vec3.ZERO);
        if (!rightingGrip(actor, HatBoatEntity.point(boat.motionData(), "RightGrip")))
            return refuseSelfRighting(actor, "grip_lost", 1, progress, Vec3.ZERO);
        var motion = boat.motionData();
        var finalPose = rightingCandidate(actor, motion, HatBoatEntity.point(motion, "RightAnchor"), HatBoatEntity.point(motion, "RightGrip"), 1);
        if (!finalPose.ok()) return refuseSelfRighting(actor, finalPose.reason(), 1, progress, finalPose.movement());
        boat.state(State.AFLOAT);
        flooding = 0;
        flooded = 0;
        boat.syncMotion(new CompoundTag());
        capsizeReleased = true;
        boat.helm().stop("righted");
        rightedAt = boat.level().getGameTime(); rightedY = boat.getY();
        GameplayLog.event(boat, "aquatic", "hazard righted actor={} state=AFLOAT flooding=0 cause={}", actor.getUUID(), cause);
        return true;
    }

    private boolean mayReclaim(YukkuriEntity actor) {
        var owner = boat.owner();
        return actor != null && actor.isAlive() && owner != null && owner.isAlive()
                && owner.life().custody().reclaimAssigned(actor.getUUID()) && boat.ownsPhysicalHat(owner)
                && actor.getVehicle() instanceof HatBoatEntity home && home != boat && WaterIncidents.settledAboard(actor,home)
                && WaterIncidents.settledAboard(owner,home) && home.young(owner) && actor.getUUID().equals(home.ownerYukkuri())
                && !actor.family().crew().busy() && !actor.operations().active() && !actor.life().isSleeping()
                && CrewReach.hasBraid(actor) && CrewReach.canLift(actor, owner);
    }
    private boolean rightingGrip(YukkuriEntity actor, Vec3 local) {
        if (ownsPhysicalHat(actor)) return selfRescueGrip(actor, local) != null;
        return reclaimReachable(actor,local)&&actor.aquaticLife().familyLife().reclaimContact(boat,local);
    }
    /** A geometric candidate for extending a braid, never proof that its tip is already there. */
    public boolean reclaimReachable(YukkuriEntity actor,Vec3 local){
        if (!mayReclaim(actor) || local == null || GripRim.points(boat.mode()).stream().noneMatch(p -> p.distanceToSqr(local) < 1e-8)) return false;
        var root = CrewReach.root(actor);
        var point = rimPoint(local);
        double minimum=Math.abs(BraidRescueGeometry.firstLength(actor)-BraidRescueGeometry.secondLength(actor))+.001*actor.growthScale()*actor.getScale();
        return root.distanceTo(point)>=minimum&&CrewReach.braidReaches(actor, root, point) && boat.level().clip(new ClipContext(root, point,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, actor)).getType() == HitResult.Type.MISS;
    }
    /** A claimed, empty child's hat is handled by the same bounded rim-pivot solver as self rescue. */
    public Vec3 reclaimGripLocal(YukkuriEntity actor) {
        if (!mayReclaim(actor) || boat.isVehicle() || boat.hasIncoming() || boat.capsizing() || boat.selfRescueHeld()) return null;
        var motion = boat.motionData();
        if (boat.selfRighting() && motion.contains("RightGripX")) {
            var held = HatBoatEntity.point(motion, "RightGrip");
            return reclaimReachable(actor, held) ? held : null;
        }
        return GripRim.points(boat.mode()).stream().filter(local -> reclaimReachable(actor, local))
                .filter(local -> boat.state() != State.CAPSIZED || reclaimRightingSpace(actor, local))
                .min(Comparator.comparingDouble(local -> CrewReach.root(actor).distanceToSqr(rimPoint(local)))).orElse(null);
    }
    private boolean reclaimRightingSpace(YukkuriEntity actor, Vec3 local) {
        // A nearest-rim pivot may rotate the little hat into the parent's hull. Choose a reachable pivot whose final
        // floating footprint is free; every intermediate step is still checked by the common righting solver.
        Vec3 centre = rimPoint(local).subtract(boat.selfRightingOffset(local, 1)).add(0, boat.rimLift(local), 0);
        var water = BlockPos.containing(centre.x, boat.getY() - .12 + boat.sinkBlocks(), centre.z);
        if (!HatBoatRoute.calmSurface(boat.level(), water)) return false;
        var afloat = new Vec3(centre.x, HatBoatRoute.surface(boat.level(), water).y - boat.sinkBlocks(), centre.z);
        return HullFootprint.fits((ServerLevel) boat.level(), actor, boat, afloat, boat.getYRot(), boat.hullScale(), 0).ok();
    }

    public void tickSelfRighting() {
        var m = boat.motionData();
        if (!boat.selfRighting() || boat.level().getGameTime() - m.getLong("RightTouched") <= 1) return;
        var copy = m.copy();
        float progress = m.getFloat("RightProgress");
        copy.putFloat("RightPrevious", progress);
        copy.putFloat("RightProgress", Math.max(0, progress - .04f));
        // The held pivot is valid only while a swimmer renews it.  Free rollback does not translate the centre around
        // that grip, so drop every dependent anchor before clients return to ordinary entity interpolation.
        copy.remove("RightWaterY"); copy.remove("RightSurfaceY");
        for (var axis : new String[]{"X", "Y", "Z"}) { copy.remove("RightGrip" + axis); copy.remove("RightAnchor" + axis); }
        if (progress <= .04) {
            copy.remove("RightProgress"); copy.remove("RightPrevious"); copy.remove("RightTouched");
        }
        boat.syncMotion(copy);
    }

    // ================================================================= taking the hat off the head to right it
    public static SelfRescueDeployment planSelfRescueDeployment(YukkuriEntity actor) {
        if (!(actor.level() instanceof ServerLevel level) || !actor.profile().aquatic() || !actor.isAlive() || !actor.isInWaterOrBubble()
                || actor.isPassenger() || actor.life().custody().custodied() || !actor.life().hasAccessory()) return null;
        var water = BlockPos.containing(actor.position());
        if (!HatBoatRoute.calmSurface(level, water)) { GameplayLog.detail(actor, "self.surface." + actor.getId(), 40, "not a calm surface at " + water); return null; }
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
        if (!(inside && flat && fit.ok())) GameplayLog.detail(actor, "self.spot." + actor.getId(), 40, "hat spot " + GameplayLog.at(plan.position()) + " bounds="
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
        double q = Math.clamp((elapsed - SelfRescueHatGeometry.SHAKE - SelfRescueHatGeometry.TURN) / SelfRescueHatGeometry.LOWER, 0, 1);
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
        if (!boat.selfRescueHeld()) boat.setPos(centre); // Initial placement of the newly created, already checked physical prop.
        else {
            actor.family().crew().beginHullSweep(boat);
            try { boat.move(MoverType.SELF, centre.subtract(boat.position())); }
            finally { actor.family().crew().endHullSweep(); }
            if (boat.position().distanceToSqr(centre) > 1e-8) {
                GameplayLog.limited(boat,"aquatic","self.carry.sweep",80,
                        "self rescue carry movement refused actor={} reason=hull_swept_collision wanted={} actual={}",
                        actor.getUUID(),GameplayLog.at(centre),GameplayLog.at(boat.position()));
                return false;
            }
        }
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
        var ejections = new ArrayList<Vec3>();
        var thrown = new ArrayList<YukkuriEntity>();
        var releasedYoung = new ArrayList<UUID>();
        for (var p : passengers) {
            var local = boat.toLocal(p.position());
            positions.add(tiltedWorld(local));
            ejections.add(capsizeEjection(local, side));
        }
        var incidentCause = "shaken".equals(this.cause) ? WaterIncidents.Cause.SHAKEN : WaterIncidents.Cause.CAPSIZE;
        for (int i = 0; i < passengers.size(); i++) {
            var p = passengers.get(i);
            // Leave the actual rider position through swept, speed-limited motion; never teleport beyond the hat.
            var at = positions.get(i);
            var outward = ejections.get(i);
            if (p instanceof YukkuriEntity body) YukkuriLocomotion.detach(body);
            else p.stopRiding();
            var impulse = boat.getDeltaMovement().add(outward.scale(.055 + i * .009)).add(downhill.scale(.025))
                    .add(along.scale((i - (passengers.size() - 1) * .5) * .018)).add(0, .025 - i * .006, 0);
            if (p instanceof YukkuriEntity body) {
                YukkuriLocomotion.waterStep(body, at, YukkuriLocomotion.limits(body).maxHorizSpeed());
                YukkuriLocomotion.waterImpulse(body, impulse.subtract(body.getDeltaMovement()));
                separating.put(body.getUUID(), new Separation(outward, boat.level().getGameTime() + 80));
            } else p.setDeltaMovement(impulse);
            p.hasImpulse = true;
            GameplayLog.limited(boat, "aquatic", "capsize.release." + p.getId(), 200,
                    "capsize release rider={} local={} at={} outward={} cause={}", p.getUUID(), GameplayLog.at(boat.toLocal(at)),
                    GameplayLog.at(at), GameplayLog.at(outward), this.cause);
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

    /** A capsized rider leaves from its real seat with a small outward impulse, never by being repositioned beyond the hat. */
    private Vec3 capsizeEjection(Vec3 local, int side) {
        var radial = new Vec3(local.x, 0, local.z);
        if (radial.horizontalDistanceSqr() < 1e-8) radial = new Vec3(-side, 0, 0);
        var at = tiltedWorld(local);
        var outward = tiltedWorld(local.add(radial.normalize())).subtract(at).multiply(1, 0, 1);
        if (outward.horizontalDistanceSqr() < 1e-8) outward = radial.yRot(boat.turn()).multiply(1, 0, 1);
        return outward.normalize();
    }

    /** A finite separation response while the body still overlaps the overturned cloth hull. */
    private void separateSwimmers() {
        if (!(boat.level() instanceof ServerLevel server) || separating.isEmpty()) return;
        long now = server.getGameTime();
        separating.entrySet().removeIf(entry -> {
            if (!(server.getEntity(entry.getKey()) instanceof YukkuriEntity body) || !body.isAlive() || body.isPassenger()
                    || body.family().crew().busy() || now >= entry.getValue().until() || boat.state() != State.CAPSIZED
                    || !body.getBoundingBox().intersects(boat.getBoundingBox().inflate(.02))) return true;
            Vec3 toward = body.position().add(entry.getValue().outward());
            YukkuriLocomotion.waterStep(body, toward, Math.min(.045, YukkuriLocomotion.limits(body).swimSpeed()));
            return false;
        });
    }

    private void observeRise() {
        if (rightedAt < 0 || boat.level().isClientSide) return;
        long elapsed = boat.level().getGameTime() - rightedAt;
        var water = BlockPos.containing(boat.position().add(0, -.12 + boat.sinkBlocks(), 0));
        double target = HatBoatRoute.surface(boat.level(), water).y - boat.sinkBlocks();
        if (!boat.isAfloat() || Math.abs(boat.getY() - target) <= .04 || elapsed >= 200) {
            GameplayLog.event(boat, "aquatic", "righting rise observed ticks={} startY={} currentY={} surfaceY={} settled={} state={}",
                    elapsed, rightedY, boat.getY(), target, boat.isAfloat() && Math.abs(boat.getY() - target) <= .04, boat.state());
            rightedAt = -1;
        }
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
