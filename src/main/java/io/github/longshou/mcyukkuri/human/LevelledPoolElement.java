package io.github.longshou.mcyukkuri.human;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.longshou.mcyukkuri.human.mixin.SinglePoolElementAccessor;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElementType;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * A single-template pool element that levels its site before the template is written: the footprint is cut free of terrain, trees and plants above the
 * template's ground layer and filled with dirt below it. The shops and factories are 41 blocks wide; ordinary terrain varies by many blocks over that
 * distance, so refusing every uneven site meant they almost never generated (measured in a normal world: every candidate was rejected on height).
 * Only the part of the footprint inside the chunk currently being written is touched, as with any structure piece.
 */
public final class LevelledPoolElement extends SinglePoolElement {
    public static final MapCodec<LevelledPoolElement> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            templateCodec(), processorsCodec(), projectionCodec(), overrideLiquidSettingsCodec(),
            Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("max_count").forGetter(e -> e.maxCount),
            Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("max_cut").forGetter(e -> e.maxCut),
            Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("max_fill").forGetter(e -> e.maxFill),
            BlockState.CODEC.optionalFieldOf("fill").forGetter(e -> e.fill),
            BlockState.CODEC.optionalFieldOf("edge").forGetter(e -> e.edge)).apply(i, LevelledPoolElement::new));
    private static final int FILL_DEPTH = 24;
    private static final int TOWN_LOT_MAX_CUT = 8;

    private final Optional<Integer> maxCount;
    private final Optional<Integer> maxCut;
    private final Optional<Integer> maxFill;
    private final Optional<BlockState> fill;
    private final Optional<BlockState> edge;

    private LevelledPoolElement(Either<ResourceLocation, StructureTemplate> template,
            Holder<StructureProcessorList> processors,
            StructureTemplatePool.Projection projection,
            Optional<LiquidSettings> liquid,
            Optional<Integer> maxCount, Optional<Integer> maxCut, Optional<Integer> maxFill,
            Optional<BlockState> fill, Optional<BlockState> edge) {
        super(template, processors, projection, liquid);
        this.maxCount = maxCount;
        this.maxCut = maxCut;
        this.maxFill = maxFill;
        this.fill = fill;
        this.edge = edge;
    }

    @Override public boolean place(StructureTemplateManager manager, WorldGenLevel level, StructureManager structures, ChunkGenerator generator, BlockPos pos,
            BlockPos pivot, Rotation rotation, BoundingBox chunkBox, RandomSource random, LiquidSettings liquid, boolean keepJigsaws) {
        // The template manager resolves removed IDs to zero-size templates. Their bounding boxes must never be levelled.
        if (getSize(manager, rotation).getY() == 0) return true;
        level(manager, level, pos, rotation, chunkBox);
        return super.place(manager, level, structures, generator, pos, pivot, rotation, chunkBox, random, liquid, keepJigsaws);
    }

    public boolean reservesColumns() {
        // Legacy elements had no town fields and already reserved columns. Authored town lots only use max_fill.
        return maxCut.isPresent() || (!hasPlacementLimits() && fill.isEmpty() && edge.isEmpty());
    }

    public boolean hasPlacementLimits() {
        return maxCount.isPresent() || maxCut.isPresent() || maxFill.isPresent();
    }

    public boolean isAtLimit(List<PoolElementStructurePiece> pieces) {
        if (maxCount.isEmpty()) return false;
        int remaining = maxCount.get();
        for (var piece : pieces) {
            if (piece.getElement() instanceof SinglePoolElement single
                    && template.equals(((SinglePoolElementAccessor) single).mcyukkuri$template()) && --remaining == 0) return true;
        }
        return false;
    }

    public boolean fitsTerrain(ChunkGenerator generator, LevelHeightAccessor height, RandomState random, BoundingBox box) {
        if (maxCut.isEmpty() && maxFill.isEmpty()) return true;
        int cut = maxCut.orElse(TOWN_LOT_MAX_CUT);
        int fill = maxFill.orElse(Integer.MAX_VALUE);
        int centerX = box.minX() + (box.maxX() - box.minX()) / 2;
        int centerZ = box.minZ() + (box.maxZ() - box.minZ()) / 2;
        int floor = box.minY();
        if (!fitsTerrainAt(generator, height, random, centerX, centerZ, floor, cut, fill)) return false;
        return maxCut.isEmpty() || (fitsTerrainAt(generator, height, random, box.minX(), box.minZ(), floor, cut, fill)
                && fitsTerrainAt(generator, height, random, box.maxX(), box.minZ(), floor, cut, fill)
                && fitsTerrainAt(generator, height, random, box.minX(), box.maxZ(), floor, cut, fill)
                && fitsTerrainAt(generator, height, random, box.maxX(), box.maxZ(), floor, cut, fill));
    }

    private static boolean fitsTerrainAt(ChunkGenerator generator, LevelHeightAccessor height, RandomState random,
            int x, int z, int floor, int cut, int fill) {
        int surface = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, height, random);
        int delta = surface - 1 - floor;
        if (surface == height.getMinBuildHeight() || delta > cut || delta < -fill) return false;
        if (generator instanceof NoiseBasedChunkGenerator noise) {
            var settings = noise.generatorSettings().value();
            // For solid base terrain and non-solid fluid, the ocean floor height detects a wet surface without a column allocation.
            if (settings.defaultBlock().blocksMotion() && settings.defaultBlock().getFluidState().isEmpty()
                    && !settings.defaultFluid().blocksMotion()) {
                return surface == generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, height, random);
            }
        }
        return generator.getBaseColumn(x, z, height, random).getBlock(surface - 1).getFluidState().isEmpty();
    }

    private void level(StructureTemplateManager manager, WorldGenLevel level, BlockPos pos, Rotation rotation, BoundingBox chunkBox) {
        BoundingBox foot = getBoundingBox(manager, pos, rotation);
        int ground = pos.getY();
        BlockState fillBlock = fill.orElse(Blocks.DIRT.defaultBlockState());
        BlockState edgeBlock = edge.orElse(fillBlock);
        var at = new BlockPos.MutableBlockPos();
        for (int x = Math.max(foot.minX(), chunkBox.minX()); x <= Math.min(foot.maxX(), chunkBox.maxX()); x++)
            for (int z = Math.max(foot.minZ(), chunkBox.minZ()); z <= Math.min(foot.maxZ(), chunkBox.maxZ()); z++) {
                BlockState foundation = x == foot.minX() || x == foot.maxX() || z == foot.minZ() || z == foot.maxZ()
                        ? edgeBlock : fillBlock;
                for (int y = ground + 1; y <= foot.maxY() && y <= chunkBox.maxY(); y++) {
                    at.set(x, y, z);
                    if (!level.getBlockState(at).isAir()) level.setBlock(at, Blocks.AIR.defaultBlockState(), 2);
                }
                for (int y = ground - 1; y >= ground - FILL_DEPTH && y >= chunkBox.minY(); y--) {
                    at.set(x, y, z);
                    var state = level.getBlockState(at);
                    boolean soft = state.isAir() || !state.getFluidState().isEmpty() || state.is(BlockTags.REPLACEABLE) || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS);
                    if (!soft) break;
                    level.setBlock(at, foundation, 2);
                }
            }
    }

    @Override public StructurePoolElementType<?> getType() { return HumanWorld.LEVELLED.get(); }
    @Override public String toString() { return "Levelled[" + template + "]"; }
}
