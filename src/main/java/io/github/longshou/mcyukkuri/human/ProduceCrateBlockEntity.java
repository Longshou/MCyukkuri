package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The real contents of an open produce crate: four rows of up to four items each. The client draws exactly these items
 * (client/ProduceCrateRenderer), so what is seen in the crate is what it holds.
 */
public final class ProduceCrateBlockEntity extends BlockEntity implements Container {
    public static final int ROWS = 4, PER_ROW = 4;
    private final NonNullList<ItemStack> items = NonNullList.withSize(ROWS, ItemStack.EMPTY);

    public ProduceCrateBlockEntity(BlockPos pos, BlockState state) { super(ModBlocks.PRODUCE_CRATE_ENTITY.get(), pos, state); }

    /** Puts one of {@code stack} in: onto a row of the same item with room, else into the first empty row. False when full. */
    public boolean put(ItemStack stack) {
        if (stack.isEmpty()) return false;
        int free = -1;
        for (int row = 0; row < ROWS; row++) {
            var held = items.get(row);
            if (held.isEmpty()) { if (free < 0) free = row; continue; }
            if (held.getCount() < PER_ROW && ItemStack.isSameItemSameComponents(held, stack)) { held.grow(1); changed(); return true; }
        }
        if (free < 0) return false;
        items.set(free, stack.copyWithCount(1)); changed();
        return true;
    }

    /** Takes one item from the front-most occupied row; empty when the crate is empty. */
    public ItemStack takeOne() {
        for (int row = ROWS - 1; row >= 0; row--) if (!items.get(row).isEmpty()) return removeItem(row, 1);
        return ItemStack.EMPTY;
    }

    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override public int getContainerSize() { return ROWS; }
    @Override public int getMaxStackSize() { return PER_ROW; }
    @Override public boolean isEmpty() { return items.stream().allMatch(ItemStack::isEmpty); }
    @Override public ItemStack getItem(int slot) { return items.get(slot); }
    @Override public ItemStack removeItem(int slot, int count) {
        var out = ContainerHelper.removeItem(items, slot, count);
        if (!out.isEmpty()) changed();
        return out;
    }
    @Override public ItemStack removeItemNoUpdate(int slot) { return ContainerHelper.takeItem(items, slot); }
    @Override public void setItem(int slot, ItemStack stack) { stack.limitSize(getMaxStackSize(stack)); items.set(slot, stack); changed(); }
    @Override public boolean stillValid(Player player) { return Container.stillValidBlockEntity(this, player); }
    @Override public void clearContent() { items.clear(); changed(); }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items.clear();
        ContainerHelper.loadAllItems(tag, items, registries);
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
