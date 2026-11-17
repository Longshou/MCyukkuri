package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.human.mixin.SinglePoolElementAccessor;
import java.util.ArrayList;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;

/** Add one edge building after all the original village lots and roads have been decided. */
public final class VillagePlant {
    private record RoadEnd(BlockPos cell, Direction outward) {}

    public static void append(Structure.GenerationContext context, BlockPos center, int radius, StructurePiecesBuilder builder) {
        var village = builder.build().pieces();
        var townCenter = template(village.getFirst());
        if (townCenter.contains("/zombie/")) return;
        var manager = context.structureTemplateManager();
        if (manager.get(PlantSite.TEMPLATE).isEmpty()) return;
        var pool = context.registryAccess().registryOrThrow(Registries.TEMPLATE_POOL).getOptional(MCYukkuri.id("processing_plant"));
        if (pool.isEmpty()) return;
        // Keep this draw and the connector order independent of the village's jigsaw random stream.
        var random = RandomSource.create(context.seed() ^ context.chunkPos().toLong());
        var plant = (LevelledPoolElement) pool.get().getRandomTemplate(random);
        var ends = new ArrayList<RoadEnd>();
        for (var piece : village) {
            String path = template(piece);
            boolean terminator = path.contains("/terminators/");
            if (!terminator && !path.contains("/streets/")) continue;
            var road = (PoolElementStructurePiece) piece;
            for (var jigsaw : road.getElement().getShuffledJigsawBlocks(manager, road.getPosition(), road.getRotation(), random)) {
                if (jigsaw.nbt() == null || !jigsaw.nbt().getString("name").equals("minecraft:street")) continue;
                var outward = JigsawBlock.getFrontFacing(jigsaw.state());
                if (!outward.getAxis().isHorizontal()) continue;
                var edge = jigsaw.pos();
                if (terminator) {
                    var box = road.getBoundingBox();
                    outward = outward.getOpposite();
                    edge = switch (outward) {
                        case NORTH -> new BlockPos(edge.getX(), edge.getY(), box.minZ());
                        case SOUTH -> new BlockPos(edge.getX(), edge.getY(), box.maxZ());
                        case WEST -> new BlockPos(box.minX(), edge.getY(), edge.getZ());
                        case EAST -> new BlockPos(box.maxX(), edge.getY(), edge.getZ());
                        default -> throw new IllegalStateException("Village road end must be horizontal");
                    };
                }
                ends.add(new RoadEnd(edge.relative(outward), outward));
            }
        }
        ends.sort(Comparator.comparingDouble((RoadEnd end) -> end.cell().distSqr(center)).reversed());
        for (var end : ends) {
            var cell = end.cell();
            var rotation = HumanScenes.rotationFor(end.outward().getOpposite());
            var origin = cell.subtract(PlantSite.ENTRANCE.rotate(rotation));
            var box = plant.getBoundingBox(manager, origin, rotation);
            if (box.minX() < center.getX() - radius || box.maxX() > center.getX() + radius
                    || box.minZ() < center.getZ() - radius || box.maxZ() > center.getZ() + radius
                    || village.stream().anyMatch(piece -> piece.getBoundingBox().intersects(box.minX(), box.minZ(), box.maxX(), box.maxZ()))) continue;
            int ground = context.chunkGenerator().getBaseHeight(cell.getX(), cell.getZ(),
                    Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), context.randomState()) - 1;
            origin = new BlockPos(origin.getX(), ground, origin.getZ());
            var site = plant.getBoundingBox(manager, origin, rotation);
            if (!plant.fitsTerrain(context.chunkGenerator(), context.heightAccessor(), context.randomState(), site)) continue;
            builder.addPiece(new PoolElementStructurePiece(manager, plant, origin, plant.getGroundLevelDelta(), rotation, site,
                    LiquidSettings.IGNORE_WATERLOGGING));
            return;
        }
    }

    private static String template(StructurePiece piece) {
        if (piece instanceof PoolElementStructurePiece pool && pool.getElement() instanceof SinglePoolElement single)
            return ((SinglePoolElementAccessor) single).mcyukkuri$template().left().map(id -> id.getPath()).orElse("");
        return "";
    }

    private VillagePlant() {}
}
