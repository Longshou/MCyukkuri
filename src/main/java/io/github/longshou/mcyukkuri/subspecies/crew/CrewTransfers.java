package io.github.longshou.mcyukkuri.subspecies.crew;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.family.BraidCarry;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.crew.CrewTransfer.Anchor;
import io.github.longshou.mcyukkuri.subspecies.crew.CrewTransfer.Mode;
import io.github.longshou.mcyukkuri.subspecies.crew.CrewTransfer.Request;
import io.github.longshou.mcyukkuri.subspecies.crew.CrewTransfer.Result;
import io.github.longshou.mcyukkuri.family.BraidRescueGeometry;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.HatBoatRoute;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The callers' side of {@link CrewTransfer}: one method per situation the decision layers know, each turning their decision into
 * a request (mode, anchors, deck purpose) and returning the ticket or the refusal class. A decision layer keeps deciding who,
 * when and why; where the body goes, how, and what it may do on the way is decided here and nowhere else.
 */
public final class CrewTransfers {
    private static Anchor world(YukkuriEntity body) {
        return body.isInWaterOrBubble() ? Anchor.water(body.position()) : Anchor.ground(body.position());
    }
    private static Anchor seat(HatBoatEntity hat, YukkuriEntity body) {
        var placed = hat.deck().get(body.getUUID());
        return Anchor.berth(hat, placed == null ? DeckLayout.NONE : placed.slot());
    }
    private static Anchor anySeat(HatBoatEntity hat) { return Anchor.berth(hat, DeckLayout.NONE); }

    public static Result open(Request request) {
        if (request.body().level().isClientSide || !(request.body().level() instanceof ServerLevel level)) return Result.refusedClient();
        return request.mode() == Mode.LIFT && !request.body().family().crew().active()
                ? BraidCarry.begin(request).tick(level, request) : CrewTransfer.open(level, request);
    }

    /** The captain steps from the bank onto the stern of her hat. */
    public static Result embark(YukkuriEntity owner, HatBoatEntity hat) {
        return open(new Request(Mode.HOP, owner, null, world(owner), anySeat(hat), Purpose.OWN, false, false, DeckLayout.NONE, "embark"));
    }

    /** A capable child on dry ground hops into the hat; {@code emergency} may sacrifice provisions for the seat. */
    public static Result boardFromShore(YukkuriEntity body, HatBoatEntity hat, boolean emergency) {
        return open(new Request(Mode.HOP, body, null, world(body), anySeat(hat), Purpose.BOARD, emergency, false, DeckLayout.NONE, "board_from_shore"));
    }

    /** A capable swimmer pulls itself over the rim (a child to any seat, the owner to her own hat). */
    public static Result climb(YukkuriEntity body, HatBoatEntity hat, boolean own) {
        return open(new Request(Mode.CLIMB, body, null, Anchor.water(body.position()), anySeat(hat), own ? Purpose.OWN : Purpose.BOARD, !own, false,
                DeckLayout.NONE, own ? "climb_own_hat" : "scramble"));
    }

    /**
     * A swimmer or a child on the bank is brought aboard by the captain of {@code hat}: a jumper in the water climbs with her nudge, any
     * other is lifted in her braid. The decision gates stay on the caller's side of the line: whether a swimmer is
     * helped at all ({@link WaterIncidents}), whether the child can be lifted yet, whether she sees it.
     */
    public static Result liftAboard(YukkuriEntity body, HatBoatEntity hat) {
        var carrier = hat.captain();
        boolean swimming = body.isInWaterOrBubble();
        if (carrier == null || !carrier.isAlive() || carrier.level() != body.level() || carrier.getVehicle() != hat)
            return Result.refused(CrewTransfer.Refusal.TRANSIENT, "no_captain_aboard");
        if (body.isPassenger() || body.family().flying() || !swimming && !body.onGround() || swimming && !body.waterDistress().canBeLifted()
                || body.distanceToSqr(hat) > 9 || !carrier.hasLineOfSight(body) || !hat.admits(body))
            return Result.refused(CrewTransfer.Refusal.TRANSIENT, "preconditions");
        if (body.family().crew().active() || carrier.family().crew().active()) return Result.refused(CrewTransfer.Refusal.TRANSIENT, "busy");
        if (swimming && !WaterIncidents.guide(carrier, body, hat)) return Result.refused(CrewTransfer.Refusal.TRANSIENT, "rescue_unavailable");
        // A water emergency may sacrifice provisions for the seat; ordinary dry boarding only puts them aside.
        boolean emergency = swimming || body.isPanicking() || carrier.aquaticLife().familyLife().alarm().active();
        boolean bites = swimming && CrewTransfer.canStepOut(body) && CrewTransfer.climbInReach(body, hat);
        String how = bites ? "rescue_climb" : swimming ? "rescue_lift" : "board_lift";
        Result result;
        if (bites) {
            result = open(new Request(Mode.CLIMB, body, carrier, world(body), anySeat(hat), Purpose.RESCUE, emergency, true, DeckLayout.NONE, how));
            if (result.ok() || result.refusal() == CrewTransfer.Refusal.TRANSIENT) {
                WaterIncidents.attempted(carrier, body, how, result);
                return result;
            }
            // A capable swimmer can still have an unreachable climbing curve. The same rescuer may carry it instead.
            how = "rescue_lift";
        }
        if (swimming) {
            var preview = CrewTransfer.previewLiftAboard(body, carrier, hat);
            if (!preview.ok()) {
                // Planning a rendezvous is not a physical lift attempt and must not spend its seats, food or failure budget.
                result = Result.refused(preview.refusal() == CrewTransfer.Refusal.GEOMETRY ? CrewTransfer.Refusal.TRANSIENT : preview.refusal(),
                        "rescue_approach:" + preview.reason());
                WaterIncidents.attempted(carrier, body, how, result);
                return result;
            }
        }
        result = open(new Request(Mode.LIFT, body, carrier, world(body), anySeat(hat), Purpose.RESCUE, emergency, swimming, DeckLayout.NONE, how));
        if (swimming) WaterIncidents.attempted(carrier, body, how, result);
        return result;
    }

    /**
     * A dependent young on the bank is set into the moored hat by the captain standing on the bank (braid cradle, or mouth when the
     * braid cannot reach). The captain boards last, so she is not yet aboard: the transfer takes her feet as the pickup stand.
     */
    public static Result loadFromBank(YukkuriEntity body, YukkuriEntity carrier, HatBoatEntity hat) {
        if (carrier == null || !carrier.isAlive() || carrier.level() != body.level() || carrier.isPassenger())
            return Result.refused(CrewTransfer.Refusal.TRANSIENT, "no_captain_on_bank");
        if (body.isPassenger() || body.family().flying() || body.isInWaterOrBubble() || !hat.admits(body))
            return Result.refused(CrewTransfer.Refusal.TRANSIENT, "preconditions");
        return open(new Request(Mode.LIFT, body, carrier, world(body), anySeat(hat), Purpose.BOARD, false, false, DeckLayout.NONE, "load_lift"));
    }

    /** A capsized captain, afloat herself, lifts one of her ejected young onto the hat she has righted. */
    public static Result liftFromWater(YukkuriEntity body, YukkuriEntity carrier, HatBoatEntity hat) {
        return open(new Request(Mode.LIFT, body, carrier, Anchor.water(body.position()), anySeat(hat), Purpose.RESCUE, true, true, DeckLayout.NONE, "water_parent_lift"));
    }

    /**
     * A yukkuri on the bank pulls a swimmer within her reach up onto the ground: braid cradle, or the mouth when the braid cannot
     * reach. She does not enter the water; the landing spot is a standing place inside her own reach.
     */
    public static Result pullToBank(YukkuriEntity body, YukkuriEntity carrier) {
        if (carrier == null || !carrier.isAlive() || carrier.level() != body.level() || carrier.isPassenger() || carrier.isInWaterOrBubble() || !carrier.onGround())
            return Result.refused(CrewTransfer.Refusal.TRANSIENT, "carrier_not_on_bank");
        if (!(body.level() instanceof ServerLevel level) || body.isPassenger() || !body.isInWaterOrBubble() || !body.waterDistress().canBeLifted())
            return Result.refused(CrewTransfer.Refusal.TRANSIENT, "preconditions");
        double ring = Math.max(.35, (CrewReach.hasBraid(carrier) ? BraidRescueGeometry.maximumReach(carrier) : BraidRescueGeometry.holdReach(carrier)) * .6);
        var tries = new ArrayList<Vec3>();
        for (int k = 0; k < 8; k++) tries.add(carrier.position().add(Math.cos(k * Math.PI / 4) * ring, 0, Math.sin(k * Math.PI / 4) * ring));
        tries.add(carrier.position());
        Result last = Result.refused(CrewTransfer.Refusal.GEOMETRY, "no_landing");
        for (var at : tries) {
            var spot = standingGround(level, at, body);
            if (spot == null) continue;
            last = open(new Request(Mode.LIFT, body, carrier, Anchor.water(body.position()), Anchor.ground(spot), Purpose.RESCUE, true, true, DeckLayout.NONE, "pull_to_bank"));
            if (last.ok() || last.refusal() == CrewTransfer.Refusal.INCAPABLE) return last;
        }
        return last;
    }

    /**
     * Growth left this rider no legal seat: she steps over the rim into the water beside the hull in one hop, and from there is an
     * ordinary swimmer (an incident of cause SQUEEZED opens when she lands).
     */
    public static Result squeezeOut(YukkuriEntity body, HatBoatEntity hat) {
        var spot = waterBeside(hat, body);
        if (spot == null) return Result.refused(CrewTransfer.Refusal.GEOMETRY, "no_water_beside");
        return open(new Request(Mode.HOP, body, null, seat(hat, body), Anchor.water(spot), Purpose.BOARD, false, false, DeckLayout.NONE, "squeeze_out"));
    }

    private static Vec3 waterBeside(HatBoatEntity hat, YukkuriEntity body) {
        if (!(hat.level() instanceof ServerLevel level)) return null;
        var local = hat.toLocal(body.position());
        double base = Math.atan2(local.z, local.x);
        for (int k = 0; k < 9; k++) {
            double angle = base + (k % 2 == 1 ? 1 : -1) * ((k + 1) / 2) * Math.PI / 6;
            var around = hat.toWorld(new Vec3(Math.cos(angle), 0, Math.sin(angle))).subtract(hat.toWorld(Vec3.ZERO)).multiply(1, 0, 1);
            if (around.lengthSqr() < 1e-8) continue;
            var dir = around.normalize();
            double reach = hat.brimReachToward(dir) + HatVessel.radius(body.getBbWidth()) + .3;
            var p = hat.position().add(dir.scale(reach));
            var pos = BlockPos.containing(p.x, hat.getY() - .12, p.z);
            if (!level.hasChunkAt(pos) || !level.getFluidState(pos).is(FluidTags.WATER) || !level.getFluidState(pos.above()).isEmpty()) continue;
            var spot = new Vec3(p.x, HatBoatRoute.surface(level, pos).y - body.getBbHeight() * .3, p.z);
            if (level.noCollision(body, body.getBoundingBox().move(spot.subtract(body.position())))) return spot;
        }
        return null;
    }

    /** The id of the hull entry nearest a world point, or null: the entry a swimmer there would climb by. */
    public static String nearestEntryId(HatBoatEntity hat, Vec3 world) {
        var entry = CrewTransfer.nearestEntry(hat, world);
        return entry == null ? null : entry.id();
    }

    /**
     * Where a swimmer should go to climb by the entry nearest her that she has not been refused at: the water just outside that entry
     * (null when every entry is refused). The hull does not move.
     */
    public static Vec3 entryApproach(HatBoatEntity hat, YukkuriEntity body, WaterIncidents.Incident incident) {
        return entryApproach(hat, body, incident, null);
    }

    public static Vec3 entryApproach(HatBoatEntity hat, YukkuriEntity body, WaterIncidents.Incident incident, String selected) {
        var cls = hat.bodyOf(body).cls();
        var local = hat.toLocal(body.position());
        DeckLayout.Entry best = null;
        double cost = Double.MAX_VALUE;
        for (var entry : hat.deckLayout().entries()) {
            var anchor = entry.anchors().get(cls);
            if (anchor == null || incident != null && incident.refused(entry.id())) continue;
            if (selected != null && !selected.equals(hat.getUUID() + ":" + entry.id())) continue;
            double d = Math.hypot(anchor.outside().x - local.x, anchor.outside().z - local.z);
            if (d < cost) { cost = d; best = entry; }
        }
        if (best == null) return null;
        var outside = hat.toWorld(new Vec3(best.anchors().get(cls).outside().x, 0, best.anchors().get(cls).outside().z));
        return new Vec3(outside.x, body.getY(), outside.z);
    }

    /** A mother lifts a baby from her hull over to the father's. */
    public static Result liftAcross(YukkuriEntity body, YukkuriEntity carrier, HatBoatEntity from, HatBoatEntity to) {
        return open(new Request(Mode.LIFT, body, carrier, seat(from, body), anySeat(to), Purpose.RESCUE, false, false, DeckLayout.NONE, "share_with_father"));
    }

    /** A caretaker in her own hull takes a baby from an ownerless hull alongside. */
    public static Result liftRefuge(YukkuriEntity body, YukkuriEntity carrier, HatBoatEntity from, HatBoatEntity to) {
        return open(new Request(Mode.LIFT, body, carrier, seat(from, body), anySeat(to), Purpose.RESCUE, false, false, DeckLayout.NONE, "orphan_refuge"));
    }

    private static Result hop(YukkuriEntity body, HatBoatEntity from, HatBoatEntity to, Purpose purpose, boolean emergency, String why) {
        return open(new Request(Mode.HOP, body, null, seat(from, body), anySeat(to), purpose, emergency, false, DeckLayout.NONE, why));
    }
    /** An owner steps from the hull she rides onto the hat that is hers (a pupil onto its own new hat). */
    public static Result hopOwnHat(YukkuriEntity owner, HatBoatEntity from, HatBoatEntity own) { return hop(owner, from, own, Purpose.OWN, false, "own_hat"); }
    /** A pupil back from its first voyage steps onto the berth kept for it in the parent's hull. */
    public static Result hopReturn(YukkuriEntity pupil, HatBoatEntity from, HatBoatEntity home, boolean emergency) {
        return hop(pupil, from, home, Purpose.RETAINED, emergency, "return_to_parent");
    }
    /** A rider steps to a neighbouring hull (a refuge, a father's); emergency capacity is contested only by the real close brim. */
    public static Result hopGuest(YukkuriEntity body, HatBoatEntity from, HatBoatEntity to, boolean emergency) {
        return hop(body, from, to, Purpose.BOARD, emergency, "hull_to_hull");
    }

    /** A rider leaves the hull for ground (or, with a carrier, is set down by her braid). */
    public static Result land(YukkuriEntity body, YukkuriEntity carrier, HatBoatEntity hat, Vec3 spot) {
        return open(new Request(carrier == null ? Mode.HOP : Mode.LIFT, body, carrier, seat(hat, body), Anchor.ground(spot), Purpose.BOARD, false, false,
                DeckLayout.NONE, carrier == null ? "land_hop" : "land_lift"));
    }

    /** The captain moves a rider to another seat of the same hull; she ends on {@code carrierSlot} (or stays when NONE). */
    public static Result carryWithin(YukkuriEntity body, YukkuriEntity carrier, HatBoatEntity hat, int toSlot, int carrierSlot) {
        return open(new Request(Mode.LIFT, body, carrier, seat(hat, body), Anchor.berth(hat, toSlot), Purpose.SERVICE, false, false, carrierSlot, "carry_within"));
    }

    /** The hull centre at which the entry nearest a swimmer lies where a body of its size would float: the berth the helm brings the hull to. */
    public static Vec3 berthFor(HatBoatEntity hat, YukkuriEntity body) {
        var berths = rescueBerths(hat, body);
        return berths.isEmpty() ? null : berths.getFirst().center();
    }

    public record RescueBerth(String entry, Vec3 center) {}

    /** Alternative real entrances, nearest first. The incident keeps the selected entrance until it fails or completes. */
    public static List<RescueBerth> rescueBerths(HatBoatEntity hat, YukkuriEntity body) {
        var layout = hat.deckLayout();
        var cls = hat.bodyOf(body).cls();
        var berths = new ArrayList<RescueBerth>();
        for (var entry : layout.entries()) {
            var anchor = entry.anchors().get(cls);
            if (anchor == null) continue;
            var outside = anchor.outside();
            var offset = hat.toWorld(new Vec3(outside.x, 0, outside.z)).subtract(hat.position());
            var center = new Vec3(body.getX() - offset.x, hat.getY(), body.getZ() - offset.z);
            berths.add(new RescueBerth(hat.getUUID() + ":" + entry.id(), center));
        }
        berths.sort(Comparator.comparingDouble(berth -> berth.center().distanceToSqr(hat.position())));
        return berths;
    }

    /**
     * Before the hat is taken back its young leave it, side by side on the side clear of the adults (onto the bank beside a floated
     * hat): a capable child hops out, a dependent one is lifted out in {@code taker}'s braid, one transfer opened per call and the
     * hull's single MOVE serializing them. True while any young is aboard or a transfer is under way, so the caller keeps waiting.
     */
    public static boolean releaseYoung(HatBoatEntity hat, YukkuriEntity taker) {
        return releaseYoung(hat, taker, false);
    }

    /**
     * Opens at most one existing real departure from {@code hat}.  With {@code onlyOwnYoung},
     * the taker may lift only their live, non-adult recorded children; it neither grants custody
     * of the hat nor authority over its other riders.
     */
    public static boolean releaseYoung(HatBoatEntity hat, YukkuriEntity taker, boolean onlyOwnYoung) {
        if (hat.level().isClientSide || !(hat.level() instanceof ServerLevel level)) return false;
        if (onlyOwnYoung && !taker.isAlive()) return false;
        if (taker.family().crew().active() || hat.crew().busy()) return true;
        var young = new ArrayList<YukkuriEntity>();
        for (var rider : hat.getPassengers()) if (rider instanceof YukkuriEntity y && !y.getUUID().equals(hat.ownerYukkuri())
                && (!onlyOwnYoung || y.isAlive() && !y.family().adult() && Bonds.parentOf(taker, y))) young.add(y);
        if (young.isEmpty()) return false;
        // A pickup may follow a real crawl inside the hat. Never lift a rider off an unfinished walking route.
        if (young.stream().anyMatch(y -> hat.deck().moving(y.getUUID()))) return true;
        var others = new ArrayList<double[]>();
        for (var adult : level.getEntitiesOfClass(YukkuriEntity.class, hat.getBoundingBox().inflate(4), y -> y.family().adult()))
            others.add(new double[]{adult.getX(), adult.getZ(), adult.getBbWidth() * .5});
        if (others.stream().noneMatch(o -> o[0] == taker.getX() && o[1] == taker.getZ())) others.add(new double[]{taker.getX(), taker.getZ(), taker.getBbWidth() * .5});
        var bank = taker.position().subtract(hat.position());
        var spots = HatVessel.outSpots(hat, young.stream().mapToDouble(y -> HatVessel.radius(y.getBbWidth())).toArray(), others,
                hat.isMoored() && bank.horizontalDistanceSqr() > 1e-6 ? new Vec3(bank.x, 0, bank.z).normalize() : null);
        var froms = young.stream().map(YukkuriEntity::position).toList();
        var order = HatVessel.match(froms, spots);
        var turn = new ArrayList<Integer>();
        for (int i = 0; i < young.size(); i++) turn.add(i);
        turn.sort(Comparator.comparingDouble(i -> froms.get(i).distanceToSqr(spots.get(order[i]))));
        for (int i : turn) {
            var child = young.get(i);
            if (child.family().crew().active()) continue;
            var spot = standingGround(level, spots.get(order[i]), child);
            if (child.childhood().canJump()) {
                if (spot != null && land(child, null, hat, spot).ok()) return true;
                continue;
            }
            // A dependent child is set down within the braid's reach of the one taking it: the planned spot first, then a ring around her.
            var tries = new ArrayList<Vec3>();
            if (spot != null) tries.add(spot);
            double ring = BraidRescueGeometry.maximumReach(taker) * .7;
            for (int k = 0; k < 8; k++) {
                var around = standingGround(level, taker.position().add(Math.cos(k * Math.PI / 4) * ring, 0, Math.sin(k * Math.PI / 4) * ring), child);
                if (around != null) tries.add(around);
            }
            boolean unreachable = false;
            for (var at : tries) {
                var result = land(child, taker, hat, at);
                if (result.ok()) return true;
                if (result.refusal() == CrewTransfer.Refusal.INCAPABLE || result.refusal() == CrewTransfer.Refusal.TRANSIENT) break;
                unreachable |= result.reason().equals("no_reach") || result.reason().equals("known_impossible");
            }
            if (unreachable && approachPickup(hat, child, taker, tries)) return true;
        }
        return true;
    }

    /** An unreachable dependent rider crawls toward the waiting parent's side, along its existing step-free deck paths. */
    private static boolean approachPickup(HatBoatEntity hat, YukkuriEntity child, YukkuriEntity taker, List<Vec3> landings) {
        if (!hat.catches() || child.isPanicking() || child.family().crew().active()) return false;
        var placed = hat.deck().get(child.getUUID());
        if (placed == null || !placed.aboard() || placed.slot() < 0) return false;
        var body = hat.bodyOf(child);
        var seats = hat.deck().freeSeats(body, Purpose.BOARD, hat.toLocal(taker.position()));
        seats.sort(Comparator.comparingDouble(s -> hat.toWorld(hat.deckLayout().slot(s).pos()).distanceToSqr(taker.position())));
        for (int seat : seats) {
            if (seat == placed.slot()) continue;
            var path = hat.deckLayout().path(placed.slot(), seat, body.cls(), false, s -> hat.deck().occupant(s) != null);
            if (path == null) continue;
            boolean clear = true;
            for (int step : path) if (step != placed.slot() && !seats.contains(step)) { clear = false; break; }
            if (!clear) continue;
            for (var ground : landings) {
                if (!CrewTransfer.canLandFromSeat(child, taker, hat, seat, ground)) continue;
                int from = placed.slot();
                if (!hat.deck().moveWithin(body, seat, hat.toLocal(child.position()), false, Purpose.BOARD)) break;
                child.life().wakeInHat();
                hat.syncSeats();
                GameplayLog.event(child, "family", "catch pickup approach started hat={} carrier={} from={} to={} landing={}",
                        hat.getUUID(), taker.getUUID(), from, seat, ground);
                return true;
            }
        }
        GameplayLog.limited(child, "family", "catch.pickup_blocked", 200,
                "catch pickup approach refused hat={} carrier={} seat={} reason=no_walkable_reachable_seat", hat.getUUID(), taker.getUUID(), placed.slot());
        return false;
    }

    /** Standing room on the ground at a spot for this body, or null. */
    public static Vec3 standingGround(ServerLevel level, Vec3 spot, YukkuriEntity child) {
        var hit = level.clip(new ClipContext(spot.add(0, 1.2, 0), spot.add(0, -1.5, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, child));
        if (hit.getType() != HitResult.Type.BLOCK || !level.getFluidState(hit.getBlockPos()).isEmpty()) return null;
        var at = hit.getLocation();
        return level.noCollision(child, child.getBoundingBox().move(at.subtract(child.position()))) ? at : null;
    }

    private CrewTransfers() {}
}
