package io.github.longshou.mcyukkuri.subspecies.boat;

import io.github.longshou.mcyukkuri.subspecies.crew.CrewTransfers;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatRoute;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The one way a hat boat comes into the world. Each deployment runs one precheck, creates the hull, moves the owner's real hat
 * into its custody and, when the origin has the owner step aboard at once, boards her; any failure along the way is rolled back
 * the same way (the hat goes back on the owner, the hull is discarded). Every outcome is logged as {@code hull deployed} or
 * {@code hull refused reason=}.
 */
public final class HullFactory {
    /** Where the deployment comes from: it decides which owner conditions apply and whether she is boarded here. */
    public enum Origin {
        BANK, PARENT_HAT, SWIMMING, SPAWN, SCENARIO, CRADLE, MOORING;
        boolean boardsOwner() { return this == SPAWN || this == SCENARIO; }
        boolean aquaticDeployment() { return this != CRADLE && this != MOORING; }
    }

    /** Hull centre (at the waterline), yaw, hull scale; {@code water} is the cell reported in logs; {@code home} the parent hat of a first voyage. */
    public record Spec(Origin origin, Vec3 position, float yaw, float hullScale, BlockPos water, HatBoatEntity home, double margin) {}

    public sealed interface Result permits Deployed, Refused {}
    public record Deployed(HatBoatEntity boat) implements Result {}
    public record Refused(String reason, String detail) implements Result {
        public String describe() { return detail.isEmpty() ? reason : reason + " (" + detail + ")"; }
    }

    private HullFactory() {}

    /** Counted-hold key: a freshly deployed hull stays still while boarding begins on a settled hull. */
    private static final Object DEPLOY_SETTLE = new Object();

    /** The boat of a successful result, or null. */
    public static HatBoatEntity boatOf(Result result) { return result instanceof Deployed deployed ? deployed.boat() : null; }

    public static Result deploy(YukkuriEntity owner, Spec spec) {
        if (!(owner.level() instanceof ServerLevel level)) return new Refused("client_side", "");
        var origin = spec.origin();
        var water = spec.water();
        String why = precheck(level, owner, spec);
        if (why != null) return refused(owner, spec, why, "");
        // The parent's own hat lies right beside a child's launch spot; it is passed as self so it never obstructs the new hull.
        var fit = HullFootprint.fits(level, owner, spec.home(), spec.position(), spec.yaw(), spec.hullScale(), spec.margin());
        if (!fit.ok()) return refused(owner, spec, fit.reason(), fit.detail());
        var boat = ModEntities.HAT_BOAT.get().create(level);
        if (boat == null) return refused(owner, spec, "entity_creation_failed", "");
        boat.syncHullScale(spec.hullScale());
        boat.setPos(spec.position());
        boat.setYRot(spec.yaw());
        if (origin == Origin.SWIMMING) boat.launchRole(HatVessel.Role.SELF);
        boat.syncOwner(Optional.of(owner.getUUID()));
        boat.syncAccessoryIdentity(owner);
        boat.syncHullScale(spec.hullScale());
        if (!level.addFreshEntity(boat)) return refused(owner, spec, "entity_add_failed", "");
        boolean committed = false;
        String failure = "accessory_transfer_failed";
        try {
            boat.launchToken(owner.life().stowAccessory(boat.getUUID()));
            if (boat.launchToken() != null) {
                failure = "boarding_refused";
                committed = !origin.boardsOwner() || owner.startRiding(boat, true);
            }
        } finally {
            if (!committed) rollback(owner, boat);
        }
        if (!committed) return refused(owner, spec, failure, "");
        owner.subspecies().noteBoatExperience();
        owner.setPersistenceRequired();
        if (origin != Origin.SWIMMING) owner.getNavigation().stop();
        if (origin.boardsOwner()) {
            owner.setYRot(boat.getYRot());
            owner.yBodyRot = boat.getYRot();
            owner.setYHeadRot(boat.getYRot());
            var seat = boat.toWorld(boat.ownerSeat(owner));
            owner.moveTo(seat.x, seat.y, seat.z, boat.getYRot(), owner.getXRot());
        }
        owner.aquaticLife().firstVoyage().restoreHatCargo(boat);
        owner.aquaticLife().equip(boat);
        if (origin != Origin.SWIMMING) boat.helm().hold(DEPLOY_SETTLE, 60);
        if (spec.home() != null && origin == Origin.PARENT_HAT) {
            // During the real hat-off launch the child still rides the parent and can hand over provisions.
            // Restored or carried provisions are used first; a parent never loses its final serving.
            var home = spec.home();
            if (boat.cargo().count() == 0 && home.protects(owner) && owner.aquaticLife().firstVoyage().launchingFrom(home))
                home.cargo().transferProvisionsTo(boat.cargo(), owner, 2);
            // False can mean that the child has only started approaching the parent's rim.
            // BOARD owns retries and cancellation; the same physical hat must remain deployed meanwhile.
            CrewTransfers.hopOwnHat(owner, home, boat);
        }
        GameplayLog.event(owner, "aquatic", "hull deployed origin={} boat={} water={} at={} yaw={} scale={} ownerAboard={} food={} paddle={}",
                origin, boat.getUUID(), water, spec.position(), spec.yaw(), spec.hullScale(), owner.getVehicle() == boat,
                boat.cargo().count(), boat.cargo().paddle());
        return new Deployed(boat);
    }

    /** Put the owner's hat on open water found by {@link ShoreBerth} (SPAWN or SCENARIO) and board her at once. */
    public static Result deployOnBerth(YukkuriEntity owner, Origin origin, ShoreBerth.Berth berth) {
        return deploy(owner, new Spec(origin, berth.center(), berth.yaw(), HatVessel.hatScale(owner), berth.water(), null, 0));
    }

    /**
     * The shore launch hull: the owner stands on {@code stand} beside {@code water}; the hull lies along the bank wall where
     * {@link ShoreBerth} puts it, empty. Loading the young and the owner's own hop aboard belong to the voyage controller.
     */
    public static Result moorAtBank(YukkuriEntity owner, BlockPos stand, BlockPos water) {
        if (!(owner.level() instanceof ServerLevel level)) return new Refused("client_side", "");
        float scale = HatVessel.hatScale(owner);
        var outcome = ShoreBerth.berthAt(level, owner, scale, stand, water, ShoreBerth.Purpose.LAUNCH);
        if (outcome instanceof ShoreBerth.Refusal refusal) {
            GameplayLog.limited(owner, "aquatic", "hull.refused.BANK." + refusal.reason(), 200,
                    "hull refused origin=BANK reason={} detail={} bank={} water={} counts={}", refusal.reason(), refusal.detail(), stand, water, refusal.counts());
            return new Refused(refusal.reason(), refusal.detail());
        }
        var berth = (ShoreBerth.Berth) outcome;
        float facing = Mth.wrapDegrees(berth.yaw() - HatVessel.placement(true, HatVessel.Role.SELF).yaw());
        owner.setYRot(facing); owner.yBodyRot = facing; owner.setYHeadRot(facing);
        return deploy(owner, new Spec(Origin.BANK, berth.center(), berth.yaw(), scale, water, null, 0));
    }

    /** A child's first launch from its parent's hull: its hat goes onto the water at the authored hat-off pose beside the child. */
    public static HatBoatEntity launchFromFather(YukkuriEntity child, HatBoatEntity home, BlockPos water) {
        if (child.getVehicle() != home || !home.protects(child) || !home.onVoyageBerth(child) || home.voyageBerth(child) == null
                || !(home.ownerYukkuri().equals(child.family().father()) || home.ownerYukkuri().equals(child.family().mother()))
                || !child.aquaticLife().firstVoyage().launchingFrom(home)) return null;
        var placement = HatVessel.placement(true, HatVessel.Role.SELF);
        var offset = placement.offset().scale(child.growthScale() * child.getScale() / 16).yRot((float) Math.toRadians(180 - child.yBodyRot));
        var at = new Vec3(child.getX() + offset.x, HatBoatRoute.surface(child.level(), water).y, child.getZ() + offset.z);
        return boatOf(deploy(child, new Spec(Origin.PARENT_HAT, at, child.yBodyRot + placement.yaw(), HatVessel.hatScale(child), water, home, 0)));
    }

    /** An empty upside-down hat set down by the owner's braid: a catching nest on dry ground (CRADLE) or a floated hull at the bank (MOORING). */
    public static HatBoatEntity setDown(YukkuriEntity owner, Vec3 at, float yaw, HatVessel.Mode mode, HatVessel.Role role) {
        var origin = mode.afloat() ? Origin.MOORING : Origin.CRADLE;
        var spec = new Spec(origin, at, yaw, HatVessel.hatScale(owner), BlockPos.containing(at), null, 0);
        if (!(owner.level() instanceof ServerLevel level)) return null;
        if (owner.getVariant() != YukkuriVariant.MARISA) { refused(owner, spec, "not_marisa", ""); return null; }
        if (!owner.isAlive()) { refused(owner, spec, "not_alive", ""); return null; }
        if (owner.isPassenger()) { refused(owner, spec, "passenger", ""); return null; }
        if (!owner.life().hasAccessory()) { refused(owner, spec, "no_accessory", ""); return null; }
        if (owner.life().custody().custodied()) { refused(owner, spec, "existing_boat", ""); return null; }
        var spot = BlockPos.containing(at);
        if (!level.hasChunkAt(spot)) { refused(owner, spec, "water_unloaded", spot.toShortString()); return null; }
        double y;
        if (mode.afloat()) {
            // The water surface below the bank she stands on.
            BlockPos surface = null;
            for (int down = 0; down < 3 && surface == null; down++)
                if (level.getFluidState(spot.below(down)).is(FluidTags.WATER) && level.getFluidState(spot.below(down).above()).isEmpty()) surface = spot.below(down);
            if (surface == null) { refused(owner, spec, "no_water_below", spot.toShortString()); return null; }
            y = surface.getY() + level.getFluidState(surface).getHeight(level, surface) + .025;
        } else {
            if (!level.getFluidState(spot).isEmpty()) { refused(owner, spec, "spot_in_fluid", spot.toShortString()); return null; }
            if (!level.getBlockState(spot.below()).isFaceSturdy(level, spot.below(), Direction.UP)) {
                refused(owner, spec, "no_sturdy_floor", spot.below().toShortString() + " " + level.getBlockState(spot.below()).getBlock()); return null;
            }
            y = spot.getY();
        }
        var hat = ModEntities.HAT_BOAT.get().create(level);
        if (hat == null) { refused(owner, spec, "entity_creation_failed", ""); return null; }
        hat.setPos(at.x, y, at.z);
        hat.setYRot(yaw);
        hat.launchRole(role);
        hat.helm().moor(hat.position());
        hat.syncOwner(Optional.of(owner.getUUID()));
        hat.syncAccessoryIdentity(owner);
        hat.syncHullScale(HatVessel.hatScale(owner));
        hat.state(mode.afloat() ? HatBoatEntity.State.MOORED : HatBoatEntity.State.CRADLE);
        if (!level.addFreshEntity(hat)) { refused(owner, spec, "entity_add_failed", ""); return null; }
        boolean committed = false;
        try {
            hat.launchToken(owner.life().stowAccessory(hat.getUUID()));
            committed = hat.launchToken() != null;
        } finally {
            if (!committed) rollback(owner, hat);
        }
        if (!committed) { refused(owner, spec, "accessory_transfer_failed", ""); return null; }
        GameplayLog.event(owner, "aquatic", "hull deployed origin={} boat={} water={} at={} yaw={} scale={} ownerAboard=false food=0 paddle=false",
                origin, hat.getUUID(), spot, hat.position(), yaw, hat.hullScale());
        return hat;
    }

    /** The owner conditions common to every aquatic deployment; the reason of the first one that fails, or null. */
    private static String precheck(ServerLevel level, YukkuriEntity owner, Spec spec) {
        var origin = spec.origin();
        if (origin == Origin.SWIMMING) {
            if (!owner.profile().aquatic()) return "not_aquatic_marisa";
        } else if (owner.getVariant() != YukkuriVariant.MARISA || owner.subspecies().kind() != SubspeciesLife.Kind.AQUATIC_MARISA) return "not_aquatic_marisa";
        if (origin != Origin.SWIMMING && !owner.aquaticLife().firstVoyage().mayLaunch()) return "first_voyage_ineligible";
        if (owner.life().custody().custodied()) return "existing_boat";
        if (!owner.isAlive()) return "not_alive";
        if (owner.isPassenger() && origin != Origin.PARENT_HAT) return "passenger";
        if (owner.isVehicle() && origin != Origin.SWIMMING) return "carrying_passengers";
        if (!owner.life().hasAccessory()) return "no_accessory";
        if (origin == Origin.BANK || origin == Origin.PARENT_HAT) {
            if (owner.isInWaterOrBubble()) return "owner_in_water";
            if (origin == Origin.BANK && !level.getFluidState(owner.blockPosition()).isEmpty()) return "feet_not_dry";
        }
        if (origin == Origin.SWIMMING && !owner.isInWaterOrBubble()) return "owner_not_in_water";
        if (origin != Origin.SWIMMING && owner.operations().active()) return "operation_active";
        double limit = origin == Origin.SPAWN || origin == Origin.SCENARIO ? 20 * 20 : origin == Origin.SWIMMING ? Double.MAX_VALUE : 9;
        if (owner.distanceToSqr(spec.position()) > limit && owner.distanceToSqr(Vec3.atCenterOf(spec.water())) > limit) return "water_out_of_range";
        return null;
    }

    /** One rollback for every failed deployment: nobody stays aboard, the hat goes back to its owner, the hull disappears. */
    private static void rollback(YukkuriEntity owner, HatBoatEntity boat) {
        var token = boat.launchToken();
        boat.launchToken(null);
        try {
            if (token != null) owner.life().returnAccessory(owner.getUUID(), token, null);
        } finally {
            for (var rider : List.copyOf(boat.getPassengers())) rider.stopRiding();
            boat.discard();
        }
    }

    private static Refused refused(YukkuriEntity owner, Spec spec, String reason, String detail) {
        GameplayLog.limited(owner, "aquatic", "hull.refused." + spec.origin() + "." + reason, 200,
                "hull refused origin={} reason={} detail={} water={} at={} yaw={} scale={} distanceSquared={} boatId={} accessory={} operation={}",
                spec.origin(), reason, detail, spec.water(), spec.position(), spec.yaw(), spec.hullScale(),
                owner.distanceToSqr(spec.position()), owner.life().custody().state(), owner.life().hasAccessory(), owner.operations().active());
        return new Refused(reason, detail);
    }
}
