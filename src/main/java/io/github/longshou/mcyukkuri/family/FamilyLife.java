package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.life.NestDoorway;
import io.github.longshou.mcyukkuri.life.TravelRange;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.dialogue.NativeDialogue;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.intent.Conductor;
import io.github.longshou.mcyukkuri.intent.Drive;
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
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.settlement.SettlementSeasons;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.settlement.SettlementSociety;
import io.github.longshou.mcyukkuri.status.Identity;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
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
import net.minecraft.world.phys.AABB;
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
    private static final double FAMILY_FOLLOW_DISTANCE_SQR = 2.25, FAMILY_FOLLOW_DISTANCE = 1.5;
    public static final double FAMILY_SEARCH_RANGE = 48;
    public enum Stage { BABY, JUVENILE, ADULT }
    enum Task { NONE, COURT, RECEIVE_COURT, BROOD, FOLLOW, WAIT_CHILD, SEARCH, FEED_CHILD, STAY_HOME, FETCH_HAT, CARRY, COMFORT, ABSENCE, WATCH_HOME }
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
    /** She was drained by a stem and has not yet fed or slept back above the thinning thresholds (saved with her). */
    private boolean stemDrained;
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
    /** The waking newborn has turned to its kin for its first words (transient). */
    private boolean greetFaced;
    private String landingOutcome="";
    private double landingFromAim=Double.NaN,landingImpactVy,airVy;
    private final ParentCarry carry;
    private Task task=Task.NONE;
    private YukkuriEntity target;
    private Vec3 searchAt;
    private record WatchedForager(UUID id, long started) {}
    private WatchedForager watchedForager;
    private final CareDecisionLedger careDecisions;
    private final FamilyFeeding feeding;
    private final FamilySeparation separation;
    private final Courtship courtship;
    /** The search's own calls: a parent calling for a lost child, the child answering (transient). */
    private final ChildCall call;
    private final GlassVigil glass;
    /** The near-term birth this mother conducts (saved as {@code Birth}). */
    private final Conductor birth;
    /** A hurt newborn's fall (bump), a parent's comfort of it, and the hat errand of an orphaned catch hat; all transient. */
    private boolean bumped;
    private int bumpTicks, comfortTicks, comfortNear;
    private UUID comfortBaby;
    private static final int COMFORT=200;
    /** Ticks a relative's startle at a newborn's hard landing is held before anything else shows (project design). */
    private static final int MISS_SHOCK=50;
    /** Degrees a greeting turn waits for before it starts (no turn per tick for a newborn shifting in the hat). */
    private static final float GREET_TURN=10;
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
        call=new ChildCall(this);
        glass=new GlassVigil(this);
        birth=new Conductor(entity,"Birth");
    }
    /** Land transport of dependent babies (mouth, brim, tails or braid); see {@link ParentCarry}. */
    public ParentCarry carry(){return carry;}
    /** One persistent parent-side decision per child-care need, shared by land and hat routes. */
    public CareDecisionLedger careDecisions(){return careDecisions;}
    FamilyFeeding feeding(){return feeding;}
    KinAbsence absence(){return separation.absence();}
    public boolean kinOverdue(UUID id){return absence().overdue(id);}
    GlassVigil glass(){return glass;}
    void dismissAtGlass(YukkuriEntity child,String reason){separation.dismissAtGlass(child,reason);}
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
                + " search=" + (searchAt == null ? "none" : searchAt) + " call={" + call.describe() + "}"
                + " court={" + courtship.describe() + "}"
                + " separation={" + separation.describe() + "} nest={" + FamilyNest.describe(entity) + "}" + " radius=" + Math.sqrt(familyRadiusSqr()) + " care={" + careDecisions.describe() + "} feeding={" + feeding.describe() + "} birth={" + (liveBirth() == null ? "none" : liveBirth().describe()) + "} carry={" + carry.describe() + "}";
    }
    ServerLevel level() { return (ServerLevel)entity.level(); }
    YukkuriEntity entity() { return entity; }
    Courtship courtship() { return courtship; }
    ChildCall call() { return call; }
    /** Observe movement ownership without advancing the drawn birth launch. */
    public boolean launchInFlight(){ return entity.motor().current() instanceof Launch; }
    UUID arrangedMate() { return courtship.arranged(); }
    UUID legacyPartner() { return partner; }
    int waitTicks() { return wait; }
    void waitTicks(int value) { wait=value; }
    Task task() { return task; }
    /** Stable observation id; the enum and its decision code remain package-private. */
    public String taskId() { return task.name(); }
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
    /** Care food from elsewhere (a stored portion of her hat): held, saved and shown like the feeding's own. */
    public boolean holdCareFood(ItemStack one) { return feeding.hold(one); }
    /** The held care food chewed for {@code child} (mash when it needs mash); the chewing time is the caller's ({@link #CHEW_TICKS}). */
    public void chewCareFood(YukkuriEntity child) { feeding.chew(child); }
    /** The held care food into {@code child}'s meal; the caller has seen their mouths meet (FamilyFeedingContact). */
    public boolean handCareFood(YukkuriEntity child) { return feeding.hand(child); }
    /** The held care food back where {@code into} keeps it (a hat's hold); false when it is refused and stays held. */
    public boolean putBackCareFood(Predicate<ItemStack> into) { return feeding.putBack(into); }
    /** Clears the way for a different mouthful after a real drop. False leaves the care portion held. */
    public boolean releaseCareFood(String reason) { return feeding.releaseCareFood(reason); }
    public static final int CHEW_TICKS = FamilyFeeding.CHEW_TICKS;
    public int careChewTicks() { return feeding.chewTicks(); }
    /** Client-sync source: the child a land caregiver is currently approaching, or {@code -1}. */
    public int careTargetId() { return task == Task.FEED_CHILD && target != null ? target.getId() : -1; }
    /** Client-sync source: uninterrupted mouth-contact progress, from zero through twenty. */
    public int careContactProgress() { return task == Task.FEED_CHILD ? feeding.offerTicks() : 0; }
    private String courtActivity() { return courtship.courtActivity(); }
    public long courtAnimationStart() { return courtship.courtAnimationStart(); }
    public YukkuriAction courtContactAction() { return courtship.contactAction(); }
    public CompoundTag courtshipState() { return courtship.state(); }
    public boolean youngVoice() { return stage!=Stage.ADULT||juvenileVoice||babyTalk; }
    public boolean babyTalk(){return babyTalk;}
    public boolean applyBabyTalk(){if(!adult()||babyTalk)return false;babyTalk=true;entity.setPersistenceRequired();return true;}
    public int sexDrive(){return sexDrive;}
    public boolean inBreedingPeriod(){return adult()&&!sterile()&&!elder()&&sexDrive>0&&entity.status().identity().canConceive();}
    public boolean canTakeStimulant(){return adult()&&!sterile()&&!elder()&&sexDrive==0&&entity.status().identity().canConceive();}
    /** One saved server clock feeds both natural periods and the existing stimulant item. */
    public boolean beginBreedingPeriod(){
        if(entity.level().isClientSide||!canTakeStimulant())return false;
        sexDrive=BREEDING_PERIOD_TICKS;entity.setPersistenceRequired();return true;
    }
    public void endBreedingPeriod(){sexDrive=0;}
    public boolean takeStimulant(){return beginBreedingPeriod();}
    public boolean adult() { return stage==Stage.ADULT; }
    private FamilyData.Foster foster() {
        var person = FamilyData.get(level()).person(entity.getUUID());
        return person == null ? null : person.foster();
    }
    UUID fosterParent() {
        var record = foster();
        return record == null ? null : record.parent();
    }
    public boolean orphaned() {
        return !adult() && (mother != null || father != null)
                && (mother == null || absence().gone(mother)) && (father == null || absence().gone(father));
    }
    public boolean isActiveFoster(YukkuriEntity parent) {
        if (adult()) return false;
        UUID foster = fosterParent();
        return parent.getUUID().equals(foster) && parent.isAlive() && orphaned() && !absence().gone(foster);
    }
    public UUID[] caregiverIds() {
        UUID foster = fosterParent();
        if (foster != null && orphaned() && !absence().gone(foster)) return new UUID[]{mother, father, foster};
        return new UUID[]{mother, father};
    }
    public boolean careParentOf(YukkuriEntity child) {
        return Bonds.parentOf(entity, child) || child.family().isActiveFoster(entity);
    }
    public boolean canAdopt(YukkuriEntity child) {
        if (!adult() || !entity.status().identity().free() || !child.status().identity().free()
                || entity.profile().aquatic() != child.profile().aquatic()
                || !child.family().orphaned() || !entity.life().canRecognize(child)
                || !child.life().canRecognize(entity)) return false;
        var former = child.family().foster();
        if (former != null && (level().getGameTime() - former.acceptedAt() < 24000
                || !child.family().absence().gone(former.parent()))) return false;
        var identity = entity.status().identity();
        if (identity != child.status().identity()) return false;
        if (identity == Identity.WILD_FAMILY) {
            if (entity.habitat().shelter() == null && FamilyNest.care(entity) == null) return false;
        } else if (!SettlementSociety.sameGroup(entity, child)) return false;
        return FamilyData.get(level()).dependents(entity) < 3;
    }
    public boolean adopt(YukkuriEntity child) {
        if (!canAdopt(child)) return false;
        var care = FamilyNest.care(entity);
        boolean foundHome = child.family().sharesHome(entity)
                || (care != null ? child.life().assignHome(entity.life().home()) : child.habitat().takeInBy(entity));
        if (!foundHome && entity.status().identity() == Identity.WILD_FAMILY) return false;
        FamilyData.get(level()).recordFoster(child, entity);
        child.family().absence().observe();
        child.family().wait = 0;
        GameplayLog.event(entity, "family", "foster accepted child={}", child.getUUID());
        return true;
    }
    public boolean caresFor(YukkuriEntity child) {
        return !child.family().adult() && !entity.moldExile().leavesCareToResident(child)
                && (adult() && (Bonds.parentOf(entity, child) || entity.life().nutrition() >= 35
                    && child.family().isActiveFoster(entity)) || FamilyProvision.siblingCare(entity, child));
    }
    public boolean hasHungryChild() {
        return !entity.level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(1.5),child->caresFor(child)
                &&entity.life().canRecognize(child)&&child.life().nutrition()<entity.life().nutrition()-entity.species().traits().dote()).isEmpty();
    }
    /** An own child within 12 blocks that is waiting for this parent's care meal (not refused, not in a failed-search pause). */
    public boolean hasChildAwaitingFeed() { return adult()&&feeding.hungriestOwnChild()!=null; }
    /** A dependent young with a parent of its own in follow range: it keeps to her side rather than sweeping an empty field alone. */
    public boolean keepsToParent() { return !adult()&&entity.childhood().shouldStayWithParents()&&closestParent()!=null; }
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
    /** The bedtime walk yields to the same search that normally fetches a dependent home. */
    public boolean prepareHomeCarry() {
        if(!adult()||!ordinaryLandFree(entity)||entity.dailyLife().active()||urgentNeed())return false;
        if(task==Task.CARRY||task==Task.SEARCH&&carry.lifting())return true;
        if(task==Task.SEARCH&&target!=null)return carry.pickupWanted(target);
        if(task!=Task.NONE||wait>0)return false;
        var child=closestSeparatedDependentChild(true);
        if(child==null)return false;
        return startLandChildSearch(child);
    }
    /** In its nest or inside its own home (FamilySeparation's test). */
    public boolean atHome() { return FamilySeparation.atHome(entity); }
    /** The tick it last came home after a real absence, or -1; kin at home who see it come in may welcome it. */
    public long cameHomeAt() { return separation.cameHome(); }
    /** Both rest in the same home: one shelter, or one nest. */
    public boolean sharesHome(YukkuriEntity other) {
        var home=entity.habitat().shelter();
        return home!=null&&home==other.habitat().shelter()||FamilyNest.sameNest(entity,other);
    }
    /** A parent on her way to a child she searches for (the aquatic life does not set out under it); current family work only. */
    public boolean searchingChild() { return task==Task.SEARCH&&target!=null; }
    /** Current family work only: no search or willingness decision is performed for this readout. */
    public boolean migrationCareActive() { return task==Task.FEED_CHILD||task==Task.SEARCH&&target!=null&&caresFor(target); }
    public boolean elder() { return age>=GrowthTiming.ELDER_START; }
    /** The final child interval is internally still {@link Stage#JUVENILE}; it is a separate age qualification. */
    public boolean subadult() { return subadult(age); }
    private static boolean subadult(long age) { return age>=GrowthTiming.SUBADULT_START&&age<GrowthTiming.ADULT_START; }
    /** The age stage as players read it (lang {@code stage.mcyukkuri.<id>}): baby, juvenile, subadult or adult. */
    public String stageId() { return stageId(stage, age); }
    public static String savedStageId(CompoundTag family) {
        long age = family.getLong("Age");
        return stageId(stageForAge(age), age);
    }
    private static String stageId(Stage stage, long age) { return subadult(age)?"subadult":stage.name().toLowerCase(Locale.ROOT); }
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
        return !laboring()&&!inBirthBeat()&&!launchInFlight()&&!carry.lifting()&&!glass.finishing()&&comfortTicks==0
                &&(task==Task.NONE||task==Task.WAIT_CHILD||task==Task.FOLLOW||task==Task.STAY_HOME||task==Task.SEARCH
                    ||task==Task.ABSENCE||task==Task.WATCH_HOME);
    }
    public String activity() {
        var action=entity.motor().current();
        if(action instanceof PlaceProp||action instanceof TakeProp)return action.pose().clip();
        // Comforting comes before a birth role's clip, so the cheering hat_hold faces never show over a hurt newborn.
        if(task==Task.COMFORT&&comfortTicks>0)return comfortNear>=10?Behaviors.NUZZLE:Behaviors.IDLE;
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
            case WAIT_CHILD, WATCH_HOME -> Behaviors.WAIT_CHILD;
            case SEARCH -> carry.lifting()?Behaviors.CARRY_LIFT:call.activity()!=null?call.activity():glass.active()?glass.activity():Behaviors.SEARCH_FAMILY;
            case ABSENCE -> absence().activity();
            case FEED_CHILD -> feeding.activity();
            case STAY_HOME -> Behaviors.STAY_HOME; default -> awaitingLanding?Behaviors.NEWBORN_FALL:Behaviors.IDLE;
        };
    }
    /** Why the current delivery is held up (empty when it is not): the birth's own hold, or the last refusal of the ledger. */
    public String birthBlock() {
        var b=brood();if(b==null||b.cancelled||b.complete())return "";
        if(b.kind==FamilyData.BirthKind.LIVE&&!deliverySupported())return "must_go_ashore";
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
    private boolean stagingStem() {
        var ceremony=liveBirth();
        return ceremony!=null&&ceremony.anchorsMother();
    }
    /** Danger and physical interruptions take precedence over staging. Ordinary live labour also yields to urgent needs. */
    private boolean birthFree() {
        boolean staging=stagingStem();
        var life=entity.life();
        return !entity.level().isClientSide&&entity.isAlive()&&!entity.isNoAi()&&!entity.isPanicking()&&!entity.isCommandPlaying()&&(!entity.isPassenger()||cabinBirth())
                &&!entity.operations().active()&&!awaitingLanding
                &&(staging ? !entity.isOnFire()&&!entity.isInWaterOrBubble()&&!life.hotFloor().active()&&!entity.body().dying()
                        : !life.hasUrgentNeed()&&life.mealTicks()==0);
    }
    /** The birth role this body plays in the current beat (BirthGoal.canUse); when the goal yields, the beat's deadline decides the cost.
     *  Danger or a hunger emergency can displace a catcher. Ordinary comfort waits while the catch is under way. */
    public Conductor.Role birthRole() {
        var role=birthFree()?Conductor.roleOf(entity):null;
        if(role==null||!role.engaged(entity))return null;
        if(role.choreography().host()==entity&&shoreLanding())return null;
        return LiveBirth.CATCHER.equals(role.role())&&role.choreography().host()!=entity&&role.choreography() instanceof LiveBirth b&&b.watching()
                &&(hasUrgentlyHungryChild()||comfortTicks>0&&!b.catching())?null:role;
    }
    /** BirthGoal's tick for a role with nothing to run: hold still; the mother's postpartum beats, the catcher's eyes on the birth. */
    public void holdBirthRole(Conductor.Role role) {
        if(role.choreography() instanceof LiveBirth ceremony && ceremony.host()==entity && ceremony.anchorsMother()){
            SurfaceApproach.halt(entity);
            entity.life().eatAtStand();
        }else entity.getNavigation().stop();
        var cabin=birthCabin();
        if(cabin!=null&&cabin.ready()&&(laboring()||"linger".equals(role.choreography().beat())))
            Motor.supportHeight(cabin.hat(),entity,cabin.underside(),"birth_support");
        if(LiveBirth.MOTHER.equals(role.role())&&"linger".equals(role.choreography().beat()))recoveryBeats((int)(level().getGameTime()-role.choreography().beatTick()));
        else watchBirth(role);
    }
    /** The catcher's eyes while it watches: its birth's one watch point (her outlet, then the young in the hat), the same point
     *  its Attend turns the body to, so the look never pulls the body off a yaw the birth asked for. */
    public void watchBirth(Conductor.Role role) {
        var mom=role.choreography().host();
        if(mom==entity||!(role.choreography() instanceof LiveBirth b)||!b.watching())return;
        var at=b.watchPoint();
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
            // A catcher in its birth role hops from its own Attend instead: one owner of that hop.
            var hello=greetHop();
            if(hello!=null&&level().getGameTime()-hello.tick()==30&&birthRole()==null)entity.motor().run(BIRTH_BODY,new Gesture(Gesture.Kind.HOP_SMALL));
            else if(greetee() instanceof YukkuriEntity other){
                // Only on an idle motor (a running Face is left to finish), and only once it is off by more than GREET_TURN.
                float yaw=SurfaceApproach.yaw(entity.position(),other.position());
                // Face completes on the body yaw, so the same yaw is what tells whether a turn is still owed.
                if(!CareApproach.seatedWith(entity,other)&&Math.abs(Mth.wrapDegrees(yaw-entity.yBodyRot))>GREET_TURN)entity.motor().run(BIRTH_BODY,new Face(yaw));
            }
            return false;
        }
        if(launch!=null&&!launched&&!entity.onGround()&&!entity.isInWaterOrBubble()&&!entity.isPassenger()){
            launched=true;
            var target=launch.hat()==null?null:level().getEntity(launch.hat());
            if(entity.motor().run(BIRTH_BODY,new Launch(launch.velocity(),"birth").envelope(HatGeometry::envelope)
                    .toward(target instanceof SurfaceHost host?host:null))){entity.getNavigation().stop();return true;}
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
    public boolean stimulate(){if(!adult()||sterile()||!entity.status().identity().canConceive())return false;stimulation=Math.min(1200,stimulation+240);return true;}
    public boolean sterilize(){if(!adult()||sterile()||stimulation==0)return false;sterile=true;stimulation=0;return true;}
    public void detached(){gestation=null;spentStemCount=0;recovery=Math.max(recovery,FamilyConfig.RECOVERY.get());stop();entity.syncFamily();}
    public void tick() {
        if(entity.level().isClientSide)return;
        FamilyData.get(level()).importPartner(entity);
        if(!entity.isAlive()){if(entity.isVehicle())entity.motor().interrupt("carrier_dead");return;}
        carry.tick();
        // The drain look fades with her recovery, not with the brood.
        if(stemDrained&&drainStage()==0)stemDrained=false;
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
        if(!adult()||sterile()||elder()||!entity.status().identity().canConceive())sexDrive=0;
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
        if(entity.isPanicking()||entity.isCommandPlaying()||(task!=Task.FEED_CHILD&&urgentNeed()&&!courtship.finishingContact()))stop();
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
        // The conductor owns the whole stem growth and the late preparation and labour of live birth.
        // A birth still running for an older brood never holds a new one back: it ends, and its hat goes to the hat errand.
        if(expecting&&liveBirth() instanceof LiveBirth old&&!brood.id.equals(old.broodId()))birth.stop("brood_replaced");
        if(expecting&&!birth.active()){
            if(brood.kind==FamilyData.BirthKind.STEM||brood.elapsed>=brood.duration-LiveBirth.APPROACH_LEAD)
                birth.start(LiveBirth.cast(this,brood));
            else if(brood.kind==FamilyData.BirthKind.LIVE)gestate(brood,true);
        } else if(gestation!=null&&(brood==null||brood.cancelled||brood.complete()&&brood.kind!=FamilyData.BirthKind.STEM))gestation=null;
        if(expecting&&brood.kind==FamilyData.BirthKind.STEM){
            if(entity.life().isSleeping())entity.life().wakeForStemFood();
            advanceStem(brood);
            if(!entity.isAlive())return;
        }
        birth.tick();
    }
    private UUID stemWarningBrood;

    /** The attached stem grows from the bearer once per loaded tick. Delivery only handles ripe fruit. */
    private void advanceStem(FamilyData.Brood brood){
        if(brood.elapsed>=brood.duration)return;
        int elapsed=++brood.elapsed;
        FamilyData.get(level()).setDirty();
        if(elapsed==1)GameplayLog.event(entity,"family","stem sprouted brood={} elapsed={} duration={} fruit={}",
                brood.id,elapsed,brood.duration,brood.children.size());
        int ticks=StemDrain.drawTicks(elapsed,brood.duration,brood.drawInterval());
        if(brood.fastStem){
            if(entity.life().nutrition()>0)brood.emptyAt=-1;
            else if(brood.emptyAt<0)brood.emptyAt=elapsed;
            if(!brood.id.equals(stemWarningBrood)&&stemDepletionWarning()){
                stemWarningBrood=brood.id;
                entity.speakCue("wounded");
                for(var player:level().getEntitiesOfClass(ServerPlayer.class,entity.getBoundingBox().inflate(12)))
                    player.displayClientMessage(Component.translatable("stem.mcyukkuri.help",entity.getName()),true);
                GameplayLog.event(entity,"family","stem depletion warning brood={} elapsed={} duration={} nutrition={} health={}",
                        brood.id,elapsed,brood.duration,entity.life().nutrition(),entity.getHealth());
            }
        }
        if(ticks==0)return;
        int unborn=brood.unborn();
        float cost=brood.draw(ticks);
        float before=entity.life().nutrition(),spent=Math.min(cost,before);
        entity.life().spendNutrition(spent);
        if(brood.fastStem&&entity.life().nutrition()<=0&&brood.emptyAt<0)brood.emptyAt=elapsed;
        if(entity.life().nutrition()<StemDrain.THIN_NUTRITION)stemDrained=true;
        if(spent<cost){
            float healthBefore=entity.getHealth();
            float damage=brood.fastStem?StemDrain.damage(cost-spent,healthBefore,elapsed,brood.emptyAt)
                    :(cost-spent)/StemDrain.NUTRITION_PER_HEALTH;
            if(damage>0)entity.body().stemDepletionDamage(damage);
            GameplayLog.event(entity,"family","stem drew body brood={} elapsed={} duration={} fruit={} nutrition_drawn={} deficit={} health={}->{}",
                    brood.id,elapsed,brood.duration,unborn,spent,cost-spent,healthBefore,entity.getHealth());
        }
        if(entity.isAlive()&&(elapsed==brood.drawInterval()||elapsed==brood.duration))
            GameplayLog.event(entity,"family","stem {} brood={} elapsed={} duration={} fruit={} nutrition={}->{} health={}",
                    elapsed==brood.duration?"ripe":"first draw",brood.id,elapsed,brood.duration,unborn,before,entity.life().nutrition(),entity.getHealth());
        // The attack's release and fright response keep their own timing.
        if(entity.isAlive()&&elapsed==Math.min(StemDrain.LEGACY_DRAW_TICKS,brood.duration)&&brood.incidentSource!=null)
            entity.showReaction("confused","aftermath_realize",160);
    }
    /** One loaded tick of live gestation: "" when it ran, else why it waits. */
    String gestate(FamilyData.Brood b,boolean sited){
        if(b.elapsed>=b.duration-LiveBirth.NEST_GRACE&&!deliverySupported())return "must_go_ashore";
        if(!(b.elapsed<b.duration-LiveBirth.NEST_GRACE?gestationReady():readyForDelivery()))return "mother_not_ready";
        if(!sited&&b.elapsed>=b.duration-LiveBirth.NEST_GRACE)return "no_place_for_term";
        if(!bellyRoom(b))return "no_room_for_belly";
        if(b.elapsed>=b.duration)return "";
        if(++b.elapsed%200==0)entity.life().spendNutrition(1);
        if(b.elapsed==b.duration)GameplayLog.event(entity,"family","delivery labor started brood={} kind={} children={}",b.id,b.kind,b.children.size());
        FamilyData.get(level()).setDirty();return "";
    }
    /** A ripe fruit still needs a physically available mother to drop from. */
    boolean readyForStemDrop(){
        return !entity.operations().active()&&!entity.isPanicking()&&!entity.isOnFire()&&!entity.isInWaterOrBubble()
                &&!entity.isPassenger()&&supported()
                &&!entity.isCommandPlaying()&&!entity.body().condition().blocksBreedingApproach()
                &&entity.life().mealTicks()==0&&!entity.life().isSleeping();
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
        return deliverySupported()&&!entity.isVehicle()&&gestationReady();
    }
    public boolean cabinBirth(){return liveBirth()!=null&&liveBirth().cabinChosen();}
    public BirthCabin birthCabin(){return liveBirth()==null?null:liveBirth().cabin();}
    boolean deliverySupported(){var cabin=birthCabin();return cabinBirth()?cabin!=null&&cabin.ready():deliveryGrounded();}
    boolean deliveryClear(FamilyData.Brood brood,int index){
        var cabin=birthCabin();return cabinBirth()?cabin!=null&&cabin.clear(brood,index):LiveBirthGeometry.clear(entity,index,entity.yBodyRot);
    }
    /** Live labour needs the mother's own dry ground, even when a choreography deadline expires. */
    boolean deliveryGrounded(){return !entity.isPassenger()&&supported()&&!entity.isInWaterOrBubble();}
    /** The birth conductor's own preparation lead also starts the shore return. */
    public static int shoreBirthLead(){return LiveBirth.APPROACH_LEAD;}
    public int currentShoreBirthLead(){
        var b=brood();
        return attachedStem(b)?b.duration:shoreBirthLead();
    }
    /** The shore return is still under way: her riders are aboard, or she is down but her hat still floats as a boat while her own
     *  landing runs (it ends with the hat worn again, or at its own deadline). The birth role waits for the hat so the newborns land in it. */
    private boolean shoreLanding(){
        if(!shoreBirthDue())return false;
        if(entity.isVehicle())return true;
        var hat=PlacedHatEntity.find(entity);var landing=Drive.running(entity,"aquatic");
        return hat!=null&&!hat.catches()&&landing!=null&&("come_ashore".equals(landing.id())||"resolve_own_hat".equals(landing.id()));
    }
    public boolean shoreBirthDue(){
        var b=brood();
        return attachedStem(b)||b!=null&&!b.cancelled&&!b.complete()&&b.kind==FamilyData.BirthKind.LIVE
                &&b.duration-b.elapsed<=shoreBirthLead()&&liveBirth()!=null&&!cabinBirth();
    }
    /** Pregnancy can advance aboard; labour and birth roles require her own ground support. */
    private boolean gestationReady(){
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
    /** A greeting needs real ground or host support; being carried or airborne does not qualify. */
    private boolean greetingSupported(){return !flying()&&(entity.onGround()&&!entity.isPassenger()||onHost());}
    /** The newborn this awake, supported relative answers, from 30 ticks after an unhurt landing for 10 ticks. */
    private LiveBirth.Welcome answering(){
        var hello=greeting();long since=hello==null?-1:level().getGameTime()-hello.tick();
        return since>=30&&since<40&&greetingSupported()&&!entity.life().isSleeping()
                &&level().getEntity(hello.baby()) instanceof YukkuriEntity baby&&baby.isAlive()&&!baby.family().bumped()?hello:null;
    }
    /** Whom this body turns to for a birth greeting, or null: a waking newborn its nearest awake parent or sibling (once, before
     *  its first words), an awake supported relative with no birth role the unhurt newborn it is about to answer. */
    private YukkuriEntity greetee(){
        if(entity.firstWaking()){
            if(greetFaced)return null;
            greetFaced=true;return level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(6),other->other!=entity
                &&other.isAlive()&&!other.life().isSleeping()&&(other.getUUID().equals(mother)||other.getUUID().equals(father)||mother!=null&&mother.equals(other.family().mother())))
                .stream().min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
        }
        if(!greetingSupported()||entity.life().isSleeping())return null;
        var hello=greeting();long since=hello==null?-1:level().getGameTime()-hello.tick();
        return since>0&&since<30&&birthRole()==null
                &&level().getEntity(hello.baby()) instanceof YukkuriEntity baby&&baby.isAlive()&&!baby.family().bumped()?baby:null;
    }
    /** A newborn's first words: its kind's hello in the childish (young) tier when that tier has one, else its newborn line.
     *  Its relatives answer once, in kind: the hello echoed, or the newborn reply. */
    public String firstWords(){
        return NativeDialogue.has(entity.getVariant().dialogueSpecies(),"young",entity.myon().quirk(),"hello")?"hello":"newborn";
    }
    /** The newborn this relative greets with its small hop (answering, and the body can hop), or null. */
    LiveBirth.Welcome greetHop(){return entity.onGround()&&!entity.isPassenger()&&Gesture.smallHopImpulse(entity)>0?answering():null;}
    /** The father and the older siblings turn to a landed newborn and answer its first greeting with the classic one (L36: a
     *  heard ゆっくりしていってね is echoed). A hurt one startles them first, the startle held whole (MISS_SHOCK, nothing else
     *  shown over it); only then does the mate go to comfort it. */
    private void greet(){
        if(!(greeting() instanceof LiveBirth.Welcome hello)||entity.life().isSleeping()||!(level().getEntity(hello.baby()) instanceof YukkuriEntity baby)||!baby.isAlive())return;
        long since=level().getGameTime()-hello.tick();boolean hurt=baby.family().bumped(),mate=partner()!=null&&partner().equals(baby.family().mother());
        if(greetingSupported())entity.getLookControl().setLookAt(baby,20,20);
        if(since==0&&mate&&!hurt)entity.showReaction("happy","caregiver",LiveBirthGeometry.REST);
        if(hurt&&since==6&&greetingSupported()){
            entity.mind().felt(EmotionEvent.FRIGHTENED);entity.holdReaction("surprise","afraid",MISS_SHOCK);BirthStaging.sweat(entity);
        }
        if(hurt&&since==6+MISS_SHOCK&&mate&&entity.distanceToSqr(baby)<256)comfort(baby);
        if(since==30&&!hurt&&answering()!=null){
            entity.showReaction("happy","",40);boolean spoken=entity.speak(baby.family().firstWords());BirthStaging.affection(entity);
            GameplayLog.event(entity,"family","birth greeting child={} spoken={} host={}",baby.getUUID(),spoken,entity.getVehicle()==null?null:entity.getVehicle().getUUID());
        }
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
        // The greeting waits until first_wake has opened the eyes and reached its happy face (24 ticks), not the landing tick;
        // meanwhile it turns to its nearest kin (birthTravel).
        greetFaced=false;entity.showReactionDelayed("happy","newborn",100,24);
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
        if(CareApproach.seatedWith(entity,baby))return;
        float yaw=(float)(Mth.atan2(baby.getZ()-entity.getZ(),baby.getX()-entity.getX())*Mth.RAD_TO_DEG)-90;var motor=entity.motor();
        if(motor.current() instanceof Face&&motor.owner()!=null&&beats.ownerId().equals(motor.owner().ownerId())||Math.abs(Mth.wrapDegrees(yaw-entity.yBodyRot))<=Face.FACE_ARRIVED)return;
        motor.run(beats,new Face(yaw));
    }
    /** A parent hurries to a newborn that fell hard, worried, nuzzles it and licks it (project original; nuzzling is Wiki E06). */
    public void comfort(YukkuriEntity baby){
        if(entity.level().isClientSide||baby==null||baby==entity||!adult()||entity.isNoAi()||!entity.isAlive())return;
        comfortBaby=baby.getUUID();comfortTicks=COMFORT;comfortNear=0;
        entity.showReaction("teary","",40);
    }
    /** Only the COMFORT task may advance contact or steer. */
    private void tickComfort(){
        if(comfortTicks--==COMFORT)BirthStaging.caption(entity,"亲油走向哭泣的新生儿");
        var baby=comfortBaby!=null&&level().getEntity(comfortBaby) instanceof YukkuriEntity found&&found.isAlive()?found:null;
        if(baby==null||!adult()){comfortTicks=0;comfortNear=0;return;}
        double near=(entity.getBbWidth()+baby.getBbWidth())*.5+.12;
        // Her outlet is no comfort stand while a child is still inside her: standing there holds the whole delivery.
        var outlet=birthOutlet(baby);
        boolean inside=outlet!=null&&inBirthOutlet(outlet,entity.position());
        if(inside||entity.position().subtract(baby.position()).multiply(1,0,1).length()>near+.2||Math.abs(entity.getY()-baby.getY())>.35||!entity.hasLineOfSight(baby)
                ||!supported()||!baby.family().supported()){
            comfortNear=0;entity.getLookControl().setLookAt(baby,20,20);
            var stand=SurfaceApproach.beside(entity,baby,near,.35,Double.POSITIVE_INFINITY,at->outlet==null||!inBirthOutlet(outlet,at));
            if(inside)GameplayLog.limited(entity,"comfort","birth_outlet."+baby.getUUID(),200,
                    "comfort stand in the mother's outlet space; stepping out baby={} mother={} stand={}",
                    baby.getUUID(),baby.family().mother(),stand);
            if(stand==null)SurfaceApproach.halt(entity);else SurfaceApproach.walkAround(entity,stand,baby,1.1);
            return;
        }
        SurfaceApproach.halt(entity);turnTo(baby);comfortNear++;
        if(comfortNear==10){entity.showReaction("closed_smile","nuzzle",60);BirthStaging.affection(entity);BirthStaging.caption(entity,"蹭脸安抚");}
        if(comfortNear==70){entity.showReaction("open_smile","",40);BirthStaging.affection(entity);BirthStaging.caption(entity,"轻轻舔舐安抚");}
        if(comfortNear>=130){comfortTicks=0;comfortNear=0;}
    }
    /**
     * The room a labouring mother's outlet needs right now, at the yaw she holds: the next unborn child's release box (the box
     * {@link LiveBirthGeometry#clear} refuses a living body in) grown into her own body, so a stand in either is in the outlet's way.
     * Null when the hurt one's mother has no live child left inside her (the comfort is then nobody's obstruction).
     */
    private AABB birthOutlet(YukkuriEntity baby){
        if(!(baby.family().mother() instanceof UUID mom)||!(level().getEntity(mom) instanceof YukkuriEntity mother))return null;
        var brood=mother.family().brood();
        if(brood==null||brood.cancelled||brood.complete()||brood.kind!=FamilyData.BirthKind.LIVE)return null;
        int index=0;while(brood.children.get(index).born||brood.children.get(index).eggLost)index++;
        return LiveBirthGeometry.childDimensions(brood.children.get(index),mother.getScale())
                .makeBoundingBox(LiveBirthGeometry.position(mother,index)).minmax(mother.getBoundingBox());
    }
    /** Whether this body, stood at {@code stand}, would lie in that outlet space (the tolerance {@link LiveBirthGeometry#clear} reads bodies with). */
    private boolean inBirthOutlet(AABB outlet,Vec3 stand){
        return entity.getBoundingBox().move(stand.subtract(entity.position())).inflate(.015).intersects(outlet);
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
        boolean own=hat.ownsPhysicalHat(entity);
        if(chore==null){
            if(hat.isRemoved()||!hat.catches()){stop();return;}
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
        entity.syncFamily();
        if(entity.getRootVehicle() instanceof SurfaceHost host)host.loadChanged(entity,"growth");
        if(slept||stage!=Stage.BABY)entity.showReaction("happy","grown",100);return true;
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
        // Vanilla applies the control value twice. The growth curve describes the stride on the ground.
        double factor=Math.sqrt(BodyAbility.growthPace(entity));
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
        if(resolvedHeat)entity.agitation().markRelieved(true);
        entity.setPersistenceRequired(); }
    /** Preconditions of a checked aftermath reservation. Existing pregnancies and partners are never replaced. */
    public boolean canReceiveAftermath() {
        return !entity.level().isClientSide && entity.status().identity().canConceive() && entity.isAlive() && !entity.isNoAi() && adult() && !sterile() && !elder()
                && !entity.body().dying() && entity.body().coreIntact()
                && !entity.profile().houseBody() && !entity.profile().dangerToYukkuri()
                && gestation==null && recovery==0 && !awaitingLanding && !birthDuty();
    }
    /** Called only after a checked STEM reservation; the source gains no partner or care role. */
    void receiveAftermath(UUID brood) {
        endBreedingPeriod();gestation=brood;conceptions++;recovery=FamilyConfig.RECOVERY.get();
        spentStemCount=0;stemDrained=true;
        stop();entity.setPersistenceRequired();entity.syncFamily();
    }
    public boolean growingStem(){
        var b=brood();
        return attachedStem(b)&&b.elapsed<b.duration;
    }
    private boolean wouldDeplete(FamilyData.Brood b) {
        if(!attachedStem(b)||b.elapsed>=b.duration)return false;
        float budget=entity.life().nutrition()+entity.getHealth()*StemDrain.NUTRITION_PER_HEALTH;
        return b.draw(b.unpaidTicks())>budget;
    }
    public boolean stemFoodUrgent() {
        var b=brood();
        return attachedStem(b)&&b.elapsed<b.duration&&(entity.life().nutrition()<StemDrain.WEAK_NUTRITION||wouldDeplete(b));
    }
    public boolean stemDepletionWarning() {
        var b=brood();
        // From the first visible shoot: the stem now grows for seconds before any fruit, and the drain already runs.
        return attachedStem(b)&&b.fastStem&&b.elapsed>0&&wouldDeplete(b);
    }
    private static boolean attachedStem(FamilyData.Brood b){
        return b!=null&&b.kind==FamilyData.BirthKind.STEM&&!b.cancelled&&!b.complete();
    }
    /** Visible drain: 0 none, 1 carrying after an attack, 2 thinning, 3 weak. */
    public int drainStage() {
        var b=brood();boolean aftermath=attachedStem(b)&&b.incidentSource!=null;
        return StemDrain.stage(aftermath,stemDrained,entity.life().nutrition(),entity.life().energy());
    }
    /** Manufactured filling has no identified father and does not create or replace a partner. */
    public void assisted(UUID brood) {
        if(gestation!=null)return;
        endBreedingPeriod();gestation=brood;conceptions++;recovery=FamilyConfig.RECOVERY.get();spentStemCount=0;
        stop();entity.setPersistenceRequired();entity.syncFamily();
    }
    public boolean readyForAssistance() {
        return !entity.level().isClientSide&&entity.status().identity().canConceive()&&!entity.profile().houseBody()&&!entity.profile().dangerToYukkuri()
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
        if(resolvedHeat)entity.agitation().markRelieved(false);
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
    /** A host supports pregnancy and greetings, never labour or courtship. */
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
        if(entity.isPanicking()||entity.isCommandPlaying()||urgentNeed()||entity.life().mealTicks()>0||awaitingLanding)return false;
        if(task!=Task.NONE)return true;
        if(comfortTicks>0&&adult()){task=Task.COMFORT;taskTicks=0;return true;}
        if(wait>0)return false;wait=40;
        var pregnancy=brood();
        // Early pregnancy: home to tend the brood; the last APPROACH_LEAD ticks belong to the birth's own beats.
        if(pregnancy!=null&&!pregnancy.cancelled&&!pregnancy.complete()&&pregnancy.kind!=FamilyData.BirthKind.EGG&&!birth.active()) { task=Task.BROOD;taskTicks=0;return true; }
        if(!adult()) {
            var foster = fosterParent();
            if (foster != null && !ParentCarry.awaitsCarrier(entity)) {
                var caregiver = closestParent();
                if (caregiver != null && !withinFollowDistance(caregiver)
                        && (Bonds.parentOf(caregiver, entity)
                        || caregiver.life().nutrition() >= 35 && !ParentCarry.needsCarry(entity))) {
                    target=caregiver;task=Task.FOLLOW;taskTicks=0;return true;
                }
            }
            if(emergencyForaging())return false;
            if(subadult() && feeding.startCare())return true;
            if(startAbsence())return true;
            if(subadult() && ordinaryLandFree(entity) && startHomeWatch())return true;
            if(seekingFamilyHelp() && !familyTravelling() && closestParent()==null && startFamilyHelp())return true;
            if(exploring()&&!familyTravelling()&&!entity.life().hasUrgentNeed())return false;
            if(!entity.childhood().shouldStayWithParents())return false;
            if(entity.getVehicle() instanceof YukkuriEntity)return false;
            // A baby a parent is coming to carry waits for the pickup, also on its way home: a walking baby slips the parent's mouth.
            if(ParentCarry.awaitsCarrier(entity)) {
                GameplayLog.limited(entity,"family","wait.carrier",1200,"baby waits for parent carry");
                return false;
            }
            var home=earlyHomeStand();
            if(home!=null) {
                if(needsBedReturn(home)) { task=Task.STAY_HOME;target=null;taskTicks=0;return true; }
                return false;
            }
            // A travelling child that can locate its escort follows the live body before answering an old call.
            if(familyTravelling()){
                target=migrationEscort();
                if(target!=null){
                    if(withinFollowDistance(target)){target=null;return false;}
                    task=Task.FOLLOW;taskTicks=0;return true;
                }
            }
            // Only a child not kept to its nest leaves to answer a call; one at home stays there for the parent to come.
            var heard=call.heard();
            if(heard!=null){task=Task.SEARCH;target=null;searchAt=call.answer(heard);taskTicks=0;return true;}
            target=closestParent();
            if(target!=null&&!withinFollowDistance(target)) { task=Task.FOLLOW;taskTicks=0;return true; }
            return false;
        }
        if((choreHat=hatChore())!=null){task=Task.FETCH_HAT;taskTicks=0;return true;}
        // Finish collecting and depositing the real load before routine care can send the parent home again.
        if(entity.forageTrip()!=null&&entity.forageTrip().outing())return false;
        if(feeding.startCare())return true;
        if(ordinaryLandFree(entity)) {
            if(startLandChildSearch())return true;
            if(startAbsence()||startHomeWatch())return true;
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
            // A candidate she cannot pair with while her partner stands still gets a real answer: she turns her back on him.
            target=level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(3),
                    other->other.family().target()==entity&&Courtship.askingForRefusal(other)).stream().findFirst().orElse(null);
            if(target!=null) { task=Task.RECEIVE_COURT;courtship.resetAttempt();taskTicks=0;return true; }
            target=courtship.comeback();
            boolean comeback=target!=null,declined=false;
            if(target==null)target=level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(3),other->
                    SettlementSeasons.mayCourt(entity)&&entity.getUUID().compareTo(other.getUUID())<0
                            &&other.family().task()==Task.NONE&&other.competingMoveGoalId()==null&&!data.busyParent(other.getUUID())&&courtship.mayStart(other)&&canReproduceWith(other))
                    .stream().findFirst().orElse(null);
            // Nobody free will have him: an adult whose partner still stands is courted anyway, and answers with her back.
            if(target==null) {
                target=level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(3),other->
                    other.family().task()==Task.NONE&&other.competingMoveGoalId()==null&&!data.busyParent(other.getUUID())&&courtship.refusedPairing(other))
                    .stream().findFirst().orElse(null);
                declined=target!=null;
            }
            var releasedGoal=comeback?courtship.consumeComeback():null;
            if(target!=null&&!allowCourt(target)){target=null;return false;}
            if(target!=null) { task=Task.COURT;courtship.beginRound(declined,releasedGoal);taskTicks=0;entity.showReaction("happy","courtship",100);return true; }
        }
        return false;
    }
    /** An urgent need puts family aside, except a dependent young's hunger: that keeps it with its parents, who feed it or find food with it. */
    private boolean urgentNeed(){
        var life=entity.life();
        if(!life.hasUrgentNeed())return false;
        boolean hungerOnly=life.nutrition()<30&&life.energy()>=25&&(life.bladder()<90||life.constipated())&&life.wetness()<=40
                &&!entity.isInWaterOrBubble()&&!entity.isOnFire();
        return adult()||!hungerOnly||!entity.childhood().shouldStayWithParents();
    }
    private boolean startLandChildSearch() {
        var child=closestSeparatedDependentChild(false);
        if(child==null)return false;
        return startLandChildSearch(child);
    }
    private boolean startLandChildSearch(YukkuriEntity child) {
        task=Task.SEARCH;target=child;searchAt=separation.lastSeen(child);taskTicks=0;
        entity.showReaction("teary","wait_family",80);return true;
    }

    private boolean startAbsence() {
        if(!absence().start())return false;
        task=Task.ABSENCE;taskTicks=0;return true;
    }

    private boolean startHomeWatch() {
        if(!FamilyProvision.nearHome(entity,entity.blockPosition()))return false;
        var provider=FamilyProvision.choose(FamilyProvision.caregivers(entity),other -> other!=entity
                && !kinOverdue(other.getUUID()) && FamilyProvision.outForFood(other));
        if(provider==null)return false;
        long started=level().getGameTime()-(provider.forageTrip().familyOuting()?provider.forageTrip().away():provider.family().taskTicks);
        var outing=new WatchedForager(provider.getUUID(),started);
        if(outing.equals(watchedForager))return false;
        var door=FamilyProvision.entranceStand(entity);
        if(door==null)return false;
        watchedForager=outing;
        target=provider;searchAt=door;task=Task.WATCH_HOME;taskTicks=0;
        GameplayLog.event(entity,"family","household watch provider={} entrance={}",provider.getUUID(),GameplayLog.at(door));
        return true;
    }

    /** An older dependant who has made its search can approach someone familiar before a greeting. */
    public boolean seekingFamilyHelp() {
        return !adult() && canEatIndependently() && absence().movedOn() && entity.life().nutrition()<60;
    }

    private boolean startFamilyHelp() {
        var helper=closestFamilyHelp();
        if(helper==null||withinFollowDistance(helper))return false;
        target=helper;task=Task.FOLLOW;taskTicks=0;
        GameplayLog.limited(entity,"family","kin.help",600,"missing kin seeking company adult={} nutrition={}",
                helper.getUUID(),entity.life().nutrition());
        return true;
    }

    private YukkuriEntity closestFamilyHelp() {
        var relations=YukkuriWorldData.get(level());
        return level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(12,4,12),other ->
                other!=entity&&other.isAlive()&&other.family().adult()&&ordinaryLandFree(other)
                &&entity.life().canRecognize(other)&&entity.hasLineOfSight(other)
                &&(Bonds.careKin(entity,other)||relations.affinity(entity.getUUID(),other.getUUID())>=10))
                .stream().min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
    }
    private YukkuriEntity closestParent() {
        if(familyTravelling())return migrationEscort();
        var parent = level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(12),other->other.isAlive()
                &&(other.getUUID().equals(mother)||other.getUUID().equals(father))&&entity.life().canRecognize(other)
                &&!other.moldExile().leavesCareToResident(entity))
                .stream().sorted(Comparator.comparingDouble(entity::distanceToSqr))
                .filter(other->ParentCarry.needsCarry(entity)||gatherAt(other)!=null||entity.life().reachable(other.blockPosition(),false)).findFirst().orElse(null);
        if (parent != null) return parent;
        UUID fosterId = fosterParent();
        return fosterId != null && level().getEntity(fosterId) instanceof YukkuriEntity foster && isActiveFoster(foster)
                && foster.life().nutrition() >= 35 && entity.distanceToSqr(foster) < 12 * 12
                && (ParentCarry.needsCarry(entity) || gatherAt(foster) != null || entity.life().reachable(foster.blockPosition(), false))
                ? foster : null;
    }
    /** Use the same real parent for a travelling child's follow and the parents' search decisions. */
    YukkuriEntity migrationEscort() {
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
        // The usual local route probe cannot cover a march gap. FOLLOW tests its actual route.
        return first;
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
    /** The one reunion rule: within the child's own follow radius and in sight. The search ends, the approach halts and the greeting plays here. */
    boolean reached(YukkuriEntity child){return entity.distanceToSqr(child)<=child.family().familyRadiusSqr()&&entity.hasLineOfSight(child);}
    /** The same arrival distance that ends this child's ordinary land follow. */
    public boolean withinFollowDistance(YukkuriEntity parent) {
        var at = gatherAt(parent);
        return at == null ? entity.distanceToSqr(parent) < familyRadiusSqr() : SurfaceApproach.arrived(entity, at);
    }
    private Vec3 gatherAt(YukkuriEntity parent) {
        var run = IntentRunner.running(parent);
        return run != null && run.intent() instanceof FamilyRendezvous meeting ? meeting.gatherAt(entity) : null;
    }
    /** Early movement practice yields to an actual parent's gathering; ordinary family-follow owns the approach. */
    boolean gathering() {
        for(var id:new UUID[]{mother,father})if(id!=null&&level().getEntity(id) instanceof YukkuriEntity parent
                &&parent.isAlive()&&entity.distanceToSqr(parent)<FAMILY_SEARCH_RANGE*FAMILY_SEARCH_RANGE) {
            var run=IntentRunner.running(parent);
            if(run!=null&&run.intent() instanceof FamilyRendezvous)return true;
        }
        return false;
    }
    private double familyRadiusSqr() {
        if(exploring()&&!familyTravelling())return Math.pow(entity.roaming().radius()+2,2);
        if(!ordinaryLandFree(entity))return FAMILY_FOLLOW_DISTANCE_SQR;
        var personality=entity.life().personality();
        double radius=FAMILY_FOLLOW_DISTANCE*personality.exploreMultiplier();
        if(entity.childhood().shouldStayWithParents())radius*=1.20-.004*personality.attachment();
        return radius*radius;
    }
    double followRadius() { return Math.sqrt(familyRadiusSqr()); }
    /** The new mutual search is only for ordinary land families that can all move freely; independent young are never recalled.
     *  A child near in the separation sense is watched, not searched: the search starts beyond it and ends at {@link #reached}. */
    private YukkuriEntity closestSeparatedDependentChild(boolean pickupOnly) {
        var candidates=new LinkedHashSet<>(level().getEntitiesOfClass(YukkuriEntity.class,
                entity.getBoundingBox().inflate(FAMILY_SEARCH_RANGE),this::canSearchForChild));
        for(var id:separation.sightedChildren())
            if(level().getEntity(id) instanceof YukkuriEntity child&&canSearchForChild(child))candidates.add(child);

        YukkuriEntity closest=null;
        double best=FAMILY_SEARCH_RANGE*FAMILY_SEARCH_RANGE;
        for(var child:candidates) {
            if(child.family().exploring()&&!child.life().hasUrgentNeed())continue;
            boolean visible=separation.observeChild(child);
            if(pickupOnly&&(!visible||!entity.hasLineOfSight(child)||!carry.pickupWanted(child)))continue;
            if(visible&&!carry.pickupWanted(child)&&!carry.needsReunion(child)
                    &&(FamilySeparation.near(entity,child)||child.settlement().canCatchUpOnFoot()||followingOnMarch(child)))continue;
            var at=separation.lastSeen(child);
            if(at==null)continue;
            double distance=entity.distanceToSqr(visible?child.position():at);
            if(distance<best){closest=child;best=distance;}
        }
        return closest;
    }
    private boolean canSearchForChild(YukkuriEntity child) {
        return ordinaryLandFree(child)&&caresFor(child)&&separation.searchingAllowed(child)&&child.childhood().shouldStayWithParents()
                &&carry.canSearchWhileCarrying(child)&&escortsMigratingChild(child)&&!safeInSharedHome(child);
    }
    private boolean followingOnMarch(YukkuriEntity child) {
        return child.family().familyTravelling() && child.family().task == Task.FOLLOW
                && child.family().target == entity && !child.getNavigation().isDone();
    }
    /** A full route, else vanilla's partial one: a parent or a lost child goes as near as the ground lets it (to the glass). */
    private boolean navigateToward(BlockPos at,double speed){
        if(entity.life().navigate(at,speed,false))return true;
        var path=entity.getNavigation().createPath(at,0);
        return path!=null&&entity.getNavigation().moveTo(path,speed);
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
    private boolean needsBedReturn(BlockPos home) {
        var occupancy=FamilyNest.bedOccupancy(entity,home);
        return entity.life().mayReturnToBed(home)&&(occupancy==null
                ? entity.distanceToSqr(Vec3.atBottomCenterOf(home))>4 : occupancy!=Occupancy.INSIDE_SUPPORTED);
    }
    /** Dependent walkers follow their real family on the march and on an aborted move's return route. */
    public boolean familyTravelling() {
        if(entity.settlement().migrating())return entity.settlement().familyMarching();
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
    /** An adult whose own BABY-stage young live at the same home: while they are unweaned she keeps near them (outings, strolls, leisure);
     *  hunger still sends her to forage through the ordinary need, and she comes back. */
    public boolean tendsUnweaned() {
        if(!adult())return false;
        var home=entity.life().homeStand();
        if(home==null)return false;
        for(var id:FamilyData.get(level()).children(entity.getUUID()))
            if(level().getEntity(id) instanceof YukkuriEntity child&&child.isAlive()&&child.family().stage()==Stage.BABY&&home.equals(child.life().homeStand()))return true;
        return false;
    }
    /** Idle roaming must not immediately undo the child's decision to wait in the family shelter. */
    public boolean allowsIdleWalk(Vec3 destination) {
        return adult()?carry.allowsIdleWalk(destination):allowsYoungWalk(destination);
    }
    /** A parent's hunger can take her out of idle watching range. Young still forage within their ordinary limits. */
    public boolean allowsForageWalk(Vec3 destination) {
        return adult()||allowsYoungWalk(destination);
    }
    private boolean allowsYoungWalk(Vec3 destination) {
        if(ParentCarry.awaitsCarrier(entity))return false;
        var bed=earlyHomeStand();
        // Vanilla idle routes start by leaving the rimmed bed. A settled dependent rests
        // there when it has arrived on its own or a nearby parent has set it down.
        if(entity.childhood().shouldStayWithParents()&&settledInFamilyBed(bed))return false;
        if(stage==Stage.BABY)return TravelRange.babyDestination(entity,BlockPos.containing(destination));
        if(!entity.childhood().shouldStayWithParents()||bed==null)return true;
        var home=entity.habitat().shelter();
        return home!=null&&home.contains(BlockPos.containing(destination));
    }
    private boolean settledInFamilyBed(BlockPos assigned) {
        if(assigned==null&&(exploring()||familyTravelling()||emergencyForaging()))return false;
        var bed=NestDoorway.at(entity,entity.position());
        // Release has reached the supported surface before vanilla sets the free child's onGround flag.
        if(bed==null||!entity.onGround()&&!SurfaceApproach.stands(entity,entity.position()))return false;
        if(assigned!=null)return assigned.equals(bed.getBlockPos());
        for(var id:new UUID[]{mother,father})
            if(id!=null&&bed.owns(id)&&level().getEntity(id) instanceof YukkuriEntity parent&&parent.isAlive()&&FamilySeparation.near(entity,parent))return true;
        return false;
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
        if(task==Task.NONE||entity.isPanicking()||entity.life().mealTicks()>0||entity.isCommandPlaying())return false;
        if(task==Task.FEED_CHILD)return feeding.canContinue();
        if(urgentNeed()&&!courtship.finishingContact())return false;
        if(task==Task.ABSENCE)return absence().active();
        if(task==Task.WATCH_HOME)return taskTicks<160 && !entity.life().isSleeping() && target.isAlive()
                && !kinOverdue(target.getUUID()) && FamilyProvision.outForFood(target);
        if(task==Task.COURT&&(courtship.receiverInterrupted()||!courtship.stillWanted()))return false;
        if(task==Task.RECEIVE_COURT&&!courtship.canReceive())return false;
        if(task==Task.FETCH_HAT)return (chore!=null||!choreHat.isRemoved()&&choreHat.catches())&&taskTicks<2400&&Conductor.roleOf(entity)==null;
        if(task==Task.COMFORT)return comfortTicks>0;
        // She stays settled with the unborn until a real need (above) takes her out or the birth's own beats begin.
        if(task==Task.BROOD){var pregnancy=brood();return pregnancy!=null&&!pregnancy.cancelled&&!pregnancy.complete()&&!birth.active();}
        if((task==Task.FOLLOW||task==Task.SEARCH||task==Task.STAY_HOME)&&ParentCarry.awaitsCarrier(entity)) {
            GameplayLog.limited(entity,"family","wait.carrier",1200,"baby waits for parent carry task={} ticks={}",task,taskTicks);
            return false;
        }
        if(task==Task.STAY_HOME) {
            var home=earlyHomeStand();return home!=null&&needsBedReturn(home);
        }
        if (task == Task.FOLLOW && target != null) {
            UUID foster = fosterParent();
            if (target.getUUID().equals(foster)) {
                if (!isActiveFoster(target) || target.life().nutrition() < 35 || withinFollowDistance(target)) return false;
            } else if (foster != null && Bonds.parentOf(target, entity)) {
                if (withinFollowDistance(target)) return false;
            } else if (earlyHomeStand()!=null&&(!seekingFamilyHelp()||Bonds.parentOf(target,entity))) return false;
        }
        if(task==Task.CARRY)return carry.carrying()&&taskTicks<2400;
        if(task==Task.SEARCH&&target!=null)return ordinaryLandFree(entity)&&target.level()==entity.level()
                &&(carry.lifting()&&target.isAlive()&&caresFor(target)&&target.childhood().shouldStayWithParents()
                &&(ordinaryLandFree(target)||target.getVehicle()==entity)
                ||canSearchForChild(target)&&(glass.active()||separation.lastSeen(target)!=null));
        if(task==Task.SEARCH&&call.waitingAtGlass())return true;
        double followRange = task == Task.FOLLOW && familyTravelling() ? FAMILY_SEARCH_RANGE * FAMILY_SEARCH_RANGE : 256;
        int timeCap = switch (task) {
            case COURT, RECEIVE_COURT -> courtship.roundTimeCap();
            case FOLLOW -> familyTravelling() ? 1200 : 240;
            default -> 240;
        };
        return taskTicks<timeCap
                &&(task==Task.SEARCH||target!=null&&target.isAlive()&&target.level()==entity.level()&&entity.distanceToSqr(target)<followRange);
    }
    public void tickGoal() {
        if(!canContinue()){
            if(task==Task.COURT&&taskTicks>=courtship.roundTimeCap())courtship.courtStopped("time_cap");
            stop();return;
        }
        taskTicks++;
        if(task==Task.SEARCH&&taskTicks==1)GameplayLog.limited(entity,"family","search.started",1200,
                "family search started target={} lastKnownPosition={} mother={} father={} nutrition={} energy={}",
                target==null?null:target.getUUID(),searchAt,mother,father,entity.life().nutrition(),entity.life().energy());
        // A child out looking for a parent (or following one) that hears her call answers it and goes where the call came from.
        if(!adult()&&(task==Task.FOLLOW||task==Task.SEARCH&&target==null)){
            var escort=familyTravelling()?migrationEscort():null;
            if (escort == null && fosterParent() != null) {
                var caregiver = closestParent();
                if (caregiver != null && (Bonds.parentOf(caregiver, entity) || !ParentCarry.needsCarry(entity))) escort = caregiver;
            }
            if(escort!=null){
                if(task!=Task.FOLLOW||target!=escort){task=Task.FOLLOW;target=escort;taskTicks=1;call.stop();}
            }else{
                var heard=call.heard();
                if(heard!=null){task=Task.SEARCH;target=null;searchAt=call.answer(heard);taskTicks=0;}
                if(call.tickAnswer())return;
            }
        }
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
                else {
                    entity.getNavigation().stop();
                    var care=FamilyNest.care(entity);
                    var out=care!=null?care.origin().add(NestDoorway.shape(care).offset(0,2)):shelter!=null?Vec3.atCenterOf(shelter.entrance()):null;
                    if(out!=null)entity.getLookControl().setLookAt(out.x,entity.getEyeY(),out.z,10,10);
                }
            }
            case COURT -> courtship.tickCourt();
            case RECEIVE_COURT -> courtship.tickReceiver();
            case FOLLOW -> {
                if(!entity.life().canRecognize(target)||withinFollowDistance(target)){stop();return;}
                entity.getLookControl().setLookAt(target,20,20);
                var at = gatherAt(target);
                if(at!=null)SurfaceApproach.walk(entity,at,1.15);
                else if(taskTicks%10==1&&!(familyTravelling()?navigateToward(target.blockPosition(),1.15)
                        :entity.life().navigate(target.blockPosition(),1.15,false)))stop();
            }
            case STAY_HOME -> {
                var home=earlyHomeStand();
                if(home==null){stop();return;}
                var at=FamilyNest.restPosition(entity,home);
                if(at==null){stop();return;}
                if(!entity.life().walkToBed(home,at,1)){stop();return;}
                if(SurfaceApproach.lastResult(entity,at)==SurfaceApproach.Result.BLOCKED){stop();wait=200;}
            }
            case FEED_CHILD -> feeding.tickCare();
            case ABSENCE -> absence().tick();
            case WATCH_HOME -> {
                if(searchAt!=null){
                    var result=SurfaceApproach.walkResult(entity,searchAt,null,.85);
                    if(result==SurfaceApproach.Result.BLOCKED){stop();return;}
                    if(result==SurfaceApproach.Result.ARRIVED){
                        GameplayLog.event(entity,"family","household watched entrance provider={} at={}",
                                target.getUUID(),GameplayLog.at(searchAt));
                        searchAt=null;
                    }
                }
                entity.getLookControl().setLookAt(target,20,20);
            }
            case WAIT_CHILD -> {
                entity.getNavigation().stop();entity.getLookControl().setLookAt(target,20,20);
                if(entity.distanceToSqr(target)<4||!entity.life().canRecognize(target))stop();
            }
            case FETCH_HAT -> tickHatChore();
            case COMFORT -> tickComfort();
            case CARRY -> { if(!carry.tickHome(taskTicks))stop(); }
            case SEARCH -> {
                if(target!=null)tickLandChildSearch();
                else {
                    if(searchAt==null||entity.distanceToSqr(searchAt)<2){stop();return;}
                    if(taskTicks%20==1&&!navigateToward(BlockPos.containing(searchAt),.9))stop();
                }
            }
            default -> { }
        }
    }
    private void tickLandChildSearch() {
        boolean visible=separation.observeChild(target);
        searchAt=separation.lastSeen(target);
        if(glass.active()&&glass.tick())return;
        if(visible&&!carry.lifting()&&taskTicks%10==1&&glass.start(target))return;
        if(carry.lifting()) {
            // A Grab in progress owns the child through the custody transfer.
            if(carry.tickPickup(target)==ParentCarry.Step.FAILED)stop();
            return;
        }
        if(searchAt==null){stop();return;}
        if(taskTicks>=1200){
            if(!visible)separation.searchedLastSight(target);
            stop();return;
        }
        if(call.tickParent(target,searchAt,visible))return;
        if(visible) {
            if(entity.hasLineOfSight(target)&&carry.pickupWanted(target)){
                if(carry.tickPickup(target)==ParentCarry.Step.FAILED)stop();
                return;
            }
            if(reached(target)){separation.reunited(target);stop();return;}
            if(taskTicks%10==1&&!navigateToward(target.blockPosition(),1.1)){stop();return;}
            if(entity.getNavigation().isDone())entity.getLookControl().setLookAt(target,20,20);
            return;
        }
        // Play the warning before leaving for the last sight. The shared walk ends at the sight or a real obstruction.
        if(taskTicks<=80&&entity.hasReaction()){SurfaceApproach.halt(entity);return;}
        var result=SurfaceApproach.walkResult(entity,searchAt,null,1.1);
        if(result==SurfaceApproach.Result.ARRIVED||result==SurfaceApproach.Result.BLOCKED) {
            GameplayLog.event(entity,"family","child search checked last sight child={} at={} outcome={}",
                    target.getUUID(),GameplayLog.at(searchAt),result);
            separation.searchedLastSight(target);
            stop();
        }
    }
    public void interruptCourtship() {
        if(task==Task.COURT||task==Task.RECEIVE_COURT)stop();
    }

    public void stop() {
        glass.stop();
        call.stop();
        if(task==Task.SEARCH&&target!=null&&!carry.lifting())SurfaceApproach.halt(entity);
        if(task==Task.COURT||task==Task.RECEIVE_COURT)entity.getNavigation().stop();
        if(task==Task.ABSENCE)absence().stop();
        if(task==Task.STAY_HOME||task==Task.ABSENCE||task==Task.WATCH_HOME)SurfaceApproach.halt(entity);
        if(chore!=null){chore.stop("family_goal_stopped");chore=null;if(task==Task.FETCH_HAT)choreRetry=level().getGameTime()+600;}
        choreHat=null;
        if(task==Task.COMFORT)comfortNear=0;
        if(task!=Task.NONE&&task!=Task.FOLLOW&&task!=Task.STAY_HOME&&task!=Task.WAIT_CHILD)GameplayLog.limited(entity,"family","task.stop."+task,200,
                "task stopped task={} target={} ticks={} carriedFood={}",
                task,target==null?null:target.getUUID(),taskTicks,feeding.carrying());
        courtship.reset();
        if(task==Task.FEED_CHILD)entity.careApproach().cancel();task=Task.NONE;target=null;searchAt=null;taskTicks=0;feeding.reset();
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
                other.life().personality().experienced(Trait.BOLDNESS,-3);other.bereave(EmotionEvent.BIRTH_LOSS);
            } else if((Bonds.careKin(entity, other)||Bonds.eitherMate(entity, other)
                    || entity.getUUID().equals(other.family().fosterParent()) && other.family().orphaned())
                    &&!other.life().isSleeping()&&entity.distanceToSqr(other)<100&&other.life().canRecognize(entity)&&other.hasLineOfSight(entity)) {
                // Kin and the mate who see it die grieve where they stand (no walk to the body).
                other.family().absence().witness(entity.getUUID(),entity.blockPosition());
                other.life().personality().experienced(Trait.BOLDNESS,-3);other.bereave(EmotionEvent.FAMILY_DEATH);
                GameplayLog.event(other,"family","witnessed kin death dead={} distance={} mate={}",entity.getUUID(),Math.round(other.distanceTo(entity)*10)/10.0,Bonds.eitherMate(entity, other));
            }
    }
    public CompoundTag save() {
        CompoundTag tag=new CompoundTag();tag.putInt("Version",3);tag.putLong("Maturity",GrowthTiming.ADULT_START);tag.putString("Stage",stage.name());tag.putLong("Age",age);tag.putLong("Nourishment",nourishment);
        tag.putLong("Lifespan",GrowthTiming.LIFESPAN);tag.putFloat("AdultScale",adultScale);tag.putFloat("Scale",scale);tag.putFloat("BirthScale",birthScale);tag.putInt("Conceptions",conceptions);tag.putInt("Recovery",recovery);
        tag.putBoolean("Initialized",initialized);tag.putBoolean("AwaitingLanding",awaitingLanding);tag.putBoolean("JuvenileVoice",juvenileVoice);
        tag.putBoolean("Sterile",sterile);tag.putInt("Stimulation",stimulation);
        tag.putBoolean("BabyTalk",babyTalk);tag.putInt("SexDrive",sexDrive);tag.putBoolean("StemDrained",stemDrained);
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
        stemDrained=tag.getBoolean("StemDrained");
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
        if(!entity.level().isClientSide&&!entity.status().identity().canConceive()){
            var stocked=brood();
            if(attachedStem(stocked)){
                FamilyData.get(level()).cancel(stocked,entity,"stock_cannot_conceive");
                gestation=null;
            }
            endBreedingPeriod();
        }
        // The birth after the gestation it belongs to; past the last child it keeps only its post-labour beats.
        birth.load(tag,saved->{if(entity.level().isClientSide)return null;var b=brood();
            return new LiveBirth(this,b==null?null:b.id,b==null?!saved.getString("Beat").equals("drop"):b.kind==FamilyData.BirthKind.LIVE);});
        stop();entity.syncFamily();
    }
}
