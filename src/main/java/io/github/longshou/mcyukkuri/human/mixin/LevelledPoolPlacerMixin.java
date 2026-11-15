package io.github.longshou.mcyukkuri.human.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.longshou.mcyukkuri.human.LevelledPoolElement;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement$Placer")
abstract class LevelledPoolPlacerMixin {
    @Shadow @Final private ChunkGenerator chunkGenerator;
    @Shadow @Final private List<PoolElementStructurePiece> pieces;

    @WrapOperation(method = "tryPlacingChildren", at = @At(value = "INVOKE", ordinal = 0, target =
            "Lnet/minecraft/world/level/levelgen/structure/pools/StructureTemplatePool;getShuffledTemplates(Lnet/minecraft/util/RandomSource;)Ljava/util/List;"))
    private List<StructurePoolElement> mcyukkuri$endMainStreet(StructureTemplatePool pool, RandomSource random,
            Operation<List<StructurePoolElement>> shuffle, @Local(argsOnly = true) int depth, @Local ResourceKey<StructureTemplatePool> key) {
        // Town centre is depth 0, frontage is 1, two lots are 2 and 3. Leave the fallback mouth and vanilla lanes room to grow.
        var id = key.location();
        if (depth >= 3 && id.getNamespace().equals("mcyukkuri") && id.getPath().startsWith("village/")
                && (id.getPath().endsWith("/main_market") || id.getPath().endsWith("/main_civic"))) return List.of();
        return shuffle.call(pool, random);
    }

    @WrapOperation(method = "tryPlacingChildren", at = @At(value = "INVOKE", ordinal = 1, target = "Ljava/util/List;iterator()Ljava/util/Iterator;"))
    private Iterator<StructurePoolElement> mcyukkuri$limitCandidates(List<StructurePoolElement> candidates,
            Operation<Iterator<StructurePoolElement>> iterator) {
        // Vanilla already shuffled the weighted copies. Keep the first draw and avoid evaluating identical failed copies at this socket.
        var seen = new HashSet<LevelledPoolElement>();
        candidates.removeIf(element -> element instanceof LevelledPoolElement lot && lot.hasPlacementLimits()
                && (!seen.add(lot) || lot.isAtLimit(pieces)));
        return iterator.call(candidates);
    }

    @WrapOperation(method = "tryPlacingChildren", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/phys/shapes/Shapes;joinIsNotEmpty(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/BooleanOp;)Z"))
    private boolean mcyukkuri$rejectUnsuitableTerrain(VoxelShape free, VoxelShape occupied, BooleanOp operation, Operation<Boolean> collides,
            @Local(ordinal = 1) StructurePoolElement child, @Local(ordinal = 3) BoundingBox box,
            @Local(argsOnly = true) LevelHeightAccessor height, @Local(argsOnly = true) RandomState random) {
        return collides.call(free, occupied, operation)
                || (child instanceof LevelledPoolElement lot && !lot.fitsTerrain(chunkGenerator, height, random, box));
    }
}
