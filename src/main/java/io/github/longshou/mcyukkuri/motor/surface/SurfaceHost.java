package io.github.longshou.mcyukkuri.motor.surface;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Something a body can stand on (spec 6). Today only {@code HatBoatEntity} implements it.
 */
public interface SurfaceHost {
    Entity entity();

    /** The host's pose for this tick: origin, hat unit, yaw and tilt. */
    HostFrame frame();

    /** Hat-surface height (hat units) at {@code local} for a foot radius {@code r} in blocks; empty when nothing supports it. */
    OptionalDouble support(Vec3 local, double r);

    /** The foot circle at {@code local} overlaps no other rider (nor, from WP4, stored provisions). */
    boolean room(YukkuriEntity body, Vec3 local);

    /** Brim sample points in world coordinates, tilt included (24 of them). */
    List<Vec3> rim();

    /** Named places on the host, as targets only. */
    Map<String, Vec3> spots();

    /** The hollow riders stand in; "in the hat" is OnHost of this host plus Occupancy INSIDE_SUPPORTED here. */
    Container container();

    /** UUID to local spot and yaw; the persisted and synchronized rider table. */
    Riders riders();

    /** False while the host shakes or is capsized; no jump landing may be planned onto it then. */
    boolean steady();

    /** True while the owner's running intent is Unload or taking the hat back; derived from the intent, never stored. */
    boolean unloadRequested();
}
