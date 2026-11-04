package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.geom.FloorSearch;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import io.github.longshou.mcyukkuri.motor.geom.BodyFootprint;
import io.github.longshou.mcyukkuri.motor.geom.Disc;
import io.github.longshou.mcyukkuri.motor.geom.PlaceGeometry;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.motor.surface.Floor;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.motor.surface.SeatTarget;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** The round hull carries real footprints on a continuous floor; preferences never reserve positions. */
public final class HullSurface implements HatSurface {
    static final HatSurface INSTANCE = new HullSurface();
    @Override public boolean bears(PlacedHatEntity hat, YukkuriEntity body) {
        return hat.hasPassenger(body) || !hat.cooling(body) && (body.getUUID().equals(hat.ownerYukkuri())
                || hat.admits(body));
    }
    @Override public boolean mountable(PlacedHatEntity hat, YukkuriEntity body) {
        var local = hat.frame().toLocal(body.position());
        var height = support(hat, local, body);
        return hat.bears(body) && height.isPresent() && Math.abs(local.y - height.getAsDouble()) * hat.unit() <= .05
                && hat.room(body, local);
    }
    @Override public Vec3 boarded(PlacedHatEntity hat, YukkuriEntity body) { return hat.frame().toLocal(body.position()); }
    @Override public void tickRiders(PlacedHatEntity hat) { hat.squeeze(); Floor.settle(hat); }
    @Override public boolean settled(PlacedHatEntity hat, YukkuriEntity body) {
        if (hat.capsized()) return false;
        if (body.getVehicle() instanceof YukkuriEntity carrier && carrier == hat.placeCarrier()
                && ParentCarry.hold(carrier, body) == ParentCarry.Hold.HEAD)
            return ParentCarry.holdProblem(carrier, body, ParentCarry.Hold.HEAD, true) == null;
        if (body.getVehicle() != hat || !Floor.standing(body)) return false;
        var at = hat.riders().local(body.getUUID());
        if (body.getUUID().equals(hat.ownerYukkuri()))
            return at.multiply(1, 0, 1).distanceToSqr(Floor.point(HullBounds.HOME.scale(1 / HullBounds.UNIT)).multiply(1, 0, 1))
                    * hat.unit() * hat.unit() <= .0025 && Math.abs(hat.riders().yaw(body.getUUID())) <= 3 && hat.insideFloor(at);
        var support = hat.support(at, body);
        return support.isPresent() && Math.abs(at.y - support.getAsDouble()) <= Occupancy.MARGIN / hat.unit()
                && hat.room(body, at);
    }
    private static Disc hatDisc(PlacedHatEntity hat, YukkuriEntity body, Vec3 at) {
        double bottom = HatGeometry.anchor().y * body.growthScale() * body.getScale() / 16 / hat.unit();
        return new Disc(at.x, at.z, ParentCarry.headwearRadius(body) / hat.unit(), at.y + bottom,
                at.y + bottom + .3 * body.growthScale() * body.getScale() / 16 / hat.unit());
    }
    @Override public boolean room(PlacedHatEntity hat, YukkuriEntity body, Vec3 local, double top) {
        if (!hat.shapedRoom(body, local, top)) return false;
        if (body.getUUID().equals(hat.ownerYukkuri()) || !hat.insideFloor(local)) return true;
        var captain = hat.owner();
        var envelope = new ArrayList<FloorSearch.Placed>();
        if (captain != null) {
            var at = captain.getVehicle() == hat ? Floor.vec(hat.riders().local(captain.getUUID())) : HullBounds.HOME.scale(1 / HullBounds.UNIT);
            float yaw = captain.getVehicle() == hat ? hat.riders().yaw(captain.getUUID()) : 0;
            for (float turn : new float[]{-15, 0, 15, yaw}) envelope.add(new FloorSearch.Placed(BodyFootprint.envelope(captain.assetId(),
                    captain.getBbWidth() / hat.unit(), captain.getBbHeight() / hat.unit(), Math.toRadians(turn)), at));
        }
        var others = hat.getPassengers().stream().filter(p -> p != body && p != captain).map(p -> (YukkuriEntity) p)
                .map(p -> hatDisc(hat, p, hat.riders().local(p.getUUID()))).toList();
        var disc = hatDisc(hat, body, local);
        return PlaceGeometry.cabin(disc, HullBounds.innerRadius(disc.bottom() * HullBounds.UNIT) / HullBounds.UNIT,
                envelope, others, .3 / HullBounds.SCALE);
    }
    @Override public List<Vec3> hopStops(PlacedHatEntity hat, YukkuriEntity body) {
        return HullBounds.brimStops(body.assetId(), body.getBbWidth(), body.getBbHeight(),
                hat.hullScale(), Floor.shape(hat, body).yaw()).stream().map(Floor::point).toList();
    }
    public static List<Vec3> hopStops(YukkuriEntity body, float hullScale) {
        return HullBounds.brimStops(body.assetId(), body.getBbWidth(), body.getBbHeight(), hullScale, 0)
                .stream().map(Floor::point).toList();
    }
    @Override public Vec3 landingSpot(PlacedHatEntity hat, YukkuriEntity body, Vec3 edge) {
        return hopStops(hat, body).stream().filter(p -> new SeatTarget(hat, p).available(body))
                .min(Comparator.comparingDouble(p -> p.distanceToSqr(edge))).orElse(null);
    }
    @Override public List<SeatTarget> restSpots(PlacedHatEntity hat, YukkuriEntity body, int limit) {
        if (!hat.bears(body)) return none(hat, body, "admission");
        if (body.getUUID().equals(hat.ownerYukkuri())) {
            var home = Floor.point(HullBounds.HOME.scale(1 / HullBounds.UNIT));
            var height = hat.support(home, Floor.shape(hat, body, 0));
            if (height.isEmpty()) return none(hat, body, "captain_support");
            var target = new SeatTarget(hat, new Vec3(home.x, height.getAsDouble(), home.z), SeatTarget.Kind.CABIN);
            return target.available(body) ? List.of(target) : none(hat, body, "captain_room");
        }
        var out = new ArrayList<SeatTarget>();
        var shape = Floor.shape(hat, body);
        var anchor = HullBounds.HOME.scale(1 / HullBounds.UNIT);
        var captain = hat.owner();
        if (!body.getUUID().equals(hat.ownerYukkuri()) && captain != null) {
            boolean aboard = hat.settled(captain);
            if (aboard) anchor = Floor.vec(hat.riders().local(captain.getUUID()));
            float yaw = aboard ? hat.riders().yaw(captain.getUUID()) : 0;
            double angle = Math.toRadians(yaw), x = Math.sin(angle), z = -Math.cos(angle);
            double reach = FloorSearch.extent(Floor.shape(hat, captain, yaw), x, z)
                    + FloorSearch.extent(shape, -x, -z) + hat.roomGap();
            anchor = anchor.add(new Vec(x * reach, 0, z * reach));
        }
        double range = HullBounds.MOUTH / HullBounds.UNIT + anchor.hlen();
        var candidates = FloorSearch.spots((x,z,fp) -> {
                    if (!hat.profile().inOpening(x,z)) return Double.NaN;
                    double y = hat.profile().support(x,z,fp);
                    var at = new Vec3(x, y, z);
                    return hat.insideFloor(at) && hat.room(body, at) ? y : Double.NaN;
                },
                hat.profile().floor(), Occupancy.MARGIN / hat.unit(), shape, Floor.others(hat, body), anchor,
                range, true, hat.roomGap(), limit);
        for (var p : candidates) {
            var target = new SeatTarget(hat, Floor.point(p));
            if (!out.contains(target)) out.add(target);
            if (out.size() == limit) return out;
        }
        return out.isEmpty() ? none(hat, body, "room") : out;
    }
    private static List<SeatTarget> none(PlacedHatEntity hat, YukkuriEntity body, String reason) {
        double capacity = HullBounds.capacity(hat.hullScale() / HullBounds.SCALE);
        GameplayLog.limited(hat, "hat", "rest_spot." + body.getUUID(), 1200,
                "rest spot none body={} reason={} load={} capacity={}", body.getUUID(), reason, hat.loadRatio() * capacity, capacity);
        return List.of();
    }
}
