package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.GrowthSize;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import io.github.longshou.mcyukkuri.motor.ContactResolver;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.surface.SeatTarget;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.List;
import java.util.OptionalDouble;
import net.minecraft.world.phys.Vec3;

/** The bounds-defined round surface: fixed seats, with no walking or repacking. */
final class SeatSurface implements HatSurface {
    static final HatSurface INSTANCE = new SeatSurface();
    @Override public OptionalDouble support(PlacedHatEntity hat, Vec3 local, YukkuriEntity body) {
        var brim = HullBounds.brim(body.growthScale() * body.getScale() * HullBounds.SCALE / hat.hullScale(), Math.atan2(local.z, local.x));
        if (Math.abs(local.horizontalDistance() * HullBounds.UNIT - Math.hypot(brim.x(), brim.z())) < .05)
            return OptionalDouble.of(brim.y() / HullBounds.UNIT);
        var seat = hat.seatDestination(body);
        return seat != null && local.subtract(seat.local()).horizontalDistance() * hat.unit() < .05
                ? OptionalDouble.of(seat.local().y) : OptionalDouble.empty();
    }
    @Override public boolean room(PlacedHatEntity hat, YukkuriEntity body, Vec3 local, double top) {
        var seat = hat.seatDestination(body);
        // HatSeats already admits fixed seats against the captain and other riders; shapedRoom is for a brim hop.
        if (seat != null && local.subtract(seat.local()).horizontalDistance() * hat.unit() < .05) return true;
        return hat.shapedRoom(body, local, top);
    }
    @Override public boolean bears(PlacedHatEntity hat, YukkuriEntity body) {
        return hat.hasPassenger(body) || (body.getUUID().equals(hat.ownerYukkuri()) || hat.admits(body)) && hat.seatDestination(body) != null;
    }
    @Override public boolean mountable(PlacedHatEntity hat, YukkuriEntity body) {
        var local = hat.frame().toLocal(body.position());
        var height = support(hat, local, body);
        return hat.bears(body) && height.isPresent() && Math.abs(local.y - height.getAsDouble()) * hat.unit() <= .05
                && hat.room(body, local);
    }
    @Override public Vec3 boarded(PlacedHatEntity hat, YukkuriEntity body) { return hat.frame().toLocal(body.position()); }
    @Override public void tickRiders(PlacedHatEntity hat) {}
    @Override public List<Vec3> hopStops(PlacedHatEntity hat, YukkuriEntity body) {
        double growth = body.growthScale() * body.getScale() * HullBounds.SCALE / hat.hullScale();
        return HullBounds.brimStops(growth).stream()
                .map(p -> new Vec3(p.x(), p.y(), p.z()).scale(1 / HullBounds.UNIT)).toList();
    }
    @Override public List<double[]> walkRoute(PlacedHatEntity hat, YukkuriEntity body, Vec3 target, boolean aroundRiders) { return List.of(); }
    @Override public Vec3 landingSpot(PlacedHatEntity hat, YukkuriEntity body, Vec3 edge) {
        return hopStops(hat, body).stream().filter(p -> new SeatTarget(hat, p).available(body))
                .min(java.util.Comparator.comparingDouble(p -> p.distanceToSqr(edge))).orElse(null);
    }
    @Override public List<Vec3> hollowPlaces(PlacedHatEntity hat, FamilyData.BirthKind kind, FamilyData.Child child) {
        var size = LiveBirthGeometry.unbornDimensions(kind, child);
        var seat = hat.seatDestination(new HatSeats.Body(SubspeciesLife.assetId(child.variant, child.subspeciesFlags),
                GrowthSize.physicalBirth(kind, child), size.width(), size.height()));
        return seat == null ? List.of() : List.of(seat.world());
    }
    @Override public ContactResolver.Contact contact(PlacedHatEntity hat, YukkuriEntity body, Vec3 spot, Vec3 feet) {
        var seat = new SeatTarget(hat, spot);
        if (!seat.available(body)) return new ContactResolver.Contact(hat, spot, true);
        Motor.move(body, seat.world().subtract(body.position()), "seat_contact");
        return new ContactResolver.Contact(hat, seat.local(), !seat.land(body, "seat_contact"));
    }
}
