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

/** A physical home: finite stores, contact care, and four stable egg places backed by the family ledger. */
public final class HouseNestBlockEntity extends BlockEntity {
    public static final int MATERIAL_COST=6,STOCK_CAP=24,EGG_CAP=4;
    private UUID identity=UUID.randomUUID(),broodId,carer;
    private int materials,food,dirt,ticks,arrivalTicks,arrivals,careTicks,disturbedTicks,maintenanceWait,hatchWait,incubationFoodTicks;
    private boolean destroyed,legacyEggs;
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
    public Vec3 eggPosition(int slot){return new Vec3(worldPosition.getX()+.61+(slot%2)*.11,worldPosition.getY()+.036,worldPosition.getZ()+.45+(slot/2)*.15);}
    public FamilyData.Brood brood(){return level instanceof ServerLevel server?FamilyData.get(server).brood(broodId):null;}
    public static boolean isMaterial(ItemStack item){return item.is(ModItems.NEST_LINT.get())||item.is(Items.STRING);}
    public boolean deposit(ItemStack item){
        if(destroyed||item.getCount()!=1)return false;
        if(item.is(ModItems.FOOD_SCRAP.get())&&food<STOCK_CAP)food++;
        else if(isMaterial(item)&&materials<MATERIAL_COST)materials++;
        else return false;
        changed();return true;
    }
    public ItemStack takeFood(){if(food==0||destroyed)return ItemStack.EMPTY;food--;changed();return new ItemStack(ModItems.FOOD_SCRAP.get());}
    public boolean usable(YukkuriEntity entity){
        return entity.getVariant().isHouse()&&!destroyed&&entity.life().dryStand(entrance())&&entity.life().covered(entrance());
    }
    public BlockPos entrance(){return worldPosition.north();}
    private boolean sheltered(){
        // Match living-entity shelter checks: actual roof blocks, not delayed skylight propagation.
        return level!=null&&level.hasChunkAt(entrance())
                &&level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                entrance().getX(),entrance().getZ())>entrance().getY();
    }
    private boolean atEggs(YukkuriEntity entity){
        if(!entity.onGround()||!entity.blockPosition().equals(worldPosition)||entity.getY()>worldPosition.getY()+.3)return false;
        return level.clip(new net.minecraft.world.level.ClipContext(entity.getEyePosition(),eggPosition(0).add(0,.06,0),
                net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,entity))
                .getType()==net.minecraft.world.phys.HitResult.Type.MISS;
    }
    private List<YukkuriEntity> residents(){
        return nearby.stream().filter(e->e.isAlive()&&worldPosition.equals(e.house().nestPosition())&&e.house().nest()==this).toList();
    }
    /** Called at the actual contact beat, not while a parent is merely walking towards the nest. */
    public boolean tend(YukkuriEntity entity){
        if(!(level instanceof ServerLevel)||!built()||destroyed||disturbedTicks>0||!healthyCarer(entity)
                ||entity.house().nest()!=this||entity.position().distanceToSqr(eggPosition(0))>1.21
                ||!atEggs(entity))return false;
        carer=entity.getUUID();careTicks=240;
        if(dirt>0&&maintenanceWait==0){
            dirt--;maintenanceWait=240;
            Block.popResource(level,entrance().north(),new ItemStack(ModItems.WASTE.get()));
        }
        changed();return true;
    }
    public ItemStack extractEgg(){
        if(!(level instanceof ServerLevel server)||destroyed)return ItemStack.EMPTY;
        migrateEggs(server);
        for(int slot=0;slot<EGG_CAP;slot++){
            var stack=HouseEggs.extract(server,eggSlots[slot],this);
            if(!stack.isEmpty()){eggSlots[slot]=null;syncEggs(server);changed();return stack;}
        }
        return ItemStack.EMPTY;
    }
    public boolean insertEgg(ItemStack stack){
        if(!(level instanceof ServerLevel server)||destroyed||!built()||!sheltered()
                ||!level.getFluidState(entrance()).isEmpty()||!level.getFluidState(worldPosition).isEmpty())return false;
        migrateEggs(server);syncEggs(server);
        for(int slot=0;slot<EGG_CAP;slot++)if(eggSlots[slot]==null&&hatchAges[slot]<0){
            var ref=HouseEggs.insert(server,stack,this);if(ref==null)return false;
            eggSlots[slot]=ref;syncEggs(server);changed();return true;
        }
        return false;
    }
    private void migrateEggs(ServerLevel server){
        if(!legacyEggs)return;legacyEggs=false;
        var brood=brood();
        if(brood!=null&&!brood.cancelled&&brood.kind==FamilyData.BirthKind.EGG
                &&GlobalPos.of(level.dimension(),worldPosition).equals(brood.anchor)){
            int slot=0;
            for(var child:brood.children)if(!child.born&&!child.eggLost&&slot<EGG_CAP){
                var ref=HouseEggs.attachNew(brood,child,this);if(ref!=null)eggSlots[slot++]=ref;
            }
            FamilyData.get(server).setDirty();
        }
        syncEggs(server);changed();
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
        ticks++;migrateEggs(server);
        if(ticks%20==1)nearby=level.getEntitiesOfClass(YukkuriEntity.class,new AABB(worldPosition).inflate(8),
                e->e.isAlive()&&e.getVariant().isHouse());
        var residents=residents();
        if(careTicks>0&&--careTicks==0){carer=null;changed();}
        if(disturbedTicks>0&&--disturbedTicks==0)changed();
        if(maintenanceWait>0)maintenanceWait--;
        if(hatchWait>0)hatchWait--;
        for(int slot=0;slot<EGG_CAP;slot++)if(hatchAges[slot]>=0&&++hatchAges[slot]>=120){hatchAges[slot]=-1;changed();}
        // At most two founders; recruitment is a quiet-night opportunity, not a hidden resident store.
        if(FamilyConfig.HOUSE_ARRIVALS.get()&&arrivals<2&&food>=4&&dirt<6&&level.isNight()
                &&disturbedTicks==0&&sheltered()&&residents.size()<2){
            if(++arrivalTicks>=FamilyConfig.HOUSE_ARRIVAL_TIME.get()){
                arrivalTicks=0;
                if(FamilyData.get(server).population(server,worldPosition)<FamilyConfig.POPULATION_CAP.get()){
                    var entity=(arrivals==0?ModEntities.HOUSE_REIMU.get():ModEntities.HOUSE_MARISA.get()).create(server);
                    if(entity!=null){
                        entity.moveTo(Vec3.atBottomCenterOf(entrance()));entity.setPersistenceRequired();
                        if(entity.life().dryStand(entrance()))entity.setOnGround(true);
                        if(entity.life().dryStand(entrance())&&server.noCollision(entity)&&entity.house().choose(this)&&server.addFreshEntity(entity)){
                            food-=4;arrivals++;entity.showReaction("surprise","house_arrival",100);changed();
                        }
                    }
                }
            }
        }
        if(ticks%600==0&&!residents.isEmpty()){dirt=Math.min(8,dirt+1);changed();}
        syncEggs(server);
        boolean cared=careTicks>0&&carer!=null&&server.getEntity(carer) instanceof YukkuriEntity parent
                &&healthyCarer(parent)&&parent.house().nest()==this&&atEggs(parent)&&parent.position().distanceToSqr(eggPosition(0))<1.5625;
        if(!cared&&careTicks>0){careTicks=0;carer=null;changed();}
        if(built()&&dirt<6&&food>0&&disturbedTicks==0&&cared&&eggs()>0){
            for(int slot=0;slot<EGG_CAP;slot++){
                var entry=HouseEggs.resolve(server,eggSlots[slot]);if(!HouseEggs.belongs(entry,this))continue;
                entry.child().eggElapsed=Math.min(entry.brood().duration,entry.child().eggElapsed+1);
                // The clutch summary remains useful to old notebook callers; custody is per egg.
                entry.brood().elapsed=Math.max(entry.brood().elapsed,entry.child().eggElapsed);
                if(hatchWait==0&&entry.progress()>=1){
                    var baby=FamilyData.get(server).birth(entry.brood(),entry.index(),server,eggPosition(slot));
                    if(baby!=null){
                        baby.setOnGround(true);baby.house().choose(this);baby.showReaction("happy","hatched",80);
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
        if(eggs()==0&&Arrays.stream(eggSlots).allMatch(java.util.Objects::isNull)
                &&Arrays.stream(hatchAges).allMatch(age->age<0)&&ticks%100==0&&built()&&food>=4&&dirt<6&&disturbedTicks==0){
            var candidates=residents.stream().filter(this::healthyCarer)
                    .filter(e->e.position().distanceToSqr(eggPosition(0))<2.25)
                    .sorted(java.util.Comparator.comparing(YukkuriEntity::getUUID)).toList();
            outer:for(int i=0;i<candidates.size();i++)for(int j=i+1;j<candidates.size();j++){
                var a=candidates.get(i);var b=candidates.get(j);
                if((a.family().partner()==null||a.family().partner().equals(b.getUUID()))&&(b.family().partner()==null||b.family().partner().equals(a.getUUID()))){
                    var next=FamilyData.get(server).conceive(a,b,FamilyData.BirthKind.EGG,GlobalPos.of(level.dimension(),worldPosition));
                    if(next!=null){
                        broodId=next.id;int slot=0;
                        for(var child:next.children)eggSlots[slot++]=HouseEggs.attachNew(next,child,this);
                        food-=4;a.showReaction("happy","laid_eggs",100);syncEggs(server);changed();break outer;
                    }
                }
            }
        }
        if(syncEggs(server)&&ticks%10==0)changed();
        if(ticks%20==0&&(careTicks>0||disturbedTicks>0||Arrays.stream(hatchAges).anyMatch(age->age>=0)))changed();
        if(ticks%200==0)setChanged();
    }
    private boolean healthyCarer(YukkuriEntity entity){
        return entity.isAlive()&&!entity.isNoAi()&&entity.family().adult()&&entity.life().nutrition()>=40
                &&entity.getHealth()>=entity.getMaxHealth()*.5&&entity.body().wound()==0&&entity.body().infection()==0
                &&!entity.isOnFire()&&!entity.isInWaterOrBubble()&&!entity.isPanicking()&&usable(entity);
    }
    private void disturb(boolean dismantle){
        disturbedTicks=dismantle?240:120;careTicks=0;carer=null;
        if(level!=null)for(var resident:level.getEntitiesOfClass(YukkuriEntity.class,new AABB(worldPosition).inflate(8),
                e->e.isAlive()&&e.getVariant().isHouse()&&worldPosition.equals(e.house().nestPosition())))resident.house().disturb(dismantle);
    }
    /** Gentle cleaning removes loose dirt and food. Dismantling only loses eggs still physically here. */
    public int clean(boolean dismantle){
        int removed=dirt+food;dirt=food=0;
        if(level instanceof ServerLevel server){
            migrateEggs(server);
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
        if(level instanceof ServerLevel server){
            migrateEggs(server);for(var ref:eggSlots)HouseEggs.lose(server,ref,this);
        }
        disturb(true);destroyed=true;
        if(food>0)Block.popResource(level,worldPosition,new ItemStack(ModItems.FOOD_SCRAP.get(),food));
        if(materials+dirt>0)Block.popResource(level,worldPosition,new ItemStack(ModItems.WASTE.get(),materials+dirt));
        food=materials=dirt=0;Arrays.fill(eggSlots,null);Arrays.fill(eggPresent,false);Arrays.fill(eggProgress,0);broodId=null;changed();
    }
    private void changed(){setChanged();if(level!=null&&!level.isClientSide)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),3);}
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider registries){
        super.saveAdditional(tag,registries);tag.putInt("Version",2);tag.putUUID("Identity",identity);if(broodId!=null)tag.putUUID("Brood",broodId);
        tag.putInt("Materials",materials);tag.putInt("Food",food);tag.putInt("Dirt",dirt);tag.putInt("Ticks",ticks);tag.putInt("ArrivalTicks",arrivalTicks);tag.putInt("Arrivals",arrivals);
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
        materials=Math.clamp(tag.getInt("Materials"),0,MATERIAL_COST);food=Math.clamp(tag.getInt("Food"),0,STOCK_CAP);dirt=Math.clamp(tag.getInt("Dirt"),0,8);
        ticks=Math.max(0,tag.getInt("Ticks"));arrivalTicks=Math.max(0,tag.getInt("ArrivalTicks"));arrivals=Math.clamp(tag.getInt("Arrivals"),0,2);
        careTicks=Math.clamp(tag.getInt("CareTicks"),0,240);carer=null;
        disturbedTicks=Math.clamp(tag.getInt("DisturbedTicks"),0,240);maintenanceWait=Math.clamp(tag.getInt("MaintenanceWait"),0,240);
        hatchWait=Math.clamp(tag.getInt("HatchWait"),0,48);incubationFoodTicks=Math.clamp(tag.getInt("IncubationFoodTicks"),0,599);
        Arrays.fill(eggSlots,null);Arrays.fill(eggPresent,false);Arrays.fill(eggProgress,0);Arrays.fill(hatchAges,-1);
        legacyEggs=!tag.contains("EggSlots");
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
