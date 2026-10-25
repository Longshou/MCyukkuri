package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The droppings lying on one ground cell: up to {@link Poops#CAPACITY} real portions, each the very waste stack its
 * body produced (source, variant and scale stay in the stack's custom data). The block state's tier follows the count.
 */
public final class PoopBlockEntity extends BlockEntity {
    private final List<ItemStack> portions = new ArrayList<>();
    public PoopBlockEntity(BlockPos pos, BlockState state) { super(ModBlocks.POOP_ENTITY.get(), pos, state); }

    public int count() { return portions.size(); }
    public boolean full() { return portions.size() >= Poops.CAPACITY; }
    public List<ItemStack> portions() { return List.copyOf(portions); }

    /** Adds one portion; the caller owns and debits the source stack. */
    public boolean add(ItemStack portion) {
        if (level == null || level.isClientSide || isRemoved() || full() || !portion.is(ModItems.WASTE.get()) || portion.getCount() != 1) return false;
        portions.add(portion.copy());
        changed();
        return true;
    }
    /** The newest portion, or empty. The block disappears with its last portion. */
    public ItemStack takeOne() {
        if (level == null || level.isClientSide || isRemoved() || portions.isEmpty()) return ItemStack.EMPTY;
        var taken = portions.removeLast();
        if (portions.isEmpty()) level.removeBlock(worldPosition, false); else changed();
        return taken;
    }
    /** Every portion, leaving the block empty without removing it (used by block removal, which drops them). */
    public List<ItemStack> drain() {
        var all = new ArrayList<>(portions);
        portions.clear();
        setChanged();
        return all;
    }
    private void changed() {
        setChanged();
        var state = getBlockState();
        int tier = Poops.tier(portions.size());
        if (state.getBlock() instanceof PoopBlock && state.getValue(PoopBlock.TIER) != tier) {
            level.setBlock(worldPosition, state.setValue(PoopBlock.TIER, tier), 3);
        } else level.sendBlockUpdated(worldPosition, state, state, 3);
    }
    @Override public void setRemoved() {
        super.setRemoved();
        Poops.forget(this);
    }
    @Override public void onChunkUnloaded() {
        super.onChunkUnloaded();
        Poops.forget(this);
    }
    @Override public void onLoad() {
        super.onLoad();
        Poops.remember(this);
    }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Version", 1);
        var list = new ListTag();
        portions.forEach(stack -> list.add(stack.save(registries)));
        tag.put("Portions", list);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        portions.clear();
        for (var raw : tag.getList("Portions", Tag.TAG_COMPOUND)) {
            var stack = ItemStack.parseOptional(registries, (CompoundTag)raw);
            if (stack.is(ModItems.WASTE.get()) && portions.size() < Poops.CAPACITY) portions.add(stack.copyWithCount(1));
        }
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
