package io.github.longshou.mcyukkuri.house;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyConfig;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.Comparator;
import java.util.Objects;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;

/** A physical home: finite stores, contact care, and four stable egg places backed by the family ledger. */
public final class HouseNestBlockEntity extends BlockEntity {
    public static final int MATERIAL_COST=6,STOCK_CAP=24,EGG_CAP=4;
    private UUID identity=UUID.randomUUID(),broodId,carer;
    private int materials,food,dirt,pendingDebris,ticks,arrivalTicks,arrivals,careTicks,disturbedTicks,maintenanceWait,hatchWait,incubationFoodTicks;
    private boolean destroyed,founderReimu=true;
    private Direction entranceDirection=Direction.NORTH;
    private final HouseEggs.Ref[] eggSlots=new HouseEggs.Ref[EGG_CAP];
    private final float[] eggProgress=new float[EGG_CAP];
    private final boolean[] eggPresent=new boolean[EGG_CAP];
    private final int[] hatchAges={-1,-1,-1,-1};
    private long clientSyncAt;
    private List<YukkuriEntity> nearby=List.of();

    public HouseNestBlockEntity(BlockPos pos,BlockState state){super(ModBlocks.HOUSE_NEST_ENTITY.get(),pos,state);}
    public UUID identity(){return identity;}
    public int materials(){return materials;}
    public int food(){return food;}
    public int dirt(){return dirt;}
    public int eggs(){int count=0;for(boolean present:eggPresent)if(present)count++;return count;}
    public float progress(){float sum=0;for(int i=0;i<EGG_CAP;i++)if(eggPresent[i])sum+=eggProgress[i];return eggs()==0?0:sum/eggs();}
    public boolean built(){return materials>=MATERIAL_COST;}
    public int careTicks(){return careTicks;}
    public int disturbedTicks(){return disturbedTicks;}
    public boolean eggPresent(int slot){return slot>=0&&slot<EGG_CAP&&eggPresent[slot];}
    public float eggProgress(int slot){return slot>=0&&slot<EGG_CAP?eggProgress[slot]:0;}
    public int hatchAgeTicks(int slot){
        if(slot<0||slot>=EGG_CAP||hatchAges[slot]<0)return -1;
        int elapsed=level!=null&&level.isClientSide?(int)Math.clamp(level.getGameTime()-clientSyncAt,0,120):0;
        int age=hatchAges[slot]+elapsed;return age<120?age:-1;
    }
    public Vec3 eggLocalPosition(int slot){return HouseEggGeometry.slot(slot);}
    public Vec3 eggPosition(int slot){var local=eggLocalPosition(slot);return localPosition(local.x,local.y,local.z);}
    public Direction entranceDirection(){return entranceDirection;}
    public void setEntrance(Direction direction){
        if(direction!=null&&direction.getAxis().isHorizontal()){entranceDirection=direction;changed();}
    }
    /** Coordinates are authored facing north and rotated with the accessible entrance. */
    public Vec3 localPosition(double x,double y,double z){
        double dx=x-.5,dz=z-.5;
        var rotated=switch(entranceDirection){
            case EAST -> new Vec3(-dz,y,dx);
            case SOUTH -> new Vec3(-dx,y,-dz);
            case WEST -> new Vec3(dz,y,-dx);
            default -> new Vec3(dx,y,dz);
        };
        return Vec3.atBottomCenterOf(worldPosition).add(rotated);
    }
    public Vec3 toLocal(Vec3 point){
        var delta=point.subtract(Vec3.atBottomCenterOf(worldPosition));
        return switch(entranceDirection){
            case EAST -> new Vec3(delta.z+.5,delta.y,-delta.x+.5);
            case SOUTH -> new Vec3(-delta.x+.5,delta.y,-delta.z+.5);
            case WEST -> new Vec3(-delta.z+.5,delta.y,delta.x+.5);
            default -> delta.add(.5,0,.5);
        };
    }
    public FamilyData.Brood brood(){return level instanceof ServerLevel server?FamilyData.get(server).brood(broodId):null;}
    public static boolean isMaterial(ItemStack item){return SupplyPiles.lint(item);}
    public boolean deposit(ItemStack item){
        if(destroyed||item.getCount()!=1)return false;
        if(SupplyPiles.crumb(item)&&food<STOCK_CAP)food++;
        else if(isMaterial(item)&&materials<MATERIAL_COST)materials++;
        else return false;
        changed();return true;
    }
    public ItemStack takeFood(){if(food==0||destroyed)return ItemStack.EMPTY;food--;changed();return SupplyPiles.crumbUnit();}
    public boolean usable(YukkuriEntity entity){
        return entity.profile().houseBody()&&!destroyed&&entity.life().dryStand(entrance())&&entity.life().covered(entrance());
    }
    public BlockPos entrance(){return worldPosition.relative(entranceDirection);}
    /** Residents keep this much crumb stock in hand: enough for a clutch and its incubation. */
    public static final int FOOD_GOAL=12;
    public boolean destroyed(){return destroyed;}
    /** The parents can clear one unit of dirt only once per maintenance wait; tending sooner would achieve nothing. */
    public boolean canClean(){return maintenanceWait==0;}
    /** A founder made the nest; its partner is the other kind of house yukkuri and arrives later. */
    public void markFounder(boolean reimu){founderReimu=reimu;arrivals=Math.max(arrivals,1);changed();}
    /** Too dirty, being disturbed, or covered by a repellent: parents would move the clutch if they could. */
    public boolean unfit(){
        return destroyed||dirt>=6||disturbedTicks>0||level instanceof ServerLevel server&&HouseholdProtection.protectedAt(server,worldPosition);
    }
    public boolean hasEggRoom(){
        if(destroyed||!built())return false;
        for(int slot=0;slot<EGG_CAP;slot++)if(eggSlots[slot]==null&&hatchAges[slot]<0)return true;
        return false;
    }
    /**
     * Starts an ordinary house-egg brood and installs every child in this nest in one transaction.
     * Callers still establish any relationship or scenario preconditions themselves; this method never
     * manufactures tickets or writes child identity outside {@link FamilyData#conceive}.
     */
    public boolean createClutch(YukkuriEntity mother,YukkuriEntity father){
        if(!(level instanceof ServerLevel server)||mother==null||father==null||mother==father||mother.level()!=server||father.level()!=server
                ||mother.house().nest()!=this||father.house().nest()!=this||!readyForNewClutch())return false;
        var data=FamilyData.get(server);
        var brood=data.conceive(mother,father,FamilyData.BirthKind.EGG,GlobalPos.of(server.dimension(),worldPosition));
        if(brood==null)return false;
        if(installNewClutch(server,brood))return true;
        // All installation gates were checked before conceiving, so this is defensive only.  Do not leave an
        // unborn ledger entry if a future change adds an installation failure after conception.
        data.cancel(brood);return false;
    }
    private boolean readyForNewClutch(){
        return !unfit()&&built()&&food>=4&&eggs()==0&&Arrays.stream(eggSlots).allMatch(Objects::isNull)
                &&Arrays.stream(hatchAges).allMatch(age->age<0);
    }
    /** Atomically claims all members of a freshly recorded EGG brood; callers never see a partial clutch. */
    private boolean installNewClutch(ServerLevel server,FamilyData.Brood brood){
        if(brood==null||brood.cancelled||brood.kind!=FamilyData.BirthKind.EGG||FamilyData.get(server).brood(brood.id)!=brood
                ||!readyForNewClutch()||brood.children.isEmpty()||brood.children.size()>EGG_CAP)return false;
        for(var child:brood.children)
            if(child==null||child.born||child.eggLost||child.eggManaged||child.eggNest!=null||child.eggNestId!=null||child.eggToken!=null)return false;
        broodId=brood.id;
        for(int slot=0;slot<brood.children.size();slot++)eggSlots[slot]=HouseEggs.attachNew(brood,brood.children.get(slot),this);
        food-=4;syncEggs(server);changed();FamilyData.get(server).setDirty();return true;
    }
    private boolean sheltered(){
        // Match living-entity shelter checks: actual roof blocks, not delayed skylight propagation.
        return level!=null&&level.hasChunkAt(entrance())
                &&level.getHeight(Heightmap.Types.MOTION_BLOCKING,
                entrance().getX(),entrance().getZ())>entrance().getY();
    }
    private boolean atEggs(YukkuriEntity entity){
        if(!entity.onGround()||!entity.blockPosition().equals(worldPosition)||entity.getY()>worldPosition.getY()+.3)return false;
        return level.clip(new ClipContext(entity.getEyePosition(),eggPosition(0).add(0,.06,0),
                ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,entity))
                .getType()==HitResult.Type.MISS;
    }
    private List<YukkuriEntity> residents(){
        return nearby.stream().filter(e->e.isAlive()&&worldPosition.equals(e.house().nestPosition())&&e.house().nest()==this).toList();
    }
    /** Called at the actual contact beat, not while a parent is merely walking towards the nest. */
    public boolean tend(YukkuriEntity entity){
        if(!(level instanceof ServerLevel server)||HouseholdProtection.protectedAt(server,worldPosition)||!built()||destroyed||disturbedTicks>0||!healthyCarer(entity)
                ||entity.house().nest()!=this||entity.position().distanceToSqr(eggPosition(0))>1.21
                ||!atEggs(entity))return false;
        carer=entity.getUUID();careTicks=240;
        if(dirt>0&&maintenanceWait==0){
            // One attempt per wait: when the floor outside cannot take more waste the nest simply stays dirty.
            if(HouseholdEcology.residual(server,entrance().relative(entranceDirection),new ItemStack(ModItems.WASTE.get()))==1)dirt--;
            maintenanceWait=240;
        }
        changed();return true;
    }
    /** A parent standing at the eggs takes one in its mouth to carry it elsewhere. */
    public ItemStack lift(YukkuriEntity entity){
        if(destroyed||!(level instanceof ServerLevel)||entity.house().nest()!=this||entity.position().distanceToSqr(eggPosition(0))>1.21||!atEggs(entity))return ItemStack.EMPTY;
        return extractEgg();
    }
    public ItemStack extractEgg(){
        if(!(level instanceof ServerLevel server)||destroyed)return ItemStack.EMPTY;
        for(int slot=0;slot<EGG_CAP;slot++){
            var stack=HouseEggs.extract(server,eggSlots[slot],this);
            if(!stack.isEmpty()){eggSlots[slot]=null;syncEggs(server);changed();return stack;}
        }
        return ItemStack.EMPTY;
    }
    public boolean insertEgg(ItemStack stack){
        if(!(level instanceof ServerLevel server)||destroyed||!built()||!sheltered()
                ||!level.getFluidState(entrance()).isEmpty()||!level.getFluidState(worldPosition).isEmpty())return false;
        syncEggs(server);
        for(int slot=0;slot<EGG_CAP;slot++)if(eggSlots[slot]==null&&hatchAges[slot]<0){
            var ref=HouseEggs.insert(server,stack,this);if(ref==null)return false;
            eggSlots[slot]=ref;syncEggs(server);changed();return true;
        }
        return false;
    }
    private boolean syncEggs(ServerLevel server){
        boolean changed=false;
        for(int slot=0;slot<EGG_CAP;slot++){
            var entry=HouseEggs.resolve(server,eggSlots[slot]);
            boolean present=HouseEggs.belongs(entry,this);float next=present?entry.progress():0;
            if(!present)eggSlots[slot]=null;
            changed|=eggPresent[slot]!=present||eggProgress[slot]!=next;
            eggPresent[slot]=present;eggProgress[slot]=next;
        }
        return changed;
    }
    public void tick(){
        if(!(level instanceof ServerLevel server)||destroyed)return;
        boolean protectedHome=HouseholdProtection.protectedAt(server,worldPosition);
        ticks++;
        if(ticks%20==1)nearby=level.getEntitiesOfClass(YukkuriEntity.class,new AABB(worldPosition).inflate(8),
                e->e.isAlive()&&e.profile().houseBody());
        var residents=residents();
        if(careTicks>0&&--careTicks==0){carer=null;changed();}
        if(disturbedTicks>0&&--disturbedTicks==0)changed();
        if(maintenanceWait>0)maintenanceWait--;
        if(hatchWait>0)hatchWait--;
        for(int slot=0;slot<EGG_CAP;slot++)if(hatchAges[slot]>=0&&++hatchAges[slot]>=120){
            hatchAges[slot]=-1;
            // A shell becomes one unit of nest debris; full nests eject the real excess.
            if(dirt<8)dirt++;
            else if(HouseholdEcology.residual(server,entrance().relative(entranceDirection),new ItemStack(ModItems.WASTE.get()))==0)pendingDebris++;
            changed();
        }
        if(pendingDebris>0&&dirt<8){pendingDebris--;dirt++;changed();}
        // Existing crumbs OR nesting fibers are enough for opportunistic settlers, in daylight too.
        if(HouseholdEcology.enabled(server)
                &&arrivals<2&&(food>0||materials>0)&&disturbedTicks==0&&sheltered()&&residents.size()<2
                &&HouseholdEcology.recentlyActive(server,worldPosition)&&!HouseholdEcology.suppressed(server,worldPosition)
                &&HouseholdEcology.shelteredCorner(server,worldPosition)){
            if(++arrivalTicks>=FamilyConfig.HOUSE_ARRIVAL_TIME.get()){
                arrivalTicks=0;
                if(FamilyData.get(server).population(server,worldPosition)<FamilyConfig.POPULATION_CAP.get()){
                    // The partner of the founder is the other kind of house yukkuri.
                    var entity=(arrivals==0==founderReimu?ModEntities.HOUSE_REIMU.get():ModEntities.HOUSE_MARISA.get()).create(server);
                    if(entity!=null){
                        entity.moveTo(Vec3.atBottomCenterOf(entrance()));entity.setPersistenceRequired();
                        if(entity.life().dryStand(entrance()))entity.setOnGround(true);
                        if(entity.life().dryStand(entrance())&&server.noCollision(entity)&&entity.house().choose(this)&&server.addFreshEntity(entity)){
                            arrivals++;entity.showReaction("surprise","house_arrival",100);changed();
                        }
                    }
                }
            }
        }
        if(ticks%600==0&&!residents.isEmpty()){dirt=Math.min(8,dirt+1);changed();}
        syncEggs(server);
        boolean cared=!protectedHome&&careTicks>0&&carer!=null&&server.getEntity(carer) instanceof YukkuriEntity parent
                &&healthyCarer(parent)&&parent.house().nest()==this&&atEggs(parent)&&parent.position().distanceToSqr(eggPosition(0))<1.5625;
        if(!cared&&careTicks>0){careTicks=0;carer=null;changed();}
        if(built()&&dirt<6&&food>0&&disturbedTicks==0&&cared&&eggs()>0){
            for(int slot=0;slot<EGG_CAP;slot++){
                var entry=HouseEggs.resolve(server,eggSlots[slot]);if(!HouseEggs.belongs(entry,this))continue;
                entry.child().eggElapsed=Math.min(entry.brood().duration,entry.child().eggElapsed+1);
                // The clutch summary remains useful to old notebook callers; custody is per egg.
                entry.brood().elapsed=Math.max(entry.brood().elapsed,entry.child().eggElapsed);
                if(hatchWait==0&&entry.progress()>=1){
                    var baby=FamilyData.get(server).birth(entry.brood(),entry.index(),server,eggPosition(slot).add(0,HouseEggGeometry.HATCH_FOOT_LIFT,0));
                    if(baby!=null){
                        baby.house().choose(this);baby.showReaction("happy","hatched",80);
                        eggSlots[slot]=null;hatchAges[slot]=0;hatchWait=48;
                        if(server.getEntity(carer) instanceof YukkuriEntity witness)witness.showReaction("happy","house_hatched",80);
                        syncEggs(server);
                        changed();
                    }
                }
            }
            if(++incubationFoodTicks>=600){incubationFoodTicks=0;food--;changed();}
            FamilyData.get(server).setDirty();
        }
        // Founding a clutch needs both parents physically at home. A transferred clutch occupies real slots.
        if(!protectedHome&&ticks%100==0&&readyForNewClutch()){
            var candidates=residents.stream().filter(this::healthyCarer)
                    .filter(e->e.position().distanceToSqr(eggPosition(0))<2.25)
                    .sorted(Comparator.comparing(YukkuriEntity::getUUID)).toList();
            outer:for(int i=0;i<candidates.size();i++)for(int j=i+1;j<candidates.size();j++){
                var a=candidates.get(i);var b=candidates.get(j);
                if((a.family().partner()==null||a.family().partner().equals(b.getUUID()))&&(b.family().partner()==null||b.family().partner().equals(a.getUUID()))){
                    if(createClutch(a,b)){a.showReaction("happy","laid_eggs",100);break outer;}
                }
            }
        }
        if(syncEggs(server)&&ticks%10==0)changed();
        if(ticks%20==0&&(careTicks>0||disturbedTicks>0||Arrays.stream(hatchAges).anyMatch(age->age>=0)))changed();
        if(ticks%200==0)setChanged();
    }
    private boolean healthyCarer(YukkuriEntity entity){
        return entity.isAlive()&&!entity.isNoAi()&&entity.family().adult()&&entity.life().nutrition()>=40
                &&entity.getHealth()>=entity.getMaxHealth()*.5&&entity.body().condition().readyForHouseCare()
                &&!entity.isOnFire()&&!entity.isInWaterOrBubble()&&!entity.isPanicking()&&usable(entity);
    }
    private void disturb(boolean dismantle){
        disturbedTicks=dismantle?240:120;careTicks=0;carer=null;
        if(level!=null)for(var resident:level.getEntitiesOfClass(YukkuriEntity.class,new AABB(worldPosition).inflate(8),
                e->e.isAlive()&&e.profile().houseBody()&&worldPosition.equals(e.house().nestPosition())))resident.house().disturb(dismantle);
    }
    /** Gentle cleaning removes loose dirt and food. Dismantling only loses eggs still physically here. */
    public int clean(boolean dismantle){
        int removed=dirt+pendingDebris+food+(int)Arrays.stream(hatchAges).filter(age->age>=0).count();dirt=food=pendingDebris=0;
        Arrays.fill(hatchAges,-1);
        if(level instanceof ServerLevel server){
            if(dismantle){
                removed+=materials+eggs();materials=0;
                for(var ref:eggSlots)HouseEggs.lose(server,ref,this);
                Arrays.fill(eggSlots,null);Arrays.fill(hatchAges,-1);syncEggs(server);broodId=null;
            }
        }
        if(removed>0||dismantle)disturb(dismantle);
        changed();return removed;
    }
    public void destroyNest(){
        if(destroyed)return;
        int debris=materials+dirt+pendingDebris+(int)Arrays.stream(hatchAges).filter(age->age>=0).count();
        if(level instanceof ServerLevel server){
            syncEggs(server);debris+=eggs();
            for(var ref:eggSlots)HouseEggs.lose(server,ref,this);
        }
        disturb(true);destroyed=true;
        if(debris>0)Block.popResource(level,worldPosition,new ItemStack(ModItems.WASTE.get(),debris));
        food=materials=dirt=pendingDebris=0;Arrays.fill(eggSlots,null);Arrays.fill(eggPresent,false);Arrays.fill(eggProgress,0);broodId=null;changed();
    }
    private void changed(){setChanged();if(level!=null&&!level.isClientSide)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),3);}
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider registries){
        super.saveAdditional(tag,registries);tag.putInt("Version",2);tag.putUUID("Identity",identity);if(broodId!=null)tag.putUUID("Brood",broodId);
        tag.putString("Entrance",entranceDirection.getName());
        tag.putInt("Materials",materials);tag.putInt("Food",food);tag.putInt("Dirt",dirt);tag.putInt("Ticks",ticks);tag.putInt("ArrivalTicks",arrivalTicks);tag.putInt("Arrivals",arrivals);
        tag.putInt("PendingDebris",pendingDebris);tag.putBoolean("FounderReimu",founderReimu);
        tag.putInt("CareTicks",careTicks);tag.putInt("DisturbedTicks",disturbedTicks);tag.putInt("MaintenanceWait",maintenanceWait);
        tag.putInt("HatchWait",hatchWait);tag.putInt("IncubationFoodTicks",incubationFoodTicks);
        var slots=new ListTag();
        for(int slot=0;slot<EGG_CAP;slot++){
            var data=eggSlots[slot]==null?new CompoundTag():eggSlots[slot].save();
            data.putInt("Slot",slot);data.putBoolean("Present",eggPresent[slot]);data.putFloat("Progress",eggProgress[slot]);data.putInt("HatchAge",hatchAges[slot]);slots.add(data);
        }
        tag.put("EggSlots",slots);
    }
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider registries){
        super.loadAdditional(tag,registries);if(tag.hasUUID("Identity"))identity=tag.getUUID("Identity");broodId=tag.hasUUID("Brood")?tag.getUUID("Brood"):null;
        var direction=Direction.byName(tag.getString("Entrance"));
        entranceDirection=direction!=null&&direction.getAxis().isHorizontal()?direction:Direction.NORTH;
        materials=Math.clamp(tag.getInt("Materials"),0,MATERIAL_COST);food=Math.clamp(tag.getInt("Food"),0,STOCK_CAP);dirt=Math.clamp(tag.getInt("Dirt"),0,8);
        pendingDebris=Math.clamp(tag.getInt("PendingDebris"),0,EGG_CAP);founderReimu=!tag.contains("FounderReimu")||tag.getBoolean("FounderReimu");
        ticks=Math.max(0,tag.getInt("Ticks"));arrivalTicks=Math.max(0,tag.getInt("ArrivalTicks"));arrivals=Math.clamp(tag.getInt("Arrivals"),0,2);
        careTicks=Math.clamp(tag.getInt("CareTicks"),0,240);carer=null;
        disturbedTicks=Math.clamp(tag.getInt("DisturbedTicks"),0,240);maintenanceWait=Math.clamp(tag.getInt("MaintenanceWait"),0,240);
        hatchWait=Math.clamp(tag.getInt("HatchWait"),0,48);incubationFoodTicks=Math.clamp(tag.getInt("IncubationFoodTicks"),0,599);
        Arrays.fill(eggSlots,null);Arrays.fill(eggPresent,false);Arrays.fill(eggProgress,0);Arrays.fill(hatchAges,-1);
        for(Tag entry:tag.getList("EggSlots",Tag.TAG_COMPOUND)){
            var data=(CompoundTag)entry;int slot=data.getInt("Slot");if(slot<0||slot>=EGG_CAP)continue;
            eggSlots[slot]=HouseEggs.Ref.load(data);eggPresent[slot]=data.getBoolean("Present");eggProgress[slot]=Math.clamp(data.getFloat("Progress"),0,1);
            hatchAges[slot]=Math.clamp(data.getInt("HatchAge"),-1,119);
        }
        clientSyncAt=level==null?0:level.getGameTime();
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries){return saveWithoutMetadata(registries);}
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
}
