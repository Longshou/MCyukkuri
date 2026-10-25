package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife.Task;
import io.github.longshou.mcyukkuri.life.InfantFood;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.settlement.ForageClaims;
import io.github.longshou.mcyukkuri.settlement.Grazing;
import io.github.longshou.mcyukkuri.world.SearchBudget;
import java.util.ArrayList;
import io.github.longshou.mcyukkuri.settlement.PileContact;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/** Real parent food reservation, mouth preparation and handoff. The owner retains the goal state. */
final class FamilyFeeding {
    private final FamilyLife o;
    /** A single real portion survives interruption and reload. */
    private ItemStack carrying=ItemStack.EMPTY;
    private ItemEntity foodSource;
    private BlockPos foodStore;
    private PileContact storeContact;
    private BlockPos plantSource;
    private ForageClaims.Claim plantClaim;
    private int searchTicks, plantContact;
    private int careCarryTicks, careChewTicks, careOfferTicks, careFinishTicks;
    /** A declined child must not turn the next goal poll into another willingness roll. */
    private final Map<UUID, Long> careRefusedUntil=new HashMap<>();

    FamilyFeeding(FamilyLife o) { this.o=o; }

    ItemStack carrying() { return carrying; }
    int chewTicks() { return careChewTicks; }
    int offerTicks() { return careOfferTicks; }
    String describe() { return "phase="+(o.task()!=Task.FEED_CHILD?"idle":careFinishTicks>0?"finish":carrying.isEmpty()?"search":InfantFood.isMash(carrying)?"offer":"chew")
            +" searchTicks="+searchTicks+" drop="+(foodSource==null?"none":foodSource.getUUID())+" store="+foodStore
            +" plant="+plantSource+" carrying="+carrying+" chew="+careChewTicks+" contact="+careOfferTicks; }
    String activity() {
        return careFinishTicks>0 ? Behaviors.CARE_FINISH : !carrying.isEmpty() && o.target()!=null && o.target().family().needsMash()
                && !InfantFood.isMash(carrying) ? Behaviors.FAMILY_CHEW : Behaviors.FEED_CHILD;
    }
    boolean canContinue() {
        return careContinuationReady()&&(careFinishTicks>0&&o.target()!=null&&o.target().isAlive()||validCareTarget())&&o.taskTicks()<1200;
    }
    void reset() {
        foodSource=null;foodStore=null;careOfferTicks=careFinishTicks=0;
        clearPlant();searchTicks=0;
    }
    private void clearPlant(){ForageClaims.release(plantClaim);plantClaim=null;plantSource=null;plantContact=0;}
    void died() {
        if(!carrying.isEmpty()) {
            var dropped=carrying.copy();
            var food=o.entity().spawnAtLocation(carrying.copy());
            if(food!=null){food.setPickUpDelay(40);carrying=ItemStack.EMPTY;}
            GameplayLog.event(o.entity(),"family","carried care food on death item={} spawned={} remaining={}",
                    dropped,food==null?null:food.getUUID(),carrying);
        }
    }
    void save(CompoundTag tag) {
        if(!carrying.isEmpty())tag.put("CareFood",carrying.save(o.entity().registryAccess()));
        tag.putInt("CareFoodTicks",careCarryTicks);
        tag.putInt("CareChewTicks",careChewTicks);tag.putInt("CareOfferTicks",careOfferTicks);
        var refusals=new ListTag();
        careRefusedUntil.forEach((id,until)->{var entry=new CompoundTag();entry.putUUID("Child",id);entry.putLong("Until",until);refusals.add(entry);});
        tag.put("CareRefusals",refusals);
    }
    void load(CompoundTag tag) {
        carrying=tag.contains("CareFood")?ItemStack.parseOptional(o.entity().registryAccess(),tag.getCompound("CareFood")):ItemStack.EMPTY;
        if(carrying.getCount()!=1)carrying=ItemStack.EMPTY;
        careCarryTicks=Math.clamp(tag.getInt("CareFoodTicks"),0,200);
        careChewTicks=Math.clamp(tag.getInt("CareChewTicks"),0,40);careOfferTicks=Math.clamp(tag.getInt("CareOfferTicks"),0,FamilyFeedingContact.REQUIRED_CONTACT_TICKS);
        careRefusedUntil.clear();
        for(var raw:tag.getList("CareRefusals", Tag.TAG_COMPOUND))if(raw instanceof CompoundTag entry&&entry.hasUUID("Child"))
            careRefusedUntil.put(entry.getUUID("Child"),entry.getLong("Until"));
    }

    private boolean careContextReady() {
        return o.adult()&&!o.awaitingLanding()&&!o.entity().isNoAi()&&!o.entity().isPassenger()&&!o.entity().profile().houseBody()&&!o.entity().profile().dangerToYukkuri()
                &&!o.entity().profile().aquatic()&&!o.entity().isInWaterOrBubble()&&!o.entity().isOnFire()&&!o.entity().operations().active()&&!o.entity().predator().active()
                &&!o.entity().life().isSleeping()&&o.entity().life().mealTicks()==0&&!o.entity().independence().active()
                &&(o.entity().habitat().carrying().isEmpty()||o.entity().habitat().carryingMoveFood())&&o.entity().independence().carrying().isEmpty()
                &&o.entity().settlement().duties().carrying().isEmpty()&&o.entity().house().carrying().isEmpty()
                &&(carriedCheck||!o.carry().mouthBusy());
    }
    /** Set only while asking whether a child on the carrier's own seat could be fed once it is put down. */
    private boolean carriedCheck;
    /** New care work needs a comfortable reserve; a started handoff uses the lower emergency boundary. */
    boolean careReady() {
        var life=o.entity().life();
        return careContextReady()&&!life.hasUrgentNeed()&&life.nutrition()>=40&&life.energy()>=35;
    }
    private boolean careContinuationReady() {
        var life=o.entity().life();
        return careContextReady()&&life.nutrition()>=15&&life.energy()>=15
                &&(life.bladder()<90||life.constipated())&&life.wetness()<=40;
    }

    boolean hungryOwnChild(YukkuriEntity child) {
        return child!=o.entity()&&child.isAlive()&&!child.family().adult()&&!child.family().awaitingLanding()
                &&o.caresFor(child)&&o.entity().life().canRecognize(child)&&child.life().canReceiveCareFood()
                &&(carrying.isEmpty()||child.life().canReceiveCareFood(carrying))
                &&(!child.isPassenger()||carriedCheck&&child.getVehicle()==o.entity())&&child.life().nutrition()<o.entity().life().nutrition()-o.entity().species().traits().dote()
                &&o.entity().careApproach().mayTry(child);
    }

    YukkuriEntity hungriestOwnChild() {
        long now=o.level().getGameTime();careRefusedUntil.entrySet().removeIf(entry->entry.getValue()<=now);
        return o.level().getEntitiesOfClass(YukkuriEntity.class,o.entity().getBoundingBox().inflate(12),this::hungryOwnChild).stream()
                .filter(child->careRefusedUntil.getOrDefault(child.getUUID(),0L)<=now)
                .min(Comparator.comparingDouble((YukkuriEntity child)->child.life().nutrition()).thenComparingDouble(o.entity()::distanceToSqr)).orElse(null);
    }

    /** A parent chooses once per viable child-care opportunity; attachment never enters this choice. */
    private boolean acceptsCare(YukkuriEntity child) {
        boolean urgent=child.life().nutrition()<30;
        float roll=o.entity().getRandom().nextFloat();
        var probability=o.entity().life().personality().helpProbability(true,urgent);
        boolean accepted=roll<probability;
        GameplayLog.limited(o.entity(),"family","care.decision",200,
                "feed decision child={} accepted={} probability={} roll={} urgent={} friendliness={} selfControl={}",child.getUUID(),accepted,probability,roll,urgent,
                o.entity().life().personality().friendliness(),o.entity().life().personality().selfControl());
        o.entity().getLookControl().setLookAt(child,20,20);
        o.entity().showReaction(accepted?"closed_smile":"angry",accepted?"caregiver":"mood_bad",80);
        child.showReaction(accepted?"happy":"cry",accepted?"caregiver":"hungry",80);
        BirthStaging.caption(o.entity(),accepted?"回应赤油求食，去找食物":"拒绝赤油求食，转身离开");
        if(!accepted)o.face(o.entity().getYRot()+180);
        if(accepted)return true;
        careRefusedUntil.put(child.getUUID(),o.level().getGameTime()+1200);
        return false;
    }

    boolean startCare() {
        if(!careReady())return false;
        o.target(hungriestOwnChild());
        if(o.target()==null)return false;
        if(!acceptsCare(o.target())){o.target(null);return false;}
        foodSource=null;foodStore=null;careOfferTicks=0;
        clearPlant();searchTicks=0;
        boolean ration=o.entity().habitat().hasMoveFoodFor(o.target());
        if(carrying.isEmpty()&&!ration)selectFoodSource();
        if(carrying.isEmpty()&&ration){
            carrying=o.entity().habitat().takeMoveFoodFor(o.target());careChewTicks=careOfferTicks=0;
        }
        o.task(Task.FEED_CHILD);o.taskTicks(0);o.entity().showReaction("closed_smile","caregiver",40);
        GameplayLog.limited(o.entity(),"family","care.start",200,"feed started child={} carrying={} source={} store={}",
                o.target().getUUID(),carrying,foodSource==null?null:foodSource.getUUID(),foodStore);
        BirthStaging.caption(o.entity(),"亲油接受求食，开始寻找真实食物；取得后嚼糜喂食");return true;
    }

    /** Choose a real nearby drop or the caregiver's own stocked shelter, without reserving either. */
    void selectFoodSource() {
        foodSource=null;foodStore=null;
        ItemEntity nearby=EventHooks.canEntityGrief(o.level(),o.entity())
                ?o.level().getEntitiesOfClass(ItemEntity.class,o.entity().getBoundingBox().inflate(24),item->item.isAlive()&&!item.hasPickUpDelay()
                        &&o.entity().life().acceptsFood(item.getItem())&&o.target()!=null&&o.target().life().canReceiveCareFood(item.getItem()))
                        .stream().sorted(Comparator.<ItemEntity>comparingInt(item -> o.entity().getUUID().equals(o.target().family().mother()) && item.getItem().is(ModItems.YUKKURI_STEM.get()) ? 0 : 1).thenComparingDouble(o.entity()::distanceToSqr)).limit(6)
                        .filter(item->o.entity().life().reachable(item.blockPosition(),false)).findFirst().orElse(null):null;
        var shelter=o.entity().habitat().shelter();
        var pile=shelter==null?null:shelter.pile();
        boolean stocked=shelter!=null&&shelter.usable(o.entity())&&o.target()!=null&&shelter.hasFoodFor(o.entity(),o.target())&&pile!=null&&o.entity().life().reachable(pile.standCell(),false);
        if(nearby!=null&&(o.entity().getUUID().equals(o.target().family().mother())&&nearby.getItem().is(ModItems.YUKKURI_STEM.get())||!stocked||o.entity().distanceToSqr(nearby)<=o.entity().distanceToSqr(pile.standPoint(o.entity()))))foodSource=nearby;
        else if(stocked){foodStore=shelter.getBlockPos();if(storeContact==null)storeContact=new PileContact(o.entity());storeContact.reset();}
        if(foodSource==null&&foodStore==null&&plantSource==null)selectPlant();
        if(foodSource!=null||foodStore!=null||plantSource!=null)GameplayLog.limited(o.entity(),"family","care.source",200,
                "feed source selected child={} drop={} store={} plant={}",o.target().getUUID(),foodSource==null?null:foodSource.getUUID(),foodStore,plantSource);
    }

    /** Harvest the same finite wild food as ordinary grazing, after reaching and holding contact. */
    private void selectPlant(){
        if(!EventHooks.canEntityGrief(o.level(),o.entity())||!SearchBudget.take(o.level(),o.entity(),SearchBudget.Purpose.FAMILY_FEED))return;
        var candidates=new ArrayList<BlockPos>();
        for(var pos:BlockPos.betweenClosed(o.entity().blockPosition().offset(-8,-2,-8),o.entity().blockPosition().offset(8,2,8))) {
            if(!o.level().hasChunkAt(pos)||ForageClaims.taken(o.level(),pos,o.entity().getUUID()))continue;
            var food=Grazing.yield(o.level().getBlockState(pos));
            if(!food.isEmpty()&&o.entity().life().acceptsFood(food)&&o.target().life().canReceiveCareFood(food))candidates.add(pos.immutable());
        }
        candidates.sort(Comparator.comparingDouble(pos->o.entity().distanceToSqr(Vec3.atCenterOf(pos))));
        for(var pos:candidates.stream().limit(3).toList())if(o.entity().life().reachable(pos,false)){
            plantClaim=ForageClaims.claim(o.level(),pos,o.entity().getUUID());
            if(plantClaim!=null){plantSource=pos;return;}
        }
    }

    boolean validCareTarget() { return o.target()!=null&&hungryOwnChild(o.target()); }

    /** Whether a carried hungry child would get a real meal if set down; reads the same sources and refusals, starts nothing. */
    boolean hasCarriedOpportunity(YukkuriEntity child) {
        carriedCheck=true;
        try{return hasOpportunity(child);}finally{carriedCheck=false;}
    }

    /** A real reachable meal can be attempted before a parent treats starvation as having no remedy. */
    boolean hasOpportunity(YukkuriEntity child) {
        if(child==null||!hungryOwnChild(child))return false;
        if(o.task()==Task.FEED_CHILD&&o.target()==child&&careContinuationReady()
                &&(!carrying.isEmpty()||foodSource!=null&&foodSource.isAlive()||foodStore!=null||plantSource!=null))return true;
        if(!careReady()||careRefusedUntil.getOrDefault(child.getUUID(),0L)>o.level().getGameTime())return false;
        if(!carrying.isEmpty()||o.entity().habitat().hasMoveFoodFor(child))return true;
        var home=o.entity().habitat().shelter();var pile=home==null?null:home.pile();
        if(home!=null&&home.usable(o.entity())&&pile!=null&&home.hasFoodFor(o.entity(),child)
                &&o.entity().life().reachable(pile.standCell(),false))return true;
        return EventHooks.canEntityGrief(o.level(),o.entity())&&o.level().getEntitiesOfClass(ItemEntity.class,
                o.entity().getBoundingBox().inflate(24),item->item.isAlive()&&!item.hasPickUpDelay()
                        &&o.entity().life().acceptsFood(item.getItem())&&child.life().canReceiveCareFood(item.getItem()))
                .stream().sorted(Comparator.<ItemEntity>comparingInt(item -> o.entity().getUUID().equals(child.family().mother()) && item.getItem().is(ModItems.YUKKURI_STEM.get()) ? 0 : 1).thenComparingDouble(o.entity()::distanceToSqr)).limit(6)
                .anyMatch(item->o.entity().life().reachable(item.blockPosition(),false));
    }

    /** A cancelled handoff does not mint or erase food: return the held portion or leave it where the parent stands. */
    void settleCareFood() {
        if(carrying.isEmpty()){careCarryTicks=careChewTicks=careOfferTicks=0;return;}
        if(o.task()==Task.FEED_CHILD){careCarryTicks=0;return;}
        if(++careCarryTicks<200)return;
        var home=o.entity().habitat().shelter();var heap=home==null?null:home.pile();
        if(home!=null&&heap!=null&&home.usable(o.entity())&&o.entity().distanceToSqr(heap.standPoint(o.entity()))<.64) {
            carrying=home.returnFood(carrying);
            if(carrying.isEmpty()){
                careCarryTicks=careChewTicks=careOfferTicks=0;
                GameplayLog.event(o.entity(),"family","feed reserved portion returned store={}",home.getBlockPos());return;
            }
        }
        if(o.entity().life().dryStand(o.entity().blockPosition())) {
            var food=o.entity().spawnAtLocation(carrying.copy());
            if(food!=null){
                food.setPickUpDelay(40);carrying=ItemStack.EMPTY;careCarryTicks=careChewTicks=careOfferTicks=0;
                GameplayLog.event(o.entity(),"family","feed reserved portion dropped item={} drop={}",food.getItem(),food.getUUID());
            }
        }
    }

    void tickCare() {
        if(careFinishTicks>0) {
            o.entity().careApproach().hold();
            if(++careFinishTicks>=72 || o.target()==null || !o.target().isAlive())o.stop();
            return;
        }
        if(!validCareTarget()) {
            endCare("no_eligible_child");return;
        }
        if(carrying.isEmpty()) {
            searchTicks++;
            if(searchTicks>600){
                careRefusedUntil.put(o.target().getUUID(),o.level().getGameTime()+200);
                endCare("food_search_exhausted");return;
            }
            if(foodSource==null&&foodStore==null&&plantSource==null&&searchTicks%40==1)selectFoodSource();
            if(foodSource!=null) {
                if(!foodSource.isAlive()||foodSource.hasPickUpDelay()||!o.entity().life().acceptsFood(foodSource.getItem())) { foodSource=null;return; }
                o.entity().getLookControl().setLookAt(foodSource,20,20);
                if(o.entity().distanceToSqr(foodSource)<.5&&o.entity().hasLineOfSight(foodSource)) {
                    ItemStack remaining=foodSource.getItem().copy();carrying=remaining.split(1);careChewTicks=careOfferTicks=0;
                    if(remaining.isEmpty())foodSource.discard();else foodSource.setItem(remaining);
                    GameplayLog.event(o.entity(),"family","feed portion reserved source={} item={} remaining={}",
                            foodSource.getUUID(),carrying,remaining.getCount());
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
                    if(!food.isEmpty()&&o.target().life().canReceiveCareFood(food)){
                        carrying=food;careChewTicks=careOfferTicks=0;
                        GameplayLog.event(o.entity(),"family","feed portion reserved store={} item={}",foodStore,carrying);
                    }
                    else if(!food.isEmpty()){carrying=home.returnFood(food);foodStore=null;}
                    else foodStore=null;
                }
                return;
            }
            if(plantSource!=null){
                if(!EventHooks.canEntityGrief(o.level(),o.entity())||!ForageClaims.owns(o.level(),plantSource,plantClaim)
                        ||!Grazing.edible(o.level().getBlockState(plantSource))){clearPlant();return;}
                var at=Vec3.atCenterOf(plantSource);
                boolean near=o.entity().distanceToSqr(Vec3.atBottomCenterOf(plantSource))<1
                        &&o.level().clip(new ClipContext(o.entity().getEyePosition(),at,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,o.entity())).getType()==HitResult.Type.MISS;
                if(!near){plantContact=0;if(searchTicks%10==1&&!o.entity().life().navigate(plantSource,1.1,false))clearPlant();return;}
                o.entity().getNavigation().stop();o.entity().getLookControl().setLookAt(at.x,at.y,at.z);
                if(++plantContact>=20){
                    var food=Grazing.yield(o.level().getBlockState(plantSource));
                    if(o.target().life().canReceiveCareFood(food)&&Grazing.graze(o.level(),plantSource)){
                        carrying=food;careChewTicks=careOfferTicks=0;
                        GameplayLog.event(o.entity(),"family","feed plant harvested child={} source={} item={}",o.target().getUUID(),plantSource,carrying);
                    }
                    clearPlant();
                }
                return;
            }
            // A healthy parent keeps searching after accepting, without conjuring a meal.
            if(searchTicks%40==1&&o.entity().getNavigation().isDone()){
                var point=LandRandomPos.getPos(o.entity(),12,2);
                if(point!=null&&point.distanceToSqr(o.target().position())<=32*32)o.entity().life().navigate(BlockPos.containing(point),1.0,false);
            }
            return;
        }
        if(o.target().family().needsMash()&&!InfantFood.isMash(carrying)) {
            o.entity().getNavigation().stop();
            if(++careChewTicks >= 40) {
                carrying=InfantFood.mash(carrying,o.entity().getUUID());careOfferTicks=0;
                GameplayLog.event(o.entity(),"family","feed mash prepared child={} item={}",o.target().getUUID(),carrying);
            }
            return;
        }
        var approach=o.entity().careApproach().tick(o.target(),CareApproach.Kind.FEED);
        if(approach==CareApproach.Result.FAILED){endCare("approach_failed");return;}
        if(approach==CareApproach.Result.CONTACT) {
            o.entity().getNavigation().stop();
            if(++careOfferTicks<FamilyFeedingContact.REQUIRED_CONTACT_TICKS)return;
            boolean handed=o.target().family().needsMash() ? o.target().life().beginParentMeal(o.entity(),carrying) : o.target().life().beginMeal(carrying,o.entity().getUUID(),true);
            if(handed) {
                GameplayLog.event(o.entity(),"family","feed handed to meal child={} item={} mealTicks={} nutrition={}",
                        o.target().getUUID(),carrying,o.target().life().mealTicks(),o.target().life().nutrition());
                carrying=ItemStack.EMPTY;careChewTicks=careOfferTicks=0;careFinishTicks=1;o.entity().showReaction("happy","share",80);
            }
            else endCare("meal_refused");return;
        }
        careOfferTicks=0;
    }
    private void endCare(String reason) {
        GameplayLog.limited(o.entity(),"family","care.end."+reason,200,
                "feed interrupted child={} reason={} carrying={} ticks={}",
                o.target()==null?null:o.target().getUUID(),reason,carrying,o.taskTicks());
        o.stop();
    }
}
