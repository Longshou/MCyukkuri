package io.github.longshou.mcyukkuri.coordination;

import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Explicit dimension and horizontal cell; spherical proximity remains a separate exact query. */
public record RegionKey(ResourceKey<Level> dimension, int x, int z) {
    public static RegionKey cell(GlobalPos position, int size) {
        if (size <= 0) throw new IllegalArgumentException("Non-positive cell size");
        return new RegionKey(position.dimension(), Math.floorDiv(position.pos().getX(), size), Math.floorDiv(position.pos().getZ(), size));
    }

    public static boolean within(GlobalPos a, GlobalPos b, double radius) {
        return a.dimension().equals(b.dimension()) && a.pos().distSqr(b.pos()) < radius * radius;
    }
}
