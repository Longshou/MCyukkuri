package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A staffed counter: the counter crate in front of a resident's home ({@code middle}, the crate the keeper stands in front of) and the
 * side its low edge points to. A crate is a counter crate by its own flag, not by what hangs over it. The run is every counter crate
 * of the same row, facing and height within {@link #RUN} cells of the middle: one keeper serves the whole run, and its goods and till
 * are shared by the crates in it.
 */
record Counter(BlockPos middle, Direction front) {
    /** Cells each way along the row a keeper reaches (the corner shop keeper at one end of four crates, a stall keeper by two or three). */
    static final int RUN = 2;

    /** The counter whose crate is one or two cells in front of the home cell and one up, facing away from the home. */
    static Counter atHome(Level level, BlockPos home) {
        for (var front : Direction.Plane.HORIZONTAL) {
            for (int out = 1; out <= 2; out++) {
                var counter = new Counter(home.above().relative(front, out), front);
                if (counter.crate(level) != null) return counter;
            }
        }
        return null;
    }

    static ProduceCrateBlockEntity crateAt(Level level, BlockPos pos, Direction front) {
        if (!level.hasChunkAt(pos)) return null;
        var state = level.getBlockState(pos);
        return state.is(ModBlocks.PRODUCE_CRATE.get()) && state.getValue(ProduceCrateBlock.FACING) == front
                && level.getBlockEntity(pos) instanceof ProduceCrateBlockEntity crate && crate.counter() ? crate : null;
    }

    BlockPos stand() { return middle.below().relative(front); }

    ProduceCrateBlockEntity crate(Level level) { return crateAt(level, middle, front); }

    /** Whether the crate at {@code pos} facing {@code facing} belongs to this keeper's run (the flag is read from the crate itself). */
    boolean owns(BlockPos pos, Direction facing) {
        var offset = pos.subtract(middle);
        var along = front.getClockWise().getNormal();
        return facing == front && offset.getY() == 0 && offset.getX() * front.getStepX() + offset.getZ() * front.getStepZ() == 0
                && Math.abs(offset.getX() * along.getX() + offset.getZ() * along.getZ()) <= RUN;
    }

    /** The counter crates of the run, {@code first} (the one the player used) in front. */
    List<ProduceCrateBlockEntity> run(Level level, BlockPos first) {
        var crates = new ArrayList<ProduceCrateBlockEntity>();
        var clicked = first == null ? null : crateAt(level, first, front);
        if (clicked != null && owns(first, front)) crates.add(clicked);
        for (int offset = -RUN; offset <= RUN; offset++) {
            var pos = middle.relative(front.getClockWise(), offset);
            var crate = crateAt(level, pos, front);
            if (crate != null && crate != clicked) crates.add(crate);
        }
        return crates;
    }

    boolean publicSide(Player player, BlockHitResult hit) {
        return hit.getDirection() == front || onPublicSide(player.getEyePosition());
    }

    boolean onPublicSide(Vec3 position) {
        return position.subtract(middle.getCenter()).dot(Vec3.atLowerCornerOf(front.getNormal())) >= 0;
    }

    boolean hasFood(YukkuriEntity stray) {
        for (var crate : run(stray.level(), null)) {
            if (crate.hasCounterFood(stray.life()::seeksFood) && HumanStreet.seesSite(stray, crate.getBlockPos())) return true;
        }
        return false;
    }
}
