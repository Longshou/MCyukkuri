package io.github.longshou.mcyukkuri.human;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElementType;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * A single-template pool element that levels its site before the template is written: the footprint is cut free of terrain, trees and plants above the
 * template's ground layer and filled with dirt below it. The shops and factories are 41 blocks wide; ordinary terrain varies by many blocks over that
 * distance, so refusing every uneven site meant they almost never generated (measured in a normal world: every candidate was rejected on height).
 * Only the part of the footprint inside the chunk currently being written is touched, as with any structure piece.
 */
public final class LevelledPoolElement extends SinglePoolElement {
    public static final MapCodec<LevelledPoolElement> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            templateCodec(), processorsCodec(), projectionCodec(), overrideLiquidSettingsCodec()).apply(i, LevelledPoolElement::new));
    private static final int FILL_DEPTH = 24;

    private LevelledPoolElement(com.mojang.datafixers.util.Either<net.minecraft.resources.ResourceLocation, net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate> template,
            net.minecraft.core.Holder<net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList> processors,
            net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool.Projection projection,
            java.util.Optional<LiquidSettings> liquid) {
        super(template, processors, projection, liquid);
    }

    @Override public boolean place(StructureTemplateManager manager, WorldGenLevel level, StructureManager structures, ChunkGenerator generator, BlockPos pos,
            BlockPos pivot, Rotation rotation, BoundingBox chunkBox, RandomSource random, LiquidSettings liquid, boolean keepJigsaws) {
        level(manager, level, pos, rotation, chunkBox);
        return super.place(manager, level, structures, generator, pos, pivot, rotation, chunkBox, random, liquid, keepJigsaws);
    }

    private void level(StructureTemplateManager manager, WorldGenLevel level, BlockPos pos, Rotation rotation, BoundingBox chunkBox) {
        BoundingBox foot = getBoundingBox(manager, pos, rotation);
        int ground = pos.getY();
        var at = new BlockPos.MutableBlockPos();
        for (int x = Math.max(foot.minX(), chunkBox.minX()); x <= Math.min(foot.maxX(), chunkBox.maxX()); x++)
            for (int z = Math.max(foot.minZ(), chunkBox.minZ()); z <= Math.min(foot.maxZ(), chunkBox.maxZ()); z++) {
                for (int y = ground + 1; y <= foot.maxY() && y <= chunkBox.maxY(); y++) {
                    at.set(x, y, z);
                    if (!level.getBlockState(at).isAir()) level.setBlock(at, Blocks.AIR.defaultBlockState(), 2);
                }
                for (int y = ground - 1; y >= ground - FILL_DEPTH && y >= chunkBox.minY(); y--) {
                    at.set(x, y, z);
                    var state = level.getBlockState(at);
                    boolean soft = state.isAir() || !state.getFluidState().isEmpty() || state.is(BlockTags.REPLACEABLE) || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS);
                    if (!soft) break;
                    level.setBlock(at, Blocks.DIRT.defaultBlockState(), 2);
                }
            }
    }

    @Override public StructurePoolElementType<?> getType() { return HumanWorld.LEVELLED.get(); }
    @Override public String toString() { return "Levelled[" + template + "]"; }
}
