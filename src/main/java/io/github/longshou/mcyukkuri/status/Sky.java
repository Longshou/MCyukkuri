package io.github.longshou.mcyukkuri.status;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;

/** What actually falls on one block position right now. */
public enum Sky {
    CLEAR, RAIN, STORM, SNOW;

    public static Sky at(Level level, BlockPos pos) {
        if (!level.isRaining() || !level.hasChunkAt(pos) || !level.canSeeSky(pos)
                || level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY() > pos.getY()) return CLEAR;
        return switch (level.getBiome(pos).value().getPrecipitationAt(pos)) {
            case RAIN -> level.isThundering() ? STORM : RAIN;
            case SNOW -> SNOW;
            default -> CLEAR;
        };
    }
    public boolean wet() { return this == RAIN || this == STORM; }
}
