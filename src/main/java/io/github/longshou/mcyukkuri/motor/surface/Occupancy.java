package io.github.longshou.mcyukkuri.motor.surface;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.geom.OccupancyMath;
import net.minecraft.world.phys.Vec3;

/** Where a body's footprint is relative to a container (spec 7); "in the nest / in the hat" accepts only INSIDE_SUPPORTED. */
public enum Occupancy {
    INSIDE_SUPPORTED, ON_RIM, PARTIAL, OUTSIDE;

    /** Footprint radius per block of body width. */
    public static final double FOOT=.42;

    public static Occupancy of(YukkuriEntity body, Container c) {
        if (body == null || c == null) return OUTSIDE;
        Vec3 p = c.toPlane(body.position());
        return switch (OccupancyMath.classify(p.x, p.z, p.y, FOOT * body.getBbWidth(), c.floor(), c.floorY(), c.rimY(), c.supports(body))) {
            case INSIDE_SUPPORTED -> INSIDE_SUPPORTED;
            case ON_RIM -> ON_RIM;
            case PARTIAL -> PARTIAL;
            case OUTSIDE -> OUTSIDE;
        };
    }
}
