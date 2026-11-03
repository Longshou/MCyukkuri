package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.geom.FloorSearch;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
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
                || hat.admits(body) && hat.youngAboard() < 2
                && hat.loadRatio() + HullBounds.weight(body.growthScale() * body.getScale())
                    / HullBounds.capacity(hat.hullScale() / HullBounds.SCALE) <= 1);
    }
    @Override public boolean mountable(PlacedHatEntity hat, YukkuriEntity body) {
        var local = hat.frame().toLocal(body.position());
        var height = support(hat, local, body);
        return hat.bears(body) && height.isPresent() && Math.abs(local.y - height.getAsDouble()) * hat.unit() <= .05
                && hat.room(body, local);
    }
    @Override public Vec3 boarded(PlacedHatEntity hat, YukkuriEntity body) { return hat.frame().toLocal(body.position()); }
    @Override public void tickRiders(PlacedHatEntity hat) { hat.squeeze(); Floor.settle(hat); }
    @Override public List<Vec3> hopStops(PlacedHatEntity hat, YukkuriEntity body) {
        return HullBounds.brimStops(body.assetId(), body.getBbWidth(), body.getBbHeight(),
                hat.hullScale(), Floor.shape(hat, body).yaw()).stream().map(Floor::point).toList();
    }
    public static List<Vec3> hopStops(YukkuriEntity body, float hullScale) {
        return HullBounds.brimStops(body.assetId(), body.getBbWidth(), body.getBbHeight(), hullScale, 0)
                .stream().map(Floor::point).toList();
    }
    @Override public List<double[]> walkRoute(PlacedHatEntity hat, YukkuriEntity body, Vec3 target, boolean aroundRiders) { return List.of(); }
    @Override public Vec3 landingSpot(PlacedHatEntity hat, YukkuriEntity body, Vec3 edge) {
        return hopStops(hat, body).stream().filter(p -> new SeatTarget(hat, p).available(body))
                .min(Comparator.comparingDouble(p -> p.distanceToSqr(edge))).orElse(null);
    }
    @Override public List<SeatTarget> restSpots(PlacedHatEntity hat, YukkuriEntity body, int limit) {
        if (!hat.bears(body)) return none(hat, body, "load");
        var out = new ArrayList<SeatTarget>();
        if (hat.settled(body)) {
            var current = new SeatTarget(hat, hat.riders().local(body.getUUID()));
            if (current.available(body)) out.add(current);
            if (out.size() == limit) return out;
        }
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
        var candidates = FloorSearch.spots((x,z,fp) -> hat.profile().inOpening(x,z)
                        ? hat.profile().support(x,z,fp) : Double.NaN,
                hat.profile().floor(), Occupancy.MARGIN / hat.unit(), shape, Floor.others(hat, body), anchor,
                range, true, hat.roomGap(), limit);
        for (var p : candidates) {
            var target = new SeatTarget(hat, Floor.point(p));
            if (!out.contains(target)) out.add(target);
            if (out.size() == limit) return out;
        }
        for (var p : hopStops(hat, body)) {
            var target = new SeatTarget(hat, p);
            if (target.available(body)) out.add(target);
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
