package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** A street bin's real, finite contents: nine slots the player can open, passers-by fill and strays take from. */
public final class GarbageBinBlockEntity extends BlockEntity implements Container {
    public static final int SLOTS = 9;
    private final NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    /** The street day of the last thing a passer-by put in: one deposit per bin per day. */
    private long lastDeposit = Long.MIN_VALUE;

    public GarbageBinBlockEntity(BlockPos pos, BlockState state) { super(ModBlocks.GARBAGE_BIN_ENTITY.get(), pos, state); }

    public boolean tipped() { return getBlockState().getValue(GarbageBinBlock.TIPPED); }
    public long lastDeposit() { return lastDeposit; }
    public void deposited(long day) { lastDeposit = day; setChanged(); }

    /** Items inside; a fuller bin is heavier to knock over. */
    public int load() { return items.stream().mapToInt(ItemStack::getCount).sum(); }

    @Override public int getContainerSize() { return SLOTS; }
    @Override public boolean isEmpty() { return items.stream().allMatch(ItemStack::isEmpty); }
    @Override public ItemStack getItem(int slot) { return items.get(slot); }
    @Override public ItemStack removeItem(int slot, int count) {
        var out = ContainerHelper.removeItem(items, slot, count);
        if (!out.isEmpty()) setChanged();
        return out;
    }
    @Override public ItemStack removeItemNoUpdate(int slot) { return ContainerHelper.takeItem(items, slot); }
    @Override public void setItem(int slot, ItemStack stack) { items.set(slot, stack); setChanged(); }
    @Override public boolean stillValid(Player player) { return Container.stillValidBlockEntity(this, player); }
    @Override public void clearContent() { items.clear(); setChanged(); }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
        if (lastDeposit != Long.MIN_VALUE) tag.putLong("LastDeposit", lastDeposit);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items.clear();
        ContainerHelper.loadAllItems(tag, items, registries);
        lastDeposit = tag.contains("LastDeposit") ? tag.getLong("LastDeposit") : Long.MIN_VALUE;
    }
}
