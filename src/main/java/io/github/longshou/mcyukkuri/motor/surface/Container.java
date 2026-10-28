package io.github.longshou.mcyukkuri.motor.surface;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.geom.FloorShape;
import net.minecraft.world.phys.Vec3;

/**
 * A hollow a body can be inside (spec 7): nest beds, the catch hat and the hat boat. Plane coordinates are blocks,
 * centred on the container; heights are in the same plane frame (world heights for an unrotated container).
 */
public interface Container {
    /** Inner bottom outline in plane coordinates. */
    FloorShape floor();
    double floorY();
    double rimY();
    /** World point to plane coordinates (x, height, z). */
    Vec3 toPlane(Vec3 world);
    /** Whether something solid actually carries the body's feet (ground contact, or the host surface for a rider). */
    boolean supports(YukkuriEntity body);
}
