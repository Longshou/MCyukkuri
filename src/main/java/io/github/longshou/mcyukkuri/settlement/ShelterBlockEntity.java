package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.GroupCards;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    /** Food now lives in the forage pile block beside the entrance; only an old save's inventory is read here, once, into that pile. */
    private final NonNullList<ItemStack> legacy=NonNullList.withSize(9,ItemStack.EMPTY);
    private BlockPos pilePos;
    private long nextPileTry;
    private final Map<UUID,Integer> residents=new LinkedHashMap<>();
    /** Residents that have spent real bedding to make a small, personal nest in this natural burrow. */
    private final Set<UUID> privateNests=new LinkedHashSet<>();
    private static final class NestProject {
        final java.util.List<ItemStack> materials=new ArrayList<>();
        int work;
    }
    private final Map<UUID,NestProject> nestProjects=new LinkedHashMap<>();
    public record NestConstruction(BlockPos bed,int portions,int work) {}
    public java.util.List<NestConstruction> nestConstructions(){
        return nestProjects.entrySet().stream().filter(e->owns(e.getKey()))
                .map(e->new NestConstruction(bed(e.getKey()),e.getValue().materials.size(),e.getValue().work)).toList();
    }
    public int nestPortions(UUID id){var p=nestProjects.get(id);return p==null?0:p.materials.size();}
    public int nestWork(UUID id){var p=nestProjects.get(id);return p==null?0:p.work;}
    public boolean layNestPortion(YukkuriEntity entity,ItemStack portion){
        if(level==null||level.isClientSide||!owns(entity.getUUID())||privateNest(entity.getUUID())||!usable(entity)
                ||portion.getCount()!=1||!bedding(portion)||entity.distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(bed(entity.getUUID())))>.16)return false;
        var project=nestProjects.computeIfAbsent(entity.getUUID(),id->new NestProject());
        if(project.materials.size()>=2)return false;
        project.materials.add(portion.copy());changed();return true;
    }
    public boolean workOnNest(YukkuriEntity entity){
        var project=nestProjects.get(entity.getUUID());
        if(level==null||level.isClientSide||project==null||!owns(entity.getUUID())||!usable(entity)
                ||entity.distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(bed(entity.getUUID())))>.16||project.work>=project.materials.size()*60)return false;
        project.work++;setChanged();if(project.work%10==0)changed();
        return project.work>=120&&completePrivateNest(entity);
    }
    private int condition=100, moisture, lining, dirt, ticks;
    private boolean dismantling;
    private List<BlockPos> naturalBeds=List.of();
    private BlockPos naturalEntrance;
    private int branches;
    /** Loaded ticks a natural burrow has stayed soaked; transient, a restart resets the worry. */
    private int soaked;
    public ShelterBlockEntity(BlockPos pos,BlockState state) { super(ModBlocks.SHELTER_ENTITY.get(),pos,state); }
    public ShelterBlock.Kind kind() { return ((ShelterBlock)getBlockState().getBlock()).kind(); }
    public int condition() { return condition; }
    public int moisture() { return moisture; }
    public int lining() { return lining; }
    public int dirt() { return dirt; }
    public int occupants() { return residents.size(); }
    public List<UUID> residentIds() { return List.copyOf(residents.keySet()); }
    public int capacity() { return kind()==ShelterBlock.Kind.BURROW?naturalBeds.size():kind().capacity; }
    public int food() { var pile=pile();return pile==null?0:pile.food(); }
    public Direction facing() { return getBlockState().getValue(ShelterBlock.FACING); }
    public BlockPos entrance() { return kind()==ShelterBlock.Kind.BURROW&&naturalEntrance!=null?naturalEntrance:worldPosition.relative(facing(),kind().radius+1); }
    public AABB interior() {
        var bounds=new AABB(worldPosition);for(var pos:beds())bounds=bounds.minmax(new AABB(pos));return bounds.expandTowards(0,.5,0);
    }
    public boolean contains(BlockPos pos) { return beds().contains(pos); }
    public boolean owns(UUID id) { return residents.containsKey(id); }
    public List<BlockPos> beds() { return kind()==ShelterBlock.Kind.BURROW?naturalBeds:List.of(worldPosition); }
    public boolean privateNest(UUID id) { return owns(id)&&privateNests.contains(id); }
    /** Occupied common nest cells, ordered by their surveyed sleeping slot for client-side grouping. */
    public List<BlockPos> sharedBeds() { return residentBeds(false); }
    /** Occupied cells whose resident has made a personal leaf nest, ordered by sleeping slot. */
    public List<BlockPos> privateBeds() { return residentBeds(true); }
    private List<BlockPos> residentBeds(boolean personal) {
        var beds=beds();
        return residents.entrySet().stream().filter(entry->privateNests.contains(entry.getKey())==personal)
                .sorted(Map.Entry.comparingByValue()).map(entry->entry.getValue()<beds.size()?beds.get(entry.getValue()):null)
                .filter(java.util.Objects::nonNull).toList();
    }
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
        boolean reservedChild=person!=null&&person.status==FamilyData.Status.UNBORN
                &&(residents.containsKey(person.mother)||residents.containsKey(person.father));
        int incoming=families.reservedForResidents(entity.getUUID()::equals);
        if(residents.size()+families.reservedForResidents(residents::containsKey)+incoming+(reservedChild?0:1)>capacity())return false;
        if(!residents.isEmpty() && residents.keySet().stream().noneMatch(id->id.equals(entity.family().partner())
                ||FamilyData.get((ServerLevel)level).related(id,entity.getUUID())))return false;
        int chosen=-1;double nearest=Double.MAX_VALUE;
        var beds=beds();
        for(int slot=0;slot<capacity();slot++)if(!residents.containsValue(slot)) {
            double distance=Double.MAX_VALUE;
            for(var parent:new UUID[]{entity.family().mother(),entity.family().father()}) {
                if(parent==null)continue;
                var parentBed=bed(parent);
                if(parentBed!=null)distance=Math.min(distance,beds.get(slot).distSqr(parentBed));
            }
            if(chosen<0||distance<nearest){chosen=slot;nearest=distance;}
        }
        if(chosen>=0){residents.put(entity.getUUID(),chosen);changed();return true;}
        return false;
    }
    /** The caller has already verified the two real bedding portions at this resident's own usable bed. */
    public boolean completePrivateNest(YukkuriEntity entity) {
        if(level==null||level.isClientSide||kind()!=ShelterBlock.Kind.BURROW||!owns(entity.getUUID())||privateNest(entity.getUUID())||!usable(entity))return false;
        var project=nestProjects.get(entity.getUUID());
        if(project==null||project.materials.size()!=2||project.work<120)return false;
        nestProjects.remove(entity.getUUID());privateNests.add(entity.getUUID());changed();return true;
    }
    public void release(UUID id) {
        dropNestProject(id);
        boolean released=residents.remove(id)!=null;
        boolean removedPrivate=privateNests.remove(id);
        if(released||removedPrivate)changed();
    }
    private void dropNestProject(UUID id){
        var project=nestProjects.remove(id);
        if(project!=null&&level!=null&&!level.isClientSide)for(var material:project.materials)
            Containers.dropItemStack(level,worldPosition.getX()+.5,worldPosition.getY(),worldPosition.getZ()+.5,material.copy());
    }
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
        return bed!=null&&!GroupCards.barred(entity)&&sound()&&moisture<85&&dirt<90&&entity.life().dryStand(bed)&&entity.life().dryStand(entrance());
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
    /** Capacity follows the actual item/components in each occupied slot; empty slots use ordinary forage for planning. */
    public int foodCapacity() { var pile=pile();return pile==null?ForagePileBlockEntity.SLOTS*Items.SHORT_GRASS.getDefaultInstance().getMaxStackSize():pile.capacity(); }
    public int foodSpace(ItemStack incoming) { var pile=ensurePile();return pile==null?0:pile.foodSpace(incoming); }
    public boolean accepts(ItemStack stack) { return !stack.isEmpty()&&(needsMaterial(stack)||foodSpace(stack)>0); }
    public boolean deposit(ItemStack stack) {
        if(stack.getCount()!=1)return false;
        if((branches<3||condition<95)&&repairMaterial(stack)){branches=Math.min(3,branches+1);condition=Math.min(100,condition+15);changed();return true;}
        if((lining<4||dirt>=30||moisture>=60)&&bedding(stack)){
            if(lining<4)lining++;dirt=Math.max(0,dirt-30);moisture=Math.max(0,moisture-10);changed();return true;
        }
        if(foodSpace(stack)==0)return false;
        return returnFood(stack).isEmpty();
    }
    public ItemStack takeFood(YukkuriEntity entity) {
        return takeFood(entity,entity);
    }
    /** A resident may collect a real portion for a child; selection follows the actual eater's acceptance. */
    public ItemStack takeFood(YukkuriEntity resident,YukkuriEntity eater) {
        var pile=pile();
        if(pile==null||!owns(resident.getUUID()))return ItemStack.EMPTY;
        return pile.take(stack->eater.life().acceptsFood(stack));
    }
    /** A raider takes one existing portion without being a resident. */
    public ItemStack raid() { var pile=pile();return pile==null?ItemStack.EMPTY:pile.raid(); }
    /** Preview never reserves or transfers stock; the caller cannot mutate the stored stack. */
    public ItemStack foodFor(YukkuriEntity entity) {
        return foodFor(entity,entity);
    }
    private ItemStack foodFor(YukkuriEntity resident,YukkuriEntity eater) {
        var pile=pile();
        if(pile==null||!owns(resident.getUUID()))return ItemStack.EMPTY;
        return pile.peek(stack->eater.life().acceptsFood(stack));
    }
    public boolean hasFoodFor(YukkuriEntity entity) { return !foodFor(entity).isEmpty(); }
    public boolean hasFoodFor(YukkuriEntity resident,YukkuriEntity eater) { return !foodFor(resident,eater).isEmpty(); }
    /** One synchronous ownership transfer, only while the resident's mouth is at the edge of the pile. */
    public boolean beginStoredMeal(YukkuriEntity entity) {
        var pile=pile();
        if(level==null||level.isClientSide||pile==null||entity.level()!=level||!usable(entity)
                ||entity.isNoAi()||entity.isPassenger()||entity.operations().active()||entity.life().isSleeping()
                ||entity.isOnFire()||entity.isInWaterOrBubble()||entity.life().drugSleep()>0
                ||entity.distanceToSqr(pile.standPoint(entity))>=.64
                ||!entity.life().dryStand(entity.blockPosition()))return false;
        var portion=pile.peek(stack->entity.life().acceptsFood(stack));
        if(portion.isEmpty()||!entity.life().beginMeal(portion,null,false))return false;
        pile.take(stack->ItemStack.isSameItemSameComponents(stack,portion));
        return true;
    }
    /** Transfer by copy, returning any unaccepted remainder to the caller; never overfill or drop it remotely. */
    public ItemStack returnFood(ItemStack stack) {
        var pile=ensurePile();
        return pile==null?stack.copy():pile.returnFood(stack);
    }
    // ---- the forage pile block (储粮堆) ----
    /** The pile already linked to this shelter; never places anything. */
    public ForagePileBlockEntity pile() {
        if(level==null||pilePos==null||!level.hasChunkAt(pilePos))return null;
        return level.getBlockEntity(pilePos) instanceof ForagePileBlockEntity pile&&!pile.isRemoved()?pile:null;
    }
    /** The linked pile, or a new one set beside the entrance (an unowned pile there is adopted). Tries at most every 100 ticks. */
    public ForagePileBlockEntity ensurePile() {
        var pile=pile();
        if(pile!=null||!(level instanceof ServerLevel server)||server.getGameTime()<nextPileTry||!sound())return pile;
        nextPileTry=server.getGameTime()+100;
        var facing=facing();var left=facing.getCounterClockWise();var mouth=entrance();
        // Order: straight ahead of the mouth, then beside it, then further out. The stand side faces the mouth's column.
        int[][] spots={{1,0},{1,1},{1,-1},{0,1},{0,-1},{2,0},{2,1},{2,-1}};
        BlockPos[] cells=new BlockPos[spots.length];Direction[] sides=new Direction[spots.length];
        for(int i=0;i<spots.length;i++) {
            cells[i]=mouth.relative(facing,spots[i][0]).relative(left,spots[i][1]);
            sides[i]=spots[i][1]==0?facing.getOpposite():spots[i][1]>0?left.getOpposite():left;
            if(server.hasChunkAt(cells[i])&&server.getBlockEntity(cells[i]) instanceof ForagePileBlockEntity free&&free.ownerFree(server,worldPosition))
                return link(free,cells[i],sides[i]);
        }
        for(int i=0;i<spots.length;i++) {
            var cell=cells[i];var stand=cell.relative(sides[i]);
            if(!server.hasChunkAt(cell)||!server.hasChunkAt(stand)||!server.getBlockState(cell).isAir()||!server.getFluidState(cell).isEmpty()
                    ||contains(cell)||cell.equals(mouth)||!io.github.longshou.mcyukkuri.house.SupplyPiles.supported(server,cell)
                    ||!NaturalShelter.stand(server,stand))continue;
            if(!server.setBlock(cell,ModBlocks.FORAGE_PILE.get().defaultBlockState(),3))continue;
            if(server.getBlockEntity(cell) instanceof ForagePileBlockEntity placed)return link(placed,cells[i],sides[i]);
            server.removeBlock(cell,false);
        }
        return null;
    }
    private ForagePileBlockEntity link(ForagePileBlockEntity pile,BlockPos cell,Direction side) {
        pile.setApproach(side);pile.setOwner(worldPosition);pilePos=cell.immutable();
        for(int i=0;i<legacy.size();i++) {
            if(legacy.get(i).isEmpty())continue;
            var rest=pile.returnFood(legacy.get(i));
            if(!rest.isEmpty())Containers.dropItemStack(level,cell.getX()+.5,cell.getY()+.1,cell.getZ()+.5,rest);
            legacy.set(i,ItemStack.EMPTY);
        }
        changed();return pile;
    }
    public void initializeEncounter(int count) {
        lining=4;branches=kind()==ShelterBlock.Kind.BURROW?2:0;
        // The first stock is a real heap of grass on the pile, if a spot beside the entrance is free.
        nextPileTry=0;var pile=ensurePile();
        if(pile!=null)pile.returnFood(new ItemStack(Items.SHORT_GRASS,Math.min(12,Math.max(2,count*2))));
        changed();
    }
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
        if(residents.keySet().removeIf(id->{var p=families.person(id);return p!=null&&(p.status==FamilyData.Status.DEAD||p.status==FamilyData.Status.REMOVED);})) {
            privateNests.retainAll(residents.keySet());
            for(var id:java.util.List.copyOf(nestProjects.keySet()))if(!owns(id))dropNestProject(id);
            changed();
        }
        if(!complete()) {
            if(ShelterPlacement.positions(worldPosition,kind()).stream().allMatch(level::hasChunkAt))dismantle();
            return;
        }
        boolean rain=kind()==ShelterBlock.Kind.BURROW?beds().stream().anyMatch(pos->level.isRainingAt(pos.above())):level.isRainingAt(worldPosition.above(kind().height));
        if(flooded())moisture=Math.min(100,moisture+20);
        else if(rain)moisture=Math.min(100,moisture+(kind()==ShelterBlock.Kind.CARDBOARD?7:8));
        else moisture=Math.max(0,moisture-4);
        soaked=kind()==ShelterBlock.Kind.BURROW&&moisture>=90?soaked+100:0;
        if(soaked>2400&&residents.size()>=3&&level instanceof ServerLevel server&&server.random.nextInt(YukkuriConfig.COLLAPSE_ONE_IN.get())==0
                &&GroupCards.collapse(server,this))return;
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
        var stored=pile();
        if(ticks%rot==0&&stored!=null&&!stored.raid().isEmpty())dirt=Math.min(100,dirt+3);
        if(stored==null&&!residents.isEmpty())ensurePile(); // an empty, unclaimed shelter does not grow a store on its own
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
            for(var id:java.util.List.copyOf(nestProjects.keySet()))dropNestProject(id);
            for(var stack:legacy)if(!stack.isEmpty())Containers.dropItemStack(level,worldPosition.getX()+.5,worldPosition.getY(),worldPosition.getZ()+.5,stack.copy());
            Containers.dropItemStack(level,worldPosition.getX()+.5,worldPosition.getY(),worldPosition.getZ()+.5,
                    new ItemStack(kind()==ShelterBlock.Kind.CARDBOARD?Items.PAPER:Items.STICK,kind()==ShelterBlock.Kind.CARDBOARD?(condition>0?3:1):branches));
        }
        legacy.clear();
        // A collapse leaves the heap where it lies (its portions stay real); a rollback removes the unused pile with the shelter.
        var heap=pile();
        if(!drops&&heap!=null){heap.drain();level.removeBlock(heap.getBlockPos(),false);}
        pilePos=null;
        residents.clear();privateNests.clear();nestProjects.clear();
        var positions=new ArrayList<>(ShelterPlacement.positions(worldPosition,kind()));positions.remove(worldPosition);positions.add(worldPosition);
        for(var pos:positions)if(level.hasChunkAt(pos)){
            var state=level.getBlockState(pos);
            if(state.getBlock() instanceof ShelterBlock&&ShelterBlock.root(pos,state).equals(worldPosition))level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
        }
    }
    private void changed() { setChanged();if(level!=null&&!level.isClientSide)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),3); }
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider registries) {
        super.saveAdditional(tag,registries);tag.putInt("Version",3);saveSite(tag);
        if(legacy.stream().anyMatch(stack->!stack.isEmpty()))ContainerHelper.saveAllItems(tag,legacy,registries);
        if(pilePos!=null)tag.putLong("Pile",pilePos.asLong());
        tag.putInt("Condition",condition);tag.putInt("Moisture",moisture);tag.putInt("Lining",lining);tag.putInt("Dirt",dirt);tag.putInt("Ticks",ticks);
        saveResidents(tag,registries);
    }
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider registries) {
        super.loadAdditional(tag,registries);loadSite(tag);legacy.clear();ContainerHelper.loadAllItems(tag,legacy,registries);
        pilePos=tag.contains("Pile")?BlockPos.of(tag.getLong("Pile")):null;
        condition=Math.clamp(tag.getInt("Condition"),0,100);moisture=Math.clamp(tag.getInt("Moisture"),0,100);lining=Math.clamp(tag.getInt("Lining"),0,4);dirt=Math.clamp(tag.getInt("Dirt"),0,100);ticks=Math.max(0,tag.getInt("Ticks"));
        loadResidents(tag,registries);
    }
    private void saveResidents(CompoundTag tag,HolderLookup.Provider registries) {
        ListTag residentsTag=new ListTag();residents.forEach((id,slot)->{var entry=new CompoundTag();entry.putUUID("Id",id);entry.putInt("Bed",slot);residentsTag.add(entry);});tag.put("Residents",residentsTag);
        ListTag privateTag=new ListTag();privateNests.forEach(id->{var entry=new CompoundTag();entry.putUUID("Id",id);privateTag.add(entry);});tag.put("PrivateNests",privateTag);
        ListTag projects=new ListTag();nestProjects.forEach((id,project)->{
            var entry=new CompoundTag();entry.putUUID("Id",id);entry.putInt("Work",project.work);
            var materials=new ListTag();for(var material:project.materials)materials.add(material.save(registries));
            entry.put("Materials",materials);projects.add(entry);
        });tag.put("NestProjects",projects);
    }
    private void loadResidents(CompoundTag tag,HolderLookup.Provider registries) {
        residents.clear();for(Tag raw:tag.getList("Residents",Tag.TAG_COMPOUND)){var entry=(CompoundTag)raw;int slot=entry.getInt("Bed");if(entry.hasUUID("Id")&&slot>=0&&slot<capacity()&&!residents.containsValue(slot))residents.put(entry.getUUID("Id"),slot);}
        privateNests.clear();for(Tag raw:tag.getList("PrivateNests",Tag.TAG_COMPOUND)){var entry=(CompoundTag)raw;if(entry.hasUUID("Id")&&residents.containsKey(entry.getUUID("Id")))privateNests.add(entry.getUUID("Id"));}
        nestProjects.clear();for(Tag raw:tag.getList("NestProjects",Tag.TAG_COMPOUND)){
            var entry=(CompoundTag)raw;if(!entry.hasUUID("Id")||!owns(entry.getUUID("Id"))||privateNest(entry.getUUID("Id")))continue;
            var project=new NestProject();
            for(var material:entry.getList("Materials",Tag.TAG_COMPOUND)){
                var stack=ItemStack.parseOptional(registries,(CompoundTag)material);
                if(project.materials.size()<2&&!stack.isEmpty()&&bedding(stack))project.materials.add(stack.copyWithCount(1));
            }
            if(!project.materials.isEmpty()){project.work=Math.clamp(entry.getInt("Work"),0,project.materials.size()*60);nestProjects.put(entry.getUUID("Id"),project);}
        }
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
        var tag=super.getUpdateTag(registries);saveSite(tag);saveResidents(tag,registries);tag.putInt("Condition",condition);tag.putInt("Moisture",moisture);tag.putInt("Lining",lining);tag.putInt("Dirt",dirt);return tag;
    }
    @Override public void handleUpdateTag(CompoundTag tag,HolderLookup.Provider registries) { loadSite(tag);loadResidents(tag,registries);condition=tag.getInt("Condition");moisture=tag.getInt("Moisture");lining=tag.getInt("Lining");dirt=tag.getInt("Dirt"); }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
