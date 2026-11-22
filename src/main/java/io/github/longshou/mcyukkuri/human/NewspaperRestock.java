package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The corner shop keeper's newspaper stack: once a day, at the hatch and free, the keeper unties one more bundle (4 sheets) from
 * paper the counter crates really hold (paper players sold back) and adds it to the stack in front of the counter, or sets a fresh
 * bundle down when the stack is gone. The stack is never topped past three bundles, and no paper is conjured: the crates lose
 * exactly the sheets the stack gains.
 */
final class NewspaperRestock {
    /** Cells out from the counter crate row to look for the stack (the street template sets it 2 out). */
    private static final int NEAR = 2, FAR = 3;
    private static final int FULL = NewspaperStackBlock.SHEETS_PER_BUNDLE * 3;

    static void run(HumanNpc keeper, Counter counter) {
        var level = (ServerLevel) keeper.level();
        var crates = counter.run(level, null);
        int paper = 0;
        for (var crate : crates)
            for (int row = 0; row < ProduceCrateBlockEntity.ROWS; row++)
                if (crate.getItem(row).is(Items.PAPER)) paper += crate.getItem(row).getCount();
        int bundle = NewspaperStackBlock.SHEETS_PER_BUNDLE;
        if (paper < bundle) return;

        BlockPos target = null;
        BlockState next = null;
        int before = 0;
        boolean anyStack = false;
        BlockPos free = null;
        int lowest = FULL + 1;
        for (int out = NEAR; out <= FAR; out++) {
            for (int along = -Counter.RUN; along <= Counter.RUN; along++) {
                var pos = counter.middle().below().relative(counter.front(), out).relative(counter.front().getClockWise(), along);
                if (!level.hasChunkAt(pos)) continue;
                var state = level.getBlockState(pos);
                int sheets = NewspaperStackBlock.sheets(state);
                if (sheets > 0) {
                    anyStack = true;
                    if (sheets <= FULL - bundle && sheets < lowest) { lowest = sheets; target = pos; before = sheets; }
                } else if (free == null && state.isAir() && level.getEntities((Entity) null, new AABB(pos), e -> true).isEmpty()) {
                    var fresh = HumanWorld.NEWSPAPER_STACK.get().defaultBlockState()
                            .setValue(NewspaperStackBlock.FACING, counter.front());
                    if (fresh.canSurvive(level, pos)) free = pos;
                }
            }
        }
        if (target != null) {
            var state = level.getBlockState(target);
            int sheets = before + bundle;
            int bundles = (sheets + bundle - 1) / bundle;
            next = state.setValue(NewspaperStackBlock.BUNDLES, bundles).setValue(NewspaperStackBlock.TAKEN, bundles * bundle - sheets);
        } else if (!anyStack && free != null) {
            target = free;
            next = HumanWorld.NEWSPAPER_STACK.get().defaultBlockState().setValue(NewspaperStackBlock.FACING, counter.front());
        } else return;

        if (!level.setBlock(target, next, Block.UPDATE_ALL)) return;
        int owed = bundle;
        for (var crate : crates) {
            for (int row = 0; row < ProduceCrateBlockEntity.ROWS && owed > 0; row++) {
                if (!crate.getItem(row).is(Items.PAPER)) continue;
                owed -= crate.removeItem(row, Math.min(owed, crate.getItem(row).getCount())).getCount();
            }
        }
        keeper.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, target, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, .8F, .9F + level.random.nextFloat() * .2F);
        GameplayLog.event(keeper, "shop", "action=newspaper_restock stack={} sheets={}->{} paper={}->{}",
                target.toShortString(), before, NewspaperStackBlock.sheets(next), paper, paper - bundle);
    }

    private NewspaperRestock() {}
}
