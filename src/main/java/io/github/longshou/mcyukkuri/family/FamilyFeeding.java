package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriComponents;
import io.github.longshou.mcyukkuri.motor.MouthOccupancy;
import io.github.longshou.mcyukkuri.motor.MouthOccupancy.Purpose;
import io.github.longshou.mcyukkuri.family.FamilyLife.Task;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.interaction.ItemContact;
import io.github.longshou.mcyukkuri.life.InfantFood;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.settlement.Diet;
import io.github.longshou.mcyukkuri.settlement.ForageClaims;
import io.github.longshou.mcyukkuri.world.SearchBudget;
import java.util.ArrayList;
import io.github.longshou.mcyukkuri.settlement.PileContact;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/** Real parent food reservation, mouth preparation and handoff. The owner retains the goal state. */
final class FamilyFeeding {
    private static final int RETRY_TICKS = 200, MAX_RETRIES = 64;
    /** How long a parent chews a portion into mash (ticks). */
    static final int CHEW_TICKS = 40;
    private final FamilyLife o;
    /** A single real portion survives interruption and reload. */
    private ItemEntity foodSource;
    private BlockPos foodStore;
    private PileContact storeContact;
    private BlockPos plantSource;
    /** Where she stands to pick the plant: the plant itself on land, the dry bank cell above a water plant. */
    private BlockPos plantStand;
    /** Her own set-down hat she is going to for a portion, and the stand she reaches it from. */
    private PlacedHatEntity hatLarder;
    private Vec3 hatLarderStand;
    private ForageClaims.Claim plantClaim;
    private int searchTicks, plantContact;
    private int careCarryTicks, careChewTicks, careOfferTicks, careFinishTicks;
    /** Food-search failures are local to this route and do not change the parent's care decision. */
    private final Map<UUID, Long> retryUntil = new HashMap<>();

    FamilyFeeding(FamilyLife o) { this.o=o; }

    ItemStack carrying() { return mouth().stack(Purpose.CARE); }
    private MouthOccupancy mouth() { return o.entity().component(YukkuriComponents.MOUTH); }
    private void store(ItemStack stack) { mouth().set(Purpose.CARE, stack); }
    int chewTicks() { return careChewTicks; }
    int offerTicks() { return careOfferTicks; }
    String describe() { return "phase="+(o.task()!=Task.FEED_CHILD?"idle":careFinishTicks>0?"finish":carrying().isEmpty()?"search":InfantFood.isMash(carrying())?"offer":"chew")
            +" searchTicks="+searchTicks+" drop="+(foodSource==null?"none":foodSource.getUUID())+" store="+foodStore
            +" plant="+plantSource+" larder="+(hatLarder==null?"none":hatLarder.getUUID())
            +" carrying="+carrying()+" chew="+careChewTicks+" contact="+careOfferTicks; }
    String activity() {
        return careFinishTicks>0 ? Behaviors.CARE_FINISH : !carrying().isEmpty() && o.target()!=null && o.target().family().needsMash()
                && !InfantFood.isMash(carrying()) ? Behaviors.FAMILY_CHEW : Behaviors.FEED_CHILD;
    }
    boolean canContinue() {
        return careContinuationReady()&&(careFinishTicks>0&&o.target()!=null&&o.target().isAlive()||validCareTarget())&&o.taskTicks()<1200;
    }
    void reset() {
        foodSource=null;foodStore=null;careOfferTicks=careFinishTicks=0;
        hatLarder=null;hatLarderStand=null;
        clearPlant();searchTicks=0;
    }
    private void clearPlant(){ForageClaims.release(plantClaim);plantClaim=null;plantSource=null;plantStand=null;plantContact=0;}
    void died() {
        if(!carrying().isEmpty()) {
            var dropped=carrying().copy();
            var food=ItemContact.releaseSupported(o.entity(),carrying(),40);
            if(food!=null)store(ItemStack.EMPTY);
            GameplayLog.event(o.entity(),"family","carried care food on death item={} spawned={} remaining={}",
                    dropped,food==null?null:food.getUUID(),carrying());
        }
    }
    void save(CompoundTag tag) {
        tag.putInt("CareFoodTicks",careCarryTicks);
        tag.putInt("CareChewTicks",careChewTicks);tag.putInt("CareOfferTicks",careOfferTicks);
        long now=o.level().getGameTime();
        trimRetries(now);
        var retries=new ListTag();
        retryUntil.forEach((child, until) -> {
            long remaining=until-now;
            if(remaining<=0)return;
            var entry=new CompoundTag();
            entry.putUUID("Child",child);entry.putInt("Remaining",(int)Math.min(remaining,RETRY_TICKS));
            retries.add(entry);
        });
        tag.put("CareRetry",retries);
    }
    void load(CompoundTag tag) {
        mouth().importItem(Purpose.CARE, tag.contains("CareFood")?ItemStack.parseOptional(o.entity().registryAccess(),tag.getCompound("CareFood")):ItemStack.EMPTY);
        careCarryTicks=Math.clamp(tag.getInt("CareFoodTicks"),0,200);
        careChewTicks=Math.clamp(tag.getInt("CareChewTicks"),0,40);careOfferTicks=Math.clamp(tag.getInt("CareOfferTicks"),0,FamilyFeedingContact.REQUIRED_CONTACT_TICKS);
        retryUntil.clear();
        long now=o.level().getGameTime();
        for(var raw:tag.getList("CareRetry",Tag.TAG_COMPOUND)){
            if(!(raw instanceof CompoundTag entry)||!entry.hasUUID("Child"))continue;
            int remaining=Math.clamp(entry.getInt("Remaining"),0,RETRY_TICKS);
            if(remaining>0)retryUntil.put(entry.getUUID("Child"),now+remaining);
        }
        trimRetries(now);
    }

    private boolean careContextReady() {
        return (o.adult()||o.subadult())&&!o.awaitingLanding()&&!o.entity().isNoAi()&&!o.entity().isPassenger()&&!o.entity().profile().houseBody()&&!o.entity().profile().dangerToYukkuri()
                &&!o.entity().isInWaterOrBubble()&&!o.entity().isOnFire()&&!o.entity().operations().active()&&!o.entity().predator().active()
                &&!o.entity().life().isSleeping()&&canHoldCarePortion()&&!o.entity().independence().active()
                &&(o.entity().habitat().carrying().isEmpty()||o.entity().habitat().carryingMoveFood())&&o.entity().independence().carrying().isEmpty()
                &&o.entity().settlement().duties().carrying().isEmpty()&&o.entity().house().carrying().isEmpty()
                &&(carriedCheck||!o.carry().mouthBusy());
    }

    private boolean canHoldCarePortion() {
        boolean free = carriedCheck ? mouth().canHoldAfterPuttingDownYoung(Purpose.CARE) : mouth().canHold(Purpose.CARE);
        return free && o.entity().life().mealTicks()==0 && o.entity().forageTrip().load().isEmpty();
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
                // A sleeping child cannot be served (CareApproach stills a sleeping actor), so it is no request to walk after now.
                &&!child.life().isSleeping()
                &&(carrying().isEmpty()||child.life().canReceiveCareFood(carrying()))
                &&(!child.isPassenger()||carriedCheck&&child.getVehicle()==o.entity())
                &&(!child.childhood().firstMealDone()||child.life().nutrition()<o.entity().life().nutrition()-o.entity().species().traits().dote())
                &&o.entity().careApproach().mayTry(child,CareApproach.Kind.FEED)&&!child.careApproach().heldByOther(o.entity());
    }

    YukkuriEntity hungriestOwnChild() {
        return o.level().getEntitiesOfClass(YukkuriEntity.class,o.entity().getBoundingBox().inflate(12),this::hungryOwnChild).stream()
                .filter(child->!retryBlocked(child)&&!o.careDecisions().refused(child,CareDecisionLedger.Need.FEED))
                .min(Comparator.comparingDouble((YukkuriEntity child)->child.life().nutrition()).thenComparingDouble(o.entity()::distanceToSqr)).orElse(null);
    }

    /** Owner of the refusal turn: it only lasts as long as that Face, which ends at its own 3 degree arrival. */
    private static MotorOwner refusalTurn(YukkuriEntity body,Face turn){
        return new MotorOwner(){
            @Override public String ownerId(){return "feed_refusal";}
            @Override public boolean active(){return body.motor().current()==turn;}
        };
    }

    /** A parent chooses once per viable child-care opportunity; attachment never enters this choice. */
    private boolean acceptsCare(YukkuriEntity child) {
        // The never-fed newborn's first meal is as urgent as starvation: the parent's draw takes the urgent chance.
        boolean urgent=child.life().nutrition()<30||!child.childhood().firstMealDone();
        var decision=o.careDecisions().decide(child,CareDecisionLedger.Need.FEED,urgent);
        if(!decision.newDecision())return decision.accepted();
        boolean accepted=decision.accepted();
        o.entity().getLookControl().setLookAt(child,20,20);
        o.entity().showReaction(accepted?"closed_smile":"angry",accepted?"caregiver":"mood_bad",80);
        child.showReaction(accepted?"happy":"cry",accepted?"caregiver":"hungry",80);
        BirthStaging.caption(o.entity(),accepted?"回应赤油求食，去找食物":"拒绝赤油求食，转身离开");
        // The refusal is a whole visible turn away at the body's own turn rate (a Face action), not one bounded step.
        if(!accepted){
            var turn=new Face(o.entity().getYRot()+180);
            // A refused turn is skipped: the parent just stays facing the child and the choice stands.
            o.entity().motor().run(refusalTurn(o.entity(),turn),turn);
        }
        return accepted;
    }

    boolean startCare() {
        if(!careReady())return false;
        o.target(hungriestOwnChild());
        if(o.target()==null)return false;
        foodSource=null;foodStore=null;careOfferTicks=0;
        clearPlant();searchTicks=0;
        boolean ration=o.entity().habitat().hasMoveFoodFor(o.target());
        if(carrying().isEmpty()&&!ration)selectFoodSource();
        if(carrying().isEmpty()&&!ration&&!sourceChosen()&&FamilyProvision.home(o.entity())!=null
                &&(o.entity().habitat().shelter()!=null||!FamilyProvision.canFetchOutside(o.entity()))){
            o.target(null);return false;
        }
        if(!acceptsCare(o.target())){clearPlant();o.target(null);return false;}
        if(carrying().isEmpty()&&ration){
            store(o.entity().habitat().takeMoveFoodFor(o.target()));careChewTicks=careOfferTicks=0;
        }
        o.task(Task.FEED_CHILD);o.taskTicks(0);o.entity().showReaction("closed_smile","caregiver",40);
        GameplayLog.limited(o.entity(),"family","care.start",200,"feed started child={} carrying={} source={} store={}",
                o.target().getUUID(),carrying(),foodSource==null?null:foodSource.getUUID(),foodStore);
        BirthStaging.caption(o.entity(),"亲油接受求食，开始寻找真实食物；取得后嚼糜喂食");return true;
    }

    /** Choose the species' own food in its own order ({@link Diet}): a water diet looks at the water plants before anything
     *  that lies about inland, and falls back on what her hat keeps. */
    void selectFoodSource() {
        foodSource=null;foodStore=null;hatLarder=null;hatLarderStand=null;
        var diet=o.entity().profile().diet();
        for(var step:diet.sources()) {
            switch(step) {
                case STOCK -> selectStock();
                case PLANT -> selectPlant();
                case LARDER -> selectLarder();
            }
            if(sourceChosen())break;
        }
        if(sourceChosen())GameplayLog.limited(o.entity(),"family","care.source",200,
                "feed source selected child={} source={} drop={} store={} plant={} larder={}",o.target().getUUID(),
                plantSource!=null?"plant "+diet.yield(o.level().getBlockState(plantSource)):hatLarder!=null?"hat larder":foodStore!=null?"store":"drop",
                foodSource==null?null:foodSource.getUUID(),foodStore,plantSource,hatLarder==null?null:hatLarder.getUUID());
    }

    private boolean sourceChosen() { return foodSource!=null||foodStore!=null||plantSource!=null||hatLarder!=null; }

    boolean fetchingOutside() {
        if(o.task()!=Task.FEED_CHILD)return false;
        return (foodSource!=null&&!FamilyProvision.nearHome(o.entity(),foodSource.blockPosition()))
                ||(plantStand!=null&&!FamilyProvision.nearHome(o.entity(),plantStand))
                ||(!carrying().isEmpty()&&!FamilyProvision.nearHome(o.entity(),o.entity().blockPosition()));
    }

    /** A real nearby drop or the caregiver's own stocked shelter, without reserving either. */
    private void selectStock() {
        boolean outside=FamilyProvision.canFetchOutside(o.entity());
        ItemEntity nearby=EventHooks.canEntityGrief(o.level(),o.entity())
                ?o.level().getEntitiesOfClass(ItemEntity.class,o.entity().getBoundingBox().inflate(24),item->item.isAlive()&&!item.hasPickUpDelay()
                        &&(outside||FamilyProvision.nearHome(o.entity(),item.blockPosition()))
                        &&o.entity().life().acceptsFood(item.getItem())&&o.target()!=null&&o.target().life().canReceiveCareFood(item.getItem()))
                        .stream().sorted(Comparator.<ItemEntity>comparingInt(item -> o.entity().getUUID().equals(o.target().family().mother()) && item.getItem().is(ModItems.YUKKURI_STEM.get()) ? 0 : 1).thenComparingDouble(o.entity()::distanceToSqr)).limit(6)
                        .filter(item->o.entity().life().reachable(item.blockPosition(),false)).findFirst().orElse(null):null;
        var shelter=o.entity().habitat().shelter();
        var pile=shelter==null?null:shelter.pile();
        boolean stocked=shelter!=null&&shelter.usable(o.entity())&&o.target()!=null&&shelter.hasFoodFor(o.entity(),o.target())&&pile!=null&&o.entity().life().reachable(pile.standCell(),false);
        if(nearby!=null&&(o.entity().getUUID().equals(o.target().family().mother())&&nearby.getItem().is(ModItems.YUKKURI_STEM.get())||!stocked||o.entity().distanceToSqr(nearby)<=o.entity().distanceToSqr(pile.standPoint(o.entity()))))foodSource=nearby;
        else if(stocked){foodStore=shelter.getBlockPos();if(storeContact==null)storeContact=new PileContact(o.entity());storeContact.reset();}
    }

    /** Her own hat set down near the water keeps a portion for her ({@code HullAfloat}'s store): she walks to the bank beside it
     *  and takes one with her mouth, so feeding a newborn ashore needs no way back onto the hat. Only a hat she can reach from a
     *  dry stand counts, so one afloat out of reach never takes the place of food that really lies at hand. */
    private void selectLarder() {
        var hat=PlacedHatEntity.find(o.entity());
        if(hat==null||hat.isRemoved()||o.entity().distanceToSqr(hat)>24*24)return;
        var stand=SurfaceApproach.beside(o.entity(),hat,o.entity().getBbWidth()/2+.2,ContactGeometry.GRAB_HEIGHT);
        if(stand==null)return;
        hatLarder=hat;hatLarderStand=stand;
    }

    /** Harvest the same finite wild food of the species' own diet, after reaching and holding contact. */
    private void selectPlant(){
        if(!EventHooks.canEntityGrief(o.level(),o.entity())||!SearchBudget.take(o.level(),o.entity(),SearchBudget.Purpose.FAMILY_FEED))return;
        var diet=o.entity().profile().diet();
        boolean outside=FamilyProvision.canFetchOutside(o.entity());
        var candidates=new ArrayList<BlockPos>();
        for(var pos:BlockPos.betweenClosed(o.entity().blockPosition().offset(-8,-2,-8),o.entity().blockPosition().offset(8,2,8))) {
            if(!o.level().hasChunkAt(pos)||!outside&&!FamilyProvision.nearHome(o.entity(),pos)||ForageClaims.taken(o.level(),pos,o.entity().getUUID()))continue;
            var food=diet.yield(o.level().getBlockState(pos));
            if(!food.isEmpty()&&o.entity().life().acceptsFood(food)&&o.target().life().canReceiveCareFood(food))candidates.add(pos.immutable());
        }
        candidates.sort(Comparator.comparingDouble(pos->o.entity().distanceToSqr(Vec3.atCenterOf(pos))));
        for(var pos:candidates.stream().limit(3).toList()){
            var stand=diet.stand(o.entity(),pos);
            if(stand==null||!o.entity().life().reachable(stand,false))continue;
            plantClaim=ForageClaims.claim(o.level(),pos,o.entity().getUUID());
            if(plantClaim!=null){plantSource=pos;plantStand=stand;return;}
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
        if(child==null||!hungryOwnChild(child)||retryBlocked(child))return false;
        if(o.task()==Task.FEED_CHILD&&o.target()==child&&careContinuationReady()
                &&(!carrying().isEmpty()||foodSource!=null&&foodSource.isAlive()||foodStore!=null||plantSource!=null))return true;
        if(!careReady()||o.careDecisions().refused(child,CareDecisionLedger.Need.FEED))return false;
        if(!carrying().isEmpty()||o.entity().habitat().hasMoveFoodFor(child))return true;
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
        if(carrying().isEmpty()){careCarryTicks=careChewTicks=careOfferTicks=0;return;}
        if(o.task()==Task.FEED_CHILD){careCarryTicks=0;return;}
        if(++careCarryTicks<200)return;
        var home=o.entity().habitat().shelter();var heap=home==null?null:home.pile();
        if(home!=null&&heap!=null&&home.usable(o.entity())&&o.entity().distanceToSqr(heap.standPoint(o.entity()))<.64) {
            var returnedItem=carrying().getItem();int returnedCount=carrying().getCount();
            store(home.returnFood(carrying()));
            if(carrying().isEmpty()){
                careCarryTicks=careChewTicks=careOfferTicks=0;
                GameplayLog.event(o.entity(),"family","feed reserved portion returned store={} item={} count={}",
                        home.getBlockPos(),returnedItem,returnedCount);return;
            }
        }
        if(o.entity().life().dryStand(o.entity().blockPosition())) releaseCareFood("idle");
    }

    /** A mouth can take the next serving only once the held care portion really exists in the world. */
    boolean releaseCareFood(String reason) {
        if(carrying().isEmpty())return true;
        var food=ItemContact.releaseSupported(o.entity(),carrying(),40);
        if(food==null){
            GameplayLog.limited(o.entity(),"family","care_food.release_refused",200,
                    "feed reserved portion release refused item={} reason={}",carrying(),reason);
            return false;
        }
        GameplayLog.event(o.entity(),"family","feed reserved portion dropped item={} drop={} reason={}",
                food.getItem(),food.getUUID(),reason);
        store(ItemStack.EMPTY);careCarryTicks=careChewTicks=careOfferTicks=0;
        return true;
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
        o.careDecisions().touch(o.target(),CareDecisionLedger.Need.FEED);
        if(carrying().isEmpty()) {
            if(!sourceChosen())searchTicks++;
            if(searchTicks>600){
                retryUntil.put(o.target().getUUID(),o.level().getGameTime()+RETRY_TICKS);
                trimRetries(o.level().getGameTime());
                endCare("food_search_exhausted");return;
            }
            if(!sourceChosen()&&searchTicks%40==1)selectFoodSource();
            if(foodSource!=null) {
                if(!foodSource.isAlive()||foodSource.hasPickUpDelay()||!o.entity().life().acceptsFood(foodSource.getItem())) { foodSource=null;return; }
                o.entity().getLookControl().setLookAt(foodSource,20,20);
                if(o.entity().distanceToSqr(foodSource)<.5&&o.entity().hasLineOfSight(foodSource)) {
                    ItemStack remaining=foodSource.getItem().copy();store(remaining.split(1));careChewTicks=careOfferTicks=0;
                    if(remaining.isEmpty())foodSource.discard();else foodSource.setItem(remaining);
                    GameplayLog.event(o.entity(),"family","feed portion reserved source={} item={} remaining={}",
                            foodSource.getUUID(),carrying(),remaining.getCount());
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
                        store(food);careChewTicks=careOfferTicks=0;
                        GameplayLog.event(o.entity(),"family","feed portion reserved store={} item={}",foodStore,carrying());
                    }
                    else if(!food.isEmpty()){store(home.returnFood(food));foodStore=null;}
                    else foodStore=null;
                }
                return;
            }
            if(hatLarder!=null){
                if(hatLarder.isRemoved()||!hatLarder.isAlive()||o.entity().distanceToSqr(hatLarder)>24*24){hatLarder=null;hatLarderStand=null;return;}
                if(!ContactGeometry.inMouthReach(o.entity(),hatLarder)){
                    if(hatLarderStand==null)hatLarderStand=SurfaceApproach.beside(o.entity(),hatLarder,o.entity().getBbWidth()/2+.2,ContactGeometry.GRAB_HEIGHT);
                    if(hatLarderStand==null||o.taskTicks()%10==1&&!o.entity().life().navigate(BlockPos.containing(hatLarderStand),1.0,false)){hatLarder=null;hatLarderStand=null;}
                    return;
                }
                o.entity().getNavigation().stop();
                o.entity().getLookControl().setLookAt(hatLarder,20,20);
                var portion=hatLarder.behavior().take(hatLarder,o.entity(),one->o.target().life().canReceiveCareFood(one)&&o.entity().life().acceptsFood(one));
                var from=hatLarder.getUUID();
                hatLarder=null;hatLarderStand=null;
                if(!portion.isEmpty()){
                    store(portion);careChewTicks=careOfferTicks=0;
                    GameplayLog.event(o.entity(),"family","feed portion reserved hat={} item={}",from,carrying());
                }
                return;
            }
            if(plantSource!=null){
                var diet=o.entity().profile().diet();
                if(!EventHooks.canEntityGrief(o.level(),o.entity())||!ForageClaims.owns(o.level(),plantSource,plantClaim)
                        ||!diet.edible(o.level().getBlockState(plantSource))){clearPlant();return;}
                var at=Vec3.atCenterOf(plantSource);
                boolean near=diet.reaches(o.entity(),plantStand,plantSource);
                if(!near){plantContact=0;if(o.taskTicks()%10==1&&!o.entity().life().navigate(plantStand,1.1,false))clearPlant();return;}
                o.entity().getNavigation().stop();o.entity().getLookControl().setLookAt(at.x,at.y,at.z);
                if(++plantContact>=20){
                    var food=diet.yield(o.level().getBlockState(plantSource));
                    if(o.target().life().canReceiveCareFood(food)&&diet.harvest(o.level(),plantSource)){
                        store(food);careChewTicks=careOfferTicks=0;
                        GameplayLog.event(o.entity(),"family","feed plant harvested child={} source={} item={}",o.target().getUUID(),plantSource,carrying());
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
        if(o.target().family().needsMash()&&!InfantFood.isMash(carrying())) {
            o.entity().getNavigation().stop();
            if(++careChewTicks >= CHEW_TICKS)chew(o.target());
            return;
        }
        var approach=o.entity().careApproach().tick(o.target(),CareApproach.Kind.FEED);
        if(approach==CareApproach.Result.FAILED){
            o.entity().showReaction("teary","",60);
            endCare("approach_failed");return;
        }
        if(approach==CareApproach.Result.CONTACT) {
            o.entity().getNavigation().stop();
            if(++careOfferTicks<FamilyFeedingContact.REQUIRED_CONTACT_TICKS)return;
            if(hand(o.target()))careFinishTicks=1;
            else endCare("meal_refused");return;
        }
        careOfferTicks=0;
    }
    /** A portion taken from somewhere else (a hat's hold) becomes the held care food; false while one is held already. */
    boolean hold(ItemStack one) {
        if(!carrying().isEmpty()||one.isEmpty()||!canHoldCarePortion())return false;
        store(one.copyWithCount(1));careChewTicks=careOfferTicks=careCarryTicks=0;return true;
    }
    /** The held care food leaves her mouth for {@code into} (a hat's hold); kept when it refuses it. */
    boolean putBack(Predicate<ItemStack> into) {
        if(carrying().isEmpty()||!into.test(carrying().copy()))return false;
        store(ItemStack.EMPTY);careChewTicks=careOfferTicks=careCarryTicks=0;return true;
    }
    /** The held portion chewed for {@code child}: mash when it needs mash (the chewing only changes its form, spec CHILDHOOD). */
    void chew(YukkuriEntity child) {
        if(carrying().isEmpty()||!child.family().needsMash()||InfantFood.isMash(carrying()))return;
        store(InfantFood.mash(carrying(),o.entity().getUUID()));careOfferTicks=0;
        GameplayLog.event(o.entity(),"family","feed mash prepared child={} item={}",child.getUUID(),carrying());
    }
    /** The held portion passes into {@code child}'s meal (mash to one that needs it); its care need is complete once taken. */
    boolean hand(YukkuriEntity child) {
        if(carrying().isEmpty())return false;
        boolean handed=child.family().needsMash() ? child.life().beginParentMeal(o.entity(),carrying()) : child.life().beginMeal(carrying(),o.entity().getUUID(),true);
        if(!handed)return false;
        GameplayLog.event(o.entity(),"family","feed handed to meal child={} item={} mealTicks={} nutrition={}",
                child.getUUID(),carrying(),child.life().mealTicks(),child.life().nutrition());
        o.careDecisions().complete(child,CareDecisionLedger.Need.FEED);
        store(ItemStack.EMPTY);careChewTicks=careOfferTicks=0;o.entity().showReaction("happy","share",80);
        return true;
    }
    private void endCare(String reason) {
        GameplayLog.limited(o.entity(),"family","care.end."+reason,200,
                "feed interrupted child={} reason={} carrying={} ticks={}",
                o.target()==null?null:o.target().getUUID(),reason,carrying(),o.taskTicks());
        o.stop();
    }
    private boolean retryBlocked(YukkuriEntity child) {
        long now=o.level().getGameTime();
        long until=retryUntil.getOrDefault(child.getUUID(),0L);
        if(until<=now){retryUntil.remove(child.getUUID());return false;}
        return true;
    }
    private void trimRetries(long now) {
        retryUntil.entrySet().removeIf(entry->entry.getValue()<=now);
        while(retryUntil.size()>MAX_RETRIES){
            var oldest=retryUntil.entrySet().stream().min(Comparator.comparingLong(Map.Entry::getValue)).map(Map.Entry::getKey).orElse(null);
            if(oldest==null)return;
            retryUntil.remove(oldest);
        }
    }
}
