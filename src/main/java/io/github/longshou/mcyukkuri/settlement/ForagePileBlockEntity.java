package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The wild food-store pile (储粮堆): nine real item slots in one ground cell, the physical stock of a nest.
 * Gatherers add one portion at a time by mouth contact and residents take portions the same way. The heap
 * drawn by the client follows the real count. No ticker: decay and refilling belong to the owning shelter.
 */
public final class ForagePileBlockEntity extends BlockEntity {
    public static final int SLOTS = 9;
    /** Real stock thresholds for the four drawn heaps; below one portion only the leaf bed shows. */
    private static final int[] STAGE_FROM = {1, 9, 33, 97};
    private final NonNullList<ItemStack> stock = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    /** Horizontal side of the pile cell on which animals stand to touch it with their mouths. */
    private Direction approach = Direction.SOUTH;
    /** The shelter that keeps this pile; an unowned or orphaned pile can be adopted by the next nest beside it. */
    private BlockPos owner;
    // One mouth at a time: transient, never saved. Others wait at a distance instead of piling onto the same spot.
    private UUID user;
    private long userUntil;

    public ForagePileBlockEntity(BlockPos pos, BlockState state) { super(ModBlocks.FORAGE_PILE_ENTITY.get(), pos, state); }

    public int food() { return StoreInventory.food(stock); }
    public int stage() {
        int food = food(), stage = 0;
        for (int i = 0; i < STAGE_FROM.length; i++) if (food >= STAGE_FROM[i]) stage = i + 1;
        return stage;
    }
    /** Capacity follows the actual item in each occupied slot; empty slots count as ordinary forage. */
    public int capacity() { return StoreInventory.capacity(stock); }
    public int foodSpace(ItemStack incoming) { return StoreInventory.foodSpace(stock, incoming); }
    /** Transfer by copy, returning any unaccepted remainder; the caller keeps ownership of that remainder. */
    public ItemStack returnFood(ItemStack stack) {
        var remaining = StoreInventory.returnFood(stock, stack);
        if (remaining.getCount() != stack.getCount()) changed();
        return remaining;
    }
    /** Preview without reserving: a copy of the first portion the predicate accepts. */
    public ItemStack peek(Predicate<ItemStack> wanted) { return StoreInventory.peek(stock, wanted); }
    public ItemStack take(Predicate<ItemStack> wanted) {
        var item = StoreInventory.take(stock, wanted);
        if (!item.isEmpty()) changed();
        return item;
    }
    /** One existing portion leaves the pile; used by raiders, players and decay. */
    public ItemStack raid() { return take(stack -> true); }
    /** Every real stack, removed; used when the pile or its nest is dismantled. */
    public List<ItemStack> drain() {
        var result = StoreInventory.drain(stock);
        if (!result.isEmpty()) setChanged();
        return result;
    }

    public Direction approach() { return approach; }
    public void setApproach(Direction side) {
        if (side.getAxis().isHorizontal() && side != approach) { approach = side; changed(); }
    }
    public BlockPos owner() { return owner; }
    public void setOwner(BlockPos shelter) { owner = shelter == null ? null : shelter.immutable(); changed(); }
    /** True when no living shelter other than {@code self} still claims this pile. */
    public boolean ownerFree(ServerLevel server, BlockPos self) {
        if (owner == null || owner.equals(self)) return true;
        return server.hasChunkAt(owner) && !(server.getBlockEntity(owner) instanceof ShelterBlockEntity);
    }
    public BlockPos standCell() { return worldPosition.relative(approach); }
    /** Where an animal's feet rest so that its mouth meets the edge of the heap. */
    public Vec3 standPoint(YukkuriEntity entity) {
        double reach = .42 + entity.getBbWidth() * .5; // the heap reaches about .36 from the centre; the mouth stops at its edge
        return new Vec3(worldPosition.getX() + .5 + approach.getStepX() * reach, worldPosition.getY(), worldPosition.getZ() + .5 + approach.getStepZ() * reach);
    }
    public Vec3 touchPoint() {
        return new Vec3(worldPosition.getX() + .5 + approach.getStepX() * .36, worldPosition.getY() + .14, worldPosition.getZ() + .5 + approach.getStepZ() * .36);
    }
    /** Holds the single mouth slot for ten ticks; the current holder refreshes it every tick it is in contact range. */
    public boolean reserve(UUID id, long now) {
        if (user != null && !user.equals(id) && userUntil > now) return false;
        user = id; userUntil = now + 10; return true;
    }

    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Version", 1); tag.putInt("Approach", approach.get2DDataValue());
        if (owner != null) tag.putLong("Owner", owner.asLong());
        ContainerHelper.saveAllItems(tag, stock, registries);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        stock.clear(); ContainerHelper.loadAllItems(tag, stock, registries);
        approach = Direction.from2DDataValue(Math.floorMod(tag.getInt("Approach"), 4));
        owner = tag.contains("Owner") ? BlockPos.of(tag.getLong("Owner")) : null;
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
