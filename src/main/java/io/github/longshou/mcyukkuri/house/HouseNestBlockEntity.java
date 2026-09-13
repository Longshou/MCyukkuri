package io.github.longshou.mcyukkuri.house;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyConfig;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** One physical nest owns its stock. Eggs are references to the shared durable birth ledger. */
public final class HouseNestBlockEntity extends BlockEntity {
    public static final int MATERIAL_COST=6,STOCK_CAP=24;
    private UUID identity=UUID.randomUUID(),broodId;
    private int materials,food,dirt,ticks,arrivalTicks,arrivals,eggCount;
    private float progress;
    private boolean destroyed;
    public HouseNestBlockEntity(BlockPos pos,BlockState state){super(ModBlocks.HOUSE_NEST_ENTITY.get(),pos,state);}
    public UUID identity(){return identity;}
    public int materials(){return materials;}
    public int food(){return food;}
    public int dirt(){return dirt;}
    public int eggs(){return eggCount;}
    public float progress(){return progress;}
    public boolean built(){return materials>=MATERIAL_COST;}
    public FamilyData.Brood brood(){return level instanceof ServerLevel server?FamilyData.get(server).brood(broodId):null;}
    public static boolean isMaterial(ItemStack item){return item.is(Items.PAPER)||item.is(Items.WHEAT)||item.is(Items.STRING);}
    public boolean deposit(ItemStack item){
        if(destroyed||item.getCount()!=1)return false;
        if(item.is(ModItems.FOOD_SCRAP.get())&&food<STOCK_CAP)food++;
        else if(isMaterial(item)&&materials<MATERIAL_COST)materials++;
        else return false;
        changed();return true;
    }
    public ItemStack takeFood(){if(food==0||destroyed)return ItemStack.EMPTY;food--;changed();return new ItemStack(ModItems.FOOD_SCRAP.get());}
    public boolean usable(YukkuriEntity entity){return entity.getVariant().isHouse()&&!destroyed&&entity.life().dryStand(worldPosition.north())&&entity.life().covered(worldPosition.north());}
    public BlockPos entrance(){return worldPosition.north();}
    private List<YukkuriEntity> residents(){return level.getEntitiesOfClass(YukkuriEntity.class,new AABB(worldPosition).inflate(3),e->
            e.isAlive()&&e.getVariant().isHouse()&&worldPosition.equals(e.house().nestPosition()));}
    public void tick(){
        if(!(level instanceof ServerLevel server)||destroyed)return;
        ticks++;var residents=residents();
        // A fresh, supplied crevice can attract at most two founders, even after save/load.
        if(FamilyConfig.HOUSE_ARRIVALS.get()&&arrivals<2&&food>=4&&dirt<6&&level.canSeeSky(worldPosition.north())==false&&residents.size()<2){
            if(++arrivalTicks>=FamilyConfig.HOUSE_ARRIVAL_TIME.get()){
                arrivalTicks=0;
                if(FamilyData.get(server).population(server,worldPosition)<FamilyConfig.POPULATION_CAP.get()){
                    var entity=(arrivals==0?ModEntities.HOUSE_REIMU.get():ModEntities.HOUSE_MARISA.get()).create(server);
                    entity.moveTo(Vec3.atBottomCenterOf(entrance()));entity.setPersistenceRequired();
                    // The entrance is supported ground; pathfinding must see that before the first entity tick.
                    if(entity.life().dryStand(entrance()))entity.setOnGround(true);
                    if(entity.life().dryStand(entrance())&&server.noCollision(entity)&&entity.house().choose(this)&&server.addFreshEntity(entity)){
                        food-=4;arrivals++;entity.showReaction("surprise","house_arrival",100);changed();
                    }
                }
            }
        }
        if(ticks%600==0&&!residents.isEmpty()){dirt=Math.min(8,dirt+1);changed();}
        var brood=brood();
        if(brood!=null&&!brood.cancelled&&!brood.complete()){
            if(built()&&dirt<6&&food>0&&residents.stream().anyMatch(this::healthyCarer)){
                brood.elapsed=Math.min(brood.duration+40,brood.elapsed+1);
                if(brood.elapsed%600==0)food--;
                FamilyData.get(server).setDirty();
                for(int i=0;i<brood.children.size();i++)if(!brood.children.get(i).born){
                    Vec3 position=Vec3.atBottomCenterOf(entrance()).add((i%2-.5)*.25,0,-(i/2)*.25);
                    var baby=FamilyData.get(server).birth(brood,i,server,position);
                    if(baby!=null){baby.house().choose(this);baby.showReaction("happy","hatched",80);}
                }
            }
        } else if(ticks%(residents.stream().anyMatch(e->e.family().sexDrive()>0)?20:100)==0&&built()&&food>=4&&dirt<6){
            var candidates=residents.stream().filter(this::healthyCarer).sorted(java.util.Comparator.comparing(YukkuriEntity::getUUID)).toList();
            for(int i=0;i<candidates.size();i++)for(int j=i+1;j<candidates.size();j++){
                var a=candidates.get(i);var b=candidates.get(j);
                if((a.family().partner()==null||a.family().partner().equals(b.getUUID()))&&(b.family().partner()==null||b.family().partner().equals(a.getUUID()))){
                    var next=FamilyData.get(server).conceive(a,b,FamilyData.BirthKind.EGG,GlobalPos.of(level.dimension(),worldPosition));
                    if(next!=null){broodId=next.id;food-=4;a.showReaction("happy","laid_eggs",100);changed();return;}
                }
            }
        }
        brood=brood();int count=brood==null||brood.cancelled?0:brood.unborn();float next=brood==null?0:brood.progress();
        if(count!=eggCount||ticks%20==0&&next!=progress){eggCount=count;progress=next;changed();}
        if(ticks%200==0)setChanged();
    }
    private boolean healthyCarer(YukkuriEntity entity){return entity.family().adult()&&entity.life().nutrition()>=40&&entity.getHealth()>=entity.getMaxHealth()*.5
            &&entity.body().wound()==0&&entity.body().infection()==0&&!entity.isOnFire()&&!entity.isInWaterOrBubble()&&!entity.isPanicking()&&usable(entity);}
    /** Normal cleaning removes dirt and food; sneaking also dismantles this nest's eggs and lining. */
    public int clean(boolean dismantle){
        int removed=dirt+food;dirt=food=0;
        if(dismantle){removed+=materials+eggCount;materials=0;if(level instanceof ServerLevel server)FamilyData.get(server).cancel(brood());broodId=null;eggCount=0;progress=0;}
        changed();return removed;
    }
    public void destroyNest(){
        if(destroyed)return;destroyed=true;
        if(level instanceof ServerLevel server)FamilyData.get(server).cancel(brood());
        if(food>0)Block.popResource(level,worldPosition,new ItemStack(ModItems.FOOD_SCRAP.get(),food));
        if(materials+dirt>0)Block.popResource(level,worldPosition,new ItemStack(ModItems.WASTE.get(),materials+dirt));
        food=materials=dirt=eggCount=0;broodId=null;changed();
    }
    private void changed(){setChanged();if(level!=null&&!level.isClientSide)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),3);}
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider registries){
        super.saveAdditional(tag,registries);tag.putInt("Version",1);tag.putUUID("Identity",identity);if(broodId!=null)tag.putUUID("Brood",broodId);
        tag.putInt("Materials",materials);tag.putInt("Food",food);tag.putInt("Dirt",dirt);tag.putInt("Ticks",ticks);tag.putInt("ArrivalTicks",arrivalTicks);tag.putInt("Arrivals",arrivals);
        tag.putInt("EggCount",eggCount);tag.putFloat("Progress",progress);
    }
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider registries){
        super.loadAdditional(tag,registries);if(tag.hasUUID("Identity"))identity=tag.getUUID("Identity");broodId=tag.hasUUID("Brood")?tag.getUUID("Brood"):null;
        materials=Math.clamp(tag.getInt("Materials"),0,MATERIAL_COST);food=Math.clamp(tag.getInt("Food"),0,STOCK_CAP);dirt=Math.clamp(tag.getInt("Dirt"),0,8);
        ticks=Math.max(0,tag.getInt("Ticks"));arrivalTicks=Math.max(0,tag.getInt("ArrivalTicks"));arrivals=Math.clamp(tag.getInt("Arrivals"),0,2);
        eggCount=Math.clamp(tag.getInt("EggCount"),0,4);progress=Math.clamp(tag.getFloat("Progress"),0,1);
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries){return saveWithoutMetadata(registries);}
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
}
