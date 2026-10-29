package io.github.longshou.mcyukkuri.motor.surface;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.geom.HeightGrid;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Something a body can stand on (spec 6).
 */
public interface SurfaceHost {
    /** Blocks per cell of the grid a body plans its walk on (spec 6: 0.25 block, in host units). */
    double GRID_CELL = .25;

    Entity entity();

    /** The host's pose for this tick: origin, hat unit, yaw and tilt. */
    HostFrame frame();

    /** Hat-surface height (hat units) at {@code local} for a foot radius {@code r} in blocks; empty when nothing supports it. */
    OptionalDouble support(Vec3 local, double r);

    /** The foot circle at {@code local} overlaps no other rider nor anything the host holds on its floor (stores). */
    boolean room(YukkuriEntity body, Vec3 local);

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

    /** True while the owner's running intent is Unload or taking the hat back; derived from the intent, never stored. */
    boolean unloadRequested();

    /** This rider is kept out of the water by the host (a dry hull above the waterline): its water checks treat it as dry. */
    default boolean shelters(YukkuriEntity body) { return false; }

    /** What the host carries besides its riders, in the load units of the ability factor L (spec 8.1): its riders' pace. */
    default double load() { return 0; }

    /** The height field a body of foot radius {@code r} (blocks) walks on: {@link #GRID_CELL} cells in host units, heights from
     *  {@link #support}, cells where that foot would overlap what the floor holds unsupported. Kept until the floor changes. */
    HeightGrid walkGrid(double r);
}
