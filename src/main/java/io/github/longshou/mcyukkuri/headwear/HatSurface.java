package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.ContactResolver;
import io.github.longshou.mcyukkuri.motor.geom.GridPath;
import io.github.longshou.mcyukkuri.motor.surface.Floor;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.motor.surface.SeatTarget;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.Predicate;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

/** The model owns support, boarding and rider movement; the defaults are the land cradle. */
public interface HatSurface {
    HatSurface LAND = new HatSurface() {};
    default OptionalDouble support(PlacedHatEntity hat, Vec3 local, YukkuriEntity body) { return hat.shapedSupport(local, body); }
    default double contactHeight(PlacedHatEntity hat, YukkuriEntity body, Vec3 feet, List<AABB> envelope) {
        return ContactResolver.surface(hat, feet, envelope);
    }
    /** Room for the body's footprint turned to {@code yaw} (degrees, relative to the hat). */
    default boolean room(PlacedHatEntity hat, YukkuriEntity body, Vec3 local, double top, float yaw) { return hat.shapedRoom(body, local, top, yaw); }
    /** {@code hat.room(body, local)} for many places of one body within one search (its own height and turn): what does not change
     *  between the places is read once. */
    default Predicate<Vec3> roomAt(PlacedHatEntity hat, YukkuriEntity body) {
        float yaw = Floor.yaw(hat, body);
        double tall = body.getBbHeight() / hat.frame().unit();
        return local -> room(hat, body, local, local.y + tall, yaw);
    }
    default boolean stepRoom(PlacedHatEntity hat, YukkuriEntity body, Vec3 from, Vec3 to) { return hat.room(body, to); }
    default boolean bears(PlacedHatEntity hat, YukkuriEntity body) {
        return !hat.cooling(body) && !hat.isInWaterOrBubble() && (body.getUUID().equals(hat.ownerYukkuri()) ? !hat.catches() : hat.admits(body));
    }
    default boolean mountable(PlacedHatEntity hat, YukkuriEntity body) { return true; }
    default Vec3 boarded(PlacedHatEntity hat, YukkuriEntity body) { return hat.snapped(hat.toLocal(body.position()), body); }
    default void tickRiders(PlacedHatEntity hat) { hat.squeeze(); }
    default boolean settled(PlacedHatEntity hat, YukkuriEntity body) {
        return body.getVehicle() == hat && Occupancy.of(body, hat.container()) == Occupancy.INSIDE_SUPPORTED;
    }
    default List<SeatTarget> restSpots(PlacedHatEntity hat, YukkuriEntity body, int limit) { return List.of(); }
    /** The places a body comes to rest on the brim, in seat order: the one set every brim arrival (seat, hop leg or landing, carry put down) uses. */
    default List<Vec3> hopStops(PlacedHatEntity hat, YukkuriEntity body) { return List.of(); }
    default List<Vec3> hollowPlaces(PlacedHatEntity hat, FamilyData.BirthKind kind, FamilyData.Child child) { return hat.cavityPlaces(kind, child); }
    default ContactResolver.Contact contact(PlacedHatEntity hat, YukkuriEntity body, Vec3 spot, Vec3 feet) {
        return ContactResolver.landFree(body, hat, spot, feet);
    }
    default Vec3 landingSpot(PlacedHatEntity hat, YukkuriEntity body, Vec3 edge) {
        double reach = Math.hypot(edge.x, edge.z), foot = Occupancy.foot(body.getBbWidth());
        for (double r = 0; r <= reach - foot / hat.unit(); r += .5) {
            var at = new Vec3(edge.x / reach * r, 0, edge.z / reach * r);
            var height = hat.support(at, body);
            if (height.isPresent()) {
                at = new Vec3(at.x, height.getAsDouble(), at.z);
                if (hat.room(body, at)) return at;
            }
        }
        return null;
    }
    default List<double[]> walkRoute(PlacedHatEntity hat, YukkuriEntity body, Vec3 target, boolean aroundRiders) {
        var spot = hat.riders().local(body.getUUID());
        var grid = hat.walkGrid(body, aroundRiders);
        int[] from = grid.nearest(spot.x, spot.z), to = grid.nearest(target.x, target.z);
        return from == null || to == null ? List.of()
                : GridPath.find(grid, grid.centreX(from[0]), grid.centreZ(from[1]), grid.centreX(to[0]), grid.centreZ(to[1]),
                        BodyAbility.of(body).stepUp() / hat.unit(), 1);
    }
}
