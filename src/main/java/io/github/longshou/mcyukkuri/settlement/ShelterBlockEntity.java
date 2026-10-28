package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.status.GameplayLog;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.motor.surface.BedContainer;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.season.Season;
import io.github.longshou.mcyukkuri.season.Seasons;
import io.github.longshou.mcyukkuri.social.Bonds;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import io.github.longshou.mcyukkuri.headwear.Recognition;
import io.github.longshou.mcyukkuri.life.NestOccupancy;
import io.github.longshou.mcyukkuri.life.NestResting;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.house.SupplyPiles;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** Finite household stores, assigned sleeping places and weathering, advanced only in loaded chunks. */
public final class ShelterBlockEntity extends BlockEntity {
    /** Food lives only in the forage pile block beside the entrance. */
    private BlockPos pilePos;
    private CompoundTag lastSent;
    private long nextPileTry;
    private final Map<UUID,Integer> residents=new LinkedHashMap<>();
    /** Residents that have spent real bedding to make a small, personal nest in this natural burrow. */
    private final Set<UUID> privateNests=new LinkedHashSet<>();
    private final Set<BlockPos> largeBeds=new LinkedHashSet<>();
    private final Map<UUID,NestResting.Place> resting=new LinkedHashMap<>();
    public Vec3 restOffset(UUID id){var p=resting.get(id);return p==null?Vec3.ZERO:p.offset().multiply(1,0,1);}
    private boolean chooseResting(YukkuriEntity entity){
        var at=bed(entity.getUUID());if(at==null)return false;
        var peers=new LinkedHashMap<UUID,NestResting.Place>();
        resting.forEach((id,place)->{if(at.equals(bed(id)))peers.put(id,place);});
        var place=NestResting.choose(entity,largeBed(entity.getUUID())?NestResting.LARGE_BED:NestResting.LEAF_BED,peers,
                standingInside(BedContainer.of(entity,at)),CareBlockEntity.expecting(entity));
        if(place!=null){peers.put(entity.getUUID(),place);if(peers.entrySet().stream().anyMatch(e->!e.getValue().equals(resting.get(e.getKey())))){resting.putAll(peers);setChanged();}}
        return place!=null;
    }
    /** A place counts only where that body's whole footprint would stand inside the bed (spec 7), not merely on a top. */
    private static BiPredicate<UUID,NestResting.Place> standingInside(BedContainer container){
        return (id,p)->container!=null&&container.standsInside(p.x(),p.z(),p.width());
    }
    private final Map<BlockPos,Integer> largeBedWork=new LinkedHashMap<>();
    private static final class NestProject {
        final List<ItemStack> materials=new ArrayList<>();
        int work;
    }
    private final Map<UUID,NestProject> nestProjects=new LinkedHashMap<>();
    public record NestConstruction(BlockPos bed,int portions,int work) {}
    public List<NestConstruction> nestConstructions(){
        return nestProjects.entrySet().stream().filter(e->owns(e.getKey()))
                .map(e->new NestConstruction(bed(e.getKey()),e.getValue().materials.size(),e.getValue().work)).toList();
    }
    public int nestPortions(UUID id){var p=nestProjects.get(id);return p==null?0:p.materials.size();}
    public int nestWork(UUID id){var p=nestProjects.get(id);return p==null?0:p.work;}
    public boolean layNestPortion(YukkuriEntity entity,ItemStack portion){
        if(level==null||level.isClientSide||!owns(entity.getUUID())||privateNest(entity.getUUID())||sharesBed(entity.getUUID())||!usable(entity)
                ||portion.getCount()!=1||!bedding(portion)||entity.distanceToSqr(Vec3.atBottomCenterOf(bed(entity.getUUID())))>.16)return false;
        var project=nestProjects.computeIfAbsent(entity.getUUID(),id->new NestProject());
        if(project.materials.size()>=2)return false;
        project.materials.add(portion.copy());changed();return true;
    }
    public boolean workOnNest(YukkuriEntity entity){
        var project=nestProjects.get(entity.getUUID());
        if(level==null||level.isClientSide||project==null||!owns(entity.getUUID())||!usable(entity)
                ||entity.distanceToSqr(Vec3.atBottomCenterOf(bed(entity.getUUID())))>.16||project.work>=project.materials.size()*60)return false;
        project.work++;setChanged();if(project.work%10==0)changed();
        return project.work>=120&&completePrivateNest(entity);
    }
    private int condition=100, moisture, lining, dirt, ticks;
    private boolean dismantling;
    /** Residential cells of a natural home, deepest first. The doorway cell (this core) and the store cell are not among them. */
    private List<BlockPos> naturalBeds=List.of();
    private BlockPos naturalEntrance;
    /** The cell inside the cavity where the forage pile goes, and the cell beside it the mouth touches it from; null in old or tiny homes. */
    private BlockPos naturalPile,naturalPileStand;
    private int branches;
    /** Loaded ticks a natural burrow has stayed soaked; transient, a restart resets the worry. */
    private int soaked;
    public ShelterBlockEntity(BlockPos pos,BlockState state) { super(ModBlocks.SHELTER_ENTITY.get(),pos,state); }
    @Override public void onLoad() {
        super.onLoad();
        if(level instanceof ServerLevel server)Homes.registerLoaded(server,this);
    }
    @Override public void onChunkUnloaded() {
        if(level instanceof ServerLevel server)LoadedShelters.unregister(server,this);
        super.onChunkUnloaded();
    }
    @Override public void setRemoved() {
        if(level instanceof ServerLevel server)LoadedShelters.unregister(server,this);
        super.setRemoved();
    }
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
        var bounds=new AABB(worldPosition);for(var pos:beds())bounds=bounds.minmax(new AABB(pos));
        if(naturalPile!=null)bounds=bounds.minmax(new AABB(naturalPile));return bounds.expandTowards(0,.5,0);
    }
    /** Inside the home: a residential cell, the doorway cell just inside the mouth, or the store cell. */
    public boolean contains(BlockPos pos) { return beds().contains(pos)||kind()==ShelterBlock.Kind.BURROW&&(pos.equals(worldPosition)||pos.equals(naturalPile)); }
    /** The planned store cell inside the cavity, or null. */
    public BlockPos pileCell() { return naturalPile; }
    public boolean owns(UUID id) { return residents.containsKey(id); }
    public List<BlockPos> beds() { return kind()==ShelterBlock.Kind.BURROW?naturalBeds:List.of(worldPosition); }
    public boolean privateNest(UUID id) { return owns(id)&&privateNests.contains(id); }
    /** Occupied common nest cells, ordered by their surveyed sleeping slot for client-side grouping. */
    public List<BlockPos> sharedBeds() {
        var result=new ArrayList<>(residentBeds(false));
        for(var extra:nestExtras(sharers()))if(!result.contains(extra))result.add(extra);
        return result;
    }
    /** Occupied cells whose resident has made a personal leaf nest, ordered by sleeping slot. */
    public List<BlockPos> privateBeds() { return residentBeds(true); }
    private List<BlockPos> residentBeds(boolean personal) {
        var beds=beds();
        return residents.entrySet().stream().filter(entry->privateNests.contains(entry.getKey())==personal)
                .sorted(Map.Entry.comparingByValue()).map(entry->entry.getValue()<beds.size()?beds.get(entry.getValue()):null)
                .filter(Objects::nonNull).distinct().toList();
    }
    public int branches() { return branches; }
    /** The owner lays a mouth-screen twig from the doorway stock (project design: {@link DenSeal}); false when none is left. */
    boolean takeBranch() { if(branches<=0)return false;branches--;changed();return true; }
    /** A twig of a taken-down screen goes back to the doorway stock. */
    void returnBranch() { if(branches<3){branches++;changed();} }
    public void setNaturalSite(NaturalShelter.Site site) {
        naturalBeds=site==null?List.of():site.sleeping();naturalEntrance=site==null?null:site.entrance();
        naturalPile=site==null?null:site.pile();naturalPileStand=site==null?null:site.pileStand();changed();
    }
    /** The cavity was enlarged (or re-surveyed): keep every resident on the cell it already sleeps on. */
    public void refreshSite(NaturalShelter.Site site) {
        if(site==null||kind()!=ShelterBlock.Kind.BURROW)return;
        var cellOf=new LinkedHashMap<UUID,BlockPos>();
        for(var entry:residents.entrySet())cellOf.put(entry.getKey(),entry.getValue()<naturalBeds.size()?naturalBeds.get(entry.getValue()):null);
        var next=new ArrayList<BlockPos>();
        for(var bed:naturalBeds)if(site.sleeping().contains(bed))next.add(bed);
        for(var bed:site.sleeping())if(!next.contains(bed))next.add(bed);
        naturalBeds=List.copyOf(next);naturalEntrance=site.entrance();naturalPile=site.pile();naturalPileStand=site.pileStand();
        residents.clear();
        for(var entry:cellOf.entrySet()) {
            int slot=entry.getValue()==null?-1:naturalBeds.indexOf(entry.getValue());
            if(slot<0)slot=firstFreeSlot();
            if(slot>=0)residents.put(entry.getKey(),slot);
        }
        changed();
    }
    private int firstFreeSlot() {
        for(int slot=0;slot<naturalBeds.size();slot++)if(!residents.containsValue(slot)&&!reservedByLargeBed(naturalBeds.get(slot)))return slot;
        return -1;
    }
    public boolean rainSheltered(BlockPos pos) {
        return kind()==ShelterBlock.Kind.CARDBOARD?sound()&&moisture<90:
                level.getHeight(Heightmap.Types.MOTION_BLOCKING,pos.getX(),pos.getZ())>pos.getY();
    }
    public BlockPos bed(UUID id) { Integer slot=residents.get(id);return slot==null||slot>=beds().size()?null:beds().get(slot); }
    // ---- cells, family nest and personal nests
    /** Cells that hold at least one resident. Young children sleep in a parent's cell, so this is smaller than {@link #occupants()}. */
    public int usedSlots() { return (int)residents.values().stream().distinct().count(); }
    /** Residents who share a cell with someone else: the young of the family nest. */
    public int sharers() { return residents.size()-usedSlots(); }
    public boolean sharesBed(UUID id) {
        Integer slot=residents.get(id);
        return slot!=null&&residents.values().stream().filter(slot::equals).count()>1;
    }
    /** The family nest is bigger than one cell: one extra cell for every three young that sleep in it. */
    public static int extraCells(int young) { return (young+2)/3; }
    /** Free cells next to the occupied family nest that the nest keeps for its young; nobody else takes them. */
    private List<BlockPos> nestExtras(int young) {
        int want=extraCells(young);
        if(want<=0||kind()!=ShelterBlock.Kind.BURROW)return List.of();
        var beds=beds();var cluster=new ArrayList<BlockPos>();
        for(var entry:residents.entrySet())if(!privateNests.contains(entry.getKey())&&entry.getValue()<beds.size()
                &&!cluster.contains(beds.get(entry.getValue())))cluster.add(beds.get(entry.getValue()));
        if(cluster.isEmpty())return List.of();
        var free=new ArrayList<BlockPos>();
        for(int slot=0;slot<beds.size();slot++)if(!residents.containsValue(slot))free.add(beds.get(slot));
        free.sort(Comparator.comparingDouble(cell->cluster.stream().mapToDouble(cell::distSqr).min().orElse(0)));
        return List.copyOf(free.subList(0,Math.min(want,free.size())));
    }
    public boolean largeBed(UUID id){return largeBeds.contains(bed(id));}
    public List<BlockPos> largeBeds(){return List.copyOf(largeBeds);}
    private boolean reservedByLargeBed(BlockPos at){return largeBeds.stream().anyMatch(pos->!pos.equals(at)&&pos.distSqr(at)<4);}
    private boolean acceptsAt(int slot,YukkuriEntity entity){
        var members=new LinkedHashMap<UUID,Integer>();
        residents.forEach((id,place)->{if(place==slot)members.put(id,level instanceof ServerLevel server&&server.getEntity(id) instanceof YukkuriEntity y?NestOccupancy.stage(y):2);});
        return NestOccupancy.accepts(slot<beds().size()&&largeBeds.contains(beds().get(slot)),members,entity);
    }
    private Integer parentSlot(YukkuriEntity entity) {
        for(int slot=0;slot<beds().size();slot++)if(residents.containsValue(slot)&&acceptsAt(slot,entity)){
            boolean kin=false;for(var entry:residents.entrySet())if(entry.getValue()==slot
                    &&(FamilyData.get((ServerLevel)level).socialKin(entity.getUUID(),entry.getKey())||entry.getKey().equals(entity.family().partner())))kin=true;
            if(kin)return slot;
        }
        return null;
    }
    /** Growth frees an invalid bed; lack of room remains a real housing failure. */
    public void enforceBedCapacity(YukkuriEntity entity){
        Integer slot=residents.get(entity.getUUID());if(slot==null)return;
        if(entity.family().partner()!=null&&residents.containsKey(entity.family().partner())){
            int mateSlot=residents.get(entity.family().partner());
            if(mateSlot!=slot&&largeBeds.contains(beds().get(mateSlot))&&acceptsAt(mateSlot,entity)){
                residents.put(entity.getUUID(),mateSlot);changed();slot=mateSlot;
                GameplayLog.event(entity,"home","joined partner large nest bed={}",beds().get(mateSlot));
            }
        }
        // In a large family bed it is the newly grown child who leaves, not a parent ticking first.
        if(largeBed(entity.getUUID())&&NestOccupancy.stage(entity)!=1&&level instanceof ServerLevel server)
            for(UUID id:List.copyOf(residents.keySet()))if(!id.equals(entity.getUUID())&&slot.equals(residents.get(id))
                    &&server.getEntity(id) instanceof YukkuriEntity child&&NestOccupancy.stage(child)==1)enforceBedCapacity(child);
        if(acceptsAt(slot,entity)&&chooseResting(entity))return;
        if(!separate(entity)||!chooseResting(entity)){
            GameplayLog.event(entity,"home","no separate bed fitting stage/body shelter={} oldBed={}",worldPosition,bed(entity.getUUID()));
            entity.habitat().release();
        }else GameplayLog.event(entity,"home","separate bed after growth shelter={} bed={}",worldPosition,bed(entity.getUUID()));
    }
    /** Rework two real lining portions at the occupied bed; no material or floor is spawned. */
    public boolean prepareLargeBed(YukkuriEntity mother){
        BlockPos at=bed(mother.getUUID());
        if(at==null||largeBeds.contains(at)||kind()!=ShelterBlock.Kind.BURROW||lining<2||!usable(mother)
                ||mother.distanceToSqr(Vec3.atBottomCenterOf(at))>.64)return false;
        for(var entry:residents.entrySet())if(entry.getValue()<beds().size()&&!beds().get(entry.getValue()).equals(at)&&beds().get(entry.getValue()).distSqr(at)<4
                &&!entry.getKey().equals(mother.family().partner()))return false;
        var room=new AABB(at.getX()-.5,at.getY(),at.getZ()-.5,at.getX()+1.5,at.getY()+1.1,at.getZ()+1.5);
        if(!level.noCollision(mother,room))return false;
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(!level.getBlockState(at.offset(dx,-1,dz)).isFaceSturdy(level,at.offset(dx,-1,dz),Direction.UP))return false;
        int work=largeBedWork.merge(at,1,Integer::sum);setChanged();
        if(work==1)GameplayLog.event(mother,"home","large wild nest preparation started bed={} lining={}",at,lining);
        if(work<120)return true;
        largeBedWork.remove(at);largeBeds.add(at);lining-=2;privateNests.remove(mother.getUUID());
        var mate=mother.family().partner();
        if(mate!=null&&residents.containsKey(mate)&&level instanceof ServerLevel server&&server.getEntity(mate) instanceof YukkuriEntity partner
                &&acceptsAt(residents.get(mother.getUUID()),partner))residents.put(mate,residents.get(mother.getUUID()));
        changed();
        GameplayLog.event(mother,"home","large wild nest prepared bed={} liningSpent=2 lining={}",at,lining);
        return true;
    }
    /** The spawner calls this only after completing the real family relationship records. */
    public boolean claim(YukkuriEntity entity) {
        if(owns(entity.getUUID()))return true;
        if(!(level instanceof ServerLevel server)||!sound()||entity.profile().houseBody())return false;
        var families=FamilyData.get(server);
        var person=families.person(entity.getUUID());
        boolean reservedChild=person!=null&&person.status==FamilyData.Status.UNBORN
                &&(residents.containsKey(person.mother)||residents.containsKey(person.father));
        int incoming=families.reservedForResidents(entity.getUUID()::equals);
        Integer shared=parentSlot(entity);
        if(shared!=null){residents.put(entity.getUUID(),shared);changed();return true;}
        boolean young=shared!=null||reservedChild;
        int reservedYoung=families.reservedForResidents(residents::containsKey)+incoming;
        if(usedSlots()+(young?0:1)+extraCells(sharers()+reservedYoung+(young?1:0))>capacity())return false;
        if(!residents.isEmpty() && residents.keySet().stream().noneMatch(id->id.equals(entity.family().partner())
                ||FamilyData.get((ServerLevel)level).socialKin(id,entity.getUUID())&&!(((ServerLevel)level).getEntity(id) instanceof YukkuriEntity res&&!Recognition.recognizes(res,
                        entity))))return false;
        if(shared!=null&&shared<capacity()){residents.put(entity.getUUID(),shared);changed();return true;}
        int chosen=-1;double nearest=Double.MAX_VALUE;
        var beds=beds();var extras=nestExtras(sharers()+reservedYoung);
        for(int slot=0;slot<capacity();slot++)if(!residents.containsValue(slot)&&!reservedByLargeBed(beds.get(slot))&&!extras.contains(beds.get(slot))) {
            double distance=Double.MAX_VALUE;
            // Next to a parent or the partner: the family sleeps together in the deep part of the cave.
            for(var kin:new UUID[]{entity.family().mother(),entity.family().father(),entity.family().partner()}) {
                if(kin==null)continue;
                var kinBed=bed(kin);
                if(kinBed!=null)distance=Math.min(distance,beds.get(slot).distSqr(kinBed));
            }
            if(chosen<0||distance<nearest){chosen=slot;nearest=distance;}
        }
        if(chosen<0)chosen=firstFreeSlot();
        if(chosen>=0) {
            residents.put(entity.getUUID(),chosen);
            // Young of this parent that were taken in first (a household is claimed in no fixed order) move into the parent's cell.
            for(var id:List.copyOf(residents.keySet())) {
                if(id.equals(entity.getUUID())||privateNests.contains(id)||sharesBed(id))continue;
                if(server.getEntity(id) instanceof YukkuriEntity kid&&!kid.family().adult()&&!kid.family().readyForOwnNest()
                        &&(Bonds.parentOf(entity, kid))&&acceptsAt(chosen,kid))residents.put(id,chosen);
            }
            changed();return true;
        }
        return false;
    }
    /** A grown child leaves the family nest for a cell of its own, next to where it slept. False when the cave has no free cell. */
    public boolean separate(YukkuriEntity entity) {
        Integer slot=residents.get(entity.getUUID());
        if(slot==null)return false;
        if(!sharesBed(entity.getUUID()))return true;
        var beds=beds();var current=slot<beds.size()?beds.get(slot):null;
        int chosen=-1;double nearest=Double.MAX_VALUE;
        for(int free=0;free<capacity();free++)if(!residents.containsValue(free)&&!reservedByLargeBed(beds.get(free))) {
            double distance=current==null?0:beds.get(free).distSqr(current);
            if(distance<nearest){chosen=free;nearest=distance;}
        }
        if(chosen<0)return false;
        residents.put(entity.getUUID(),chosen);changed();return true;
    }
    /** True when the family has outgrown the cave: no free cell is left for a new adult or the nest's young. */
    public boolean crowded() { return usedSlots()+extraCells(sharers())>=capacity(); }
    /** The caller has already verified the two real bedding portions at this resident's own usable bed. */
    public boolean completePrivateNest(YukkuriEntity entity) {
        if(level==null||level.isClientSide||kind()!=ShelterBlock.Kind.BURROW||!owns(entity.getUUID())||privateNest(entity.getUUID())||sharesBed(entity.getUUID())||!usable(entity))return false;
        var project=nestProjects.get(entity.getUUID());
        if(project==null||project.materials.size()!=2||project.work<120)return false;
        nestProjects.remove(entity.getUUID());privateNests.add(entity.getUUID());changed();return true;
    }
    public void release(UUID id) {
        dropNestProject(id);
        resting.remove(id);
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
                && beds().stream().allMatch(pos->kind()==ShelterBlock.Kind.BURROW?NaturalShelter.enclosed(level,pos):NaturalShelter.stand(level,pos))
                && (kind()!=ShelterBlock.Kind.BURROW||NaturalShelter.enclosed(level,worldPosition));
    }
    public boolean usable(YukkuriEntity entity) {
        var bed=bed(entity.getUUID());
        return bed!=null&&!SettlementEvents.shelterBarred(entity)&&sound()&&moisture<85&&dirt<90&&entity.life().dryStand(bed)&&(entity.life().dryStand(entrance())||DenBarrier.sealed(this));
    }
    public boolean insulated() { return lining>=3&&moisture<55&&(kind()==ShelterBlock.Kind.BURROW||condition>=40); }
    public boolean suitableForYoung(int children) {
        if(!sound()||(kind()==ShelterBlock.Kind.CARDBOARD&&condition<40)||moisture>=60||dirt>=65||lining<2)return false;
        int reserved=FamilyData.get((ServerLevel)level).reservedForResidents(residents::containsKey);
        // The young sleep in one family nest that grows by a cell for every three of them.
        return usedSlots()+extraCells(sharers()+reserved+children)<=capacity();
    }
    public static boolean bedding(ItemStack stack) { return stack.is(ItemTags.LEAVES)||stack.is(Items.PAPER)||stack.is(Items.SHORT_GRASS)||stack.is(Items.WHEAT); }
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
        int stockBefore = pile.food();
        var taken = pile.take(stack->ItemStack.isSameItemSameComponents(stack,portion));
        GameplayLog.event(entity, "habitat", "stored meal handed off home={} store={} item={} taken={} stock={} -> {} nutrition={}",
                getBlockPos(), pile.getBlockPos(), portion.getItem(), taken.getCount(), stockBefore, pile.food(), entity.life().nutrition());
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
        if(kind()==ShelterBlock.Kind.BURROW&&naturalPile!=null)return ensureInnerPile(server);
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
                    ||contains(cell)||cell.equals(mouth)||!SupplyPiles.supported(server,cell)
                    ||!NaturalShelter.stand(server,stand))continue;
            if(!server.setBlock(cell,ModBlocks.FORAGE_PILE.get().defaultBlockState(),3))continue;
            if(server.getBlockEntity(cell) instanceof ForagePileBlockEntity placed)return link(placed,cells[i],sides[i]);
            server.removeBlock(cell,false);
        }
        return null;
    }
    /** The store cell lies inside the cavity, off the line from the mouth to the nest; nothing is ever put outside the door. */
    private ForagePileBlockEntity ensureInnerPile(ServerLevel server) {
        var cell=naturalPile;Direction side=null;
        for(var dir:Direction.Plane.HORIZONTAL)if(naturalPileStand!=null&&cell.relative(dir).equals(naturalPileStand))side=dir;
        if(side==null||!server.hasChunkAt(cell))return null;
        if(server.getBlockEntity(cell) instanceof ForagePileBlockEntity free)return free.ownerFree(server,worldPosition)?link(free,cell,side):null;
        if(!server.getBlockState(cell).isAir()||!server.getFluidState(cell).isEmpty()||!SupplyPiles.supported(server,cell)
                ||!NaturalShelter.stand(server,naturalPileStand))return null;
        if(!server.setBlock(cell,ModBlocks.FORAGE_PILE.get().defaultBlockState(),3))return null;
        if(server.getBlockEntity(cell) instanceof ForagePileBlockEntity placed)return link(placed,cell,side);
        server.removeBlock(cell,false);return null;
    }
    private ForagePileBlockEntity link(ForagePileBlockEntity pile,BlockPos cell,Direction side) {
        pile.setApproach(side);pile.setOwner(worldPosition);pilePos=cell.immutable();
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
            for(var id:List.copyOf(nestProjects.keySet()))if(!owns(id))dropNestProject(id);
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
                &&SettlementEvents.tryCollapse(server,this))return;
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
            for(var id:List.copyOf(nestProjects.keySet()))dropNestProject(id);
            Containers.dropItemStack(level,worldPosition.getX()+.5,worldPosition.getY(),worldPosition.getZ()+.5,
                    new ItemStack(kind()==ShelterBlock.Kind.CARDBOARD?Items.PAPER:Items.STICK,kind()==ShelterBlock.Kind.CARDBOARD?(condition>0?3:1):branches));
        }
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
    private void changed() {
        setChanged();
        if(level==null||level.isClientSide)return;
        var update=getUpdateTag(level.registryAccess());
        if(update.equals(lastSent))return;
        lastSent=update;
        level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),3);
    }
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider registries) {
        super.saveAdditional(tag,registries);tag.putInt("Version",3);saveSite(tag);
        if(pilePos!=null)tag.putLong("Pile",pilePos.asLong());
        tag.putInt("Condition",condition);tag.putInt("Moisture",moisture);tag.putInt("Lining",lining);tag.putInt("Dirt",dirt);tag.putInt("Ticks",ticks);
        saveResidents(tag,registries);
    }
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider registries) {
        super.loadAdditional(tag,registries);loadSite(tag);
        pilePos=tag.contains("Pile")?BlockPos.of(tag.getLong("Pile")):null;
        condition=Math.clamp(tag.getInt("Condition"),0,100);moisture=Math.clamp(tag.getInt("Moisture"),0,100);lining=Math.clamp(tag.getInt("Lining"),0,4);
                dirt=Math.clamp(tag.getInt("Dirt"),0,100);ticks=Math.max(0,tag.getInt("Ticks"));
        loadResidents(tag,registries);
    }
    private void saveResidents(CompoundTag tag,HolderLookup.Provider registries) {
        tag.put("RestingPlaces",NestResting.save(resting));
        tag.putLongArray("LargeBeds",largeBeds.stream().mapToLong(BlockPos::asLong).toArray());
        var largeWork=new ListTag();largeBedWork.forEach((pos,work)->{var entry=new CompoundTag();entry.putLong("Pos",pos.asLong());entry.putInt("Work",work);largeWork.add(entry);});tag.put("LargeBedWork",largeWork);
        ListTag residentsTag=new ListTag();residents.forEach((id,slot)->{var entry=new CompoundTag();entry.putUUID("Id",id);entry.putInt("Bed",slot);residentsTag.add(entry);});
                tag.put("Residents",residentsTag);
        ListTag privateTag=new ListTag();privateNests.forEach(id->{var entry=new CompoundTag();entry.putUUID("Id",id);privateTag.add(entry);});tag.put("PrivateNests",privateTag);
        ListTag projects=new ListTag();nestProjects.forEach((id,project)->{
            var entry=new CompoundTag();entry.putUUID("Id",id);entry.putInt("Work",project.work);
            var materials=new ListTag();for(var material:project.materials)materials.add(material.save(registries));
            entry.put("Materials",materials);projects.add(entry);
        });tag.put("NestProjects",projects);
    }
    private void loadResidents(CompoundTag tag,HolderLookup.Provider registries) {
        NestResting.load(tag.getList("RestingPlaces",Tag.TAG_COMPOUND),resting);
        largeBeds.clear();for(long pos:tag.getLongArray("LargeBeds"))if(largeBeds.size()<16)largeBeds.add(BlockPos.of(pos));
        largeBedWork.clear();for(var raw:tag.getList("LargeBedWork",Tag.TAG_COMPOUND)){var entry=(CompoundTag)raw;if(largeBedWork.size()<16)largeBedWork.put(BlockPos.of(entry.getLong("Pos")),Math.clamp(entry.getInt("Work"),0,120));}
        residents.clear();for(Tag raw:tag.getList("Residents",Tag.TAG_COMPOUND)){var entry=(CompoundTag)raw;int slot=entry.getInt("Bed");if(entry.hasUUID("Id")&&slot>=0
                &&slot<capacity())residents.put(entry.getUUID("Id"),slot);}
        privateNests.clear();for(Tag raw:tag.getList("PrivateNests",Tag.TAG_COMPOUND)){var entry=(CompoundTag)raw;if(entry.hasUUID("Id")
                &&residents.containsKey(entry.getUUID("Id")))privateNests.add(entry.getUUID("Id"));}
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
        if(naturalPile!=null&&naturalPileStand!=null){tag.putLong("NaturalPile",naturalPile.asLong());tag.putLong("NaturalPileStand",naturalPileStand.asLong());}
    }
    private void loadSite(CompoundTag tag) {
        naturalBeds=Arrays.stream(tag.getLongArray("NaturalBeds")).mapToObj(BlockPos::of)
                .filter(pos->pos.getY()==worldPosition.getY()&&Math.abs(pos.getX()-worldPosition.getX())<=NaturalShelter.RADIUS
                        &&Math.abs(pos.getZ()-worldPosition.getZ())<=NaturalShelter.RADIUS).distinct().limit(NaturalShelter.MAX_CELLS).toList();
        naturalPile=tag.contains("NaturalPile")&&tag.contains("NaturalPileStand")?BlockPos.of(tag.getLong("NaturalPile")):null;
        naturalPileStand=naturalPile==null?null:BlockPos.of(tag.getLong("NaturalPileStand"));
        if(naturalPile!=null&&(naturalPile.getY()!=worldPosition.getY()||naturalPile.distManhattan(naturalPileStand)!=1)){naturalPile=null;naturalPileStand=null;}
        branches=Math.clamp(tag.getInt("Branches"),0,3);
        naturalEntrance=tag.contains("NaturalEntrance")?BlockPos.of(tag.getLong("NaturalEntrance")):null;
        if(naturalEntrance!=null&&(!naturalEntrance.atY(worldPosition.getY()).equals(worldPosition.relative(facing()))
                ||naturalEntrance.getY()<worldPosition.getY()||naturalEntrance.getY()>worldPosition.getY()+1))naturalEntrance=null;
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        var tag=super.getUpdateTag(registries);if(pilePos!=null)tag.putLong("Pile",pilePos.asLong());saveSite(tag);saveResidents(tag,registries);tag.putInt("Condition",condition);
                tag.putInt("Moisture",moisture);tag.putInt("Lining",lining);tag.putInt("Dirt",dirt);return tag;
    }
    @Override public void handleUpdateTag(CompoundTag tag,HolderLookup.Provider registries) { pilePos=tag.contains("Pile")?BlockPos.of(tag.getLong("Pile")):null;loadSite(tag);
            loadResidents(tag,registries);condition=tag.getInt("Condition");moisture=tag.getInt("Moisture");lining=tag.getInt("Lining");dirt=tag.getInt("Dirt"); }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public void onDataPacket(Connection connection,ClientboundBlockEntityDataPacket packet,HolderLookup.Provider registries) {
        handleUpdateTag(packet.getTag(),registries);
    }
}
