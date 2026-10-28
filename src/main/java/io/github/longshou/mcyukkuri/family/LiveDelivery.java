package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.status.GameplayLog;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife.Task;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Gesture;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
    private Vec3 landExit;
    private boolean leftNest;
    private float deliveryYaw=Float.NaN;
    private int exitChild=-1,exitWait;
    private int exitTurnTicks;
    /** A transient geometric choice, never a second seat lease. Loading must revalidate it before continuing. */
    private BirthExit.Exit selectedExit;
    private boolean unprotectedExit;
    boolean unprotectedExit(){return unprotectedExit;}
    BirthExit.Exit selectedExit(int index){return index==exitChild?selectedExit:null;}
    /** One maternal clock covers approach, placement and the final wait for this brood. */
    private int catcherWait, catchWindowBudget;
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
    private boolean bumped;
    private UUID comfortBaby;
    private static final int COMFORT=200;
    private static final int NEST_GRACE=400, NEST_PATIENCE=100;
    private String hold="";
    /**
     * The recovery and comfort beats as a motor owner: both run inside the family goal's decision tick, so they may ask for a
     * {@link Face} (spec 9.4). Active exactly while a beat that wants to turn is running, so the driver drops the turn when the
     * beat ends.
     */
    private final MotorOwner beats=new MotorOwner() {
        @Override public String ownerId() { return "family_birth_beats"; }
        @Override public boolean active() { return liveRecovery>0||comfortTicks>0; }
    };

    LiveDelivery(FamilyLife o) { this.o=o; }
    int recoveryTicks() { return liveRecovery; }
    boolean comfortActive() { return comfortTicks>0; }
    boolean nuzzling() { return o.task()==Task.COMFORT&&comfortTicks>0&&comfortNear>=10; }
    boolean bumping() { return bumpTicks>0; }
    boolean welcoming() { return welcomeTicks>0&&newborn!=null; }
    /** Passive detail for the one considered partner. */
    void tickRecovery() { if(liveRecovery>0)liveRecovery--; }
    void resetForConception() { selectedExit=null;unprotectedExit=false;exitChild=-1;exitWait=exitTurnTicks=0;landExit=null;leftNest=false;deliveryYaw=Float.NaN;catcherWait=catchWindowBudget=stationWait=deliveryOutcome=0;catchStation=CatchStationState.UNUSED; }
    void resetForAssistance() { selectedExit=null;unprotectedExit=false;exitChild=-1;exitWait=exitTurnTicks=0;deliveryYaw=Float.NaN;catcherWait=catchWindowBudget=stationWait=0;catchStation=CatchStationState.UNUSED; }
    void save(CompoundTag tag) {
        tag.putInt("LiveRecovery",liveRecovery);
        if(!Float.isNaN(deliveryYaw))tag.putFloat("DeliveryYaw",deliveryYaw);
        tag.putInt("CatcherWait",catcherWait);
        tag.putInt("CatchWindowBudget",catchWindowBudget);
        tag.putInt("CatchStationWait",stationWait);
        if(catchStation!=CatchStationState.UNUSED)tag.putByte("CatchStationState",(byte)catchStation.ordinal());
        tag.putInt("DeliveryOutcome",deliveryOutcome);
        tag.putInt("BirthExitChild",exitChild);tag.putInt("BirthExitWait",exitWait);
        tag.putBoolean("BirthExitUnprotected",unprotectedExit);
    }
    void load(CompoundTag tag) {
        selectedExit=null;exitTurnTicks=0;
        exitChild=tag.contains("BirthExitChild")?tag.getInt("BirthExitChild"):-1;exitWait=Math.clamp(tag.getInt("BirthExitWait"),0,NEST_GRACE+1);
        unprotectedExit=tag.getBoolean("BirthExitUnprotected");
        landExit=null;leftNest=false;
        liveRecovery=Math.clamp(tag.getInt("LiveRecovery"),0,LiveBirthGeometry.RECOVERY);deliveryLast=null;
        deliveryYaw=tag.contains("DeliveryYaw")?tag.getFloat("DeliveryYaw"):Float.NaN;
        catcherWait=Math.clamp(tag.getInt("CatcherWait"),0,CatchHat.APPROACH_LEAD+CatchHat.CATCH_WAIT);
        catchWindowBudget=Math.clamp(tag.getInt("CatchWindowBudget"),0,CatchHat.APPROACH_LEAD+CatchHat.CATCH_WAIT);
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
        var b=o.brood();return b!=null&&!b.cancelled&&!b.complete()&&b.kind==FamilyData.BirthKind.LIVE&&b.elapsed>=b.duration;
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
        pregnancy.elapsed=Math.max(0,pregnancy.duration-lead);deliveryLast=null;deliveryYaw=Float.NaN;catcherWait=catchWindowBudget=stationWait=0;catchStation=CatchStationState.UNUSED;
        FamilyData.get(o.level()).setDirty();return true;
    }

    /** Overstrain, a demonstrated miss, or the natural 12%: the newborn goes past the hat (into the water afloat, onto the ground ashore). */
    boolean missesDelivery(boolean naturalMiss){return deliveryOutcome==2||deliveryOutcome==3||deliveryOutcome==0&&naturalMiss;}

    /** A scene outcome is in force for this delivery (caught, overstrain or missed). */
    boolean sceneRequested(){return deliveryOutcome!=0;}

    /** The demonstrated miss: no hat is set down for this delivery. */
    boolean withholdsCushion(){return deliveryOutcome==3||catchStation==CatchStationState.ABANDONED;}

    boolean catchStationAbandoned(){return catchStation==CatchStationState.ABANDONED;}
    void catchStationActivated(FamilyData.Brood brood){if(brood!=null&&brood==o.brood()&&catchStation==CatchStationState.UNUSED)catchStation=CatchStationState.ACTIVE;}
    private void closeCatchWindow(FamilyData.Brood brood,String reason){
        if(catchStation==CatchStationState.ABANDONED)return;
        catchStation=CatchStationState.ABANDONED;stationWait=0;deliveryYaw=Float.NaN;
        o.abandonDeliveryStation(brood);
        GameplayLog.event(o.entity(),"family","catch window closed brood={} reason={} elapsed={}/{} waited={}/{}",
                brood.id,reason,brood.elapsed,brood.duration,catcherWait,catchWindowBudget);
    }

    /** A pure admission check for a not-yet-placed hat; the mother alone closes this window. */
    boolean acceptsCatch(FamilyData.Brood brood){
        if(brood==null||brood!=o.brood()||brood.cancelled||brood.complete()||brood.kind==FamilyData.BirthKind.EGG
                ||withholdsCushion()||brood.children.stream().anyMatch(child->child.born))return false;
        int release=brood.kind==FamilyData.BirthKind.LIVE?LiveBirthGeometry.emergenceStart(0):0;
        return brood.elapsed<brood.duration+release&&(catchWindowBudget==0||catcherWait<catchWindowBudget);
    }

    /** Advance once per ready maternal tick, including a blocked nest exit or meeting route. */
    void tickCatchWindow(FamilyData.Brood brood){
        if(catchStation!=CatchStationState.UNUSED||withholdsCushion()
                ||brood.elapsed<Math.max(0,brood.duration-CatchHat.APPROACH_LEAD)||!o.dutyReady())return;
        if(catchWindowBudget==0){
            if(FamilyLife.catcher(o.entity(),brood)==null)return;
            catchWindowBudget=Math.max(0,brood.duration-brood.elapsed)+CatchHat.CATCH_WAIT;
            GameplayLog.event(o.entity(),"family","catch window opened brood={} budget={} elapsed={}/{}",
                    brood.id,catchWindowBudget,brood.elapsed,brood.duration);
        }
        if(catcherWait<catchWindowBudget)catcherWait++;
        if(catcherWait>=catchWindowBudget)closeCatchWindow(brood,"placement_timeout");
    }

    /** Live and stem deliveries wait for the same actual placed hat and bounded return to its outlet. */
    boolean readyAtCatchStation(FamilyData.Brood brood){
        var station=o.deliveryStation(brood);
        if(catchStation==CatchStationState.ACTIVE&&station==null){closeCatchWindow(brood,"station_lost");return true;}
        if(station==null)return true;
        if(!o.deliveryStationReady(brood,station)){
            if(++stationWait<=400)return false;
            closeCatchWindow(brood,"station_timeout");return true;
        }
        stationWait=0;deliveryYaw=station.yaw();return true;
    }

    boolean bumped(){return bumped;}

    /**
     * Where the gestation may advance. The clock runs wherever a healthy mother is until the last {@link #NEST_GRACE} ticks (it used to run only
     * beside a claimed nest, so a mother whose nest was lost or far never progressed and neither grew a belly or stem nor gave birth); in the
     * last stretch she is at the nest, at a station before a helper's placed hat, or, with no nest in reach, on firm ground after a short
     * wait. {@link FamilyLife#nestStand()} is the single place where the family code asks for her nest.
     */
    /**
     * Actual near-term walking clears the nest before a capable father lays his hat. A live mother also steps out of a
     * one-body pet cushion nest: it holds one adult (no newborns, NestOccupancy) and its bolster stands where the newborn
     * comes out, so she gives birth on the floor beside it (project design).
     */
    boolean walkOutForCatcher(FamilyData.Brood brood){
        if(brood==null||brood.elapsed<brood.duration-NEST_GRACE)return false;
        var helper=FamilyLife.catcher(o.entity(),brood);
        var care=FamilyNest.care(o.entity());var shelter=o.entity().habitat().shelter();
        boolean helped=helper!=null&&helper!=o.entity()&&helper.family().dutyReady()&&!helper.family().catchDutyGaveUp();
        boolean cushion=care!=null&&!care.largeNest()&&brood.kind==FamilyData.BirthKind.LIVE;
        if(!helped&&!cushion){landExit=null;leftNest=false;return false;}
        BlockPos bed=care!=null?care.getBlockPos():shelter!=null?shelter.bed(o.entity().getUUID()):null;
        if(bed==null)return false;
        if(leftNest)return false;
        if(o.entity().distanceToSqr(Vec3.atBottomCenterOf(bed))>4&&o.supported())return finishNestExit(brood,bed);
        if(landExit==null){
            for(var side:Direction.Plane.HORIZONTAL){
                var at=SurfaceApproach.floor(o.entity(),Vec3.atBottomCenterOf(bed.relative(side,3)));
                if(at!=null&&SurfaceApproach.supported(o.entity(),at)&&o.entity().life().reachable(BlockPos.containing(at),false)){
                    landExit=at;GameplayLog.event(o.entity(),"family","near-term leave nest target={} catcher={} reason={}",at,
                            helped?helper.getUUID():null,helped?"catcher":"one_body_cushion_nest");break;
                }
            }
        }
        if(landExit==null){GameplayLog.limited(o.entity(),"family","nest_exit_blocked",200,"near-term nest exit blocked nest={}",bed);return false;}
        if(SurfaceApproach.walk(o.entity(),landExit,.9))return finishNestExit(brood,bed);
        return true;
    }
    private boolean finishNestExit(FamilyData.Brood brood,BlockPos bed){
        leftNest=true;
        GameplayLog.event(o.entity(),"family","near-term nest exit reached brood={} nest={} target={} at={}",
                brood.id,bed,landExit,o.entity().position());
        return false;
    }
    boolean outOfNestForTerm(){return leftNest;}
    /** A helper observes this handoff; only the mother's movement goal may finish the actual exit. */
    boolean leavingNestForCatcher(FamilyData.Brood brood){
        if(leftNest||brood==null)return false;
        var care=FamilyNest.care(o.entity());var shelter=o.entity().habitat().shelter();
        return care!=null||shelter!=null&&shelter.bed(o.entity().getUUID())!=null;
    }
    boolean placeForTerm(FamilyData.Brood brood){
        var data=FamilyData.get(o.level());
        var station=o.deliveryStation(brood);
        if(station!=null)return o.deliveryStationReady(brood,station)&&data.relocate(brood,o.level(),BlockPos.containing(station.at()));
        var meeting=o.catchRendezvous(brood);
        if(meeting!=null)return SurfaceApproach.arrived(o.entity(),meeting.at())&&data.relocate(brood,o.level(),BlockPos.containing(meeting.at()));
        if(brood.elapsed<brood.duration-NEST_GRACE)return true;
        if(leftNest)return o.supported()&&data.relocate(brood,o.level(),o.entity().blockPosition());
        if(landExit!=null)return false;
        if(FamilyNest.large(o.entity())){
            // Lying in the nest: at her rest place, or (when no place stands for her right now) on the nest's real floor
            // inside its rim, never on the rim itself.
            var nest=o.nestStand();var at=nest==null?null:FamilyNest.restPosition(o.entity(),nest);
            boolean lying=at!=null?o.entity().distanceToSqr(at)<.16:nest!=null&&FamilyNest.onLargeFloor(o.entity());
            if(!lying&&nest!=null)GameplayLog.limited(o.entity(),"family","term_not_lying",200,
                    "near-term not lying in nest nest={} rest={} distance={} onFloor={}",nest,at,
                    at==null?"n/a":String.format(java.util.Locale.ROOT,"%.2f",Math.sqrt(o.entity().distanceToSqr(at))),FamilyNest.onLargeFloor(o.entity()));
            return lying&&o.supported()&&data.relocate(brood,o.level(),nest);
        }
        var home=o.nestStand();
        boolean away=home==null||o.entity().distanceToSqr(Vec3.atBottomCenterOf(home))>4;
        if(away&&o.supported())nestless++;else if(!away)nestless=0;
        boolean bank=away&&nestless>=NEST_PATIENCE;
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
        if(!readyForDelivery()){hold="mother_not_ready";return;}
        tickCatchWindow(brood);
        if(!readyAtCatchStation(brood)){hold="waiting_for_catch_station";return;}
        if(!placeForTerm(brood)){hold="no_place_for_term";return;}
        // The mother must hold still.
        if(brood.elapsed>=brood.duration&&(before==null||before.distanceToSqr(deliveryLast)>.0004)){hold="mother_moving";return;}
        int next=0;while(next<brood.children.size()&&brood.children.get(next).born)next++;
        if(next==brood.children.size())return;
        if(brood.elapsed>=brood.duration-2&&o.waitsForOwnHat(brood)){hold="waiting_for_hat";return;}
        if(o.hatMoving()){hold="hat_move";return;}
        if(brood.elapsed>=brood.duration){
            // Until the helper has placed a real hat, the movement goal owns the current meeting's position and yaw.
            // Locking an earlier yaw here makes a reselected site wait forever for a turn that this tick undoes.
            if(brood.elapsed<brood.duration+LiveBirthGeometry.emergenceStart(0)&&waitForCatcher(brood)){
                deliveryYaw=Float.NaN;hold="waiting_for_catcher";return;
            }
            o.entity().getNavigation().stop();
            if(exitChild!=next){deliveryYaw=Float.NaN;selectedExit=null;exitWait=exitTurnTicks=0;unprotectedExit=false;exitChild=next;}
            if(selectedExit==null||unprotectedExit&&!BirthExit.unobstructed(o.entity(),next))deliveryYaw=Float.NaN;
            if(!unprotectedExit&&!Float.isNaN(deliveryYaw)&&(selectedExit==null||!BirthExit.valid(o.entity(),next,selectedExit))){
                deliveryYaw=Float.NaN;selectedExit=null;exitWait++;
            }
            if(Float.isNaN(deliveryYaw)){
                var exit=exitWait>NEST_GRACE?BirthExit.unprotected(o.entity(),next):BirthExit.choose(o.entity(),next);
                if(!exit.safe()&&++exitWait<=NEST_GRACE){
                    hold="no_safe_exit";
                    GameplayLog.limited(o.entity(),"family","birth.exit.none",100,
                            "birth exit none child={} yaw={} target={} candidates={} wait={}/{}",next,exit.yaw(),exit.targetKind(),exit.candidates(),exitWait,NEST_GRACE);
                    return;
                }
                if(!exit.safe()){
                    exit=BirthExit.unprotected(o.entity(),next);
                    if(!exit.safe()){hold="outlet_blocked";return;}
                    unprotectedExit=true;
                }
                unprotectedExit|=exit.targetKind().equals("unprotected");
                selectedExit=exit;deliveryYaw=exit.yaw();exitTurnTicks=0;
                GameplayLog.event(o.entity(),"family","birth exit selected child={} yaw={} target={} hat={} slot={} admission={} candidates={} reason={}",
                        next,exit.yaw(),exit.targetKind(),exit.target()==null||exit.target().hat()==null?null:exit.target().hat().getUUID(),
                        exit.target()==null?-1:exit.target().slot(),exit.target()==null?"none":exit.target().admission(),exit.candidates(),unprotectedExit?"no_safe_exit_timeout":"safe");
            }
            // A passive clock may not start an action: this is the same bounded step (ability turn rate) FamilyLife.face uses.
            o.face(deliveryYaw);
            if(Math.abs(Mth.wrapDegrees(o.entity().yBodyRot-deliveryYaw))>1.5f){
                if(++exitTurnTicks>=40){exitWait+=exitTurnTicks;exitTurnTicks=0;deliveryYaw=Float.NaN;selectedExit=null;}
                hold="turning_to_exit";return;
            }
            exitTurnTicks=0;
            if(!unprotectedExit&&!LiveBirthGeometry.clear(o.entity(),brood.children.get(next),next)){
                deliveryYaw=Float.NaN;selectedExit=null;exitWait++;hold="outlet_blocked";return;
            }
        }
        float nextBelly=LiveBirthGeometry.belly(Math.min(1,(brood.elapsed+1f)/brood.duration),brood.children.size()-brood.unborn(),brood.children.size());
        var room=LiveBirthGeometry.bodyDimensions(o.entity(),o.entity().growthScale(),nextBelly).scale(o.entity().getScale()).makeBoundingBox(o.entity().position());
        if(!o.level().noCollision(o.entity(),room)){hold="no_room_for_belly";return;}
        // Failed entity insertion pauses at this child's release, never running into the next child's clock.
        if(brood.elapsed<brood.duration+LiveBirthGeometry.birthTick(next)){
            brood.elapsed++;if(brood.elapsed%200==0)o.entity().life().spendNutrition(1);FamilyData.get(o.level()).setDirty();
            if(brood.elapsed==brood.duration)GameplayLog.event(o.entity(),"family",
                    "delivery labor started brood={} kind={} children={}",brood.id,brood.kind,brood.children.size());
        }
        if(brood.elapsed>=brood.duration){
            int delivery=brood.elapsed-brood.duration;
            labourBeats(delivery);
            if(brood.elapsed>=brood.duration+LiveBirthGeometry.birthTick(next)){
                var child=FamilyData.get(o.level()).birth(brood,next,o.level(),LiveBirthGeometry.position(o.entity(),next));
                if(child!=null){
                    BirthStaging.emerge(o.entity(),child.position());
                    newborn=child.getUUID();welcomeTicks=WELCOME;
                    // Later children come easier but leave her more tired.
                    o.entity().showReaction(next>0?"sleepy":"closed_smile","birth",LiveBirthGeometry.REST);
                    if(brood.father!=null&&o.level().getEntity(brood.father) instanceof YukkuriEntity helper&&helper.family().midwifeTarget()==o.entity())
                        helper.showReaction("happy","caregiver",LiveBirthGeometry.REST);
                }else if(++exitWait>NEST_GRACE){deliveryYaw=Float.NaN;selectedExit=null;}
            }
        }
        if(brood.complete())finishLive();
    }

    /** The same maternal window gates both the helper and the gestation clock. */
    boolean waitForCatcher(FamilyData.Brood brood) {
        var helper=FamilyLife.catcher(o.entity(),brood);
        return acceptsCatch(brood)&&helper!=null&&helper!=o.entity()&&!helper.family().catchDutyGaveUp()
                &&helper.family().cradleEntity()==null&&helper.family().dutyReady();
    }

    /** A parent hurries to a newborn that fell hard, nuzzles it and licks it (project original; nuzzling is Wiki E06). */
    void comfort(YukkuriEntity baby){
        if(o.entity().level().isClientSide||baby==null||baby==o.entity()||!o.adult()||o.entity().isNoAi()||!o.entity().isAlive())return;
        comfortBaby=baby.getUUID();comfortTicks=COMFORT;comfortNear=0;
        o.entity().mind().felt(EmotionEvent.FRIGHTENED); o.entity().showReaction("surprise","afraid",40);
    }

    void pauseComfort(){comfortNear=0;}

    /** Only the COMFORT task may advance contact or steer; queued comfort waits for birth and hat care. */
    void tickComfort(){
        if(o.task()!=Task.COMFORT)return;
        if(comfortTicks==COMFORT)BirthStaging.caption(o.entity(),"亲油走向哭泣的新生儿");
        comfortTicks--;
        var baby=comfortBaby!=null&&o.level().getEntity(comfortBaby) instanceof YukkuriEntity found&&found.isAlive()?found:null;
        if(baby==null||!o.dutyReady()||o.hatMoving()){comfortTicks=0;comfortNear=0;return;}
        double near=(o.entity().getBbWidth()+baby.getBbWidth())*.5+.12;
        var away=o.entity().position().subtract(baby.position()).multiply(1,0,1);
        double gap=away.length();
        if(gap>near+.2||Math.abs(o.entity().getY()-baby.getY())>.35||!o.entity().hasLineOfSight(baby)
                ||!o.supported()||!baby.family().supported()){
            comfortNear=0;
            o.entity().getLookControl().setLookAt(baby,20,20);
            var stand=SurfaceApproach.beside(o.entity(),baby,near,.35);
            if(stand==null)SurfaceApproach.halt(o.entity());
            else SurfaceApproach.walkAround(o.entity(),stand,baby,1.1);
            return;
        }
        SurfaceApproach.halt(o.entity());
        turnTo(o.entity(),(float)(Mth.atan2(baby.getZ()-o.entity().getZ(),baby.getX()-o.entity().getX())*Mth.RAD_TO_DEG)-90);
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
        // A real fall leaves a small bounce, sized by this body's own small hop (spec 9.4 Gesture HOP_SMALL). The landing tick
        // runs on a passive clock, so this is the impulse the gesture would apply, never a started action.
        double lift=Gesture.smallHopImpulse(o.entity());
        if(lift>0){
            var motion=o.entity().getDeltaMovement();
            Motor.velocity(o.entity(),new Vec3(motion.x,lift,motion.z),"birth_impact");
        }
        // A real fall injury through the ordinary damage path (wound record, infection and care rules), never lethal.
        float lost=Math.min(2f,o.entity().getHealth()-1f),healthBefore=o.entity().getHealth();
        if(lost>0)o.entity().hurt(o.level().damageSources().fall(),lost);
        GameplayLog.event(o.entity(),"family","birth impact injury lost={} health={}->{}",String.format(java.util.Locale.ROOT,"%.1f",Math.max(0f,lost)),healthBefore,o.entity().getHealth());
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
        if(welcomeTicks==WELCOME-20&&!hurt)BirthStaging.caption(o.entity(),baby.getVehicle() instanceof PlacedHatEntity?"新生儿落进帽中，被稳稳接住":"新生儿落在地面（这一胎没有放帽）");
        // The father hurries to a newborn that fell hard, wherever he is standing.
        if(hurt&&welcomeTicks==WELCOME-6&&o.partner()!=null&&o.level().getEntity(o.partner()) instanceof YukkuriEntity mate&&mate.isAlive()&&mate.distanceToSqr(baby)<256)mate.family().comfort(baby);
        int shown=0;
        for(var other:audience){
            if(shown++>=5)break;
            other.getLookControl().setLookAt(baby,20,20);
            if(welcomeTicks==WELCOME-30&&other.onGround()&&!other.isPassenger()&&!other.family().flying()){
                if(hurt){other.mind().felt(EmotionEvent.FRIGHTENED); other.showReaction("surprise","afraid",50);BirthStaging.sweat(other);continue;}
                // A small hop and a greeting for the newcomer, with the same impulse Gesture HOP_SMALL applies to this body.
                double lift=Gesture.smallHopImpulse(other);
                if(lift>0)Motor.velocity(other,other.getDeltaMovement().add(0,lift,0),"birth_welcome");
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
            // Turn the body toward the baby at the ability's turn rate; the clip adds its own head turn on top.
            float yaw=(float)(Mth.atan2(baby.getZ()-o.entity().getZ(),baby.getX()-o.entity().getX())*Mth.RAD_TO_DEG)-90;
            if(age>=40)turnTo(o.entity(),yaw);
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

    /**
     * Asks the motor for one bounded turn toward {@code yaw} (spec 9.4 Face), from a decision tick only. A turn already running
     * is left alone, and a body already inside the action's own arrival angle starts nothing, so a beat that repeats every tick
     * produces one turn instead of one action per tick.
     */
    private void turnTo(YukkuriEntity body,float yaw){
        var motor=body.motor();
        var running=motor.owner();
        if(motor.current() instanceof Face&&running!=null&&beats.ownerId().equals(running.ownerId()))return;
        if(Math.abs(Mth.wrapDegrees(yaw-body.getYRot()))<=Face.FACE_ARRIVED)return;
        motor.run(beats,new Face(yaw));
    }

    void finishLive(){
        var completed=o.brood();
        if(o.cradleEntity()!=null)o.lingerCradle(LiveBirthGeometry.RECOVERY);
        o.clearGestation();liveRecovery=LiveBirthGeometry.RECOVERY;landExit=null;leftNest=false;
        o.recovery(Math.max(o.recovery(),FamilyConfig.RECOVERY.get()));deliveryLast=null;deliveryYaw=Float.NaN;catcherWait=catchWindowBudget=stationWait=nestless=0;deliveryOutcome=0;catchStation=CatchStationState.UNUSED;
        o.resetCatchDuty();
        o.stop();o.entity().getNavigation().stop();o.task(Task.RECOVER);o.waitTicks(0);
        GameplayLog.event(o.entity(),"family","delivery complete brood={} recovery={} newborn={}",
                completed==null?null:completed.id,liveRecovery,newborn);
    }
}
