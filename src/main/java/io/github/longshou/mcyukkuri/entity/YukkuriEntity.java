package io.github.longshou.mcyukkuri.entity;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import io.github.longshou.mcyukkuri.body.DetachedItems;

import io.github.longshou.mcyukkuri.random.ModRandom;

import java.util.Locale;
import net.minecraft.network.chat.Component;

import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.human.HumanNpc;

import io.github.longshou.mcyukkuri.life.EcologyRoutines;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.body.BodyFood;
import io.github.longshou.mcyukkuri.body.BodyLife;
import io.github.longshou.mcyukkuri.body.BodyWound;
import io.github.longshou.mcyukkuri.dialogue.NativeDialogue;
import io.github.longshou.mcyukkuri.dialogue.SpeechCount;
import io.github.longshou.mcyukkuri.dialogue.SpeechTiming;
import io.github.longshou.mcyukkuri.life.TravelRange;
import io.github.longshou.mcyukkuri.entity.component.ComponentKey;
import io.github.longshou.mcyukkuri.entity.component.ComponentSpec;
import io.github.longshou.mcyukkuri.entity.component.Interrupt;
import io.github.longshou.mcyukkuri.entity.component.InterruptContext;
import io.github.longshou.mcyukkuri.entity.component.InterruptPhase;
import io.github.longshou.mcyukkuri.entity.component.LoadContext;
import io.github.longshou.mcyukkuri.entity.component.SyncSlot;
import io.github.longshou.mcyukkuri.entity.presentation.PresentationAccess;
import io.github.longshou.mcyukkuri.entity.presentation.Moments;
import io.github.longshou.mcyukkuri.entity.presentation.PresentationArbiter;
import io.github.longshou.mcyukkuri.entity.presentation.PresentationGoals;
import io.github.longshou.mcyukkuri.family.BirthStaging;
import io.github.longshou.mcyukkuri.family.CareApproach;
import io.github.longshou.mcyukkuri.family.ChildhoodLife;
import io.github.longshou.mcyukkuri.cognition.Skills;
import io.github.longshou.mcyukkuri.family.LeafSwaddle;
import io.github.longshou.mcyukkuri.shop.ShopWindow;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.family.FamilyNest;
import io.github.longshou.mcyukkuri.family.KinProtection;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.StatusEvents;
import io.github.longshou.mcyukkuri.status.TraceRecorder;
import io.github.longshou.mcyukkuri.family.IndependenceLife;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.headwear.Headwear;
import io.github.longshou.mcyukkuri.headwear.Sanction;
import io.github.longshou.mcyukkuri.settlement.MoldExile;
import io.github.longshou.mcyukkuri.house.HouseCrawlControl;
import io.github.longshou.mcyukkuri.house.YukkuriJumpControl;
import io.github.longshou.mcyukkuri.house.HouseCrawlMotion;
import io.github.longshou.mcyukkuri.house.HouseLife;
import io.github.longshou.mcyukkuri.interaction.OperationLife;
import io.github.longshou.mcyukkuri.life.AgitationLife;
import io.github.longshou.mcyukkuri.life.DutyClaim;
import io.github.longshou.mcyukkuri.life.Facilities;
import io.github.longshou.mcyukkuri.life.SpeciesLife;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.event.HomeTerritoryGoal;
import io.github.longshou.mcyukkuri.life.WaterDistress;
import io.github.longshou.mcyukkuri.life.YukkuriLife;
import io.github.longshou.mcyukkuri.mind.EmotionCourse;
import io.github.longshou.mcyukkuri.mind.MindLife;
import io.github.longshou.mcyukkuri.motor.BodyDriver;
import io.github.longshou.mcyukkuri.motor.Held;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.geom.Gait;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import net.neoforged.neoforge.fluids.FluidType;
import io.github.longshou.mcyukkuri.pet.PetLife;
import io.github.longshou.mcyukkuri.pet.PetOuting;
import io.github.longshou.mcyukkuri.play.PlayLife;
import io.github.longshou.mcyukkuri.predator.PredatorLife;
import io.github.longshou.mcyukkuri.rare.MyonLife;
import io.github.longshou.mcyukkuri.settlement.WinteringLife;
import io.github.longshou.mcyukkuri.settlement.ForageTrip;
import io.github.longshou.mcyukkuri.settlement.ForageGoal;
import io.github.longshou.mcyukkuri.settlement.HabitatLife;
import io.github.longshou.mcyukkuri.settlement.SettlementLife;
import io.github.longshou.mcyukkuri.settlement.VillageStrays;
import io.github.longshou.mcyukkuri.social.Cannibalism;
import io.github.longshou.mcyukkuri.social.DailyLife;
import io.github.longshou.mcyukkuri.social.GriefLife;
import io.github.longshou.mcyukkuri.social.GroupInteractionGoal;
import io.github.longshou.mcyukkuri.social.PairContact;
import io.github.longshou.mcyukkuri.social.SelfOffering;
import io.github.longshou.mcyukkuri.social.TreasureLife;
import io.github.longshou.mcyukkuri.social.WoundComfort;
import io.github.longshou.mcyukkuri.social.Kindness;
import io.github.longshou.mcyukkuri.social.YukkuriSocial;
import io.github.longshou.mcyukkuri.status.StatusLife;
import io.github.longshou.mcyukkuri.status.YukkuriStatus;
import io.github.longshou.mcyukkuri.subspecies.ShellRetraction;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.headwear.PlacedHatView;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.mind.ExcitedLeak;
import net.minecraft.world.level.block.state.BlockState;

/** Server-owned behaviour and actions. Clients only sample synchronized poses. */
public final class YukkuriEntity extends PathfinderMob implements YukkuriView {
    public static final TagKey<Item> FOOD = TagKey.create(Registries.ITEM, MCYukkuri.id("yukkuri_food"));
    /** Water plants: food for the species whose diet picks them (settlement/Diet), not for the land menu. */
    public static final TagKey<Item> WATER_FOOD = TagKey.create(Registries.ITEM, MCYukkuri.id("water_food"));

    private final YukkuriVariant variant;
    private final PredatorLife predator = new PredatorLife(this);
    /** The one action driver of this body; a READY component, constructed before any peer is read. */
    private final BodyDriver motor = new BodyDriver(this);
    public BodyDriver motor() { return motor; }

    private final SubspeciesLife subspecies = new SubspeciesLife(this);
    private final YukkuriLife life;
    private final AgitationLife agitation = new AgitationLife(this);

    private final WaterDistress waterDistress = new WaterDistress(this);
    private final PetLife pet = new PetLife(this);
    private final YukkuriSocial social;
    private final DailyLife dailyLife = new DailyLife(this);
    private final SpeciesLife species = new SpeciesLife(this);
    private final WoundComfort woundComfort = new WoundComfort(this);
    /** Created in registerGoals (it runs inside the Mob constructor, before field initialisers); deliberately no initialiser. */
    private TreasureLife treasure;
    public TreasureLife treasure() { return treasure; }
    /** The accessory as a second life and the community sanction (docs/FEATURES.md); created in registerGoals like treasure. */
    private Headwear headwear;
    private Sanction sanction;
    private MoldExile moldExile;
    public Headwear headwear() { return headwear; }
    public Sanction sanction() { return sanction; }
    public MoldExile moldExile() { return moldExile; }
    /** Drawn accessory damage and fit (Headwear.visual). */
    public int visibleHeadwear() { return sync().visibleHeadwear(); }
    public void syncHeadwear(int packed) { sync().syncHeadwear(packed); }
    /** Smell of the dead, eating remains and hunting the hatless (created in registerGoals, like treasure). */
    private Cannibalism cannibalism;
    public Cannibalism cannibalism() { return cannibalism; }
    /** Last words to kin and sacrifice against predators (static hooks plus a clock). */
    private GriefLife grief;
    void createGrief() { grief = new GriefLife(this); }
    public GriefLife grief() { return grief; }
    public void beginProtectionApproach() { if (grief.protecting()) panicTicks = 0; }
    /** "Please eat me": a parent gives its body to a starving child (created in registerGoals). */
    private SelfOffering offering;
    public SelfOffering offering() { return offering; }
    private final GroupInteractionGoal groupInteraction = new GroupInteractionGoal(this);
    private final PlayLife playLife=new PlayLife(this);
    private final FamilyLife family;
    private final ChildhoodLife childhood;
    private final CareApproach careApproach = new CareApproach(this);
    private IndependenceLife independence;
    private WinteringLife wintering;
    private final SettlementLife settlement;
    private final HabitatLife habitat;
    private final HouseLife house;
    private final BodyLife body;
    private final StatusLife status = new StatusLife(this);
    /** Unease, sweetening and the non-yukkuri syndrome (docs/FEATURES.md); PetLife.stress is a view of it. */
    private final MindLife mind = new MindLife(this);

    private final OperationLife operations;

    // Force all legacy IDs before component slots; neither path constructs an entity or component.
    static {
        YukkuriSync.initializeLegacySchema();
        YukkuriComponents.installSyncSchema(YukkuriEntity::defineSyncedValue);
    }
    /** Assigned inside Mob's constructor. Deliberately has NO initializer, including no '= null'. */
    private YukkuriComponents components;
    /** Created in registerGoals, which the Mob constructor calls before field initialisers run. */
    private MyonLife myon;

    private YukkuriAction requestedAction;
    private int panicTicks;
    private final ShellRetraction shellRetraction = new ShellRetraction(this);
    private int reactionTicks;
    /** The running reaction is held: nothing replaces it before it has run its ticks (a startle that must be seen whole). */
    private boolean reactionHeld;
    private final SpeechTiming speechTiming = new SpeechTiming();
    /** Up to 16 distinct spoken keys, oldest first. */
    private static final int SPEECH_MEMORY = 16;
    private final List<String> recentSpeech = new ArrayList<>();
    private String reactionExpression = "neutral";
    private String reactionEvent = "";
    private record DelayedCue(String event, YukkuriAction action, java.util.function.Consumer<Boolean> result) {}
    private DelayedCue pendingSpeech;
    private int pendingSpeechTicks, pendingSpeechFloor;
    // The bridge reads nothing during construction; component instances keep their original timing.
    private final PresentationArbiter presentation = new PresentationArbiter(new PresentationState());

    public YukkuriEntity(EntityType<? extends YukkuriEntity> type, Level level, YukkuriVariant variant) {
        super(type, level);
        this.variant = variant;
        this.life = new YukkuriLife(this);
        this.social = new YukkuriSocial(this);
        this.family = new FamilyLife(this);
        this.childhood = new ChildhoodLife(this);
        if (independence == null) independence = new IndependenceLife(this);
        if (wintering == null) wintering = new WinteringLife(this);
        this.settlement = new SettlementLife(this);
        this.habitat = new HabitatLife(this);
        this.house = new HouseLife(this);
        this.body = new BodyLife(this);
        this.operations = new OperationLife(this);
        this.navigation = new YukkuriGroundNavigation(this,level);
        this.moveControl = new HouseCrawlControl(this);
        this.jumpControl = new YukkuriJumpControl(this);
        setPathfindingMalus(PathType.WATER, -1);
        setPathfindingMalus(PathType.WATER_BORDER, 8);
        setPathfindingMalus(PathType.DANGER_FIRE, 16);
        setPathfindingMalus(PathType.DAMAGE_FIRE, -1);
        refreshDimensions();
        // Mob only calls registerGoals on the server. Clients only use the static sync schema.
        if (components != null) components.initializeReady();
        // A direct summon has no FamilyLife transition to trigger this.  Run only after READY components exist.
        body.syncStageHealth();
    }

    @Override public void onAddedToLevel() {
        super.onAddedToLevel();
        if (level().isClientSide) return;
        Long seed = TraceRecorder.runSeedForFirstAdd(getUUID());
        if (seed != null) ModRandom.seedEntity(this);
        VillageStrays.observe(this);
    }


    // Keep the allocation call in the owning entity class (also avoids NeoForge's foreign-owner warning).
    static <T> EntityDataAccessor<T> defineSyncedValue(EntityDataSerializer<T> serializer) {
        return SynchedEntityData.defineId(YukkuriEntity.class, serializer);
    }

    public <C> C component(ComponentKey<C> key) {
        if (components == null) throw new IllegalStateException("Components are server-owned: " + key.id());
        return components.get(key);
    }
    /** Single-phase reasons only; HURT and DEATH use explicit contexts and call-site phases. */
    public void interrupt(Interrupt reason) { interrupt(InterruptContext.of(reason), InterruptPhase.MAIN); }
    public void interrupt(InterruptContext context, InterruptPhase phase) {
        // Pain and danger end a held reaction: whatever they show next is not dropped.
        if (context.reason() == Interrupt.FRIGHT || context.reason() == Interrupt.HURT || context.reason() == Interrupt.BURNING
                || context.reason() == Interrupt.DEATH) reactionHeld = false;
        components.interrupt(context, phase);
    }

    public <T> T componentData(SyncSlot<T> slot) { return sync().componentData(slot); }
    public <T> void syncComponentData(SyncSlot<T> slot, T value) { sync().syncComponentData(slot, value); }

    /** Synchronized current action id ("" when idle); clients read it through YukkuriView. */
    public String motorAction() { return componentData(BodyDriver.MOTOR_ACTION).getString("Id"); }
    /** Synchronized start tick of the current action (clip phase). */
    public long motorStart() { return componentData(BodyDriver.MOTOR_ACTION).getLong("Start"); }
    public int motorMotionSource() { return componentData(BodyDriver.MOTOR_ACTION).getInt("Source"); }
    /** Synchronized attachment kind: 0 Free, 1 Airborne, 2 OnHost, 3 Held. */
    public byte attachKind() { return componentData(BodyDriver.ATTACH_KIND); }
    /** Synchronized carry record: which hold each carried child is in ({@link ParentCarry#HOLDS}). */
    public CompoundTag carryHolds() { return componentData(ParentCarry.HOLDS); }

    public YukkuriVariant getVariant() { return variant; }
    @Override public Component getTypeName() {
        var kind=subspeciesKind();
        return kind!=kind.behaviorKind()?Component.translatable("subspecies.mcyukkuri.kind."+kind.name().toLowerCase(Locale.ROOT)):super.getTypeName();
    }
    public SpeciesProfile profile() { return SpeciesProfile.of(variant, subspeciesKind()); }
    public float bodyYaw() { return yBodyRot; }
    public float previousBodyYaw() { return yBodyRotO; }
    public float previousHeadYaw() { return yHeadRotO; }
    public float previousYaw() { return yRotO; }
    public int ageTicks() { return tickCount; }
    public int hurtTicks() { return hurtTime; }
    public int deathTicks() { return deathTime; }
    public int vehicleId() { return getVehicle() == null ? -1 : getVehicle().getId(); }
    public YukkuriView vehicleYukkuri() { return getVehicle() instanceof YukkuriView view ? view : null; }
    public PlacedHatView vehicleHat() { return getVehicle() instanceof PlacedHatView hat ? hat : null; }
    public List<YukkuriView> passengerViews() {
        var out = new ArrayList<YukkuriView>();
        for (var rider : getPassengers()) if (rider instanceof YukkuriView view) out.add(view);
        return out;
    }
    public boolean hasPassengerId(UUID passenger) {
        return getPassengers().stream().anyMatch(rider -> rider.getUUID().equals(passenger));
    }
    public boolean hasSightTo(YukkuriView other) {
        return other instanceof Entity target && hasLineOfSight(target);
    }
    public boolean clearColliderPath(Vec3 from, Vec3 to) {
        return level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, this)).getType() == HitResult.Type.MISS;
    }
    public WaterDistress waterDistress() { return waterDistress; }
    public AgitationLife agitation() { return agitation; }
    public CompoundTag agitationState() { return sync().agitationState(); }
    @Override public CompoundTag courtshipState() { return sync().courtshipState(); }
    public int aftermathDrain() { return sync().aftermathDrain(); }
    public boolean stemDepletionWarning() { return sync().stemDepletionWarning(); }
    public String waterDistressPhase() { return sync().waterDistressPhase(); }
    public long waterDistressStartTick() { return sync().waterDistressStartTick(); }
    public boolean waterDistressEffort() { return sync().waterDistressEffort(); }
    // Compatibility views return the actual whole synchronized tag, without re-encoding snapshots.

    public YukkuriSyncState.Agitation agitationSync() { return sync().agitationSync(); }
    public YukkuriSyncState.WaterDistress waterDistressSync() { return sync().waterDistressSync(); }
    public YukkuriSyncState.LiveChildren liveChildrenSync() { return sync().liveChildrenSync(); }

    void syncWaterDistress() { sync().syncWaterDistress(); }
    public PredatorLife predator() { return predator; }
    /**
     * What this body is doing right now, as a face ("" when no activity declares one). A behavior opts in by exposing an
     * activityFace() here; the arbiter shows it above the weakness faces and below dying, leak, pain and the emotion course.
     */
    public String activityFace() { return predator.activityFace(); }
    public boolean predatorFlying() { return sync().predatorFlying(); }
    public void setPredatorFlying(boolean flying) {
        if (level().isClientSide || variant == null || !SpeciesProfile.of(variant).dangerToYukkuri() || predatorFlying() == flying) return;
        navigation.stop();
        sync().predatorFlight(flying); setNoGravity(flying); fallDistance = 0;
        if (flying) {
            var flight = new FlyingPathNavigation(this, level()) {
                @Override public boolean shouldRecomputePath(BlockPos changed) {
                    status().reactions().threat().flight().collisionChanged(changed);
                    return super.shouldRecomputePath(changed);
                }
            };
            flight.setCanPassDoors(!TravelRange.staysBehindDoors(this));
            navigation = flight;
        } else navigation = new YukkuriGroundNavigation(this, level());
        moveControl = flying ? new FlyingMoveControl(this, 30, true)
                : new HouseCrawlControl(this);
        Motor.velocity(this, getDeltaMovement().scale(.25), "predator_takeoff");
    }
    public String getAnimationId() {
        var pose = sync().pairedPose();
        return pose == PairContact.Pose.NONE ? sync().getAnimationId() : pose.clip();
    }
    public long getAnimationStartTick() { return pairedContact() ? sync().pairStart() : sync().getAnimationStartTick(); }
    public String getMomentId() {
        String id = sync().getMomentId();
        // A peer publication can replace the clip after this actor's moment was chosen.
        return id.isEmpty() || Moments.clip(id).equals(getAnimationId()) ? id : "";
    }
    public void setMoment(String id) { sync().moment(id); }
    public String getExpressionId() { return sync().getExpressionId(); }
    @Override public long leakTraceAt() { return sync().leakTraceAt(); }
    public void syncLeakTrace(long at) { sync().leakTrace(at); }
    @Override public String identityTranslationKey() { return componentData(StatusLife.IDENTITY); }
    @Override public String outlookId() { return componentData(StatusLife.OUTLOOK); }
    @Override public CompoundTag moodReadout() { return componentData(StatusLife.MOOD); }
    public String getBehaviorId() {
        var pose = sync().pairedPose();
        return pose == PairContact.Pose.NONE ? sync().getBehaviorId() : pose.behavior();
    }
    public long getBehaviorStartTick() { return pairedContact() ? sync().pairStart() : sync().getBehaviorStartTick(); }
    public int nuzzlePartnerId() { return sync().nuzzlePartnerId(); }
    public int peerRubTick() { return sync().peerRubTick(); }
    public boolean pairedContact() { return sync().pairedPose() != PairContact.Pose.NONE; }
    public void showPairContact(YukkuriEntity peer, PairContact.Pose pose, long start, int frame, float spacing) {
        sync().showPairContact(peer, pose, start, frame, spacing);
    }
    public void clearPairContact() { sync().clearPairContact(); }
    public float peerRubSpacing() { return sync().peerRubSpacing(); }
    public int carePartnerId() { return sync().carePartnerId(); }
    public BlockPos occupiedBed() { return level().isClientSide ? sync().occupiedBed() : FamilyNest.occupiedBed(this); }
    public float boardingDistance(BlockPos bed) { return level().isClientSide ? sync().boardingDistance() : life().boardingDistance(bed); }
    public float careContactProgress() { return sync().careContactProgress(); }
    public float toiletVolume() { return level().isClientSide?sync().toiletVolume():(float)DetachedItems.wasteAmount(this); }
    public boolean isCrawling() { return sync().isCrawling(); }
    public boolean runningGait(String behavior) {
        return moveControl instanceof HouseCrawlControl ground && Gait.isRunning(isCrawling(), childhood.canJump() && !BodyAbility.cannotSpring(this),
                ground.lastRequestedSpeed(), behavior.equals(Behaviors.FLEE) || behavior.equals(Behaviors.THREAT_RETREAT)
                        || behavior.equals(Behaviors.RAIN_RUN));
    }
    public long getCrawlStartTick() { return sync().getCrawlStartTick(); }
    /** Blocks the crawl gait covers per tick on average: one stroke's travel spread over its period, at this body's size and speed. */
    public double crawlSpeed() { return HouseCrawlMotion.TRAVEL_UNITS / 16 / HouseCrawlMotion.PERIOD_TICKS * crawlScale(); }
    /** Blocks the crawl gait covers this tick, on its own stroke clock from the crawl's start. */
    public double crawlStep() { return HouseCrawlMotion.advance(level().getGameTime()-getCrawlStartTick()) * crawlScale(); }
    /** Keep the crawl's short contact stroke throughout growth, with a smaller stride for young bodies. */
    private double crawlScale() {
        double growth = BodyAbility.growthPace(this);
        double mobility = getAttributeValue(Attributes.MOVEMENT_SPEED)
                / Math.max(.0001, getAttributeBaseValue(Attributes.MOVEMENT_SPEED) * Math.sqrt(growth));
        return getScale() * Math.max(1, growthScale()) * (.7 + .3 * growth)
                * mobility * BodyAbility.temperament(this) * BodyAbility.carrying(this);
    }
    public boolean canCrawlNow() { return !isPassenger() && crawler(); }
    /** The crawl is this body's gait now: on the ground, or riding a host's surface (a hat floor), where the host walk drives it. */
    public boolean crawler() {
        return (SpeciesProfile.of(variant).houseBody() || childhood != null && !childhood.canJump() || body != null && body.footBurned()) && isAlive() &&
                !isNoAi() && (!isPassenger() || getVehicle() instanceof SurfaceHost) && !isCommandPlaying()
                && !isInWaterOrBubble() && !isOnFire() && hurtTime == 0
                && contactPose().equals("NONE");
    }
    public void syncCrawl(boolean active) { sync().syncCrawl(active); }

    @Override public void jumpFromGround() {
        if (body != null && BodyAbility.cannotSpring(this) && !isInWaterOrBubble()) { // a baked sole or a frail body never springs
            if (!level().isClientSide)
                GameplayLog.limited(this, "body", "frail_jump", 200, "step jump withheld frail={} sole={} energy={} nutrition={} health={}",
                        BodyAbility.frail(this), body.footBurned(), life().energy(), life().nutrition(), getHealth());
            return;
        }
        if (childhood == null || childhood.canJump() || isInWaterOrBubble() || isOnFire()) super.jumpFromGround();
    }

    @Override public void moveRelative(float acceleration, Vec3 input) {
        Vec3 before = getDeltaMovement();
        super.moveRelative(acceleration, input);
        Vec3 after = getDeltaMovement();
        double dx = after.x - before.x, dz = after.z - before.z;
        // Vanilla ground control supplies getSpeed() as forward input as well as its acceleration.
        if (!level().isClientSide && (dx != 0 || dz != 0))
            motor.propulsion(dx, 0, dz, acceleration * Math.min(1, getSpeed()));
    }

    @Override public void move(MoverType type, Vec3 delta) {
        Vec3 from = position();
        boolean driving = type == MoverType.SELF && (xxa != 0 || zza != 0 || isCrawling());
        drivenThisTick |= driving;
        super.move(type, type == MoverType.SELF ? Motor.groundTravel(this, delta, driving) : delta);
        if (!level().isClientSide && type == MoverType.SELF) {
            motor.contact.finishMove();
            motor.stride.resolved(tickCount, delta.x, delta.y, delta.z, getX() - from.x, getY() - from.y, getZ() - from.z);
        }
    }

    /** Action sweeps and contact motion have already been solved by the motor. */
    public void moveByMotor(Vec3 delta) {
        super.move(MoverType.SELF, delta);
    }

    @Override public void travel(Vec3 input) {
        if (!level().isClientSide && motor.tickTravel(input) == TravelMode.CONSUMED) return;
        if (!level().isClientSide && family.birthTravel()) return;
        if (!level().isClientSide && fallDistance > 1 && !isInWaterOrBubble()) predator.catchFall();
        if (ParentCarry.bracingForPickup(this)) {
            SurfaceApproach.halt(this);
            crawlNudgeX = crawlNudgeZ = 0;
            super.travel(Vec3.ZERO);
        } else if (predatorFlying()) {
            if (isControlledByLocalInstance()) {
                Vec3 controlled = level().isClientSide ? null : predator.flightStep(getDeltaMovement());
                Motor.fly(this, input, controlled);
                fallDistance = 0;
            }
        } else if (!level().isClientSide && isCrawling() && canCrawlNow() && onGround()) {
            double distance = crawlStep() * species.stride(moveControl.getSpeedModifier());
            double dx = moveControl.getWantedX()-getX(), dz = moveControl.getWantedZ()-getZ();
            double remaining = Math.hypot(dx,dz);
            double fraction = remaining > 1e-6 ? Math.min(distance, remaining)/remaining : 0;
            motor.propulsion(dx * fraction, 0, dz * fraction, distance);
            Motor.velocity(this, new Vec3(dx*fraction+crawlNudgeX, getDeltaMovement().y, dz*fraction+crawlNudgeZ), "crawl");
            crawlNudgeX*=.546;crawlNudgeZ*=.546;
            super.travel(Vec3.ZERO); // Vanilla move still resolves collision, gravity and steps.
        } else {
            if (!level().isClientSide) Motor.brakeFledglingFall(this);
            super.travel(!level().isClientSide && family.freeFalling() ? Vec3.ZERO : input);
        }
    }

    /** While {@link Motor#dismount} runs the passenger stays where it actually stands; vanilla must not move it to the carrier centre. */
    @Override public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        if (Motor.DISMOUNTING.get()) return passenger.position();
        return super.getDismountLocationForPassenger(passenger);
    }

    public String getDialogueKey() { return sync().getDialogueKey(); }
    public long speechStart() { return sync().speechStart(); }
    public long speechEnd() { return sync().speechEnd(); }
    public int speechSequence() { return sync().speechSequence(); }
    public String speechVoiceTier() { return sync().speechVoiceTier(); }
    public ItemStack getMouthItem() { return sync().getMouthItem(); }
    public boolean foodOnBraid() { return level().isClientSide ? sync().foodOnBraid() : life.braidHasFood(); }
    public BlockPos braidMealBin() { return sync().braidMealBin(); }
    public Vec3 dishContact() { return sync().dishContact(); }
    public BodyFood.Bite getMouthBodyFood() { return sync().getMouthBodyFood(); }
    /** Synchronizes a transient non-item mouthful after the server has committed its bite. */
    public void syncMouthBodyFood(BodyFood.Bite bite) { sync().syncMouthBodyFood(bite); }
    public int visibleMedal() { return sync().visibleMedal(); }
    public boolean visibleDisqualified() { return sync().visibleDisqualified(); }
    @Override public Optional<UUID> reservedBy() { return sync().reservedBy(); }
    public void showReservation(UUID player) { sync().showReservation(player); }
    public boolean hasVisibleAccessory() { return sync().hasVisibleAccessory(); }
    public boolean isPanicking() { return panicTicks > 0 && (body == null || !body.dying()); } // a dying body no longer bolts
    public ShellRetraction shellRetraction() { return shellRetraction; }
    public boolean isCommandPlaying() { return requestedAction != null; }
    public YukkuriLife life() { return life; }
    public PetLife pet() { return pet; }
    @Override public boolean removeWhenFarAway(double distance) {
        return !VillageStrays.rooted(this) && super.removeWhenFarAway(distance);
    }
    private PetOuting outing;
    public PetOuting outing() { if (outing == null) outing = new PetOuting(this); return outing; }
    public DailyLife dailyLife() { return dailyLife; }
    /** Species profile: behaviour coefficients, retching, speech hooks (worldview package 4). */
    public SpeciesLife species() { return species; }
    public WoundComfort woundComfort() { return woundComfort; }
    public YukkuriSocial social() { return social; }
    public Kindness kindness() { return goals.kindness(); }
    public ForageGoal forage() { return goals.forage(); }
    public HomeTerritoryGoal homeTerritory() { return goals.homeTerritory(); }
    public GroupInteractionGoal groupInteraction() { return groupInteraction; }
    public PlayLife playLife(){return playLife;}
    public void frightenFrom(YukkuriEntity threat) {
        if(level().isClientSide||!isAlive()||life.isSleeping()||status.reactions().threat().holdingStance())return;
        if(grief.protecting())return;
        if(myon.confront(threat))return; // a brave Myon turns on the threat instead of bolting
        fleeFrom(threat);
    }
    /** A chosen retreat must not be replaced by a species' stand-ground override. */
    public void fleeFrom(LivingEntity threat) {
        if(level().isClientSide||!isAlive())return;
        interrupt(Interrupt.FRIGHT);
        setLastHurtByMob(threat);panicTicks=100;
        if (threat instanceof YukkuriEntity y && y.profile().dangerToYukkuri()) mind.feeling().frightened(1);
        life.involuntaryRelease("fright", false);
    }
    /** Actual bullying shares the same numeric choice as a peer's accepted attack. */
    public boolean respondToBullying(YukkuriEntity threat) {
        if(level().isClientSide||!isAlive()||body().dying())return false;
        boolean defend=myon.confront(threat)||getVariant()!=YukkuriVariant.MYON&&groupInteraction().defense().respond(threat);
        if(defend)panicTicks=0;
        else fleeFrom(threat);
        return defend;
    }
    public FamilyLife family() { return family; }
    @Override public boolean canCollideWith(Entity other) {
        // During a checked rim transfer the detailed hat surface replaces its coarse entity box.
        return super.canCollideWith(other) && !(other instanceof PlacedHatEntity hat
                && family != null && family.boardingThrough(hat));
    }
    /** A host that keeps this rider dry (SurfaceHost.shelters): every water check, drowning included, sees it out of the water. */
    @Override public boolean isInWater() { return super.isInWater() && !Attach.sheltered(this); }
    /** Raw fluid query for observation, without the host shelter override. */
    public boolean rawInWater() { return super.isInWater(); }
    @Override public boolean canDrownInFluidType(FluidType type) { return !Attach.sheltered(this) && super.canDrownInFluidType(type); }
    public ChildhoodLife childhood() { return childhood; }
    public CareApproach careApproach() { return careApproach; }
    /** A newborn crossing from its mother's outlet to the hat is not shoved off its arc by the body it overlaps. */
    @Override public boolean isPushable() { return !(family != null && family.newbornLaunching()) && super.isPushable(); }
    @Override public void push(Entity other) {
        if (other instanceof YukkuriEntity peer) {
            if (!level().isClientSide && peer != this) {
                pushedBy(peer);
                peer.pushedBy(this);
            }
        } else super.push(other);
    }
    /** Bodies that move on their own may be shoved; carried, boarded or launched ones follow their own carrier. */
    public boolean nudgeable() {
        return isPushable()&&!isPassenger()&&!isVehicle()&&!predatorFlying()&&!family.freeFalling();
    }
    private void pushedBy(YukkuriEntity peer) {
        var motion = getDeltaMovement();
        motor.contact.pushedBy(peer, isCrawling() ? crawlNudgeX : motion.x,
                isCrawling() ? crawlNudgeZ : motion.z);
    }
    /** A walking nudge. The crawl gait rewrites the horizontal velocity every tick, so crawlers keep a decaying carry. */
    public void nudge(double x,double z) {
        if(level().isClientSide)return;
        if(isCrawling()){crawlNudgeX+=x;crawlNudgeZ+=z;}
        else push(x,0,z);
    }
    private double crawlNudgeX,crawlNudgeZ;
    public IndependenceLife independence() { return independence; }
    public WinteringLife wintering() { return wintering; }
    public SubspeciesLife subspecies() { return subspecies; }
    /** Myon traits (saved tic, stick sword, bravery); inert for other species except the saved tic slot. */
    public MyonLife myon() { return myon; }
    public boolean visibleKanroken() { return sync().visibleKanroken(); }
    /** The synchronized stack she holds; empty when she holds none. Where it is: {@link #heldPlace()}. */
    public ItemStack getHeldItem() { return sync().getHeldItem(); }
    /** Where the stack she holds physically is, as the server synchronized it (the client draws it there only). */
    public Held heldPlace() { return sync().held(); }
    /** The server's record of where her kit is: her custody's place and spot, the hat it lies in, and the beat moving it now. */
    public Held heldRecord() {
        return heldRecord(hatKit());
    }
    Held heldRecord(ItemStack kit) {
        if (kit.isEmpty()) return Held.NONE;
        var custody = life.custody();
        var move = motor().current() == null ? null : motor().current().heldMove();
        var beat = move == null ? null : move.beat();
        var place = custody.kitPlace();
        boolean into = move != null && beat.to() == Held.Place.HAT;
        var spot = place == Held.Place.HAT ? custody.kitSpot() : into ? move.spot() : null;
        var dir = place == Held.Place.HAT ? custody.kitDir() : into ? move.dir() : null;
        var hat = spot == null ? null : PlacedHatEntity.find(this);
        return new Held(place, hat == null ? -1 : hat.getId(), hat == null ? null : spot, hat == null ? null : dir, beat);
    }
    /** The one stack her hats keep for her and she therefore holds: the worn hat's kit, else her own set-down hat's. */
    public ItemStack hatKit() {
        var kit = life.custody().kit();
        if (!kit.isEmpty()) return kit;
        var hat = PlacedHatEntity.find(this);
        return hat == null ? ItemStack.EMPTY : hat.kit();
    }
    public int subspeciesFlags() { return sync().subspeciesFlags(); }
    public void syncSubspecies(int flags) { sync().syncSubspecies(flags); }
    /** Menu preview subject only; never added to a level. */
    public void preview(int flags, String clip, long start, String face) {
        if (!level().isClientSide) throw new IllegalStateException("Preview subject is client-only");
        sync().previewSubspecies(flags);
        sync().animationId(clip);
        sync().animationStart(start);
        sync().expression(face);
    }
    public SettlementLife settlement() { return settlement; }
    /** The leaf swaddle on a child and its parents' care for it (docs/FEATURES.md). */
    public LeafSwaddle leafSwaddle() { return component(YukkuriComponents.LEAF_SWADDLE); }
    public DutyClaim duty() { return component(YukkuriComponents.DUTY); }
    public Skills skills() { return component(YukkuriComponents.SKILLS); }
    /** Shop stock at its window: customers met by its own drives, a neighbour's departure seen off (docs/FEATURES.md). */
    public ShopWindow shopWindow() { return component(YukkuriComponents.SHOP_WINDOW); }
    public HabitatLife habitat() { return habitat; }
    private ForageTrip forageTrip;
    /** Foraging trips of a household with young (father far, mother when short); read by status and the museum. */
    public ForageTrip forageTrip() { return forageTrip; }

    @Override public SpawnGroupData finalizeSpawn(ServerLevelAccessor level,
            DifficultyInstance difficulty, MobSpawnType reason,
            SpawnGroupData data) {
        var result = super.finalizeSpawn(level, difficulty, reason, data);
        subspecies.onNaturalSpawn(reason);
        if (reason == MobSpawnType.NATURAL || reason == MobSpawnType.CHUNK_GENERATION) {
            EcologyRoutines.naturalSpawn(this);
            DomainHooks.spawned(this, reason == MobSpawnType.NATURAL ? "natural" : "chunk_generation");
        }
        return result;
    }
    public HouseLife house(){return house;}
    public ItemStack getCarriedItem(){ return sync().getCarriedItem(); }
    public BodyLife body(){return body;}
    /** The status hub's per-individual part: lasting marks, perception and reactions (see the status package). */
    public StatusLife status(){return status;}
    public MindLife mind(){return mind;}
    /** Dirt patches and scars drawn on the skin (synced; kinds dirt and scar). */
    public List<BodyWound> visibleMarks(){ return sync().visibleMarks(); }
    public void syncMarks(CompoundTag marks){ sync().syncMarks(marks); }
    public OperationLife operations(){return operations;}
    @Override protected boolean canAddPassenger(Entity passenger){
        return ParentCarry.canSeat(this,passenger);
    }
    @Override public LivingEntity getControllingPassenger(){return null;}
    /** A rider's seat belongs to the carrier's route, including seats outside its collision box; a real hit still releases it. */
    @Override public boolean isInWall(){return !(getVehicle() instanceof YukkuriEntity)&&super.isInWall();}
    /** Raw collision query for observation, without the carrier override. */
    public boolean rawInWall(){return super.isInWall();}
    /** A young held in a Reimu's mouth is not drawn, so it is not picked either: the click goes to its carrier. */
    @Override public boolean isPickable(){return super.isPickable()&&!ParentCarry.hiddenInMouth(this);}
    @Override protected void positionRider(Entity passenger,MoveFunction move){
        if(passenger instanceof YukkuriEntity child&&ParentCarry.riding(this,child)){
            var point=ParentCarry.seat(this,child);
            // No clear seat this tick: the child stays where it last was clear, never inside the carrier's own body.
            if(point==null)point=child.position();
            move.accept(passenger,point.x,point.y,point.z);
        }else super.positionRider(passenger,move);
    }
    public String contactPose(){ return sync().contactPose(); }
    public float contactTurn(){ return sync().contactTurn(); }
    public int visibleHairLoss(){ return sync().visibleHairLoss(); }
    public void syncOperation(String pose,float turn){ sync().syncOperation(pose, turn); }
    public void syncOperationAnchor(BlockPos anchor){ sync().syncOperationAnchor(anchor); }
    public BlockPos contactAnchor(){ return sync().contactAnchor(); }
    public void syncOperationWound(UUID wound){sync().syncOperationWound(wound);}
    public UUID contactWound(){return sync().contactWound();}
    public void syncTableTool(String pose){sync().syncTableTool(pose);}
    public String tableTool(){return sync().tableTool();}
    public int visibleWound(){ return sync().visibleWound(); }
    public List<BodyWound> visibleWounds() { return sync().visibleWounds(); }
    public int visibleFilling(){ return sync().visibleFilling(); }
    public void syncBody() { sync().syncBody(); }
    private <T> void setChanged(EntityDataAccessor<T> key, T value) { sync().setChanged(key, value); }
    public int visibleSigns(){ return sync().visibleSigns(); }
    public boolean visibleSyndromePrevention() { return sync().visibleSyndromePrevention(); }
    /** Vanilla would reject this hit as a repeat inside the hurt cooldown (LivingEntity.hurt); the body must not analyse it. */
    public boolean repeatHitIgnored(DamageSource source,float amount){ return damage().repeatHitIgnored(source, amount); }
    public float growthScale() { return sync().growthScale(); }
    public int lifeStage() { return sync().lifeStage(); }
    public int broodCount() { return sync().broodCount(); }
    public int broodBornMask() { return sync().broodBornMask(); }
    public float broodProgress() { return sync().broodProgress(); }
    public float broodProgress(float partialTick) { return sync().broodProgress(partialTick); }
    public float broodVariation() { return sync().broodVariation(); }
    public boolean hasSpentStem() { return sync().hasSpentStem(); }
    public float livePregnancyProgress() { return sync().livePregnancyProgress(); }
    public int liveDeliveryTicks() { return sync().liveDeliveryTicks(); }
    public int liveChildCount() { return sync().liveChildCount(); }
    public float birthChildScale(int index) { return sync().birthChildScale(index); }
    public int liveBornMask() { return sync().liveBornMask(); }
    public YukkuriVariant liveChildVariant(int index) { return sync().liveChildVariant(index); }
    public String liveChildAssetId(int index) { return sync().liveChildAssetId(index); }
    public YukkuriVariant broodVariant(int index) { return sync().broodVariant(index); }
    public void syncFamily() { if(body!=null)body.syncStageHealth();sync().syncFamily(); }
    @Override protected EntityDimensions getDefaultDimensions(Pose pose) {
        if(family==null||subspecies==null||variant==null)return super.getDefaultDimensions(pose);
        return LiveBirthGeometry.bodyDimensions(this,growthScale(),livePregnancyProgress());
    }
    @Override public boolean fudgePositionAfterSizeChange(EntityDimensions previous) {
        // Live gestation normally checks the next body at these feet. Explicit scenario changes may still collide.
        if(level().noCollision(this,getBoundingBox()))return true;
        Vec3 before=position();
        GameplayLog.limited(this,"move","resize_started",200,
                "body resize collision search started oldWidth={} width={} oldHeight={} height={} at={}",
                previous.width(),getBbWidth(),previous.height(),getBbHeight(),before);
        long started=System.nanoTime();
        boolean moved=BodyResizeSpace.adjust(this,previous);
        long millis=(System.nanoTime()-started)/1_000_000;
        GameplayLog.limited(this,"move","resize_result",200,
                "body resize collision search finished oldWidth={} width={} elapsedMs={} adjusted={} from={} to={} reason={}",
                previous.width(),getBbWidth(),millis,moved,before,position(),moved?"space_found":"no_free_position");
        return moved;
    }
    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        sync().onSyncedDataUpdated(key);
    }
    @Override public boolean isBaby() { return lifeStage()!=FamilyLife.Stage.ADULT.ordinal(); }
    @Override
    protected void checkFallDamage(double dy, boolean landed, BlockState surface, BlockPos at) {
        float startle = ExcitedLeak.STARTLED_FALL_DISTANCE;
        if (!landed && !isInWaterOrBubble() && fallDistance < startle && fallDistance - dy >= startle)
            mind.spike(ExcitedLeak.Stimulus.DROP);
        super.checkFallDamage(dy, landed, surface, at);
    }
    @Override public boolean causeFallDamage(float distance,float multiplier,DamageSource damage) {
        if (predator != null && predator.canCatchFall()) return false;
        return family!=null&&family.awaitingLanding()?false:super.causeFallDamage(childhood.fallingHeight(distance),multiplier*Facilities.landing(Facilities.floor(this)),damage);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        ModRandom.construct(this);
        YukkuriSync.defineSynchedData(builder);
        YukkuriComponents.defineSynchedData(builder);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 10.0)
                .add(Attributes.MOVEMENT_SPEED, Gait.GROUND_ATTRIBUTE)
                .add(Attributes.FLYING_SPEED, Gait.FLIGHT_ATTRIBUTE)
                .add(Attributes.FOLLOW_RANGE, 16.0)
                .add(Attributes.STEP_HEIGHT, 1.0);
    }

    @Override
    protected void registerGoals() {
        kinProtection = new KinProtection(this);
        goals = new YukkuriGoals(this, goalSelector);
        goals.registerGoals();
    }

    public KinProtection kinProtection() { return kinProtection; }

    /** One exclusive track: changing action or returning to idle discards every previous pose. */
    public boolean playAction(String id) {
        if (level().isClientSide || !isAlive()) return false;
        var action = YukkuriAction.byId(id);
        if (action.isEmpty()) return false;
        if (action.get()==YukkuriAction.SHELL_RETRACT
                &&(!shellRetraction.hasShell()||isInWaterOrBubble()||isOnFire()))return false;
        interrupt(Interrupt.COMMAND);
        YukkuriAction next = action.get();
        long elapsedRetractionTicks = 0;
        if (id.startsWith("anatomy_")&&id.endsWith("_retract")) {
            String extension = id.replace("_retract", "_extend");
            if (requestedAction == null || !requestedAction.id().equals(extension)) {
                // Retracting an already hidden part must not reveal its fully extended first frame.
                next = YukkuriAction.IDLE;
            } else {
                double extensionProgress = Mth.clamp((level().getGameTime() - getAnimationStartTick())
                        / (requestedAction.duration() * 20.0), 0.0, 1.0);
                // Both source clips use symmetric smoothstep curves; reverse from the current pose.
                elapsedRetractionTicks = Math.round((1.0 - extensionProgress) * next.duration() * 20.0);
            }
        }
        requestedAction = next == YukkuriAction.IDLE ? null : next;
        panicTicks = 0;
        navigation.stop();
        setAnimation(next, true);
        sync().animationStart(level().getGameTime() - elapsedRetractionTicks);
        if (next == YukkuriAction.PUFF) speak("puff");
        return true;
    }

    void setAnimation(YukkuriAction action, boolean restart) { sync().setAnimation(action, restart); }

    /** The shell controller samples one existing clip; holding does not restart its inward movement. */
    public void poseShellRetraction(int frame) {
        if(level().isClientSide||!shellRetraction.hasShell()||frame<0)return;
        setAnimation(YukkuriAction.SHELL_RETRACT,false);
        sync().animationStart(level().getGameTime()-frame);
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide) sync().syncBodyYaw(yBodyRot);
        HumanCommerce.tickAnimal(this);
    }

    /** The motor owns the body's facing on the server; the client draws the facing it synchronized, never vanilla's own estimate
     *  (which lags a turn in place by up to 50 degrees, so every braid, mouth and brim point the server chose was drawn turned away). */
    @Override
    protected float tickHeadTurn(float yRot, float animStep) {
        if (!level().isClientSide) {
            // Flying predators keep vanilla's fast turn; they never carry and their flee would be slowed otherwise.
            if (predatorFlying()) return super.tickHeadTurn(yRot, animStep);
            Motor.bodyTurn(this);
            return animStep;
        }
        yBodyRot = sync().bodyYaw();
        return animStep;
    }

    private boolean drivenThisTick;

    @Override
    public void aiStep() {
        if (!level().isClientSide && !crawler()) syncCrawl(false);
        if (!isCrawling()) crawlNudgeX = crawlNudgeZ = 0;
        double movementX = getX(), movementZ = getZ();
        boolean wasDeciding = BodyDriver.deciding();
        BodyDriver.deciding(true);
        try { super.aiStep(); } finally { BodyDriver.deciding(wasDeciding); }
        double travelledX = getX() - movementX, travelledZ = getZ() - movementZ;
        if (level().isClientSide) return;
        if (!isAlive()) return;
        species.finishStride(motor.stride.ran(tickCount));
        boolean firstReactionTick = reactionStartedAt == level().getGameTime();
        if (!firstReactionTick && reactionTicks > 0 && --reactionTicks == 0 && reactionAfter != null) {
            var follow = reactionAfter;
            var event = reactionEvent;
            showReaction(follow, reactionExpression, "");
            setAnimation(follow, true);
            reactionEvent = event;
            reactionHeld = true;
        }
        if (!firstReactionTick && pendingSpeechTicks > 0 && --pendingSpeechTicks == 0) {
            var cue = pendingSpeech;
            pendingSpeech = null;
            int floor = pendingSpeechFloor;
            pendingSpeechFloor = 0;
            // A newborn's first words: its kind's childish hello when it has one, else its newborn line (FamilyLife.firstWords).
            if (cue.action() == null) {
                boolean first = cue.event().equals("newborn");
                String words = first ? family.firstWords() : cue.event();
                if (speak(words, first && words.equals("hello") ? "young" : speechTier(), false, null, floor) && first) BirthStaging.greeting(this);
            } else if (reactionAction() == cue.action()) cue.result().accept(speakCue(cue.event()));
        }
        if (level().getGameTime() >= speechEnd()) sync().dialogue("");
        components.tick(ComponentSpec.TickPhase.BEFORE_ALIVE_CHECK);
        if (!isAlive()) return;
        components.tick(ComponentSpec.TickPhase.AFTER_ALIVE_CHECK);
        if (reactionTicks > 0 && reactionEvent.equals("need_tell") && !status().reactions().allowsNeedTell()) cancelReaction();
        // A shove or the neighbour-separation shift is contact, not travel: it must not flip a standing clip to the walk.
        boolean driven = drivenThisTick;
        boolean passive = !driven && (motor.contact.recoiling() || motor.contact.pushedThisTick(level().getGameTime()));
        drivenThisTick = false;
        presentation.tick(YukkuriControl.advance(this), travelledX, travelledZ, passive, driven);
        sync().syncHeldItem();
    }

    /** Shell Marisa withdraws where it is instead of fleeing (development plan 4.4). */
    public boolean shellRetracts() {
        return shellRetraction.available();
    }

    private void setBehavior(String behavior) { sync().setBehavior(behavior); }

    /** The shown reaction is grief over a death (lost newborn, died kin, dying kin): the face is not replaced by another errand. */
    public boolean grieving() {
        return bereaving() || reactionTicks > 0 && (reactionEvent.equals("family_died") || reactionEvent.equals("kin_dying"));
    }

    public boolean bereaving() { return reactionTicks > 0 && reactionEvent.equals("birth_lost"); }

    /** A death in the family before its eyes (a newborn lost at birth, a kin dying in sight): a blank stare, then crying and calling out (bereaved clip). */
    public void bereave(EmotionEvent loss, UUID lost, BlockPos where) {
        if (level().isClientSide) return;
        reactionTicks = 100; reactionHeld = true; reactionExpression = "surprise"; reactionEvent = "birth_lost"; mind.felt(loss);
        reactionAction = reactionAfter = null;
        pendingSpeech = new DelayedCue("family_died", null, null); pendingSpeechTicks = 32;
        mind.feeling().mourn(lost, where);
    }

    /** A reaction whose line is spoken after a delay, when the clip reaches its face (the newborn's first
     *  greeting waits for the first_wake clip to open its eyes and smile; project original timing). */
    public void showReactionDelayed(String expression, String event, int ticks, int speechDelayTicks) {
        showReactionDelayed(null, expression, event, ticks, speechDelayTicks, null, null);
    }

    /** As above, with a priority floor for the delayed line, so a beat that must be heard is not swallowed by a competing cue. */
    public void showReactionDelayed(String expression, String event, int ticks, int speechDelayTicks, int priorityFloor) {
        if (showReactionDelayed(null, expression, event, ticks, speechDelayTicks, null, null)) pendingSpeechFloor = priorityFloor;
    }

    public boolean showReactionDelayed(YukkuriAction action, String expression, String event, int ticks, int speechDelayTicks,
                                       YukkuriAction follow, java.util.function.Consumer<Boolean> spoken) {
        if (level().isClientSide || held() && !urgent(event)) return false;
        reactionAction = action;
        reactionAfter = follow;
        reactionTicks = ticks; reactionHeld = action != null; reactionExpression = expression; reactionEvent = event == null ? "" : event;
        pendingSpeech = new DelayedCue(reactionEvent, action, spoken); pendingSpeechTicks = Math.max(1, speechDelayTicks);
        pendingSpeechFloor = 0;
        if (action != null) {
            reactionStartedAt = level().getGameTime();
            setAnimation(action, true);
        }
        return true;
    }

    public void showNeedTell(YukkuriAction action, String face, int ticks) {
        startReaction(face, "need_tell", ticks);
        reactionAction = action;
    }

    public boolean hasReaction() { return reactionTicks > 0 && !reactionEvent.equals("need_tell"); }
    private YukkuriAction reactionAction, reactionAfter;
    private long reactionStartedAt = -1;
    public YukkuriAction reactionAction() { return reactionTicks > 0 ? reactionAction : null; }
    public boolean reactingTo(String event) { return reactionTicks > 0 && reactionEvent.equals(event); }
    public void cancelReaction() {
        reactionTicks = 0;
        reactionAction = reactionAfter = null;
    }

    /** An ordinary reaction pose. Movement, active contact and danger can still take priority. */
    public boolean showReaction(YukkuriAction action, String expression, String dialogue) {
        return showReaction(action, expression, dialogue, Math.max(1, (int) Math.ceil(action.duration() * 20)));
    }

    public boolean showReaction(YukkuriAction action, String expression, String dialogue, int ticks) {
        if (!showReaction(expression, dialogue, ticks)) return false;
        reactionAction = action;
        return true;
    }

    /** A reaction with a timed line at the start of the clip. */
    public void showReactionCue(YukkuriAction action, String expression, String event) {
        if (level().isClientSide || held() && !urgent(event)) return;
        startReaction(expression, event, action.durationTicks());
        reactionAction = action;
        speakCue(event);
    }

    /** A newborn's first wake after it landed (the first_wake clip and its first greeting). */
    public boolean firstWaking() { return reactionTicks > 0 && "newborn".equals(reactionEvent); }
    /** A reaction and its line; while a held one runs it is dropped, unless it is pain or danger ({@link #forceReaction}).
     *  An empty or {@code null} dialogue is a face-only reaction: it shows the face and says nothing. */
    public boolean showReaction(String expression, String dialogue, int ticks) {
        if (level().isClientSide || held() && !urgent(dialogue)) return false;
        forceReaction(expression, dialogue, ticks);
        return true;
    }
    /** A reaction that ends any held one (pain, danger, panic); its line is spoken, or nothing when it has none
     *  ({@code ""} or {@code null}, the entry callers use for a face-only reaction). */
    public void forceReaction(String expression, String dialogue, int ticks) {
        if (level().isClientSide) return;
        startReaction(expression, dialogue, ticks);
        speak(reactionEvent);
    }

    /** Stop the speech already projected to clients, including a recording longer than its subtitle. */
    public void interruptSpeech() {
        pendingSpeech = null;
        pendingSpeechTicks = 0;
        speechTiming.interrupt();
        sync().dialogue("");
        sync().speechEnd(0);
        sync().speechSequence(speechSequence() + 1);
    }

    public void struckReaction(YukkuriAction action, String face, String line, int ticks) {
        reactionStartedAt = level().getGameTime();
        startReaction(face, line, ticks);
        speak(line, speechTier(), false, null, NativeDialogue.priority("wounded"));
        reactionAction = action;
        reactionHeld = true;
        setAnimation(action, true);
    }

    private void startReaction(String expression, String dialogue, int ticks) {
        reactionAction = reactionAfter = null;
        reactionTicks = ticks; reactionHeld = false; reactionExpression = expression; reactionEvent = dialogue == null ? "" : dialogue;
    }
    public void forceReaction(YukkuriAction action, String expression, String dialogue) {
        forceReaction(expression, dialogue, action.durationTicks());
        reactionAction = action;
    }
    /** The same reaction, held for all its ticks: any reaction but pain or danger shown meanwhile is dropped, so the face cannot
     *  flip back and forth. */
    public void holdReaction(String expression, String dialogue, int ticks) {
        if (level().isClientSide || held()) return;
        forceReaction(expression, dialogue, ticks);
        reactionHeld = true;
    }
    private boolean held() { return reactionHeld && reactionTicks > 0; }
    /** Pain and danger, the reactions a held one gives way to: the hurt line and the urgent speech classes (wounds, fright, loss). */
    private static boolean urgent(String event) { return event != null && (event.equals("hurt") || NativeDialogue.priority(event) >= 3); }

    /** Dialogue tier from the life stage: baby, juvenile and adult; an adult kept childish by voice flags stays "young". */
    public String speechTier() {
        int stage = lifeStage();
        if (stage == FamilyLife.Stage.BABY.ordinal()) return "baby";
        if (stage == FamilyLife.Stage.JUVENILE.ordinal()) return "juvenile";
        return family.youngVoice() ? "young" : "adult";
    }

    /** Which wording fits this individual right now: a bold, pleased one; a wary, low one; or the plain line. */
    public String speechStyle() {
        var feeling = mind.feeling();
        if (feeling.subdued()) return "timid";
        if (feeling.kind() == EmotionCourse.Kind.JOY) return "proud";
        var temperament = life.personality().temperament();
        if (mind.unease() >= 55 || temperament.is(Gate.TIMID)) return "timid";
        if (temperament.is(Gate.BOLD) && mind.unease() < 40) return "proud";
        return "plain";
    }
    /** A line can still sound like an echo once the nearby quiet gap ends. */
    private Set<String> nearbySpeech(long now) {
        Set<String> heard = new HashSet<>();
        for (YukkuriEntity other : level().getEntitiesOfClass(YukkuriEntity.class, getBoundingBox().inflate(8),
                y -> y != this && y.isAlive() && distanceToSqr(y) <= 64 && y.speechEnd() != 0
                        && now < y.speechEnd() + 200)) {
            heard.add(other.getDialogueKey());
        }
        return heard;
    }

    /** Say a quantity up to this body's counting ability (see {@link SpeechCount}). */
    public boolean speakCount(String base, int quantity) {
        String event = SpeechCount.event(base, quantity, skills().speechCap());
        return event != null && speak(event);
    }

    public boolean speak(String event) { return speak(event, speechTier()); }

    /** A timed presentation line cannot consume the random stream of the behavior it accompanies. */
    public boolean speakCue(String event) {
        return speakCue(event, null);
    }

    public boolean speakCue(String event, String style) {
        return speakCue(event, style, 0);
    }

    public boolean speakCue(String event, String style, int priorityFloor) {
        boolean spoken = speak(event, speechTier(), true, style, priorityFloor);
        if (StatusEvents.trace()) GameplayLog.event(this, "speech", "cue event={} spoken={}", event, spoken);
        return spoken;
    }

    /** Say a line of this event in the given voice tier (its fallback chain as {@link NativeDialogue#select}). */
    public boolean speak(String event, String tier) {
        return speak(event, tier, false, null, 0);
    }

    private boolean speak(String event, String tier, boolean timedCue, String style, int priorityFloor) {
        // An empty event is "no line": a reaction that shows only its face says nothing at all, in every voice
        // (a deficient yukkuri's babble is a line for a real event, not for a missing one).
        if (level().isClientSide || !isAlive() || event.isEmpty()) return false;
        long now = level().getGameTime();
        if (subspecies.congenital() != SubspeciesLife.Congenital.NORMAL) {
            int priority = Math.max(priorityFloor, NativeDialogue.priority(event));
            if (!speechTiming.canSpeak(this, now, priority, timedCue)) return false;
            speechTiming.begin(now, 40, priority);
            showSpeech(event, "dialogue.mcyukkuri.deficient.babble", tier, now);
            return true;
        }
        event = mind.voice(event); // a broken mind can only scream
        var choices = NativeDialogue.choices(variant.dialogueSpecies(), tier, myon.quirk(), event);
        int priority = Math.max(priorityFloor, NativeDialogue.priority(event));
        if (choices.isEmpty() || !speechTiming.canSpeak(this, now, priority, timedCue)) return false;
        var line = choices.size() == 1 ? choices.getFirst() : NativeDialogue.select(choices, style == null ? speechStyle() : style, recentSpeech,
                nearbySpeech(now), RandomSource.create(getUUID().getLeastSignificantBits() ^ now ^ event.hashCode()));
        speechTiming.begin(now, line.ticks(), priority);
        showSpeech(event, line.key(), tier, now);
        return true;
    }

    private void showSpeech(String event, String key, String tier, long now) {
        recentSpeech.remove(key);
        if (recentSpeech.size() == SPEECH_MEMORY) recentSpeech.removeFirst();
        recentSpeech.add(key);
        sync().dialogue(key);
        sync().speechVoiceTier(tier);
        sync().speechStart(now); sync().speechEnd(speechTiming.end());
        sync().speechSequence(speechSequence() + 1);
        if (StatusEvents.trace()) GameplayLog.event(this, "speech", "line event={} key={} until={} voiceTier={}", event, key, speechTiming.end(), tier);
    }

    /** Marks only the immediately following synchronous hurt/die call, never persistent data. */
    public void markSukkiriExhaustion() { damage().markSukkiriExhaustion(); }

    public boolean hurtInBout(DamageSource source,float amount) { return damage().hurtInBout(source, amount); }
    public boolean hurtInternal(DamageSource source,float amount) { return damage().hurtInternal(source,amount); }
    public boolean hurtTerminal(DamageSource source,float amount) { return damage().hurtTerminal(source,amount); }
    public BodyFood.Bite hurtBite(DamageSource source,float amount) { return damage().hurtBite(source,amount); }
    public boolean hurtBiteWound(DamageSource source,float amount) { return damage().hurtBiteWound(source,amount); }
    @Override
    public boolean hurt(DamageSource source, float amount) { return damage().hurt(source, amount); }
    public BodyWound hurtForAttachment(DamageSource source,float amount) { return damage().hurtForAttachment(source,amount); }

    @Override
    public void heal(float amount) {
        if (body != null && !body.coreIntact()) return;
        super.heal(amount);
    }

    @Override
    public void die(DamageSource source) { damage().die(source); }

    @Override
    public void remove(Entity.RemovalReason reason) { damage().remove(reason); }

    @Override
    public void onRemovedFromLevel() {
        recentSpeech.clear();
        super.onRemovedFromLevel();
    }

    void cancelRemovedBrood() { damage().cancelRemovedBrood(); }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) { return interactions().mobInteract(player, hand); }

    // pet harness and lead (docs/FEATURES.md): the lead is tied to the harness ring on the back, never to the accessory
    public int visibleWear() { return sync().visibleWear(); }
    /** Server: everything worn on the body, as YukkuriView.visibleWear bits (pet harness and cloth swaddle, leaf swaddle, plain-gap paper cover). */
    public int wornBits() { return pet().care().wearBits() | leafSwaddle().wearBits() | life().paperCoverWearBits(); }
    public void syncWear() { sync().setWear(wornBits()); }
    /** Only a harnessed pet can be led; InteractionDispatch checks who may attach the lead. */
    @Override public boolean canBeLeashed() { return interactions().canBeLeashed(); }
    @Override public boolean handleLeashAtDistance(Entity holder, float distance) {
        // Outings use neither the vanilla moving restriction nor its close-range navigation.
        return holder instanceof HumanNpc npc && npc.strollingWith(this) || super.handleLeashAtDistance(holder, distance);
    }
    @Override public void closeRangeLeashBehaviour(Entity holder) {
        if (holder instanceof HumanNpc npc && npc.strollingWith(this)) return;
        if (life().pursuingFoodOnLead()) return;
        if (shouldStayCloseToLeashHolder() && !isPanicking() && !life().isSleeping()
                && pet().care().walk().followTurn(holder, followLeashSpeed())) return;
        super.closeRangeLeashBehaviour(holder);
    }
    @Override protected Vec3 getLeashOffset() { return interactions().getLeashOffset(); }
    /** It does not follow while something is urgent or it is busy: the lead only holds it back past 6 blocks. */
    @Override protected boolean shouldStayCloseToLeashHolder() { return interactions().shouldStayCloseToLeashHolder(); }
    /** The lead goes back into the hands that held it instead of lying on the ground. */
    @Override public void dropLeash(boolean broadcast, boolean dropItem) { interactions().dropLeash(broadcast, dropItem); }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("YukkuriVersion", YukkuriComponents.SAVE_VERSION);
        components.save(tag);
        // Command poses and transient anatomy states never survive a world reload.
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        // Entity.load restores NeoForgeData before this override (NeoForge 21.1 / MC 1.21.1).
        getPersistentData().remove("SukkiriExhaustion");
        damage().resetLoadedExhaustion();
        // Invalidate send signatures before component load, including equal-value set/no-callback loads.
        // Decoded snapshots still describe entityData; retain them until a slot actually changes.
        sync().invalidateSentStructures();
        components.load(new LoadContext(tag, tag.getInt("YukkuriVersion")));
        // Vanilla attributes can only be normalized after every component loader has completed.
        body.syncStageHealth();
    }

    // Lifecycle seams: the single component catalogue owns their ordering.
    void syncKanroken() { sync().syncKanroken(); }
    void syncAgitation() { sync().syncAgitation(); }
    void syncHairLoss() { sync().syncHairLoss(); }
    void tickShellRetraction() { shellRetraction.tick(panicTicks); }
    void syncCare() { sync().syncCare(); }

    void syncAppearance() { sync().syncAppearance(); }

    void tickFireAndShellCommand() {
        if (isOnFire() && panicTicks < 100) {
            panicTicks = 100;
            interrupt(Interrupt.BURNING);
        }
        if(requestedAction==YukkuriAction.SHELL_RETRACT
                &&(!shellRetraction.hasShell()||isInWaterOrBubble()||isOnFire()))requestedAction=null;
    }

    void clearRequestedAction() { requestedAction = null; }
    void resetDeathPresentation() { sync().resetDeathPresentation(); }

    void resetLoadedCommands() {
        requestedAction = null;
        panicTicks = reactionTicks = 0;
        shellRetraction.reset();
    }

    void resetLoadedPresentation() { sync().resetLoadedPresentation(); }

    void saveDeathRecorded(CompoundTag tag) { damage().saveDeathRecorded(tag); }
    void loadDeathRecorded(CompoundTag tag) { damage().loadDeathRecorded(tag); }
    void saveDebugGoals(CompoundTag tag) { goals.saveDebugGoals(tag); }

    void saveStatusView(CompoundTag tag) {
        if (level() instanceof ServerLevel) try {
            var view = YukkuriStatus.snapshot(this);
            view.put("Presentation", presentation.debugSnapshot());
            tag.put("YukkuriStatusView", view);
        } catch (RuntimeException ignored) {}
    }

    /** Private adapter, not another owner of command/reaction/panic or synchronized state. */
    private final class PresentationState implements PresentationAccess {
        @Override public YukkuriEntity owner() { return YukkuriEntity.this; }
        @Override public PresentationGoals.Running runningGoals() { return goals.runningGoals(); }
        @Override public YukkuriAction requestedAction() { return requestedAction; }
        @Override public int reactionTicks() { return reactionTicks; }
        @Override public String reactionEvent() { return reactionEvent; }
        @Override public String reactionExpression() { return reactionExpression; }
        @Override public void behavior(String behavior) { setBehavior(behavior); }
        @Override public void animation(YukkuriAction action) { setAnimation(action, false); }
        @Override public void animationId(String animation) { sync().animationId(animation); }
        @Override public void animationStart(long tick) { sync().animationStart(tick); }
        @Override public void expression(String expression) { sync().expression(expression); }
    }

    // Lazy owner-only helpers also work from superclass callbacks; no field initializer may replace them.
    private YukkuriSync sync;
    private YukkuriDamage damage;
    private YukkuriInteractions interactions;
    // Assigned only inside Mob.registerGoals; it owns the selector's exact Goal identity bindings.
    private YukkuriGoals goals;
    private KinProtection kinProtection;
    public RoamingGoal roaming() { return goals == null ? null : goals.roaming(); }
    /** Actual selector entries only; observing never starts or probes a Goal. */
    public List<String> runningGoalIds() { return goals.runningGoalIds(); }
    public boolean canJoinDeparture() { return goals.canJoinDeparture(); }
    public String competingMoveGoalId() { return goals.competingMoveGoalId(); }
    private YukkuriSync sync() {
        if (sync == null) sync = new YukkuriSync(this);
        return sync;
    }
    private YukkuriDamage damage() {
        if (damage == null) damage = new YukkuriDamage(this);
        return damage;
    }
    private YukkuriInteractions interactions() {
        if (interactions == null) interactions = new YukkuriInteractions(this);
        return interactions;
    }

    // These hooks expose the inherited field/base operation, never a recursively dispatched facade.
    PathNavigation directNavigation() { return navigation; }
    float lastHurtValue() { return lastHurt; }
    int panicTicksValue() { return panicTicks; }
    public void setPanicTicks(int ticks) { panicTicks = ticks; }
    public boolean newbornStage() { return lifeStage() == FamilyLife.Stage.BABY.ordinal(); }
    YukkuriAction requestedActionValue() { return requestedAction; }
    boolean hurtBase(DamageSource source, float amount) {
        int previousHurt = hurtTime, previousDuration = hurtDuration;
        Vec3 previousMotion = getDeltaMovement();
        boolean accepted = super.hurt(source, amount);
        if (YukkuriLife.isWaterDamage(source)) Motor.velocity(this, previousMotion, "water_hurt");
        if (source.is(DamageTypes.STARVE) || YukkuriLife.isWaterDamage(source)) { hurtTime = previousHurt; hurtDuration = previousDuration; }
        return accepted;
    }
    @Override public void handleDamageEvent(DamageSource source) {
        if (!source.is(DamageTypes.STARVE) && !YukkuriLife.isWaterDamage(source)) super.handleDamageEvent(source);
    }
    @Override protected void playHurtSound(DamageSource source) {
        if (!source.is(DamageTypes.STARVE)) super.playHurtSound(source);
    }
    void dieBase(DamageSource source) { super.die(source); }
    void removeBase(Entity.RemovalReason reason) { super.remove(reason); }
    InteractionResult mobInteractBase(Player player, InteractionHand hand) { return super.mobInteract(player, hand); }
    void dropLeashBase(boolean broadcast, boolean dropItem) { super.dropLeash(broadcast, dropItem); }
    void initializeComponents() { components = new YukkuriComponents(this); }
    void registerComponentGoals(GoalSelector selector, PresentationGoals presentationGoals) {
        components.registerGoals(selector, presentationGoals);
    }
    void createMyon() { myon = new MyonLife(this); }
    void createSanction() { sanction = new Sanction(this); }
    void createMoldExile() { moldExile = new MoldExile(this); }
    void createHeadwear() { headwear = new Headwear(this); }
    void createForageTrip() { forageTrip = new ForageTrip(this); }
    void createTreasure() { treasure = new TreasureLife(this); }
    void createCannibalism() { cannibalism = new Cannibalism(this); }
    void createOffering() { offering = new SelfOffering(this); }
    void createIndependence() { independence = new IndependenceLife(this); }
    void createWintering() { wintering = new WinteringLife(this); }
}
