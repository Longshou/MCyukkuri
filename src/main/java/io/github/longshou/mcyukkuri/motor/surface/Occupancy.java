package io.github.longshou.mcyukkuri.motor.surface;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.geom.OccupancyMath;
import net.minecraft.world.phys.Vec3;

/** Where a body's footprint is relative to a container; "in the nest / in the hat" accepts only INSIDE_SUPPORTED. */
public enum Occupancy {
    INSIDE_SUPPORTED, ON_RIM, PARTIAL, OUTSIDE;

    /** Footprint radius per block of body width (the visible body is that wide: half of it each side), and a small margin (blocks). */
    public static final double FOOT=.5, MARGIN=.02;

    /** Broad-phase radius and walk-grid spacing; shaped hosts resolve support and collision with body-only columns. */
    public static double foot(double bbWidth) { return FOOT * bbWidth + MARGIN; }

    /** The middle of the hollow (world) of the host this body rides, while it lies on the brim or rim rather than inside: where a body that fell onto the brim tumbles toward. */
    public static Vec3 hollow(YukkuriEntity body) {
        if (!(Attach.of(body) instanceof AttachState.OnHost on)) return null;
        return of(body, on.host().container()) == INSIDE_SUPPORTED ? null : on.host().frame().toWorld(Vec3.ZERO);
    }

    /** The surface (world y) a host lays under a footprint of this body width at these feet, NaN when it lies off the host. */
    public static double surfaceUnder(SurfaceHost host, double bbWidth, Vec3 feet) {
        var local = host.frame().toLocal(feet);
        var top = host.support(local, foot(bbWidth));
        return top.isPresent() ? host.frame().toWorld(new Vec3(local.x, top.getAsDouble(), local.z)).y : Double.NaN;
    }

    public static Occupancy of(YukkuriEntity body, Container c) {
        if (body == null || c == null) return OUTSIDE;
        return at(c, body.position(), body.getBbWidth(), c.supports(body));
    }

    /** Where a body of width {@code bbWidth} with feet at {@code feet} would be: the pure probe an airborne contact evaluates before moving it. {@code supported}: it is held. */
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
