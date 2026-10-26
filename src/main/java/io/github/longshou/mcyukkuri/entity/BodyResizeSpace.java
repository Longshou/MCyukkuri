package io.github.longshou.mcyukkuri.entity;

import java.util.Optional;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;

/** Vanilla's small resize adjustment, subtracting obstacles only inside the candidate centre volume. */
final class BodyResizeSpace {
    private BodyResizeSpace() {}

    static boolean adjust(Entity entity, EntityDimensions previous) {
        var dimensions = entity.getDimensions(entity.getPose());
        Vec3 centre = entity.position().add(0, previous.height() / 2.0, 0);
        double growWidth = Math.max(0, dimensions.width() - previous.width()) + 1.0E-6;
        double growHeight = Math.max(0, dimensions.height() - previous.height()) + 1.0E-6;
        var target = find(entity, AABB.ofSize(centre, growWidth, growHeight, growWidth), centre,
                dimensions.width(), dimensions.height());
        if (target.isPresent()) {
            entity.setPos(target.get().add(0, -dimensions.height() / 2.0, 0));
            return true;
        }
        // Retain vanilla's second attempt with the previous height, including its feet offset.
        if (dimensions.width() > previous.width() && dimensions.height() > previous.height()) {
            target = find(entity, AABB.ofSize(centre, growWidth, 1.0E-6, growWidth), centre,
                    dimensions.width(), previous.height());
            if (target.isPresent()) {
                entity.setPos(target.get().add(0, -previous.height() / 2.0 + 1.0E-6, 0));
                return true;
            }
        }
        return false;
    }

    private static Optional<Vec3> find(Entity entity, AABB search, Vec3 centre, double width, double height) {
        var level = entity.level();
        var free = Shapes.create(search);
        var border = level.getWorldBorder();
        for (var shape : level.getBlockCollisions(entity, search.inflate(width, height, width))) {
            if (border != null && !border.isWithinBounds(shape.bounds())) continue;
            for (var box : shape.toAabbs()) {
                var obstacle = box.inflate(width / 2.0, height / 2.0, width / 2.0);
                if (!search.intersects(obstacle)) continue;
                // A \ (B union C) = (A \ B) \ C. Clipping before subtraction avoids building the
                // huge expanded union of a detailed nest; a fully blocked volume exits immediately.
                free = Shapes.joinUnoptimized(free, Shapes.create(search.intersect(obstacle)), BooleanOp.ONLY_FIRST);
                if (free.isEmpty()) return Optional.empty();
            }
        }
        return free.closestPointTo(centre);
    }
}
