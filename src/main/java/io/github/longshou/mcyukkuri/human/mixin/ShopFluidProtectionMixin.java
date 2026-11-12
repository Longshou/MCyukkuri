package io.github.longshou.mcyukkuri.human.mixin;

import io.github.longshou.mcyukkuri.shop.ShopRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ordinary spread does not post the fluid block-reaction event. Guard its destination before it is changed. */
@Mixin(FlowingFluid.class)
abstract class ShopFluidProtectionMixin {
    @Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
    private void mcyukkuri$guardShop(LevelAccessor level, BlockPos destination, BlockState replaced,
            Direction direction, FluidState fluid, CallbackInfo callback) {
        if (level instanceof ServerLevel server && ShopRegistry.get(server).protectedAt(server, destination)) callback.cancel();
    }
}
