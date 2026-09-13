package io.github.longshou.mcyukkuri.house;

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
import net.minecraft.world.phys.Vec3;

/** Carries one real scrap or material. No hidden duplicate resident is stored in the block. */
public final class HouseLife {
    private final YukkuriEntity entity;
    private GlobalPos nest;
    private ItemStack carrying=ItemStack.EMPTY;
    private ItemEntity target;
    private int wait,ticks,hiding;
    private boolean active;
    private int interaction,interactionTicks;
    private Vec3 contactTarget;
    public HouseLife(YukkuriEntity entity){this.entity=entity;}
    public ItemStack carrying(){return carrying;}
    public BlockPos nestPosition(){return nest!=null&&nest.dimension().equals(entity.level().dimension())?nest.pos():null;}
    public HouseNestBlockEntity nest(){return nestPosition()!=null&&entity.level().hasChunkAt(nestPosition())&&entity.level().getBlockEntity(nestPosition()) instanceof HouseNestBlockEntity value?value:null;}
    public boolean choose(HouseNestBlockEntity value){
        if(!entity.getVariant().isHouse()||!value.usable(entity)||!entity.life().reachable(value.entrance(),false))return false;
        nest=GlobalPos.of(entity.level().dimension(),value.getBlockPos());entity.setPersistenceRequired();return true;
    }
    public BlockPos homeStand(){var value=nest();return value!=null&&value.usable(entity)?value.entrance():null;}
    public boolean active(){return active;}
    public boolean wantsCrawl(){
        return active && interaction==0 && carrying.isEmpty() && (hiding==5 || hiding==3
                || target!=null && target.isAlive() && entity.distanceToSqr(target)<.36);
    }
    public boolean mustExit(){
        var value=nest();if(value==null)return false;var pos=value.getBlockPos();
        // A small mob may step onto the low roof while strolling. The vanilla path start can then
        // resolve inside this non-full block; walk off its north edge before asking for another path.
        boolean onRoof=entity.getY()>pos.getY()+.45&&entity.getY()<pos.getY()+1.2
                &&Math.abs(entity.getX()-(pos.getX()+.5))<.75&&entity.getZ()>pos.getZ()-.2&&entity.getZ()<pos.getZ()+1.2;
        return onRoof||entity.getZ()>pos.getZ()+.08&&entity.blockPosition().equals(pos)
                &&(entity.isPanicking()||entity.life().hasUrgentNeed()||!active&&entity.life().mealTicks()==0);
    }
    public void tickExit(){var value=nest();if(value==null)return;var exit=Vec3.atBottomCenterOf(value.entrance());
        entity.getNavigation().stop();entity.getMoveControl().setWantedPosition(exit.x,exit.y,exit.z,1.15);}
    public String activity(){return interaction==1?"house_pickup":interaction==2?"house_deposit":hiding==5?"house_enter":hiding==3?"house_exit":hiding==4?"house_peek":hiding>0?"house_hide":carrying.isEmpty()?"house_scavenge":"house_carry";}
    public void tick(){
        if(entity.level().isClientSide||!entity.getVariant().isHouse()||entity.isNoAi())return;
        if(wait>0)wait--;
        if(entity.isPanicking()||entity.isOnFire()||entity.isInWaterOrBubble()){stop();return;}
        if(entity.tickCount%100==0&&homeStand()==null){
            if(nest!=null&&(!nest.dimension().equals(entity.level().dimension())||!entity.level().hasChunkAt(nest.pos())))return;
            nest=null;
            for(BlockPos pos:BlockPos.betweenClosed(entity.blockPosition().offset(-8,-1,-8),entity.blockPosition().offset(8,1,8)))
                if(entity.level().hasChunkAt(pos)&&entity.level().getBlockEntity(pos) instanceof HouseNestBlockEntity value&&choose(value))break;
        }
    }
    public boolean canUse(){
        if(!entity.getVariant().isHouse()||entity.isPanicking()||entity.isCommandPlaying()||entity.life().energy()<25||entity.life().wetness()>40||entity.isInWaterOrBubble()||entity.isOnFire()||entity.life().mealTicks()>0
                ||!net.neoforged.neoforge.event.EventHooks.canEntityGrief((ServerLevel)entity.level(),entity))return false;
        if(active)return true;if(wait>0)return false;wait=60;
        var value=nest();if(value==null||!value.usable(entity))return false;
        if(entity.life().nutrition()<30&&value.food()==0&&carrying.isEmpty())return false;
        if(!carrying.isEmpty()||entity.life().needsFood()&&value.food()>0){active=true;ticks=0;return true;}
        target=entity.level().getEntitiesOfClass(ItemEntity.class,entity.getBoundingBox().inflate(8),item->item.isAlive()&&!item.hasPickUpDelay()
                &&(item.getItem().is(ModItems.FOOD_SCRAP.get())&&value.food()<HouseNestBlockEntity.STOCK_CAP||HouseNestBlockEntity.isMaterial(item.getItem())&&!value.built()))
                .stream().filter(item->entity.life().reachable(item.blockPosition(),false)).min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
        active=target!=null;ticks=0;
        if(!active&&entity.life().nutrition()>=50&&entity.life().energy()>=45&&entity.distanceToSqr(Vec3.atBottomCenterOf(value.entrance()))<16){active=true;hiding=1;}
        return active;
    }
    public boolean canContinue(){
        int limit=(hiding==3||hiding==5)?Math.max(300,(int)Math.ceil(1200/Math.max(.25,entity.getScale()*entity.growthScale()))):300;
        return active&&ticks<limit&&!entity.isPanicking()&&!entity.isCommandPlaying()&&entity.life().energy()>=25&&entity.life().wetness()<=40
            &&!entity.isInWaterOrBubble()&&!entity.isOnFire()&&entity.life().mealTicks()==0&&homeStand()!=null;}
    public void tickGoal(){
        if(!canContinue()){stop();return;}ticks++;var value=nest();
        if(interaction>0){tickInteraction(value);return;}
        if(hiding>0){
            Vec3 outside=Vec3.atBottomCenterOf(value.entrance());
            Vec3 inside=Vec3.atBottomCenterOf(value.getBlockPos()).add(0,0,-.10);
            if(hiding==1){
                // First align with the opening. The short final move uses the real collision box.
                if(entity.distanceToSqr(outside)>.09){
                    if(ticks%10==1)entity.life().navigate(value.entrance(),.8,false);
                }else {hiding=5;ticks=0;}
            }else if(hiding==5){
                if(entity.distanceToSqr(inside)<.025){hiding=2;ticks=0;entity.getNavigation().stop();}
                else {entity.getNavigation().stop();entity.getMoveControl().setWantedPosition(inside.x,inside.y,inside.z,.65);}
            }else if(hiding==2){
                entity.getNavigation().stop();if(ticks>80){hiding=3;ticks=0;}
            }else if(hiding==3){
                Vec3 peek=Vec3.atBottomCenterOf(value.getBlockPos()).add(0,0,-.55);
                if(entity.distanceToSqr(peek)<.025){hiding=4;ticks=0;entity.showReaction("neutral","house_peek",60);}
                else {entity.getNavigation().stop();entity.getMoveControl().setWantedPosition(peek.x,peek.y,peek.z,.65);}
            }else {entity.getNavigation().stop();entity.getLookControl().setLookAt(outside.x,outside.y+.1,outside.z-2,20,20);if(ticks>60)stop();}
            return;
        }
        if(!carrying.isEmpty()){
            Vec3 entrance=Vec3.atBottomCenterOf(value.entrance());
            if(inReach(entrance))beginInteraction(2,entrance);
            else if(entity.distanceToSqr(entrance)<2.25)approach(entrance);
            else if(ticks%10==1&&!entity.life().navigate(value.entrance(),.8,false))stop();
        }else if(entity.life().needsFood()&&value.food()>0){
            if(entity.distanceToSqr(Vec3.atBottomCenterOf(value.entrance()))<1.5){
                ItemStack food=value.takeFood();
                if(!food.isEmpty()&&!entity.life().beginMeal(food,null,false))value.deposit(food);
                stop();
            }else if(ticks%10==1&&!entity.life().navigate(value.entrance(),1,false))stop();
        } else if(target!=null&&target.isAlive()){
            if(inReach(target.position())&&entity.hasLineOfSight(target)){
                if(!target.hasPickUpDelay()&&(target.getItem().is(ModItems.FOOD_SCRAP.get())||HouseNestBlockEntity.isMaterial(target.getItem()))){
                    beginInteraction(1,target.position());
                } else stop();
            } else if(entity.distanceToSqr(target)<2.25&&entity.hasLineOfSight(target)&&entity.life().dryStand(target.blockPosition())){
                // Block paths finish at the block centre; a small body must still approach an item near its edge.
                entity.getNavigation().stop();
                approach(target.position());
            } else if(ticks%10==1&&!entity.life().navigate(target.blockPosition(),1.1,false))stop();
        } else stop();
    }
    private void approach(Vec3 point){
        var delta=point.subtract(entity.position()).multiply(1,0,1);
        if(delta.lengthSqr()<.0001)delta=new Vec3(0,0,1);
        var stand=point.subtract(delta.normalize().scale(reach()));
        entity.getNavigation().stop();entity.getMoveControl().setWantedPosition(stand.x,stand.y,stand.z,.65);
    }
    private double reach(){return .28*entity.getScale()*entity.growthScale();}
    private boolean inReach(Vec3 point){
        var delta=point.subtract(entity.position());
        return Math.abs(delta.y)<.12&&Math.abs(delta.horizontalDistance()-reach())<.045;
    }
    private void beginInteraction(int kind,Vec3 point){
        interaction=kind;interactionTicks=0;contactTarget=point;
        entity.getNavigation().stop();faceContact();
    }
    private void faceContact(){
        if(contactTarget==null)return;
        var delta=contactTarget.subtract(entity.position());
        float yaw=(float)(Math.atan2(-delta.x,delta.z)*180/Math.PI);
        entity.setYRot(yaw);entity.yBodyRot=yaw;entity.yHeadRot=yaw;
        entity.getLookControl().setLookAt(contactTarget.x,entity.getEyeY(),contactTarget.z,180,180);
    }
    private void tickInteraction(HouseNestBlockEntity value){
        entity.getNavigation().stop();entity.setDeltaMovement(0,entity.getDeltaMovement().y,0);faceContact();
        interactionTicks++;
        if(interactionTicks==13){
            if(interaction==1){
                if(target==null||!target.isAlive()||target.hasPickUpDelay()||!inReach(target.position())||!entity.hasLineOfSight(target)
                        ||!(target.getItem().is(ModItems.FOOD_SCRAP.get())||HouseNestBlockEntity.isMaterial(target.getItem()))){stop();return;}
                carrying=target.getItem().split(1);if(target.getItem().isEmpty())target.discard();target=null;
                entity.showReaction("happy","house_pickup",60);
            }else if(!carrying.isEmpty()){
                if(!inReach(Vec3.atBottomCenterOf(value.entrance()))){stop();return;}
                if(value.deposit(carrying)){carrying=ItemStack.EMPTY;entity.showReaction("happy","house_deposit",60);}
                else {dropCarrying();stop();return;} // Capacity races return a real item instead of retrying forever.
            }
        }
        if(interactionTicks>=26){
            if(interaction==2){stop();return;}
            interaction=interactionTicks=0;contactTarget=null;
        }
    }
    public void stop(){active=false;target=null;contactTarget=null;interaction=interactionTicks=0;ticks=hiding=0;wait=40;}
    public void dropCarrying(){if(!carrying.isEmpty()){entity.spawnAtLocation(carrying.copy());carrying=ItemStack.EMPTY;}}
    public CompoundTag save(){var tag=new CompoundTag();tag.putInt("Version",1);FamilyData.putPosition(tag,"Nest",nest);
        if(!carrying.isEmpty())tag.put("Carrying",carrying.save(entity.registryAccess()));return tag;}
    public void load(CompoundTag tag){nest=FamilyData.position(tag,"Nest");carrying=tag.contains("Carrying")?ItemStack.parseOptional(entity.registryAccess(),tag.getCompound("Carrying")):ItemStack.EMPTY;stop();}
}
