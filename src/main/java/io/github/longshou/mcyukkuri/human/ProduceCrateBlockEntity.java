package io.github.longshou.mcyukkuri.human;

import java.util.function.Predicate;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.shop.CounterGoods;
import io.github.longshou.mcyukkuri.shop.ShopProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The real contents of an open produce crate: four rows of up to four items each. The client draws exactly these items
 * (client/ProduceCrateRenderer), so what is seen in the crate is what it holds.
 */
public final class ProduceCrateBlockEntity extends BlockEntity implements WorldlyContainer {
    public static final int ROWS = 4, PER_ROW = 4;
    private static final int[] SLOTS = {0, 1, 2, 3};
    private final NonNullList<ItemStack> items = NonNullList.withSize(ROWS, ItemStack.EMPTY);
    private boolean stall, counter;

    public ProduceCrateBlockEntity(BlockPos pos, BlockState state) { super(ModBlocks.PRODUCE_CRATE_ENTITY.get(), pos, state); }

    /**
     * Puts one of {@code stack} in: onto a row of the same item with room, else into the first empty row. False when full.
     */
    public boolean put(ItemStack stack) {
        if (stack.isEmpty()) return false;
        int row = insertionRow(stack, -1);
        if (row < 0) return false;
        insertOne(row, stack);
        setChanged();
        return true;
    }

    /** Where one more of {@code stack} would go ({@code withdrawn} is a row about to lose one item), -1 when no row has room. */
    int insertionRow(ItemStack stack, int withdrawn) {
        int free = -1;
        for (int row = 0; row < ROWS; row++) {
            var held = items.get(row);
            int count = held.getCount() - (row == withdrawn ? 1 : 0);
            if (count == 0) { if (free < 0) free = row; continue; }
            if (count < getMaxStackSize(stack) && ItemStack.isSameItemSameComponents(held, stack)) return row;
        }
        return free;
    }

    private void insertOne(int row, ItemStack stack) {
        if (items.get(row).isEmpty()) items.set(row, stack.copyWithCount(1));
        else items.get(row).grow(1);
    }

    static boolean sells(ItemStack stack) {
        return !stack.isEmpty() && CounterGoods.sells(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    static int batch(ItemStack stack) {
        return stack.isEmpty() ? 0 : CounterGoods.batch(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    /** The front-most row holding a good the counter sells, -1 when there is none. */
    int saleRow() {
        for (int row = ROWS - 1; row >= 0; row--) if (sells(items.get(row))) return row;
        return -1;
    }

    ItemStack counterOffer() {
        int row = saleRow();
        return row < 0 ? ItemStack.EMPTY : items.get(row);
    }

    boolean hasCounterFood(Predicate<ItemStack> seeks) {
        return items.stream().anyMatch(stack -> sells(stack) && seeks.test(stack));
    }

    /** Emeralds lying in the crate's rows: this crate's share of the till. */
    int emeralds() {
        int sum = 0;
        for (var stack : items) if (stack.is(Items.EMERALD)) sum += stack.getCount();
        return sum;
    }

    /** The first row holding an emerald, -1 when the crate has none. */
    int emeraldRow() {
        for (int row = 0; row < ROWS; row++) if (items.get(row).is(Items.EMERALD)) return row;
        return -1;
    }

    /** Places this crate still has for {@code stack}, counting {@code withdrawn} as a row about to lose one item. */
    int room(ItemStack stack, int withdrawn) {
        int[] counts = new int[ROWS];
        boolean[] same = new boolean[ROWS];
        for (int row = 0; row < ROWS; row++) {
            counts[row] = items.get(row).getCount() - (row == withdrawn ? 1 : 0);
            same[row] = ItemStack.isSameItemSameComponents(items.get(row), stack);
        }
        return CounterGoods.room(counts, same, getMaxStackSize(stack));
    }

    /** Puts one of {@code stack} onto {@code row}, a row {@link #rowFor} returned. */
    void insert(int row, ItemStack stack) {
        insertOne(row, stack);
        setChanged();
    }

    /** Puts up to {@code count} of {@code stack} in; returns how many went in. */
    int putUpTo(ItemStack stack, int count) {
        int done = 0;
        while (done < count && put(stack)) done++;
        return done;
    }

    /** Takes one item out of {@code row}. */
    ItemStack takeFrom(int row) { return removeItem(row, 1); }

    /** Takes one item from the front-most occupied row; empty when the crate is empty. */
    public ItemStack takeOne() {
        int row = frontRow();
        return row < 0 ? ItemStack.EMPTY : removeItem(row, 1);
    }

    public ItemStack frontItem() {
        int row = frontRow();
        return row < 0 ? ItemStack.EMPTY : items.get(row);
    }

    private int frontRow() {
        for (int row = ROWS - 1; row >= 0; row--) if (!items.get(row).isEmpty()) return row;
        return -1;
    }

    public boolean stall() { return stall; }

    /** A crate of a staffed counter: sells and buys back through its keeper, no free taking. Never also a theft crate. */
    public boolean counter() { return counter; }

    public boolean theftTarget() { return stall && !frontItem().is(Items.EMERALD); }

    @Override public void setChanged() {
        super.setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override public int getContainerSize() { return ROWS; }
    @Override public int getMaxStackSize() { return PER_ROW; }
    @Override public boolean isEmpty() {
        for (int slot = 0; slot < ROWS; slot++) if (!items.get(slot).isEmpty()) return false;
        return true;
    }
    // Only automation goes through the sided interface; staff can still handle stock directly.
    @Override public int[] getSlotsForFace(Direction side) { return SLOTS; }
    // A counter crate takes goods in (a deposit) but never gives anything out without the keeper.
    @Override public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction side) {
        return !ShopProtection.guarded(level, worldPosition) && (!counter || sells(stack));
    }
    @Override public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return !counter && !ShopProtection.guarded(level, worldPosition);
    }
    @Override public ItemStack getItem(int slot) { return items.get(slot); }
    @Override public boolean canPlaceItem(int slot, ItemStack stack) {
        return getItem(slot).getCount() < getMaxStackSize(stack);
    }
    @Override public ItemStack removeItem(int slot, int count) {
        var out = ContainerHelper.removeItem(items, slot, count);
        if (!out.isEmpty()) setChanged();
        return out;
    }
    @Override public ItemStack removeItemNoUpdate(int slot) { return ContainerHelper.takeItem(items, slot); }
    @Override public void setItem(int slot, ItemStack stack) { stack.limitSize(getMaxStackSize(stack)); items.set(slot, stack); setChanged(); }
    @Override public boolean stillValid(Player player) { return Container.stillValidBlockEntity(this, player); }
    @Override public void clearContent() { items.clear(); setChanged(); }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
        if (stall) tag.putBoolean("Stall", true);
        if (counter) tag.putBoolean("Counter", true);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items.clear();
        ContainerHelper.loadAllItems(tag, items, registries);
        stall = tag.getBoolean("Stall");
        counter = tag.getBoolean("Counter");
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
