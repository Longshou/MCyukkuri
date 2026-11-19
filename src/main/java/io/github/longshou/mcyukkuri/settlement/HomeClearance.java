package io.github.longshou.mcyukkuri.settlement;

import java.util.List;

/** Placement can take a required cell or add collision inside the space a resident needs. */
final class HomeClearance {
    static <T> boolean takes(List<T> footprint, T cell, T support) {
        return footprint.contains(cell) || footprint.contains(support);
    }

    record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        boolean intersects(Box other) {
            return minX < other.maxX && maxX > other.minX && minY < other.maxY && maxY > other.minY
                    && minZ < other.maxZ && maxZ > other.minZ;
        }
    }

    static boolean blocks(Iterable<Box> shell, Box access) {
        for (var piece : shell) if (piece.intersects(access)) return true;
        return false;
    }

    private HomeClearance() {}
}
