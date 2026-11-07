package io.github.longshou.mcyukkuri.status;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/** What actually falls on one block position right now. */
public enum Sky {
    CLEAR, RAIN, STORM, SNOW;

    public static Sky at(Level level, BlockPos pos) {
        if (!level.isRaining() || !level.hasChunkAt(pos) || !level.canSeeSky(pos)
                || level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY() > pos.getY()) return CLEAR;
        return open(level, pos);
    }
    /** What falls around this position in the open, whether or not a roof keeps it off this very block. */
    public static Sky open(Level level, BlockPos pos) {
        if (!level.isRaining() || !level.hasChunkAt(pos)) return CLEAR;
        return switch (level.getBiome(pos).value().getPrecipitationAt(pos)) {
            case RAIN -> level.isThundering() ? STORM : RAIN;
            case SNOW -> SNOW;
            default -> CLEAR;
        };
    }
    /** Rain or a storm falls around this position but a roof keeps it off. */
    public static boolean sheltered(Level level, BlockPos pos) { return open(level, pos).wet() && at(level, pos) == CLEAR; }
    public boolean wet() { return this == RAIN || this == STORM; }
}
