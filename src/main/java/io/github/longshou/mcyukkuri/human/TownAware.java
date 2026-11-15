package io.github.longshou.mcyukkuri.human;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;

public final class TownAware {
    private TownAware() {}

    /** Includes occupied village POIs and pieces such as civic buildings that have no POI. */
    public static boolean inTown(ServerLevel level, BlockPos pos) {
        return level.isCloseToVillage(pos, 2)
                || level.structureManager().getStructureWithPieceAt(pos, StructureTags.VILLAGE).isValid();
    }
}
