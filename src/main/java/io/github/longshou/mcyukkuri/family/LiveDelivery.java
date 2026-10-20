package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.status.GameplayLog;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife.Task;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatRoute;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Maternal delivery and newborn/parent staging; durable births remain in FamilyData. */
final class LiveDelivery {
    private enum CatchStationState { UNUSED, ACTIVE, ABANDONED }
    private final FamilyLife o;
    private int liveRecovery;
    /** Transient greeting/recovery target after a successful real birth. */
    private UUID newborn;
    private int welcomeTicks;
    private static final int WELCOME=80;
    private Vec3 deliveryLast;
    private float deliveryYaw=Float.NaN;
    private int catcherWait;
    /** Saved bounded wait while the mother walks back to an already placed helper hat. */
    private int stationWait;
    /** Once a real helper station has failed, this brood must not silently use that hat as a cushion. */
    private CatchStationState catchStation=CatchStationState.UNUSED;
    /** Loaded ticks near term without a reachable nest, before delivering on firm ground. */
    private int nestless;
    /** 0 natural, 1 aimed catch, 2 overstrain, 3 an explicit miss without a cushion. */
    private int deliveryOutcome;
    private int bumpTicks, comfortTicks, comfortNear;
    /** Transient candidate and search throttles; parentage, capacity and custody stay in their existing owners. */
    private UUID waterPartnerChild;
    private long nextWaterPartnerScan,nextWaterPartnerRoute;
    private boolean bumpPending, bumped;
    private UUID comfortBaby;
    private static final int COMFORT=200;
    private static final int NEST_GRACE=400, NEST_PATIENCE=100;
    private static final double STATION_GAP=2.1;
    private String hold="";

    LiveDelivery(FamilyLife o) { this.o=o; }
    int recoveryTicks() { return liveRecovery; }
    boolean comfortActive() { return comfortTicks>0; }
    boolean nuzzling() { return comfortTicks>0&&comfortNear>=10; }
    boolean bumping() { return bumpTicks>0; }
    boolean welcoming() { return welcomeTicks>0&&newborn!=null; }
    boolean bumpPending() { return bumpPending; }
    void clearPendingBump() { bumpPending=false; }
    /** Passive detail for the two real partner hulls and the existing cross-hull cradle transfer. */
    String waterBirthDetail(){
        var mine=o.boatHome();
        var mate=o.partner()!=null&&o.level().getEntity(o.partner()) instanceof YukkuriEntity y&&y.isAlive()?y:null;
        var theirs=mate==null?null:mate.family().boatHome();
        var transfer=o.cradlePickup();
        var partnerTransfer=mate==null?null:mate.family().cradlePickup();
        long now=o.level().getGameTime();
        return "required="+waterBirthRequired()+" needsBoat="+needsWaterBirthBoat()+" labor="+laboring()+" gap="+deliveryGap()
                +" block="+(birthBlock().isEmpty()?"none":birthBlock())+" own="+hullDetail(mine)
                +" partner="+(mate==null?"none":"#"+mate.getId())+" partnerHull="+hullDetail(theirs)
                +" transferCandidate="+(waterPartnerChild==null?"none":waterPartnerChild)
                +" ownTransfer={"+transferDetail(transfer)+"} partnerTransfer={"+transferDetail(partnerTransfer)+"}"
                +" scan="+Math.max(0,nextWaterPartnerScan-now)+" route="+Math.max(0,nextWaterPartnerRoute-now);
    }
    private static String transferDetail(CradlePickup transfer){
        if(transfer==null||!transfer.active())return "idle";
        return "phase="+transfer.state().getString("Phase")+" child="+transfer.childId()+" held="+transfer.held();
    }
    private static String hullDetail(HatBoatEntity boat){
        return boat==null?"none":"#"+boat.getId()+" load="+boat.capacityUsed()+" remaining="+boat.remainingCapacity()
                +" incoming="+boat.hasIncoming()+" afloat="+boat.isAfloat()+" paddle="+boat.cargo().paddle();
    }
    void tickRecovery() { if(liveRecovery>0)liveRecovery--; }
    void resetForConception() { deliveryYaw=Float.NaN;catcherWait=stationWait=deliveryOutcome=0;waterPartnerChild=null;catchStation=CatchStationState.UNUSED; }
    void resetForAssistance() { deliveryYaw=Float.NaN;catcherWait=stationWait=0;waterPartnerChild=null;catchStation=CatchStationState.UNUSED; }
    void save(CompoundTag tag) {
        tag.putInt("LiveRecovery",liveRecovery);
        if(!Float.isNaN(deliveryYaw))tag.putFloat("DeliveryYaw",deliveryYaw);
        tag.putInt("CatcherWait",catcherWait);
        tag.putInt("CatchStationWait",stationWait);
        if(catchStation!=CatchStationState.UNUSED)tag.putByte("CatchStationState",(byte)catchStation.ordinal());
        tag.putInt("DeliveryOutcome",deliveryOutcome);
    }
    void load(CompoundTag tag) {
        waterPartnerChild=null;nextWaterPartnerScan=nextWaterPartnerRoute=0;
        liveRecovery=Math.clamp(tag.getInt("LiveRecovery"),0,LiveBirthGeometry.RECOVERY);deliveryLast=null;
        deliveryYaw=tag.contains("DeliveryYaw")?tag.getFloat("DeliveryYaw"):Float.NaN;
        catcherWait=Math.clamp(tag.getInt("CatcherWait"),0,CatchHat.CATCH_WAIT);
        stationWait=Math.clamp(tag.getInt("CatchStationWait"),0,400);
        int savedStation=Math.clamp(tag.getByte("CatchStationState"),0,CatchStationState.values().length-1);
        catchStation=CatchStationState.values()[savedStation];
        deliveryOutcome=Math.clamp(tag.getInt("DeliveryOutcome"),0,3);
    }

    /** Visible pregnancy and the brief post-delivery recovery of the mother's belly. */
    float liveBelly(){
        var pregnancy=o.brood();
        if(pregnancy!=null&&!pregnancy.cancelled&&!pregnancy.complete()&&pregnancy.kind==FamilyData.BirthKind.LIVE)
            return LiveBirthGeometry.belly(pregnancy.progress(),pregnancy.children.size()-pregnancy.unborn(),pregnancy.children.size());
        return .22f*liveRecovery/LiveBirthGeometry.RECOVERY;
    }

    /** A live delivery is under way (server side). */
    /** Why the current delivery is held up (empty when it is not): the last refusal of the ledger, or a guard of the mother's own. */
    String birthBlock(){
        var b=o.brood();if(b==null||b.cancelled||b.complete())return "";
        if(!hold.isEmpty())return hold;
        return FamilyData.get(o.level()).birthBlock(b);
    }

    boolean laboring() {
        if(o.entity().level().isClientSide)return false;
        var b=o.brood();return b!=null&&!b.cancelled&&!b.complete()&&b.kind==FamilyData.BirthKind.LIVE&&b.elapsed>=b.duration
                &&(!waterBirthRequired(b)||o.boatHome()!=null)&&!o.rescuingYoung();
    }

    /** The aquatic Marisa mother always delivers a live brood from her own floating hat. */
    boolean waterBirthRequired(){return waterBirthRequired(o.brood());}
    private boolean waterBirthRequired(FamilyData.Brood brood){
        return brood!=null&&!brood.cancelled&&!brood.complete()&&brood.kind==FamilyData.BirthKind.LIVE
                &&o.entity().getUUID().equals(brood.mother)&&o.entity().subspecies().kind()==SubspeciesLife.Kind.AQUATIC_MARISA;
    }
    private boolean nearWaterBirthTerm(FamilyData.Brood brood){return brood.elapsed>=Math.max(0,brood.duration-NEST_GRACE);}
    /**
     * A mother needs her own hull from the last approach to term onward. Her nearby recorded aquatic father receives the
     * same request until he has launched his own hull, so accompaniment is an intentional shore action rather than a
     * reaction after he has already fallen in the water.
     */
    boolean needsWaterBirthBoat(){
        var mine=o.brood();
        if(waterBirthRequired(mine))return nearWaterBirthTerm(mine)&&o.boatHome()==null;
        if(!o.entity().profile().aquatic()||o.boatHome()!=null)return false;
        UUID mateId=o.partner();
        if(mateId==null||!(o.level().getEntity(mateId) instanceof YukkuriEntity mother)||!mother.isAlive()
                ||o.entity().distanceToSqr(mother)>576)return false;
        var pregnancy=mother.family().brood();
        return pregnancy!=null&&!pregnancy.cancelled&&!pregnancy.complete()&&pregnancy.kind==FamilyData.BirthKind.LIVE
                &&o.entity().getUUID().equals(pregnancy.father)&&mother.family().waterBirthRequired()
                &&nearWaterBirthTerm(pregnancy);
    }

    /** In the delivery itself and not between two births: she cannot leave it to help anyone. */
    boolean tiedToLabor(){return laboring()&&!deliveryGap();}

    /**
     * The rest after a child is born and the strain before the next head appears (the clock of {@link LiveBirthGeometry}).
     * A newborn that has fallen in reach of her braid may be lifted out now; the next push waits until she has done so
     * (see {@link #advanceLive}). Project design: the lecture only says a missed catch is a drowned child, not that a mother
     * must stand by while it goes under.
     */
    boolean deliveryGap(){
        if(o.entity().level().isClientSide)return false;
        var b=o.brood();
        if(b==null||b.cancelled||b.complete()||b.kind!=FamilyData.BirthKind.LIVE||b.elapsed<b.duration)return false;
        int born=b.children.size()-b.unborn();if(born<=0||b.unborn()==0)return false;
        int delivery=b.elapsed-b.duration;
        return delivery>=LiveBirthGeometry.birthTick(born-1)&&delivery<LiveBirthGeometry.emergenceStart(born);
    }

    boolean readyForDelivery(){
        return !o.entity().level().isClientSide&&o.entity().isAlive()&&!o.entity().isNoAi()&&o.supported()&&!o.entity().operations().active()
                &&!o.entity().dailyLife().active()&&!o.entity().isPanicking()&&!o.entity().isCommandPlaying()&&!o.entity().life().hasUrgentNeed()
                &&!o.entity().life().isSleeping()&&o.entity().life().mealTicks()==0&&o.entity().body().condition().readyForDelivery();
    }

    /** Rehearse a real pregnancy through the normal server lifecycle, with its original child identities. */
    boolean prepareBirthScene(FamilyData.Brood pregnancy,String outcome){
        if(o.entity().level().isClientSide||pregnancy==null||o.brood()!=pregnancy||pregnancy.cancelled||pregnancy.complete()
                ||pregnancy.kind!=FamilyData.BirthKind.LIVE||pregnancy.elapsed>pregnancy.duration
                ||pregnancy.children.stream().anyMatch(child->child.born)||!Set.of("natural","caught","missed","overstrain").contains(outcome))return false;
        deliveryOutcome=outcome.equals("caught")?1:outcome.equals("overstrain")?2:outcome.equals("missed")?3:0;
        // A father who is to catch notices early enough to walk over, take off his hat and set it down before the labour starts.
        int lead=2;
        if(deliveryOutcome==1||deliveryOutcome==2){var helper=FamilyLife.catcher(o.entity(),pregnancy);if(helper!=null&&helper!=o.entity())lead=150;}
        o.resetCatchDuty();
        pregnancy.elapsed=Math.max(0,pregnancy.duration-lead);deliveryLast=null;deliveryYaw=Float.NaN;catcherWait=stationWait=0;catchStation=CatchStationState.UNUSED;
        FamilyData.get(o.level()).setDirty();return true;
    }

    /** Overstrain, a demonstrated miss, or the natural 12%: the newborn goes past the hat (into the water afloat, onto the ground ashore). */
    boolean missesDelivery(boolean naturalMiss){return deliveryOutcome==2||deliveryOutcome==3||deliveryOutcome==0&&naturalMiss;}

    /** A scene outcome is in force for this delivery (caught, overstrain or missed). */
    boolean sceneRequested(){return deliveryOutcome!=0;}

    /** The demonstrated miss: no hat is set down for this delivery. */
    boolean withholdsCushion(){return deliveryOutcome==3||catchStation==CatchStationState.ABANDONED;}

    /** A Marisa was due to catch this brood and no hat is down at the moment of birth: a miss, not a gentle drop. */
    boolean catchExpected(FamilyData.Brood brood){return catchStation==CatchStationState.ABANDONED||FamilyLife.catcher(o.entity(),brood)!=null;}

    boolean catchStationAbandoned(){return catchStation==CatchStationState.ABANDONED;}
    void catchStationActivated(FamilyData.Brood brood){if(brood!=null&&brood==o.brood()&&catchStation==CatchStationState.UNUSED)catchStation=CatchStationState.ACTIVE;}
    private void abandonCatchStation(FamilyData.Brood brood){
        if(catchStation!=CatchStationState.ACTIVE)return;
        catchStation=CatchStationState.ABANDONED;stationWait=0;o.abandonDeliveryStation(brood);
    }

    /** This newborn came out onto bare ground in a demonstrated miss: it will hit the floor, be hurt and cry. */
    void bumpOnLanding(){bumpPending=true;}

    boolean bumped(){return bumped||bumpPending;}

    /**
     * Water birth with a partner: his own hat boat comes alongside hers for the last stretch and the labour (he needs no shore), so that a share
     * of the newborns can be sent across into his hat (FamilyData.waterCradle). Without him, or when he cannot come, she gives birth alone in her hat.
     */
    boolean tickWaterPartner(){
        var mine=o.boatHome();
        if(o.partner()==null||mine==null||!safePartnerHull(o.entity(),mine))return false;
        if(!(o.level().getEntity(o.partner()) instanceof YukkuriEntity mate)||mate==o.entity())return false;
        var hers=mate.family().boatHome();
        if(hers==null||hers==mine||!safePartnerHull(mate,hers)||o.entity().distanceToSqr(mate)>576)return false;
        var pregnancy=mate.family().brood();
        if(pregnancy!=null&&!pregnancy.cancelled&&!pregnancy.complete()&&pregnancy.kind==FamilyData.BirthKind.LIVE){
            waterPartnerChild=null;
            if(!o.entity().getUUID().equals(pregnancy.father)||pregnancy.elapsed<pregnancy.duration-NEST_GRACE||laboring())return false;
            return approachWaterPartner(mine,hers,mate)!=HatBoatEntity.Drive.BLOCKED;
        }
        return rebalanceWaterYoung(hers,mine,mate);
    }
    /**
     * A newborn's durable parents outlive the completed brood record. Keep this limited to a dependent baby actually aboard
     * its recorded mother's own hull and the partner father's occupied hull; CradlePickup reserves and physically transfers it.
     */
    private boolean rebalanceWaterYoung(HatBoatEntity motherHat,HatBoatEntity fatherHat,YukkuriEntity mother){
        if(motherHat.hasIncoming()||fatherHat.hasIncoming())return false;
        long now=o.level().getGameTime();
        if(now>=nextWaterPartnerScan){
            nextWaterPartnerScan=now+20;waterPartnerChild=null;
            for(var rider:motherHat.getPassengers())if(rider instanceof YukkuriEntity child&&transferChild(child,motherHat,mother)
                    &&reducesBoatLoad(motherHat,fatherHat,child)){waterPartnerChild=child.getUUID();break;}
        }
        if(waterPartnerChild==null||!(o.level().getEntity(waterPartnerChild) instanceof YukkuriEntity child)
                ||!transferChild(child,motherHat,mother)||!fatherHat.hasRoomFor(child))return false;
        var approach=approachWaterPartner(fatherHat,motherHat,mother);
        if(approach==HatBoatEntity.Drive.BLOCKED)return false;
        if(approach==HatBoatEntity.Drive.MOVING)return true;
        return mother.family().cradlePickup().startBoatTransfer(motherHat,fatherHat,child);
    }
    private boolean transferChild(YukkuriEntity child,HatBoatEntity motherHat,YukkuriEntity mother){
        return child.isAlive()&&child.getVehicle()==motherHat&&child.family().stage()==FamilyLife.Stage.BABY
                &&mother.getUUID().equals(child.family().mother())&&o.entity().getUUID().equals(child.family().father());
    }
    /** Water paths end at cell centres; use the established small physical docking steps for the final supported approach. */
    private HatBoatEntity.Drive approachWaterPartner(HatBoatEntity mine,HatBoatEntity hers,YukkuriEntity mother){
        if(!mine.cargo().paddle())return HatBoatEntity.Drive.BLOCKED;
        var forward=Vec3.directionFromRotation(0,mother.yBodyRot).multiply(1,0,1).normalize();
        var right=new Vec3(-forward.z,0,forward.x);
        double sign=mine.position().subtract(hers.position()).dot(right)>=0?1:-1;
        var station=hers.position().add(forward.scale(.5)).add(right.scale(sign*STATION_GAP));
        double distance=mine.position().subtract(station).horizontalDistanceSqr();
        if(distance<=1&&partnerApproachClear(mine,station)){
            hers.holdPosition();
            return mine.driveToward(station,mine.getYRot(),.035,4f);
        }
        long now=o.level().getGameTime();
        if(now>=nextWaterPartnerRoute){
            nextWaterPartnerRoute=now+20;
            if(!mine.paddleTo(station))return HatBoatEntity.Drive.BLOCKED;
        }
        return mine.paddling()?HatBoatEntity.Drive.MOVING:HatBoatEntity.Drive.BLOCKED;
    }
    private boolean partnerApproachClear(HatBoatEntity boat,Vec3 target){
        var from=boat.position();int count=Math.max(1,(int)Math.ceil(from.distanceTo(target)/.1));
        for(int i=1;i<=count;i++){
            var at=from.lerp(target,(double)i/count);var cell=BlockPos.containing(at.add(0,-.12,0));
            if(!HatBoatRoute.calmSurface(o.level(),cell)||!o.level().getWorldBorder().isWithinBounds(cell)
                    ||!o.level().noCollision(boat,boat.getBoundingBox().move(at.subtract(from))))return false;
        }
        return true;
    }
    private static boolean safePartnerHull(YukkuriEntity parent,HatBoatEntity boat){
        return transferReady(parent)&&boat.isAfloat()&&!boat.unsteadyMotion()&&!boat.towed()&&boat.flooding()<=0
                &&!boat.landing()&&!boat.cargo().handling()&&!parent.family().braidRescueActive()
                &&!parent.aquaticLife().care().active()&&!parent.aquaticLife().familyLife().alarm().active();
    }
    /** A read-only-in-effect reservation round proves this particular child's real body-plus-return-paddle load lowers the total imbalance. */
    private boolean reducesBoatLoad(HatBoatEntity motherHat,HatBoatEntity fatherHat,YukkuriEntity child){
        if(!fatherHat.hasRoomFor(child))return false;
        int motherUsed=motherHat.capacityUsed(),fatherUsed=fatherHat.capacityUsed();
        if(fatherHat.reserveRescueBrim(child,child.position())==null)return false;
        int load=fatherHat.capacityUsed()-fatherUsed;fatherHat.cancelIncoming(child);
        return load>0&&Math.abs((motherUsed-load)-(fatherUsed+load))<Math.abs(motherUsed-fatherUsed);
    }
    private static boolean transferReady(YukkuriEntity parent){
        return parent.isAlive()&&!parent.isNoAi()&&!parent.isCommandPlaying()&&!parent.operations().active()&&!parent.isPanicking()
                &&!parent.isOnFire()&&!parent.life().isSleeping()&&!parent.life().hasUrgentNeed()&&parent.life().mealTicks()==0;
    }

    /**
     * Where the gestation may advance. The clock runs wherever a healthy mother is until the last {@link #NEST_GRACE} ticks (it used to run only
     * beside a claimed nest, so a mother whose nest was lost or far never progressed and neither grew a belly or stem nor gave birth); in the
     * last stretch she is at the nest, afloat in her own hat boat (no partner and no shore needed), at a bank, or, with no nest in reach, on
     * firm ground after a short wait. {@link FamilyLife#nestStand()} is the single place where the family code asks for her nest.
     */
    boolean placeForTerm(FamilyData.Brood brood,HatBoatEntity boat){
        var data=FamilyData.get(o.level());
        if(boat!=null)return data.relocate(brood,o.level(),boat.blockPosition());
        var station=o.deliveryStation(brood);
        if(station!=null)return o.deliveryStationReady(brood,station)&&data.relocate(brood,o.level(),BlockPos.containing(station.at()));
        // A water mother may wait for a recoverable shore/launch retry, but never silently finish this brood on land.
        if(waterBirthRequired(brood)&&nearWaterBirthTerm(brood))return false;
        if(brood.elapsed<brood.duration-NEST_GRACE)return true;
        var home=o.nestStand();
        boolean away=home==null||o.entity().distanceToSqr(Vec3.atBottomCenterOf(home))>4;
        if(away&&o.supported())nestless++;else if(!away)nestless=0;
        boolean bank=away&&(o.bankNest()||nestless>=NEST_PATIENCE);
        return !(away&&!bank)&&data.relocate(brood,o.level(),bank?o.entity().blockPosition():home);
    }

    void advanceLive(FamilyData.Brood brood){
        String previous=hold;
        advanceLiveTick(brood);
        if(!hold.isEmpty())GameplayLog.limited(o.entity(),"family","delivery."+hold,200,
                "delivery waiting brood={} reason={} elapsed={}/{} unborn={} catcherWait={} stationWait={}",
                brood.id,hold,brood.elapsed,brood.duration,brood.unborn(),catcherWait,stationWait);
        else if(!previous.isEmpty())GameplayLog.limited(o.entity(),"family","delivery.resumed",200,
                "delivery resumed brood={} previous={} elapsed={}/{}",brood.id,previous,brood.elapsed,brood.duration);
    }

    private void advanceLiveTick(FamilyData.Brood brood){
        Vec3 before=deliveryLast;deliveryLast=o.entity().position();
        hold="";
        // Between two births she may lift a fallen newborn: the clock waits for the braid to finish.
        if(o.rescuingYoung()){hold="rescuing_young";return;}
        if(!readyForDelivery()){hold="mother_not_ready";return;}
        var boat=o.boatHome();
        if(boat==null&&waterBirthRequired(brood)&&nearWaterBirthTerm(brood)){
            // The family goal has yielded its MOVE flag to AquaticLife. There is no permanent delivery task here: a
            // missing shore, paddle or surface can be corrected and then the normal launch retries.
            hold="waiting_for_own_hat";return;
        }
        // A helper's hat is placed for one outlet; a pause in the mother's route must not turn it into a birth elsewhere.
        var station=boat==null?o.deliveryStation(brood):null;
        if(catchStation==CatchStationState.ACTIVE&&station==null){abandonCatchStation(brood);deliveryYaw=Float.NaN;}
        if(station!=null&&!o.deliveryStationReady(brood,station)){
            if(++stationWait<=400){hold="waiting_for_catch_station";return;}
            abandonCatchStation(brood);deliveryYaw=Float.NaN;station=null;
        }
        if(station!=null){
            stationWait=0;deliveryYaw=station.yaw();
            if(!FamilyData.get(o.level()).relocate(brood,o.level(),BlockPos.containing(station.at()))){hold="no_place_for_term";return;}
        }else if(!placeForTerm(brood,boat)){hold="no_place_for_term";return;}
        // On land the mother must hold still; afloat, drifting with the water does not stop a delivery.
        if(boat==null&&brood.elapsed>=brood.duration&&(before==null||before.distanceToSqr(deliveryLast)>.0004)){hold="mother_moving";return;}
        int next=0;while(next<brood.children.size()&&brood.children.get(next).born)next++;
        if(next==brood.children.size())return;
        if(boat!=null&&brood.elapsed>=brood.duration-2
                &&!FamilyData.get(o.level()).prepareWaterBirthCargoSpace(brood,boat,o.level(),LiveBirthGeometry.position(o.entity(),next))){
            hold="boat_cargo_space_pending";return;
        }
        if(boat==null&&brood.elapsed>=brood.duration-2&&o.catchAlone(brood)){hold="waiting_for_hat";return;}
        if(o.hatMoving()){hold="hat_move";return;}
        if(brood.elapsed>=brood.duration){
            o.entity().getNavigation().stop();
            if(Float.isNaN(deliveryYaw))deliveryYaw=o.entity().yBodyRot;
            o.face(deliveryYaw);
            // Let the real father finish walking over and placing his hat before the head emerges.
            // Bounded and saved: an unreachable helper cannot hold a pregnancy forever or restart the wait on reload.
            if(boat==null&&brood.elapsed<brood.duration+LiveBirthGeometry.emergenceStart(0)
                    &&waitForCatcher(brood)){
                hold="waiting_for_catcher";return;
            }
            if(!LiveBirthGeometry.clear(o.entity(),brood.children.get(next),next)){hold="outlet_blocked";return;}
        }
        float nextBelly=LiveBirthGeometry.belly(Math.min(1,(brood.elapsed+1f)/brood.duration),brood.children.size()-brood.unborn(),brood.children.size());
        var room=LiveBirthGeometry.bodyDimensions(o.entity(),o.entity().growthScale(),nextBelly).scale(o.entity().getScale()).makeBoundingBox(o.entity().position());
        if(!o.level().noCollision(o.entity(),room)){hold="no_room_for_belly";return;}
        // Failed entity insertion pauses at this child's release, never running into the next child's clock.
        if(brood.elapsed<brood.duration+LiveBirthGeometry.birthTick(next)){
            brood.elapsed++;if(brood.elapsed%200==0)o.entity().life().spendNutrition(1);FamilyData.get(o.level()).setDirty();
            if(brood.elapsed==brood.duration)GameplayLog.event(o.entity(),"family",
                    "delivery labor started brood={} kind={} children={} boat={}",brood.id,brood.kind,brood.children.size(),boat==null?null:boat.getUUID());
        }
        if(brood.elapsed>=brood.duration){
            int delivery=brood.elapsed-brood.duration;
            labourBeats(delivery);
            if(brood.elapsed>=brood.duration+LiveBirthGeometry.birthTick(next)){
                var child=FamilyData.get(o.level()).birth(brood,next,o.level(),LiveBirthGeometry.position(o.entity(),next));
                if(child!=null){
                    // A soft physical release lets the landed sibling clear the next child's outlet; a small visible pop (project original).
                    if(boat==null&&!child.isPassenger()&&!child.family().doomed()&&!child.family().flying())child.setDeltaMovement(new Vec3(0,.26,
                            -.12).yRot((float)Math.toRadians(180-o.entity().yBodyRot)).scale(o.entity().getScale()));
                    BirthStaging.emerge(o.entity(),child.position());
                    newborn=child.getUUID();welcomeTicks=WELCOME;
                    // Later children come easier but leave her more tired.
                    o.entity().showReaction(next>0?"sleepy":"closed_smile","birth",LiveBirthGeometry.REST);
                    if(brood.father!=null&&o.level().getEntity(brood.father) instanceof YukkuriEntity helper&&helper.family().midwifeTarget()==o.entity())
                        helper.showReaction("happy","caregiver",LiveBirthGeometry.REST);
                }
            }
        }
        if(brood.complete())finishLive();
    }

    /** Bounded, persisted patience for the real father; shared by live and stem deliveries. */
    boolean waitForCatcher(FamilyData.Brood brood) {
        var helper=FamilyLife.catcher(o.entity(),brood);
        if(catcherWait>=CatchHat.CATCH_WAIT||withholdsCushion()||helper==null||helper==o.entity()
                ||helper.family().catchDutyGaveUp()||helper.family().cradleEntity()!=null||!helper.family().dutyReady())return false;
        catcherWait++;return true;
    }

    /** A parent hurries to a newborn that fell hard, nuzzles it and licks it (project original; nuzzling is Wiki E06). */
    void comfort(YukkuriEntity baby){
        if(o.entity().level().isClientSide||baby==null||baby==o.entity()||!o.adult()||o.entity().isNoAi()||!o.entity().isAlive())return;
        comfortBaby=baby.getUUID();comfortTicks=COMFORT;comfortNear=0;
        o.entity().getNavigation().stop();
        o.entity().mind().felt(EmotionEvent.FRIGHTENED); o.entity().showReaction("surprise","afraid",40);
        BirthStaging.caption(o.entity(),"父油大惊，赶到哭泣的新生儿身边");
    }

    void tickComfort(){
        if(o.task()==Task.MIDWIFE)return;
        comfortTicks--;
        var baby=comfortBaby!=null&&o.level().getEntity(comfortBaby) instanceof YukkuriEntity found&&found.isAlive()?found:null;
        if(baby==null||!o.dutyReady()||o.hatMoving()){comfortTicks=0;comfortNear=0;return;}
        double near=(o.entity().getBbWidth()+baby.getBbWidth())*.5+.12;
        var away=o.entity().position().subtract(baby.position()).multiply(1,0,1);
        double gap=away.length();
        var side=gap>1.0e-3?away.scale(1/gap):new Vec3(0,0,1);
        if(gap>near+.2){
            comfortNear=0;
            o.entity().getLookControl().setLookAt(baby,20,20);
            SurfaceApproach.walk(o.entity(),baby.position().add(side.scale(near)),1.1);
            return;
        }
        SurfaceApproach.halt(o.entity());
        o.face((float)(Mth.atan2(baby.getZ()-o.entity().getZ(),baby.getX()-o.entity().getX())*Mth.RAD_TO_DEG)-90);
        comfortNear++;
        if(comfortNear==10){o.entity().showReaction("closed_smile","nuzzle",60);BirthStaging.affection(o.entity());BirthStaging.caption(o.entity(),"蹭脸安抚");}
        if(comfortNear==70){o.entity().showReaction("open_smile","",40);BirthStaging.affection(o.entity());BirthStaging.caption(o.entity(),"轻轻舔舐安抚");}
        if(comfortNear>=130){comfortTicks=0;comfortNear=0;}
    }

    /** The uncushioned fall (project original): a thud, a small bounce, a real wound and a lasting cry. The wound goes through
     *  the body's ordinary injury record, so it heals, is treated or festers by the existing rules; it is not fatal. */
    void bump(){
        bumped=true;bumpTicks=140;
        BirthStaging.thud(o.entity());BirthStaging.whimper(o.entity());
        var motion=o.entity().getDeltaMovement();o.entity().setDeltaMovement(motion.x,.2,motion.z);o.entity().hasImpulse=true;
        // A real fall injury through the ordinary damage path (wound record, infection and care rules), never lethal.
        float lost=Math.min(2f,o.entity().getHealth()-1f);
        if(lost>0)o.entity().hurt(o.level().damageSources().fall(),lost);
        o.entity().mind().felt(EmotionEvent.WOUND_DISTRESS); o.entity().showReaction("cry","wounded",140);
        // The newborn carries no exhibit tag; the report goes out under its mother.
        if(o.mother()!=null&&o.level().getEntity(o.mother()) instanceof YukkuriEntity parent)
            BirthStaging.caption(parent,"漏接：新生儿重重摔在地上，弹了一下，受了摔伤哭了起来（进入伤势系统，不致命，可照料）");
    }

    void tickBump(){
        bumpTicks--;
        if(bumpTicks%8==0)BirthStaging.tears(o.entity());
        if(bumpTicks==100)BirthStaging.whimper(o.entity());
    }

    /** Delivery-tick beats on the server clock: labour signs in the lead-in, the calls and soft sounds at the pushes.
     *  Each fires at one tick value, so a reload or a paused delivery cannot repeat it. Project-original staging. */
    private void labourBeats(int delivery){
        int prep=LiveBirthGeometry.PREPARATION;
        if(delivery<prep){
            // Restless and nervous: a worried face, beads of sweat, then she settles into place.
            if(delivery==1)o.entity().showReaction("teary","",34);
            if(delivery%30==12)BirthStaging.sweat(o.entity());
            if(delivery==prep/2)o.entity().showReaction("surprise","",30);
            if(delivery==prep-4)BirthStaging.settle(o.entity());
            return;
        }
        int index=LiveBirthGeometry.cycleIndex(delivery),strain=LiveBirthGeometry.strain(index),cycle=(int)LiveBirthGeometry.cycleTick(delivery);
        // Calls of birth at the start of the strain and again at the second push.
        if(cycle==0||cycle==strain+36)o.entity().showReaction("chewing_closed","birth",28);
        if(cycle==strain+8)BirthStaging.push(o.entity(),.9f);
        if(cycle==strain+46)BirthStaging.push(o.entity(),.8f);
        if(cycle==strain+LiveBirthGeometry.EMERGENCE+10&&index>0)BirthStaging.sweat(o.entity());
    }

    /** After a birth the father and the older siblings nearby turn to the newborn, hop and greet it. */
    void welcome(){
        welcomeTicks--;
        if(!(o.level().getEntity(newborn) instanceof YukkuriEntity baby)||!baby.isAlive()){welcomeTicks=0;return;}
        var audience=o.level().getEntitiesOfClass(YukkuriEntity.class,o.entity().getBoundingBox().inflate(6),other->other!=o.entity()&&other!=baby&&other.isAlive()
                &&!other.isNoAi()&&!other.isPanicking()&&!other.life().isSleeping()&&!other.family().awaitingLanding()
                &&(other.getUUID().equals(o.partner())||o.entity().getUUID().equals(other.family().mother())));
        boolean hurt=baby.family().bumped();
        if(welcomeTicks==WELCOME-20&&!hurt)BirthStaging.caption(o.entity(),baby.getVehicle() instanceof HatBoatEntity?"新生儿落进帽中，被稳稳接住":"新生儿落在地面（这一胎没有放帽）");
        // The father hurries to a newborn that fell hard, wherever he is standing.
        if(hurt&&welcomeTicks==WELCOME-6&&o.partner()!=null&&o.level().getEntity(o.partner()) instanceof YukkuriEntity mate&&mate.isAlive()&&mate.distanceToSqr(baby)<256)mate.family().comfort(baby);
        int shown=0;
        for(var other:audience){
            if(shown++>=5)break;
            other.getLookControl().setLookAt(baby,20,20);
            if(welcomeTicks==WELCOME-30&&other.onGround()&&!other.isPassenger()&&!other.family().flying()){
                if(hurt){other.mind().felt(EmotionEvent.FRIGHTENED); other.showReaction("surprise","afraid",50);BirthStaging.sweat(other);continue;}
                // A small hop and a greeting for the newcomer.
                other.setDeltaMovement(other.getDeltaMovement().add(0,.2,0));
                other.showReaction("happy","caregiver",40);BirthStaging.affection(other);
            }
        }
    }

    /** The mother's recovery after the last birth: she turns to the newborn, nuzzles it and licks it once, then goes on
     *  to feed it (the existing mash feeding). Nuzzling is supported by the wiki (E06); the rest is project original. */
    void recoveryBeats(){
        int age=LiveBirthGeometry.RECOVERY-liveRecovery;
        YukkuriEntity baby=newborn!=null&&o.level().getEntity(newborn) instanceof YukkuriEntity found&&found.isAlive()?found:null;
        if(baby==null)baby=o.level().getEntitiesOfClass(YukkuriEntity.class,o.entity().getBoundingBox().inflate(5),other->other!=o.entity()&&other.isAlive()
                &&o.entity().getUUID().equals(other.family().mother())&&!other.family().adult()).stream().min(Comparator.comparingDouble(o.entity()::distanceToSqr)).orElse(null);
        if(baby==null)return;
        if(age>=24&&age<=150){
            o.entity().getLookControl().setLookAt(baby,20,20);
            // Turn the body toward the baby a few degrees a tick; the clip adds its own head turn on top.
            float yaw=(float)(Mth.atan2(baby.getZ()-o.entity().getZ(),baby.getX()-o.entity().getX())*Mth.RAD_TO_DEG)-90;
            if(age>=40)o.face(Mth.approachDegrees(o.entity().getYRot(),yaw,5));
        }
        if(age==24&&baby.family().bumped()){
            o.entity().mind().felt(EmotionEvent.FRIGHTENED); o.entity().showReaction("surprise","afraid",50);BirthStaging.sweat(o.entity());BirthStaging.caption(o.entity(),"母油见孩子摔伤，惊慌地转向它");
        }
        if(age==88){
            o.entity().showReaction("happy","nuzzle",60);BirthStaging.affection(o.entity());
            if(baby.family().bumped())BirthStaging.caption(o.entity(),"母油蹭脸安抚摔伤的孩子，随后轻舔");
        }
        if(age==116){o.entity().showReaction("open_smile","",30);BirthStaging.affection(o.entity());}
    }

    void finishLive(){
        var completed=o.brood();
        if(o.cradleEntity()!=null)o.lingerCradle(LiveBirthGeometry.RECOVERY);
        o.clearGestation();liveRecovery=LiveBirthGeometry.RECOVERY;
        o.recovery(Math.max(o.recovery(),FamilyConfig.RECOVERY.get()));deliveryLast=null;deliveryYaw=Float.NaN;catcherWait=stationWait=nestless=0;deliveryOutcome=0;catchStation=CatchStationState.UNUSED;
        o.resetCatchDuty();
        o.stop();o.entity().getNavigation().stop();o.task(Task.RECOVER);o.waitTicks(0);
        GameplayLog.event(o.entity(),"family","delivery complete brood={} recovery={} newborn={}",
                completed==null?null:completed.id,liveRecovery,newborn);
    }
}
