package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Finite household stores, assigned sleeping places and weathering, advanced only in loaded chunks. */
public final class ShelterBlockEntity extends BlockEntity {
    private final NonNullList<ItemStack> stock=NonNullList.withSize(9,ItemStack.EMPTY);
    private final Map<UUID,Integer> residents=new LinkedHashMap<>();
    private int condition=100, moisture, lining, dirt, ticks;
    private boolean dismantling;
    private int displayFood;
    private List<BlockPos> naturalBeds=List.of();
    private BlockPos naturalEntrance;
    private int branches;
    public ShelterBlockEntity(BlockPos pos,BlockState state) { super(ModBlocks.SHELTER_ENTITY.get(),pos,state); }
    public ShelterBlock.Kind kind() { return ((ShelterBlock)getBlockState().getBlock()).kind(); }
    public int condition() { return condition; }
    public int moisture() { return moisture; }
    public int lining() { return lining; }
    public int dirt() { return dirt; }
    public int occupants() { return residents.size(); }
    public List<UUID> residentIds() { return List.copyOf(residents.keySet()); }
    /** Autumn regions let households pile up more than the usual 32 portions. */
    public int foodLimit() { return level != null && Seasons.on(level, worldPosition, Season.AUTUMN) ? 48 : 32; }
    public int capacity() { return kind()==ShelterBlock.Kind.BURROW?naturalBeds.size():kind().capacity; }
    public int food() { return level!=null&&level.isClientSide ? displayFood : stock.stream().mapToInt(ItemStack::getCount).sum(); }
    public Direction facing() { return getBlockState().getValue(ShelterBlock.FACING); }
    public BlockPos entrance() { return kind()==ShelterBlock.Kind.BURROW&&naturalEntrance!=null?naturalEntrance:worldPosition.relative(facing(),kind().radius+1); }
    public AABB interior() {
        var bounds=new AABB(worldPosition);for(var pos:beds())bounds=bounds.minmax(new AABB(pos));return bounds.expandTowards(0,.5,0);
    }
    public boolean contains(BlockPos pos) { return beds().contains(pos); }
    public boolean owns(UUID id) { return residents.containsKey(id); }
    public List<BlockPos> beds() { return kind()==ShelterBlock.Kind.BURROW?naturalBeds:List.of(worldPosition); }
    public int branches() { return branches; }
    public void setNaturalSite(NaturalShelter.Site site) { naturalBeds=site==null?List.of():site.beds();naturalEntrance=site==null?null:site.entrance();changed(); }
    public boolean rainSheltered(BlockPos pos) {
        return kind()==ShelterBlock.Kind.CARDBOARD?sound()&&moisture<90:
                level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,pos.getX(),pos.getZ())>pos.getY();
    }
    public BlockPos bed(UUID id) { Integer slot=residents.get(id);return slot==null||slot>=beds().size()?null:beds().get(slot); }
    /** The spawner calls this only after completing the real family relationship records. */
    public boolean claim(YukkuriEntity entity) {
        if(owns(entity.getUUID()))return true;
        if(!(level instanceof ServerLevel server)||!sound()||entity.getVariant().isHouse())return false;
        var families=FamilyData.get(server);
        var person=families.person(entity.getUUID());
        boolean reservedChild=person!=null&&person.status==FamilyData.Status.UNBORN&&residents.containsKey(person.mother);
        int incoming=families.reservedForResidents(entity.getUUID()::equals);
        if(residents.size()+families.reservedForResidents(residents::containsKey)+incoming+(reservedChild?0:1)>capacity())return false;
        if(!residents.isEmpty() && residents.keySet().stream().noneMatch(id->id.equals(entity.family().partner())
                ||FamilyData.get((ServerLevel)level).related(id,entity.getUUID())))return false;
        for(int slot=0;slot<capacity();slot++)if(!residents.containsValue(slot)){residents.put(entity.getUUID(),slot);changed();return true;}
        return false;
    }
    public void release(UUID id) { if(residents.remove(id)!=null)changed(); }
    public boolean complete() {
        if(level==null)return false;
        for(var pos:ShelterPlacement.positions(worldPosition,kind())) {
            if(!level.hasChunkAt(pos))return false;
            var state=level.getBlockState(pos);
            if(!state.is(getBlockState().getBlock())||!ShelterBlock.root(pos,state).equals(worldPosition)||state.getValue(ShelterBlock.FACING)!=facing())return false;
        }
        return true;
    }
    public boolean flooded() {
        if(level==null)return false;
        if(!level.getFluidState(entrance()).isEmpty()||!level.getFluidState(worldPosition.below()).isEmpty())return true;
        return beds().stream().anyMatch(pos->!level.getFluidState(pos).isEmpty());
    }
    public boolean sound() {
        return (kind()==ShelterBlock.Kind.BURROW||condition>0) && complete() && !beds().isEmpty() && !flooded()
                && beds().stream().allMatch(pos->kind()==ShelterBlock.Kind.BURROW?NaturalShelter.enclosed(level,pos):NaturalShelter.stand(level,pos));
    }
    public boolean usable(YukkuriEntity entity) {
        var bed=bed(entity.getUUID());
        return bed!=null&&sound()&&moisture<85&&dirt<90&&entity.life().dryStand(bed)&&entity.life().dryStand(entrance());
    }
    public boolean insulated() { return lining>=3&&moisture<55&&(kind()==ShelterBlock.Kind.BURROW||condition>=40); }
    public boolean suitableForYoung(int children) {
        if(!sound()||(kind()==ShelterBlock.Kind.CARDBOARD&&condition<40)||moisture>=60||dirt>=65||lining<2)return false;
        int reserved=FamilyData.get((ServerLevel)level).reservedForResidents(residents::containsKey);
        return residents.size()+reserved+children<=capacity();
    }
    public static boolean bedding(ItemStack stack) { return stack.is(net.minecraft.tags.ItemTags.LEAVES)||stack.is(Items.PAPER)||stack.is(Items.SHORT_GRASS)||stack.is(Items.WHEAT); }
    public boolean repairMaterial(ItemStack stack) { return kind()==ShelterBlock.Kind.BURROW&&stack.is(Items.STICK); }
    public boolean needsMaterial(ItemStack stack) { return (branches<3||condition<95)&&repairMaterial(stack)||(lining<4||dirt>=30||moisture>=60)&&bedding(stack); }
    public boolean accepts(ItemStack stack) { return needsMaterial(stack)||stack.is(YukkuriEntity.FOOD)&&food()<foodLimit(); }
    public boolean deposit(ItemStack stack) {
        if(stack.getCount()!=1)return false;
        if((branches<3||condition<95)&&repairMaterial(stack)){branches=Math.min(3,branches+1);condition=Math.min(100,condition+15);changed();return true;}
        if((lining<4||dirt>=30||moisture>=60)&&bedding(stack)){
            if(lining<4)lining++;dirt=Math.max(0,dirt-30);moisture=Math.max(0,moisture-10);changed();return true;
        }
        if(!stack.is(YukkuriEntity.FOOD)||food()>=foodLimit())return false;
        for(int i=0;i<stock.size();i++)if(stock.get(i).isEmpty()||ItemStack.isSameItemSameComponents(stock.get(i),stack)&&stock.get(i).getCount()<16){
            if(stock.get(i).isEmpty())stock.set(i,stack.copy());else stock.get(i).grow(1);changed();return true;
        }
        return false;
    }
    public ItemStack takeFood(YukkuriEntity entity) {
        if(!owns(entity.getUUID()))return ItemStack.EMPTY;
        for(int i=0;i<stock.size();i++)if(!stock.get(i).isEmpty()&&entity.life().acceptsFood(stock.get(i))){var item=stock.get(i).split(1);changed();return item;}
        return ItemStack.EMPTY;
    }
    public void returnFood(ItemStack stack) {
        for(int i=0;i<stock.size();i++)if(stock.get(i).isEmpty()||ItemStack.isSameItemSameComponents(stock.get(i),stack)){
            if(stock.get(i).isEmpty())stock.set(i,stack.copy());else stock.get(i).grow(stack.getCount());changed();return;
        }
        Containers.dropItemStack(level,entrance().getX()+.5,entrance().getY(),entrance().getZ()+.5,stack);
    }
    public void initializeEncounter(int count) { lining=4;branches=kind()==ShelterBlock.Kind.BURROW?2:0;stock.set(0,new ItemStack(Items.SHORT_GRASS,Math.min(12,Math.max(2,count*2))));changed(); }
    public List<ItemEntity> wasteInside() {
        return level.getEntitiesOfClass(ItemEntity.class,interior(),item->item.isAlive()&&contains(item.blockPosition())&&item.getItem().is(ModItems.WASTE.get()));
    }
    public void cleaned() { dirt=Math.max(0,dirt-30);changed(); }
    public void clean(Player player) {
        for(var waste:wasteInside()) {
            var stack=waste.getItem().copy();player.getInventory().add(stack);
            if(stack.isEmpty())waste.discard();else waste.setItem(stack);
        }
        dirt=0;moisture=Math.max(0,moisture-10);changed();
        player.displayClientMessage(Component.translatable("shelter.mcyukkuri.cleaned"),true);
    }
    public void tick() {
        if(level==null||level.isClientSide||dismantling)return;
        ticks++;if(ticks%100!=0)return;
        if(kind()==ShelterBlock.Kind.BURROW&&naturalBeds.isEmpty()){dismantle();return;}
        var families=FamilyData.get((ServerLevel)level);
        residents.keySet().removeIf(id->{var p=families.person(id);return p!=null&&(p.status==FamilyData.Status.DEAD||p.status==FamilyData.Status.REMOVED);});
        if(!complete()) {
            if(ShelterPlacement.positions(worldPosition,kind()).stream().allMatch(level::hasChunkAt))dismantle();
            return;
        }
        boolean rain=kind()==ShelterBlock.Kind.BURROW?beds().stream().anyMatch(pos->level.isRainingAt(pos.above())):level.isRainingAt(worldPosition.above(kind().height));
        if(flooded())moisture=Math.min(100,moisture+20);
        else if(rain)moisture=Math.min(100,moisture+(kind()==ShelterBlock.Kind.CARDBOARD?7:8));
        else moisture=Math.max(0,moisture-4);
        int waste=wasteInside().stream().mapToInt(item->item.getItem().getCount()).sum();
        if(waste>0)dirt=Math.min(100,dirt+Math.min(10,waste*2));
        else if(ticks%400==0&&residents.size()>0)dirt=Math.min(100,dirt+1);
        if(moisture>=60&&ticks%400==0)condition=Math.max(0,condition-(kind()==ShelterBlock.Kind.CARDBOARD?3:1));
        if(moisture>=75&&lining>0&&ticks%1200==0)lining--;
        // Stores keep longer in autumn and winter air and spoil faster in summer heat. [original]
        int rot=moisture>=60?1200:6000;
        if(level instanceof ServerLevel&&Seasons.enabled()) {
            var season=Seasons.at(level,worldPosition);
            if(season==Season.AUTUMN||season==Season.WINTER)rot=rot*3/2;else if(season==Season.SUMMER)rot=rot/2;
        }
        if(ticks%rot==0) {
            for(var stack:stock)if(!stack.isEmpty()){stack.shrink(1);dirt=Math.min(100,dirt+3);break;}
        }
        changed();
    }
    public Component description() {
        if(kind()==ShelterBlock.Kind.BURROW)return Component.translatable("shelter.mcyukkuri.inspect_natural",occupants(),capacity(),branches,condition,moisture,lining,food(),dirt,
                Component.translatable("shelter.mcyukkuri."+(flooded()?"flooded":rainSheltered(worldPosition)?"cave":"pit")));
        return Component.translatable("shelter.mcyukkuri.inspect",Component.translatable("block.mcyukkuri."+kind().id),occupants(),capacity(),condition,moisture,lining,food(),dirt,
                Component.translatable("shelter.mcyukkuri."+(flooded()?"flooded":condition==0?"ruined":insulated()?"insulated":"exposed")));
    }
    public void dismantle() { clearStructure(true); }
    public void rollback() { clearStructure(false); }
    private void clearStructure(boolean drops) {
        if(dismantling||level==null)return;dismantling=true;
        if(drops){
            for(var stack:stock)if(!stack.isEmpty())Containers.dropItemStack(level,worldPosition.getX()+.5,worldPosition.getY(),worldPosition.getZ()+.5,stack.copy());
            Containers.dropItemStack(level,worldPosition.getX()+.5,worldPosition.getY(),worldPosition.getZ()+.5,
                    new ItemStack(kind()==ShelterBlock.Kind.CARDBOARD?Items.PAPER:Items.STICK,kind()==ShelterBlock.Kind.CARDBOARD?(condition>0?3:1):branches));
        }
        stock.clear();
        var positions=new ArrayList<>(ShelterPlacement.positions(worldPosition,kind()));positions.remove(worldPosition);positions.add(worldPosition);
        for(var pos:positions)if(level.hasChunkAt(pos)){
            var state=level.getBlockState(pos);
            if(state.getBlock() instanceof ShelterBlock&&ShelterBlock.root(pos,state).equals(worldPosition))level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
        }
    }
    private void changed() { setChanged();if(level!=null&&!level.isClientSide)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),3); }
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider registries) {
        super.saveAdditional(tag,registries);tag.putInt("Version",2);saveSite(tag);ContainerHelper.saveAllItems(tag,stock,registries);
        tag.putInt("Condition",condition);tag.putInt("Moisture",moisture);tag.putInt("Lining",lining);tag.putInt("Dirt",dirt);tag.putInt("Ticks",ticks);
        ListTag list=new ListTag();residents.forEach((id,slot)->{var entry=new CompoundTag();entry.putUUID("Id",id);entry.putInt("Bed",slot);list.add(entry);});tag.put("Residents",list);
    }
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider registries) {
        super.loadAdditional(tag,registries);loadSite(tag);stock.clear();ContainerHelper.loadAllItems(tag,stock,registries);
        condition=Math.clamp(tag.getInt("Condition"),0,100);moisture=Math.clamp(tag.getInt("Moisture"),0,100);lining=Math.clamp(tag.getInt("Lining"),0,4);dirt=Math.clamp(tag.getInt("Dirt"),0,100);ticks=Math.max(0,tag.getInt("Ticks"));
        residents.clear();for(Tag raw:tag.getList("Residents",Tag.TAG_COMPOUND)){var entry=(CompoundTag)raw;int slot=entry.getInt("Bed");if(entry.hasUUID("Id")&&slot>=0&&slot<capacity()&&!residents.containsValue(slot))residents.put(entry.getUUID("Id"),slot);}
    }
    private void saveSite(CompoundTag tag) {
        tag.putLongArray("NaturalBeds",naturalBeds.stream().mapToLong(BlockPos::asLong).toArray());tag.putInt("Branches",branches);
        if(naturalEntrance!=null)tag.putLong("NaturalEntrance",naturalEntrance.asLong());
    }
    private void loadSite(CompoundTag tag) {
        naturalBeds=java.util.Arrays.stream(tag.getLongArray("NaturalBeds")).mapToObj(BlockPos::of)
                .filter(pos->pos.getY()==worldPosition.getY()&&Math.abs(pos.getX()-worldPosition.getX())<=3&&Math.abs(pos.getZ()-worldPosition.getZ())<=3).distinct().limit(16).toList();
        branches=Math.clamp(tag.getInt("Branches"),0,3);
        naturalEntrance=tag.contains("NaturalEntrance")?BlockPos.of(tag.getLong("NaturalEntrance")):null;
        if(naturalEntrance!=null&&(!naturalEntrance.atY(worldPosition.getY()).equals(worldPosition.relative(facing()))
                ||naturalEntrance.getY()<worldPosition.getY()||naturalEntrance.getY()>worldPosition.getY()+1))naturalEntrance=null;
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        var tag=super.getUpdateTag(registries);saveSite(tag);tag.putInt("Condition",condition);tag.putInt("Moisture",moisture);tag.putInt("Lining",lining);tag.putInt("Dirt",dirt);tag.putInt("Food",food());return tag;
    }
    @Override public void handleUpdateTag(CompoundTag tag,HolderLookup.Provider registries) { loadSite(tag);condition=tag.getInt("Condition");moisture=tag.getInt("Moisture");lining=tag.getInt("Lining");dirt=tag.getInt("Dirt");displayFood=tag.getInt("Food"); }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
