package io.github.longshou.mcyukkuri.motor.surface;

import com.google.common.cache.CacheBuilder;
import io.github.longshou.mcyukkuri.motor.mixin.VoxelShapeAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Vanilla's merged collision boxes in grid indices. Read coordinates from the current shape. */
public final class CollisionBoxes {
    public record Box(int minX, int maxX, int maxY, int minZ, int maxZ) {}

    // A translated shape keeps the same grid, while a changed voxel layout has a different grid.
    private static final ConcurrentMap<DiscreteVoxelShape, List<Box>> BOXES =
            CacheBuilder.newBuilder().weakKeys().maximumSize(128).<DiscreteVoxelShape, List<Box>>build().asMap();

    public static List<Box> of(VoxelShape shape) {
        var grid = ((VoxelShapeAccess) shape).mcyukkuri$discreteShape();
        return BOXES.computeIfAbsent(grid, CollisionBoxes::merged);
    }

    private static List<Box> merged(DiscreteVoxelShape grid) {
        var boxes = new ArrayList<Box>();
        grid.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> boxes.add(new Box(minX, maxX, maxY, minZ, maxZ)), true);
        return List.copyOf(boxes);
    }

    private CollisionBoxes() {}
}
