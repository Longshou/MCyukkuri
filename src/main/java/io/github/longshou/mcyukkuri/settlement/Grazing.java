package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.MCYukkuri;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Sustainable grazing (project design). Eating a plant never deletes it for good: it falls back one growth stage and
 * grows again on game time (so {@code /tick rate} and {@code /tick sprint} move it like everything else).
 * <ul>
 * <li>tall grass / large fern: back to short grass / fern, returns to tall after {@link #TALL_DELAY};</li>
 * <li>short grass / fern: back to bare turf, returns after {@link #SHORT_DELAY};</li>
 * <li>ripe crop: back by half its growth range, then ripens again with vanilla random ticks (no ledger needed);</li>
 * <li>sweet berry bush: already left at age 1 by the berry foragers.</li>
 * </ul>
 * Vanilla never regrows short grass on its own (only bonemeal and world generation place it), which is why the old
 * "eat = delete the block" rule was a permanent loss. The return ledger lives in per-dimension saved data.
 */
@EventBusSubscriber(modid = MCYukkuri.MOD_ID)
public final class Grazing {
    /** Game ticks until bare turf grows a tuft again (plus up to half again at random): 0.5 to 0.75 day. */
    public static final int SHORT_DELAY = 12000;
    /** Game ticks until a grazed tall plant is tall again (plus up to half again at random): 1 to 1.5 days. */
    public static final int TALL_DELAY = 24000;
    private static final int SCAN = 100, MAX_ENTRIES = 4096;
    private enum Kind { SHORT_GRASS, FERN, TALL_GRASS, LARGE_FERN }

    /** True for a plant a yukkuri may graze. A double plant counts only through its lower half. */
    public static boolean edible(BlockState state) {
        if (state.is(Blocks.SHORT_GRASS) || state.is(Blocks.FERN)) return true;
        return (state.is(Blocks.TALL_GRASS) || state.is(Blocks.LARGE_FERN)) && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER;
    }
    /** The ration one grazed plant gives. */
    public static ItemStack yield(BlockState state) { return edible(state) ? new ItemStack(Items.SHORT_GRASS) : ItemStack.EMPTY; }

    /** Moves the plant at {@code pos} back one stage and queues its regrowth; false (nothing changed) when it is not grazable. */
    public static boolean graze(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (!edible(state)) return false;
        var ledger = Ledger.get(level);
        if (state.is(Blocks.TALL_GRASS) || state.is(Blocks.LARGE_FERN)) {
            boolean grass = state.is(Blocks.TALL_GRASS);
            if (level.getBlockState(pos.above()).is(state.getBlock())) level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 3);
            if (!level.setBlock(pos, (grass ? Blocks.SHORT_GRASS : Blocks.FERN).defaultBlockState(), 3)) return false;
            ledger.add(pos, level.getGameTime() + TALL_DELAY + level.random.nextInt(TALL_DELAY / 2), grass ? Kind.TALL_GRASS : Kind.LARGE_FERN);
        } else {
            boolean grass = state.is(Blocks.SHORT_GRASS);
            if (!level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)) return false;
            ledger.add(pos, level.getGameTime() + SHORT_DELAY + level.random.nextInt(SHORT_DELAY / 2), grass ? Kind.SHORT_GRASS : Kind.FERN);
        }
        return true;
    }

    /** A crop worth eating: ripe at least halfway. */
    public static boolean ripe(BlockState state) {
        return state.getBlock() instanceof CropBlock crop && crop.getAge(state) >= (crop.getMaxAge() + 1) / 2;
    }
    /** Eating a crop sets it back half its growth range instead of removing it; vanilla growth ripens it again. */
    public static boolean graze(ServerLevel level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof CropBlock crop) || !ripe(state)) return false;
        return level.setBlock(pos, crop.getStateForAge(Math.max(0, crop.getAge(state) - (crop.getMaxAge() + 1) / 2)), 3);
    }

    /** Pending regrowths in this level (for tests and observation). */
    public static int pending(ServerLevel level) { return Ledger.get(level).entries.size(); }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (level.getGameTime() % SCAN != 0) continue;
            var ledger = Ledger.get(level);
            if (ledger.entries.isEmpty()) continue;
            long now = level.getGameTime();
            List<Entry> due = new ArrayList<>();
            for (var entry : ledger.entries) if (entry.due <= now) due.add(entry);
            for (var entry : due) {
                BlockPos pos = BlockPos.of(entry.pos);
                if (!level.isLoaded(pos)) continue; // wait until the chunk is there again
                regrow(level, pos, entry.kind);
                ledger.entries.remove(entry);
            }
            if (!due.isEmpty()) ledger.setDirty();
        }
    }
    private static void regrow(ServerLevel level, BlockPos pos, Kind kind) {
        var here = level.getBlockState(pos);
        switch (kind) {
            case SHORT_GRASS, FERN -> {
                Block block = kind == Kind.SHORT_GRASS ? Blocks.SHORT_GRASS : Blocks.FERN;
                if (here.isAir() && level.getBlockState(pos.below()).is(BlockTags.DIRT) && block.defaultBlockState().canSurvive(level, pos))
                    level.setBlock(pos, block.defaultBlockState(), 2);
            }
            case TALL_GRASS, LARGE_FERN -> {
                Block small = kind == Kind.TALL_GRASS ? Blocks.SHORT_GRASS : Blocks.FERN;
                Block tall = kind == Kind.TALL_GRASS ? Blocks.TALL_GRASS : Blocks.LARGE_FERN;
                if (here.is(small) && level.getBlockState(pos.above()).isAir() && pos.getY() + 1 < level.getMaxBuildHeight())
                    DoublePlantBlock.placeAt(level, tall.defaultBlockState(), pos, 2);
            }
        }
    }

    private record Entry(long pos, long due, Kind kind) {}

    /** Per-dimension regrowth ledger. */
    private static final class Ledger extends SavedData {
        final List<Entry> entries = new ArrayList<>();
        static Ledger get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(new Factory<>(Ledger::new, Ledger::load), "mcyukkuri_regrowth");
        }
        void add(BlockPos pos, long due, Kind kind) {
            if (entries.size() >= MAX_ENTRIES) entries.remove(0); // the oldest simply stays as it is
            entries.add(new Entry(pos.asLong(), due, kind)); setDirty();
        }
        @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            var list = new ListTag();
            for (var entry : entries) {
                var one = new CompoundTag();
                one.putLong("Pos", entry.pos); one.putLong("Due", entry.due); one.putByte("Kind", (byte) entry.kind.ordinal());
                list.add(one);
            }
            tag.put("Entries", list); return tag;
        }
        static Ledger load(CompoundTag tag, HolderLookup.Provider registries) {
            var ledger = new Ledger();
            for (Tag raw : tag.getList("Entries", Tag.TAG_COMPOUND)) {
                var one = (CompoundTag) raw; int kind = one.getByte("Kind");
                if (kind >= 0 && kind < Kind.values().length) ledger.entries.add(new Entry(one.getLong("Pos"), one.getLong("Due"), Kind.values()[kind]));
            }
            return ledger;
        }
    }
    private Grazing() {}
}
