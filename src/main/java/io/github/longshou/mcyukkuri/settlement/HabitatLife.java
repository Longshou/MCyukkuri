package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/** A real resident, never a hidden duplicate: carries one resource, maintains stores and clears waste. */
public final class HabitatLife {
    private enum Task { NONE, FOOD, PICKUP, HARVEST, DEPOSIT, CLEAN }
    private final YukkuriEntity entity;
    private GlobalPos home;
    private ItemStack carrying=ItemStack.EMPTY;
    private ItemEntity targetItem;
    private BlockPos targetBlock;
    private Task task=Task.NONE;
    private int wait,ticks;
    public HabitatLife(YukkuriEntity entity) { this.entity=entity; }
    private ServerLevel level() { return (ServerLevel)entity.level(); }
    public boolean hasMemory() { return home!=null; }
    public boolean unavailable() { return home!=null&&(!home.dimension().equals(entity.level().dimension())||!entity.level().hasChunkAt(home.pos())); }
    public ShelterBlockEntity shelter() { return home==null||unavailable()?null:ShelterBlock.shelter(entity.level(),home.pos()); }
    public ItemStack carrying() { return carrying; }
    public boolean active() { return task!=Task.NONE; }
    public String activity() { return task==Task.CLEAN?"shelter_clean":task==Task.DEPOSIT?"shelter_carry":task==Task.HARVEST||task==Task.PICKUP?"shelter_gather":"shelter_stock"; }
    public BlockPos homeStand() { var value=shelter();return value!=null&&value.usable(entity)?value.bed(entity.getUUID()):null; }
    public boolean leaking() {
        var value=shelter();
        return value!=null&&value.moisture()>=60&&value.contains(entity.blockPosition());
    }
    public boolean choose(ShelterBlockEntity value) {
        if(entity.getVariant().isHouse()||entity.getVariant().isPredator()||!value.sound()||value.moisture()>=85||value.dirt()>=90||entity.distanceToSqr(Vec3.atBottomCenterOf(value.getBlockPos()))>32*32)return false;
        boolean owned=value.owns(entity.getUUID());
        if(!value.claim(entity))return false;
        BlockPos bed=value.bed(entity.getUUID());
        if(!entity.life().dryStand(bed)||!entity.life().reachable(bed,false)){if(!owned)value.release(entity.getUUID());return false;}
        var old=shelter();
        if(!entity.life().assignNaturalHome(bed)){if(!owned)value.release(entity.getUUID());return false;}
        if(old!=null&&old!=value)old.release(entity.getUUID());
        home=GlobalPos.of(entity.level().dimension(),value.getBlockPos());entity.setPersistenceRequired();return true;
    }
    public boolean inherit(YukkuriEntity parent) { var value=parent.habitat().shelter();return value!=null&&choose(value); }
    public void release() { var value=shelter();if(value!=null)value.release(entity.getUUID());home=null;stop(); }
    public boolean findHome() {
        if(entity.getVariant().isHouse()||entity.getVariant().isPredator()||unavailable())return false;
        for(BlockPos pos:BlockPos.betweenClosed(entity.blockPosition().offset(-12,-2,-12),entity.blockPosition().offset(12,2,12)))
            if(level().hasChunkAt(pos)&&level().getBlockEntity(pos) instanceof ShelterBlockEntity value&&choose(value))return true;
        if(!EventHooks.canEntityGrief(level(),entity))return false;
        for(BlockPos pos:BlockPos.betweenClosed(entity.blockPosition().offset(-8,-2,-8),entity.blockPosition().offset(8,2,8))) {
            if(!NaturalShelter.enclosed(level(),pos))continue;
            for(var direction:net.minecraft.core.Direction.Plane.HORIZONTAL) {
                var plan=new ShelterPlacement.Plan(pos.immutable(),ShelterBlock.Kind.BURROW,direction);
                if(!ShelterPlacement.canPlace(level(),plan)||!entity.life().reachable(pos,false))continue;
                var placed=ShelterPlacement.place(level(),plan);
                if(placed!=null){if(choose(placed.shelter()))return true;placed.rollback(level());}
            }
        }
        return false;
    }
    public void tick() {
        if(entity.level().isClientSide||entity.isNoAi()||entity.getVariant().isHouse())return;
        if(wait>0)wait--;
        if(entity.tickCount%100!=0)return;
        var value=shelter();boolean inside=value!=null&&value.contains(entity.blockPosition());
        int wet=inside&&value.moisture()>=60?3:0;
        int hygiene=inside&&value.dirt()>=50?2:0;
        entity.life().applyEnvironment(wet,hygiene,0);
    }
    private boolean ready() {
        return !entity.getVariant().isHouse()&&!entity.isPanicking()&&!entity.isCommandPlaying()&&!entity.operations().active()
                &&!entity.life().isSleeping()&&!entity.isInWaterOrBubble()&&!entity.isOnFire()&&entity.life().mealTicks()==0;
    }
    public boolean canUse() {
        if(!ready())return false;
        if(entity.independence().householdOuting()&&!entity.life().canEat()&&carrying.isEmpty())return false;
        if(active())return true;
        if(wait>0)return false;wait=60;
        var value=shelter();if(value==null||!value.usable(entity))return false;
        if(entity.life().canEat()&&value.food()>0){task=Task.FOOD;targetBlock=value.bed(entity.getUUID());ticks=0;return true;}
        if(carrying.isEmpty()&&(entity.settlement().society().dejected()||!entity.settlement().duties().carrying().isEmpty()))return false;
        if(!EventHooks.canEntityGrief(level(),entity)||entity.life().nutrition()<40||entity.life().energy()<35)return false;
        if(!carrying.isEmpty()){task=carrying.is(ModItems.WASTE.get())?Task.CLEAN:Task.DEPOSIT;ticks=0;return true;}
        targetItem=value.wasteInside().stream().filter(item->!item.hasPickUpDelay()).findFirst().orElse(null);
        if(targetItem==null)targetItem=level().getEntitiesOfClass(ItemEntity.class,entity.getBoundingBox().inflate(10),item->item.isAlive()&&!item.hasPickUpDelay()
                &&(value.needsMaterial(item.getItem())||item.getItem().is(YukkuriEntity.FOOD)&&value.food()<Math.min(24,value.occupants()*3)))
                .stream().filter(item->entity.life().reachable(item.blockPosition(),false)).min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
        if(targetItem!=null){task=Task.PICKUP;ticks=0;return true;}
        if(value.food()<Math.min(24,value.occupants()*3)||value.needsMaterial(new ItemStack(Items.SHORT_GRASS))) {
            for(var pos:BlockPos.betweenClosed(entity.blockPosition().offset(-8,-2,-8),entity.blockPosition().offset(8,2,8)))
                if(level().hasChunkAt(pos)&&level().getBlockState(pos).is(Blocks.SHORT_GRASS)&&entity.life().dryStand(pos)&&entity.life().reachable(pos,false)){
                    targetBlock=pos.immutable();task=Task.HARVEST;ticks=0;return true;
                }
        }
        return false;
    }
    public boolean canContinue() {
        return active()&&ready()&&ticks<400&&homeStand()!=null&&(task==Task.FOOD||EventHooks.canEntityGrief(level(),entity))
                &&(!entity.independence().householdOuting()||task==Task.FOOD||!carrying.isEmpty())
                &&(task==Task.FOOD||!carrying.isEmpty()||!entity.settlement().society().dejected());
    }
    public void tickGoal() {
        if(!canContinue()){stop();return;}ticks++;
        var value=shelter();
        if(task==Task.FOOD) {
            if(approach(value.bed(entity.getUUID()))) {
                var food=value.takeFood(entity);
                if(!food.isEmpty()&&!entity.life().beginMeal(food,null,false))value.returnFood(food);
                stop();
            }
        } else if(task==Task.PICKUP) {
            if(targetItem==null||!targetItem.isAlive()||targetItem.hasPickUpDelay()
                    ||!(targetItem.getItem().is(ModItems.WASTE.get())||value.accepts(targetItem.getItem()))){stop();return;}
            if(approach(targetItem.blockPosition())&&entity.distanceToSqr(targetItem)<2.25) {
                carrying=targetItem.getItem().split(1);if(targetItem.getItem().isEmpty())targetItem.discard();targetItem=null;
                task=carrying.is(ModItems.WASTE.get())?Task.CLEAN:Task.DEPOSIT;ticks=0;
            }
        } else if(task==Task.HARVEST) {
            if(targetBlock==null||!level().getBlockState(targetBlock).is(Blocks.SHORT_GRASS)){stop();return;}
            if(approach(targetBlock)&&level().setBlock(targetBlock,Blocks.AIR.defaultBlockState(),3)) {
                carrying=new ItemStack(Items.SHORT_GRASS);task=Task.DEPOSIT;ticks=0;
            }
        } else if(task==Task.DEPOSIT) {
            if(approach(value.bed(entity.getUUID()))) {
                if(value.deposit(carrying))carrying=ItemStack.EMPTY;else dropCarrying();
                stop();
            }
        } else if(task==Task.CLEAN) {
            BlockPos outside=value.entrance().relative(value.facing(),2);
            if(!entity.life().dryStand(outside)){stop();return;}
            if(approach(outside)){dropCarrying();value.cleaned();stop();}
        }
    }
    private boolean approach(BlockPos pos) {
        Vec3 goal=Vec3.atBottomCenterOf(pos);
        if(entity.distanceToSqr(goal)<.64){entity.getNavigation().stop();return true;}
        if(ticks%20==1) {
            if(entity.distanceToSqr(goal)>100)pos=BlockPos.containing(entity.position().add(goal.subtract(entity.position()).normalize().scale(7)));
            if(!entity.life().navigate(pos,1,false))stop();
        }
        return false;
    }
    public void stop() { task=Task.NONE;targetItem=null;targetBlock=null;wait=80;entity.getNavigation().stop(); }
    public void dropCarrying() { if(!carrying.isEmpty()){var drop=entity.spawnAtLocation(carrying.copy());if(drop!=null){drop.setPickUpDelay(100);carrying=ItemStack.EMPTY;}} }
    public CompoundTag save() {
        var tag=new CompoundTag();FamilyData.putPosition(tag,"Home",home);
        if(!carrying.isEmpty())tag.put("Carrying",carrying.save(entity.registryAccess()));return tag;
    }
    public void load(CompoundTag tag) {
        home=FamilyData.position(tag,"Home");carrying=tag.contains("Carrying")?ItemStack.parseOptional(entity.registryAccess(),tag.getCompound("Carrying")):ItemStack.EMPTY;
        task=Task.NONE;targetItem=null;targetBlock=null;wait=ticks=0;
    }
}
