package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import java.util.LinkedHashMap;
import net.minecraft.server.level.ServerLevel;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.motor.surface.BedContainer;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;

public final class CareBlockEntity extends BlockEntity implements Container {
    public static final int WASTE_CAPACITY = 8;
    private final NonNullList<ItemStack> items = NonNullList.withSize(9, ItemStack.EMPTY);
    private final LinkedHashMap<UUID,Integer> residents=new LinkedHashMap<>();
    private final LinkedHashMap<UUID,NestResting.Place> resting=new LinkedHashMap<>();
    public Vec3 restOffset(UUID id){var p=resting.get(id);return p==null?new Vec3(0,.1,0):p.offset();}
    private int waste;
    private final List<ItemStack> wastePortions=new ArrayList<>();
    private ItemStack displayFood = ItemStack.EMPTY;
    private int visibleFoodCount;
    /** Dye-selected look (CareStyles); "" is the default. Saved and synced with the block. */
    private String pattern="";
    public CareBlockEntity(BlockPos pos, BlockState state) { super(ModBlocks.CARE.get(), pos, state); }
    public CareBlock.Kind kind() { return ((CareBlock)getBlockState().getBlock()).kind(); }
    public UUID resident() { return residents.keySet().stream().findFirst().orElse(null); }
    public boolean owns(UUID id){return residents.containsKey(id);}
    public List<UUID> residentIds(){return List.copyOf(residents.keySet());}
    public boolean largeNest(){return ((CareBlock)getBlockState().getBlock()).style().startsWith("large_");}
    /** Ground centre of the bed: this block's centre, or for a 2x2 nest the shared corner of its four cells. */
    public Vec3 centre(){return largeNest()?Vec3.atLowerCornerOf(worldPosition).add(1,0,1):Vec3.atBottomCenterOf(worldPosition);}
    /** The level floor and visible hollow that resting bodies must fit (sizes in NestResting). */
    public NestResting.Bed bed(){return largeNest()?NestResting.LARGE:getBlockState().is(ModBlocks.PREDATOR_NEST.get())?NestResting.LEDGE:NestResting.CUSHION;}
    public boolean canClaim(YukkuriEntity entity){
        var current=new LinkedHashMap<UUID,Integer>();
        residents.forEach((id,stage)->{
            var person=level instanceof ServerLevel server?FamilyData.get(server).person(id):null;
            if(person==null||person.status==FamilyData.Status.ALIVE)
                current.put(id,level instanceof ServerLevel server&&server.getEntity(id) instanceof YukkuriEntity y?NestOccupancy.stage(y):stage);
        });
        return kind()==CareBlock.Kind.NEST&&NestOccupancy.accepts(largeNest(),current,entity);
    }
    public String occupancy(){return "residents="+residents+" large="+largeNest();}
    public int waste() { return waste; }
    public int capacity(){return ((CareBlock)getBlockState().getBlock()).capacity();}
    public ItemStack displayFood() { return displayFood; }
    public int visibleFoodCount(){return visibleFoodCount;}
    public String pattern(){return pattern;}
    public void setPattern(String value){if(!pattern.equals(value)){pattern=value;changed();}}
    private String blockId(){return BuiltInRegistries.BLOCK.getKey(getBlockState().getBlock()).getPath();}
    private void reconcileGrowth(ServerLevel server){
        // Resolve the member who changed stage first, rather than ejecting whichever parent ticks first.
        for(UUID member:List.copyOf(residents.keySet()))if(server.getEntity(member) instanceof YukkuriEntity actor){
            int stage=NestOccupancy.stage(actor);Integer previous=residents.get(member);
            if(previous==null||previous==stage)continue;
            if(NestOccupancy.accepts(largeNest(),residents,actor)){residents.put(member,stage);changed();}
            else {
                release(member);
                GameplayLog.event(actor,"home","growth released shared nest nest={} oldStage={} stage={}",worldPosition,previous,stage);
                var address=actor.life().home();if(address!=null&&address.dimension().equals(server.dimension())&&address.pos().equals(worldPosition))actor.life().releaseHome();
            }
        }
    }
    public boolean claim(UUID id) {
        if(!(level instanceof ServerLevel server)||!(server.getEntity(id) instanceof YukkuriEntity entity))return false;
        reconcileGrowth(server);
        if(!canClaim(entity))return false;
        residents.keySet().removeIf(member->{var person=FamilyData.get(server).person(member);return person!=null&&person.status!=FamilyData.Status.ALIVE;});
        resting.keySet().retainAll(residents.keySet());
        var before=new LinkedHashMap<>(resting);
        var container=BedContainer.of(entity,worldPosition);
        var place=NestResting.choose(entity,bed(),resting,standingInside(container),expecting(entity));
        if(place==null){GameplayLog.limited(entity,"home","nest_no_body_space",200,"nest claim refused reason=no_supported_body_space nest={} block={} bodyWidth={} occupants={}",
                worldPosition,blockId(),entity.getBbWidth(),residents.size());return false;}
        resting.put(id,place);if(!resting.equals(before))changed();
        int stage=NestOccupancy.stage(entity);
        Integer previous=residents.put(id,stage);if(previous==null||previous!=stage)changed();return true;
    }
    /** Where this body would lie on the bed now without moving anyone (its kept place, else a free one); null when no free supported spot exists. */
    public Vec3 freeSpot(YukkuriEntity baby){
        if(!(level instanceof ServerLevel server)||kind()!=CareBlock.Kind.NEST)return null;
        var trial=new LinkedHashMap<>(resting);
        var container=BedContainer.of(baby,worldPosition);
        var place=NestResting.choose(baby,bed(),trial,standingInside(container),false);
        if(place==null)return null;
        for(var entry:resting.entrySet())if(!entry.getKey().equals(baby.getUUID())&&!entry.getValue().equals(trial.get(entry.getKey())))return null;
        return centre().add(place.x(),0,place.z());
    }
    /** A place is supported only where that body's whole footprint would stand inside the bed (spec 7), not merely on a top. */
    private static BiPredicate<UUID,NestResting.Place> standingInside(BedContainer container){
        return (id,p)->container!=null&&container.standsInside(p.x(),p.z(),p.width());
    }
    /** A mother carrying an unfinished brood lies in the middle of her bed (she gives birth there). */
    public static boolean expecting(YukkuriEntity entity){var brood=entity.family().brood();return brood!=null&&!brood.cancelled&&!brood.complete();}
    public void release(UUID id) { if(residents.remove(id)!=null){resting.remove(id);changed();} }
    public boolean relieve() {
        return relieve(new ItemStack(ModItems.WASTE.get()));
    }
    public boolean relieve(ItemStack portion) {
        if (kind() != CareBlock.Kind.TOILET || waste >= capacity()) return false;
        if(!portion.is(ModItems.WASTE.get())||portion.getCount()!=1)return false;
        wastePortions.add(portion.copy());waste++; changed(); return true;
    }
    public int clean() { return cleanItems().size(); }
    /** Transfer one real portion to a nearby worker, preserving its original components. */
    public ItemStack takeWaste() {
        if (kind() != CareBlock.Kind.TOILET || wastePortions.isEmpty()) return ItemStack.EMPTY;
        ItemStack portion = wastePortions.removeFirst(); waste = wastePortions.size(); changed(); return portion;
    }
    public List<ItemStack> cleanItems(){var result=wastePortions.stream().map(ItemStack::copy).toList();wastePortions.clear();waste=0;changed();return result;}
    public boolean hasFood() {
        return hasFood(stack -> stack.is(YukkuriEntity.FOOD));
    }
    public boolean hasFood(Predicate<ItemStack> accepts) {
        return kind() == CareBlock.Kind.BOWL && items.stream().anyMatch(stack -> !stack.isEmpty() && stack.is(YukkuriEntity.FOOD) && accepts.test(stack));
    }
    /** Called only after the consumer rechecks distance, line of sight and hunger. */
    public ItemStack takeFood() {
        return takeFood(stack -> stack.is(YukkuriEntity.FOOD));
    }
    public ItemStack takeFood(Predicate<ItemStack> accepts) {
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
        var portions=new ListTag();wastePortions.forEach(stack->portions.add(stack.save(registries)));tag.put("WastePortions",portions);
        if (resident() != null) {tag.putUUID("Resident", resident());tag.putInt("ResidentStage",residents.get(resident()));}
        tag.put("RestingPlaces",NestResting.save(resting));
        if(!pattern.isEmpty())tag.putString("Pattern",pattern);
        var guests=new ListTag();for(var entry:residents.entrySet())if(!entry.getKey().equals(resident())){
            var guest=new CompoundTag();guest.putUUID("Id",entry.getKey());guest.putInt("Stage",entry.getValue());guests.add(guest);
        }tag.put("NestGuests",guests);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items.clear(); ContainerHelper.loadAllItems(tag, items, registries);
        waste = Math.clamp(tag.getInt("Waste"), 0, capacity());
        wastePortions.clear();for(var raw:tag.getList("WastePortions",Tag.TAG_COMPOUND)){
            var stack=ItemStack.parseOptional(registries,(CompoundTag)raw);
            if(stack.is(ModItems.WASTE.get())&&wastePortions.size()<waste)wastePortions.add(stack.copyWithCount(1));
        }
        while(wastePortions.size()<waste)wastePortions.add(new ItemStack(ModItems.WASTE.get()));
        NestResting.load(tag.getList("RestingPlaces",Tag.TAG_COMPOUND),resting);
        String saved=tag.getString("Pattern");pattern=CareStyles.valid(blockId(),saved)?saved:"";
        residents.clear();if(tag.hasUUID("Resident"))residents.put(tag.getUUID("Resident"),tag.contains("ResidentStage")?Math.clamp(tag.getInt("ResidentStage"),0,2):2);
        for(var raw:tag.getList("NestGuests",Tag.TAG_COMPOUND)){var guest=(CompoundTag)raw;if(guest.hasUUID("Id")&&residents.size()<6)residents.put(guest.getUUID("Id"),Math.clamp(guest.getInt("Stage"),0,2));}
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        // Inventory contents use the vanilla menu; only the visible fill level is broadcast.
        CompoundTag tag = new CompoundTag(); tag.putInt("Waste", waste);
        tag.putInt("FoodCount",foodCount());
        if(!pattern.isEmpty())tag.putString("Pattern",pattern);
        ItemStack food = items.stream().filter(stack -> stack.is(YukkuriEntity.FOOD)).findFirst().orElse(ItemStack.EMPTY);
        if (!food.isEmpty()) tag.put("DisplayFood", food.copyWithCount(1).save(registries));
        return tag;
    }
    @Override public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        waste = tag.getInt("Waste");
        visibleFoodCount=Math.max(0,tag.getInt("FoodCount"));
        pattern=tag.getString("Pattern");
        displayFood = tag.contains("DisplayFood") ? ItemStack.parseOptional(registries,tag.getCompound("DisplayFood")) : ItemStack.EMPTY;
    }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
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
