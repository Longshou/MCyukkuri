package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.boat.HullHelm;
import io.github.longshou.mcyukkuri.subspecies.crew.Body;
import io.github.longshou.mcyukkuri.subspecies.crew.CrewTransfer;
import io.github.longshou.mcyukkuri.subspecies.crew.DeckLayout;
import io.github.longshou.mcyukkuri.subspecies.crew.DeckLayout.Kind;
import io.github.longshou.mcyukkuri.subspecies.crew.HatDeck;
import io.github.longshou.mcyukkuri.subspecies.crew.Lease;
import io.github.longshou.mcyukkuri.subspecies.crew.Purpose;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Entity;

/**
 * The entity-facing side of the deck: how care, toilet, practice, rescue, departure and boarding needs turn into deck
 * leases and moves. Every seat comes from the {@link HatDeck}; this class only decides who asks for which kind of seat, remembers
 * what a station is for, and sends bodies home when it ends. It replaces the service-seat, departure-berth and rescue-pair
 * ledgers (P4 folds the transfer parts into the shared crew transfer).
 */
final class HatStations {
    private final HatBoatEntity boat;
    /** A care or hygiene station: the child's seat, the captain's seat beside it (or NONE), what it is for. */
    private record Station(int youngSlot, int captainSlot, boolean rear, boolean toilet, boolean practice) {}
    private final Map<UUID, Station> stations = new HashMap<>();
    private int ownerToiletSlot = DeckLayout.NONE;
    private boolean rescueOwnerHeld;
    private int rescueStand = DeckLayout.NONE;
    String carePlanDebug = "not planned", departureDebug = "not requested";
    long boardingEdgeUntil;
    /** id -> {next try tick, failures}: a body that cannot get home is not retried every tick. */
    private final Map<UUID, long[]> returnTries = new HashMap<>();
    /** The farthest a practice hop or walk may go, in hat units. */
    private static final double PRACTICE_REACH = 4.5;
    private UUID departureChild;
    private int departureSlot = DeckLayout.NONE;
    private long departureUntil;
    private final Set<UUID> yielders = new HashSet<>();

    HatStations(HatBoatEntity boat) { this.boat = boat; }

    private HatDeck deck() { return boat.deck(); }
    private Vec3 local(Entity e) { return boat.toLocal(e.position()); }
    private Body body(YukkuriEntity y) { return boat.bodyOf(y); }
    private Vec3 slotPos(int slot) { return boat.deckLayout().slot(slot).pos(); }
    private long now() { return boat.level().getGameTime(); }
    /** The body sits on that seat and is not moving. */
    private boolean settled(UUID id, int slot) {
        if (!boat.usesDeck()) return false;
        var p = deck().get(id);
        return p != null && p.aboard() && p.slot() == slot && !deck().moving(id);
    }
    private YukkuriEntity entity(UUID id) {
        return boat.level() instanceof ServerLevel server && server.getEntity(id) instanceof YukkuriEntity y ? y : null;
    }

    /** The rider is on an errand (care, toilet, practice, a lift): it must not be shaken off or reflowed meanwhile. */
    boolean isStationed(UUID id) {
        return stations.containsKey(id) || boat.crew().involves(id)
                || id.equals(boat.ownerYukkuri()) && (ownerToiletSlot >= 0 || rescueOwnerHeld);
    }
    private final Map<UUID, Long> liftRetry = new HashMap<>();
    /** A reflow that needs a lift may ask for one only every few seconds per child. */
    boolean liftAllowed(UUID child) {
        long now = now();
        if (now < liftRetry.getOrDefault(child, 0L)) return false;
        liftRetry.put(child, now + 100);
        if (liftRetry.size() > 64) liftRetry.clear();
        return true;
    }

    // ---------------------------------------------------------------- leases and tidying
    boolean hasServiceSeats() { return !stations.isEmpty(); }
    boolean hasReturningSeats() { for (var p : deck().all()) if (p.aboard() && p.returning()) return true; return false; }
    boolean ownerUsesToiletSeat() { return ownerToiletSlot >= 0; }
    String serviceSeatIdsDebug() { return stations.keySet().toString(); }
    String returningSeatIdsDebug() {
        var out = new ArrayList<UUID>();
        for (var p : deck().all()) if (p.aboard() && p.returning()) out.add(p.id());
        return out.toString();
    }
    String carePlanDebug() {
        return carePlanDebug + "; carry=" + boat.carryPlanDebug + "; returning=" + returningSeatIdsDebug() + " service=" + stations.size()
                + " transfers=" + boat.crew().tickets().size() + " last=" + deck().lastRefusal();
    }

    /** Once a second: a booking whose body is no longer coming, a lapsed refuge, a berth the pupil no longer keeps, a station nobody uses. */
    void tickLeases() {
        if (!boat.usesDeck()) return;
        if (!(boat.level() instanceof ServerLevel server)) return;
        for (var p : List.copyOf(deck().all())) {
            var lease = p.lease();
            if (!p.aboard() && lease != null && (lease.purpose() == Purpose.BOARD || lease.purpose() == Purpose.RESCUE)) {
                var y = server.getEntity(p.id()) instanceof YukkuriEntity found ? found : null;
                boolean gone = y == null || !y.isAlive();
                if (!gone) {
                    var ticket = y.family().crew().ticket();
                    boolean coming = ticket != null && !ticket.finished() && ticket.touches(boat);
                    gone = y.isPassenger() && !coming || !coming && !y.family().crew().active() || y.distanceToSqr(boat) > 64;
                }
                if (gone) deck().release(p.id(), "no_longer_coming");
            }
            if (p.caregiver() != null) {
                var caregiver = boat.owner();
                var child = server.getEntity(p.id()) instanceof YukkuriEntity y ? y : null;
                // A wet child can still take the already granted berth after a fall, but the authority never survives a
                // dead or departed captain or a real shore hand-off.
                if (caregiver == null || !caregiver.isAlive() || caregiver.getVehicle() != boat
                        || child != null && (!child.isAlive() || child.getVehicle() != boat && child.onGround() && !child.isInWaterOrBubble()))
                    deck().dropRefuge(p.id(), "caregiver_lapsed");
            }
            if (lease != null && lease.purpose() == Purpose.RETAINED && boat.tickCount > 60) {
                var y = server.getEntity(p.id()) instanceof YukkuriEntity found ? found : null;
                if (y == null || !y.isAlive() || !y.aquaticLife().firstVoyage().keepsBerth(boat)) releaseVoyageBerth(p.id());
            }
        }
        stations.keySet().removeIf(id -> !deck().aboard(id));
        // A service lease nobody uses (a loaded save, a cancelled errand) sends its body home.
        for (var p : List.copyOf(deck().all())) {
            var lease = p.lease();
            if (!p.aboard() || lease == null || lease.purpose() != Purpose.SERVICE) continue;
            boolean owner = p.id().equals(boat.ownerYukkuri());
            boolean used = stations.containsKey(p.id()) || owner && (!stations.isEmpty() || ownerToiletSlot >= 0 || boat.crew().busy())
                    || boat.crew().involves(p.id()) || owner && rescueOwnerHeld
                    || deck().moving(p.id()) || yielders.contains(p.id()) || departureChild != null || now() < boardingEdgeUntil;
            if (!used) deck().release(p.id(), "unused_station");
        }
        returnTries.keySet().removeIf(id -> !deck().aboard(id));
    }

    /** A body left the hull: whatever station it held ends with it. */
    void onLeft(UUID id) {
        stations.remove(id);
        if (id.equals(boat.ownerYukkuri())) { ownerToiletSlot = DeckLayout.NONE; rescueOwnerHeld = false; rescueStand = DeckLayout.NONE; }
        yielders.remove(id);
        if (id.equals(departureChild)) clearDeparture();
        returnTries.remove(id);
        if (stations.isEmpty() && ownerToiletSlot < 0 && !boat.hasIncoming()) {
            var owner = boat.ownerYukkuri();
            var lease = owner == null ? null : deck().lease(owner);
            if (lease != null && lease.purpose() == Purpose.SERVICE && deck().aboard(owner) && !boat.crew().busy() && !rescueOwnerHeld)
                deck().release(owner, "station_ended");
        }
    }


    /** Stop every station and walk: the hull capsizes and everyone is where they stand. */
    void clearAll() {
        stations.clear();
        ownerToiletSlot = DeckLayout.NONE;
        rescueOwnerHeld = false;
        rescueStand = DeckLayout.NONE;
        yielders.clear();
        clearDeparture();
        returnTries.clear();
    }

    // ---------------------------------------------------------------- care stations
    boolean prepareCareSeat(YukkuriEntity child, YukkuriEntity parent) { return prepareServiceSeat(child, parent, false); }
    boolean prepareRearCareSeat(YukkuriEntity child, YukkuriEntity parent) { return prepareServiceSeat(child, parent, true); }

    boolean prepareServiceSeat(YukkuriEntity child, YukkuriEntity parent, boolean rear) {
        if (!boat.usesDeck() || boat.level().isClientSide || boat.unsteadyMotion() || child == null || parent == null || parent != boat.owner() || child.getVehicle() != boat
                || parent.getVehicle() != boat || !boat.careRecipient(child) || !boat.protects(parent) || !boat.protects(child)
                || !boat.isAfloat() || boat.flooding() != 0 || boat.hasIncoming() || ownerToiletSlot >= 0 || departurePreparing()
                || deck().hasLease(child.getUUID(), Purpose.RETAINED)) return false;
        var id = child.getUUID();
        var held = stations.get(id);
        if (held != null && held.captainSlot() >= 0) {
            if (held.rear() != rear) return false;
            // Unfolding may require a real withdrawal; the care station is restored afterwards.
            if (!boat.crew().busy() && !deck().moving(parent.getUUID()) && !settled(parent.getUUID(), held.captainSlot())) {
                if (!deck().moveWithin(body(parent), held.captainSlot(), local(parent), false, Purpose.SERVICE)) { releaseCareSeat(id); return false; }
                boat.syncSeats();
            }
            return true;
        }
        if (boat.crew().busy()) return false;
        if (stations.keySet().stream().anyMatch(other -> !other.equals(id))) return false;
        // Feeding takes precedence over a child's voluntary movement.
        if (held != null) stations.remove(id);
        var plan = deck().planCare(body(parent), body(child), rear, local(parent), local(child));
        if (plan == null) {
            carePlanDebug = "no care pair for " + body(child).cls().id + (rear ? " (rear)" : "");
            GameplayLog.limited(boat, "aquatic", "deck.nocare." + id, 200, "deck refuse body={} class={} purpose=SERVICE reason=NO_SLOT_FOR_CLASS care rear={}",
                    id, body(child).cls().id, rear);
            return false;
        }
        if (!beginPair(child, parent, plan.captainSlot(), plan.youngSlot())) return false;
        stations.put(id, new Station(plan.youngSlot(), plan.captainSlot(), rear, false, false));
        child.childhood().stopHatPractice();
        boat.syncSeats();
        return true;
    }

    /** Both bodies walk to their care seats together; a child that cannot climb a step is lifted by the captain instead. */
    private boolean beginPair(YukkuriEntity child, YukkuriEntity parent, int captainSlot, int youngSlot) {
        var cb = body(child);
        var pb = body(parent);
        if (deck().movePair(pb, captainSlot, local(parent), false, cb, youngSlot, local(child), false, Purpose.SERVICE, false)) return true;
        if (deck().needsLift()) {
            if (boat.beginCarry(child, parent, youngSlot, captainSlot)) return true;
            carePlanDebug = "lift: " + boat.carryPlanDebug;
            return false;
        }
        if (cb.jumps() && deck().movePair(pb, captainSlot, local(parent), false, cb, youngSlot, local(child), true, Purpose.SERVICE, false)) return true;
        carePlanDebug = "pair refused: " + deck().lastRefusal();
        return false;
    }

    /** One child to a seat: a walk, a lift by the captain when it cannot climb, or a hop when it can jump. */
    private boolean moveChild(YukkuriEntity child, YukkuriEntity parent, int slot, int captainDelivery) {
        var cb = body(child);
        if (deck().moveWithin(cb, slot, local(child), false, Purpose.SERVICE)) return true;
        if (deck().needsLift() && parent != null && boat.beginCarry(child, parent, slot, captainDelivery)) return true;
        return cb.jumps() && deck().moveWithin(cb, slot, local(child), true, Purpose.SERVICE);
    }

    boolean careSeatReady(YukkuriEntity child) {
        var st = child == null ? null : stations.get(child.getUUID());
        var parent = boat.owner();
        return st != null && st.captainSlot() >= 0 && parent != null && !boat.crew().busy()
                && settled(child.getUUID(), st.youngSlot()) && settled(parent.getUUID(), st.captainSlot());
    }

    boolean rearCareSeat(YukkuriEntity child) {
        var st = child == null ? null : stations.get(child.getUUID());
        return st != null && st.rear();
    }

    private int firstFree(Body b, Kind kind, Vec3 near) {
        for (int seat : deck().freeSeats(b, Purpose.SERVICE, near)) if (boat.deckLayout().slot(seat).kind() == kind) return seat;
        return DeckLayout.NONE;
    }

    boolean prepareToiletSeat(YukkuriEntity child, YukkuriEntity parent) {
        if (boat.level().isClientSide || child == null || parent == null || parent != boat.owner() || child.getVehicle() != boat
                || parent.getVehicle() != boat || !boat.protects(child) || !boat.protects(parent) || !boat.isAfloat()) return false;
        var id = child.getUUID();
        var st = stations.get(id);
        if (st == null || st.captainSlot() < 0 || !st.rear()) return false;
        if (st.toilet()) return true;
        if (!careSeatReady(child)) return false;
        int rim = firstFree(body(child), Kind.TOILET_RIM, local(child));
        if (rim < 0) { carePlanDebug = "toilet: no free toilet rim for " + body(child).cls().id; return false; }
        if (!moveChild(child, parent, rim, st.captainSlot())) { carePlanDebug = "toilet: transfer to " + boat.deckLayout().slot(rim).id() + " refused: " + deck().lastRefusal(); return false; }
        stations.put(id, new Station(rim, st.captainSlot(), true, true, false));
        boat.syncSeats();
        return true;
    }

    boolean prepareToiletSeat(YukkuriEntity child) {
        if (boat.level().isClientSide || child == null || child.getVehicle() != boat || !boat.protects(child) || !boat.isAfloat()
                || boat.flooding() != 0 || boat.hasIncoming()) return false;
        if (child.getUUID().equals(boat.ownerYukkuri())) return prepareOwnerToiletSeat(child);
        var id = child.getUUID();
        var st = stations.get(id);
        if (st != null && st.toilet()) return true;
        if (st != null && st.captainSlot() >= 0) return false;
        if (stations.keySet().stream().anyMatch(other -> !other.equals(id)) || hasReturningSeats() || ownerSeatBusy()) return false;
        int rim = firstFree(body(child), Kind.TOILET_RIM, local(child));
        if (rim < 0) return false;
        if (!moveChild(child, null, rim, DeckLayout.NONE)) return false;
        stations.put(id, new Station(rim, DeckLayout.NONE, false, true, false));
        boat.syncSeats();
        return true;
    }

    boolean prepareOwnerToiletSeat(YukkuriEntity owner) {
        if (owner != boat.owner()) return false;
        if (ownerToiletSlot >= 0) return true;
        if (!stations.isEmpty() || ownerSeatBusy()) return false;
        var pb = body(owner);
        int rim = firstFree(pb, Kind.TOILET_RIM, local(owner));
        if (rim < 0) { carePlanDebug = "toilet: no free toilet rim for the captain"; return false; }
        if (!deck().moveWithin(pb, rim, local(owner), false, Purpose.SERVICE)) return false;
        ownerToiletSlot = rim;
        boat.syncSeats();
        return true;
    }

    /** The captain is off her stern seat, moving, or at a toilet rim. */
    boolean ownerSeatBusy() {
        if (!boat.usesDeck()) return false;
        var owner = boat.ownerYukkuri();
        var p = owner == null ? null : deck().get(owner);
        return ownerToiletSlot >= 0 || p != null && p.aboard() && (deck().moving(owner) || p.home() >= 0 && p.slot() != p.home());
    }

    boolean toiletSeatReady(YukkuriEntity child) {
        if (child == null) return false;
        if (child.getUUID().equals(boat.ownerYukkuri())) return ownerToiletSlot >= 0 && settled(child.getUUID(), ownerToiletSlot);
        var st = stations.get(child.getUUID());
        var parent = boat.owner();
        return !boat.crew().busy() && st != null && st.toilet() && settled(child.getUUID(), st.youngSlot())
                && (st.captainSlot() < 0 || parent != null && settled(parent.getUUID(), st.captainSlot()));
    }

    /** The body faces into the hull so its rear is toward the open water beyond the rim seat. */
    void faceToiletOutward(YukkuriEntity child) {
        if (child == null) return;
        var slot = deck().slotOf(child.getUUID());
        if (slot == null) return;
        double yaw = Math.toRadians(slot.yaw());
        var inward = new Vec3(Math.sin(yaw), 0, -Math.cos(yaw)).yRot(boat.turn());
        SurfaceApproach.face(child, SurfaceApproach.yaw(Vec3.ZERO, inward));
    }

    Vec3 toiletDropPosition(YukkuriEntity child) {
        if (!toiletSeatReady(child)) return null;
        var slot = deck().slotOf(child.getUUID());
        if (slot == null) return null;
        double yaw = Math.toRadians(slot.yaw());
        var outLocal = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3 at = boat.toWorld(slot.pos());
        Vec3 outside = boat.toWorld(slot.pos().add(outLocal)).subtract(at).normalize();
        Vec3 raw = at.add(outside.scale(child.getBbWidth() * .55 + .06));
        // A water block hidden under the brim does not make this a valid drop. The rear point must already be beyond the
        // actual hat outline, then have an unobstructed short fall into water.
        Vec3 rawLocal = boat.toLocal(raw);
        if (Double.isFinite(HatVessel.surface(boat.mode(), rawLocal.x, rawLocal.z))) return null;
        BlockPos below = BlockPos.containing(raw.add(0, -.04, 0));
        for (int down = 0; down <= 2; down++) {
            BlockPos atBelow = below.below(down);
            if (boat.level().getFluidState(atBelow).is(FluidTags.WATER)) return raw.add(0, .04, 0);
            if (!boat.level().getBlockState(atBelow).isAir()) return null;
        }
        return null;
    }

    boolean toiletAssistanceValidated(YukkuriEntity child) {
        var st = child == null ? null : stations.get(child.getUUID());
        return st != null && st.toilet() && st.rear();
    }

    boolean releaseToiletSeat(YukkuriEntity child) {
        if (child != null && child.getUUID().equals(boat.ownerYukkuri())) {
            if (ownerToiletSlot < 0) return false;
            ownerToiletSlot = DeckLayout.NONE;
            deck().release(child.getUUID(), "toilet_done");
            boat.syncSeats();
            return true;
        }
        var st = child == null ? null : stations.get(child.getUUID());
        if (st == null || !st.toilet()) return false;
        releaseCareSeat(child.getUUID());
        return true;
    }

    /** End a station: the child and, when no other station needs her, the captain go home. */
    void releaseCareSeat(UUID childId) {
        var st = stations.remove(childId);
        if (st != null) deck().release(childId, "care_done");
        var owner = boat.ownerYukkuri();
        if (owner != null && stations.isEmpty() && ownerToiletSlot < 0 && !boat.crew().busy() && !rescueOwnerHeld) deck().release(owner, "care_done");
        boat.syncSeats();
    }

    /** Send bodies that finished an errand back to the seat they left. Called every few ticks. */
    void tendReturns() {
        if (!boat.usesDeck() || boat.crew().busy()) return;
        long now = now();
        for (var p : List.copyOf(deck().all())) {
            if (!p.aboard() || !p.returning() || deck().moving(p.id())) continue;
            boolean owner = p.id().equals(boat.ownerYukkuri());
            if (stations.containsKey(p.id()) || owner && (ownerToiletSlot >= 0 || rescueOwnerHeld || !stations.isEmpty())) continue;
            if (owner && now < boardingEdgeUntil) continue;
            if (p.home() < 0 || p.slot() == p.home()) { deck().forgetHome(p.id()); continue; }
            var y = entity(p.id());
            if (y == null || y.getVehicle() != boat) { deck().forgetHome(p.id()); continue; }
            if (y.family().crew().busy()) continue;
            var tries = returnTries.computeIfAbsent(p.id(), k -> new long[]{0, 0});
            if (now < tries[0]) continue;
            var parent = boat.owner();
            if (deck().moveWithin(body(y), p.home(), local(y), false, Purpose.BOARD)) continue;
            if (deck().needsLift()) {
                if (parent != null && parent != y && parent.getVehicle() == boat && boat.beginCarry(y, parent, p.home(), DeckLayout.NONE)) continue;
            } else if (y.childhood().canJump() && deck().moveWithin(body(y), p.home(), local(y), true, Purpose.BOARD)) continue;
            tries[1]++;
            tries[0] = now + 40;
            if (tries[1] >= 6) {
                GameplayLog.limited(boat, "aquatic", "deck.home." + p.id(), 200, "deck release body={} reason=home_unreachable home={} last={}", p.id(),
                        boat.deckLayout().slot(p.home()).id(), deck().lastRefusal());
                deck().forgetHome(p.id());
                returnTries.remove(p.id());
            }
        }
    }

    // ---------------------------------------------------------------- practice moves
    boolean beginPracticeMove(YukkuriEntity child, boolean hop) {
        if (boat.level().isClientSide || boat.unsteadyMotion() || child == null || child.getVehicle() != boat || !boat.young(child) || !boat.protects(child)
                || !boat.isAfloat() || boat.flooding() != 0 || boat.hasIncoming() || departurePreparing() || deck().hasLease(child.getUUID(), Purpose.RETAINED)
                || !stations.isEmpty() || hasReturningSeats() || boat.crew().busy() || boat.changingSeat(child) || ownerSeatBusy()) return false;
        var cb = body(child);
        var p = deck().get(child.getUUID());
        if (p == null || !p.aboard() || p.slot() < 0) return false;
        Vec3 from = slotPos(p.slot());
        int chosen = DeckLayout.NONE;
        for (int seat : deck().freeSeats(cb, Purpose.BOARD, from)) {
            var slot = boat.deckLayout().slot(seat);
            // A practice trip is a short hop or walk beside the child, never across the hull.
            if (seat == p.slot() || slot.kind() == Kind.BRIM || slot.pos().distanceToSqr(from) < .16 || slot.pos().distanceTo(from) > PRACTICE_REACH) continue;
            if (!hop && boat.deckLayout().path(p.slot(), seat, cb.cls(), false, null) == null) continue;
            chosen = seat;
            break;
        }
        if (chosen < 0 || !deck().moveWithin(cb, chosen, local(child), hop, Purpose.SERVICE)) return false;
        stations.put(child.getUUID(), new Station(chosen, DeckLayout.NONE, false, false, true));
        boat.syncSeats();
        return true;
    }
    boolean practisingMove(YukkuriEntity child) {
        var st = child == null ? null : stations.get(child.getUUID());
        return st != null && st.practice() && boat.changingSeat(child);
    }
    boolean completePracticeMove(YukkuriEntity child) {
        var st = child == null ? null : stations.get(child.getUUID());
        if (st == null || !st.practice() || boat.changingSeat(child)) return false;
        releaseCareSeat(child.getUUID());
        return true;
    }
    boolean cancelPracticeMove(YukkuriEntity child) {
        var st = child == null ? null : stations.get(child.getUUID());
        if (st == null || !st.practice()) return false;
        releaseCareSeat(child.getUUID());
        return true;
    }











    void releaseRescueOwner(YukkuriEntity parent) {
        if (boat.level().isClientSide || parent != boat.owner()) return;
        rescueOwnerHeld = false;
        rescueStand = DeckLayout.NONE;
        if (parent.getVehicle() == boat && stations.isEmpty() && ownerToiletSlot < 0) deck().release(parent.getUUID(), "rescue_done");
        boat.syncSeats();
    }

    /** The braid rescue no longer runs: drop a hold whose captain left or finished. */
    void tendRescueOwner() {
        if (!rescueOwnerHeld) return;
        var parent = boat.owner();
        if (parent == null || parent.getVehicle() != boat) { rescueOwnerHeld = false; rescueStand = DeckLayout.NONE; boat.syncSeats(); }
        else if (!parent.isAlive() || !parent.family().crew().active()) releaseRescueOwner(parent);
    }
    boolean rescueOwnerHeld() { return rescueOwnerHeld; }

    // ---------------------------------------------------------------- the first voyage's berth
    private void clearDeparture() {
        departureChild = null;
        departureSlot = DeckLayout.NONE;
        yielders.clear();
    }
    boolean departurePreparing() { return departureChild != null; }
    String departureDebug() { return departureDebug; }
    boolean preparingDepartureBerth(YukkuriEntity child) { return child != null && child.getUUID().equals(departureChild); }


    /** The pupil, already on the front brim, keeps that berth while its own hat is away. */
    boolean keepVoyageBerth(YukkuriEntity child) {
        if (boat.level().isClientSide || boat.unsteadyMotion() || !boat.young(child) || !boat.onBrim(child) || !boat.protects(child) || boat.hasIncoming()
                || ownerSeatBusy() || departureChild != null && !child.getUUID().equals(departureChild)) return false;
        var id = child.getUUID();
        var p = deck().get(id);
        if (p == null || !p.aboard() || p.slot() < 0) return false;
        if (deck().hasLease(id, Purpose.RETAINED)) return true;
        return deck().claim(body(child), Purpose.RETAINED, null, p.slot(), 0) != null;
    }

    /** The pupil takes the front brim berth for its departure; idle siblings that stand in its way are moved first. */
    boolean keepDepartureBerth(YukkuriEntity child) {
        if (!boat.usesDeck() || boat.level().isClientSide || boat.unsteadyMotion() || !boat.young(child) || !boat.protects(child) || boat.hasIncoming() || hasServiceSeats()
                || hasReturningSeats() || boat.hasCarryTrip() || ownerSeatBusy() || departureChild != null && !child.getUUID().equals(departureChild)) return false;
        if (preparingDepartureBerth(child)) return true;
        var id = child.getUUID();
        var cb = body(child);
        var p = deck().get(id);
        if (p == null || !p.aboard() || p.slot() < 0) return false;
        var layout = boat.deckLayout();
        int brim = DeckLayout.NONE;
        for (var slot : layout.slots()) if (slot.kind() == Kind.BRIM && slot.allows(cb.cls())) { brim = slot.index(); break; }
        if (brim < 0) { departureDebug = "no front brim seat for " + cb.cls().id; return false; }
        if (deck().hasLease(id, Purpose.RETAINED) && p.slot() == brim && !deck().moving(id)) return true;
        var room = deck().planSeat(cb, Purpose.RETAINED, brim);
        if (room == null) { departureDebug = "front brim cannot be freed: " + deck().lastRefusal(); return false; }
        if (room.moves().isEmpty()) return startDeparture(child, brim);
        yielders.clear();
        if (!boat.applyRoom(room, yielders)) { departureDebug = "yield refused: " + deck().lastRefusal(); return false; }
        departureChild = id;
        departureSlot = brim;
        departureUntil = now() + 240;
        departureDebug = "yield started for " + yielders.size() + " sibling(s)";
        boat.syncSeats();
        return true;
    }

    private boolean startDeparture(YukkuriEntity child, int brim) {
        var cb = body(child);
        var lease = deck().claim(cb, Purpose.RETAINED, null, brim, 0);
        if (lease == null) { departureDebug = "claim refused: " + deck().lastRefusal(); return false; }
        if (deck().get(child.getUUID()).slot() != brim && !deck().moveWithin(cb, brim, local(child), true, Purpose.BOARD)) {
            deck().release(child.getUUID(), "departure_move_refused");
            departureDebug = "hop to the brim refused: " + deck().lastRefusal();
            return false;
        }
        departureDebug = "ready " + boat.deckLayout().slot(brim).id();
        clearDeparture();
        boat.syncSeats();
        return true;
    }

    /** APPROACH waits for the siblings' real walk, then books the pupil's berth. */
    boolean advanceDepartureBerth(YukkuriEntity child) {
        if (!boat.usesDeck() || !preparingDepartureBerth(child)) return false;
        if (now() > departureUntil) { departureDebug = "yield expired"; clearDeparture(); return false; }
        for (var id : yielders) if (deck().moving(id)) return true;
        int brim = departureSlot;
        clearDeparture();
        departureChild = child.getUUID();
        departureSlot = brim;
        boolean ok = startDeparture(child, brim);
        if (!ok) clearDeparture();
        return ok;
    }

    void releaseVoyageBerth(UUID child) {
        if (child.equals(departureChild)) clearDeparture();
        if (deck().hasLease(child, Purpose.RETAINED)) deck().release(child, "voyage_end");
        boat.syncSeats();
    }

    Vec3 voyageBerth(YukkuriEntity child) {
        var p = deck().get(child.getUUID());
        return p != null && p.lease() != null && p.lease().purpose() == Purpose.RETAINED && p.home() >= 0 ? slotPos(p.home()) : null;
    }

    boolean onVoyageBerth(YukkuriEntity child) {
        if (!boat.usesDeck() || child == null) return false;
        var p = deck().get(child.getUUID());
        return p != null && p.aboard() && p.lease() != null && p.lease().purpose() == Purpose.RETAINED && p.home() >= 0 && p.slot() == p.home()
                && !deck().moving(child.getUUID()) && boat.deckLayout().slot(p.slot()).kind() == Kind.BRIM;
    }

    // ---------------------------------------------------------------- boarding from outside
    /** The hull-local edge of the entry nearest an outsider: seats nearest it are where a boarder lands. */
    private Vec3 entryNear(Vec3 worldFrom) {
        var local = boat.toLocal(worldFrom);
        Vec3 best = null;
        double cost = Double.MAX_VALUE;
        for (var entry : boat.deckLayout().entries()) {
            double d = Math.hypot(entry.edge().x - local.x, entry.edge().z - local.z);
            if (d < cost) { cost = d; best = entry.edge(); }
        }
        return best == null ? local : best;
    }
    private Vec3 incomingPos(UUID id) {
        var p = deck().get(id);
        return p != null && !p.aboard() && p.target() >= 0 ? slotPos(p.target()) : null;
    }

    /** A place for this young (nearest {@code prefer}, hull-local; null for the usual one) kept for it until it boards. */
    Vec3 reserve(YukkuriEntity child, Vec3 prefer) {
        if (child == null || !boat.young(child)) return null;
        var have = incomingPos(child.getUUID());
        if (have != null) return have;
        var cb = body(child);
        if (deck().reclaimRetained(cb)) return incomingPos(child.getUUID());
        var lease = deck().claim(cb, Purpose.BOARD, prefer, DeckLayout.NONE, HatDeck.BOARD_TTL);
        return lease == null ? null : incomingPos(child.getUUID());
    }

    /** The place a young would get next, without keeping it. */
    Vec3 peekSeat(YukkuriEntity child) {
        if (child == null || !boat.young(child)) return null;
        var have = incomingPos(child.getUUID());
        if (have != null) return have;
        var a = deck().canAdmit(body(child), Purpose.BOARD);
        return a.ok() ? slotPos(a.slot()) : null;
    }


    /** A physical braid lift reserves the seat nearest the entry the child comes up at. */
    Vec3 reserveRescueBrim(YukkuriEntity child, Vec3 actualFrom) { return reserveRescueBrim(child, actualFrom, DeckLayout.NONE); }
    /** Reserve the preflighted rescue seat; no alternate seat is silently substituted. */
    Vec3 reserveRescueBrim(YukkuriEntity child, Vec3 actualFrom, int preferred) { return reserveBoardingSeat(child, actualFrom, false, true, preferred); }

    Vec3 reserveBoardingSeat(YukkuriEntity child, Vec3 actualFrom, boolean emergency) { return reserveBoardingSeat(child, actualFrom, emergency, DeckLayout.NONE); }
    /** Reserve the preflighted boarding seat; no alternate seat is silently substituted. */
    Vec3 reserveBoardingSeat(YukkuriEntity child, Vec3 actualFrom, boolean emergency, int preferred) {
        return reserveBoardingSeat(child, actualFrom, emergency, false, preferred);
    }

    private Vec3 reserveBoardingSeat(YukkuriEntity child, Vec3 actualFrom, boolean emergency, boolean rescue, int preferred) {
        if (boat.level().isClientSide || boat.landing() || child == null || actualFrom == null || !boat.admits(child) || !boat.isAfloat() || boat.unsteadyMotion()) return null;
        if (emergency && boat.young(child) && !boat.requestEmergencyBerth(child)) return null;
        var have = incomingPos(child.getUUID());
        if (have != null) return have;
        var cb = body(child);
        var near = entryNear(actualFrom);
        var lease = rescue ? claimAt(cb, Purpose.RESCUE, near, preferred, HatDeck.BOARD_TTL) : claimAt(cb, Purpose.BOARD, near, preferred, HatDeck.BOARD_TTL);
        if (lease == null && (emergency || rescue)) lease = claimAt(cb, Purpose.RESCUE, near, preferred, HatDeck.BOARD_TTL);
        if (lease == null) return null;
        boat.syncSeats();
        return incomingPos(child.getUUID());
    }

    /** The place a returning pupil takes in this hull: the berth kept for it while its own hat was away, else a close place. */
    Vec3 returnSeat(YukkuriEntity child, boolean emergency) { return returnSeat(child, emergency, DeckLayout.NONE); }
    /** Reclaim the preflighted voyage berth, or reserve exactly the fallback boarding seat. */
    Vec3 returnSeat(YukkuriEntity child, boolean emergency, int preferred) {
        if (boat.level().isClientSide || child == null || !boat.young(child) || !boat.isAfloat() || boat.unsteadyMotion()) return null;
        var cb = body(child);
        if (deck().reclaimRetained(cb)) {
            var target = incomingPos(child.getUUID());
            if (preferred >= 0 && (target == null || deck().get(child.getUUID()).target() != preferred)) return null;
            boat.syncSeats();
            return target;
        }
        return reserveBoardingSeat(child, child.position(), emergency, false, preferred);
    }

    /** A wet owner first reaches a nearby supported seat, then walks to the stern after settling. */
    Vec3 reserveOwnBoardingSeat(YukkuriEntity actor, Vec3 actualFrom, Predicate<Vec3> reachable) {
        return reserveOwnBoardingSeat(actor, actualFrom, reachable, DeckLayout.NONE);
    }
    /** Reserve exactly the preflighted owner seat after rechecking its physical approach. */
    Vec3 reserveOwnBoardingSeat(YukkuriEntity actor, Vec3 actualFrom, Predicate<Vec3> reachable, int preferred) {
        if (boat.level().isClientSide || !boat.ownsPhysicalHat(actor) || actor.getVehicle() == boat || !boat.isAfloat() || boat.unsteadyMotion() || actualFrom == null) return null;
        var pb = body(actor);
        var near = entryNear(actualFrom);
        for (int seat : deck().freeSeats(pb, Purpose.OWN, near)) {
            if (preferred >= 0 && seat != preferred) continue;
            var pos = slotPos(seat);
            if (!reachable.test(pos)) continue;
            var lease = claimAt(pb, Purpose.OWN, near, seat, HatDeck.OWN_TTL);
            if (lease == null) continue;
            boardingEdgeUntil = now() + 80;
            boat.syncSeats();
            return pos;
        }
        return null;
    }

    private Lease claimAt(Body body, Purpose purpose, Vec3 near, int preferred, long ttl) {
        return preferred >= 0 ? deck().claimAt(body, purpose, near, preferred, ttl)
                : deck().claim(body, purpose, near, DeckLayout.NONE, ttl);
    }

    void releaseOwnBoardingSeat(YukkuriEntity actor) {
        if (boat.level().isClientSide || !boat.ownsPhysicalHat(actor) || !deck().hasLease(actor.getUUID(), Purpose.OWN)) return;
        // An idle self-rescue stop is not authority to cancel a captain's current SERVICE errand.
        boardingEdgeUntil = 0;
        deck().release(actor.getUUID(), "boarding_done");
        boat.syncSeats();
    }

    /** Move to the closest departure seat toward a world point along the seats' walking edges. */
    boolean approachBoardingEdge(YukkuriEntity rider, Vec3 towardWorld) { return approachBoardingEdge(null, rider, towardWorld); }

    /** The captain is still using her actual edge station for a bounded physical errand. */
    void holdBoardingEdge(YukkuriEntity rider) {
        if (!boat.level().isClientSide && rider != null && rider.getVehicle() == boat
                && deck().hasLease(rider.getUUID(), Purpose.SERVICE)) boardingEdgeUntil = now() + 40;
    }

    /** A source-hull HOP ticket alone may walk its own body to the departure edge during APPROACH. */
    boolean approachBoardingEdge(CrewTransfer ticket, YukkuriEntity rider, Vec3 towardWorld) {
        boolean ownDeparture = ticket != null && rider != null && rider.family().crew().ticket() == ticket
                && ticket.allowsDepartureApproach(boat, rider);
        if (boat.level().isClientSide || rider == null || towardWorld == null || rider.getVehicle() != boat || !boat.protects(rider) || !boat.isAfloat()
                || boat.unsteadyMotion() || boat.transportingInHat(rider) && !ownDeparture) return false;
        boardingEdgeUntil = now() + 40;
        if (boat.changingSeat(rider)) return true;
        var rb = body(rider);
        var p = deck().get(rider.getUUID());
        if (p == null || !p.aboard()) return false;
        var near = entryNear(towardWorld);
        double have = p.slot() < 0 ? Double.MAX_VALUE : slotPos(p.slot()).distanceTo(near);
        int tried = 0;
        boolean nearer = false;
        for (int seat : deck().freeSeats(rb, Purpose.SERVICE, near)) {
            if (seat == p.slot() || slotPos(seat).distanceTo(near) >= have - 1e-6) continue;
            nearer = true;
            if (++tried > 8) break;
            boolean walk = p.slot() >= 0 && boat.deckLayout().path(p.slot(), seat, rb.cls(), false, null) != null;
            if (deck().moveWithin(rb, seat, local(rider), !walk && rb.jumps(), Purpose.SERVICE)) { boat.syncSeats(); return true; }
        }
        // Already on the seat nearest the departure edge that the deck lets this body use: that is where it waits.
        return !nearer && p.slot() >= 0;
    }

    /** The free seats nearest a world point, nearest first; read only. */
    List<Vec3> brimSeatsToward(YukkuriEntity child, Vec3 towardWorld, int limit) {
        if (boat.level().isClientSide || child == null || towardWorld == null || !boat.young(child) || !boat.isAfloat() || boat.unsteadyMotion() || !boat.hasPassenger(child)) return List.of();
        var p = deck().get(child.getUUID());
        var out = new ArrayList<Vec3>();
        for (int seat : deck().freeSeats(body(child), Purpose.SERVICE, boat.toLocal(towardWorld))) {
            if (p != null && seat == p.slot()) continue;
            out.add(slotPos(seat));
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** A child on a rim moves to an inner seat when it is scared; a body already inside stays. */
    boolean requestInnerRefuge(YukkuriEntity child) {
        var cb = body(child);
        var p = deck().get(child.getUUID());
        if (p == null || !p.aboard() || p.slot() < 0) return false;
        var kind = boat.deckLayout().slot(p.slot()).kind();
        if (kind == Kind.BOW_PERCH || kind == Kind.NEST_HOLLOW || kind == Kind.WELL_FRONT) return true;
        var parent = boat.owner();
        Vec3 near = parent == null ? slotPos(p.slot()) : local(parent);
        for (int seat : deck().freeSeats(cb, Purpose.BOARD, near)) {
            var k = boat.deckLayout().slot(seat).kind();
            if (k != Kind.BOW_PERCH && k != Kind.NEST_HOLLOW && k != Kind.WELL_FRONT) continue;
            boolean walk = boat.deckLayout().path(p.slot(), seat, cb.cls(), false, null) != null;
            if (deck().moveWithin(cb, seat, local(child), !walk && cb.jumps(), Purpose.BOARD)) { boat.syncSeats(); return true; }
        }
        return false;
    }
}
