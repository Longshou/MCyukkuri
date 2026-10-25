package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.subspecies.boat.HullHelm;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyFeedingContact;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.crew.DeckLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.subspecies.crew.Purpose;

/**
 * One physical parent-child lift inside the hat between two deck seats: the captain walks the captain seats to a pickup
 * seat within reach (the authored care pairs give that reach), lifts the child, walks to the delivery seat with it and sets it
 * down on the seat the deck reserved. Seats come from the deck; nothing here searches geometry. P4 replaces this with the
 * shared crew transfer; until then it keeps the old phases so the carry clips keep playing.
 */
final class HatBoatCarry {
    /** How long a whole trip may take before it is given up and the child returns to the seat it was lifted from. */
    static final int TRIP_TICKS = 700, APPROACH_TICKS = 260;
    private final HatBoatEntity boat;
    /** -1 tuck, 0 approach, 1 lift, 2 carry, 3 lower, 4 retreat, 5 unfold. */
    static final class CarryTrip {
        final UUID child;
        final int fromSlot, toSlot, pickupSlot, deliverySlot;
        final Vec3 from, destination, pickup, ownerDestination, offset;
        final long began;
        Vec3 facing;
        Vec3 lastAnchor;
        int phase, contact;
        long at;
        CarryTrip(UUID child, int fromSlot, int toSlot, int pickupSlot, int deliverySlot, Vec3 from, Vec3 destination, Vec3 pickup,
                Vec3 ownerDestination, Vec3 offset, long at) {
            this.child = child; this.fromSlot = fromSlot; this.toSlot = toSlot; this.pickupSlot = pickupSlot; this.deliverySlot = deliverySlot;
            this.from = from; this.destination = destination; this.pickup = pickup; this.ownerDestination = ownerDestination; this.offset = offset;
            this.at = at; this.began = at;
            this.lastAnchor = from;
            this.facing = offset.multiply(1, 0, 1).normalize();
        }
    }
    CarryTrip carryTrip;
    String carryPlanDebug = "not planned";

    HatBoatCarry(HatBoatEntity boat) { this.boat = boat; }

    /** The reach of a lift in hat units: the farthest captain/child distance among the authored care pairs. */
    private double reach() {
        double reach = 0;
        var layout = boat.deckLayout();
        for (var care : layout.care()) reach = Math.max(reach, layout.slot(care.captain()).pos().distanceTo(layout.slot(care.young()).pos()));
        return reach;
    }

    /**
     * Plan and start a lift of {@code child} to deck seat {@code toSlot}. {@code deliverySlot} is the captain seat she ends on
     * (a care station), or NONE to pick the captain seat that holds the child nearest its new seat. Reserves the new seat.
     */
    boolean beginCarry(YukkuriEntity child, YukkuriEntity parent, int toSlot, int deliverySlot) {
        if (carryTrip != null) return carryTrip.child.equals(child.getUUID());
        var deck = boat.deck();
        var layout = boat.deckLayout();
        var cp = deck.get(child.getUUID());
        var pp = deck.get(parent.getUUID());
        if (cp == null || pp == null || !cp.aboard() || !pp.aboard() || cp.slot() < 0 || pp.slot() < 0 || cp.slot() == toSlot
                || deck.moving(child.getUUID()) || deck.moving(parent.getUUID())) { carryPlanDebug = "child or captain not settled on seats"; return false; }
        var cb = boat.bodyOf(child);
        var pb = boat.bodyOf(parent);
        Vec3 from = layout.slot(cp.slot()).pos();
        Vec3 to = layout.slot(toSlot).pos();
        double reach = reach();
        int pickup = DeckLayout.NONE;
        double cost = Double.MAX_VALUE;
        var choices = deck.freeSeats(pb, Purpose.SERVICE, from);
        if (!choices.contains(pp.slot())) choices = append(choices, pp.slot());
        for (int seat : choices) {
            var at = layout.slot(seat).pos();
            if (at.distanceTo(from) > reach) continue;
            if (seat != pp.slot() && layout.path(pp.slot(), seat, pb.cls(), false, null) == null) continue;
            double c = at.distanceTo(from) + (seat == pp.slot() ? 0 : at.distanceTo(layout.slot(pp.slot()).pos()) * .25);
            if (c < cost) { cost = c; pickup = seat; }
        }
        if (pickup < 0) { carryPlanDebug = "no captain seat within reach of " + layout.slot(cp.slot()).id(); return false; }
        Vec3 offset = from.subtract(layout.slot(pickup).pos());
        int delivery = deliverySlot;
        if (delivery < 0) {
            double best = Double.MAX_VALUE;
            for (int seat : deck.freeSeats(pb, Purpose.SERVICE, to)) {
                if (layout.path(pickup, seat, pb.cls(), false, null) == null && seat != pickup) continue;
                double c = layout.slot(seat).pos().add(offset).distanceTo(to);
                if (c < best) { best = c; delivery = seat; }
            }
            if (delivery < 0) delivery = pickup;
        } else if (delivery != pickup && layout.path(pickup, delivery, pb.cls(), false, null) == null) {
            carryPlanDebug = "no captain walk from " + layout.slot(pickup).id() + " to " + layout.slot(delivery).id();
            return false;
        }
        if (!deck.reserveLift(cb, toSlot, Purpose.SERVICE)) {
            carryPlanDebug = "seat " + layout.slot(toSlot).id() + " refused: " + deck.lastRefusal();
            return false;
        }
        carryTrip = new CarryTrip(child.getUUID(), cp.slot(), toSlot, pickup, delivery, from, to, layout.slot(pickup).pos(),
                layout.slot(delivery).pos(), offset, boat.level().getGameTime());
        carryTrip.phase = -1;
        carryPlanDebug = "ready pickup=" + layout.slot(pickup).id() + " delivery=" + layout.slot(delivery).id();
        GameplayLog.event(boat, "aquatic", "deck lift started child={} {}->{} captainPickup={} captainDelivery={}", child.getUUID(),
                layout.slot(cp.slot()).id(), layout.slot(toSlot).id(), layout.slot(pickup).id(), layout.slot(delivery).id());
        boat.syncSeats();
        return true;
    }
    private static List<Integer> append(List<Integer> list, int value) {
        var out = new ArrayList<>(list);
        out.add(value);
        return out;
    }

    /** A child that cannot walk a step back to its own seat is lifted there, with the captain ending where she can set it down. */
    boolean beginReturnCarry(YukkuriEntity child, YukkuriEntity parent, int homeSlot) {
        return beginCarry(child, parent, homeSlot, DeckLayout.NONE);
    }

    private void cancel(String reason, YukkuriEntity child) {
        var trip = carryTrip;
        carryTrip = null;
        if (trip == null) return;
        boat.deck().abandon(trip.child, "lift_" + reason);
        boat.deck().lift(trip.child, false);
        GameplayLog.event(boat, "aquatic", "deck lift cancelled child={} reason={} phase={} {}->{}", trip.child, reason, trip.phase,
                boat.deckLayout().slot(trip.fromSlot).id(), boat.deckLayout().slot(trip.toSlot).id());
        boat.syncSeats();
    }

    void tickCarry() {
        if (carryTrip == null) return;
        var trip = carryTrip;
        var deck = boat.deck();
        var parent = boat.owner();
        var child = boat.level() instanceof ServerLevel server && server.getEntity(trip.child) instanceof YukkuriEntity y ? y : null;
        if (parent == null || child == null) { cancel("participant_missing", child); return; }
        if (!parent.isAlive() || parent.getVehicle() != boat || child.getVehicle() != boat || !boat.isAfloat()) { cancel("participant_left", child); return; }
        long now = boat.level().getGameTime();
        if (now - trip.began > TRIP_TICKS) { cancel("timeout", child); return; }
        boat.helm().drift(boat, HullHelm.Purpose.CARE);
        float carryYaw = SurfaceApproach.yaw(Vec3.ZERO, trip.facing.yRot(boat.turn()));
        SurfaceApproach.face(parent, carryYaw);
        SurfaceApproach.face(child, carryYaw + 180);
        var pb = boat.bodyOf(parent);
        if (trip.phase == -1) {
            if (now - trip.at < 12) return;
            if (deck.get(parent.getUUID()).slot() != trip.pickupSlot) {
                if (!deck.moveWithin(pb, trip.pickupSlot, boat.toLocal(parent.position()), false, Purpose.SERVICE)) {
                    if (now - trip.at > APPROACH_TICKS) cancel("pickup_unreachable " + deck.lastRefusal(), child);
                    return;
                }
            }
            trip.phase = 0; trip.at = now; boat.syncSeats();
            return;
        }
        if (trip.phase == 0) {
            if (deck.get(parent.getUUID()).slot() == trip.pickupSlot && !deck.moving(parent.getUUID())) {
                if (FamilyFeedingContact.mouthsMeet(parent, child)) trip.contact++; else trip.contact = 0;
                if (trip.contact >= 12) { trip.phase = 1; trip.at = now; deck.lift(trip.child, true); boat.syncSeats(); }
            }
            if (now - trip.at > APPROACH_TICKS) cancel("no_contact", child);
            return;
        }
        if (trip.phase == 1 && now - trip.at >= 12) {
            if (trip.pickupSlot != trip.deliverySlot && !deck.moveWithin(pb, trip.deliverySlot, boat.toLocal(parent.position()), false,
                    Purpose.SERVICE)) { cancel("delivery_unreachable " + deck.lastRefusal(), child); return; }
            trip.phase = 2; trip.at = now; boat.syncSeats();
        } else if (trip.phase == 2) {
            var placed = deck.get(parent.getUUID());
            if (placed != null && placed.slot() == trip.deliverySlot && !deck.moving(parent.getUUID())) { trip.phase = 3; trip.at = now; boat.syncSeats(); }
        } else if (trip.phase == 3 && now - trip.at >= 12) {
            deck.arrived(trip.child);
            deck.lift(trip.child, false);
            trip.phase = 4; trip.at = now; boat.syncSeats();
        } else if (trip.phase == 4 && now - trip.at >= 6) {
            trip.phase = 5; trip.at = now; boat.syncSeats();
        } else if (trip.phase == 5 && now - trip.at >= 12) {
            carryTrip = null;
            GameplayLog.event(boat, "aquatic", "deck lift done child={} {}->{} ticks={}", trip.child, boat.deckLayout().slot(trip.fromSlot).id(),
                    boat.deckLayout().slot(trip.toSlot).id(), now - trip.began);
            boat.stations.afterCarry(child, parent);
            boat.syncSeats();
        }
    }

    String carryActivity(Entity rider) {
        var trip = carryPose();
        if (trip == null) return Behaviors.IDLE;
        if (rider.getUUID().equals(boat.ownerYukkuri())) return Behaviors.BOAT_CARRY_YOUNG;
        if (!rider.getUUID().equals(trip.child)) return Behaviors.IDLE;
        return trip.phase == -1 ? Behaviors.BOAT_PREPARE_CARRY : trip.phase == 5 ? Behaviors.BOAT_RELEASE_CARRY : Behaviors.BOAT_BE_CARRIED;
    }

    float carrySeconds(float partial) {
        var trip = carryPose();
        return trip == null ? 0 : (float) Math.max(0, (boat.level().getGameTime() - trip.at + partial) / 20.0);
    }

    boolean carryingInHat(Entity rider) {
        var trip = carryPose();
        return trip != null && trip.phase > 0 && trip.phase <= 3 && (rider.getUUID().equals(trip.child) || rider.getUUID().equals(boat.ownerYukkuri()));
    }

    boolean transportingInHat(Entity rider) {
        var trip = carryPose();
        return trip != null && (rider.getUUID().equals(trip.child) || rider.getUUID().equals(boat.ownerYukkuri()));
    }

    UUID hatCarrier(YukkuriEntity child) {
        var trip = carryPose();
        return trip != null && trip.phase > 0 && trip.phase <= 3 && trip.child.equals(child.getUUID()) ? boat.ownerYukkuri() : null;
    }

    Vec3 hatCarryAnchor(YukkuriEntity child) {
        var trip = carryPose();
        if (trip == null || !trip.child.equals(child.getUUID())) return null;
        var parent = boat.getPassengers().stream().filter(p -> p.getUUID().equals(boat.ownerYukkuri())).findFirst().orElse(null);
        return trip.phase == 2 && parent != null ? parent.position().add(trip.offset.scale(boat.unit()).yRot(boat.turn())) : boat.toWorld(carriedLocal(trip));
    }

    /** The captain's hull-local place now, walking included (server and client alike). */
    Vec3 ownerCarryLocal(YukkuriEntity parent) {
        return boat.seatLocalNow(parent);
    }

    Vec3 carriedLocal(CarryTrip trip) {
        if (trip.phase <= 0) return trip.from;
        if (trip.phase >= 4) return trip.destination;
        var parent = boat.getPassengers().stream().filter(p -> p.getUUID().equals(boat.ownerYukkuri()) && p instanceof YukkuriEntity)
                .map(p -> (YukkuriEntity) p).findFirst().orElse(null);
        if (parent == null) return trip.lastAnchor;
        Vec3 anchor = ownerCarryLocal(parent).add(trip.offset);
        double q = Math.clamp((boat.level().getGameTime() - trip.at) / 12.0, 0, 1);
        q = q * q * (3 - 2 * q);
        trip.lastAnchor = trip.phase == 1 ? trip.from.lerp(anchor, q) : trip.phase == 3 ? anchor.lerp(trip.destination, q) : anchor;
        return trip.lastAnchor;
    }

    /** The trip as the client needs it; trips are not saved (a loaded hull restores seats and sends the child home). */
    CompoundTag saveCarry(CarryTrip trip) {
        var tag = new CompoundTag();
        tag.putUUID("Child", trip.child);
        if (boat.ownerYukkuri() != null) tag.putUUID("Holder", boat.ownerYukkuri());
        tag.putInt("Phase", trip.phase);
        tag.putLong("At", trip.at);
        HatBoatEntity.point(tag, "From", trip.from);
        HatBoatEntity.point(tag, "To", trip.destination);
        HatBoatEntity.point(tag, "Pickup", trip.pickup);
        HatBoatEntity.point(tag, "OwnerTo", trip.ownerDestination);
        HatBoatEntity.point(tag, "Offset", trip.offset);
        HatBoatEntity.point(tag, "Facing", trip.facing);
        HatBoatEntity.point(tag, "ChildNow", carriedLocal(trip));
        return tag;
    }

    CarryTrip carryPose() {
        if (!boat.level().isClientSide) return carryTrip;
        var tag = boat.deckData().getCompound("Carry");
        if (!tag.hasUUID("Child")) return null;
        var trip = new CarryTrip(tag.getUUID("Child"), DeckLayout.NONE, DeckLayout.NONE, DeckLayout.NONE, DeckLayout.NONE, HatBoatEntity.point(tag, "From"),
                HatBoatEntity.point(tag, "To"), HatBoatEntity.point(tag, "Pickup"), HatBoatEntity.point(tag, "OwnerTo"), HatBoatEntity.point(tag, "Offset"),
                tag.getLong("At"));
        trip.phase = tag.getInt("Phase");
        trip.lastAnchor = HatBoatEntity.point(tag, "ChildNow");
        if (tag.contains("FacingX")) trip.facing = HatBoatEntity.point(tag, "Facing");
        return trip;
    }
}
