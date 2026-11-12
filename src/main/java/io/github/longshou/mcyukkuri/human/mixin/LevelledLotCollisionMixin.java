package io.github.longshou.mcyukkuri.human.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.longshou.mcyukkuri.human.LevelledPoolElement;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement$Placer")
abstract class LevelledLotCollisionMixin {
    @WrapOperation(method = "tryPlacingChildren", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/phys/shapes/Shapes;joinUnoptimized(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/BooleanOp;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
    private VoxelShape mcyukkuri$reserveLotColumns(VoxelShape free, VoxelShape occupied, BooleanOp operation,
            Operation<VoxelShape> join, @Local(ordinal = 1) StructurePoolElement child, @Local(argsOnly = true) LevelHeightAccessor height) {
        if (child instanceof LevelledPoolElement) {
            // Terrain-matching roads can rise out of their jigsaw box when written. Keep their columns clear of levelled lots.
            var box = occupied.bounds();
            occupied = Shapes.create(box.minX, height.getMinBuildHeight(), box.minZ, box.maxX, height.getMaxBuildHeight(), box.maxZ);
        }
        return join.call(free, occupied, operation);
    }
}
