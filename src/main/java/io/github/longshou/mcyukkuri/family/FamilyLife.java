package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.status.GameplayLog;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.settlement.SettlementSeasons;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.interaction.OperationLife;
import io.github.longshou.mcyukkuri.subspecies.HatRoomProbe;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.List;
import java.util.Random;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.FluidTags;

/** Individual growth and interruptible family goals; durable births live in FamilyData. */
public final class FamilyLife {
    private static final ResourceLocation GROWTH_SPEED=MCYukkuri.id("growth_speed");
    /** Existing stimulant/normal breeding-period duration; a project parameter, not a source-game timer. */
    public static final int BREEDING_PERIOD_TICKS = 1200;
    /** A dependent child normally stays within 1.5 blocks; curiosity or boldness opens the radius gradually, never past six blocks. */
    private static final double FAMILY_FOLLOW_DISTANCE_SQR = 2.25, FAMILY_FOLLOW_DISTANCE = 1.5,
            FAMILY_WANDER_START = 60, FAMILY_WANDER_PER_POINT = .225, FAMILY_WANDER_MAX = 6, FAMILY_SEARCH_RANGE = 16;
    public enum Stage { BABY, JUVENILE, ADULT }
    enum Task { NONE, COURT, RECEIVE_COURT, BROOD, FOLLOW, WAIT_CHILD, SEARCH, MIDWIFE, FEED_CHILD, STAY_HOME, RECOVER }
    private final YukkuriEntity entity;
    private Stage stage=Stage.ADULT;
    private long maturity=GrowthTiming.ADULT_START, age=maturity+1, nourishment=maturity,
            lifespan=GrowthTiming.LIFESPAN;
    private float adultScale=1, scale=1;
    private UUID mother, father, birthId, gestation;
    /** Legacy entity mirror only; migrated server relationships belong to FamilyData.Person. */
    private UUID partner;
    private int conceptions, recovery, wait, taskTicks, spentStemCount;
    private float spentStemVariation=1;
    private boolean initialized, awaitingLanding, juvenileVoice;
    private boolean sterile;
    private int stimulation;
    private int sexDrive;
    private boolean babyTalk;
    /** A hat set down to catch young: where, facing (entity yaw) and how it lies, and who set it. */
    public record HatPose(Vec3 at, float yaw, HatVessel.Mode mode, HatVessel.Role role) {}
    /** Where a catcher stands, and her yaw, to set down, hold or take back a hat at a pose. */
    public record Stand(Vec3 at, float yaw) {}
    /** The mother's land-delivery position after a helper has placed a real catching hat. */
    record DeliveryStation(Vec3 at, float yaw) {}
    /** A newborn pushed past the hat by an overstrained delivery: it will not survive the landing. */
    private boolean doomed;
    private final FamilyFlight flight;
    private final HatBoarding boarding;
    private final BraidRescue rescue;
    private final CradlePickup pickup;
    private UUID cradleHolder;
    private UUID rescuingChild;
    /** Visible pregnancy and the brief post-delivery recovery of the mother's belly. */
    public float liveBelly() { return delivery.liveBelly(); }
    private Task task=Task.NONE;
    private YukkuriEntity target;
    private Vec3 searchAt;
    private final FamilyFeeding feeding;
    private final Courtship courtship;
    private final CatchHat catchHat;
    private final LiveDelivery delivery;
    public FamilyLife(YukkuriEntity entity) {
        this.entity=entity;
        rescue=new BraidRescue(entity);
        boarding=new HatBoarding(entity);
        pickup=new CradlePickup(entity);
        feeding=new FamilyFeeding(this);
        flight=new FamilyFlight(this);
        courtship=new Courtship(this);
        catchHat=new CatchHat(this);
        delivery=new LiveDelivery(this);
    }
    public CradlePickup cradlePickup(){return pickup;}
    public String describe() {
        return "task=" + task + " ticks=" + taskTicks
                + " target=" + (target == null ? "none" : "#" + target.getId())
                + " search=" + (searchAt == null ? "none" : searchAt)
                + " radius=" + Math.sqrt(familyRadiusSqr());
    }
    /** Dedicated passive water-birth/partner-hull readout; generic family detail remains stable. */
    public String waterBirthDetail() { return delivery.waterBirthDetail(); }
    /** The target hat may retain one incoming place only while this exact child's live cross-hat braid transfer is active. */
    public boolean crossingToHat(HatBoatEntity target){
        var controller=pickupController();return controller!=null&&controller.transferringTo(entity,target);
    }
    /** Called by the afloat owner loop so a real family rendezvous cannot be overwritten by routine care or foraging. */
    public boolean tickWaterPartner(){return delivery.tickWaterPartner();}
    public void cradleHolder(UUID id){cradleHolder=id;}
    private CradlePickup pickupController(){
        if(pickup.active())return pickup;
        if(!entity.level().isClientSide&&cradleHolder!=null&&level().getEntity(cradleHolder) instanceof YukkuriEntity parent
                &&parent.family().pickup.active()&&entity.getUUID().equals(parent.family().pickup.childId()))return parent.family().pickup;
        return null;
    }
    public boolean cradleHeld(){var controller=pickupController();return controller!=null&&controller!=pickup&&controller.held();}
    ServerLevel level() { return (ServerLevel)entity.level(); }
    YukkuriEntity entity() { return entity; }
    HatBoarding boarding() { return boarding; }
    BraidRescue rescue() { return rescue; }
    Courtship courtship() { return courtship; }
    boolean hasFlightArc() { return flight.hasArc(); }
    boolean rescuingYoung() { return rescueController()!=null; }
    boolean comfortActive() { return delivery.comfortActive(); }
    boolean hatMoving() { return catchHat.moving(); }
    boolean catchDutyGaveUp() { return catchHat.dutyGaveUp(); }
    void resetCatchDuty() { catchHat.resetDuty(); }
    void lingerCradle(int ticks) { catchHat.linger(ticks); }
    void clearGestation() { gestation=null; }
    void recovery(int value) { recovery=value; }
    UUID arrangedMate() { return courtship.arranged(); }
    UUID legacyPartner() { return partner; }
    int waitTicks() { return wait; }
    void waitTicks(int value) { wait=value; }
    Task task() { return task; }
    void task(Task value) { task=value; }
    YukkuriEntity target() { return target; }
    void target(YukkuriEntity value) { target=value; }
    int taskTicks() { return taskTicks; }
    void taskTicks(int value) { taskTicks=value; }
    public Stage stage() { return stage; }
    public long age() { return age; }
    public long nourishment() { return nourishment; }
    public long lifespan() { return lifespan; }
    public long maturityAge() { return maturity; }
    public float adultScale() { return adultScale; }
    public float scale() { return scale; }
    public UUID mother() { return mother; }
    public UUID father() { return father; }
    public UUID birthId() { return birthId; }
    /** The server ledger owns migrated relationships; the old field is only an import/client mirror. */
    public UUID partner() {
        if(entity.level() instanceof ServerLevel level) {
            var person=FamilyData.get(level).person(entity.getUUID());
            if(person!=null&&person.partnerState()!=FamilyData.PartnerState.UNIMPORTED)return person.partner();
        }
        return partner;
    }
    public int conceptions() { return conceptions; }
    public int recovery() { return recovery; }
    public int spentStemCount() { return spentStemCount; }
    public float spentStemVariation() { return spentStemVariation; }
    public boolean awaitingLanding() { return awaitingLanding; }
    /** Ordinary children need a parent-prepared serving until their juvenile nourishment and age can support chewing. */
    public boolean needsMash() {
        if (adult() || entity.profile().houseBody() || entity.profile().dangerToYukkuri()) return false;
        return stage != Stage.JUVENILE || age < maturity / 4 || nourishment < maturity * 3 / 16;
    }
    public boolean canEatIndependently() { return !awaitingLanding && !needsMash(); }
    /** Late-child gate for a personal nest; stem-born juveniles still start at age and nourishment zero. */
    public boolean readyForOwnNest() { return !awaitingLanding&&stage==Stage.JUVENILE&&age>=maturity/2&&nourishment>=maturity*3/8&&entity.childhood().canLearn(); }
    public ItemStack carrying() { return feeding.carrying(); }
    public int careChewTicks() { return feeding.chewTicks(); }
    /** Client-sync source: the child a land caregiver is currently approaching, or {@code -1}. */
    public int careTargetId() { return task == Task.FEED_CHILD && target != null ? target.getId() : -1; }
    /** Client-sync source: uninterrupted mouth-contact progress, from zero through twenty. */
    public int careContactProgress() { return task == Task.FEED_CHILD ? feeding.offerTicks() : 0; }
    private String courtActivity() { return courtship.courtActivity(); }
    public long courtAnimationStart() { return courtship.courtAnimationStart(); }
    public boolean youngVoice() { return stage!=Stage.ADULT||juvenileVoice||babyTalk; }
    public boolean babyTalk(){return babyTalk;}
    public boolean applyBabyTalk(){if(!adult()||babyTalk)return false;babyTalk=true;entity.setPersistenceRequired();return true;}
    public int sexDrive(){return sexDrive;}
    public boolean inBreedingPeriod(){return adult()&&!sterile()&&!elder()&&sexDrive>0;}
    public boolean canTakeStimulant(){return adult()&&!sterile()&&!elder()&&sexDrive==0;}
    /** One saved server clock feeds both natural periods and the existing stimulant item. */
    public boolean beginBreedingPeriod(){
        if(entity.level().isClientSide||!canTakeStimulant())return false;
        sexDrive=BREEDING_PERIOD_TICKS;entity.setPersistenceRequired();return true;
    }
    public void endBreedingPeriod(){sexDrive=0;}
    public boolean takeStimulant(){return beginBreedingPeriod();}
    public boolean adult() { return stage==Stage.ADULT; }
    public boolean caresFor(YukkuriEntity child) {
        return adult()&&!child.family().adult()&&(Bonds.parentOf(entity, child));
    }
    public boolean hasHungryChild() {
        return !entity.level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(1.5),child->caresFor(child)
                &&entity.life().canRecognize(child)&&child.life().nutrition()<entity.life().nutrition()-entity.species().traits().dote()).isEmpty();
    }
    public boolean hasUrgentlyHungryChild() {
        return !entity.level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(12),
                child->caresFor(child)&&child.life().nutrition()<30&&child.life().canReceiveCareFood()).isEmpty();
    }
    public boolean elder() { return age>=GrowthTiming.ELDER_START; }
    /** The final child interval is internally still {@link Stage#JUVENILE}; it is a separate age qualification. */
    public boolean subadult() { return age>=GrowthTiming.SUBADULT_START&&age<GrowthTiming.ADULT_START; }
    public boolean active() { return pickupController()!=null||task!=Task.NONE||laboring()||delivery.recoveryTicks()>0||catchHat.moving()||catchHat.fetching()||flight.hasArc()
            ||delivery.comfortActive(); }
    /**
     * An everyday family errand (waiting for, following or looking for a child, staying near the nest) gives way to comforting a hurt kin
     * ({@link io.github.longshou.mcyukkuri.social.WoundComfort}); births, feeding, courtship, brooding and recovery do not.
     * Before this the parent's standing "wait for the child" errand lasted as long as the child wandered, and the parent never noticed the wound.
     */
    public boolean yieldsToComfort() {
        return pickupController()==null&&!laboring()&&delivery.recoveryTicks()==0&&!catchHat.moving()&&!catchHat.fetching()&&!flight.hasArc()&&!delivery.comfortActive()
                &&(task==Task.NONE||task==Task.WAIT_CHILD||task==Task.FOLLOW||task==Task.STAY_HOME||task==Task.SEARCH);
    }
    public String activity() {
        boolean aquatic=entity.profile().aquatic();
        if(catchHat.moving())return catchHat.activity(aquatic);
        if(delivery.nuzzling())return Behaviors.NUZZLE;
        if(laboring())return Behaviors.LABOR;
        if(delivery.recoveryTicks()>0)return Behaviors.POSTPARTUM;
        return switch(task) {
            case COURT, RECEIVE_COURT -> courtActivity(); case BROOD -> Behaviors.TEND_BROOD; case FOLLOW -> Behaviors.FOLLOW_PARENT;
            case WAIT_CHILD -> Behaviors.WAIT_CHILD; case SEARCH -> Behaviors.SEARCH_FAMILY;
            case MIDWIFE -> cradleEntity()!=null?Behaviors.aquatic(Behaviors.HAT_HOLD, aquatic):Behaviors.MIDWIFE;
            case FEED_CHILD -> feeding.activity();
            case STAY_HOME -> Behaviors.STAY_HOME; case RECOVER -> Behaviors.POSTPARTUM; default -> awaitingLanding?Behaviors.NEWBORN_FALL:Behaviors.IDLE;
        };
    }
    /** A live delivery is under way (server side). */
    /** Why the current delivery is held up (empty when it is not): the last refusal of the ledger, or a guard of the mother's own. */
    public String birthBlock() { return delivery.birthBlock(); }
    public boolean laboring() { return delivery.laboring(); }
    /** In the delivery itself and not between two births: she cannot leave it to help anyone. */
    public boolean tiedToLabor() { return delivery.tiedToLabor(); }
    /**
     * The rest after a child is born and the strain before the next head appears (the clock of {@link LiveBirthGeometry}).
     * A newborn that has fallen in reach of her braid may be lifted out now; the next push waits until she has done so
     * (see {@link LiveDelivery#advanceLive}). Project design: the lecture only says a missed catch is a drowned child, not that a mother
     * must stand by while it goes under.
     */
    public boolean deliveryGap() { return delivery.deliveryGap(); }
    /** The mate this partner is helping deliver, while acting as midwife. */
    public YukkuriEntity midwifeTarget() { return task==Task.MIDWIFE?target:null; }
    /** An aquatic mother afloat in her own hat boat nests in it: her young are born into the hat (water birth). */
    public HatBoatEntity boatHome() {
        return entity.getVehicle() instanceof HatBoatEntity boat && boat.isAfloat()
                && entity.getUUID().equals(boat.ownerYukkuri()) ? boat : null;
    }
    /** Aquatic live birth belongs in the mother's own afloat hat, never at a land outlet. */
    public boolean waterBirthRequired() { return delivery.waterBirthRequired(); }
    /**
     * Late in an aquatic live pregnancy the normal family goal must yield MOVE so the shore/hat controller can put this
     * parent into her own boat. For a nearby aquatic father this is the same signal to launch his own escort boat.
     */
    public boolean needsWaterBirthBoat() { return delivery.needsWaterBirthBoat(); }
    /** Only Marisa midwife with her hat: a hatted Marisa father close by, else a hatted Marisa mother catches her own young. */
    public static YukkuriEntity catcher(YukkuriEntity mother,FamilyData.Brood brood) { return CatchHat.catcher(mother,brood); }
    /** Where the hat goes to catch (hat_vessel.json, the end pose of the braid clips). A land Marisa presses it upside down
     *  before the outlet, the live exit just inside its opening (a mother on her own sets it where her own clip puts it);
     *  the aquatic Marisa floats her larger hat at the bank's edge before the outlet, its near brim slipped under the bank. */
    public static HatPose catchPose(YukkuriEntity mother,FamilyData.Brood brood,YukkuriEntity catcher,HatVessel.Role role) { return CatchHat.catchPose(mother,brood,catcher,role); }
    /** The catcher's place for a hat at this pose: the inverse of the clip's placement. */
    public static Stand stand(HatPose pose,YukkuriEntity catcher) { return CatchHat.stand(pose,catcher); }
    void face(float yaw){entity.setYRot(yaw);entity.yBodyRot=yaw;entity.yBodyRotO=yaw;entity.setYHeadRot(yaw);entity.yHeadRotO=yaw;}
    /** This Marisa's hat, set down nearby to catch. */
    public HatBoatEntity cradleEntity() { return catchHat.cradleEntity(); }
    /** The hat stays down while the young are coming and a little after, while they wake in it. */
    public boolean keepsCradle() { return catchHat.keepsCradle(); }
    /** A helper's landed hat brings the mother back to the outlet position used when it was set down. */
    DeliveryStation deliveryStation(FamilyData.Brood brood) {
        if(brood==null||entity.profile().aquatic()||delivery.catchStationAbandoned())return null;
        var helper=catcher(entity,brood);
        return helper==null||helper==entity?null:helper.family().catchHat.deliveryStation(entity,brood);
    }
    private DeliveryStation catchRendezvous(FamilyData.Brood brood) {
        if(brood==null||entity.profile().aquatic()||withholdsCushion())return null;
        var helper=catcher(entity,brood);
        return helper==null||helper==entity?null:helper.family().catchHat.rendezvous(entity,brood);
    }
    /** Late delivery and the father's approach take precedence over ordinary household routines. Pure observation. */
    public boolean prioritizesBirth() {
        if(entity.level().isClientSide||!entity.isAlive()||entity.isNoAi()||entity.isPanicking()||entity.isCommandPlaying()
                ||entity.operations().active()||entity.life().hasUrgentNeed()||entity.life().mealTicks()>0||awaitingLanding||needsWaterBirthBoat())return false;
        var b=brood();
        return b!=null&&!b.cancelled&&!b.complete()&&b.kind!=FamilyData.BirthKind.EGG&&b.elapsed>=Math.max(0,b.duration-CatchHat.APPROACH_LEAD)
                ||task==Task.MIDWIFE||catchHat.mateInLabor()!=null;
    }
    void catchStationActivated(FamilyData.Brood brood){delivery.catchStationActivated(brood);}
    /** The helper still holds this exact hat, the mother is at its outlet and they can both see the delivery. */
    boolean deliveryStationReady(FamilyData.Brood brood,DeliveryStation station) {
        var helper=catcher(entity,brood);
        return helper!=null&&helper!=entity&&station!=null&&station.equals(deliveryStation(brood))&&SurfaceApproach.arrived(entity,station.at())
                &&entity.hasLineOfSight(helper)&&helper.hasLineOfSight(entity);
    }
    void abandonDeliveryStation(FamilyData.Brood brood) {
        var helper=catcher(entity,brood);
        if(helper!=null&&helper!=entity)helper.family().catchHat.abandonDeliveryStation(entity,brood);
    }
    /** A Marisa mother with nobody to catch for her sets her own hat before her outlet (true while she is doing so). */
    boolean catchAlone(FamilyData.Brood brood) { return catchHat.catchAlone(brood); }
    /** Called by the set-down hat when it is no longer needed: come back for it and put it on. */
    public void fetchHat(HatBoatEntity hat) { catchHat.fetchHat(hat); }
    public void doom(){doomed=true;}
    public boolean doomed(){return doomed;}
    public boolean flying(){return flight.hasArc()||boarding.active();}
    /** The hat does not collide with its own incoming passenger: a boarding, a braid transfer, or a newborn's launch into it (it must reach seats inside the well). */
    public boolean boardingThrough(HatBoatEntity hat){
        return boarding.traverses(hat)||rescue.transferringThrough(hat)||flight.targets(hat);
    }
    public boolean boardingDismountInPlace(HatBoatEntity hat){return boarding.traverses(hat);}
    public boolean hatLandingActive(){return boarding.landingAshore();}
    public boolean startHatLanding(HatBoatEntity hat,Vec3 dryFeet){return !flight.hasArc()&&!heldByBraid()&&boarding.startLanding(hat,dryFeet);}
    public String hatLandingRefusal(){return boarding.landingRefusal();}
    /** True for a body that can hop between supports on its own; infants are lifted by the parent's braid. */
    public boolean canStepOut(){return boarding.canStep();}
    /** Hop from the hull this body rides onto another hull's reserved seat (a short, reach-tolerant arc). */
    public boolean startHatStep(HatBoatEntity from,HatBoatEntity to,Vec3 seatLocal){return !flight.hasArc()&&!heldByBraid()&&boarding.startStep(from,to,seatLocal,null);}
    /** The captain's hat goes back on (the authored hat_on_self clip) once the token has been returned to her. */
    public boolean playHatOn() { return catchHat.playHatOn(); }
    public boolean hatOnPlaying() { return catchHat.hatOnPlaying(); }
    public boolean selfClimbActive(){return boarding.climbing();}
    public boolean climbGripActive(){return boarding.gripped();}
    public CompoundTag boardingState(){return boarding.state();}
    void rescueTarget(UUID id){rescuingChild=id;}
    public UUID rescueTarget(){return rescuingChild;}
    private BraidRescue rescueController(){
        if(rescue.active())return rescue;
        if(!entity.level().isClientSide&&rescuingChild!=null&&level().getEntity(rescuingChild) instanceof YukkuriEntity child
                &&child.family().rescue.active()&&entity.getUUID().equals(child.family().rescue.parentId()))return child.family().rescue;
        return null;
    }
    public boolean braidRescueActive(){return pickupController()!=null||rescueController()!=null;}
    public boolean heldByBraid(){return cradleHeld()||rescue.active()&&rescue.held()||entity.waterDistress().heldByWaterRescue();}
    public CompoundTag braidRescueState(){var cradle=pickupController();if(cradle!=null)return cradle.state();var controller=rescueController();return controller==null
            ?new CompoundTag():controller.state();}
    public Vec3 braidRescueApproach(HatBoatEntity hat){return hat==null?null:rescue.approach(hat);}
    public void cancelBraidRescue(){boarding.cancel();var cradle=pickupController();if(cradle!=null)cradle.cancel();var controller=rescueController();if(controller!=null)controller.cancel();}
    /** Into a free place in the hat (kept for it now; nearest prefer, in hat units, or the usual one), over the brim on
     *  a parabola. */
    /** How far a newborn may be sent across the water from its mother's outlet into a partner's hat boat alongside. */
    public static final double SHARE_REACH=3.2;
    public void launchInto(HatBoatEntity hat,int tmin,Vec3 prefer){ flight.launchInto(hat,tmin,prefer); }
    static final boolean FLIGHT_DEBUG=false;
    public void launchInto(HatBoatEntity hat,int tmin,Vec3 prefer,double reach){ flight.launchInto(hat,tmin,prefer,reach); }
    public boolean newbornLaunching(){ return flight.newbornLaunching(); }
    /** Pushed too hard: on along the same line past the hat's far brim, down where it comes to the ground or the water. */
    public void launchPast(HatBoatEntity hat){ flight.launchPast(hat); }
    /** Out of the hat over its brim to a spot beside it. */
    public void hop(HatBoatEntity hat,Vec3 from,Vec3 to){ flight.hop(hat,from,to); }
    /** The owner hops from the bank into the stern; her offspring use the same physical flight solver. */
    public void embark(HatBoatEntity hat) { flight.embark(hat); }
    public String hatBoardingDebug(){ return flight.hatBoardingDebug(); }
    /** A physical hop or supported step between hats; leave the source only after reserving the landing. */
    public boolean transferHat(HatBoatEntity from, HatBoatEntity to) { return flight.transferHat(from,to); }
    /** Emergency capacity is contested only by reserveEmergency's real close-brim contact. */
    public boolean transferHat(HatBoatEntity from, HatBoatEntity to, boolean emergency) { return flight.transferHat(from,to,emergency); }
    /** A pupil back from its first voyage steps from its own hat onto the berth kept for it in the parent's hull. */
    public boolean returnToHat(HatBoatEntity from,HatBoatEntity to,boolean emergency){ return flight.returnToHat(from,to,emergency); }
    /** A parent at the rim offers a dipped brim to a swimming young; the child bites, is nudged and slides over from its real water position. */
    public boolean rescueIntoHat(HatBoatEntity hat){ return flight.rescueIntoHat(hat); }
    /** A capable shore child jumps from its actual feet; infants wait for the parent's dipped rim. */
    public boolean boardFromShore(HatBoatEntity hat){ return flight.boardFromShore(hat); }
    /** Alarm recall may sacrifice provisions; ordinary dry boarding only puts them aside. */
    public boolean boardFromShore(HatBoatEntity hat,boolean emergency){ return flight.boardFromShore(hat,emergency); }
    /** A trained young may scramble from real brim contact; an untrained infant is nudged over the dipped rim by its parent. */
    public boolean scrambleIntoHat(HatBoatEntity hat){ return flight.scrambleIntoHat(hat); }
    /** The same real near-brim grip is available to a capable owner of a recovered personal hat. */
    public boolean climbOwnHat(HatBoatEntity hat){ return flight.climbOwnHat(hat); }
    /** Called from travel while a checked boarding trajectory or gravity-driven launch owns movement. */
    public boolean travelFlight(){ return flight.travelFlight(); }
    /** The own parents' hat (set down to catch, or afloat) with room for this young, within reach. */
    private HatBoatEntity nearHat(double reach){ return flight.nearHat(reach); }
    /** A falling stem fruit meets the hat: on the floor it settles there (or bounces to its place), off the brim it
     *  bounces in. */
    private boolean catchFalling(){ return flight.catchFalling(); }
    /** Aquatic mothers may give birth on a riverbank, which serves as a temporary nest. */
    boolean bankNest(){
        if(entity.subspecies().kind()!=SubspeciesLife.Kind.AQUATIC_MARISA)return false;
        for(var p:BlockPos.betweenClosed(entity.blockPosition().offset(-4,-2,-4),entity.blockPosition().offset(4,1,4)))
            if(level().getFluidState(p).is(FluidTags.WATER))return true;
        return false;
    }
    private Vec3 nearestWater(){
        Vec3 best=null;
        for(var p:BlockPos.betweenClosed(entity.blockPosition().offset(-3,-2,-3),entity.blockPosition().offset(3,0,3)))
            if(level().getFluidState(p).is(FluidTags.WATER)&&level().getFluidState(p.above()).isEmpty()){
                var c=Vec3.atCenterOf(p);if(best==null||c.distanceToSqr(entity.position())<best.distanceToSqr(entity.position()))best=c;
            }
        return best;
    }
    public FamilyData.Brood brood() {
        var result=gestation==null||entity.level().isClientSide?null:FamilyData.get(level()).brood(gestation);
        return result!=null&&result.detachedHolder==null?result:null;
    }
    public boolean sterile(){return sterile||!entity.subspecies().canReproduce();}
    public int stimulation(){return stimulation;}
    public boolean stimulate(){if(!adult()||sterile())return false;stimulation=Math.min(1200,stimulation+240);return true;}
    public boolean sterilize(){if(!adult()||sterile()||stimulation==0)return false;sterile=true;stimulation=0;return true;}
    public void detached(){gestation=null;spentStemCount=0;recovery=Math.max(recovery,FamilyConfig.RECOVERY.get());stop();entity.syncFamily();}
    public void tick() {
        if(entity.level().isClientSide)return;
        FamilyData.get(level()).importPartner(entity);
        if(!entity.isAlive()){boarding.cancel();rescue.cancel();pickup.cancel();return;}
        if(!entity.isNoAi())tickAftermathBurden();
        pickup.tick();
        if(pickupController()!=null)return;
        cradleHolder=null;
        boarding.tick();
        rescue.tick();
        if(rescuingChild!=null&&rescueController()==null){
            rescuingChild=null;
            if(entity.getVehicle() instanceof HatBoatEntity home)home.releaseRescueOwner(entity);
        }
        var finished=brood();
        if(finished!=null&&!finished.cancelled&&finished.kind==FamilyData.BirthKind.STEM&&finished.complete())finishStem(finished);
        else if(finished!=null&&!finished.cancelled&&finished.kind==FamilyData.BirthKind.LIVE&&finished.complete())delivery.finishLive();
        if(!entity.subspecies().canReproduce()) {
            // Retire an invalid maternal gestation while retaining born children and ancestry records.
            var pending=brood();
            if(pending!=null&&!pending.cancelled&&!pending.complete())FamilyData.get(level()).cancel(pending,entity,"mother_cannot_reproduce");
            gestation=null;stimulation=sexDrive=0;
        }
        if(entity.isNoAi()) {
            // Physics still lands a NoAI body. Confirm contact without advancing growth, flights or hat care.
            handleLanding(false);
            if(birthId!=null)FamilyData.get(level()).landed(entity);
            return;
        }
        if(!initialized) {
            initialized=true;maturity=GrowthTiming.ADULT_START;lifespan=GrowthTiming.LIFESPAN;
            age=maturity+1;nourishment=maturity;stage=Stage.ADULT;
        }
        if(!adult())entity.subspecies().trackSubadultDeibu();
        age++;boolean becameAdult=advanceStage();
        if (age == GrowthTiming.SUBADULT_START || age == GrowthTiming.ELDER_START)
            GameplayLog.event(entity, "growth", "age boundary age={} stage={} subadult={} elder={} nourishment={} scale={}",
                    age, stage, subadult(), elder(), nourishment, scale);
        if(becameAdult)entity.subspecies().resolveAdultDeibu();
        if(entity.life().nutrition()>=45&&entity.getHealth()>=entity.getMaxHealth()*.6)nourishment++;
        if(!awaitingLanding&&entity.tickCount%GROW_INTERVAL==0&&entity.life().nutrition()>=35)grow(false);
        if(entity.tickCount==1||entity.tickCount%20==0)updateMovement();
        if(recovery>0)recovery--;if(wait>0)wait--;if(stimulation>0)stimulation--;delivery.tickRecovery();
        if(!adult()||sterile()||elder())sexDrive=0;
        if(sexDrive>0){sexDrive--;if(sexDrive%200==0)entity.life().spendNutrition(1);}
        // Natural lifetime is authoritative even while the body is boarded or in a flight arc.
        if(FamilyConfig.AGING.get()&&age>=lifespan) {
            GameplayLog.limited(entity, "growth", "lifespan", 1200, "lifespan reached age={} lifespan={}", age, lifespan);
            entity.body().terminalKill(level().damageSources().generic(),false,false);return;
        }
        if(entity.tickCount%100==0)FamilyData.get(level()).register(entity);
        catchHat.tickLinger();
        catchHat.tickHat();
        if(delivery.bumping())delivery.tickBump();
        if(boarding.active())return;
        if(flight.hasArc())return;
        if(awaitingLanding&&!doomed&&!entity.isPassenger()&&catchFalling())return;
        if(handleLanding(true))return;
        if(birthId!=null)FamilyData.get(level()).landed(entity);
        if(delivery.welcoming())delivery.welcome();
        feeding.settleCareFood();
        if(entity.tickCount%40==0)retryMotherBurrow();
        if(entity.isPanicking()||entity.isCommandPlaying()||(task!=Task.FEED_CHILD&&entity.life().hasUrgentNeed()))stop();
        if(prioritizesBirth()) {
            if(entity.life().isSleeping())entity.life().interrupt();
            if(entity.dailyLife().active())entity.dailyLife().stop();
            if(entity.playLife().active())entity.playLife().stop();
        }
        catchHat.claimMidwife();
        if(delivery.comfortActive())delivery.tickComfort();
        var brood=brood();
        // Labour wakes a sleeping mother (a night pregnancy used to wait until morning, or for ever in a hat).
        if(brood!=null&&!brood.cancelled&&!brood.complete()&&brood.kind!=FamilyData.BirthKind.EGG&&brood.elapsed>=brood.duration-60&&entity.life().isSleeping()){
            if(boatHome()!=null)entity.life().wakeInHat();else entity.life().interrupt();
        }
        if(brood!=null&&!brood.cancelled&&!brood.complete()&&FLIGHT_DEBUG&&brood.elapsed>=brood.duration&&entity.tickCount%200==0&&boatHome()!=null)
            MCYukkuri.LOGGER.info("[birth-debug] hold={} {}",birthBlock(),HatRoomProbe.explain(boatHome()));
        // An expecting mother shows it at a glance: a stem on her head or a swelling belly, and a small heart now and then.
        if(brood!=null&&!brood.cancelled&&!brood.complete()&&brood.incidentSource==null&&brood.kind!=FamilyData.BirthKind.EGG&&entity.tickCount%60==0)
            level().sendParticles(ParticleTypes.HEART,entity.getX(),entity.getY()+entity.getBbHeight()+.15,entity.getZ(),1,.12,.04,.12,0);
        if(brood!=null&&!brood.cancelled&&!brood.complete()&&brood.kind==FamilyData.BirthKind.STEM) advanceStem(brood);
        else if(brood!=null&&!brood.cancelled&&!brood.complete()&&brood.kind==FamilyData.BirthKind.LIVE)delivery.advanceLive(brood);
        else if(gestation!=null&&(brood==null||brood.cancelled||brood.complete()&&brood.kind!=FamilyData.BirthKind.STEM))gestation=null;
    }
    /** Pure contact confirmation also runs with AI disabled; optional hat seeking remains an AI behavior. */
    private boolean handleLanding(boolean hopIntoHat) {
        if(!awaitingLanding||flight.hasArc()||!(entity.onGround()||entity.isInWaterOrBubble()||entity.getVehicle() instanceof HatBoatEntity))return false;
        awaitingLanding=false;
        if(doomed){
            // Missed the hat. In water the newborn is simply in the river: the ordinary water rules apply (it cannot swim,
            // drowns by immersion unless a parent's braid brings it out). On the ground it falls hard: a real injury.
            doomed=false;
            if(entity.isInWaterOrBubble()){
                BirthStaging.thud(entity);entity.waterDistress().plunge(null,null);
                if(mother!=null&&level().getEntity(mother) instanceof YukkuriEntity parent)BirthStaging.caption(parent,"漏接落水：新生儿掉进水里，无法自救，须亲油及时救起，否则溺亡");
                return true;
            }
            delivery.bump();return false;
        }
        if(delivery.bumpPending()){delivery.clearPendingBump();delivery.bump();return false;}
        if(hopIntoHat&&!entity.isPassenger()){
            var hat=nearHat(2.5);
            if(hat!=null)launchInto(hat,HatVessel.HOP_TMIN,hat.toLocal(entity.position()));
        }
        // The greeting waits until first_wake has opened the eyes and reached its happy face (24 ticks), not the landing tick.
        entity.showReactionDelayed("happy","newborn",100,24);return false;
    }
    /** Called once when a real sleep completes. Neither observation nor time-of-day commands grow the entity. */
    public boolean woke() {
        if(entity.level().isClientSide||entity.life().nutrition()<35)return false;
        return grow(true);
    }
    /** Stage changes only follow the fixed loaded-time clock. Nourishment only controls how far that stage can grow. */
    private boolean grow(boolean slept) {
        // The size standard (GrowthSize): one curve over nourishment, capped by the time stage reached, never shrinking.
        float desired=Math.max(scale,GrowthSize.scale(stage,nourishment,maturity,adultScale,entity.profile().houseBody()));
        if(Math.abs(desired-scale)<=.001f)return false;
        var box=LiveBirthGeometry.bodyDimensions(entity,desired*entity.subspecies().formScale(),liveBelly()).scale(entity.getScale()).makeBoundingBox(entity.position());
        if(!level().noCollision(entity,box))return false;
        scale=desired;
        entity.syncFamily();if(slept||stage!=Stage.BABY)entity.showReaction("happy","grown",100);return true;
    }
    private static Stage stageForAge(long value) {
        return value<GrowthTiming.BABY_END?Stage.BABY:value<GrowthTiming.ADULT_START?Stage.JUVENILE:Stage.ADULT;
    }
    /** Advance age qualification even when hunger or collision prevents a visible size increase. */
    private boolean advanceStage() {
        Stage next=stageForAge(age);if(next==stage)return false;
        Stage previous = stage;
        stage=next;entity.syncFamily();
        GameplayLog.event(entity, "growth", "stage={} -> {} age={} nourishment={} scale={}", previous, stage, age, nourishment, scale);
        if(next!=Stage.BABY)entity.showReaction("happy","grown",100);
        return next==Stage.ADULT;
    }
    /** Scenario and natural-population setup use a real age, rather than writing a stage label by itself. */
    public void initializeGrowthAge(long value) {
        initialized=true;maturity=GrowthTiming.ADULT_START;lifespan=GrowthTiming.LIFESPAN;
        age=Math.clamp(value,0,GrowthTiming.LIFESPAN-1);stage=stageForAge(age);
        scale=GrowthSize.scale(stage,nourishment,maturity,adultScale,entity.profile().houseBody());
        entity.syncFamily();
    }
    private static final int GROW_INTERVAL=100;
    private void updateMovement() {
        var speed=entity.getAttribute(Attributes.MOVEMENT_SPEED);if(speed==null)return;
        double factor=GrowthSize.movement(stage,scale,entity.profile().houseBody());
        if(factor>=1)speed.removeModifier(GROWTH_SPEED);
        else speed.addOrUpdateTransientModifier(new AttributeModifier(GROWTH_SPEED,factor-1,AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }
    /** Observable growth: 0..1 from birth to a full adult, limited by whichever of age and nourishment is further behind. */
    public float growthProgress() {
        long m=Math.max(1,maturity);
        return Math.clamp(Math.min(age/(float)(m+1),nourishment/(m*.75f)),0f,1f);
    }
    /** Loaded ticks to the next fixed stage; hunger and injury do not delay the age transition. */
    public long stageRemainingTicks() {
        return switch(stage){
            case BABY -> Math.max(0,GrowthTiming.BABY_END-age);
            case JUVENILE -> Math.max(0,GrowthTiming.ADULT_START-age);
            case ADULT -> 0;
        };
    }
    public void newborn(FamilyData.Child child,FamilyData.Brood brood) {
        initialized=true;mother=brood.mother;father=brood.father;birthId=brood.id;adultScale=child.adultScale;lifespan=GrowthTiming.LIFESPAN;
        maturity=GrowthTiming.ADULT_START;
        age=brood.kind==FamilyData.BirthKind.STEM?GrowthTiming.BABY_END:0;nourishment=0;stage=stageForAge(age);
        scale=GrowthSize.birth(brood.kind);
        awaitingLanding=brood.kind!=FamilyData.BirthKind.EGG;
        juvenileVoice=new Random(child.id.getLeastSignificantBits()).nextInt(10)==0;
        entity.childhood().newborn();
        entity.syncFamily();
    }
    public void conceived(UUID brood,UUID other) {
        boolean resolvedHeat=inBreedingPeriod();
        endBreedingPeriod();gestation=brood;conceptions++;recovery=FamilyConfig.RECOVERY.get();delivery.resetForConception();
        // A conception inside a still-running period resolves it: the relief beat replaces the plain expecting reaction.
        if(resolvedHeat)entity.agitation().markRelieved();
        entity.setPersistenceRequired(); }
    /** Preconditions of a checked aftermath reservation. Existing pregnancies and partners are never replaced. */
    public boolean canReceiveAftermath() {
        return !entity.level().isClientSide && entity.isAlive() && !entity.isNoAi() && adult() && !sterile() && !elder()
                && !entity.body().dying() && entity.body().coreIntact()
                && !entity.profile().houseBody() && !entity.profile().dangerToYukkuri()
                && gestation==null && recovery==0 && !awaitingLanding && delivery.recoveryTicks()==0 && !birthDuty();
    }
    /** Called only after a checked STEM reservation; the source gains no partner or care role. */
    void receiveAftermath(UUID brood) {
        endBreedingPeriod();gestation=brood;conceptions++;recovery=FamilyConfig.RECOVERY.get();
        spentStemCount=0;delivery.resetForConception();
        stop();entity.setPersistenceRequired();entity.syncFamily();
    }
    private void tickAftermathBurden() {
        var pregnancy=brood();
        if(pregnancy==null||pregnancy.incidentSource==null||pregnancy.cancelled||pregnancy.complete()
                ||pregnancy.kind!=FamilyData.BirthKind.STEM)return;
        // Persistent loaded-time clock: even arrested growth below 30 nutrition still taxes the mother.
        // Hunger at zero uses YukkuriLife's ordinary starvation damage; there is no instant-death branch.
        if(++pregnancy.burdenTicks>=200) {
            pregnancy.burdenTicks=0;
            float cost=1f+.2f*pregnancy.unborn();
            entity.life().spendNutrition(Math.min(cost,entity.life().nutrition()));
            entity.life().applyEnvironment(0,0,entity.life().nutrition()<30?2:1);
        }
        FamilyData.get(level()).setDirty();
    }
    /** Manufactured filling has no identified father and does not create or replace a partner. */
    public void assisted(UUID brood) {
        if(gestation!=null)return;
        endBreedingPeriod();gestation=brood;conceptions++;recovery=FamilyConfig.RECOVERY.get();spentStemCount=0;delivery.resetForAssistance();
        stop();entity.setPersistenceRequired();entity.syncFamily();
    }
    public boolean readyForAssistance() {
        return !entity.level().isClientSide&&!entity.profile().houseBody()&&!entity.profile().dangerToYukkuri()
                &&!entity.isNoAi()&&gestation==null&&adult()&&!sterile()&&!elder()&&recovery==0
                &&entity.isAlive()&&!entity.isPanicking()&&!entity.isCommandPlaying()&&!entity.predator().active()
                &&(!entity.operations().active()||entity.operations().pose()==OperationLife.Pose.ASSIST)
                &&!entity.dailyLife().active()
                &&!entity.isInWaterOrBubble()&&!entity.isOnFire()&&supported()
                &&!entity.life().hasUrgentNeed()&&!entity.life().isSleeping()&&entity.life().mealTicks()==0
                &&entity.life().nutrition()>=65&&entity.life().energy()>=50&&entity.life().hygiene()>=40
                &&entity.getHealth()>=entity.getMaxHealth()*.8&&entity.body().condition().readyForReproduction()
                &&nestStand()!=null;
    }
    public void partnerConceived(UUID other) {
        boolean resolvedHeat=inBreedingPeriod();
        endBreedingPeriod();recovery=FamilyConfig.RECOVERY.get();
        if(resolvedHeat)entity.agitation().markRelieved();
        entity.setPersistenceRequired(); }
    /** An already-born juvenile in a naturally discovered household, not a simulated conception. */
    public void generatedChild(YukkuriEntity first, YukkuriEntity second) {
        initialized = true; mother = first.getUUID(); father = second == null ? null : second.getUUID();
        maturity = GrowthTiming.ADULT_START; lifespan = GrowthTiming.LIFESPAN;
        age = GrowthTiming.BABY_END+(GrowthTiming.SUBADULT_START-GrowthTiming.BABY_END)/2; nourishment = age;
        stage = Stage.JUVENILE; adultScale = 1; scale = GrowthSize.scale(stage, nourishment, maturity, adultScale, entity.profile().houseBody());
        entity.subspecies().initializeBirth(SubspeciesLife.birthFlags(entity.getUUID(),entity.getVariant(),first.subspecies().kind(),second==null?null:second.subspecies().kind()));
        entity.life().personality().applyBirthProfile(YukkuriPersonality.inheritBirth(entity.getUUID(),first.life().personality(),
                second==null?null:second.life().personality(),entity.subspecies().congenital()!=SubspeciesLife.Congenital.NORMAL,entity.subspecies().kind()));
        entity.syncFamily();
        var relations = YukkuriWorldData.get(level());
        for (YukkuriEntity parent : second == null ? List.of(first) : List.of(first, second)) {
            relations.relate(entity.getUUID(), parent.getUUID(), 30);
            relations.relate(parent.getUUID(), entity.getUUID(), 30);
        }
    }
    public boolean introduce(YukkuriEntity other) { return courtship.introduce(other); }
    public boolean ready() { return courtship.ready(); }
    public boolean ready(boolean needHome) { return courtship.ready(needHome); }
    public void arrange(UUID other){ courtship.arrange(other); }
    /** Shop service check: can the two mate if brought together, ignoring distance, homes, recognition and affinity. */
    public static boolean arrangedMatch(YukkuriEntity a,YukkuriEntity b) { return Courtship.arrangedMatch(a,b); }
    /** Long-lived compatibility for breeding permits: species rules and fertility, not momentary condition. */
    public boolean compatibleWith(YukkuriEntity other) { return courtship.compatibleWith(other); }
    /** Rolled once per courtship attempt for owned pets; the unauthorized partner is remembered so that conception can count it. */
    private boolean allowCourt(YukkuriEntity other) { return courtship.allowCourt(other); }
    /** A water voyager who has landed with her retained hat may use this dry stand for courtship, without claiming a land home. */
    boolean shoreCourtStand() {
        return entity.profile().aquatic()&&entity.onGround()&&!entity.isPassenger()&&!entity.isInWaterOrBubble()
                &&entity.life().dryStand(entity.blockPosition());
    }
    /** Read-only afloat check: it never chooses a mate, rolls pet authorization, starts a courtship or creates a brood. */
    public boolean seeksShoreCourtshipWith(YukkuriEntity other) {
        return task==Task.NONE&&!entity.profile().houseBody()&&ready()
                &&(FamilyConfig.NATURAL_PAIRING.get()||partner()!=null||sexDrive>0)&&courtship.canReproduceWith(other);
    }
    /** Aquatic couples conceive on a dry shore before the mother later launches for her water birth. */
    private boolean reproductionOnShore(YukkuriEntity other) {
        if(other==null||!entity.profile().aquatic()&&!other.profile().aquatic())return true;
        return entity.onGround()&&other.onGround()&&!entity.isPassenger()&&!other.isPassenger()
                &&!entity.isInWaterOrBubble()&&!other.isInWaterOrBubble()
                &&entity.life().dryStand(entity.blockPosition())&&other.life().dryStand(other.blockPosition());
    }
    public boolean canReproduceWith(YukkuriEntity other) { return reproductionOnShore(other)&&courtship.canReproduceWith(other); }
    private void advanceStem(FamilyData.Brood brood) {
        if(entity.operations().active())return;
        if(entity.isPanicking()||entity.isOnFire()||entity.isInWaterOrBubble()||entity.isCommandPlaying()||entity.life().nutrition()<30||entity.body().condition().blocksBreedingApproach())return;
        var boat=boatHome();
        if(!delivery.placeForTerm(brood,boat))return;
        if(entity.life().mealTicks()>0||entity.life().isSleeping())return;
        if(boat==null&&brood.elapsed>=brood.duration-200&&catchAlone(brood))return;
        if(boat==null&&brood.elapsed>=brood.duration-200&&delivery.waitForCatcher(brood))return;
        if(catchHat.moving())return;
        if(brood.elapsed<brood.duration+40)brood.elapsed++;
        if(brood.elapsed%200==0)entity.life().spendNutrition(1);
        // The first tick of actual growth is also the first visible sprout: the victim realizes it (native seisian wording).
        if(brood.elapsed==1&&brood.incidentSource!=null)entity.showReaction("confused","aftermath_realize",160);
        FamilyData.get(level()).setDirty();
        for(int i=0;i<brood.children.size();i++) {
            if(brood.children.get(i).born||brood.elapsed<brood.duration+i*8)continue;
            Vec3 local=FamilyGeometry.childBase(brood.children.size(),i,1,entity.growthScale(),brood.variation,entity.getVariant(),brood.children.get(i).variant);
            Vec3 world=FamilyGeometry.toWorld(entity,local);
            // A blocked attachment delays birth; it does not spawn through the ceiling or into a wall.
            var child=FamilyData.get(level()).birth(brood,i,level(),world);
            if(child!=null) {
                if(boat==null&&!child.family().doomed()&&!child.family().flying())child.setDeltaMovement(0,-.015,0);
                if(brood.incidentSource==null)entity.showReaction("happy","birth",100);
                else entity.showReaction("teary","",100);
            }
        }
        if(brood.complete())finishStem(brood);
    }
    private void finishStem(FamilyData.Brood brood) {
        var drops=ShedStemDrops.get(level());drops.queue(entity,brood);
        recovery=Math.max(recovery,FamilyConfig.RECOVERY.get());
        if(!drops.resolve(level(),brood.id))return;
        if(cradleEntity()!=null)catchHat.linger(120);
        spentStemCount=0;
        gestation=null;stop();entity.syncFamily();
        GameplayLog.event(entity,"family","stem delivery complete brood={} children={} recovery={}",brood.id,brood.children.size(),recovery);
    }
    public FamilyData.BirthKind naturalBirthKind(YukkuriEntity other){
        var forced=entity.profile().birth();
        if(forced!=null)return forced;
        long seed=entity.getUUID().getMostSignificantBits()^other.getUUID().getLeastSignificantBits()^conceptions^0x4c495645L;
        return new Random(seed).nextDouble()<FamilyConfig.LIVE_BIRTH_CHANCE.get()?FamilyData.BirthKind.LIVE:FamilyData.BirthKind.STEM;
    }
    public boolean readyForDelivery() { return delivery.readyForDelivery(); }
    /** Rehearse a real pregnancy through the normal server lifecycle, with its original child identities. */
    public boolean prepareBirthScene(FamilyData.Brood pregnancy,String outcome) { return delivery.prepareBirthScene(pregnancy,outcome); }
    /** Overstrain, a demonstrated miss, or the natural 12%: the newborn goes past the hat (into the water afloat, onto the ground ashore). */
    public boolean missesDelivery(boolean naturalMiss) { return delivery.missesDelivery(naturalMiss); }
    /** A scene outcome is in force for this delivery (caught, overstrain or missed). */
    public boolean sceneRequested() { return delivery.sceneRequested(); }
    /** The demonstrated miss: no hat is set down for this delivery. */
    public boolean withholdsCushion() { return delivery.withholdsCushion(); }
    /** A Marisa was due to catch this brood and no hat is down at the moment of birth: a miss, not a gentle drop. */
    public boolean catchExpected(FamilyData.Brood brood) { return delivery.catchExpected(brood); }
    /** This newborn came out onto bare ground in a demonstrated miss: it will hit the floor, be hurt and cry. */
    public void bumpOnLanding() { delivery.bumpOnLanding(); }
    public boolean bumped() { return delivery.bumped(); }
    /** Birth duties that come before first-stool licking and mash feeding: the hat is still down or being taken back,
     *  a hurt newborn is being comforted, or the mother is still recovering. */
    public boolean birthDuty(){return keepsCradle()||catchHat.moving()||catchHat.fetching()||delivery.comfortActive()||delivery.recoveryTicks()>0;}
    boolean supported(){
        if(boatHome()!=null)return true;
        // A zero-displacement physics step can clear onGround for one tick while the sole still touches a block.
        return entity.onGround()||Math.abs(entity.getDeltaMovement().y)<.09
                &&entity.level().getBlockCollisions(entity,entity.getBoundingBox().move(0,-.025,0)).iterator().hasNext();
    }
    /**
     * The nest this family member brings its young to (stand position beside it), or null. Interface point for the household/nest model
     * ("family big nest", wild/home agent): swap the body of this method, not its callers.
     */
    public BlockPos nestStand(){return entity.life().homeStand();}
    /** Where a new brood is anchored for the population budget: her nest, else the boat she floats in, else where she stands. */
    public BlockPos birthAnchor(){var nest=nestStand();var boat=boatHome();return nest!=null?nest:boat!=null?boat.blockPosition():entity.blockPosition();}
    /** Guards a parent must pass to take a duty at the birth: the same ones the family goal applies. */
    boolean dutyReady() { return catchHat.dutyReady(); }
    /** A parent hurries to a newborn that fell hard, nuzzles it and licks it (project original; nuzzling is Wiki E06). */
    public void comfort(YukkuriEntity baby) { delivery.comfort(baby); }
    public boolean canUse() {
        if(pickupController()!=null)return false;
        if(entity.dailyLife().active())return false;
        if(entity.isPanicking()||entity.isCommandPlaying()||entity.life().hasUrgentNeed()||entity.life().mealTicks()>0||awaitingLanding)return false;
        // Do not keep the shared MOVE flag while the aquatic controller walks a late parent to the bank and launches the
        // real hat boat. A failed shore search simply retries through that controller; this goal remains idle meanwhile.
        if(needsWaterBirthBoat())return false;
        if(task!=Task.NONE)return true;
        if(delivery.recoveryTicks()>0){task=Task.RECOVER;taskTicks=0;return true;}
        if(laboring()){task=Task.BROOD;taskTicks=0;return true;}
        if(wait>0)return false;wait=40;
        var pregnancy=brood();
        if(pregnancy!=null&&!pregnancy.cancelled&&!pregnancy.complete()&&pregnancy.kind!=FamilyData.BirthKind.EGG) { task=Task.BROOD;taskTicks=0;return true; }
        if(!adult()) {
            if(!entity.childhood().shouldStayWithParents())return false;
            var home=earlyHomeStand();
            if(home!=null) {
                if(entity.distanceToSqr(Vec3.atBottomCenterOf(home))>4) { task=Task.STAY_HOME;target=null;taskTicks=0;return true; }
                return false;
            }
            target=closestParent();
            if(target!=null&&entity.distanceToSqr(target)>familyRadiusSqr()) { task=Task.FOLLOW;taskTicks=0;return true; }
            if(target==null) {
                var data=FamilyData.get(level());var known=data.person(mother);
                if(known==null||known.status==FamilyData.Status.DEAD)known=data.person(father);
                if(known!=null&&known.status==FamilyData.Status.ALIVE&&known.position!=null&&known.position.dimension().equals(level().dimension())
                        &&level().hasChunkAt(known.position.pos())&&entity.distanceToSqr(Vec3.atBottomCenterOf(known.position.pos()))>4
                        &&entity.distanceToSqr(Vec3.atBottomCenterOf(known.position.pos()))<256) {
                    searchAt=Vec3.atBottomCenterOf(known.position.pos());task=Task.SEARCH;taskTicks=0;entity.mind().felt(EmotionEvent.SEPARATED); entity.showReaction("surprise",
                            "lost_parent",100);return true;
                }
            }
            return false;
        }
        catchHat.claimMidwife();
        if(task==Task.MIDWIFE)return true;
        // Finish collecting and depositing the real load before routine care can send the parent home again.
        if(entity.forageTrip()!=null&&entity.forageTrip().outing())return false;
        if(feeding.startCare())return true;
        if(ordinaryLandFree(entity)) {
            var child=closestSeparatedDependentChild();
            if(child!=null) {
                task=Task.SEARCH;target=child;searchAt=null;taskTicks=0;entity.showReaction("happy","wait_family",80);return true;
            }
        } else if(!entity.profile().landSettler()) {
            var child=level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(10),other->!other.family().adult()
                    &&other.childhood().shouldStayWithParents()&&Bonds.parentOf(entity,other)&&entity.life().canRecognize(other))
                    .stream().filter(other->entity.distanceToSqr(other)>6.25&&entity.hasLineOfSight(other)).min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
            if(child!=null) { task=Task.WAIT_CHILD;target=child;taskTicks=0;entity.showReaction("happy","wait_family",80);return true; }
        }
        if(!entity.profile().houseBody()&&ready()&&(FamilyConfig.NATURAL_PAIRING.get()||partner()!=null||sexDrive>0)) {
            var data=FamilyData.get(level());
            if(data.busyParent(entity.getUUID()))return false;
            target=level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(3),other->
                    other.family().task()==Task.COURT&&other.family().target()==entity&&canReproduceWith(other)).stream().findFirst().orElse(null);
            if(target!=null) { task=Task.RECEIVE_COURT;courtship.resetAttempt();taskTicks=0;entity.showReaction("happy","courtship",100);return true; }
            target=level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(3),other->
                    SettlementSeasons.mayCourt(entity)&&entity.getUUID().compareTo(other.getUUID())<0
                            &&other.family().task()==Task.NONE&&!data.busyParent(other.getUUID())&&canReproduceWith(other))
                    .stream().findFirst().orElse(null);
            if(target!=null&&!allowCourt(target)){target=null;return false;}
            if(target!=null) { task=Task.COURT;courtship.resetAttempt();taskTicks=0;entity.showReaction("happy","courtship",100);return true; }
        }
        return false;
    }
    private YukkuriEntity closestParent() {
        return level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(12),other->other.isAlive()
                &&(Bonds.parentOf(other, entity))&&entity.life().canRecognize(other))
                .stream().filter(other->entity.life().reachable(other.blockPosition(),false)).min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
    }
    /** The current child's own confidence sets how much room a parent gives it before searching. */
    private double familyRadiusSqr() {
        if(!ordinaryLandFree(entity))return FAMILY_FOLLOW_DISTANCE_SQR;
        int wandering=entity.life().personality().trait(Trait.BOLDNESS);
        double radius=FAMILY_FOLLOW_DISTANCE+Math.max(0,wandering-FAMILY_WANDER_START)*FAMILY_WANDER_PER_POINT;
        radius=Math.min(FAMILY_WANDER_MAX,radius);
        return radius*radius;
    }
    /** The new mutual search is only for ordinary land families that can all move freely; independent young are never recalled. */
    private YukkuriEntity closestSeparatedDependentChild() {
        return level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(FAMILY_SEARCH_RANGE),other->other.isAlive()
                &&ordinaryLandFree(other)&&caresFor(other)&&other.childhood().shouldStayWithParents()&&entity.life().canRecognize(other)
                &&!safeInSharedHome(other)
                &&(entity.distanceToSqr(other)>other.family().familyRadiusSqr()||!entity.hasLineOfSight(other)))
                .stream().min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
    }
    /** Water, boarding, delivery and braid work retain their dedicated family paths instead of being taken over by land-child search. */
    private static boolean ordinaryLandFree(YukkuriEntity member) {
        var family=member.family();
        return member.isAlive()&&!member.isNoAi()&&!member.profile().aquatic()&&!member.profile().houseBody()&&!member.profile().dangerToYukkuri()
                &&!member.isInWaterOrBubble()&&!member.isPassenger()&&!member.isCommandPlaying()&&!member.operations().active()
                &&!family.awaitingLanding()&&!family.laboring()&&!family.birthDuty()&&!family.braidRescueActive()&&!family.heldByBraid();
    }
    /** Early children wait by their real shared shelter instead of continually chasing a foraging parent. */
    private BlockPos earlyHomeStand() {
        if(readyForOwnNest()&&!parentForaging(mother)&&!parentForaging(father))return null;
        var home=entity.habitat().shelter();
        return home!=null&&home.usable(entity)?home.bed(entity.getUUID()):null;
    }
    private boolean parentForaging(UUID id) {
        return id!=null&&level().getEntity(id) instanceof YukkuriEntity parent&&parent.isAlive()
                &&parent.forageTrip()!=null&&parent.forageTrip().outing()&&entity.habitat().shelter()!=null
                &&entity.habitat().shelter()==parent.habitat().shelter();
    }
    private boolean safeInSharedHome(YukkuriEntity child) {
        var home=entity.habitat().shelter();
        return home!=null&&home==child.habitat().shelter()&&home.usable(child)&&home.contains(child.blockPosition())
                &&!child.isPanicking()&&!child.isInWaterOrBubble()&&!child.isOnFire();
    }
    /** Idle roaming must not immediately undo the child's decision to wait in the family shelter. */
    public boolean allowsIdleWalk(Vec3 destination) {
        if(!entity.childhood().shouldStayWithParents()||earlyHomeStand()==null)return true;
        var home=entity.habitat().shelter();
        return home!=null&&home.contains(BlockPos.containing(destination));
    }
    /** A failed conception-time path does not move either parent; retry only while this father's brood is pending. */
    private void retryMotherBurrow() {
        UUID partner=partner();
        if(partner==null||!(level().getEntity(partner) instanceof YukkuriEntity mother)||mother==entity)return;
        var brood=mother.family().brood();
        if(brood==null||brood.cancelled||brood.complete()||!entity.getUUID().equals(brood.father))return;
        var home=mother.habitat().shelter();
        if(FamilyData.ordinaryWildBurrow(mother,entity,home)&&!home.owns(entity.getUUID()))entity.habitat().choose(home);
    }
    public boolean canContinue() {
        if(pickupController()!=null)return false;
        if(needsWaterBirthBoat())return false;
        if((task==Task.COURT||task==Task.RECEIVE_COURT)&&!reproductionOnShore(target))return false;
        if(task==Task.NONE||entity.isPanicking()||entity.life().mealTicks()>0||entity.isCommandPlaying())return false;
        if(task==Task.FEED_CHILD)return feeding.canContinue();
        if(entity.life().hasUrgentNeed())return false;
        if(task==Task.RECOVER)return delivery.recoveryTicks()>0;
        // Let the duty execute its completion/cancellation cleanup once when the brood disappears.
        if(task==Task.MIDWIFE)return target!=null&&target.level()==entity.level();
        if(task==Task.STAY_HOME) {
            var home=earlyHomeStand();return home!=null&&entity.distanceToSqr(Vec3.atBottomCenterOf(home))>4;
        }
        if(task==Task.FOLLOW&&earlyHomeStand()!=null)return false;
        if(task==Task.SEARCH&&target!=null)return ordinaryLandFree(entity)&&ordinaryLandFree(target)&&caresFor(target)
                &&!safeInSharedHome(target)
                &&target.childhood().shouldStayWithParents()&&entity.life().canRecognize(target)&&taskTicks<240&&target.level()==entity.level()
                &&entity.distanceToSqr(target)<FAMILY_SEARCH_RANGE*FAMILY_SEARCH_RANGE;
        return (taskTicks<240||task==Task.BROOD&&prioritizesBirth())
                &&(task==Task.BROOD||task==Task.SEARCH||target!=null&&target.isAlive()&&target.level()==entity.level()&&entity.distanceToSqr(target)<256);
    }
    public void tickGoal() {
        if(!canContinue()){stop();return;}taskTicks++;
        switch(task) {
            case RECOVER -> { entity.getNavigation().stop();delivery.recoveryBeats(); }
            case BROOD -> {
                // The aquatic route owns the walk to water and the authored hat-off/boarding sequence. It may fail and
                // retry without leaving this family goal latched in BROOD on dry land.
                if(needsWaterBirthBoat()){stop();return;}
                var brood=brood();var station=brood!=null&&boatHome()==null?deliveryStation(brood):null;
                if(station!=null){approachDeliveryStation(brood,station);return;}
                var meeting=catchRendezvous(brood);
                if(meeting!=null) {
                    if(SurfaceApproach.walk(entity,meeting.at(),1.05))face(meeting.yaw());
                    return;
                }
                // Hold a supported position once the father is near, so his site search does not chase a moving outlet.
                var helper=brood==null?null:catcher(entity,brood);
                if(prioritizesBirth()&&helper!=null&&helper!=entity&&helper.family().midwifeTarget()==entity
                        &&helper.distanceToSqr(entity)<=64&&supported()){SurfaceApproach.halt(entity);return;}
                if(boatHome()!=null||laboring()){entity.getNavigation().stop();return;}
                BlockPos home=nestStand();
                if(bankNest()&&(home==null||entity.distanceToSqr(Vec3.atBottomCenterOf(home))>4)){
                    // Ashore at a bank she gives birth where she landed, facing the river: her hat floats there to catch.
                    entity.getNavigation().stop();var water=nearestWater();
                    if(water!=null&&!catchHat.moving()&&!laboring())face((float)(Mth.atan2(water.z-entity.getZ(),water.x-entity.getX())*Mth.RAD_TO_DEG)-90);
                    return;
                }
                if(home==null){stop();return;}
                if(entity.distanceToSqr(Vec3.atBottomCenterOf(home))>.36) { if(taskTicks%10==1)entity.life().navigate(home,.9,false); }
                else { entity.getNavigation().stop();entity.getLookControl().setLookAt(entity.getX(),entity.getEyeY(),entity.getZ()-2,10,10); }
            }
            case COURT -> courtship.tickCourt();
            case RECEIVE_COURT -> courtship.tickReceiver();
            case FOLLOW -> {
                if(!entity.life().canRecognize(target)||entity.distanceToSqr(target)<familyRadiusSqr()){stop();return;}
                var bank=target.getVehicle() instanceof HatBoatEntity && target.aquaticLife().awaiting(entity)?target.aquaticLife().boardingBank():null;
                entity.getLookControl().setLookAt(target,20,20);
                if(taskTicks%10==1&&!entity.life().navigate(bank==null?target.blockPosition():bank,1.15,false))stop();
            }
            case STAY_HOME -> {
                var home=earlyHomeStand();
                if(home==null){stop();return;}
                if(entity.distanceToSqr(Vec3.atBottomCenterOf(home))>4) {
                    if(taskTicks%10==1&&!entity.life().navigate(home,1.0,false))stop();
                } else entity.getNavigation().stop();
            }
            case FEED_CHILD -> feeding.tickCare();
            case WAIT_CHILD -> {
                entity.getNavigation().stop();entity.getLookControl().setLookAt(target,20,20);
                if(entity.distanceToSqr(target)<4||!entity.life().canRecognize(target))stop();
            }
            case MIDWIFE -> catchHat.tickMidwife();
            case SEARCH -> {
                if(target!=null) {
                    if(!ordinaryLandFree(entity)||!ordinaryLandFree(target)||!entity.life().canRecognize(target)
                            ||entity.distanceToSqr(target)<=target.family().familyRadiusSqr()&&entity.hasLineOfSight(target)){stop();return;}
                    if(taskTicks%10==1&&!entity.life().navigate(target.blockPosition(),1.1,false))stop();
                } else {
                    if(searchAt==null||entity.distanceToSqr(searchAt)<2){stop();return;}
                    if(taskTicks%20==1&&!entity.life().navigate(BlockPos.containing(searchAt),.9,false))stop();
                }
            }
            default -> { }
        }
    }
    /** Walk back to the helper's actual landed cradle; an unreachable or obstructed station falls back to the normal uncushioned path. */
    private boolean approachDeliveryStation(FamilyData.Brood brood,DeliveryStation station) {
        if(!SurfaceApproach.supported(entity,station.at())){
            abandonDeliveryStation(brood);return false;
        }
        if(!SurfaceApproach.walk(entity,station.at(),.9))return false;
        face(station.yaw());
        return deliveryStationReady(brood,station);
    }
    public void stop() {
        boolean recovered=task==Task.RECOVER&&delivery.recoveryTicks()<=0;
        if(task!=Task.NONE)GameplayLog.limited(entity,"family","task.stop."+task,200,
                "task stopped task={} target={} ticks={} recovered={} carriedFood={}",
                task,target==null?null:target.getUUID(),taskTicks,recovered,feeding.carrying());
        catchHat.resetApproach();
        if(task==Task.FEED_CHILD)entity.careApproach().cancel();task=Task.NONE;target=null;searchAt=null;taskTicks=0;feeding.reset();
        courtship.reset();
        // Recovered: no idle pause before the existing mash feeding picks up the hungriest child.
        wait=recovered?0:Math.max(wait,40);
    }
    public void died() {
        feeding.died();
        var data=FamilyData.get(level());data.died(entity);
        // Pending unborn reservations are cancelled above; completed births and detached stems
        // remain in the world ledger. The dead body must not retain their live presentation.
        gestation=null;spentStemCount=0;endBreedingPeriod();stop();entity.syncFamily();
        for(var other:level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(16),other->other!=entity&&other.isAlive()))
            if(age<1200&&(Bonds.parentOf(other, entity))) {
                // A newborn lost at birth: the parents stare blankly, then cry.
                other.life().personality().experienced(Trait.BOLDNESS,-3);other.bereave();
            } else if(Bonds.careKin(entity, other)&&entity.distanceToSqr(other)<100&&other.life().canRecognize(entity)&&other.hasLineOfSight(entity)) {
                other.life().personality().experienced(Trait.BOLDNESS,-3);other.mind().felt(EmotionEvent.FAMILY_DEATH); other.showReaction("surprise","family_died",120);
            }
    }
    public CompoundTag save() {
        CompoundTag tag=new CompoundTag();tag.putInt("Version",3);tag.putLong("Maturity",GrowthTiming.ADULT_START);tag.putString("Stage",stage.name());tag.putLong("Age",age);tag.putLong("Nourishment",nourishment);
        tag.putLong("Lifespan",GrowthTiming.LIFESPAN);tag.putFloat("AdultScale",adultScale);tag.putFloat("Scale",scale);tag.putInt("Conceptions",conceptions);tag.putInt("Recovery",recovery);
        tag.putBoolean("Initialized",initialized);tag.putBoolean("AwaitingLanding",awaitingLanding);tag.putBoolean("JuvenileVoice",juvenileVoice);
        tag.putBoolean("Sterile",sterile);tag.putInt("Stimulation",stimulation);
        tag.putBoolean("BabyTalk",babyTalk);tag.putInt("SexDrive",sexDrive);
        tag.putBoolean("BirthMiss",doomed);
        tag.put("BraidRescue",rescue.save());
        tag.put("HatBoarding",boarding.save());
        delivery.save(tag);
        catchHat.save(tag);
        flight.save(tag);
        tag.putInt("SpentStemCount",spentStemCount);tag.putFloat("SpentStemVariation",spentStemVariation);
        feeding.save(tag);
        if(mother!=null)tag.putUUID("Mother",mother);if(father!=null)tag.putUUID("Father",father);if(birthId!=null)tag.putUUID("BirthId",birthId);
        UUID partner=partner();
        if(gestation!=null)tag.putUUID("Gestation",gestation);if(partner!=null)tag.putUUID("Partner",partner);return tag;
    }
    public void load(CompoundTag tag) {
        // A saved body never resumes half of a live two-party handshake.
        task=Task.NONE;target=null;courtship.resetAttempt();
        pickup.cancel();cradleHolder=null;
        boarding.cancel();
        lifespan=GrowthTiming.LIFESPAN;age=Math.clamp(tag.getLong("Age"),0,GrowthTiming.LIFESPAN);nourishment=Math.max(0,tag.getLong("Nourishment"));
        maturity=GrowthTiming.ADULT_START;stage=stageForAge(age);
        adultScale=Math.clamp(tag.getFloat("AdultScale"),.8f,1.2f);scale=Math.clamp(tag.getFloat("Scale"),.1f,1.2f);
        conceptions=Math.max(0,tag.getInt("Conceptions"));recovery=Math.max(0,tag.getInt("Recovery"));initialized=tag.getBoolean("Initialized");
        awaitingLanding=tag.getBoolean("AwaitingLanding");juvenileVoice=tag.getBoolean("JuvenileVoice");
        sterile=tag.getBoolean("Sterile");stimulation=Math.clamp(tag.getInt("Stimulation"),0,1200);
        babyTalk=tag.getBoolean("BabyTalk");sexDrive=Math.clamp(tag.getInt("SexDrive"),0,BREEDING_PERIOD_TICKS);
        doomed=tag.getBoolean("BirthMiss")&&awaitingLanding;
        delivery.load(tag);
        catchHat.load(tag);
        flight.load(tag);
        // Dry stems are no longer kept on the body.
        spentStemCount=0;spentStemVariation=Math.clamp(tag.getFloat("SpentStemVariation"),.85f,1.15f);
        feeding.load(tag);
        mother=tag.hasUUID("Mother")?tag.getUUID("Mother"):null;father=tag.hasUUID("Father")?tag.getUUID("Father"):null;
        birthId=tag.hasUUID("BirthId")?tag.getUUID("BirthId"):null;gestation=tag.hasUUID("Gestation")?tag.getUUID("Gestation"):null;partner=tag.hasUUID("Partner")?tag.getUUID("Partner"):null;
        rescuingChild=null;rescue.load(tag.getCompound("BraidRescue"));
        boarding.load(tag.getCompound("HatBoarding"));
        stop();entity.syncFamily();
    }
}
