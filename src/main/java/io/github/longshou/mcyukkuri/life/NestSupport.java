package io.github.longshou.mcyukkuri.life;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;

public final class NestSupport {
    private NestSupport() {}
    public static boolean below(LevelReader level, BlockPos pos) {
        var below=pos.below();
        var floor=level.getBlockState(below);
        return floor.isFaceSturdy(level,below,Direction.UP)
                || floor.is(BlockTags.LEAVES) && Block.isFaceFull(floor.getCollisionShape(level,below),Direction.UP);
    }
}
