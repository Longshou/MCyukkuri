package io.github.longshou.mcyukkuri.predator;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.world.SearchBudget;
import io.github.longshou.mcyukkuri.world.SearchBudget.Cost;
import io.github.longshou.mcyukkuri.world.SearchBudget.Purpose;
import io.github.longshou.mcyukkuri.world.SearchBudget.Result;
import java.lang.ref.WeakReference;
import java.util.Iterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.neoforged.neoforge.event.EventHooks;

/** Existing nests first, then optional weaving; all world reads and writes stay on the server thread. */
final class PredatorNestSearch {
    private static final int SLICE = 4096, ASSIGN_PATHS = 512, WEAVE_PATHS = 768;
    private final WeakReference<ServerLevel> world;
    private final BlockPos origin;
    private final long generation = SearchBudget.generation();
    final boolean weave;
    private Iterator<BlockPos> cells;
    private boolean weaving;
    private BlockPos pending;
    private long lastSlice = Long.MIN_VALUE;

    PredatorNestSearch(ServerLevel level, YukkuriEntity animal, boolean weave) {
        world = new WeakReference<>(level); origin = animal.blockPosition().immutable(); this.weave = weave;
        cells = BlockPos.betweenClosed(origin.offset(-10, -3, -10), origin.offset(10, 8, 10)).iterator();
    }
    boolean current(ServerLevel level, YukkuriEntity animal) {
        // Small movement does not restart thousands of probes; assignment still checks the actual distance.
        return world.get() == level && origin.distSqr(animal.blockPosition()) <= 16;
    }
    void cancel(YukkuriEntity animal) {
        var level = world.get();
        if (level != null) SearchBudget.cancel(level, animal, Purpose.PREDATOR_NEST, generation);
    }

    Result advance(ServerLevel level, YukkuriEntity animal) {
        if (lastSlice == level.getGameTime()) return Result.DEFERRED;
        var lease = SearchBudget.request(level, animal, Purpose.PREDATOR_NEST, generation, new Cost(SLICE, 0, 0));
        if (lease == null) return Result.DEFERRED;
        lastSlice = level.getGameTime();
        try (lease; var scope = lease.bind(animal)) {
            int left = SLICE;
            boolean weavingChecked = false;
            while (left > 0) {
                if (pending == null) {
                    if (!cells.hasNext()) {
                        if (weaving || !weave) return Result.NOT_FOUND;
                        weaving = true;
                        cells = BlockPos.betweenClosed(origin.offset(-8, 1, -8), origin.offset(8, 6, 8)).iterator();
                    }
                    pending = cells.next().immutable();
                }
                if (weaving && !weavingChecked) {
                    if (HumanCommerce.protectedAnimal(animal) || !EventHooks.canEntityGrief(level, animal)) return Result.NOT_FOUND;
                    weavingChecked = true;
                }
                var pos = pending;
                SearchBudget.probe(level); left--;
                if (!level.hasChunkAt(pos)) { pending = null; continue; }
                if (!weaving) {
                    if (!level.getBlockState(pos).is(ModBlocks.PREDATOR_NEST.get())) { pending = null; continue; }
                    if (left < ASSIGN_PATHS) return Result.DEFERRED;
                    pending = null; left -= ASSIGN_PATHS;
                    if (animal.life().assignHome(GlobalPos.of(level.dimension(), pos))) return Result.FOUND;
                    continue;
                }
                if (!level.getBlockState(pos).isAir() || !animal.life().covered(pos)
                        || !level.getFluidState(pos).isEmpty()) { pending = null; continue; }
                var entrance = animal.life().entrance(pos);
                if (entrance == null) { pending = null; continue; }
                var support = level.getBlockState(pos.below());
                if (!(support.is(BlockTags.LOGS) || support.is(BlockTags.BASE_STONE_OVERWORLD) || support.is(BlockTags.DIRT))) {
                    pending = null; continue;
                }
                if (left < WEAVE_PATHS) return Result.DEFERRED;
                pending = null; left -= WEAVE_PATHS;
                if (!animal.predator().canFlyTo(entrance)) continue;
                if (!level.setBlock(pos, ModBlocks.PREDATOR_NEST.get().defaultBlockState(), 3)) continue;
                // Claim or undo within this lease: no partially woven nest survives a deferral.
                if (animal.life().assignHome(GlobalPos.of(level.dimension(), pos))) return Result.FOUND;
                level.removeBlock(pos, false);
            }
            return Result.DEFERRED;
        }
    }
}
