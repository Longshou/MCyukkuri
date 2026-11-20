package io.github.longshou.mcyukkuri.settlement.mixin;

import io.github.longshou.mcyukkuri.settlement.EnclosureTerrain;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunkSection.class)
abstract class EnclosureTerrainMixin implements EnclosureTerrain {
    @Unique private static final AtomicLong MCYUKKURI_ENCLOSURE_VERSIONS = new AtomicLong();
    @Unique private long mcyukkuri$version;

    @Override public long mcyukkuri$enclosureVersion() {
        if (mcyukkuri$version == 0) mcyukkuri$version = MCYUKKURI_ENCLOSURE_VERSIONS.incrementAndGet();
        return mcyukkuri$version;
    }

    @Override public void mcyukkuri$invalidateEnclosure() {
        mcyukkuri$version = 0;
    }

    // Ordinary chunk edits and bulk feature placement both write the section before block callbacks run.
    @Inject(method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("RETURN"))
    private void mcyukkuri$terrainEdited(int x, int y, int z, BlockState state, boolean lock, CallbackInfoReturnable<BlockState> ci) {
        if (ci.getReturnValue() != state) mcyukkuri$invalidateEnclosure();
    }
}
