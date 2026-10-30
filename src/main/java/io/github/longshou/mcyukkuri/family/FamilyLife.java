package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.life.TravelRange;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.intent.Conductor;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Gesture;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.action.Launch;
import io.github.longshou.mcyukkuri.motor.action.PlaceProp;
import io.github.longshou.mcyukkuri.motor.action.TakeProp;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.motor.surface.BedContainer;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.settlement.SettlementSeasons;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;
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

/** Individual growth and interruptible family goals; durable births live in FamilyData, the near-term birth in {@link LiveBirth}. */
public final class FamilyLife {
    private static final ResourceLocation GROWTH_SPEED=MCYukkuri.id("growth_speed");
    /** Existing stimulant/normal breeding-period duration; a project parameter, not a source-game timer. */
    public static final int BREEDING_PERIOD_TICKS = 1200;
    /** The existing close-follow baseline for a dependent land child; boldness and attachment adjust it at the decision boundary. */
    private static final double FAMILY_FOLLOW_DISTANCE_SQR = 2.25, FAMILY_FOLLOW_DISTANCE = 1.5,
            FAMILY_SEARCH_RANGE = 48;
    public enum Stage { BABY, JUVENILE, ADULT }
    enum Task { NONE, COURT, RECEIVE_COURT, BROOD, FOLLOW, WAIT_CHILD, SEARCH, FEED_CHILD, STAY_HOME, FETCH_HAT, CARRY, COMFORT }
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
    /** A kit that came down on a nest's soft rim tumbles inward up to RIM_ROLLS times (transient, from the landing tick). */
    private int rimRolls;
    private static final int RIM_ROLLS=3;
    /** The committed land-birth impulse is saved while airborne; settled readouts remain transient. */
    private BirthLaunch.Plan launch;
    private boolean launched;
    private String landingOutcome="";
    private double landingFromAim=Double.NaN,landingImpactVy,airVy;
    private final ParentCarry carry;
    private Task task=Task.NONE;
    private YukkuriEntity target;
    private Vec3 searchAt;
    private final CareDecisionLedger careDecisions;
    private final FamilyFeeding feeding;
    private final FamilySeparation separation;
    private final Courtship courtship;
    /** The near-term birth this mother conducts (saved as {@code Birth}). */
    private final Conductor birth;
    /** A hurt newborn's fall (bump), a parent's comfort of it, and the hat errand of an orphaned catch hat; all transient. */
    private boolean bumped;
    private int bumpTicks, comfortTicks, comfortNear;
    private UUID comfortBaby;
    private static final int COMFORT=200;
    private PlacedHatEntity choreHat;
    private IntentRunner chore;
    /** A failed hat errand waits this long before the same body tries again (bounded retries, never a busy loop). */
    private long choreRetry;
    public FamilyLife(YukkuriEntity entity) {
        this.entity=entity;
        carry=new ParentCarry(entity);
        careDecisions=new CareDecisionLedger(entity);
        feeding=new FamilyFeeding(this);
        separation=new FamilySeparation(this);
        courtship=new Courtship(this);
        birth=new Conductor(entity,"Birth");
    }
    /** Land transport of dependent babies (mouth, brim, tails or braid); see {@link ParentCarry}. */
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
    public String describe() {
        return "task=" + task + " ticks=" + taskTicks
                + " target=" + (target == null ? "none" : "#" + target.getId())
                + " search=" + (searchAt == null ? "none" : searchAt)
                + " court={" + courtship.describe() + "}"
                + " separation={" + separation.describe() + "} nest={" + FamilyNest.describe(entity) + "}" + " radius=" + Math.sqrt(familyRadiusSqr()) + " care={" + careDecisions.describe() + "} feeding={" + feeding.describe() + "} birth={" + (liveBirth() == null ? "none" : liveBirth().describe()) + "} carry={" + carry.describe() + "}";
    }
    ServerLevel level() { return (ServerLevel)entity.level(); }
    YukkuriEntity entity() { return entity; }
    Courtship courtship() { return courtship; }
    /** Observe movement ownership without advancing the drawn birth launch. */
    public boolean launchInFlight(){ return entity.motor().current() instanceof Launch; }
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
    /** Care food from elsewhere (a stored portion of her hat): held, saved and shown like the feeding's own (false: one is held). */
    public boolean holdCareFood(ItemStack one) { return feeding.hold(one); }
    /** The held care food chewed for {@code child} (mash when it needs mash); the chewing time is the caller's ({@link #CHEW_TICKS}). */
    public void chewCareFood(YukkuriEntity child) { feeding.chew(child); }
    /** The held care food into {@code child}'s meal; the caller has seen their mouths meet (FamilyFeedingContact). */
    public boolean handCareFood(YukkuriEntity child) { return feeding.hand(child); }
    /** The held care food back where {@code into} keeps it (a hat's hold); false when it is refused and stays held. */
    public boolean putBackCareFood(Predicate<ItemStack> into) { return feeding.putBack(into); }
    public static final int CHEW_TICKS = FamilyFeeding.CHEW_TICKS;
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
    public boolean active() { return task!=Task.NONE||laboring()||inBirthBeat()||launchInFlight()||comfortTicks>0; }
    /** The current beat of a running birth casts this body (its other beats leave it free). */
    private boolean inBirthBeat() { var role=Conductor.roleOf(entity);return role!=null&&role.engaged(entity); }
    /** Her birth's current beat casts her as the mother: she keeps her place (a stem hat's near brim may lie under her front). */
    public boolean holdsBirthStation() { var role=Conductor.roleOf(entity);return role!=null&&LiveBirth.MOTHER.equals(role.role())&&role.engaged(entity); }
    /**
     * An everyday family errand (waiting for, following or looking for a child, staying near the nest) gives way to comforting a hurt kin
     * ({@link io.github.longshou.mcyukkuri.social.WoundComfort}); births, feeding, courtship, brooding and recovery do not.
     * Before this the parent's standing "wait for the child" errand lasted as long as the child wandered, and the parent never noticed the wound.
     */
    public boolean yieldsToComfort() {
        return !laboring()&&!inBirthBeat()&&!launchInFlight()&&comfortTicks==0
                &&(task==Task.NONE||task==Task.WAIT_CHILD||task==Task.FOLLOW||task==Task.STAY_HOME||task==Task.SEARCH);
    }
    public String activity() {
        var action=entity.motor().current();
        if(action instanceof PlaceProp||action instanceof TakeProp)return action.pose().clip();
        if(task==Task.COMFORT&&comfortTicks>0&&comfortNear>=10)return Behaviors.NUZZLE;
        if(laboring())return Behaviors.LABOR;
        var role=Conductor.roleOf(entity);
        if(role!=null&&role.engaged(entity)){
            String clip=role.choreography().present(entity.getUUID());
            if(clip!=null&&!clip.isEmpty())return clip;
            // The catcher holding the brim of the hat she set down, or (no hat) cheering beside the outlet, once she stands there.
            if(LiveBirth.CATCHER.equals(role.role())&&role.choreography().host()!=entity&&!(action instanceof Walk)&&role.choreography() instanceof LiveBirth b&&b.attending())
                return PlacedHatEntity.find(entity)!=null?Behaviors.HAT_HOLD:Behaviors.MIDWIFE;
            if(LiveBirth.MOTHER.equals(role.role())&&brood()!=null)return Behaviors.TEND_BROOD;
        }
        return switch(task) {
            case COURT, RECEIVE_COURT -> courtActivity(); case BROOD -> Behaviors.TEND_BROOD; case FOLLOW -> Behaviors.FOLLOW_PARENT;
            case WAIT_CHILD -> Behaviors.WAIT_CHILD; case SEARCH -> carry.lifting()?Behaviors.CARRY_LIFT:Behaviors.SEARCH_FAMILY;
            case FEED_CHILD -> feeding.activity();
            case STAY_HOME -> Behaviors.STAY_HOME; default -> awaitingLanding?Behaviors.NEWBORN_FALL:Behaviors.IDLE;
        };
    }
    /** Why the current delivery is held up (empty when it is not): the birth's own hold, or the last refusal of the ledger. */
    public String birthBlock() {
        var b=brood();if(b==null||b.cancelled||b.complete())return "";
        var running=liveBirth();return running!=null&&!running.hold().isEmpty()?running.hold():FamilyData.get(level()).birthBlock(b);
    }
    /** A live delivery is under way (server side). */
    public boolean laboring() { var b=entity.level().isClientSide?null:brood();return b!=null&&!b.cancelled&&!b.complete()&&b.kind==FamilyData.BirthKind.LIVE&&b.elapsed>=b.duration; }
    /** The mother this body helps deliver while it plays her birth's catcher (the hat and cheer clips follow her labour). */
    public YukkuriEntity midwifeTarget() {
        var role=Conductor.roleOf(entity);var mom=role==null?null:role.choreography().host();
        return LiveBirth.CATCHER.equals(role==null?null:role.role())&&mom!=entity?mom:null;
    }
    /** A Marisa father near enough to notice (hatted, or hatless only to cheer), else a hatted Marisa mother herself. */
    public static YukkuriEntity catcher(YukkuriEntity mother,FamilyData.Brood brood) { return LiveBirth.catcher(mother,brood); }
    /** Turns toward {@code yaw} by at most the body's own turn rate (Face); courtship's step-wise fallback. */
    void face(float yaw){Motor.face(entity,Mth.approachDegrees(entity.getYRot(),yaw,(float)BodyAbility.of(entity).turnRate()));}
    /** The birth this mother conducts, while it runs. */
    LiveBirth liveBirth() { return birth.choreography() instanceof LiveBirth running?running:null; }
    /** Danger, commands, operations, urgent needs and meals still come before any birth duty. */
    private boolean birthFree() {
        return !entity.level().isClientSide&&entity.isAlive()&&!entity.isNoAi()&&!entity.isPanicking()&&!entity.isCommandPlaying()&&(!entity.isPassenger()||onHost())
                &&!entity.operations().active()&&!entity.life().hasUrgentNeed()&&entity.life().mealTicks()==0&&!awaitingLanding;
    }
    /** The birth role this body plays in the current beat (BirthGoal.canUse); when the goal yields, the beat's deadline decides the cost.
     *  A catcher watching the young yields only to danger, a hunger emergency (birthFree) or a young needing care it can give:
     *  a hurt newborn to comfort, an urgently hungry young of its own. */
    public Conductor.Role birthRole() {
        var role=birthFree()?Conductor.roleOf(entity):null;
        if(role==null||!role.engaged(entity))return null;
        return LiveBirth.CATCHER.equals(role.role())&&role.choreography().host()!=entity&&role.choreography() instanceof LiveBirth b&&b.watching()
                &&(comfortTicks>0||hasUrgentlyHungryChild())?null:role;
    }
    /** BirthGoal's tick for a role with nothing to run: hold still; the mother's postpartum beats, the catcher's eyes on the birth. */
    public void holdBirthRole(Conductor.Role role) {
        entity.getNavigation().stop();
        if(LiveBirth.MOTHER.equals(role.role())&&"linger".equals(role.choreography().beat()))recoveryBeats((int)(level().getGameTime()-role.choreography().beatTick()));
        else watchBirth(role);
    }
    /** The catcher's eyes while it watches: on the outlet through the labour, then on the young in the hat. */
    public void watchBirth(Conductor.Role role) {
        var mom=role.choreography().host();
        if(mom==entity||!(role.choreography() instanceof LiveBirth b)||!b.watching())return;
        var hat=b.attending()?null:b.placedHat();var at=hat!=null?hat.position():LiveBirthGeometry.position(mom,0);
        entity.getLookControl().setLookAt(at.x,at.y+.1,at.z,20,20);
    }
    /** A role's intent starts in BirthGoal: the father who notices the labour shows it (project original staging). */
    public void birthIntentStarted(Conductor.Role role,Intent intent) { if(LiveBirth.CATCHER.equals(role.role())&&"rendezvous".equals(intent.id()))entity.showReaction("surprise","caregiver",50); }
    /** BirthGoal stops: only a family task it took over is stopped, so a finished role leaves the next errand no pause. */
    public void stopBirthGoal() { if(task!=Task.NONE)stop(); }
    /** A birth role, or comforting a hurt newborn, comes before ordinary household routines. Pure observation. */
    public boolean prioritizesBirth() { return birthRole()!=null||comfortTicks>0&&birthFree(); }
    /** In a drawn birth launch: the body is not on its own feet. */
    public boolean flying(){return launchInFlight();}
    /** The hat does not collide with its own incoming passenger: a newborn launched into it. */
    public boolean boardingThrough(PlacedHatEntity hat){ return awaitingLanding&&launch!=null&&hat!=null&&hat.getUUID().equals(launch.hat()); }
    /** The body's own decision-tick owner of its birth actions (the launch, the rim roll, the impact bounce, the welcome hop). */
    private static final MotorOwner BIRTH_BODY=new MotorOwner(){@Override public String ownerId(){return "birth_body";}@Override public boolean active(){return true;}};
    /** The one drawn birth impulse, kept (and saved) exactly as drawn; the body leaves on its own first decision tick. */
    void launch(BirthLaunch.Plan plan,FamilyData.Brood brood,int index,PlacedHatEntity hat){
        launch=plan;launched=false;
        GameplayLog.event(entity,"family","birth launch brood={} index={} kind={} target={} hat={} aim={} start={} room={} nominal={} push={} yaw={} velocity={}",
                brood.id,index,brood.kind,plan.target(),hat==null?null:hat.getUUID(),plan.aim(),plan.start(),
                Double.isNaN(plan.room())?"n/a":String.format(Locale.ROOT,"%.2f",plan.room()),String.format(Locale.ROOT,"%.3f",plan.nominal()),
                String.format(Locale.ROOT,"%.2f",plan.push()),String.format(Locale.ROOT,"%.1f",plan.yaw()),plan.velocity());
    }
    /** The body's own decision tick for its birth (from {@code travel}): the drawn launch leaves once, the first real contact
     *  is judged (a rim roll is Gesture ROLL_IN, a ground bounce Launch), a relative hops to greet a newborn. True: the tick is the launch's. */
    public boolean birthTravel(){
        if(entity.level().isClientSide||entity.motor().current()!=null)return false;
        if(!awaitingLanding){
            var hello=greetHop();
            if(hello!=null&&level().getGameTime()-hello.tick()==30)entity.motor().run(BIRTH_BODY,new Gesture(Gesture.Kind.HOP_SMALL));
            return false;
        }
        if(launch!=null&&!launched&&!entity.onGround()&&!entity.isInWaterOrBubble()&&!entity.isPassenger()){
            launched=true;
            if(entity.motor().run(BIRTH_BODY,new Launch(launch.velocity(),"birth").envelope(HatGeometry::envelope))){entity.getNavigation().stop();return true;}
            var refusal=entity.motor().last();
            GameplayLog.event(entity,"family","birth launch refused lastReason={} shortfall={}; the body drops where it is",refusal==null?"n/a":refusal.reason(),refusal==null?0:refusal.shortfall());
            return false;
        }
        if(entity.onGround()||entity.isInWaterOrBubble()||entity.isPassenger())land();
        return false;
    }
    public boolean newbornLaunching(){ return freeFalling()||launchInFlight(); }
    /** A newborn between the outlet (or stem) and its first contact: nothing steers or shoves it. */
    public boolean freeFalling(){ return awaitingLanding&&!entity.onGround()&&!entity.isPassenger()&&!entity.isInWaterOrBubble(); }
    /** Where the kit came down: outcome, distance from the planned landing point and the vertical speed on impact. */
    public String landingOutcome(){ return landingOutcome; }
    public double landingFromAim(){ return landingFromAim; }
    public double landingImpactVy(){ return landingImpactVy; }
    public String landingStatus(){
        if(landingOutcome.isEmpty())return "";
        return landingOutcome+" push="+(launch==null?"n/a":String.format(Locale.ROOT,"%.2f",launch.push()))
                +" fromAim="+(Double.isNaN(landingFromAim)?"n/a":String.format(Locale.ROOT,"%.2f",landingFromAim));
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
        if(!entity.isAlive()){if(entity.isVehicle())entity.motor().interrupt("carrier_dead");return;}
        carry.tick();
        if(!entity.isNoAi())tickAftermathBurden();
        var finished=brood();
        if(finished!=null&&!finished.cancelled&&finished.kind==FamilyData.BirthKind.STEM&&finished.complete())finishStem(finished);
        else if(finished!=null&&!finished.cancelled&&finished.kind==FamilyData.BirthKind.LIVE&&finished.complete())delivered(finished);
        if(!entity.subspecies().canReproduce()) {
            // Retire an invalid maternal gestation while retaining born children and ancestry records.
            var pending=brood();
            if(pending!=null&&!pending.cancelled&&!pending.complete())FamilyData.get(level()).cancel(pending,entity,"mother_cannot_reproduce");
            gestation=null;stimulation=sexDrive=0;
        }
        if(entity.isNoAi()) {
            // Physics still lands a NoAI body and its own travel judges the contact; confirm it without advancing growth or a birth.
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
        if(recovery>0)recovery--;if(wait>0)wait--;if(stimulation>0)stimulation--;
        if(!adult()||sterile()||elder())sexDrive=0;
        if(sexDrive>0){sexDrive--;if(sexDrive%200==0)entity.life().spendNutrition(1);}
        // Natural lifetime is authoritative even while the body is boarded or in a flight arc.
        if(FamilyConfig.AGING.get()&&age>=lifespan) {
            GameplayLog.limited(entity, "growth", "lifespan", 1200, "lifespan reached age={} lifespan={}", age, lifespan);
            entity.body().terminalKill(level().damageSources().generic(),false,false,"old_age");return;
        }
        if(entity.tickCount%100==0)FamilyData.get(level()).register(entity);
        if(bumpTicks>0){bumpTicks--;if(bumpTicks%8==0)BirthStaging.tears(entity);if(bumpTicks==100)BirthStaging.whimper(entity);}
        if(awaitingLanding&&!entity.onGround())airVy=entity.getDeltaMovement().y;
        if(launchInFlight())return;
        if(freeFalling())entity.getNavigation().stop();
        if(awaitingLanding)return;
        if(birthId!=null)FamilyData.get(level()).landed(entity);
        greet();
        feeding.settleCareFood();
        if(entity.tickCount%40==0)retryMotherBurrow();
        if(entity.isPanicking()||entity.isCommandPlaying()||(task!=Task.FEED_CHILD&&entity.life().hasUrgentNeed()))stop();
        if(prioritizesBirth()) {
            if(entity.life().isSleeping())entity.life().interrupt();
            if(entity.dailyLife().active())entity.dailyLife().stop();
            if(entity.playLife().active())entity.playLife().stop();
        }
        var brood=brood();
        boolean expecting=brood!=null&&!brood.cancelled&&!brood.complete()&&brood.kind!=FamilyData.BirthKind.EGG;
        // Labour wakes a sleeping mother (a night pregnancy used to wait until morning, or for ever in a hat).
        if(expecting&&brood.elapsed>=brood.duration-60&&entity.life().isSleeping())entity.life().interrupt();
        // An expecting mother shows it at a glance: a stem on her head or a swelling belly, and a small heart now and then.
        if(expecting&&brood.incidentSource==null&&entity.tickCount%60==0)
            level().sendParticles(ParticleTypes.HEART,entity.getX(),entity.getY()+entity.getBbHeight()+.15,entity.getZ(),1,.12,.04,.12,0);
        // The gestation clock runs wherever she is; its last APPROACH_LEAD ticks are the birth's, which the conductor advances.
        // A birth still running for an older brood never holds a new one back: it ends, and its hat goes to the hat errand.
        if(expecting&&liveBirth() instanceof LiveBirth old&&!brood.id.equals(old.broodId()))birth.stop("brood_replaced");
        if(expecting&&!birth.active()){
            if(brood.elapsed>=brood.duration-LiveBirth.APPROACH_LEAD)birth.start(LiveBirth.cast(this,brood));
            else gestate(brood,true);
        } else if(gestation!=null&&(brood==null||brood.cancelled||brood.complete()&&brood.kind!=FamilyData.BirthKind.STEM))gestation=null;
        birth.tick();
    }
    /** One loaded tick of gestation (the brood's own clock: no body writes): "" when it ran, else why it waits. */
    String gestate(FamilyData.Brood b,boolean sited){
        boolean stem=b.kind==FamilyData.BirthKind.STEM;
        if(stem?entity.operations().active()||entity.isPanicking()||entity.isOnFire()||entity.isInWaterOrBubble()||entity.isCommandPlaying()
                ||entity.life().nutrition()<30||entity.body().condition().blocksBreedingApproach()||entity.life().mealTicks()>0||entity.life().isSleeping()
                :!readyForDelivery())return "mother_not_ready";
        if(!sited&&b.elapsed>=b.duration-LiveBirth.NEST_GRACE)return "no_place_for_term";
        if(!stem&&!bellyRoom(b))return "no_room_for_belly";
        if(b.elapsed>=b.duration+(stem?40:0))return "";
        if(++b.elapsed%200==0)entity.life().spendNutrition(1);
        // The first tick of actual growth is also the first visible sprout: the victim realizes it (native seisian wording).
        if(b.elapsed==1&&b.incidentSource!=null)entity.showReaction("confused","aftermath_realize",160);
        if(b.elapsed==b.duration&&!stem)GameplayLog.event(entity,"family","delivery labor started brood={} kind={} children={}",b.id,b.kind,b.children.size());
        FamilyData.get(level()).setDirty();return "";
    }
    /** The next belly still fits where she stands. */
    boolean bellyRoom(FamilyData.Brood b){
        float next=LiveBirthGeometry.belly(Math.min(1,(b.elapsed+1f)/b.duration),b.children.size()-b.unborn(),b.children.size());
        return level().noCollision(entity,LiveBirthGeometry.bodyDimensions(entity,entity.growthScale(),next).scale(entity.getScale()).makeBoundingBox(entity.position()));
    }
    /** The last live child is out: the brood leaves her and the reproduction cooldown starts; the postpartum rest is the birth's beat. */
    void delivered(FamilyData.Brood b){
        if(gestation==null||!gestation.equals(b.id))return;
        gestation=null;recovery=Math.max(recovery,FamilyConfig.RECOVERY.get());
        GameplayLog.event(entity,"family","delivery complete brood={} recovery={} newborn={}",b.id,LiveBirthGeometry.RECOVERY,b.children.getLast().id);
    }
    /** Visible pregnancy and the brief post-delivery recovery of the mother's belly. */
    public float liveBelly() {
        var p=brood();
        if(p!=null&&!p.cancelled&&!p.complete()&&p.kind==FamilyData.BirthKind.LIVE)return LiveBirthGeometry.belly(p.progress(),p.children.size()-p.unborn(),p.children.size());
        var running=entity.level().isClientSide?null:liveBirth();return running==null?0:.22f*running.recoveryLeft()/LiveBirthGeometry.RECOVERY;
    }
    public boolean readyForDelivery(){
        return !entity.level().isClientSide&&entity.isAlive()&&!entity.isNoAi()&&(supported()||onHost())&&!entity.operations().active()&&!entity.dailyLife().active()
                &&!entity.isPanicking()&&!entity.isCommandPlaying()&&!entity.life().hasUrgentNeed()&&!entity.life().isSleeping()&&entity.life().mealTicks()==0
                &&entity.body().condition().readyForDelivery();
    }
    /** The newborn of the birth this body's partner or mother conducts, while its greeting lasts; read from that birth, never kept. */
    private LiveBirth.Welcome greeting(){
        for(var id:new UUID[]{partner(),mother})if(id!=null&&level().getEntity(id) instanceof YukkuriEntity parent&&parent!=entity
                &&parent.distanceToSqr(entity)<36&&parent.family().liveBirth() instanceof LiveBirth running&&running.welcome(level().getGameTime()) instanceof LiveBirth.Welcome hello
                &&!hello.baby().equals(entity.getUUID()))return hello;
        return null;
    }
    /** The newborn this relative greets with a small hop, from 30 ticks after its landing for 10 ticks (unhurt, this body on its feet). */
    LiveBirth.Welcome greetHop(){
        var hello=greeting();long since=hello==null?-1:level().getGameTime()-hello.tick();
        return since>=30&&since<40&&entity.onGround()&&!entity.isPassenger()&&Gesture.smallHopImpulse(entity)>0
                &&level().getEntity(hello.baby()) instanceof YukkuriEntity baby&&!baby.family().bumped()?hello:null;
    }
    /** The father and the older siblings turn to a landed newborn and answer its first greeting with the classic one (L36: a
     *  heard ゆっくりしていってね is echoed); a hurt one frightens them and the mate comes to comfort it. */
    private void greet(){
        if(!(greeting() instanceof LiveBirth.Welcome hello)||entity.life().isSleeping()||!(level().getEntity(hello.baby()) instanceof YukkuriEntity baby)||!baby.isAlive())return;
        long since=level().getGameTime()-hello.tick();boolean hurt=baby.family().bumped(),mate=partner()!=null&&partner().equals(baby.family().mother());
        entity.getLookControl().setLookAt(baby,20,20);
        if(since==0&&mate&&!hurt)entity.showReaction("happy","caregiver",LiveBirthGeometry.REST);
        if(since==6&&mate&&hurt&&entity.distanceToSqr(baby)<256)comfort(baby);
        if(since!=30||!entity.onGround()||entity.isPassenger()||flying())return;
        if(hurt){entity.mind().felt(EmotionEvent.FRIGHTENED);entity.showReaction("surprise","afraid",50);BirthStaging.sweat(entity);}
        else{entity.showReaction("happy","",40);entity.speak("newborn");BirthStaging.affection(entity);}
    }
    /** The first real contact decides: the hat it rides, the water, a nest's supported floor, its soft rim, or the bare ground. */
    private void land() {
        boolean pad=FamilyNest.cushions(entity),hat=entity.getVehicle()!=null;
        var rim=entity.isInWaterOrBubble()||pad?null:hat?Occupancy.hollow(entity):FamilyNest.rimCentre(entity);
        if(rim!=null&&rimRolls<RIM_ROLLS){
            // On the soft rim or bolster it tumbles down the inner slope toward the floor (project design); on a hat's brim it tumbles
            // into the hollow when there is room, else off the outer side (Gesture ROLL_IN); the next contact decides again.
            rimRolls++;entity.fallDistance=0;
            GameplayLog.event(entity,"family","newborn on {} rim rolls inward roll={} at={} centre={}",hat?"hat":"nest",rimRolls,entity.position(),rim);
            entity.motor().run(BIRTH_BODY,new Gesture(Gesture.Kind.ROLL_IN,rim));return;
        }
        awaitingLanding=false;
        var bed=entity.getVehicle()==null&&!entity.isInWaterOrBubble()?carryingBed():null;
        String outcome=Attach.of(entity) instanceof AttachState.OnHost?"HAT":entity.isInWaterOrBubble()?"WATER":pad||bed!=null?"CUSHION":rim!=null?"RIM":"GROUND";
        landingOutcome=outcome;landingImpactVy=airVy;
        landingFromAim=launch==null||launch.aim()==null?Double.NaN:Math.hypot(entity.getX()-launch.aim().x,entity.getZ()-launch.aim().z);
        var parent=mother!=null&&level().getEntity(mother) instanceof YukkuriEntity found?found:null; // the newborn has no exhibit tag
        if(outcome.equals("CUSHION")||outcome.equals("RIM")){
            // On the nest floor, or still on its soft rim after rolling: unhurt; a rim kit stays until it crawls or is carried in.
            entity.fallDistance=0;GameplayLog.event(entity,"family","newborn landed on nest {} rimRolls={}",outcome.equals("RIM")?"soft rim":"cushion",rimRolls);
        }else if(outcome.equals("WATER")&&launch!=null){
            // Missed into the water: the newborn cannot swim and drowns by immersion unless a parent brings it out.
            BirthStaging.thud(entity);entity.waterDistress().plunge();
            BirthStaging.caption(parent,"漏接落水：新生儿掉进水里，无法自救，须亲油及时救起，否则溺亡");return;
        }else if(outcome.equals("GROUND")){
            // The uncushioned fall (project original): a thud, a small bounce, a real wound through the ordinary injury record, a cry; never lethal.
            bumped=true;bumpTicks=140;BirthStaging.thud(entity);BirthStaging.whimper(entity);
            float lost=Math.min(2f,entity.getHealth()-1f),healthBefore=entity.getHealth();
            if(lost>0)entity.hurt(level().damageSources().fall(),lost);
            GameplayLog.event(entity,"family","birth impact injury lost={} health={}->{}",String.format(Locale.ROOT,"%.1f",Math.max(0f,lost)),healthBefore,entity.getHealth());
            // The bounce is this body's own small-hop impulse (Gesture HOP_SMALL), thrown from its own decision tick.
            double lift=Gesture.smallHopImpulse(entity);var motion=entity.getDeltaMovement();
            if(lift>0)entity.motor().run(BIRTH_BODY,new Launch(new Vec3(motion.x,lift,motion.z),"birth_impact"));
            entity.mind().felt(EmotionEvent.WOUND_DISTRESS);entity.showReaction("cry","wounded",140);
            BirthStaging.caption(parent,"漏接：新生儿重重摔在地上，弹了一下，受了摔伤哭了起来（进入伤势系统，不致命，可照料）");return;
        }
        // The greeting waits until first_wake has opened the eyes and reached its happy face (24 ticks), not the landing tick.
        entity.showReactionDelayed("happy","newborn",100,24);
    }
    public boolean bumped() { return bumped; }
    /** Postpartum and comfort turns as a motor owner, active only while one of those beats runs. */
    private final MotorOwner beats=new MotorOwner(){@Override public String ownerId(){return "family_birth_beats";}
        @Override public boolean active(){return comfortTicks>0||liveBirth()!=null&&"linger".equals(liveBirth().beat());}};
    /** Her postpartum rest (project original; the nuzzle is Wiki E06): she turns to the newborn, startles at a hurt one, nuzzles and licks it. */
    private void recoveryBeats(int age){
        var b=liveBirth()==null?null:liveBirth().brood();YukkuriEntity baby=null;
        if(b!=null)for(var child:b.children)if(child.born&&level().getEntity(child.id) instanceof YukkuriEntity found&&found.isAlive())baby=found;
        if(baby==null)baby=level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(5),other->other!=entity&&other.isAlive()
                &&entity.getUUID().equals(other.family().mother())&&!other.family().adult()).stream().min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
        if(baby==null)return;
        if(age>=24&&age<=150){entity.getLookControl().setLookAt(baby,20,20);if(age>=40)turnTo(baby);}
        if(age==24&&baby.family().bumped()){
            entity.mind().felt(EmotionEvent.FRIGHTENED);entity.showReaction("surprise","afraid",50);BirthStaging.sweat(entity);BirthStaging.caption(entity,"母油见孩子摔伤，惊慌地转向它");
        }
        if(age==88){entity.showReaction("happy","nuzzle",60);BirthStaging.affection(entity);if(baby.family().bumped())BirthStaging.caption(entity,"母油蹭脸安抚摔伤的孩子，随后轻舔");}
        if(age==116){entity.showReaction("open_smile","",30);BirthStaging.affection(entity);}
    }
    /** One bounded turn toward the baby (Face) from a decision tick; a running turn of these beats is left alone. */
    private void turnTo(YukkuriEntity baby){
        float yaw=(float)(Mth.atan2(baby.getZ()-entity.getZ(),baby.getX()-entity.getX())*Mth.RAD_TO_DEG)-90;var motor=entity.motor();
        if(motor.current() instanceof Face&&motor.owner()!=null&&beats.ownerId().equals(motor.owner().ownerId())||Math.abs(Mth.wrapDegrees(yaw-entity.getYRot()))<=Face.FACE_ARRIVED)return;
        motor.run(beats,new Face(yaw));
    }
    /** A parent hurries to a newborn that fell hard, nuzzles it and licks it (project original; nuzzling is Wiki E06). */
    public void comfort(YukkuriEntity baby){
        if(entity.level().isClientSide||baby==null||baby==entity||!adult()||entity.isNoAi()||!entity.isAlive())return;
        comfortBaby=baby.getUUID();comfortTicks=COMFORT;comfortNear=0;
        entity.mind().felt(EmotionEvent.FRIGHTENED); entity.showReaction("surprise","afraid",40);
    }
    /** Only the COMFORT task may advance contact or steer. */
    private void tickComfort(){
        if(comfortTicks--==COMFORT)BirthStaging.caption(entity,"亲油走向哭泣的新生儿");
        var baby=comfortBaby!=null&&level().getEntity(comfortBaby) instanceof YukkuriEntity found&&found.isAlive()?found:null;
        if(baby==null||!adult()){comfortTicks=0;comfortNear=0;return;}
        double near=(entity.getBbWidth()+baby.getBbWidth())*.5+.12;
        if(entity.position().subtract(baby.position()).multiply(1,0,1).length()>near+.2||Math.abs(entity.getY()-baby.getY())>.35||!entity.hasLineOfSight(baby)
                ||!supported()||!baby.family().supported()){
            comfortNear=0;entity.getLookControl().setLookAt(baby,20,20);
            var stand=SurfaceApproach.beside(entity,baby,near,.35);
            if(stand==null)SurfaceApproach.halt(entity);else SurfaceApproach.walkAround(entity,stand,baby,1.1);
            return;
        }
        SurfaceApproach.halt(entity);turnTo(baby);comfortNear++;
        if(comfortNear==10){entity.showReaction("closed_smile","nuzzle",60);BirthStaging.affection(entity);BirthStaging.caption(entity,"蹭脸安抚");}
        if(comfortNear==70){entity.showReaction("open_smile","",40);BirthStaging.affection(entity);BirthStaging.caption(entity,"轻轻舔舐安抚");}
        if(comfortNear>=130){comfortTicks=0;comfortNear=0;}
    }
    /** A catch hat this body set down that no running birth uses any more, or a dead owner's hat with this body's own young aboard. */
    private PlacedHatEntity hatChore(){
        if(Conductor.roleOf(entity)!=null||level().getGameTime()<choreRetry)return null;
        var own=PlacedHatEntity.find(entity);
        if(own!=null&&own.catches())return own;
        return level().getEntitiesOfClass(PlacedHatEntity.class,entity.getBoundingBox().inflate(48),hat->hat.catches()&&hat.ownerYukkuri()!=null&&!hat.ownerYukkuri().equals(entity.getUUID())
                        &&FamilyData.get(level()).person(hat.ownerYukkuri()) instanceof FamilyData.Person owner&&owner.status==FamilyData.Status.DEAD
                        &&hat.getPassengers().stream().anyMatch(rider->rider instanceof YukkuriEntity young&&young.isAlive()&&!young.family().adult()&&Bonds.parentOf(entity,young)))
                .stream().min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
    }
    /** The hat errand: unload the young this body may unload (a survivor only its own), then take its own hat back on. */
    private void tickHatChore(){
        var hat=choreHat;
        if(hat==null||hat.isRemoved()||!hat.catches()){stop();return;}
        boolean own=hat.ownsPhysicalHat(entity);
        if(chore==null){
            var unload=new Unload(hat,entity,!own);
            Intent next=!unload.satisfied()?unload:own?LiveBirth.takeHat(entity,hat,level().getGameTime()+600):null;
            if(next==null){stop();return;}
            chore=new IntentRunner(entity,next,"hat_chore");chore.start();
            GameplayLog.limited(entity,"family","catch.cleanup",200,"catch cleanup started hat={} passengers={} foreign={}",hat.getUUID(),hat.getPassengers().size(),!own);
        }
        chore.tick();
        if(chore.result()==IntentRunner.Result.RUNNING)return;
        var ended=chore;chore=null;
        GameplayLog.event(entity,"family","catch cleanup {} hat={} passengers={} foreign={} step={} result={}",ended.result()==IntentRunner.Result.DONE?"advanced":"interrupted",
                hat.getUUID(),hat.getPassengers().size(),!own,ended.intent().id(),ended.result());
        if(ended.result()!=IntentRunner.Result.DONE){choreRetry=level().getGameTime()+600;stop();}
    }
    /** The nest bed whose real supported floor carries this body (INSIDE_SUPPORTED), or null on bare ground. */
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
        endBreedingPeriod();gestation=brood;conceptions++;recovery=FamilyConfig.RECOVERY.get();
        // A conception inside a still-running period resolves it: the relief beat replaces the plain expecting reaction.
        if(resolvedHeat)entity.agitation().markRelieved();
        entity.setPersistenceRequired(); }
    /** Preconditions of a checked aftermath reservation. Existing pregnancies and partners are never replaced. */
    public boolean canReceiveAftermath() {
        return !entity.level().isClientSide && entity.isAlive() && !entity.isNoAi() && adult() && !sterile() && !elder()
                && !entity.body().dying() && entity.body().coreIntact()
                && !entity.profile().houseBody() && !entity.profile().dangerToYukkuri()
                && gestation==null && recovery==0 && !awaitingLanding && !birthDuty();
    }
    /** Called only after a checked STEM reservation; the source gains no partner or care role. */
    void receiveAftermath(UUID brood) {
        endBreedingPeriod();gestation=brood;conceptions++;recovery=FamilyConfig.RECOVERY.get();
        spentStemCount=0;
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
        endBreedingPeriod();gestation=brood;conceptions++;recovery=FamilyConfig.RECOVERY.get();spentStemCount=0;
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
    private void finishStem(FamilyData.Brood brood) {
        var drops=ShedStemDrops.get(level());drops.queue(entity,brood);
        recovery=Math.max(recovery,FamilyConfig.RECOVERY.get());
        if(!drops.resolve(level(),brood.id))return;
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
    /** Birth duties that come before first-stool licking and mash feeding: a part in a running birth, or a hurt newborn to comfort. */
    public boolean birthDuty(){return inBirthBeat()||comfortTicks>0;}
    /** Standing on a host (her own boat): its surface carries her as a floor does, for labour and the birth roles (never for courtship). */
    private boolean onHost(){return Attach.of(entity) instanceof AttachState.OnHost;}
    boolean supported(){
        // A zero-displacement physics step can clear onGround for one tick while the sole still touches a block.
        return entity.onGround()||Math.abs(entity.getDeltaMovement().y)<.09
                &&entity.level().getBlockCollisions(entity,entity.getBoundingBox().move(0,-.025,0)).iterator().hasNext();
    }
    /** The assigned nest anchor; FamilyNest resolves the actual supported resting position. */
    public BlockPos nestStand(){return entity.life().homeStand();}
    /** Where a new brood is anchored for the population budget: her nest, else where she stands. */
    public BlockPos birthAnchor(){var nest=nestStand();return nest!=null?nest:entity.blockPosition();}
    public boolean canUse() {
        if(entity.dailyLife().active())return false;
        if(entity.isPanicking()||entity.isCommandPlaying()||entity.life().hasUrgentNeed()||entity.life().mealTicks()>0||awaitingLanding)return false;
        if(task!=Task.NONE)return true;
        if(comfortTicks>0&&adult()){task=Task.COMFORT;taskTicks=0;return true;}
        if(wait>0)return false;wait=40;
        var pregnancy=brood();
        // Early pregnancy: home to tend the brood; the last APPROACH_LEAD ticks belong to the birth's own beats.
        if(pregnancy!=null&&!pregnancy.cancelled&&!pregnancy.complete()&&pregnancy.kind!=FamilyData.BirthKind.EGG&&!birth.active()) { task=Task.BROOD;taskTicks=0;return true; }
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
        if((choreHat=hatChore())!=null){task=Task.FETCH_HAT;taskTicks=0;return true;}
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
        if(task==Task.FETCH_HAT)return choreHat!=null&&!choreHat.isRemoved()&&choreHat.catches()&&taskTicks<2400&&Conductor.roleOf(entity)==null;
        if(task==Task.COMFORT)return comfortTicks>0;
        if(task==Task.BROOD)return taskTicks<240&&!birth.active();
        if(task==Task.STAY_HOME) {
            var home=earlyHomeStand();return home!=null&&entity.distanceToSqr(Vec3.atBottomCenterOf(home))>4;
        }
        if(task==Task.FOLLOW&&earlyHomeStand()!=null)return false;
        if(task==Task.CARRY)return carry.carrying()&&taskTicks<2400;
        if(task==Task.SEARCH&&target!=null)return ordinaryLandFree(entity)&&ordinaryLandFree(target)&&caresFor(target)
                &&escortsMigratingChild(target)&&!safeInSharedHome(target)
                &&target.childhood().shouldStayWithParents()&&entity.life().canRecognize(target)&&taskTicks<1200&&target.level()==entity.level()
                &&entity.distanceToSqr(target)<FAMILY_SEARCH_RANGE*FAMILY_SEARCH_RANGE;
        return taskTicks<240
                &&(task==Task.SEARCH||target!=null&&target.isAlive()&&target.level()==entity.level()&&entity.distanceToSqr(target)<256);
    }
    public void tickGoal() {
        if(!canContinue()){stop();return;}taskTicks++;
        if(task==Task.SEARCH&&taskTicks==1)GameplayLog.limited(entity,"family","search.started",1200,
                "family search started target={} lastKnownPosition={} mother={} father={} nutrition={} energy={}",
                target==null?null:target.getUUID(),searchAt,mother,father,entity.life().nutrition(),entity.life().energy());
        switch(task) {
            case BROOD -> {
                // Early pregnancy: home to the nest to tend the brood (the last APPROACH_LEAD ticks are LiveBirth's site beat).
                BlockPos home=nestStand();
                var shelter=entity.habitat().shelter();
                if(shelter!=null&&shelter.prepareLargeBed(entity)){SurfaceApproach.halt(entity);entity.showReaction("chewing_closed","",20);return;}
                if(home==null){stop();return;}
                // Her place may not stand right now (another body, a growing belly): the bed's middle, never wherever she is on its rim.
                var resting=FamilyNest.restPosition(entity,home);
                if(resting==null)resting=FamilyNest.bedMiddle(entity,home);
                if(resting!=null&&entity.distanceToSqr(resting)>.04) { SurfaceApproach.walk(entity,resting,.9); }
                else { entity.getNavigation().stop();entity.getLookControl().setLookAt(entity.getX(),entity.getEyeY(),entity.getZ()-2,10,10); }
            }
            case COURT -> courtship.tickCourt();
            case RECEIVE_COURT -> courtship.tickReceiver();
            case FOLLOW -> {
                if(!entity.life().canRecognize(target)||entity.distanceToSqr(target)<familyRadiusSqr()){stop();return;}
                entity.getLookControl().setLookAt(target,20,20);
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
            case FETCH_HAT -> tickHatChore();
            case COMFORT -> tickComfort();
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
    public void stop() {
        if(chore!=null){chore.stop("family_goal_stopped");chore=null;if(task==Task.FETCH_HAT)choreRetry=level().getGameTime()+600;}
        choreHat=null;
        if(task==Task.COMFORT)comfortNear=0;
        if(task!=Task.NONE&&task!=Task.FOLLOW&&task!=Task.STAY_HOME&&task!=Task.WAIT_CHILD)GameplayLog.limited(entity,"family","task.stop."+task,200,
                "task stopped task={} target={} ticks={} carriedFood={}",
                task,target==null?null:target.getUUID(),taskTicks,feeding.carrying());
        if(task==Task.FEED_CHILD)entity.careApproach().cancel();task=Task.NONE;target=null;searchAt=null;taskTicks=0;feeding.reset();
        courtship.reset();
        wait=Math.max(wait,40);
    }
    public void died() {
        feeding.died();
        var data=FamilyData.get(level());data.died(entity);
        // Pending unborn reservations are cancelled above; completed births and detached stems
        // remain in the world ledger. The dead body must not retain their live presentation.
        gestation=null;spentStemCount=0;endBreedingPeriod();stop();birth.stop("mother_died");entity.syncFamily();
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
        if(awaitingLanding&&launch!=null)tag.put("BirthLaunch",BirthLaunch.save(launch));
        if(awaitingLanding&&launch!=null&&launched)tag.putBoolean("BirthLaunched",true);
        birth.save(tag);
        tag.putInt("SpentStemCount",spentStemCount);tag.putFloat("SpentStemVariation",spentStemVariation);
        tag.put("CareDecisions",careDecisions.save());tag.put("Carry",carry.save());
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
        launch=awaitingLanding&&tag.contains("BirthLaunch",Tag.TAG_COMPOUND)
                ?BirthLaunch.load(tag.getCompound("BirthLaunch")):null;
        launched=launch!=null&&tag.getBoolean("BirthLaunched");
        // Dry stems are no longer kept on the body.
        spentStemCount=0;spentStemVariation=Math.clamp(tag.getFloat("SpentStemVariation"),.85f,1.15f);
        careDecisions.load(tag.getCompound("CareDecisions"));carry.load(tag.getCompound("Carry"));
        feeding.load(tag);
        separation.load(tag);
        mother=tag.hasUUID("Mother")?tag.getUUID("Mother"):null;father=tag.hasUUID("Father")?tag.getUUID("Father"):null;
        birthId=tag.hasUUID("BirthId")?tag.getUUID("BirthId"):null;gestation=tag.hasUUID("Gestation")?tag.getUUID("Gestation"):null;partner=tag.hasUUID("Partner")?tag.getUUID("Partner"):null;
        // The birth after the gestation it belongs to; past the last child it keeps only its post-labour beats.
        birth.load(tag,saved->{if(entity.level().isClientSide)return null;var b=brood();
            return new LiveBirth(this,b==null?null:b.id,b==null?!saved.getString("Beat").equals("drop"):b.kind==FamilyData.BirthKind.LIVE);});
        stop();entity.syncFamily();
    }
}
