package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.body.DetachedItems;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.server.level.ServerLevel;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Ground stock: one volume per cell; the client draws that volume.
 */
public final class PoopBlockEntity extends BlockEntity {
    private double volume;
    public PoopBlockEntity(BlockPos pos, BlockState state) { super(ModBlocks.POOP_ENTITY.get(), pos, state); }

    public double count() { return volume; }
    public boolean full() { return volume >= Poops.CAPACITY; }

    /** The caller owns and debits the accepted stack. */
    public boolean add(ItemStack stack) {
        if (!(level instanceof ServerLevel) || isRemoved() || !stack.is(ModItems.WASTE.get())) return false;
        double added = DetachedItems.volume(stack);
        if (!(added > 0 && added <= Poops.CAPACITY - volume)) return false;
        volume += added;
        changed();
        return true;
    }
    /** Collection leaves the fractional remainder on the ground. */
    public ItemStack takeWhole(int limit) {
        if (!(level instanceof ServerLevel server) || isRemoved()) return ItemStack.EMPTY;
        int whole = Math.min(limit, (int)volume);
        if (whole == 0) return ItemStack.EMPTY;
        subtract(server, whole, "taken");
        return new ItemStack(ModItems.WASTE.get(), whole);
    }

    /** A cleaner carries the heap as one load, including its fractional volume. */
    public ItemStack takeForCleanup() {
        if (!(level instanceof ServerLevel server) || isRemoved() || volume == 0) return ItemStack.EMPTY;
        var load = DetachedItems.waste(volume);
        subtract(server, volume, "taken");
        return load;
    }

    /** Eating at the heap can use a fractional bite. The mouth must accept the bite before the heap is debited. */
    public boolean eat(Predicate<ItemStack> accept) {
        if (!(level instanceof ServerLevel server) || isRemoved() || volume == 0) return false;
        double bite = Math.min(1, volume);
        if (!accept.test(DetachedItems.waste(bite))) return false;
        subtract(server, bite, "eaten");
        return true;
    }

    void decay(ServerLevel server) {
        if (volume == 0) return;
        double loss = Math.min(.75 * volume, .22 * Math.sqrt(volume));
        if (volume - loss < DetachedItems.MIN_WASTE_VOLUME / 4) loss = volume;
        subtract(server, loss, "decayed");
    }

    private void subtract(ServerLevel server, double removed, String action) {
        double before = volume;
        volume -= removed;
        GameplayLog.world(server, "needs", "poop {} at={} volume={} -> {}", action, worldPosition, before, volume);
        if (volume == 0) server.removeBlock(worldPosition, false); else changed();
    }

    /** Block removal transfers all remaining stock to the spill. */
    double drain(ServerLevel server) {
        double before = volume;
        volume = 0;
        if (before > 0) GameplayLog.world(server, "needs", "poop spilled at={} volume={} -> 0", worldPosition, before);
        setChanged();
        return before;
    }
    private void changed() {
        setChanged();
        var state = getBlockState();
        level.sendBlockUpdated(worldPosition, state, state, 3);
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
        tag.putDouble("Volume", volume);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        volume = Poops.loadVolume(tag, "Volume", "Portions", registries);
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
