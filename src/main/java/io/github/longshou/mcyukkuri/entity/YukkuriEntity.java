package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.human.HumanCommerce;

import io.github.longshou.mcyukkuri.life.EcologyRoutines;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.body.BodyFood;
import io.github.longshou.mcyukkuri.body.BodyLife;
import io.github.longshou.mcyukkuri.body.BodyWound;
import io.github.longshou.mcyukkuri.dialogue.NativeDialogue;
import io.github.longshou.mcyukkuri.dialogue.SpeechCount;
import io.github.longshou.mcyukkuri.dialogue.SpeechQuirk;
import io.github.longshou.mcyukkuri.dialogue.SpeechTiming;
import io.github.longshou.mcyukkuri.entity.component.ComponentKey;
import io.github.longshou.mcyukkuri.entity.component.ComponentSpec;
import io.github.longshou.mcyukkuri.entity.component.Interrupt;
import io.github.longshou.mcyukkuri.entity.component.InterruptContext;
import io.github.longshou.mcyukkuri.entity.component.InterruptPhase;
import io.github.longshou.mcyukkuri.entity.component.LoadContext;
import io.github.longshou.mcyukkuri.entity.component.SyncSlot;
import io.github.longshou.mcyukkuri.entity.presentation.PresentationAccess;
import io.github.longshou.mcyukkuri.entity.presentation.PresentationArbiter;
import io.github.longshou.mcyukkuri.entity.presentation.PresentationGoals;
import io.github.longshou.mcyukkuri.family.BirthStaging;
import io.github.longshou.mcyukkuri.family.CareApproach;
import io.github.longshou.mcyukkuri.family.ChildhoodLife;
import io.github.longshou.mcyukkuri.family.FamilyNest;
import io.github.longshou.mcyukkuri.family.LeafSwaddle;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.family.HatCarry;
import io.github.longshou.mcyukkuri.family.IndependenceLife;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.headwear.Headwear;
import io.github.longshou.mcyukkuri.headwear.Sanction;
import io.github.longshou.mcyukkuri.house.HouseCrawlControl;
import io.github.longshou.mcyukkuri.house.YukkuriJumpControl;
import io.github.longshou.mcyukkuri.house.HouseCrawlMotion;
import io.github.longshou.mcyukkuri.house.HouseLife;
import io.github.longshou.mcyukkuri.interaction.OperationLife;
import io.github.longshou.mcyukkuri.life.AgitationLife;
import io.github.longshou.mcyukkuri.life.Facilities;
import io.github.longshou.mcyukkuri.life.SpeciesLife;
import io.github.longshou.mcyukkuri.life.SurfaceLife;
import io.github.longshou.mcyukkuri.life.WaterDistress;
import io.github.longshou.mcyukkuri.life.YukkuriLife;
import io.github.longshou.mcyukkuri.mind.MindLife;
import io.github.longshou.mcyukkuri.pet.PetLife;
import io.github.longshou.mcyukkuri.play.PlayLife;
import io.github.longshou.mcyukkuri.predator.PredatorLife;
import io.github.longshou.mcyukkuri.rare.MyonLife;
import io.github.longshou.mcyukkuri.settlement.WinteringLife;
import io.github.longshou.mcyukkuri.settlement.ForageTrip;
import io.github.longshou.mcyukkuri.settlement.HabitatLife;
import io.github.longshou.mcyukkuri.settlement.Judgement;
import io.github.longshou.mcyukkuri.settlement.SettlementLife;
import io.github.longshou.mcyukkuri.social.Cannibalism;
import io.github.longshou.mcyukkuri.social.DailyLife;
import io.github.longshou.mcyukkuri.social.GriefLife;
import io.github.longshou.mcyukkuri.social.GroupInteractionGoal;
import io.github.longshou.mcyukkuri.social.SelfOffering;
import io.github.longshou.mcyukkuri.social.TreasureLife;
import io.github.longshou.mcyukkuri.social.WoundComfort;
import io.github.longshou.mcyukkuri.social.YukkuriSocial;
import io.github.longshou.mcyukkuri.status.StatusLife;
import io.github.longshou.mcyukkuri.status.YukkuriStatus;
import io.github.longshou.mcyukkuri.subspecies.AquaticLife;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatView;
import io.github.longshou.mcyukkuri.subspecies.ShellRetraction;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
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
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
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
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.event.entity.living.LivingBreatheEvent;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;

/** Server-owned behaviour and actions. Clients only sample synchronized poses. */
public final class YukkuriEntity extends PathfinderMob implements YukkuriView {
    public static final TagKey<Item> FOOD = TagKey.create(Registries.ITEM, MCYukkuri.id("yukkuri_food"));

    private final YukkuriVariant variant;
    private final PredatorLife predator = new PredatorLife(this);

    private final SubspeciesLife subspecies = new SubspeciesLife(this);
    private AquaticLife aquaticLife;
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
    /** The accessory as a second life and the community sanction (docs/HEADWEAR.md); created in registerGoals like treasure. */
    private Headwear headwear;
    private Sanction sanction;
    public Headwear headwear() { return headwear; }
    public Sanction sanction() { return sanction; }
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
    private final SurfaceLife surfaceLife=new SurfaceLife(this);
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
    /** Unease, sweetening and the non-yukkuri syndrome (docs/EMOTION_CHAIN.md); PetLife.stress is a view of it. */
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
    /** Game time until which a hurt newborn holds still and cries; server-only, never saved. */
    private long hurtCryUntil;
    private String hurtCryCause;
    private final ShellRetraction shellRetraction = new ShellRetraction(this);
    private int reactionTicks;
    private final SpeechTiming speechTiming = new SpeechTiming();
    private final RandomSource speechRandom = RandomSource.create();
    private String reactionExpression = "neutral";
    private String reactionEvent = "";
    private String pendingSpeech = "";
    private int pendingSpeechTicks;
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
        if (aquaticLife == null) aquaticLife = new AquaticLife(this);
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
    public void interrupt(InterruptContext context, InterruptPhase phase) { components.interrupt(context, phase); }

    public <T> T componentData(SyncSlot<T> slot) { return sync().componentData(slot); }
    public <T> void syncComponentData(SyncSlot<T> slot, T value) { sync().syncComponentData(slot, value); }

    public YukkuriVariant getVariant() { return variant; }
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
    public List<YukkuriView> passengerViews() {
        var out = new ArrayList<YukkuriView>();
        for (var rider : getPassengers()) if (rider instanceof YukkuriView view) out.add(view);
        return out;
    }
    public YukkuriView carriedYukkuri() { return getFirstPassenger() instanceof YukkuriView view ? view : null; }
    public HatBoatView vehicleHat() {
        return getVehicle() instanceof HatBoatView view ? view : null;
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
    public AquaticLife aquaticLife() { return aquaticLife; }
    public WaterDistress waterDistress() { return waterDistress; }
    public AgitationLife agitation() { return agitation; }
    public CompoundTag agitationState() { return sync().agitationState(); }
    public boolean aftermathBurden() { return sync().aftermathBurden(); }
    public boolean aftermathWeak() { return sync().aftermathWeak(); }
    public String waterDistressPhase() { return sync().waterDistressPhase(); }
    public long waterDistressStartTick() { return sync().waterDistressStartTick(); }
    public boolean waterDistressEffort() { return sync().waterDistressEffort(); }
    // Compatibility views return the actual whole synchronized tag, without re-encoding snapshots.
    public CompoundTag braidRescueState() { return sync().braidRescueState(); }
    public String braidRescuePhase() { return sync().braidRescuePhase(); }
    public long braidRescueStartTick() { return sync().braidRescueStartTick(); }
    public CompoundTag boardingState() { return sync().boardingState(); }
    public CompoundTag aquaticSelfRescueState() { return sync().aquaticSelfRescueState(); }
    public int aquaticRescueTargetId() { return sync().aquaticRescueTargetId(); }
    public String aquaticAlarmActivity() { return sync().aquaticAlarmActivity(); }
    public long aquaticAlarmStartTick() { return sync().aquaticAlarmStartTick(); }
    public int aquaticAlarmSourceId() { return sync().aquaticAlarmSourceId(); }

    public YukkuriSyncState.Agitation agitationSync() { return sync().agitationSync(); }
    public YukkuriSyncState.WaterDistress waterDistressSync() { return sync().waterDistressSync(); }
    public YukkuriSyncState.AquaticAlarm aquaticAlarmSync() { return sync().aquaticAlarmSync(); }
    public YukkuriSyncState.BraidRescue braidRescueSync() { return sync().braidRescueSync(); }
    public YukkuriSyncState.Boarding boardingSync() { return sync().boardingSync(); }
    public YukkuriSyncState.SelfRescue selfRescueSync() { return sync().selfRescueSync(); }
    public YukkuriSyncState.LiveChildren liveChildrenSync() { return sync().liveChildrenSync(); }

    void syncAquaticInteraction() { sync().syncAquaticInteraction(); }
    void syncWaterDistress() { sync().syncWaterDistress(); }
    /** Only an upright, unsubmerged hat keeps its actual passengers dry. No innate water immunity. */
    public boolean shelteredInHat() {
        return getVehicle() instanceof HatBoatEntity boat && boat.protects(this);
    }
    @Override public boolean isInWater() { return !shelteredInHat() && super.isInWater(); }
    @Override public boolean isInWaterOrBubble() { return !shelteredInHat() && super.isInWaterOrBubble(); }
    @Override public boolean isEyeInFluid(TagKey<Fluid> fluid) {
        return !(fluid == FluidTags.WATER && shelteredInHat()) && super.isEyeInFluid(fluid);
    }
    /** NeoForge breathes through its fluid-type hook rather than the vanilla eye-fluid predicate. */
    public static void breatheInHat(LivingBreatheEvent event) {
        if (event.getEntity() instanceof YukkuriEntity y && y.shelteredInHat()
                && y.getEyeInFluidType() == NeoForgeMod.WATER_TYPE.value()) {
            event.setCanBreathe(true);
            event.setRefillAirAmount(y.getMaxAirSupply());
        }
    }
    public PredatorLife predator() { return predator; }
    public boolean predatorFlying() { return sync().predatorFlying(); }
    public void setPredatorFlying(boolean flying) {
        if (level().isClientSide || variant == null || !SpeciesProfile.of(variant).dangerToYukkuri() || predatorFlying() == flying) return;
        navigation.stop();
        sync().predatorFlight(flying); setNoGravity(flying); fallDistance = 0;
        navigation = flying ? new FlyingPathNavigation(this, level())
                : new YukkuriGroundNavigation(this, level());
        moveControl = flying ? new FlyingMoveControl(this, 30, true)
                : new HouseCrawlControl(this);
        setDeltaMovement(getDeltaMovement().scale(.25));
    }
    public String getAnimationId() { return sync().getAnimationId(); }
    public long getAnimationStartTick() { return sync().getAnimationStartTick(); }
    public String getExpressionId() { return sync().getExpressionId(); }
    public String getBehaviorId() { return sync().getBehaviorId(); }
    public long getBehaviorStartTick() { return sync().getBehaviorStartTick(); }
    public int nuzzlePartnerId() { return sync().nuzzlePartnerId(); }
    public int carePartnerId() { return sync().carePartnerId(); }
    public float careContactProgress() { return sync().careContactProgress(); }
    public boolean isCrawling() { return sync().isCrawling(); }
    public long getCrawlStartTick() { return sync().getCrawlStartTick(); }
    public boolean canCrawlNow() {
        return (SpeciesProfile.of(variant).houseBody() || childhood != null && !childhood.canJump() || body != null && body.footBurned()) && isAlive() &&
                !isNoAi() && !isPassenger() && !isCommandPlaying()
                && !isPanicking() && !isInWaterOrBubble() && !isOnFire() && hurtTime == 0
                && contactPose().equals("NONE");
    }
    public void syncCrawl(boolean active) { sync().syncCrawl(active); }

    @Override public void jumpFromGround() {
        if (body != null && body.footBurned() && !isInWaterOrBubble()) return; // a baked sole never springs again
        if (childhood == null || childhood.canJump() || isInWaterOrBubble() || isOnFire() || isPanicking()) super.jumpFromGround();
    }

    @Override public void travel(Vec3 input) {
        if (!level().isClientSide && family.crew().holdsBody()) return;
        if (!level().isClientSide && family.travelFlight()) return;
        if (predatorFlying()) {
            if (isControlledByLocalInstance()) {
                moveRelative(.1f, input);
                move(MoverType.SELF, getDeltaMovement());
                setDeltaMovement(getDeltaMovement().scale(.91)); fallDistance = 0;
            }
        } else if (!level().isClientSide && isCrawling() && canCrawlNow() && onGround()) {
            double distance = HouseCrawlMotion.advance(
                    level().getGameTime()-getCrawlStartTick()) * getScale() * growthScale()
                    * getAttributeValue(Attributes.MOVEMENT_SPEED) / Math.max(.0001, getAttributeBaseValue(Attributes.MOVEMENT_SPEED));
            double dx = moveControl.getWantedX()-getX(), dz = moveControl.getWantedZ()-getZ();
            double remaining = Math.hypot(dx,dz);
            double fraction = remaining > 1e-6 ? Math.min(distance, remaining)/remaining : 0;
            setDeltaMovement(dx*fraction+crawlNudgeX, getDeltaMovement().y, dz*fraction+crawlNudgeZ);
            crawlNudgeX*=.546;crawlNudgeZ*=.546;
            super.travel(Vec3.ZERO); // Vanilla move still resolves collision, gravity and steps.
        } else super.travel(!level().isClientSide && family.freeFalling() ? Vec3.ZERO : input);
    }
    public String getDialogueKey() { return sync().getDialogueKey(); }
    public long speechStart() { return sync().speechStart(); }
    public long speechEnd() { return sync().speechEnd(); }
    public int speechSequence() { return sync().speechSequence(); }
    public ItemStack getMouthItem() { return sync().getMouthItem(); }
    public BodyFood.Bite getMouthBodyFood() { return sync().getMouthBodyFood(); }
    /** Synchronizes a transient non-item mouthful after the server has committed its bite. */
    public void syncMouthBodyFood(BodyFood.Bite bite) { sync().syncMouthBodyFood(bite); }
    public ItemStack aquaticServing() { return sync().aquaticServing(); }
    public int aquaticChildId() { return sync().aquaticChildId(); }
    public int visibleMedal() { return sync().visibleMedal(); }
    public boolean hasVisibleAccessory() { return sync().hasVisibleAccessory(); }
    public boolean isPanicking() { return panicTicks > 0 && (body == null || !body.dying()); } // a dying body no longer bolts
    public ShellRetraction shellRetraction() { return shellRetraction; }
    public boolean isCommandPlaying() { return requestedAction != null; }
    public YukkuriLife life() { return life; }
    public PetLife pet() { return pet; }
    public void sayBadge(String event) {
        if (level().isClientSide || !isAlive()) return;
        long now = level().getGameTime();
        if (!speechTiming.admit(now, 80, 2)) return;
        sync().dialogue("badge.mcyukkuri.speech." + event);
        sync().speechStart(now); sync().speechEnd(speechTiming.end());
        sync().speechSequence(speechSequence() + 1);
    }
    public DailyLife dailyLife() { return dailyLife; }
    /** Species profile: behaviour coefficients, retching, speech hooks (worldview package 4). */
    public SpeciesLife species() { return species; }
    public WoundComfort woundComfort() { return woundComfort; }
    public YukkuriSocial social() { return social; }
    public GroupInteractionGoal groupInteraction() { return groupInteraction; }
    public PlayLife playLife(){return playLife;}
    public SurfaceLife surfaceLife(){return surfaceLife;}
    public void frightenFrom(YukkuriEntity threat) {
        if(level().isClientSide||!isAlive()||life.isSleeping()||hurtCrying()||hurtCrying())return;
        if(grief.protecting())return;
        if(myon.confront(threat))return; // a brave Myon turns on the threat instead of bolting
        fleeFrom(threat);
    }
    /** A chosen retreat must not be replaced by a species' stand-ground override. */
    public void fleeFrom(YukkuriEntity threat) {
        if(level().isClientSide||!isAlive()||hurtCrying())return;
        interrupt(Interrupt.FRIGHT);
        setLastHurtByMob(threat);panicTicks=100;
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
        return super.canCollideWith(other) && !(other instanceof HatBoatEntity hat
                && family != null && family.boardingThrough(hat));
    }
    public ChildhoodLife childhood() { return childhood; }
    public CareApproach careApproach() { return careApproach; }
    /** A newborn crossing from its mother's outlet to the hat is not shoved off its arc by the body it overlaps. */
    @Override public boolean isPushable() { return !(family != null && family.newbornLaunching()) && super.isPushable(); }
    @Override public void push(Entity other) {
        // Vanilla's extra proximity push otherwise separates a consenting pair before mouths can meet.
        // Actual body overlap, players and unrelated animals retain ordinary collision response.
        if(other instanceof YukkuriEntity peer&&careApproach.cooperatingWith(peer)
                &&!getBoundingBox().intersects(peer.getBoundingBox()))return;
        if(other instanceof YukkuriEntity rival&&rival!=this){bodyPush(rival);return;}
        super.push(other);
    }
    /** Bodies that move on their own may be shoved; carried, boarded or launched ones follow their own carrier. */
    boolean nudgeable() {
        return isPushable()&&!isPassenger()&&!isVehicle()&&!predatorFlying()
                &&!family.crew().holdsBody()&&!family.travelFlight()&&!family.freeFalling();
    }
    private double bodyMass() { return getBbWidth()*getBbWidth()*getBbHeight(); }
    /**
     * Mutual body push between two yukkuri. Vanilla's 0.05 impulse is lost against a walking gait, so overlapping
     * bodies press apart harder; the lighter body yields more, a resting one yields less, and both turn to their own
     * right so a head-on pair slides past instead of cancelling. Called once per overlapping tick from each side.
     */
    private void bodyPush(YukkuriEntity peer) {
        if(isPassengerOfSameVehicle(peer)||noPhysics||peer.noPhysics||(!nudgeable()&&!peer.nudgeable()))return;
        // Nest-mates lying in their shared bed may nestle (NestResting.NESTLE): they do not shove each other out of it.
        if(!level().isClientSide&&FamilyNest.sameNest(this,peer)&&FamilyNest.atAssignedNest(this)&&FamilyNest.atAssignedNest(peer))return;
        double dx=peer.getX()-getX(),dz=peer.getZ()-getZ(),reach=(getBbWidth()+peer.getBbWidth())*.5;
        double distance=Math.hypot(dx,dz);
        if(distance<.01){ // exactly stacked bodies still separate, along an axis both sides agree on
            double angle=Math.abs(getId()-peer.getId())*2.399963+Math.min(getId(),peer.getId());
            dx=Math.cos(angle);dz=Math.sin(angle);
            if(getId()>peer.getId()){dx=-dx;dz=-dz;}
            distance=1;
        }
        double ux=dx/distance,uz=dz/distance,closeness=Math.clamp(1-distance/Math.max(reach,.05),0,1);
        double strength=.035*(.5+.5*closeness),mine=bodyMass(),theirs=peer.bodyMass();
        if(nudgeable())shove(-ux,-uz,strength*Math.clamp(2*theirs/(mine+theirs),.4,1.8)*(life.isSleeping()?.6:1));
        if(peer.nudgeable())peer.shove(ux,uz,strength*Math.clamp(2*mine/(mine+theirs),.4,1.8)*(peer.life.isSleeping()?.6:1));
    }
    private void shove(double x,double z,double strength) {
        double turn=.5,c=Math.cos(turn),s=Math.sin(turn);
        nudge((x*c-z*s)*strength,(x*s+z*c)*strength);
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
    public int subspeciesFlags() { return sync().subspeciesFlags(); }
    public void syncSubspecies(int flags) { sync().syncSubspecies(flags); }
    public SettlementLife settlement() { return settlement; }
    public Judgement judgement() { return component(YukkuriComponents.JUDGEMENT); }
    /** The leaf swaddle on a child and its parents' care for it (docs/CHILDHOOD_LIFECYCLE.md). */
    public LeafSwaddle leafSwaddle() { return component(YukkuriComponents.LEAF_SWADDLE); }
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
    @Override protected void positionRider(Entity passenger,MoveFunction move){
        if(passenger instanceof YukkuriEntity child&&ParentCarry.riding(this,child)){
            var point=ParentCarry.seat(this,child);
            if(point==null)point=position();
            move.accept(passenger,point.x,point.y,point.z);
        }else super.positionRider(passenger,move);
    }
    public String contactPose(){ return sync().contactPose(); }
    public float contactTurn(){ return sync().contactTurn(); }
    public int visibleHairLoss(){ return sync().visibleHairLoss(); }
    public void syncOperation(String pose,float turn){ sync().syncOperation(pose, turn); }
    public void syncOperationAnchor(BlockPos anchor){ sync().syncOperationAnchor(anchor); }
    public BlockPos contactAnchor(){ return sync().contactAnchor(); }
    public int visibleWound(){ return sync().visibleWound(); }
    public List<BodyWound> visibleWounds() { return sync().visibleWounds(); }
    public int visibleFilling(){ return sync().visibleFilling(); }
    public void syncBody() { sync().syncBody(); }
    private <T> void setChanged(EntityDataAccessor<T> key, T value) { sync().setChanged(key, value); }
    public int visibleSigns(){ return sync().visibleSigns(); }
    /** Vanilla would reject this hit as a repeat inside the hurt cooldown (LivingEntity.hurt); the body must not analyse it. */
    public boolean repeatHitIgnored(DamageSource source,float amount){ return damage().repeatHitIgnored(source, amount); }
    public float growthScale() { return sync().growthScale(); }
    public int lifeStage() { return sync().lifeStage(); }
    public int broodCount() { return sync().broodCount(); }
    public int broodBornMask() { return sync().broodBornMask(); }
    public float broodProgress() { return sync().broodProgress(); }
    public float broodVariation() { return sync().broodVariation(); }
    public boolean hasSpentStem() { return sync().hasSpentStem(); }
    public float livePregnancyProgress() { return sync().livePregnancyProgress(); }
    public int liveDeliveryTicks() { return sync().liveDeliveryTicks(); }
    public int liveChildCount() { return sync().liveChildCount(); }
    public int liveBornMask() { return sync().liveBornMask(); }
    public YukkuriVariant liveChildVariant(int index) { return sync().liveChildVariant(index); }
    public String liveChildAssetId(int index) { return sync().liveChildAssetId(index); }
    public YukkuriVariant broodVariant(int index) { return sync().broodVariant(index); }
    public void syncFamily() { if(body!=null)body.syncStageHealth();sync().syncFamily(); }
    @Override protected EntityDimensions getDefaultDimensions(Pose pose) {
        if(family==null||subspecies==null||variant==null)return super.getDefaultDimensions(pose);
        return LiveBirthGeometry.bodyDimensions(this,growthScale(),livePregnancyProgress());
    }
    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        sync().onSyncedDataUpdated(key);
    }
    @Override public boolean isBaby() { return lifeStage()!=FamilyLife.Stage.ADULT.ordinal(); }
    @Override public boolean causeFallDamage(float distance,float multiplier,DamageSource damage) {
        return family!=null&&family.awaitingLanding()?false:super.causeFallDamage(distance,multiplier*Facilities.landing(Facilities.floor(this)),damage);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        YukkuriSync.defineSynchedData(builder);
        YukkuriComponents.defineSynchedData(builder);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 10.0)
                .add(Attributes.MOVEMENT_SPEED, 0.18)
                .add(Attributes.FLYING_SPEED, 0.30)
                .add(Attributes.FOLLOW_RANGE, 16.0)
                .add(Attributes.STEP_HEIGHT, 1.0);
    }

    @Override
    protected void registerGoals() {
        goals = new YukkuriGoals(this, goalSelector);
        goals.registerGoals();
    }

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
        HumanCommerce.tickAnimal(this);
    }

    @Override
    public void aiStep() {
        if (!level().isClientSide && !canCrawlNow()) syncCrawl(false);
        if (!isCrawling()) crawlNudgeX = crawlNudgeZ = 0;
        double movementX = getX(), movementZ = getZ();
        super.aiStep();
        double travelledX = getX() - movementX, travelledZ = getZ() - movementZ;
        if (level().isClientSide) return;
        if (!isAlive()) return;
        if (reactionTicks > 0) reactionTicks--;
        if (pendingSpeechTicks > 0 && --pendingSpeechTicks == 0) {
            if (speak(pendingSpeech) && pendingSpeech.equals("newborn")) BirthStaging.greeting(this);
        }
        if (level().getGameTime() >= speechEnd()) sync().dialogue("");
        components.tick(ComponentSpec.TickPhase.BEFORE_ALIVE_CHECK);
        if (!isAlive()) return;
        components.tick(ComponentSpec.TickPhase.AFTER_ALIVE_CHECK);
        presentation.tick(YukkuriControl.advance(this), travelledX, travelledZ);
    }

    /** Shell Marisa withdraws where it is instead of fleeing (development plan 4.4). */
    boolean shellRetracts() {
        return shellRetraction.available();
    }

    private void setBehavior(String behavior) { sync().setBehavior(behavior); }

    /** A parent whose newborn was lost at birth: a blank stare, then crying and calling out (bereaved clip). */
    public void bereave() {
        if (level().isClientSide) return;
        reactionTicks = 100; reactionExpression = "surprise"; reactionEvent = "birth_lost"; mind.felt(EmotionEvent.BIRTH_LOSS);
        pendingSpeech = "family_died"; pendingSpeechTicks = 32;
    }

    /** A reaction whose line is spoken after a delay, when the clip reaches its face (the newborn's first
     *  greeting waits for the first_wake clip to open its eyes and smile; project original timing). */
    public void showReactionDelayed(String expression, String event, int ticks, int speechDelayTicks) {
        if (level().isClientSide) return;
        reactionTicks = ticks; reactionExpression = expression; reactionEvent = event == null ? "" : event;
        pendingSpeech = reactionEvent; pendingSpeechTicks = Math.max(1, speechDelayTicks);
    }

    public boolean hasReaction() { return reactionTicks > 0; }
    public void showReaction(String expression, String dialogue, int ticks) {
        if (level().isClientSide) return;
        reactionTicks = ticks; reactionExpression = expression; reactionEvent = dialogue == null ? "" : dialogue;
        speak(dialogue);
    }

    /** Dialogue tier from the life stage: baby, juvenile and adult; an adult kept childish by voice flags stays "young". */
    public String speechTier() {
        int stage = lifeStage();
        if (stage == FamilyLife.Stage.BABY.ordinal()) return "baby";
        if (stage == FamilyLife.Stage.JUVENILE.ordinal()) return "juvenile";
        return family.youngVoice() ? "young" : "adult";
    }

    /** Say a quantity the way a yukkuri does: 1, 2, 3, then "lots" (see {@link SpeechCount}). */
    public boolean speakCount(String base, int quantity) {
        String event = SpeechCount.event(base, quantity, variant.dialogueSpecies(), speechTier());
        return event != null && speak(event);
    }

    public boolean speak(String event) {
        if (level().isClientSide || !isAlive()) return false;
        if (subspecies.congenital() != SubspeciesLife.Congenital.NORMAL) {
            long now = level().getGameTime();
            if (!speechTiming.admit(this, now, 40, NativeDialogue.priority(event))) return false;
            sync().dialogue("dialogue.mcyukkuri.deficient.babble");
            sync().speechStart(now); sync().speechEnd(speechTiming.end());
            sync().speechSequence(speechSequence() + 1);
            return true;
        }
        event = mind.voice(event); // a broken mind can only scream
        String species = variant.dialogueSpecies();
        var line = NativeDialogue.select(species, speechTier(), myon.quirk(), event, speechRandom);
        long now = level().getGameTime();
        if (line == null || !speechTiming.admit(this, now, line.ticks(), NativeDialogue.priority(event))) return false;
        sync().dialogue(line.key());
        sync().speechStart(now); sync().speechEnd(speechTiming.end());
        sync().speechSequence(speechSequence() + 1);
        return true;
    }

    /** Repeat a currently heard, catalogued line and retain its actual speaker as provenance. */
    public boolean imitateSpeech(YukkuriEntity teacher) {
        if (level().isClientSide || !isAlive() || teacher == this || !teacher.isAlive()
                || teacher.level() != level() || distanceToSqr(teacher) > 36 || !hasLineOfSight(teacher)) return false;
        if (subspecies.congenital() != SubspeciesLife.Congenital.NORMAL) return speak("imitate");
        var line = NativeDialogue.byKey(teacher.getDialogueKey());
        long now = level().getGameTime();
        if (line == null || teacher.speechEnd() <= now || !line.species().equals(teacher.getVariant().dialogueSpecies())
                || !Objects.equals(line.quirk(), line.species().equals(variant.dialogueSpecies()) ? myon.quirk()
                        : SpeechQuirk.of(line.species(), getUUID()))
                || !speechTiming.admit(now, line.ticks(), 1)) return false;
        sync().dialogue(line.key());
        sync().speechStart(now); sync().speechEnd(speechTiming.end());
        sync().speechSequence(speechSequence() + 1);
        var learned = new CompoundTag(); learned.putUUID("Teacher", teacher.getUUID());
        learned.putString("Species", line.species()); learned.putString("Key", line.key());
        learned.putLong("HeardAt", now); learned.putInt("Uses", getPersistentData().getCompound("LearnedDialect").getInt("Uses") + 1);
        getPersistentData().put("LearnedDialect", learned);
        return true;
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

    @Override
    public void heal(float amount) {
        if (body != null && !body.coreIntact()) return;
        super.heal(amount);
    }

    @Override
    public void die(DamageSource source) { damage().die(source); }

    @Override
    public void remove(Entity.RemovalReason reason) { damage().remove(reason); }

    void cancelRemovedBrood() { damage().cancelRemovedBrood(); }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) { return interactions().mobInteract(player, hand); }

    // pet harness and lead (docs/PET_SHOP.md): the lead is tied to the harness ring on the back, never to the accessory
    public int visibleWear() { return sync().visibleWear(); }
    /** Server: everything worn on the body, as YukkuriView.visibleWear bits (pet harness and cloth swaddle, leaf swaddle). */
    public int wornBits() { return pet().care().wearBits() | leafSwaddle().wearBits(); }
    public void syncWear() { sync().setWear(wornBits()); }
    /** Only a harnessed pet can be led; PetCare/LeashGuard further restrict this to the owner (the check below is the same on both sides). */
    @Override public boolean canBeLeashed() { return interactions().canBeLeashed(); }
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
        // A pre-component save has no family compound, so its vanilla attributes can only be normalized after every
        // component loader (including the legacy Body/Filling migration) has completed.
        body.syncStageHealth();
    }

    // Lifecycle seams: the single component catalogue owns their ordering.
    void syncKanroken() { sync().syncKanroken(); }
    void syncAgitation() { sync().syncAgitation(); }
    void syncHairLoss() { sync().syncHairLoss(); }
    void tickShellRetraction() { shellRetraction.tick(panicTicks); }
    void syncCarryingAndCare() { sync().syncCarryingAndCare(); }

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
    public RoamingGoal roaming() { return goals == null ? null : goals.roaming(); }
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
    void rememberHit(float incoming) { lastHurt = Math.max(lastHurt, incoming); }
    int panicTicksValue() { return panicTicks; }
    void setPanicTicks(int ticks) { panicTicks = ticks; }
    /** Project design: a hurt newborn (BABY stage) does not flee; it stays put and cries until the cry lapses. */
    public boolean hurtCrying() {
        return hurtCryUntil > level().getGameTime() && isAlive() && lifeStage() == FamilyLife.Stage.BABY.ordinal();
    }
    public boolean newbornStage() { return lifeStage() == FamilyLife.Stage.BABY.ordinal(); }
    public String hurtCryCause() { return hurtCryCause; }
    void startHurtCry(String cause, int ticks) {
        boolean fresh = !hurtCrying();
        hurtCryUntil = level().getGameTime() + ticks; hurtCryCause = cause; panicTicks = 0;
        if (fresh) GameplayLog.event(this, "childhood", "hurt cry start cause={} ticks={} health={}", cause, ticks, getHealth());
    }
    void endHurtCry(String reason) {
        if (hurtCryUntil == 0) return;
        hurtCryUntil = 0;
        GameplayLog.event(this, "childhood", "hurt cry end cause={} reason={} health={}", hurtCryCause, reason, getHealth());
    }
    YukkuriAction requestedActionValue() { return requestedAction; }
    boolean hurtBase(DamageSource source, float amount) {
        int previousHurt = hurtTime, previousDuration = hurtDuration;
        Vec3 previousMotion = getDeltaMovement();
        boolean accepted = super.hurt(source, amount);
        if (YukkuriLife.isSoaking(source)) setDeltaMovement(previousMotion);
        if (source.is(DamageTypes.STARVE)) { hurtTime = previousHurt; hurtDuration = previousDuration; }
        return accepted;
    }
    @Override public void handleDamageEvent(DamageSource source) {
        if (!source.is(DamageTypes.STARVE)) super.handleDamageEvent(source);
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
    void createHeadwear() { headwear = new Headwear(this); }
    void createAquaticLife() { aquaticLife = new AquaticLife(this); }
    void createForageTrip() { forageTrip = new ForageTrip(this); }
    void createTreasure() { treasure = new TreasureLife(this); }
    void createCannibalism() { cannibalism = new Cannibalism(this); }
    void createOffering() { offering = new SelfOffering(this); }
    void createIndependence() { independence = new IndependenceLife(this); }
    void createWintering() { wintering = new WinteringLife(this); }
}
