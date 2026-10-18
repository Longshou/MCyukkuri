package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife.Task;
import io.github.longshou.mcyukkuri.life.InfantFood;
import io.github.longshou.mcyukkuri.settlement.PileContact;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.EventHooks;

/** Real parent food reservation, mouth preparation and handoff. The owner retains the goal state. */
final class FamilyFeeding {
    private final FamilyLife o;
    /** A single real portion survives interruption and reload. */
    private ItemStack carrying=ItemStack.EMPTY;
    private ItemEntity foodSource;
    private BlockPos foodStore;
    private PileContact storeContact;
    private int careCarryTicks, careChewTicks, careOfferTicks, careFinishTicks;

    FamilyFeeding(FamilyLife o) { this.o=o; }

    ItemStack carrying() { return carrying; }
    int chewTicks() { return careChewTicks; }
    int offerTicks() { return careOfferTicks; }
    String activity() {
        return careFinishTicks>0 ? Behaviors.CARE_FINISH : !carrying.isEmpty() && o.target()!=null && o.target().family().needsMash() && !InfantFood.isMash(carrying) ? Behaviors.FAMILY_CHEW : Behaviors.FEED_CHILD;
    }
    boolean canContinue() {
        return careReady()&&(careFinishTicks>0&&o.target()!=null&&o.target().isAlive()||validCareTarget())&&o.taskTicks()<600;
    }
    void reset() {
        foodSource=null;foodStore=null;careOfferTicks=careFinishTicks=0;
    }
    void died() {
        if(!carrying.isEmpty()) {
            var food=o.entity().spawnAtLocation(carrying.copy());
            if(food!=null){food.setPickUpDelay(40);carrying=ItemStack.EMPTY;}
        }
    }
    void save(CompoundTag tag) {
        if(!carrying.isEmpty())tag.put("CareFood",carrying.save(o.entity().registryAccess()));
        tag.putInt("CareFoodTicks",careCarryTicks);
        tag.putInt("CareChewTicks",careChewTicks);tag.putInt("CareOfferTicks",careOfferTicks);
    }
    void load(CompoundTag tag) {
        carrying=tag.contains("CareFood")?ItemStack.parseOptional(o.entity().registryAccess(),tag.getCompound("CareFood")):ItemStack.EMPTY;
        if(carrying.getCount()!=1)carrying=ItemStack.EMPTY;
        careCarryTicks=Math.clamp(tag.getInt("CareFoodTicks"),0,200);
        careChewTicks=Math.clamp(tag.getInt("CareChewTicks"),0,40);careOfferTicks=Math.clamp(tag.getInt("CareOfferTicks"),0,FamilyFeedingContact.REQUIRED_CONTACT_TICKS);
    }

    boolean careReady() {
        return o.adult()&&!o.awaitingLanding()&&!o.entity().isNoAi()&&!o.entity().isPassenger()&&!o.entity().profile().houseBody()&&!o.entity().profile().dangerToYukkuri()
                &&!o.entity().profile().aquatic()&&!o.entity().isInWaterOrBubble()&&!o.entity().isOnFire()&&!o.entity().operations().active()&&!o.entity().predator().active()
                &&!o.entity().life().isSleeping()&&!o.entity().life().hasUrgentNeed()&&o.entity().life().mealTicks()==0&&!o.entity().independence().active()
                &&o.entity().habitat().carrying().isEmpty()&&o.entity().independence().carrying().isEmpty()
                &&o.entity().settlement().duties().carrying().isEmpty()&&o.entity().house().carrying().isEmpty();
    }

    boolean hungryOwnChild(YukkuriEntity child) {
        return child!=o.entity()&&child.isAlive()&&!child.family().adult()&&!child.family().awaitingLanding()
                &&o.caresFor(child)&&o.entity().life().canRecognize(child)&&child.life().canReceiveCareFood()
                &&(carrying.isEmpty()||child.life().canReceiveCareFood(carrying))
                &&child.life().nutrition()<o.entity().life().nutrition()-o.entity().species().traits().dote()&&o.entity().careApproach().mayTry(child)&&o.entity().life().reachable(child.blockPosition(),false)
                // First the licking that brings the first stool (EH08 p.8), only then the mash; hunger below 30 still comes first.
                &&!(child.childhood().needsAssistedFirstToilet()&&child.life().nutrition()>=30);
    }

    YukkuriEntity hungriestOwnChild() {
        return o.level().getEntitiesOfClass(YukkuriEntity.class,o.entity().getBoundingBox().inflate(12),this::hungryOwnChild).stream()
                .min(Comparator.comparingDouble((YukkuriEntity child)->child.life().nutrition()).thenComparingDouble(o.entity()::distanceToSqr)).orElse(null);
    }

    boolean startCare() {
        if(!careReady())return false;
        o.target(hungriestOwnChild());
        if(o.target()==null)return false;
        foodSource=null;foodStore=null;careOfferTicks=0;
        if(carrying.isEmpty())selectFoodSource();
        if(carrying.isEmpty()&&foodSource==null&&foodStore==null){o.target(null);return false;}
        o.task(Task.FEED_CHILD);o.taskTicks(0);o.entity().showReaction("closed_smile","caregiver",40);
        BirthStaging.caption(o.entity(),"首便之后：亲油取一份粮，嚼成糜再喂赤油");return true;
    }

    /** Choose a real nearby drop or the caregiver's own stocked shelter, without reserving either. */
    void selectFoodSource() {
        foodSource=null;foodStore=null;
        ItemEntity nearby=EventHooks.canEntityGrief(o.level(),o.entity())
                ?o.level().getEntitiesOfClass(ItemEntity.class,o.entity().getBoundingBox().inflate(8),item->item.isAlive()&&!item.hasPickUpDelay()
                        &&o.entity().life().acceptsFood(item.getItem())&&o.target()!=null&&o.target().life().canReceiveCareFood(item.getItem())
                        &&o.entity().life().reachable(item.blockPosition(),false)).stream()
                        .min(Comparator.comparingDouble(o.entity()::distanceToSqr)).orElse(null):null;
        var shelter=o.entity().habitat().shelter();
        var pile=shelter==null?null:shelter.pile();
        boolean stocked=shelter!=null&&shelter.usable(o.entity())&&o.target()!=null&&shelter.hasFoodFor(o.entity(),o.target())&&pile!=null&&o.entity().life().reachable(pile.standCell(),false);
        if(nearby!=null&&(!stocked||o.entity().distanceToSqr(nearby)<=o.entity().distanceToSqr(pile.standPoint(o.entity()))))foodSource=nearby;
        else if(stocked){foodStore=shelter.getBlockPos();if(storeContact==null)storeContact=new PileContact(o.entity());storeContact.reset();}
    }

    boolean validCareTarget() { return o.target()!=null&&hungryOwnChild(o.target()); }

    /** A cancelled handoff does not mint or erase food: return the held portion or leave it where the parent stands. */
    void settleCareFood() {
        if(carrying.isEmpty()){careCarryTicks=careChewTicks=careOfferTicks=0;return;}
        if(o.task()==Task.FEED_CHILD){careCarryTicks=0;return;}
        if(++careCarryTicks<200)return;
        var home=o.entity().habitat().shelter();var heap=home==null?null:home.pile();
        if(home!=null&&heap!=null&&home.usable(o.entity())&&o.entity().distanceToSqr(heap.standPoint(o.entity()))<.64) {
            carrying=home.returnFood(carrying);
            if(carrying.isEmpty()){careCarryTicks=careChewTicks=careOfferTicks=0;return;}
        }
        if(o.entity().life().dryStand(o.entity().blockPosition())) {
            var food=o.entity().spawnAtLocation(carrying.copy());
            if(food!=null){food.setPickUpDelay(40);carrying=ItemStack.EMPTY;careCarryTicks=careChewTicks=careOfferTicks=0;}
        }
    }

    void tickCare() {
        if(careFinishTicks>0) {
            o.entity().careApproach().hold();
            if(++careFinishTicks>=72 || o.target()==null || !o.target().isAlive())o.stop();
            return;
        }
        if(!validCareTarget()) {
            o.target(hungriestOwnChild());foodSource=null;foodStore=null;careOfferTicks=0;
            if(o.target()==null){o.stop();return;}
        }
        if(carrying.isEmpty()) {
            if(foodSource==null&&foodStore==null)selectFoodSource();
            if(foodSource!=null) {
                if(!foodSource.isAlive()||foodSource.hasPickUpDelay()||!o.entity().life().acceptsFood(foodSource.getItem())) { foodSource=null;return; }
                o.entity().getLookControl().setLookAt(foodSource,20,20);
                if(o.entity().distanceToSqr(foodSource)<.5&&o.entity().hasLineOfSight(foodSource)) {
                    ItemStack remaining=foodSource.getItem().copy();carrying=remaining.split(1);careChewTicks=careOfferTicks=0;
                    if(remaining.isEmpty())foodSource.discard();else foodSource.setItem(remaining);
                    foodSource=null;
                } else if(o.taskTicks()%10==1&&!o.entity().life().navigate(foodSource.blockPosition(),1.1,false))foodSource=null;
                return;
            }
            if(foodStore!=null) {
                var home=o.level().getBlockEntity(foodStore) instanceof ShelterBlockEntity shelter?shelter:null;
                var pile=home==null?null:home.pile();
                if(home==null||pile==null||!home.usable(o.entity())||!home.hasFoodFor(o.entity(),o.target())) { foodStore=null;return; }
                if(storeContact==null)storeContact=new PileContact(o.entity());
                if(!storeContact.near(pile)) {
                    storeContact.reset();
                    if(o.taskTicks()%10==1&&!o.entity().life().navigate(pile.standCell(),.9,false))foodStore=null;
                    return;
                }
                if(storeContact.tick(pile)) {
                    var food=home.takeFood(o.entity(),o.target());
                    if(!food.isEmpty()&&o.target().life().canReceiveCareFood(food)){carrying=food;careChewTicks=careOfferTicks=0;}
                    else if(!food.isEmpty()){carrying=home.returnFood(food);foodStore=null;}
                    else foodStore=null;
                }
                return;
            }
            o.stop();return;
        }
        if(o.target().family().needsMash()&&!InfantFood.isMash(carrying)) {
            o.entity().getNavigation().stop();
            if(++careChewTicks >= 40) { carrying=InfantFood.mash(carrying,o.entity().getUUID());careOfferTicks=0; }
            return;
        }
        var approach=o.entity().careApproach().tick(o.target(),CareApproach.Kind.FEED);
        if(approach==CareApproach.Result.FAILED){o.stop();return;}
        if(approach==CareApproach.Result.CONTACT) {
            o.entity().getNavigation().stop();
            if(++careOfferTicks<FamilyFeedingContact.REQUIRED_CONTACT_TICKS)return;
            boolean handed=o.target().family().needsMash() ? o.target().life().beginParentMeal(o.entity(),carrying) : o.target().life().beginMeal(carrying,null,true);
            if(handed) { carrying=ItemStack.EMPTY;careChewTicks=careOfferTicks=0;careFinishTicks=1;o.entity().showReaction("happy","share",80); }
            else o.stop();return;
        }
        careOfferTicks=0;
    }
}
