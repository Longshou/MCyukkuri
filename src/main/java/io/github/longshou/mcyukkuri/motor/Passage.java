package io.github.longshou.mcyukkuri.motor;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** The common physical sidestep for bodies giving another walker room. */
public final class Passage {
    public static final double MARGIN = .2;

    private Passage() {}

    public static double halfWidth(Entity body, Vec3 axis) {
        var box = body.getBoundingBox();
        return (box.getXsize() * Math.abs(axis.x) + box.getZsize() * Math.abs(axis.z)) * .5;
    }

    public static Vec3 aside(Vec3 start, Vec3 offset, Vec3 side, double width, double margin, Predicate<Vec3> clear) {
        for (var direction : List.of(side, side.reverse())) {
            var closest = start.add(direction.scale(width + MARGIN - offset.dot(direction)));
            if (!clear.test(closest)) continue;
            var roomy = closest.add(direction.scale(margin - MARGIN));
            return clear.test(roomy) ? roomy : closest;
        }
        return null;
    }
}
