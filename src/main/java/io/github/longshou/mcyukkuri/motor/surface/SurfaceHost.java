package io.github.longshou.mcyukkuri.motor.surface;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.geom.HeightGrid;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

/** Something a body can stand on. */
public interface SurfaceHost {
    /** Blocks per cell of the grid a body plans its walk on (in host units). */
    double GRID_CELL = .25;

    Entity entity();

    /** The host's pose for this tick: origin, hat unit, yaw and tilt. */
    HostFrame frame();

    /** Hat-surface height (hat units) at {@code local} for a foot radius {@code r} in blocks; empty when nothing supports it. */
    OptionalDouble support(Vec3 local, double r);

    /** The foot circle at {@code local} overlaps no other rider nor anything the host holds on its floor at the same height: footprints conflict only where their heights overlap. */
    default boolean room(YukkuriEntity body, Vec3 local) {
        return body != null && local != null && room(body, local, local.y + body.getBbHeight() / frame().unit());
    }

    /** As {@link #room(YukkuriEntity, Vec3)} with the body reaching up to {@code top} (hat units): a body coming down needs the column it falls through free as well. */
    boolean room(YukkuriEntity body, Vec3 local, double top);

    /** It lets this body stand on it (ride it): a body it does not bear meets it only as an edge to glance off. */
    default boolean bears(YukkuriEntity body) { return true; }

    /** Brim sample points in world coordinates, tilt included (24 of them). */
    List<Vec3> rim();

    /** Named places on the host, as targets only. */
    Map<String, Vec3> spots();

    /** The hollow riders stand in; "in the hat" is OnHost of this host plus Occupancy INSIDE_SUPPORTED here. */
    Container container();

    /** UUID to local spot and yaw; the persisted and synchronized rider table. */
    Riders riders();

    /** False while the host shakes; no jump landing may be planned onto it then. */
    boolean steady();

    /** True while a body near runs an intent on this host (unload it, take it back, attend its young); derived, never stored. */
    boolean unloadRequested();

    /** This rider is kept out of the water by the host (a dry hull above the waterline): its water checks treat it as dry. */
    default boolean shelters(YukkuriEntity body) { return false; }

    /** What the host carries besides its riders, in the load units of the ability factor L: its riders' pace. */
    default double load() { return 0; }

    /** Turned over: no surface and no container until righted. */
    default boolean capsized() { return false; }

    /** {@code by} holding the brim has turned it {@code progress} (0..1) back upright (Right); false when it cannot be righted. */
    default boolean right(YukkuriEntity by, double progress, String cause) { return false; }

    /** A rider's paddle stroke: the water velocity it drives and the yaw it turns (Paddle); false when it cannot be paddled. */
    default boolean thrust(YukkuriEntity by, Vec3 drive, float turn) { return false; }

    /** It keeps such an item where a rider can use it (a stick to paddle with). */
    default boolean holds(Item item) { return false; }

    /** The height field {@code walker} plans a walk on: cells of at most {@link #GRID_CELL} hat units and no wider than its foot, unsupported where a store or a rider is in the way. */
    HeightGrid walkGrid(YukkuriEntity walker, boolean aroundRiders);
}
