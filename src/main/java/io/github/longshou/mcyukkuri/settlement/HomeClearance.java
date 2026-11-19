package io.github.longshou.mcyukkuri.settlement;

/** Placement can take a required cell or add collision inside the space a resident needs. */
final class HomeClearance {
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
