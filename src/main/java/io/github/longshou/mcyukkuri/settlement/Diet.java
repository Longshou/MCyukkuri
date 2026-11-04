package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A species' own food: the wild plants it picks where they grow and the order its feeding search tries its sources
 * (project design; wiki N14《水上まりさのお帽子は》 gives the water group 水藻 and N114《山と川》 the riverside 水草).
 * One plant is one portion: picking it consumes the plant where it grew, and it comes back only by the vanilla rules,
 * so a water plant is finite while a land plant returns through the grazing ledger.
 */
public enum Diet {
    /** Ordinary species: food lying nearby or in the household store, then a wild land plant (unchanged). */
    ORDINARY(false, Step.STOCK, Step.PLANT),
    /** Aquatic Marisa: the water plants of her water at its edge, then the portion her own hat keeps, then food lying nearby. */
    AQUATIC(true, Step.PLANT, Step.LARDER, Step.STOCK);

    /** One step of the feeding search's food selection. */
    public enum Step { STOCK, PLANT, LARDER }

    private final boolean water;
    private final List<Step> sources;

    Diet(boolean water, Step... sources) {
        this.water = water;
        this.sources = List.of(sources);
    }

    /** The sources the feeding search tries, in order. */
    public List<Step> sources() { return sources; }

    /** Whether a plant of this diet grows here. */
    public boolean edible(BlockState state) {
        return water ? state.is(Blocks.SEAGRASS) || state.is(Blocks.KELP) : Grazing.edible(state);
    }

    /** The portion one plant of this diet gives; empty when nothing of this diet grows at {@code state}. */
    public ItemStack yield(BlockState state) {
        if (!edible(state)) return ItemStack.EMPTY;
        return water ? new ItemStack(state.is(Blocks.SEAGRASS) ? Items.SEAGRASS : Items.KELP) : Grazing.yield(state);
    }

    /** Picks the plant at {@code pos} for real: a land plant falls back one stage, a water plant leaves the water it grew in. */
    public boolean harvest(ServerLevel level, BlockPos pos) {
        if (!edible(level.getBlockState(pos))) return false;
        // A picked kelp head takes its stem with it on the next block update and drops nothing: no second portion is minted.
        return water ? level.setBlock(pos, Blocks.WATER.defaultBlockState(), 3) : Grazing.graze(level, pos);
    }

    /** The cell a body stands on to pick {@code plant}, or null when no stand of this diet reaches it: a water plant is
     *  picked from the dry bank cell above the shallow water beside it, so the body stays out of the water. */
    public BlockPos stand(YukkuriEntity body, BlockPos plant) {
        if (!water) return plant;
        for (var beside : new BlockPos[]{plant.east(), plant.west(), plant.south(), plant.north()}) {
            var at = beside.above();
            if (body.level().getFluidState(at).isEmpty() && SurfaceApproach.supported(body, Vec3.atBottomCenterOf(at))) return at;
        }
        return null;
    }

    /** Whether the ration item itself is food for this diet: the water plants feed only the species that picks them. */
    public boolean eats(ItemStack food) { return water && food.is(YukkuriEntity.WATER_FOOD); }

    /** Whether a body standing at {@code stand} reaches {@code plant} now: on land it must lie in sight straight ahead, while a
     *  water plant is picked down over the bank edge, whose own block is what that sight line would graze. */
    public boolean reaches(YukkuriEntity body, BlockPos stand, BlockPos plant) {
        if (body.distanceToSqr(Vec3.atBottomCenterOf(stand)) >= 1) return false;
        var level = body.level();
        if (!water) return level.clip(new ClipContext(body.getEyePosition(), Vec3.atCenterOf(plant),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body)).getType() == HitResult.Type.MISS;
        var open = plant.above();
        return level.getFluidState(open).isEmpty() && level.getBlockState(open).getCollisionShape(level, open).isEmpty();
    }
}
