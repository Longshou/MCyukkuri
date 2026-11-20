package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** World positions from the authored kiosk, with the crate's low side pointing out under the awning. */
record KioskCounter(BlockPos middle, Direction front) {
    static KioskCounter at(Level level, BlockPos crate, Direction front) {
        if (!level.getBlockState(crate).is(ModBlocks.PRODUCE_CRATE.get())) return null;
        for (int offset = -1; offset <= 1; offset++) {
            var counter = new KioskCounter(crate.relative(front.getClockWise(), offset), front);
            // The inner roof row also identifies the public counter if its hanging valance is broken.
            if (counter.valance(level) || counter.stripes(level, 1, 2)) return counter;
        }
        return null;
    }

    static KioskCounter atHome(Level level, BlockPos home) {
        for (var front : Direction.Plane.HORIZONTAL) {
            var counter = new KioskCounter(home.relative(front, 2).above(), front);
            if (counter.valance(level) && counter.hasCrate(level)) return counter;
        }
        return null;
    }

    BlockPos stand() { return middle.below().relative(front); }
    BlockPos home() { return middle.below().relative(front.getOpposite(), 2); }
    boolean valance(Level level) { return stripes(level, 2, 1); }

    private boolean stripes(Level level, int forward, int above) {
        var center = middle.relative(front, forward).above(above);
        for (int offset = -3; offset <= 3; offset++) {
            var pos = center.relative(front.getClockWise(), offset);
            var wool = (offset & 1) == 0 ? Blocks.WHITE_WOOL : Blocks.BLUE_WOOL;
            if (!level.hasChunkAt(pos) || !level.getBlockState(pos).is(wool)) return false;
        }
        return true;
    }

    boolean publicSide(Player player, BlockHitResult hit) {
        return hit.getDirection() == front || onPublicSide(player.getEyePosition());
    }

    boolean onPublicSide(Vec3 position) {
        return position.subtract(middle.getCenter()).dot(Vec3.atLowerCornerOf(front.getNormal())) >= 0;
    }

    boolean hasCrate(Level level) {
        for (int offset = -1; offset <= 1; offset++) if (crate(level, offset) != null) return true;
        return false;
    }

    boolean hasFood(YukkuriEntity stray) {
        for (int offset = -1; offset <= 1; offset++) {
            var crate = crate(stray.level(), offset);
            if (crate != null && crate.hasKioskFood(stray.life()::seeksFood)
                    && HumanStreet.seesSite(stray, crate.getBlockPos())) return true;
        }
        return false;
    }

    private ProduceCrateBlockEntity crate(Level level, int offset) {
        var pos = middle.relative(front.getClockWise(), offset);
        if (!level.hasChunkAt(pos)) return null;
        var state = level.getBlockState(pos);
        return state.is(ModBlocks.PRODUCE_CRATE.get()) && state.getValue(ProduceCrateBlock.FACING) == front
                && level.getBlockEntity(pos) instanceof ProduceCrateBlockEntity crate ? crate : null;
    }

    static boolean saleable(ItemStack stack) {
        return stack.is(Items.BREAD) || stack.is(ModItems.GRAIN_REGULAR.get())
                || stack.is(Items.PAPER) || stack.is(ModItems.SWEET_CANDY.get());
    }
}
