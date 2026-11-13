package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.body.DetachedItems;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import java.util.LinkedHashMap;
import net.minecraft.server.level.ServerLevel;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.motor.surface.BedContainer;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;

public final class CareBlockEntity extends BlockEntity implements Container {
    private final NonNullList<ItemStack> items = NonNullList.withSize(9, ItemStack.EMPTY);
    private final LinkedHashMap<UUID,Integer> residents=new LinkedHashMap<>();
    private final LinkedHashMap<UUID,NestResting.Place> resting=new LinkedHashMap<>();
    public Vec3 restOffset(UUID id){var p=resting.get(id);return p==null?new Vec3(0,.1,0):p.offset();}
    private double wasteVolume;
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
    /** The level floor and visible hollow that resting bodies must fit (sizes in NestResting), around {@link #centre()}. */
    public NestResting.Bed bed(){
        if(getBlockState().getBlock() instanceof PetNestBlock)return PetNestBlock.bed(getBlockState());
        return largeNest()?NestResting.LARGE:NestResting.LEDGE;
    }
    /** The side the entrance (the low lip) faces; the predator's ledge has none and reports north. */
    public Direction entrance(){
        var state=getBlockState();
        return state.getBlock() instanceof PetNestBlock?state.getValue(PetNestBlock.FACING)
                :state.getBlock() instanceof LargeNestBlock?state.getValue(LargeNestBlock.FACING):Direction.NORTH;
    }
    /** How far the level floor reaches from the origin toward the entrance (the doorway's inner edge). */
    public double entranceReach(){return getBlockState().getBlock() instanceof PetNestBlock?PetNestBlock.entranceReach(getBlockState()):bed().box();}
    /** Bed grade (CareBlock.grade): 1 straw or wild, 2 cushion or woven, 3 velvet; 0 for other facilities. */
    public int grade(){return ((CareBlock)getBlockState().getBlock()).grade();}
    public boolean canClaim(YukkuriEntity entity){
        if(kind()!=CareBlock.Kind.NEST||!entity.moldExile().allowsAddress(worldPosition))return false;
        var current=new LinkedHashMap<UUID,Integer>();
        residents.forEach((id,stage)->{
            var person=level instanceof ServerLevel server?FamilyData.get(server).person(id):null;
            if(person==null||person.status==FamilyData.Status.ALIVE)
                current.put(id,level instanceof ServerLevel server&&server.getEntity(id) instanceof YukkuriEntity y?NestOccupancy.stage(y):stage);
        });
        return NestOccupancy.accepts(largeNest(),current,entity);
    }
    public String occupancy(){return "residents="+residents+" large="+largeNest();}
    public double waste() { return wasteVolume; }
    public int capacity(){return ((CareBlock)getBlockState().getBlock()).capacity();}
    public boolean acceptsWaste(ItemStack stack) {
        if (!stack.is(ModItems.WASTE.get())) return false;
        double volume = DetachedItems.volume(stack);
        return volume > 0 && volume <= capacity() - wasteVolume;
    }
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
                var address=actor.life().home();
                if(address!=null&&address.dimension().equals(server.dimension())&&address.pos().equals(worldPosition)){
                    actor.life().releaseHome();
                    // The released child takes its own bed the ordinary way; a free nest nowhere near means it rests beside its parents.
                    if(actor.life().claimNearestNest())GameplayLog.event(actor,"home","released child claimed a free nest nest={} stage={}",actor.life().home().pos(),stage);
                    else GameplayLog.event(actor,"home","released child has no bed nest={} stage={} reason=no_claimable_nest",worldPosition,stage);
                }
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
    /** A place is supported only where that body's whole footprint would stand inside the bed, not merely on a top. */
    private static BiPredicate<UUID,NestResting.Place> standingInside(BedContainer container){
        return (id,p)->container!=null&&container.standsInside(p.x(),p.z(),p.width());
    }
    /** A mother carrying an unfinished brood lies in the middle of her bed (she gives birth there). */
    public static boolean expecting(YukkuriEntity entity){var brood=entity.family().brood();return brood!=null&&!brood.cancelled&&!brood.complete();}
    public void release(UUID id) { if(residents.remove(id)!=null){resting.remove(id);changed();} }
    public boolean relieve(ItemStack portion) {
        if (!(level instanceof ServerLevel server) || kind() != CareBlock.Kind.TOILET || !acceptsWaste(portion)) return false;
        double before = wasteVolume;
        wasteVolume += DetachedItems.volume(portion);
        changed();
        GameplayLog.world(server, "needs", "toilet stored at={} volume={} -> {}", worldPosition, before, wasteVolume);
        return true;
    }
    /** Transfer whole units and keep the fraction in the toilet. */
    public ItemStack takeWaste(int limit) {
        if (!(level instanceof ServerLevel server) || kind() != CareBlock.Kind.TOILET) return ItemStack.EMPTY;
        int whole = Math.min(limit, (int)wasteVolume);
        if (whole == 0) return ItemStack.EMPTY;
        subtractWaste(server, whole);
        return new ItemStack(ModItems.WASTE.get(), whole);
    }
    /** A cleaner scoops up to one unit from a toilet, including a smaller remainder. */
    public ItemStack takeWasteForCleanup() {
        if (!(level instanceof ServerLevel server) || kind() != CareBlock.Kind.TOILET || wasteVolume == 0) return ItemStack.EMPTY;
        double taken = Math.min(1, wasteVolume);
        subtractWaste(server, taken);
        return DetachedItems.waste(taken);
    }
    private void subtractWaste(ServerLevel server, double taken) {
        double before = wasteVolume;
        wasteVolume -= taken;
        changed();
        GameplayLog.world(server, "needs", "toilet taken at={} volume={} -> {}", worldPosition, before, wasteVolume);
    }
    double drainWaste(ServerLevel server) {
        double before = wasteVolume;
        wasteVolume = 0;
        GameplayLog.world(server, "needs", "toilet spilled at={} volume={} -> 0", worldPosition, before);
        return before;
    }
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
    /** The bowl keeps its portion until the actual receiver takes it. A refused spawn or mouth leaves the bowl intact. */
    boolean handFood(Predicate<ItemStack> accepts, Predicate<ItemStack> receiver) {
        if (kind() != CareBlock.Kind.BOWL) return false;
        for (int slot = 0; slot < items.size(); slot++) {
            var source = items.get(slot);
            if (!source.is(YukkuriEntity.FOOD) || !accepts.test(source)) continue;
            if (!receiver.test(source.copyWithCount(1))) return false;
            removeItem(slot, 1);
            return true;
        }
        return false;
    }
    public int foodCount() { return items.stream().filter(s -> s.is(YukkuriEntity.FOOD)).mapToInt(ItemStack::getCount).sum(); }
    private void changed() {
        setChanged();
    }
    /** A pet bed loaded from an older save (or next to blocks changed while it was unloaded) checks its sides once. */
    @Override public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server && getBlockState().getBlock() instanceof PetNestBlock bed) server.scheduleTick(worldPosition, bed, 1);
    }
    @Override public void setChanged() {
        super.setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
        tag.putDouble("Waste", wasteVolume);
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
        wasteVolume = Poops.loadVolume(tag, "Waste", "WastePortions", registries);
        NestResting.load(tag.getList("RestingPlaces",Tag.TAG_COMPOUND),resting);
        String saved=tag.getString("Pattern");pattern=CareStyles.valid(blockId(),saved)?saved:"";
        residents.clear();if(tag.hasUUID("Resident"))residents.put(tag.getUUID("Resident"),tag.contains("ResidentStage")?Math.clamp(tag.getInt("ResidentStage"),0,2):2);
        for(var raw:tag.getList("NestGuests",Tag.TAG_COMPOUND)){var guest=(CompoundTag)raw;if(guest.hasUUID("Id")&&residents.size()<6)residents.put(guest.getUUID("Id"),Math.clamp(guest.getInt("Stage"),0,2));}
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        // Inventory contents use the vanilla menu; only the visible fill level is broadcast.
        CompoundTag tag = new CompoundTag();
        tag.putDouble("Waste", wasteVolume);
        tag.putInt("FoodCount",foodCount());
        if(!pattern.isEmpty())tag.putString("Pattern",pattern);
        ItemStack food = items.stream().filter(stack -> stack.is(YukkuriEntity.FOOD)).findFirst().orElse(ItemStack.EMPTY);
        if (!food.isEmpty()) tag.put("DisplayFood", food.copyWithCount(1).save(registries));
        return tag;
    }
    @Override public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        wasteVolume = tag.getDouble("Waste");
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
