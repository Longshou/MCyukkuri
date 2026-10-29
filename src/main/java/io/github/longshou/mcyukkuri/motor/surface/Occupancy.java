package io.github.longshou.mcyukkuri.motor.surface;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.geom.OccupancyMath;
import net.minecraft.world.phys.Vec3;

/** Where a body's footprint is relative to a container (spec 7); "in the nest / in the hat" accepts only INSIDE_SUPPORTED. */
public enum Occupancy {
    INSIDE_SUPPORTED, ON_RIM, PARTIAL, OUTSIDE;

    /** Footprint radius per block of body width (the visible body is that wide: half of it each side), and a small margin (blocks). */
    public static final double FOOT=.5, MARGIN=.02;

    /** The one footprint definition: the visible body's half width plus the margin (blocks); room, landing, the walk grid and squeeze all use it. */
    public static double foot(double bbWidth) { return FOOT * bbWidth + MARGIN; }

    public static Occupancy of(YukkuriEntity body, Container c) {
        if (body == null || c == null) return OUTSIDE;
        return at(c, body.position(), body.getBbWidth(), c.supports(body));
    }

    /**
     * Where a body of width {@code bbWidth} would be with its feet at {@code feet}: the pure probe an airborne contact
     * evaluates before the body is moved. {@code supported} says whether something would carry it there.
     */
    public static Occupancy at(Container c, Vec3 feet, double bbWidth, boolean supported) {
        if (c == null || feet == null) return OUTSIDE;
        Vec3 p = c.toPlane(feet);
        return switch (OccupancyMath.classify(p.x, p.z, p.y, foot(bbWidth), c.floor(), c.floorY(), c.rimY(), supported)) {
            case INSIDE_SUPPORTED -> INSIDE_SUPPORTED;
            case ON_RIM -> ON_RIM;
            case PARTIAL -> PARTIAL;
            case OUTSIDE -> OUTSIDE;
        };
    }
}
