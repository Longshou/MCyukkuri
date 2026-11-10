package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.body.DetachedItems;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.server.level.ServerLevel;
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
 * Ground stock: the original stacks alone own the volume, source and variant. Tier follows their summed volume.
 */
public final class PoopBlockEntity extends BlockEntity {
    private final List<ItemStack> portions = new ArrayList<>();
    public PoopBlockEntity(BlockPos pos, BlockState state) { super(ModBlocks.POOP_ENTITY.get(), pos, state); }

    public double count() { return portions.stream().mapToDouble(DetachedItems::volume).sum(); }
    public boolean full() { return count() >= Poops.CAPACITY; }
    public List<ItemStack> portions() { return List.copyOf(portions); }

    /** Adds one portion; the caller owns and debits the source stack. */
    public boolean add(ItemStack portion) {
        if (level == null || level.isClientSide || isRemoved() || count()+DetachedItems.volume(portion)>Poops.CAPACITY || !portion.is(ModItems.WASTE.get()) || portion.getCount() != 1) return false;
        portions.add(portion.copy());
        changed();
        return true;
    }
    /** The newest portion, or empty. The block disappears with its last portion. */
    public ItemStack takeOne() {
        if (level == null || level.isClientSide || isRemoved() || portions.isEmpty()) return ItemStack.EMPTY;
        double before=count();var taken = portions.removeLast();
        GameplayLog.world((ServerLevel)level,"needs","poop taken at={} volume={} -> {}",worldPosition,before,count());
        if (portions.isEmpty()) level.removeBlock(worldPosition, false); else changed();
        return taken;
    }
    /** Every portion, leaving the block empty without removing it (used by block removal, which drops them). */
    public List<ItemStack> drain() {
        double before=count();
        var all = new ArrayList<>(portions);
        portions.clear();
        if(level instanceof ServerLevel server && before>0)GameplayLog.world(server,"needs","poop gathered at={} volume={} -> 0",worldPosition,before);
        setChanged();
        return all;
    }
    private void changed() {
        setChanged();
        var state = getBlockState();
        int tier = Poops.tier(count());
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
            if (stack.is(ModItems.WASTE.get())) portions.add(stack);
        }
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
