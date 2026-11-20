package io.github.longshou.mcyukkuri.settlement.mixin;

import io.github.longshou.mcyukkuri.settlement.EnclosureTerrain;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
abstract class EnclosureTerrainMixin implements EnclosureTerrain {
    @Unique private static final AtomicLong MCYUKKURI_ENCLOSURE_VERSIONS = new AtomicLong();
    @Unique private long mcyukkuri$version;

    @Override public long mcyukkuri$enclosureVersion() {
        if (mcyukkuri$version == 0) mcyukkuri$version = MCYUKKURI_ENCLOSURE_VERSIONS.incrementAndGet();
        return mcyukkuri$version;
    }

    // A block callback can inspect the terrain during the write. Expire both sides of that callback.
    @Inject(method = "setBlockState", at = {@At("HEAD"), @At("RETURN")})
    private void mcyukkuri$terrainEdited(BlockPos pos, BlockState state, boolean moving, CallbackInfoReturnable<BlockState> ci) {
        mcyukkuri$version = 0;
    }

    @Inject(method = "setLoaded", at = @At("RETURN"))
    private void mcyukkuri$loadChanged(boolean loaded, CallbackInfo ci) {
        mcyukkuri$version = 0;
    }
}
