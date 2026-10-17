package io.github.longshou.mcyukkuri.settlement.site;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/** The generated settlement ground. Stores only the plan inputs; every chunk rebuilds the plan and writes its own columns. */
public final class WildSettlementPiece extends StructurePiece {
    private final SiteTier tier;
    private final long seed;
    private final int rotation, ox, oz, palette, gridX, gridZ, step, size;
    private final int[] heights;
    private SitePlan plan;

    public WildSettlementPiece(SiteTier tier, long seed, int rotation, int ox, int oz, int palette, int gridX, int gridZ, int step, int size,
                               int[] heights, BoundingBox box) {
        super(SiteWorld.PIECE.get(), 0, box);
        this.tier = tier; this.seed = seed; this.rotation = rotation; this.ox = ox; this.oz = oz; this.palette = palette;
        this.gridX = gridX; this.gridZ = gridZ; this.step = step; this.size = size; this.heights = heights;
    }
    public WildSettlementPiece(CompoundTag tag) {
        super(SiteWorld.PIECE.get(), tag);
        var read = SiteTier.byName(tag.getString("Tier"));
        this.tier = read == null ? SiteTier.BAND : read;
        this.seed = tag.getLong("Seed"); this.rotation = tag.getInt("Rotation"); this.ox = tag.getInt("X"); this.oz = tag.getInt("Z");
        this.palette = tag.getInt("Palette"); this.gridX = tag.getInt("GridX"); this.gridZ = tag.getInt("GridZ");
        this.step = Math.max(1, tag.getInt("Step")); this.size = Math.max(1, tag.getInt("Size"));
        var saved = tag.getIntArray("Heights");
        this.heights = saved.length == this.size * this.size ? saved : new int[this.size * this.size];
    }

    @Override protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putString("Tier", tier.name());
        tag.putLong("Seed", seed); tag.putInt("Rotation", rotation); tag.putInt("X", ox); tag.putInt("Z", oz);
        tag.putInt("Palette", palette); tag.putInt("GridX", gridX); tag.putInt("GridZ", gridZ);
        tag.putInt("Step", step); tag.putInt("Size", size); tag.putIntArray("Heights", heights);
    }

    /** The same plan the structure start computed; also what the site registry reads once players come near. */
    public synchronized SitePlan plan() {
        if (plan == null) plan = SitePlanner.plan(tier, seed, rotation, ox, oz, palette, new SiteBuilder.GridTerrain(gridX, gridZ, step, size, size, heights), false);
        return plan;
    }

    @Override public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator, RandomSource random,
                                      BoundingBox chunkBox, ChunkPos chunk, BlockPos pivot) {
        var layout = plan();
        if (layout == null) return;
        int minX = Math.max(chunkBox.minX(), boundingBox.minX()), maxX = Math.min(chunkBox.maxX(), boundingBox.maxX());
        int minZ = Math.max(chunkBox.minZ(), boundingBox.minZ()), maxZ = Math.min(chunkBox.maxZ(), boundingBox.maxZ());
        if (minX > maxX || minZ > maxZ) return;
        var clip = new BoundingBox(minX, boundingBox.minY(), minZ, maxX, boundingBox.maxY(), maxZ);
        SiteBuilder.build(level, layout, clip, (x, z) -> level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z), false, false);
    }
}
