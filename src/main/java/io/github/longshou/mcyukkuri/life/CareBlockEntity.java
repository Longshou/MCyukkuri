package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import java.util.UUID;
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

public final class CareBlockEntity extends BlockEntity implements Container {
    public static final int WASTE_CAPACITY = 8;
    private final NonNullList<ItemStack> items = NonNullList.withSize(9, ItemStack.EMPTY);
    private UUID resident;
    private int waste;
    private final java.util.List<ItemStack> wastePortions=new java.util.ArrayList<>();
    private ItemStack displayFood = ItemStack.EMPTY;
    private int visibleFoodCount;
    public CareBlockEntity(BlockPos pos, BlockState state) { super(ModBlocks.CARE.get(), pos, state); }
    public CareBlock.Kind kind() { return ((CareBlock)getBlockState().getBlock()).kind(); }
    public UUID resident() { return resident; }
    public int waste() { return waste; }
    public int capacity(){return ((CareBlock)getBlockState().getBlock()).capacity();}
    public ItemStack displayFood() { return displayFood; }
    public int visibleFoodCount(){return visibleFoodCount;}
    public boolean claim(UUID id) {
        if (kind() != CareBlock.Kind.NEST || resident != null && !resident.equals(id)) return false;
        resident = id; changed(); return true;
    }
    public void release(UUID id) { if (id.equals(resident)) { resident = null; changed(); } }
    public boolean relieve() {
        return relieve(new ItemStack(io.github.longshou.mcyukkuri.registry.ModItems.WASTE.get()));
    }
    public boolean relieve(ItemStack portion) {
        if (kind() != CareBlock.Kind.TOILET || waste >= capacity()) return false;
        if(!portion.is(io.github.longshou.mcyukkuri.registry.ModItems.WASTE.get())||portion.getCount()!=1)return false;
        wastePortions.add(portion.copy());waste++; changed(); return true;
    }
    public int clean() { return cleanItems().size(); }
    /** Transfer one real portion to a nearby worker, preserving its original components. */
    public ItemStack takeWaste() {
        if (kind() != CareBlock.Kind.TOILET || wastePortions.isEmpty()) return ItemStack.EMPTY;
        ItemStack portion = wastePortions.removeFirst(); waste = wastePortions.size(); changed(); return portion;
    }
    public java.util.List<ItemStack> cleanItems(){var result=wastePortions.stream().map(ItemStack::copy).toList();wastePortions.clear();waste=0;changed();return result;}
    public boolean hasFood() {
        return hasFood(stack -> stack.is(YukkuriEntity.FOOD));
    }
    public boolean hasFood(java.util.function.Predicate<ItemStack> accepts) {
        return kind() == CareBlock.Kind.BOWL && items.stream().anyMatch(stack -> !stack.isEmpty() && stack.is(YukkuriEntity.FOOD) && accepts.test(stack));
    }
    /** Called only after the consumer rechecks distance, line of sight and hunger. */
    public ItemStack takeFood() {
        return takeFood(stack -> stack.is(YukkuriEntity.FOOD));
    }
    public ItemStack takeFood(java.util.function.Predicate<ItemStack> accepts) {
        if (kind() != CareBlock.Kind.BOWL) return ItemStack.EMPTY;
        for (int i=0; i<items.size(); i++) if (items.get(i).is(YukkuriEntity.FOOD) && accepts.test(items.get(i))) return removeItem(i, 1);
        return ItemStack.EMPTY;
    }
    public int foodCount() { return items.stream().filter(s -> s.is(YukkuriEntity.FOOD)).mapToInt(ItemStack::getCount).sum(); }
    private void changed() {
        setChanged();
    }
    @Override public void setChanged() {
        super.setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
        tag.putInt("Waste", waste);
        var portions=new net.minecraft.nbt.ListTag();wastePortions.forEach(stack->portions.add(stack.save(registries)));tag.put("WastePortions",portions);
        if (resident != null) tag.putUUID("Resident", resident);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items.clear(); ContainerHelper.loadAllItems(tag, items, registries);
        waste = Math.clamp(tag.getInt("Waste"), 0, capacity());
        wastePortions.clear();for(var raw:tag.getList("WastePortions",net.minecraft.nbt.Tag.TAG_COMPOUND)){
            var stack=ItemStack.parseOptional(registries,(CompoundTag)raw);
            if(stack.is(io.github.longshou.mcyukkuri.registry.ModItems.WASTE.get())&&wastePortions.size()<waste)wastePortions.add(stack.copyWithCount(1));
        }
        while(wastePortions.size()<waste)wastePortions.add(new ItemStack(io.github.longshou.mcyukkuri.registry.ModItems.WASTE.get()));
        resident = tag.hasUUID("Resident") ? tag.getUUID("Resident") : null;
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        // Inventory contents use the vanilla menu; only the visible fill level is broadcast.
        CompoundTag tag = new CompoundTag(); tag.putInt("Waste", waste);
        tag.putInt("FoodCount",foodCount());
        ItemStack food = items.stream().filter(stack -> stack.is(YukkuriEntity.FOOD)).findFirst().orElse(ItemStack.EMPTY);
        if (!food.isEmpty()) tag.put("DisplayFood", food.copyWithCount(1).save(registries));
        return tag;
    }
    @Override public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        waste = tag.getInt("Waste");
        visibleFoodCount=Math.max(0,tag.getInt("FoodCount"));
        displayFood = tag.contains("DisplayFood") ? ItemStack.parseOptional(registries,tag.getCompound("DisplayFood")) : ItemStack.EMPTY;
    }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public void onDataPacket(net.minecraft.network.Connection connection, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        handleUpdateTag(packet.getTag(), registries);
    }
    @Override public int getContainerSize() { return items.size(); }
    @Override public boolean isEmpty() { return items.stream().allMatch(ItemStack::isEmpty); }
    @Override public ItemStack getItem(int slot) { return items.get(slot); }
    @Override public ItemStack removeItem(int slot, int count) {
        ItemStack result = ContainerHelper.removeItem(items, slot, count); if (!result.isEmpty()) changed(); return result;
    }
    @Override public ItemStack removeItemNoUpdate(int slot) { return ContainerHelper.takeItem(items, slot); }
    @Override public void setItem(int slot, ItemStack stack) { items.set(slot, stack); stack.limitSize(getMaxStackSize(stack)); changed(); }
    @Override public boolean stillValid(Player player) { return Container.stillValidBlockEntity(this, player); }
    @Override public void clearContent() { items.clear(); changed(); }
}
