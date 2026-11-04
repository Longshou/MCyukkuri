package io.github.longshou.mcyukkuri.motor.surface;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.ContactResolver;
import io.github.longshou.mcyukkuri.motor.geom.BodyFootprint;
import io.github.longshou.mcyukkuri.motor.geom.FloorSearch;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.stream.Stream;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

/** Something a body can stand on. */
public interface SurfaceHost {
    /** Blocks per cell of the grid a body plans its walk on (in host units). */
    double GRID_CELL = FloorSearch.GRID_CELL;

    Entity entity();
    /** A carried body's real attachment or size changed, including bodies held by another rider. */
    default void loadChanged(YukkuriEntity body, String event) {}

    /** The host's pose for this tick: origin, hat unit, yaw and tilt. */
    HostFrame frame();
    /** Pose after coasting for this many ticks without another drive; external contacts can still change it. */
    default HostFrame frameAfter(int ticks) { return frame(); }
    /** Motor-owned tilt write; riders observe the resulting shared frame. */
    void pose(float roll, float pitch);

    /** Hat-surface height (hat units) at {@code local} for a foot radius {@code r} in blocks; empty when nothing supports it. */
    OptionalDouble support(Vec3 local, double r);

    /** Shaped support for a proposed footprint, including a turn not yet executed. */
    OptionalDouble support(Vec3 local, BodyFootprint body);
    /** Contact clearance in host units, shared by admission, turns and squeeze. */
    double roomGap();

    /** The body's own underside, where the host has shaped support. */
    default OptionalDouble support(Vec3 local, YukkuriEntity body) { return support(local, Occupancy.foot(body.getBbWidth())); }
    /** First flight contact uses the same support geometry as standing on this host. */
    default double contactHeight(YukkuriEntity body, Vec3 feet, List<AABB> envelope) {
        return ContactResolver.surface(this, feet, envelope);
    }

    /** The body's footprint at {@code local} overlaps no rider or stored object; shaped hosts compare body columns. */
    default boolean room(YukkuriEntity body, Vec3 local) {
        return body != null && local != null && room(body, local, local.y + body.getBbHeight() / frame().unit());
    }

    /** As {@link #room(YukkuriEntity, Vec3)} with the body reaching up to {@code top} (hat units): a body coming down needs the column it falls through free as well. */
    boolean room(YukkuriEntity body, Vec3 local, double top);

    /** It lets this body stand on it (ride it): a body it does not bear meets it only as an edge to glance off. */
    default boolean bears(YukkuriEntity body) { return true; }

    /** A carer putting this young down here: null while the host still carries it (or the carer ignores load), else the over-capacity detail
     *  ({@code load}, {@code capacity}). Only a weighed hull answers; a young that boards itself is not weighed this way. */
    default String loadRefusal(YukkuriEntity carer, YukkuriEntity child) { return null; }

    /** Brim sample points in world coordinates, tilt included (24 of them). */
    List<Vec3> rim();

    /** Named places on the host, as targets only. */
    Map<String, Vec3> spots();

    /** The hollow riders stand in; "in the hat" is OnHost of this host plus Occupancy INSIDE_SUPPORTED here. */
    Container container();

    /** UUID to local spot and yaw; the persisted and synchronized rider table. */
    Riders riders();

    boolean mountable(YukkuriEntity body);
    Vec3 landingSpot(YukkuriEntity body, Vec3 edge);
    ContactResolver.Contact contact(YukkuriEntity body, Vec3 spot, Vec3 feet);
    default List<SeatTarget> restSpots(YukkuriEntity body, int limit) { return List.of(); }
    /** Rest places in support order: cabin, head, then brim. */
    default List<SeatTarget> places(YukkuriEntity body, int limit) {
        return Stream.of(SeatTarget.Kind.values()).flatMap(kind -> places(body, kind, limit).stream()).limit(limit).toList();
    }
    default List<SeatTarget> places(YukkuriEntity body, SeatTarget.Kind kind, int limit) {
        return switch (kind) {
            case CABIN -> restSpots(body, limit);
            case HEAD -> List.of();
            case BRIM -> hopStops(body).stream().map(p -> new SeatTarget(this, p, kind)).filter(p -> p.available(body)).limit(limit).toList();
        };
    }
    default YukkuriEntity placeCarrier() { return null; }
    default SeatTarget restEntry(YukkuriEntity body) { return restSpot(body); }
    default float restFacing(YukkuriEntity body) { return body.bodyYaw(); }
    default OptionalDouble turnSupport(Vec3 at, YukkuriEntity body, float yaw) { return support(at, Floor.shape(this, body, yaw)); }
    /** A rest point on the floor inside the opening, excluding raised walls and the hop rim. */
    boolean insideFloor(Vec3 local);
    default SeatTarget restSpot(YukkuriEntity body) {
        var spots = places(body, 1);
        return spots.isEmpty() ? null : spots.getFirst();
    }
    default boolean settled(YukkuriEntity body) {
        return body.getVehicle() == entity() && Occupancy.of(body, container()) == Occupancy.INSIDE_SUPPORTED;
    }
    /** Supported intermediate destinations for a body's hops aboard this host, in local units. */
    default List<Vec3> hopStops(YukkuriEntity body) { return List.of(); }

    /** False while the host shakes; no jump landing may be planned onto it then. */
    boolean steady();

    /** True while a body near runs an intent on this host that asks its young off (unload it, take it back); derived, never stored. */
    boolean unloadRequested();

    /** True while a body near only attends its young (a catcher watching): crawlers gather at the brim on its side; derived. */
    default boolean attended() { return false; }

    /** This rider is kept out of the water by the host (a dry hull above the waterline): its water checks treat it as dry. */
    default boolean shelters(YukkuriEntity body) { return false; }

    /** Turned over: no surface and no container until righted. */
    default boolean capsized() { return false; }

    /** {@code by} holding the brim has turned it {@code progress} (0..1) back upright (Right); false when it cannot be righted. */
    default boolean right(YukkuriEntity by, double progress, String cause) { return false; }
    /** Release an interrupted grip at its actual pose. */
    default void stopRight(YukkuriEntity by) {}

    /** A rider's paddle stroke: the water velocity it drives and the yaw it turns (Paddle); false when it cannot be paddled. */
    default boolean thrust(YukkuriEntity by, Vec3 drive, float turn) { return false; }

    /** It keeps such an item where a rider can use it (a stick to paddle with). */
    default boolean holds(Item item) { return false; }

    /** Supported walking route, or empty when this surface offers no route. */
    List<double[]> walkRoute(YukkuriEntity walker, Vec3 target, boolean aroundRiders);
}
