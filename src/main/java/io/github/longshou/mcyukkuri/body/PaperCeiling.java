package io.github.longshou.mcyukkuri.body;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Lowest underside of a solid overhang above the space covered by the paper. */
public final class PaperCeiling {
    public static double height(Entity body, Vec3 feet) {
        double reach = body.getBbWidth() * 1.5;
        var space = new AABB(feet.x - reach, feet.y + .001, feet.z - reach,
                feet.x + reach, feet.y + body.getBbHeight() + 2 * reach, feet.z + reach);
        double height = Double.POSITIVE_INFINITY;
        for (var shape : body.level().getBlockCollisions(body, space)) {
            for (var box : shape.toAabbs()) {
                if (box.minY > feet.y + .001 && box.intersects(space))
                    height = Math.min(height, box.minY - feet.y);
            }
        }
        return height;
    }

    private PaperCeiling() {}
}
