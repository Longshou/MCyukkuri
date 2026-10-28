package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.life.TravelRange;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.action.Gesture;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.action.Launch;
import io.github.longshou.mcyukkuri.motor.surface.BedContainer;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.settlement.SettlementSeasons;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.interaction.OperationLife;
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
    /** The existing close-follow baseline for a dependent land child; boldness and attachment adjust it at the decision boundary. */
    private static final double FAMILY_FOLLOW_DISTANCE_SQR = 2.25, FAMILY_FOLLOW_DISTANCE = 1.5,
            FAMILY_SEARCH_RANGE = 48;
    public enum Stage { BABY, JUVENILE, ADULT }
    enum Task { NONE, COURT, RECEIVE_COURT, BROOD, FOLLOW, WAIT_CHILD, SEARCH, MIDWIFE, FEED_CHILD, STAY_HOME, RECOVER, FETCH_HAT, CARRY, COMFORT }
    private final YukkuriEntity entity;
    private Stage stage=Stage.ADULT;
    private long maturity=GrowthTiming.ADULT_START, age=maturity+1, nourishment=maturity,
            lifespan=GrowthTiming.LIFESPAN;
    private float adultScale=1, scale=1;
    private UUID mother, father, birthId, gestation;
    /** Legacy entity mirror only; migrated server relationships belong to FamilyData.Person. */
    private UUID partner;
    private float birthScale;
    private int conceptions, recovery, wait, taskTicks, spentStemCount;
    private float spentStemVariation=1;
    private boolean initialized, awaitingLanding, juvenileVoice;
    private boolean sterile;
    private int stimulation;
    private int sexDrive;
    private boolean babyTalk;
    /** A hat set down to catch young: where, facing (entity yaw) and how it lies, and who set it. */
    public record HatPose(Vec3 at, float yaw, HatGeometry.Role role) {}
    /** Where a catcher stands, and her yaw, to set down, hold or take back a hat at a pose. */
    public record Stand(Vec3 at, float yaw) {}
    /** The mother's land-delivery position after a helper has placed a real catching hat. */
    record DeliveryStation(Vec3 at, float yaw) {}
    /** A newborn pushed past the hat by an overstrained delivery: it will not survive the landing. */
    private boolean doomed;
    /** A kit that came down on a nest's soft rim tumbles inward up to RIM_ROLLS times (transient, from the landing tick). */
    private int rimRolls,rimRollWait;
    private static final int RIM_ROLLS=3;
    /** The committed land-birth impulse is saved while airborne; settled readouts remain transient. */
    private BirthLaunch.Plan launch;
    private boolean launched;
    private String landingOutcome="";
    private double landingFromAim=Double.NaN,landingImpactVy,airVy;
    private final ParentCarry carry;
    /** Visible pregnancy and the brief post-delivery recovery of the mother's belly. */
    public float liveBelly() { return delivery.liveBelly(); }
    private Task task=Task.NONE;
    private YukkuriEntity target;
    private Vec3 searchAt;
    private final CareDecisionLedger careDecisions;
    private final FamilyFeeding feeding;
    private final FamilySeparation separation;
    private final Courtship courtship;
    private final CatchHat catchHat;
    private final LiveDelivery delivery;
    public FamilyLife(YukkuriEntity entity) {
        this.entity=entity;
        carry=new ParentCarry(entity);
        careDecisions=new CareDecisionLedger(entity);
        feeding=new FamilyFeeding(this);
        separation=new FamilySeparation(this);
        courtship=new Courtship(this);
        catchHat=new CatchHat(this);
        delivery=new LiveDelivery(this);
    }
    /** Land transport of dependent babies (hat brim, tail or mouth); see {@link ParentCarry}. */
    public ParentCarry carry(){return carry;}
    /** One persistent parent-side decision per child-care need, shared by land and hat routes. */
    public CareDecisionLedger careDecisions(){return careDecisions;}
    FamilyFeeding feeding(){return feeding;}
    /** Where a retrieved baby is set down: its care nest, else its own shelter bed. */
    public BlockPos carryHome(){
        var care=FamilyNest.care(entity);if(care!=null)return care.getBlockPos();
        var home=entity.habitat().shelter();
        return home!=null&&home.usable(entity)?home.bed(entity.getUUID()):null;
    }
    /** The carrier walks the carried baby home (retrieval); marching carriers keep to the settlement walk instead. */
    void beginCarry(){if(task==Task.NONE){task=Task.CARRY;target=carry.carried();taskTicks=0;}}
    void endCarry(){if(task==Task.CARRY)stop();}
    public void carryInterrupted(String why){
        boolean childSide=entity.isPassenger()&&!carry.carrying();
        if(childSide&&(why.equals("fright")))return;
        carry.interrupted(why,childSide);
    }
    public String describe() {
        return "task=" + task + " ticks=" + taskTicks
                + " target=" + (target == null ? "none" : "#" + target.getId())
                + " search=" + (searchAt == null ? "none" : searchAt)
                + " court={" + courtship.describe() + "}"
                + " separation={" + separation.describe() + "} nest={" + FamilyNest.describe(entity) + "}" + " radius=" + Math.sqrt(familyRadiusSqr()) + " care={" + careDecisions.describe() + "} feeding={" + feeding.describe() + "} catch={" + catchHat.describe() + "} carry={" + carry.describe() + "}";
    }
    ServerLevel level() { return (ServerLevel)entity.level(); }
    YukkuriEntity entity() { return entity; }
    Courtship courtship() { return courtship; }
    /** Observe movement ownership without advancing the drawn birth launch. */
    public boolean launchInFlight(){ return entity.motor().current() instanceof Launch; }
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
        if(stage==Stage.BABY)return true;
        if (adult() || entity.profile().houseBody() || entity.profile().dangerToYukkuri()) return false;
        return stage != Stage.JUVENILE || age < maturity / 4 || nourishment < maturity * 3 / 16;
    }
    public boolean emergencyForaging() { return separation.emergencyForaging(); }
    public boolean canEatIndependently() { return !awaitingLanding && (stage!=Stage.BABY&&!needsMash()||emergencyForaging()); }
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
    public CompoundTag courtshipState() { return courtship.state(); }
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
    /** Uses the existing food source, care refusal and physical reach rules; it never starts care or rolls willingness. */
    public boolean hasFeedingOpportunity(YukkuriEntity child) { return feeding.hasOpportunity(child); }
    /** Migration yields to the same feeding and lost-child routines instead of leaving dependants behind. */
    public boolean prepareMigrationCare() {
        if(!adult()||!ordinaryLandFree(entity)||!entity.settlement().familyMarching())return false;
        if(migrationCareActive())return canContinue();
        if(task!=Task.NONE||wait>0||entity.life().hasUrgentNeed()||entity.life().mealTicks()>0
                ||entity.life().isSleeping()||entity.dailyLife().active())return false;
        wait=40;
        return feeding.startCare()||startLandChildSearch();
    }
    /** Current family work only: no search or willingness decision is performed for this readout. */
    public boolean migrationCareActive() { return task==Task.FEED_CHILD||task==Task.SEARCH&&target!=null&&caresFor(target); }
    public boolean elder() { return age>=GrowthTiming.ELDER_START; }
    /** The final child interval is internally still {@link Stage#JUVENILE}; it is a separate age qualification. */
    public boolean subadult() { return age>=GrowthTiming.SUBADULT_START&&age<GrowthTiming.ADULT_START; }
    public boolean active() { return task!=Task.NONE||laboring()||delivery.recoveryTicks()>0||catchHat.moving()||catchHat.fetching()||launchInFlight()
            ||delivery.comfortActive(); }
    /**
     * An everyday family errand (waiting for, following or looking for a child, staying near the nest) gives way to comforting a hurt kin
     * ({@link io.github.longshou.mcyukkuri.social.WoundComfort}); births, feeding, courtship, brooding and recovery do not.
     * Before this the parent's standing "wait for the child" errand lasted as long as the child wandered, and the parent never noticed the wound.
     */
    public boolean yieldsToComfort() {
        return !laboring()&&delivery.recoveryTicks()==0&&!catchHat.moving()&&!catchHat.fetching()&&!launchInFlight()&&!delivery.comfortActive()
                &&(task==Task.NONE||task==Task.WAIT_CHILD||task==Task.FOLLOW||task==Task.STAY_HOME||task==Task.SEARCH);
    }
    public String activity() {
        if(catchHat.moving())return catchHat.activity();
        if(delivery.nuzzling())return Behaviors.NUZZLE;
        if(laboring())return Behaviors.LABOR;
        if(delivery.recoveryTicks()>0)return Behaviors.POSTPARTUM;
        return switch(task) {
            case COURT, RECEIVE_COURT -> courtActivity(); case BROOD -> Behaviors.TEND_BROOD; case FOLLOW -> Behaviors.FOLLOW_PARENT;
            case WAIT_CHILD -> Behaviors.WAIT_CHILD; case SEARCH -> carry.lifting()?Behaviors.CARRY_LIFT:Behaviors.SEARCH_FAMILY;
            case MIDWIFE -> catchHat.dutyActivity();
            case FEED_CHILD -> feeding.activity();
            case FETCH_HAT -> Behaviors.IDLE;
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
    /** Only Marisa midwife with her hat: a hatted Marisa father close by, else a hatted Marisa mother catches her own young. */
    public static YukkuriEntity catcher(YukkuriEntity mother,FamilyData.Brood brood) { return CatchHat.catcher(mother,brood); }
    /** Where the hat goes to catch (hat_vessel.json, the end pose of the braid clips). A land Marisa presses it upside down
     *  before the outlet, the live exit just inside its opening (a mother on her own sets it where her own clip puts it). */
    public static HatPose catchPose(YukkuriEntity mother,FamilyData.Brood brood,YukkuriEntity catcher,HatGeometry.Role role) { return CatchHat.catchPose(mother,brood,catcher,role); }
    /** The catcher's place for a hat at this pose: the inverse of the clip's placement. */
    public static Stand stand(HatPose pose,YukkuriEntity catcher) { return CatchHat.stand(pose,catcher); }
    /**
     * Turns toward {@code yaw} by at most the body's own turn rate (spec 8.1 / 9.4 Face). The remaining callers of this run on
     * the passive component clock or inside a move step, where spec 9.3 forbids starting an action, so a goal that can decide
     * asks the motor for {@code Face} instead and this stays the bounded fallback.
     */
    void face(float yaw){Motor.face(entity,Mth.approachDegrees(entity.getYRot(),yaw,(float)BodyAbility.of(entity).turnRate()));}
    /** This Marisa's hat, set down nearby to catch. */
    public PlacedHatEntity cradleEntity() { return catchHat.cradleEntity(); }
    /** The hat stays down while the young are coming and a little after, while they wake in it. */
    public boolean keepsCradle() { return catchHat.keepsCradle(); }
    /** A helper's landed hat brings the mother back to the outlet position used when it was set down. */
    DeliveryStation deliveryStation(FamilyData.Brood brood) {
        if(brood==null||delivery.catchStationAbandoned())return null;
        var helper=catcher(entity,brood);
        return helper==null||helper==entity?null:helper.family().catchHat.deliveryStation(entity,brood);
    }
    DeliveryStation catchRendezvous(FamilyData.Brood brood) {
        if(brood==null||withholdsCushion())return null;
        var helper=catcher(entity,brood);
        return helper==null||helper==entity?null:helper.family().catchHat.rendezvous(entity,brood);
    }
    /** Late delivery and the father's approach take precedence over ordinary household routines. Pure observation. */
    public boolean prioritizesBirth() {
        if(entity.level().isClientSide||!entity.isAlive()||entity.isNoAi()||entity.isPanicking()||entity.isCommandPlaying()
                ||entity.operations().active()||entity.life().hasUrgentNeed()||entity.life().mealTicks()>0||awaitingLanding)return false;
        var b=brood();
        return b!=null&&!b.cancelled&&!b.complete()&&b.kind!=FamilyData.BirthKind.EGG&&b.elapsed>=Math.max(0,b.duration-CatchHat.APPROACH_LEAD)
                ||task==Task.MIDWIFE||catchHat.cleanupPending()||catchHat.mateInLabor()!=null||delivery.comfortActive();
    }
    /** A helper observes the mother's single placement window; it never advances her clock. */
    boolean acceptsCatch(FamilyData.Brood brood){return delivery.acceptsCatch(brood);}
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
    /** The maternal clock waits while the BROOD goal prepares her own catching hat. */
    boolean waitsForOwnHat(FamilyData.Brood brood) {
        if(FamilyNest.large(entity)&&FamilyNest.atAssignedNest(entity)&&catcher(entity,brood)==entity)return false;
        return catchHat.needsSelfCatch(brood);
    }
    /** Called by the set-down hat when it is no longer needed: come back for it and put it on. */
    public void fetchHat(PlacedHatEntity hat) { catchHat.fetchHat(hat); }
    public void doom(){doomed=true;}
    public boolean doomed(){return doomed;}
    /** In a drawn birth launch: the body is not on its own feet. */
    public boolean flying(){return launchInFlight();}
    /** The hat does not collide with its own incoming passenger: a newborn launched into it. */
    public boolean boardingThrough(PlacedHatEntity hat){
        return awaitingLanding&&launch!=null&&hat!=null&&hat.getUUID().equals(launch.hat());
    }
    /** The captain's hat goes back on (the authored hat_on_self clip) once the token has been returned to her. */
    public boolean playHatOn() { return catchHat.playHatOn(); }
    public boolean hatOnPlaying() { return catchHat.hatOnPlaying(); }
    /** The newborn's own owner of the drawn birth launch; it stays active until that action ends. */
    private static final MotorOwner BIRTH_LAUNCH=new MotorOwner(){
        @Override public String ownerId(){return "birth_launch";}
        @Override public boolean active(){return true;}
    };
    /**
     * The one birth launch: the caller has already drawn the impulse into {@code plan}, which is kept (and saved) exactly as it was
     * drawn. The body itself leaves the outlet on its first decision tick, so the action is started from {@link #startBirthLaunch()}
     * and never from a passive clock; nothing here moves it.
     */
    void launch(BirthLaunch.Plan plan,FamilyData.Brood brood,int index,PlacedHatEntity hat){
        this.launch=plan;
        this.launched=false;
        logLaunch(plan,brood,index,hat);
    }
    /**
     * The pending drawn impulse leaves the body now (called from the newborn's own decision tick, {@code travel}). True means this
     * tick belongs to the launch: vanilla must not move the body before the first contact check. The stored plan is the only
     * "pending" state, so a reload mid-flight resumes the same impulse instead of rolling a new one.
     */
    public boolean startBirthLaunch(){
        if(launch==null||launched||launchInFlight()||!awaitingLanding||entity.onGround()||entity.isInWaterOrBubble()||entity.isPassenger())return false;
        launched=true;
        if(!entity.motor().run(BIRTH_LAUNCH,new Launch(launch.velocity(),"birth"))){
            var refusal=entity.motor().last();
            GameplayLog.event(entity,"family","birth launch refused lastReason={} shortfall={}; the body drops where it is",refusal==null?"n/a":refusal.reason(),refusal==null?0:refusal.shortfall());
            return false;
        }
        entity.getNavigation().stop();
        return true;
    }
    public boolean newbornLaunching(){ return freeFalling()||launchInFlight(); }
    /** A newborn between the outlet (or stem) and its first contact: nothing steers or shoves it. */
    public boolean freeFalling(){ return awaitingLanding&&!entity.onGround()&&!entity.isPassenger()&&!entity.isInWaterOrBubble(); }
    /** A stem fruit comes away with a small random toss; the hat held under the stem, a large nest or the bare ground take it by contact. */
    void tossStem(FamilyData.Brood brood,int index){
        var start=entity.position();
        var hats=level().getEntitiesOfClass(PlacedHatEntity.class,entity.getBoundingBox().inflate(1.2,3,1.2),cradle->cradle.catches()&&cradle.young(entity));
        PlacedHatEntity hat=hats.isEmpty()?null:hats.get(0);
        var mom=mother!=null&&level().getEntity(mother) instanceof YukkuriEntity parent?parent:null;
        var centre=mom==null?null:FamilyNest.largeCentre(mom);
        double half=io.github.longshou.mcyukkuri.life.NestResting.LARGE.half();
        var target=hat!=null?BirthLaunch.Target.HAT:centre!=null&&Math.abs(start.x-centre.x)<half&&Math.abs(start.z-centre.z)<half?BirthLaunch.Target.NEST:BirthLaunch.Target.GROUND;
        launch(BirthLaunch.stem(level().random,start,target),brood,index,hat);
    }
    private void logLaunch(BirthLaunch.Plan plan,FamilyData.Brood brood,int index,PlacedHatEntity hat){
        GameplayLog.event(entity,"family","birth launch brood={} index={} kind={} target={} hat={} aim={} start={} room={} nominal={} push={} yaw={} velocity={}",
                brood.id,index,brood.kind,plan.target(),hat==null?null:hat.getUUID(),plan.aim(),plan.start(),
                Double.isNaN(plan.room())?"n/a":String.format(java.util.Locale.ROOT,"%.2f",plan.room()),
                String.format(java.util.Locale.ROOT,"%.3f",plan.nominal()),String.format(java.util.Locale.ROOT,"%.2f",plan.push()),
                String.format(java.util.Locale.ROOT,"%.1f",plan.yaw()),plan.velocity());
    }
    /** Where the kit came down: outcome, distance from the planned landing point and the vertical speed on impact. */
    public String landingOutcome(){ return landingOutcome; }
    public double landingFromAim(){ return landingFromAim; }
    public double landingImpactVy(){ return landingImpactVy; }
    public String landingStatus(){
        if(landingOutcome.isEmpty())return "";
        return landingOutcome+" push="+(launch==null?"n/a":String.format(java.util.Locale.ROOT,"%.2f",launch.push()))
                +" fromAim="+(Double.isNaN(landingFromAim)?"n/a":String.format(java.util.Locale.ROOT,"%.2f",landingFromAim));
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
        if(!entity.isAlive()){carry.dropAll("carrier_dead");return;}
        carry.tick();
        if(!entity.isNoAi())tickAftermathBurden();
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
            catchHat.stopAction("ai_disabled");
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
        separation.tick();
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
            entity.body().terminalKill(level().damageSources().generic(),false,false,"old_age");return;
        }
        if(entity.tickCount%100==0)FamilyData.get(level()).register(entity);
        catchHat.tickLinger();
        if(delivery.bumping())delivery.tickBump();
        if(awaitingLanding&&!entity.onGround())airVy=entity.getDeltaMovement().y;
        if(launchInFlight())return;
        if(freeFalling())entity.getNavigation().stop();
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
        var brood=brood();
        // Labour wakes a sleeping mother (a night pregnancy used to wait until morning, or for ever in a hat).
        if(brood!=null&&!brood.cancelled&&!brood.complete()&&brood.kind!=FamilyData.BirthKind.EGG&&brood.elapsed>=brood.duration-60&&entity.life().isSleeping()){
            if(entity.life().isSleeping())entity.life().interrupt();
        }
        // An expecting mother shows it at a glance: a stem on her head or a swelling belly, and a small heart now and then.
        if(brood!=null&&!brood.cancelled&&!brood.complete()&&brood.incidentSource==null&&brood.kind!=FamilyData.BirthKind.EGG&&entity.tickCount%60==0)
            level().sendParticles(ParticleTypes.HEART,entity.getX(),entity.getY()+entity.getBbHeight()+.15,entity.getZ(),1,.12,.04,.12,0);
        if(brood!=null&&!brood.cancelled&&!brood.complete()&&brood.kind==FamilyData.BirthKind.STEM) advanceStem(brood);
        else if(brood!=null&&!brood.cancelled&&!brood.complete()&&brood.kind==FamilyData.BirthKind.LIVE)delivery.advanceLive(brood);
        else if(gestation!=null&&(brood==null||brood.cancelled||brood.complete()&&brood.kind!=FamilyData.BirthKind.STEM))gestation=null;
    }
    /** Pure contact confirmation also runs with AI disabled; optional hat seeking remains an AI behavior. */
    private boolean handleLanding(boolean hopIntoHat) {
        if(!awaitingLanding||launchInFlight())return false;
        if(rimRollWait>0){rimRollWait--;return true;}
        if(!(entity.onGround()||entity.isInWaterOrBubble()))return false;
        // Only real contact decides: the hat, the water, the nest cushion, a nest's soft rim, or the bare ground.
        boolean pad=FamilyNest.cushions(entity);
        var rim=entity.getVehicle()==null&&!entity.isInWaterOrBubble()&&!pad?FamilyNest.rimCentre(entity):null;
        if(rim!=null&&rimRolls<RIM_ROLLS){
            // Come down on the soft rim or bolster: it tumbles down the inner slope toward the floor, a small real roll
            // (project design); the next contact decides again. The landing tick runs on a passive clock, so this applies
            // Gesture ROLL_IN's own collision-resolved step toward the nest centre instead of starting the action.
            rimRolls++;rimRollWait=3;
            double moved=Gesture.roll(entity,rim,Gesture.ROLL_REACH/RIM_ROLLS);
            entity.fallDistance=0;
            GameplayLog.event(entity,"family","newborn on nest rim rolls inward roll={} moved={} at={} centre={}",
                    rimRolls,String.format(java.util.Locale.ROOT,"%.2f",moved),entity.position(),rim);
            return true;
        }
        awaitingLanding=false;
        // The launch is over: classify only by where the body actually is. The hat it rode, the water it fell into, a nest
        // bed whose supported floor carries it, a nest's soft rim, or the bare ground.
        var bed=entity.getVehicle()==null&&!entity.isInWaterOrBubble()?carryingBed():null;
        String outcome=Attach.of(entity) instanceof AttachState.OnHost?"HAT":entity.isInWaterOrBubble()?"WATER":pad||bed!=null?"CUSHION":rim!=null?"RIM":"GROUND";
        boolean randomized=launch!=null||doomed;
        landingOutcome=outcome;landingImpactVy=airVy;
        landingFromAim=launch==null||launch.aim()==null?Double.NaN:Math.hypot(entity.getX()-launch.aim().x,entity.getZ()-launch.aim().z);
        doomed=false;
        switch(outcome){
            case "CUSHION" -> {
                entity.fallDistance=0;
                GameplayLog.event(entity,"family","newborn landed on nest cushion rimRolls={}",rimRolls);
            }
            case "RIM" -> {
                // Still on the soft rim after rolling: unhurt, it stays there until it crawls or is carried in.
                entity.fallDistance=0;
                GameplayLog.event(entity,"family","newborn settled on soft nest rim rimRolls={}",rimRolls);
            }
            case "WATER" -> {
                // Missed the hat into the river: the newborn cannot swim and drowns by immersion unless a parent's braid brings it out.
                if(randomized){
                    BirthStaging.thud(entity);entity.waterDistress().plunge();
                    if(mother!=null&&level().getEntity(mother) instanceof YukkuriEntity parent)BirthStaging.caption(parent,"漏接落水：新生儿掉进水里，无法自救，须亲油及时救起，否则溺亡");
                    return true;
                }
            }
            case "GROUND" -> {
                // Nothing under it: a real fall injury.
                delivery.bump();return false;
            }
            default -> {}
        }
        // The greeting waits until first_wake has opened the eyes and reached its happy face (24 ticks), not the landing tick.
        entity.showReactionDelayed("happy","newborn",100,24);return false;
    }
    /** The nest bed whose real supported floor carries this body (spec 7: INSIDE_SUPPORTED), or null on bare ground. */
    private BedContainer carryingBed(){
        if(!entity.onGround()||entity.isInWaterOrBubble())return null;
        var box=entity.getBoundingBox();
        for(var pos:BlockPos.betweenClosed(BlockPos.containing(box.minX,box.minY-.01,box.minZ),BlockPos.containing(box.maxX,box.maxY,box.maxZ))){
            var bed=BedContainer.of(entity,pos);
            if(bed!=null&&Occupancy.of(entity,bed)==Occupancy.INSIDE_SUPPORTED)return bed;
        }
        return null;
    }
    /** Called once when a real sleep completes. Neither observation nor time-of-day commands grow the entity. */
    public boolean woke() {
        if(entity.level().isClientSide||entity.life().nutrition()<35)return false;
        return grow(true);
    }
    /** Stage changes only follow the fixed loaded-time clock. Nourishment only controls how far that stage can grow. */
    private boolean grow(boolean slept) {
        // The size standard (GrowthSize): one curve over nourishment, capped by the time stage reached, never shrinking.
        float desired=Math.max(scale,GrowthSize.scale(stage,nourishment,maturity,adultScale,entity.profile().houseBody(),birthScale));
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
        scale=GrowthSize.scale(stage,nourishment,maturity,adultScale,entity.profile().houseBody(),birthScale);
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
        age=0;nourishment=0;stage=GrowthSize.birthStage(brood.kind);
        birthScale=GrowthSize.birth(brood.kind,child);scale=birthScale;
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
    public boolean canReproduceWith(YukkuriEntity other) { return courtship.canReproduceWith(other); }
    private void advanceStem(FamilyData.Brood brood) {
        if(entity.operations().active())return;
        if(entity.isPanicking()||entity.isOnFire()||entity.isInWaterOrBubble()||entity.isCommandPlaying()||entity.life().nutrition()<30||entity.body().condition().blocksBreedingApproach())return;
        delivery.tickCatchWindow(brood);
        if(!delivery.readyAtCatchStation(brood))return;
        if(!delivery.placeForTerm(brood))return;
        if(entity.life().mealTicks()>0||entity.life().isSleeping())return;
        if(brood.elapsed>=brood.duration-200&&waitsForOwnHat(brood))return;
        if(brood.elapsed>=brood.duration-200&&delivery.waitForCatcher(brood))return;
        if(catchHat.moving())return;
        if(brood.elapsed<brood.duration+40)brood.elapsed++;
        if(brood.elapsed%200==0)entity.life().spendNutrition(1);
        // The first tick of actual growth is also the first visible sprout: the victim realizes it (native seisian wording).
        if(brood.elapsed==1&&brood.incidentSource!=null)entity.showReaction("confused","aftermath_realize",160);
        FamilyData.get(level()).setDirty();
        for(int i=0;i<brood.children.size();i++) {
            if(brood.children.get(i).born||brood.elapsed<brood.duration+i*8)continue;
            Vec3 local=FamilyGeometry.childBase(brood.children.size(),i,1,entity.growthScale(),brood.variation,entity.getVariant(),brood.children.get(i).variant,GrowthSize.physicalBirth(brood.kind,brood.children.get(i)));
            Vec3 world=FamilyGeometry.toWorld(entity,local);
            // A blocked attachment delays birth; it does not spawn through the ceiling or into a wall.
            var child=FamilyData.get(level()).birth(brood,i,level(),world);
            if(child!=null) {
                if(!child.family().doomed()&&!child.family().flying())child.family().tossStem(brood,i);
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
    /** Overstrain, a demonstrated miss, or the natural 12%: the newborn goes past the hat onto the ground. */
    public boolean missesDelivery(boolean naturalMiss) { return delivery.missesDelivery(naturalMiss); }
    /** A scene outcome is in force for this delivery (caught, overstrain or missed). */
    public boolean sceneRequested() { return delivery.sceneRequested(); }
    /** The demonstrated miss: no hat is set down for this delivery. */
    public boolean withholdsCushion() { return delivery.withholdsCushion(); }
    public boolean unprotectedBirthExit(){return delivery.unprotectedExit();}
    public BirthExit.Exit selectedBirthExit(int index){return delivery.selectedExit(index);}
    public boolean bumped() { return delivery.bumped(); }
    /** Birth duties that come before first-stool licking and mash feeding: the hat is still down or being taken back,
     *  a hurt newborn is being comforted, or the mother is still recovering. */
    public boolean birthDuty(){return keepsCradle()||catchHat.moving()||catchHat.fetching()||delivery.comfortActive()||delivery.recoveryTicks()>0;}
    boolean supported(){
        // A zero-displacement physics step can clear onGround for one tick while the sole still touches a block.
        return entity.onGround()||Math.abs(entity.getDeltaMovement().y)<.09
                &&entity.level().getBlockCollisions(entity,entity.getBoundingBox().move(0,-.025,0)).iterator().hasNext();
    }
    /** The assigned nest anchor; FamilyNest resolves the actual supported resting position. */
    public BlockPos nestStand(){return entity.life().homeStand();}
    /** Where a new brood is anchored for the population budget: her nest, else where she stands. */
    public BlockPos birthAnchor(){var nest=nestStand();return nest!=null?nest:entity.blockPosition();}
    /** Guards a parent must pass to take a duty at the birth: the same ones the family goal applies. */
    boolean dutyReady() { return catchHat.dutyReady(); }
    boolean leavingNestForCatcher(FamilyData.Brood brood) { return delivery.leavingNestForCatcher(brood); }
    /** A parent hurries to a newborn that fell hard, nuzzles it and licks it (project original; nuzzling is Wiki E06). */
    public void comfort(YukkuriEntity baby) { delivery.comfort(baby); }
    public boolean canUse() {
        if(entity.dailyLife().active())return false;
        if(entity.isPanicking()||entity.isCommandPlaying()||entity.life().hasUrgentNeed()||entity.life().mealTicks()>0||awaitingLanding)return false;
        if(catchHat.cleanupPending()&&task!=Task.MIDWIFE&&task!=Task.BROOD&&task!=Task.RECOVER) {
            if(task!=Task.FETCH_HAT){if(task!=Task.NONE)stop();task=Task.FETCH_HAT;taskTicks=0;}
            return true;
        }
        if(task!=Task.NONE)return true;
        if(delivery.recoveryTicks()>0){task=Task.RECOVER;taskTicks=0;return true;}
        if(laboring()){task=Task.BROOD;taskTicks=0;return true;}
        if(wait>0)return false;wait=40;
        var pregnancy=brood();
        if(pregnancy!=null&&!pregnancy.cancelled&&!pregnancy.complete()&&pregnancy.kind!=FamilyData.BirthKind.EGG) { task=Task.BROOD;taskTicks=0;return true; }
        if(!adult()) {
            if(emergencyForaging())return false;
            if(exploring()&&!familyTravelling()&&!entity.life().hasUrgentNeed())return false;
            if(!entity.childhood().shouldStayWithParents())return false;
            if(entity.getVehicle() instanceof YukkuriEntity)return false;
            // A baby that cannot keep up waits for a parent who can carry it instead of crawling after the march.
            if(ParentCarry.awaitsCarrier(entity))return false;
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
                        &&entity.distanceToSqr(Vec3.atBottomCenterOf(known.position.pos()))<48*48) {
                    searchAt=Vec3.atBottomCenterOf(known.position.pos());task=Task.SEARCH;taskTicks=0;entity.showReaction("surprise",
                            "lost_parent",100);return true;
                }
            }
            return false;
        }
        catchHat.claimMidwife();
        if(task==Task.MIDWIFE)return true;
        if(catchHat.claimParentCradle()){task=Task.FETCH_HAT;taskTicks=0;return true;}
        if(delivery.comfortActive()){task=Task.COMFORT;taskTicks=0;return true;}
        // Finish collecting and depositing the real load before routine care can send the parent home again.
        if(entity.forageTrip()!=null&&entity.forageTrip().outing())return false;
        if(feeding.startCare())return true;
        if(ordinaryLandFree(entity)) {
            if(startLandChildSearch())return true;
        } else if(!entity.profile().landSettler()) {
            var child=level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(10),other->!other.family().adult()
                    &&other.childhood().shouldStayWithParents()&&Bonds.parentOf(entity,other)&&entity.life().canRecognize(other))
                    .stream().filter(other->entity.distanceToSqr(other)>6.25&&entity.hasLineOfSight(other)).min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
            if(child!=null) { task=Task.WAIT_CHILD;target=child;taskTicks=0;entity.showReaction("teary","wait_family",80);return true; }
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
    private boolean startLandChildSearch() {
        var child=closestSeparatedDependentChild();
        if(child==null)return false;
        task=Task.SEARCH;target=child;searchAt=null;taskTicks=0;entity.showReaction("teary","wait_family",80);return true;
    }
    private YukkuriEntity closestParent() {
        if(familyTravelling())return migrationEscort();
        return level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(12),other->other.isAlive()
                &&(other.getUUID().equals(mother)||other.getUUID().equals(father))&&entity.life().canRecognize(other))
                .stream().filter(other->entity.life().reachable(other.blockPosition(),false)).min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
    }
    /** Use the same real parent for a travelling child's follow and the parents' search decisions. */
    private YukkuriEntity migrationEscort() {
        YukkuriEntity first=null,second=null;
        for(var id:new UUID[]{mother(),father()})if(id!=null&&level().getEntity(id) instanceof YukkuriEntity parent
                &&ordinaryLandFree(parent)&&parent.family().adult()&&ParentCarry.mayEscort(parent,entity)
                &&(parent.settlement().familyMarching()||entity.settlement().returningToSettledParent(parent))
                &&entity.distanceToSqr(parent)<FAMILY_SEARCH_RANGE*FAMILY_SEARCH_RANGE
                &&entity.life().canRecognize(parent)&&parent.life().canRecognize(entity)) {
            if(first==null)first=parent;else second=parent;
        }
        // Retain an actual rendezvous until it finishes; neither parent may pull against the child's running follow.
        if(task==Task.FOLLOW&&(target==first||target==second))return target;
        boolean firstSearching=first!=null&&first.family().task==Task.SEARCH&&first.family().target==entity;
        boolean secondSearching=second!=null&&second.family().task==Task.SEARCH&&second.family().target==entity;
        if(firstSearching&&secondSearching)return first.getUUID().compareTo(second.getUUID())<0?first:second;
        if(firstSearching)return first;
        if(secondSearching)return second;
        if(first==null)return null;
        // A baby that needs carrying goes to the parent who can take it, a Reimu who can hold several before the rest.
        int firstRank=ParentCarry.carryRank(first,entity),secondRank=second==null?0:ParentCarry.carryRank(second,entity);
        if(second!=null&&(secondRank>firstRank||secondRank==firstRank&&(entity.distanceToSqr(second)<entity.distanceToSqr(first)
                ||entity.distanceToSqr(second)==entity.distanceToSqr(first)&&second.getUUID().compareTo(first.getUUID())<0))) {
            var swap=first;first=second;second=swap;
        }
        if(entity.life().reachable(first.blockPosition(),false))return first;
        return second!=null&&entity.life().reachable(second.blockPosition(),false)?second:null;
    }
    private boolean escortsMigratingChild(YukkuriEntity child) {
        return !child.family().familyTravelling()||child.family().migrationEscort()==entity;
    }
    /**
     * The existing 1.5-block dependent-child follow baseline is scaled by the shared exploration
     * formula. Attachment only narrows or releases the radius while the child is still dependent;
     * independent subadults never enter this follow/search path.
     */
    private boolean exploring() { return entity.roaming()!=null&&entity.roaming().exploring(); }
    private double familyRadiusSqr() {
        if(exploring()&&!familyTravelling())return Math.pow(entity.roaming().radius()+2,2);
        if(!ordinaryLandFree(entity))return FAMILY_FOLLOW_DISTANCE_SQR;
        var personality=entity.life().personality();
        double radius=FAMILY_FOLLOW_DISTANCE*personality.exploreMultiplier();
        if(entity.childhood().shouldStayWithParents())radius*=1.20-.004*personality.attachment();
        return radius*radius;
    }
    /** The new mutual search is only for ordinary land families that can all move freely; independent young are never recalled. */
    private YukkuriEntity closestSeparatedDependentChild() {
        return level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(FAMILY_SEARCH_RANGE),other->other.isAlive()
                &&ordinaryLandFree(other)&&caresFor(other)&&other.childhood().shouldStayWithParents()&&entity.life().canRecognize(other)
                &&escortsMigratingChild(other)&&!safeInSharedHome(other)
                &&(!other.family().exploring()||other.life().hasUrgentNeed())
                &&(entity.distanceToSqr(other)>other.family().familyRadiusSqr()||!entity.hasLineOfSight(other)||carry.pickupDue(other)))
                .stream().min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
    }
    /** Water, boarding, delivery and braid work retain their dedicated family paths instead of being taken over by land-child search. */
    static boolean ordinaryLandFree(YukkuriEntity member) {
        var family=member.family();
        return member.isAlive()&&!member.isNoAi()&&!member.profile().houseBody()&&!member.profile().dangerToYukkuri()
                &&!member.isInWaterOrBubble()&&!member.isPassenger()&&!member.isCommandPlaying()&&!member.operations().active()
                &&!family.awaitingLanding()&&!family.laboring()&&!family.birthDuty();
    }
    /** Early children wait by their real shared shelter instead of continually chasing a foraging parent. */
    private BlockPos earlyHomeStand() {
        if(familyTravelling()||exploring()||emergencyForaging())return null;
        var care=FamilyNest.care(entity);if(care!=null)return care.getBlockPos();
        if(readyForOwnNest()&&!parentForaging(mother)&&!parentForaging(father))return null;
        var home=entity.habitat().shelter();
        return home!=null&&home.usable(entity)?home.bed(entity.getUUID()):null;
    }
    /** Dependent walkers follow their real family on the march and on an aborted move's return route. */
    public boolean familyTravelling() {
        if(entity.settlement().familyMarching())return true;
        for(var id:new UUID[]{mother(),father()})if(id!=null&&level().getEntity(id) instanceof YukkuriEntity parent
                &&parent.isAlive()&&parent.settlement().familyMarching())return true;
        return false;
    }
    private boolean parentForaging(UUID id) {
        return id!=null&&level().getEntity(id) instanceof YukkuriEntity parent&&parent.isAlive()
                &&parent.forageTrip()!=null&&parent.forageTrip().outing()&&entity.habitat().shelter()!=null
                &&entity.habitat().shelter()==parent.habitat().shelter();
    }
    private boolean safeInSharedHome(YukkuriEntity child) {
        if(FamilyNest.rimNest(child)!=null)return false;
        if(entity.settlement().familyMarching()&&entity.settlement().underway())return false;
        if(FamilyNest.sameNest(entity,child)&&FamilyNest.atAssignedNest(child))return true;
        var home=entity.habitat().shelter();
        return home!=null&&home==child.habitat().shelter()&&home.usable(child)&&home.contains(child.blockPosition())
                &&!child.isPanicking()&&!child.isInWaterOrBubble()&&!child.isOnFire();
    }
    /** Idle roaming must not immediately undo the child's decision to wait in the family shelter. */
    public boolean allowsIdleWalk(Vec3 destination) {
        if(stage==Stage.BABY)return TravelRange.babyDestination(entity,BlockPos.containing(destination));
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
        if((task==Task.COURT||task==Task.RECEIVE_COURT)&&!canReproduceWith(target))return false;
        if(task==Task.NONE||entity.isPanicking()||entity.life().mealTicks()>0||entity.isCommandPlaying())return false;
        if(task==Task.FEED_CHILD)return feeding.canContinue();
        if(entity.life().hasUrgentNeed())return false;
        if(task==Task.FETCH_HAT)return catchHat.cleanupPending();
        if(task==Task.RECOVER)return delivery.recoveryTicks()>0;
        if(task==Task.COMFORT)return delivery.comfortActive();
        // Let the duty execute its completion/cancellation cleanup once when the brood disappears.
        if(task==Task.MIDWIFE)return target!=null&&target.level()==entity.level();
        if(task==Task.STAY_HOME) {
            var home=earlyHomeStand();return home!=null&&entity.distanceToSqr(Vec3.atBottomCenterOf(home))>4;
        }
        if(task==Task.FOLLOW&&earlyHomeStand()!=null)return false;
        if(task==Task.CARRY)return carry.carrying()&&taskTicks<2400;
        if(task==Task.SEARCH&&target!=null)return ordinaryLandFree(entity)&&ordinaryLandFree(target)&&caresFor(target)
                &&escortsMigratingChild(target)&&!safeInSharedHome(target)
                &&target.childhood().shouldStayWithParents()&&entity.life().canRecognize(target)&&taskTicks<1200&&target.level()==entity.level()
                &&entity.distanceToSqr(target)<FAMILY_SEARCH_RANGE*FAMILY_SEARCH_RANGE;
        return (taskTicks<240||task==Task.BROOD&&prioritizesBirth())
                &&(task==Task.BROOD||task==Task.SEARCH||target!=null&&target.isAlive()&&target.level()==entity.level()&&entity.distanceToSqr(target)<256);
    }
    public void tickGoal() {
        if(!canContinue()){stop();return;}taskTicks++;
        if(catchHat.moving()){catchHat.tickHat();return;}
        if(task==Task.SEARCH&&taskTicks==1)GameplayLog.limited(entity,"family","search.started",1200,
                "family search started target={} lastKnownPosition={} mother={} father={} nutrition={} energy={}",
                target==null?null:target.getUUID(),searchAt,mother,father,entity.life().nutrition(),entity.life().energy());
        switch(task) {
            case RECOVER -> { entity.getNavigation().stop();delivery.recoveryBeats(); }
            case BROOD -> {
                var brood=brood();var station=brood!=null?deliveryStation(brood):null;
                if(station!=null){approachDeliveryStation(brood,station);return;}
                if(delivery.walkOutForCatcher(brood))return;
                var meeting=catchRendezvous(brood);
                if(meeting!=null) {
                    if(SurfaceApproach.walkAround(entity,meeting.at(),catcher(entity,brood),1.05))face(meeting.yaw());
                    return;
                }
                // Hold a supported position once the father is near, so his site search does not chase a moving outlet.
                var helper=brood==null?null:catcher(entity,brood);
                if(prioritizesBirth()&&helper!=null&&helper!=entity&&helper.family().midwifeTarget()==entity
                        &&helper.distanceToSqr(entity)<=64&&supported()){SurfaceApproach.halt(entity);return;}
                if(brood!=null&&brood.elapsed>=brood.duration-(brood.kind==FamilyData.BirthKind.STEM?200:2)
                        &&waitsForOwnHat(brood)){catchHat.catchAlone(brood);return;}
                if(laboring()){entity.getNavigation().stop();return;}
                // Out of the nest for the birth (beside a cushion nest, or for the catcher): she stays out.
                if(delivery.outOfNestForTerm()){SurfaceApproach.halt(entity);return;}
                BlockPos home=nestStand();
                var shelter=entity.habitat().shelter();
                if(shelter!=null&&brood!=null&&shelter.prepareLargeBed(entity)){
                    SurfaceApproach.halt(entity);entity.showReaction("chewing_closed","",20);return;
                }
                if(home==null){stop();return;}
                var resting=FamilyNest.restPosition(entity,home);
                if(resting==null){
                    // Her place is not standable right now (another body, a growing belly): keep walking into the bed's
                    // middle rather than standing wherever she is, on its rim; the nest claim picks a new place.
                    var middle=FamilyNest.bedMiddle(entity,home);
                    if(middle!=null&&entity.position().subtract(middle).horizontalDistanceSqr()>.04){
                        GameplayLog.limited(entity,"family","brood_rest_unstandable",200,"brood walking to nest middle reason=rest_place_unstandable nest={} at={}",home,middle);
                        SurfaceApproach.walk(entity,middle,.9);return;
                    }
                }
                if(resting!=null&&entity.distanceToSqr(resting)>.04) { SurfaceApproach.walk(entity,resting,.9); }
                else { entity.getNavigation().stop();entity.getLookControl().setLookAt(entity.getX(),entity.getEyeY(),entity.getZ()-2,10,10); }
            }
            case COURT -> courtship.tickCourt();
            case RECEIVE_COURT -> courtship.tickReceiver();
            case FOLLOW -> {
                if(!entity.life().canRecognize(target)||entity.distanceToSqr(target)<familyRadiusSqr()){stop();return;}
                if(taskTicks%10==1&&!entity.life().navigate(target.blockPosition(),1.15,false))stop();
            }
            case STAY_HOME -> {
                var home=earlyHomeStand();
                if(home==null){stop();return;}
                var at=FamilyNest.restPosition(entity,home);
                if(at==null){stop();return;}
                if(entity.distanceToSqr(at)>.16)SurfaceApproach.walk(entity,at,1);
                else {SurfaceApproach.halt(entity);stop();}
            }
            case FEED_CHILD -> feeding.tickCare();
            case WAIT_CHILD -> {
                entity.getNavigation().stop();entity.getLookControl().setLookAt(target,20,20);
                if(entity.distanceToSqr(target)<4||!entity.life().canRecognize(target))stop();
            }
            case MIDWIFE -> catchHat.tickMidwife();
            case FETCH_HAT -> catchHat.tickFetch();
            case COMFORT -> delivery.tickComfort();
            case CARRY -> { if(!carry.tickHome(taskTicks))stop(); }
            case SEARCH -> {
                if(target!=null) {
                    if(!ordinaryLandFree(entity)||!ordinaryLandFree(target)||!entity.life().canRecognize(target)){stop();return;}
                    if(carry.pickupWanted(target)) {
                        // Lift the baby instead of waiting for it to walk over; a march carries on, a stray goes back to its nest.
                        var step=carry.tickPickup(target,taskTicks);
                        if(step==ParentCarry.Step.DONE){
                            if(carry.purpose()==ParentCarry.Purpose.RETRIEVE||carry.purpose()==ParentCarry.Purpose.NEST_RIM){task=Task.CARRY;target=carry.carried();taskTicks=0;}else stop();
                        } else if(step==ParentCarry.Step.FAILED)stop();
                        return;
                    }
                    if(entity.distanceToSqr(target)<=target.family().familyRadiusSqr()&&entity.hasLineOfSight(target)){stop();return;}
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
        catchHat.stopAction("family_goal_stopped");
        if(task==Task.COMFORT)delivery.pauseComfort();
        boolean recovered=task==Task.RECOVER&&delivery.recoveryTicks()<=0;
        if(task!=Task.NONE&&task!=Task.FOLLOW&&task!=Task.STAY_HOME&&task!=Task.WAIT_CHILD)GameplayLog.limited(entity,"family","task.stop."+task,200,
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
        tag.putLong("Lifespan",GrowthTiming.LIFESPAN);tag.putFloat("AdultScale",adultScale);tag.putFloat("Scale",scale);tag.putFloat("BirthScale",birthScale);tag.putInt("Conceptions",conceptions);tag.putInt("Recovery",recovery);
        tag.putBoolean("Initialized",initialized);tag.putBoolean("AwaitingLanding",awaitingLanding);tag.putBoolean("JuvenileVoice",juvenileVoice);
        tag.putBoolean("Sterile",sterile);tag.putInt("Stimulation",stimulation);
        tag.putBoolean("BabyTalk",babyTalk);tag.putInt("SexDrive",sexDrive);
        tag.putBoolean("BirthMiss",doomed);
        if(awaitingLanding&&launch!=null)tag.put("BirthLaunch",BirthLaunch.save(launch));
        if(awaitingLanding&&launch!=null&&launched)tag.putBoolean("BirthLaunched",true);
        delivery.save(tag);
        catchHat.save(tag);
        tag.putInt("SpentStemCount",spentStemCount);tag.putFloat("SpentStemVariation",spentStemVariation);
        tag.put("CareDecisions",careDecisions.save());
        feeding.save(tag);
        separation.save(tag);
        if(mother!=null)tag.putUUID("Mother",mother);if(father!=null)tag.putUUID("Father",father);if(birthId!=null)tag.putUUID("BirthId",birthId);
        UUID partner=partner();
        if(gestation!=null)tag.putUUID("Gestation",gestation);if(partner!=null)tag.putUUID("Partner",partner);return tag;
    }
    public void load(CompoundTag tag) {
        // A saved body never resumes half of a live two-party handshake.
        task=Task.NONE;target=null;courtship.resetAttempt();
        lifespan=GrowthTiming.LIFESPAN;age=Math.clamp(tag.getLong("Age"),0,GrowthTiming.LIFESPAN);nourishment=Math.max(0,tag.getLong("Nourishment"));
        maturity=GrowthTiming.ADULT_START;stage=stageForAge(age);
        birthScale=tag.getFloat("BirthScale");adultScale=Math.clamp(tag.getFloat("AdultScale"),.8f,1.2f);scale=Math.clamp(tag.getFloat("Scale"),.1f,1.2f);
        conceptions=Math.max(0,tag.getInt("Conceptions"));recovery=Math.max(0,tag.getInt("Recovery"));initialized=tag.getBoolean("Initialized");
        awaitingLanding=tag.getBoolean("AwaitingLanding");juvenileVoice=tag.getBoolean("JuvenileVoice");
        sterile=tag.getBoolean("Sterile");stimulation=Math.clamp(tag.getInt("Stimulation"),0,1200);
        babyTalk=tag.getBoolean("BabyTalk");sexDrive=Math.clamp(tag.getInt("SexDrive"),0,BREEDING_PERIOD_TICKS);
        doomed=tag.getBoolean("BirthMiss")&&awaitingLanding;
        launch=awaitingLanding&&tag.contains("BirthLaunch",Tag.TAG_COMPOUND)
                ?BirthLaunch.load(tag.getCompound("BirthLaunch")):null;
        launched=launch!=null&&tag.getBoolean("BirthLaunched");
        delivery.load(tag);
        catchHat.load(tag);
        // Dry stems are no longer kept on the body.
        spentStemCount=0;spentStemVariation=Math.clamp(tag.getFloat("SpentStemVariation"),.85f,1.15f);
        careDecisions.load(tag.getCompound("CareDecisions"));
        feeding.load(tag);
        separation.load(tag);
        mother=tag.hasUUID("Mother")?tag.getUUID("Mother"):null;father=tag.hasUUID("Father")?tag.getUUID("Father"):null;
        birthId=tag.hasUUID("BirthId")?tag.getUUID("BirthId"):null;gestation=tag.hasUUID("Gestation")?tag.getUUID("Gestation"):null;partner=tag.hasUUID("Partner")?tag.getUUID("Partner"):null;
        stop();entity.syncFamily();
    }
}
