package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.dialogue.NativeDialogue;
import io.github.longshou.mcyukkuri.dialogue.SpeechTiming;
import io.github.longshou.mcyukkuri.life.YukkuriLife;
import io.github.longshou.mcyukkuri.social.YukkuriSocial;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.EnumSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.TemptGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/** Server-owned behaviour and actions. Clients only sample synchronized poses. */
public final class YukkuriEntity extends PathfinderMob {
    public static final TagKey<Item> FOOD = TagKey.create(Registries.ITEM, MCYukkuri.id("yukkuri_food"));
    private static final EntityDataAccessor<String> ANIMATION = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Long> ANIMATION_START = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<String> EXPRESSION = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> BEHAVIOR = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Long> BEHAVIOR_START = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Integer> NUZZLE_PARTNER = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> CARE_PARTNER = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> CARE_PROGRESS = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> CRAWLING = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Long> CRAWL_START = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<String> DIALOGUE = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Long> SPEECH_START = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Long> SPEECH_END = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Integer> SPEECH_SEQUENCE = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<ItemStack> MOUTH = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<ItemStack> AQUATIC_SERVING = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Integer> AQUATIC_CHILD = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> MEDAL = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> ACCESSORY = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> GROWTH_SCALE = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> LIFE_STAGE = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> BROOD_COUNT = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> BROOD_BORN = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> BROOD_TYPES = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> BROOD_PROGRESS = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> BROOD_VARIATION = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> SPENT_STEM = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> AFTERMATH_STATE = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> LIVE_PROGRESS = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> LIVE_DELIVERY = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<CompoundTag> LIVE_CHILDREN = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private final YukkuriVariant variant;
    private final io.github.longshou.mcyukkuri.predator.PredatorLife predator = new io.github.longshou.mcyukkuri.predator.PredatorLife(this);
    private static final EntityDataAccessor<Boolean> PREDATOR_FLIGHT = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> SUBSPECIES = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.INT);
    private final io.github.longshou.mcyukkuri.subspecies.SubspeciesLife subspecies = new io.github.longshou.mcyukkuri.subspecies.SubspeciesLife(this);
    private io.github.longshou.mcyukkuri.subspecies.AquaticLife aquaticLife;
    private final YukkuriLife life;
    private final io.github.longshou.mcyukkuri.life.AgitationLife agitation = new io.github.longshou.mcyukkuri.life.AgitationLife(this);
    private static final EntityDataAccessor<CompoundTag> AGITATION = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private final io.github.longshou.mcyukkuri.life.WaterDistress waterDistress = new io.github.longshou.mcyukkuri.life.WaterDistress(this);
    /** One server phase clock, including actual effort/rest beats, survives observer and chunk changes. */
    private static final EntityDataAccessor<CompoundTag> WATER_DISTRESS = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<CompoundTag> BRAID_RESCUE = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<CompoundTag> AQUATIC_ALARM = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<CompoundTag> HAT_BOARDING = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<CompoundTag> AQUATIC_SELF_RESCUE = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private final io.github.longshou.mcyukkuri.pet.PetLife pet = new io.github.longshou.mcyukkuri.pet.PetLife(this);
    private final YukkuriSocial social;
    private final io.github.longshou.mcyukkuri.social.DailyLife dailyLife = new io.github.longshou.mcyukkuri.social.DailyLife(this);
    private final io.github.longshou.mcyukkuri.life.SpeciesLife species = new io.github.longshou.mcyukkuri.life.SpeciesLife(this);
    private final io.github.longshou.mcyukkuri.social.WoundComfort woundComfort = new io.github.longshou.mcyukkuri.social.WoundComfort(this);
    /** Created in registerGoals (it runs inside the Mob constructor, before field initialisers); deliberately no initialiser. */
    private io.github.longshou.mcyukkuri.social.TreasureLife treasure;
    public io.github.longshou.mcyukkuri.social.TreasureLife treasure() { return treasure; }
    /** The accessory as a second life and the community sanction (docs/HEADWEAR.md); created in registerGoals like treasure. */
    private io.github.longshou.mcyukkuri.headwear.Headwear headwear;
    private io.github.longshou.mcyukkuri.headwear.Sanction sanction;
    public io.github.longshou.mcyukkuri.headwear.Headwear headwear() { return headwear; }
    public io.github.longshou.mcyukkuri.headwear.Sanction sanction() { return sanction; }
    /** Drawn accessory damage and fit (Headwear.visual). */
    private static final EntityDataAccessor<Integer> HEADWEAR = SynchedEntityData.defineId(YukkuriEntity.class, EntityDataSerializers.INT);
    public int visibleHeadwear() { return entityData.get(HEADWEAR); }
    public void syncHeadwear(int packed) { if (entityData.get(HEADWEAR) != packed) entityData.set(HEADWEAR, packed); }
    /** Smell of the dead, eating remains and hunting the hatless (created in registerGoals, like treasure). */
    private io.github.longshou.mcyukkuri.social.Cannibalism cannibalism;
    public io.github.longshou.mcyukkuri.social.Cannibalism cannibalism() { return cannibalism; }
    /** Last words to kin and sacrifice against predators (static hooks plus a clock). */
    private final io.github.longshou.mcyukkuri.social.GriefLife grief = new io.github.longshou.mcyukkuri.social.GriefLife(this);
    public io.github.longshou.mcyukkuri.social.GriefLife grief() { return grief; }
    /** "Please eat me": a parent gives its body to a starving child (created in registerGoals). */
    private io.github.longshou.mcyukkuri.social.SelfOffering offering;
    public io.github.longshou.mcyukkuri.social.SelfOffering offering() { return offering; }
    private final io.github.longshou.mcyukkuri.social.GroupInteractionGoal groupInteraction = new io.github.longshou.mcyukkuri.social.GroupInteractionGoal(this);
    private final io.github.longshou.mcyukkuri.play.PlayLife playLife=new io.github.longshou.mcyukkuri.play.PlayLife(this);
    private final io.github.longshou.mcyukkuri.life.SurfaceLife surfaceLife=new io.github.longshou.mcyukkuri.life.SurfaceLife(this);
    private final FamilyLife family;
    private final io.github.longshou.mcyukkuri.family.ChildhoodLife childhood;
    private final io.github.longshou.mcyukkuri.family.CareApproach careApproach = new io.github.longshou.mcyukkuri.family.CareApproach(this);
    private io.github.longshou.mcyukkuri.family.IndependenceLife independence;
    private io.github.longshou.mcyukkuri.season.WinteringLife wintering;
    private final io.github.longshou.mcyukkuri.settlement.SettlementLife settlement;
    private final io.github.longshou.mcyukkuri.settlement.HabitatLife habitat;
    private final io.github.longshou.mcyukkuri.house.HouseLife house;
    private final io.github.longshou.mcyukkuri.body.BodyLife body;
    private final io.github.longshou.mcyukkuri.status.StatusLife status = new io.github.longshou.mcyukkuri.status.StatusLife(this);
    /** Unease, sweetening and the non-yukkuri syndrome (docs/EMOTION_CHAIN.md); PetLife.stress is a view of it. */
    private final io.github.longshou.mcyukkuri.mind.MindLife mind = new io.github.longshou.mcyukkuri.mind.MindLife(this);
    private static final EntityDataAccessor<CompoundTag> MARKS=SynchedEntityData.defineId(YukkuriEntity.class,EntityDataSerializers.COMPOUND_TAG);
    private java.util.List<io.github.longshou.mcyukkuri.body.BodyWound> marksCache=java.util.List.of();
    private final io.github.longshou.mcyukkuri.interaction.OperationLife operations;
    private static final EntityDataAccessor<Integer> WOUND=SynchedEntityData.defineId(YukkuriEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<CompoundTag> WOUNDS=SynchedEntityData.defineId(YukkuriEntity.class,EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<Integer> FILLING=SynchedEntityData.defineId(YukkuriEntity.class,EntityDataSerializers.INT);
    /** Lasting body states drawn on the skin (mould stage, baked sole, broken core, dying): body.BodySigns. */
    private static final EntityDataAccessor<Integer> BODY_SIGNS=SynchedEntityData.defineId(YukkuriEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> BANDAGED=SynchedEntityData.defineId(YukkuriEntity.class,EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<ItemStack> CARRYING=SynchedEntityData.defineId(YukkuriEntity.class,EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<String> CONTACT_POSE=SynchedEntityData.defineId(YukkuriEntity.class,EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Float> CONTACT_TURN=SynchedEntityData.defineId(YukkuriEntity.class,EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<java.util.Optional<net.minecraft.core.BlockPos>> CONTACT_ANCHOR=SynchedEntityData.defineId(YukkuriEntity.class,EntityDataSerializers.OPTIONAL_BLOCK_POS);
    private static final EntityDataAccessor<Integer> HAIR_LOSS=SynchedEntityData.defineId(YukkuriEntity.class,EntityDataSerializers.INT);
    /** Worn pet gear: bit 0 harness, bit 1 swaddle (docs/PET_SHOP.md section 12). */
    private static final EntityDataAccessor<Integer> WEAR=SynchedEntityData.defineId(YukkuriEntity.class,EntityDataSerializers.INT);
    /** A Myon's stick sword (rare/MyonLife), shown in the mouth by the client. */
    private static final EntityDataAccessor<Boolean> KANROKEN=SynchedEntityData.defineId(YukkuriEntity.class,EntityDataSerializers.BOOLEAN);
    /** Created in registerGoals, which the Mob constructor calls before field initialisers run. */
    private io.github.longshou.mcyukkuri.rare.MyonLife myon;
    private boolean deathRecorded;
    private YukkuriAction requestedAction;
    private int panicTicks;
    private final io.github.longshou.mcyukkuri.subspecies.ShellRetraction shellRetraction = new io.github.longshou.mcyukkuri.subspecies.ShellRetraction(this);
    private int reactionTicks;
    private final SpeechTiming speechTiming = new SpeechTiming();
    private final net.minecraft.util.RandomSource speechRandom = net.minecraft.util.RandomSource.create();
    private String reactionExpression = "neutral";
    private String reactionEvent = "";
    private String pendingSpeech = "";
    private int pendingSpeechTicks;

    public YukkuriEntity(EntityType<? extends YukkuriEntity> type, Level level, YukkuriVariant variant) {
        super(type, level);
        this.variant = variant;
        this.life = new YukkuriLife(this);
        this.social = new YukkuriSocial(this);
        this.family = new FamilyLife(this);
        this.childhood = new io.github.longshou.mcyukkuri.family.ChildhoodLife(this);
        if (independence == null) independence = new io.github.longshou.mcyukkuri.family.IndependenceLife(this);
        if (wintering == null) wintering = new io.github.longshou.mcyukkuri.season.WinteringLife(this);
        if (aquaticLife == null) aquaticLife = new io.github.longshou.mcyukkuri.subspecies.AquaticLife(this);
        this.settlement = new io.github.longshou.mcyukkuri.settlement.SettlementLife(this);
        this.habitat = new io.github.longshou.mcyukkuri.settlement.HabitatLife(this);
        this.house = new io.github.longshou.mcyukkuri.house.HouseLife(this);
        this.body = new io.github.longshou.mcyukkuri.body.BodyLife(this);
        this.operations = new io.github.longshou.mcyukkuri.interaction.OperationLife(this);
        this.navigation = new io.github.longshou.mcyukkuri.subspecies.AquaticNavigation(this,level);
        this.moveControl = new io.github.longshou.mcyukkuri.house.HouseCrawlControl(this);
        setPathfindingMalus(PathType.WATER, -1);
        setPathfindingMalus(PathType.WATER_BORDER, 8);
        setPathfindingMalus(PathType.DANGER_FIRE, 16);
        setPathfindingMalus(PathType.DAMAGE_FIRE, -1);
        refreshDimensions();
    }

    public YukkuriVariant getVariant() { return variant; }
    public io.github.longshou.mcyukkuri.subspecies.AquaticLife aquaticLife() { return aquaticLife; }
    public io.github.longshou.mcyukkuri.life.WaterDistress waterDistress() { return waterDistress; }
    public io.github.longshou.mcyukkuri.life.AgitationLife agitation() { return agitation; }
    public CompoundTag agitationState() { return entityData.get(AGITATION); }
    public boolean aftermathBurden() { return entityData.get(AFTERMATH_STATE) > 0; }
    public boolean aftermathWeak() { return entityData.get(AFTERMATH_STATE) == 2; }
    public String waterDistressPhase() {
        var data = entityData.get(WATER_DISTRESS);
        return data.contains("Phase") ? data.getString("Phase") : "IDLE";
    }
    public long waterDistressStartTick() { return entityData.get(WATER_DISTRESS).getLong("Start"); }
    public boolean waterDistressEffort() { return entityData.get(WATER_DISTRESS).getBoolean("Effort"); }
    public CompoundTag braidRescueState() { return entityData.get(BRAID_RESCUE); }
    public String braidRescuePhase() {
        var data=braidRescueState();return data.contains("Phase")?data.getString("Phase"):"IDLE";
    }
    public long braidRescueStartTick() { return braidRescueState().getLong("Start"); }
    public CompoundTag boardingState() { return entityData.get(HAT_BOARDING); }
    public CompoundTag aquaticSelfRescueState() { return entityData.get(AQUATIC_SELF_RESCUE); }
    public int aquaticRescueTargetId() {
        var data=entityData.get(AQUATIC_ALARM);return data.contains("RescueTargetId")?data.getInt("RescueTargetId"):-1;
    }
    public String aquaticAlarmActivity() {
        var data=entityData.get(AQUATIC_ALARM);return data.contains("Activity")?data.getString("Activity"):"idle";
    }
    public long aquaticAlarmStartTick() { return entityData.get(AQUATIC_ALARM).getLong("Start"); }
    public int aquaticAlarmSourceId() {
        var data=entityData.get(AQUATIC_ALARM);return data.contains("SourceId")?data.getInt("SourceId"):-1;
    }
    private void syncAquaticInteraction() {
        var rescue=family.braidRescueState();
        if(!rescue.equals(entityData.get(BRAID_RESCUE)))entityData.set(BRAID_RESCUE,rescue);
        var boarding=family.boardingState();
        if(!boarding.equals(entityData.get(HAT_BOARDING)))entityData.set(HAT_BOARDING,boarding);
        var selfRescue=aquaticLife.selfRescue().syncState();
        if(!selfRescue.equals(entityData.get(AQUATIC_SELF_RESCUE)))entityData.set(AQUATIC_SELF_RESCUE,selfRescue);
        var controller=aquaticLife.familyLife();var alarm=new CompoundTag();
        String activity=controller.alarmActivity();
        if(!activity.equals("idle")){
            alarm.putString("Activity",activity);alarm.putLong("Start",controller.alarmPhaseStart());
            alarm.putInt("SourceId",controller.alarmSourceId());
            alarm.putInt("RescueTargetId",controller.rescueTargetId());
        }
        if(!alarm.equals(entityData.get(AQUATIC_ALARM)))entityData.set(AQUATIC_ALARM,alarm);
    }
    private void syncWaterDistress() {
        var data = new CompoundTag();
        if (waterDistress.airbornePlunge()) {
            data.putString("Phase", "FALLING");
            data.putLong("Start", level().getGameTime() - waterDistress.airbornePlungeTicks());
            data.putBoolean("Effort", false);
        } else if (waterDistress.active()) {
            data.putString("Phase", waterDistress.phase().name());
            data.putLong("Start", level().getGameTime() - waterDistress.phaseTicks());
            data.putBoolean("Effort", waterDistress.effort());
        }
        if (!data.equals(entityData.get(WATER_DISTRESS))) entityData.set(WATER_DISTRESS, data);
    }
    /** Only an upright, unsubmerged hat keeps its actual passengers dry. No innate water immunity. */
    public boolean shelteredInHat() {
        return getVehicle() instanceof io.github.longshou.mcyukkuri.subspecies.HatBoatEntity boat && boat.protects(this);
    }
    @Override public boolean isInWater() { return !shelteredInHat() && super.isInWater(); }
    @Override public boolean isInWaterOrBubble() { return !shelteredInHat() && super.isInWaterOrBubble(); }
    @Override public boolean isEyeInFluid(net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid> fluid) {
        return !(fluid == net.minecraft.tags.FluidTags.WATER && shelteredInHat()) && super.isEyeInFluid(fluid);
    }
    /** NeoForge breathes through its fluid-type hook rather than the vanilla eye-fluid predicate. */
    public static void breatheInHat(net.neoforged.neoforge.event.entity.living.LivingBreatheEvent event) {
        if (event.getEntity() instanceof YukkuriEntity y && y.shelteredInHat()
                && y.getEyeInFluidType() == net.neoforged.neoforge.common.NeoForgeMod.WATER_TYPE.value()) {
            event.setCanBreathe(true);
            event.setRefillAirAmount(y.getMaxAirSupply());
        }
    }
    public io.github.longshou.mcyukkuri.predator.PredatorLife predator() { return predator; }
    public boolean predatorFlying() { return entityData.get(PREDATOR_FLIGHT); }
    public void setPredatorFlying(boolean flying) {
        if (level().isClientSide || variant == null || !variant.isPredator() || predatorFlying() == flying) return;
        navigation.stop();
        entityData.set(PREDATOR_FLIGHT, flying); setNoGravity(flying); fallDistance = 0;
        navigation = flying ? new net.minecraft.world.entity.ai.navigation.FlyingPathNavigation(this, level())
                : new io.github.longshou.mcyukkuri.subspecies.AquaticNavigation(this, level());
        moveControl = flying ? new net.minecraft.world.entity.ai.control.FlyingMoveControl(this, 30, true)
                : new io.github.longshou.mcyukkuri.house.HouseCrawlControl(this);
        setDeltaMovement(getDeltaMovement().scale(.25));
    }
    public String getAnimationId() { return entityData.get(ANIMATION); }
    public long getAnimationStartTick() { return entityData.get(ANIMATION_START); }
    public String getExpressionId() { return entityData.get(EXPRESSION); }
    public String getBehaviorId() { return entityData.get(BEHAVIOR); }
    public long getBehaviorStartTick() { return entityData.get(BEHAVIOR_START); }
    public int nuzzlePartnerId() { return entityData.get(NUZZLE_PARTNER); }
    public int carePartnerId() { return entityData.get(CARE_PARTNER); }
    public float careContactProgress() { return entityData.get(CARE_PROGRESS); }
    public boolean isCrawling() { return entityData.get(CRAWLING); }
    public long getCrawlStartTick() { return entityData.get(CRAWL_START); }
    public boolean canCrawlNow() {
        return (variant.isHouse() || childhood != null && !childhood.canJump() || body != null && body.footBurned()) && isAlive() && !isNoAi() && !isPassenger() && !isCommandPlaying()
                && !isPanicking() && !isInWaterOrBubble() && !isOnFire() && hurtTime == 0
                && contactPose().equals("NONE");
    }
    public void syncCrawl(boolean active) {
        if (level().isClientSide) return;
        active &= canCrawlNow();
        if (active && !isCrawling()) entityData.set(CRAWL_START, level().getGameTime());
        entityData.set(CRAWLING, active);
    }

    @Override public void jumpFromGround() {
        if (body != null && body.footBurned() && !isInWaterOrBubble()) return; // a baked sole never springs again
        if (childhood == null || childhood.canJump() || isInWaterOrBubble() || isOnFire() || isPanicking()) super.jumpFromGround();
    }

    @Override public void travel(Vec3 input) {
        if (!level().isClientSide && family.cradleHeld()) return;
        if (!level().isClientSide && family.travelFlight()) return;
        if (predatorFlying()) {
            if (isControlledByLocalInstance()) {
                moveRelative(.1f, input);
                move(net.minecraft.world.entity.MoverType.SELF, getDeltaMovement());
                setDeltaMovement(getDeltaMovement().scale(.91)); fallDistance = 0;
            }
        } else if (!level().isClientSide && isCrawling() && canCrawlNow() && onGround()) {
            double distance = io.github.longshou.mcyukkuri.house.HouseCrawlMotion.advance(
                    level().getGameTime()-getCrawlStartTick()) * getScale() * growthScale();
            double dx = moveControl.getWantedX()-getX(), dz = moveControl.getWantedZ()-getZ();
            double remaining = Math.hypot(dx,dz);
            double fraction = remaining > 1e-6 ? Math.min(distance, remaining)/remaining : 0;
            setDeltaMovement(dx*fraction, getDeltaMovement().y, dz*fraction);
            super.travel(Vec3.ZERO); // Vanilla move still resolves collision, gravity and steps.
        } else super.travel(input);
    }
    public String getDialogueKey() { return entityData.get(DIALOGUE); }
    public long speechStart() { return entityData.get(SPEECH_START); }
    public long speechEnd() { return entityData.get(SPEECH_END); }
    public int speechSequence() { return entityData.get(SPEECH_SEQUENCE); }
    public ItemStack getMouthItem() { return entityData.get(MOUTH); }
    public ItemStack aquaticServing() { return entityData.get(AQUATIC_SERVING); }
    public int aquaticChildId() { return entityData.get(AQUATIC_CHILD); }
    public int visibleMedal() { return entityData.get(MEDAL); }
    public boolean hasVisibleAccessory() { return entityData.get(ACCESSORY); }
    public boolean isPanicking() { return panicTicks > 0 && (body == null || !body.dying()); } // a dying body no longer bolts
    public io.github.longshou.mcyukkuri.subspecies.ShellRetraction shellRetraction() { return shellRetraction; }
    public boolean isCommandPlaying() { return requestedAction != null; }
    public YukkuriLife life() { return life; }
    public io.github.longshou.mcyukkuri.pet.PetLife pet() { return pet; }
    public void sayBadge(String event) {
        if (level().isClientSide || !isAlive()) return;
        long now = level().getGameTime();
        if (!speechTiming.admit(now, 80, 2)) return;
        entityData.set(DIALOGUE, "badge.mcyukkuri.speech." + event);
        entityData.set(SPEECH_START, now); entityData.set(SPEECH_END, speechTiming.end());
        entityData.set(SPEECH_SEQUENCE, speechSequence() + 1);
    }
    public io.github.longshou.mcyukkuri.social.DailyLife dailyLife() { return dailyLife; }
    /** Species profile: behaviour coefficients, retching, speech hooks (worldview package 4). */
    public io.github.longshou.mcyukkuri.life.SpeciesLife species() { return species; }
    public io.github.longshou.mcyukkuri.social.WoundComfort woundComfort() { return woundComfort; }
    public YukkuriSocial social() { return social; }
    public io.github.longshou.mcyukkuri.social.GroupInteractionGoal groupInteraction() { return groupInteraction; }
    public io.github.longshou.mcyukkuri.play.PlayLife playLife(){return playLife;}
    public io.github.longshou.mcyukkuri.life.SurfaceLife surfaceLife(){return surfaceLife;}
    public void frightenFrom(YukkuriEntity threat) {
        if(level().isClientSide||!isAlive())return;
        if(myon.confront(threat))return; // a brave Myon turns on the threat instead of bolting
        agitation.stop();
        operations.end();groupInteraction.stop();dailyLife.stop();woundComfort.stop();playLife.stop();social.stop();life.interrupt();
        setLastHurtByMob(threat);panicTicks=100;
        life.involuntaryRelease("fright", false);
    }
    public FamilyLife family() { return family; }
    @Override public boolean canCollideWith(net.minecraft.world.entity.Entity other) {
        // During a checked rim transfer the detailed hat surface replaces its coarse entity box.
        return super.canCollideWith(other) && !(other instanceof io.github.longshou.mcyukkuri.subspecies.HatBoatEntity hat
                && family != null && family.boardingThrough(hat));
    }
    public io.github.longshou.mcyukkuri.family.ChildhoodLife childhood() { return childhood; }
    public io.github.longshou.mcyukkuri.family.CareApproach careApproach() { return careApproach; }
    /** A newborn crossing from its mother's outlet to the hat is not shoved off its arc by the body it overlaps. */
    @Override public boolean isPushable() { return !(family != null && family.newbornLaunching()) && super.isPushable(); }
    @Override public void push(net.minecraft.world.entity.Entity other) {
        // Vanilla's extra proximity push otherwise separates a consenting pair before mouths can meet.
        // Actual body overlap, players and unrelated animals retain ordinary collision response.
        if(other instanceof YukkuriEntity peer&&careApproach.cooperatingWith(peer)
                &&!getBoundingBox().intersects(peer.getBoundingBox()))return;
        super.push(other);
    }
    public io.github.longshou.mcyukkuri.family.IndependenceLife independence() { return independence; }
    public io.github.longshou.mcyukkuri.season.WinteringLife wintering() { return wintering; }
    public io.github.longshou.mcyukkuri.subspecies.SubspeciesLife subspecies() { return subspecies; }
    /** Myon traits (saved tic, stick sword, bravery); inert for other species except the saved tic slot. */
    public io.github.longshou.mcyukkuri.rare.MyonLife myon() { return myon; }
    public boolean visibleKanroken() { return entityData.get(KANROKEN); }
    public int subspeciesFlags() { return entityData.get(SUBSPECIES); }
    public void syncSubspecies(int flags) { if (!level().isClientSide) entityData.set(SUBSPECIES, flags); }
    public io.github.longshou.mcyukkuri.settlement.SettlementLife settlement() { return settlement; }
    public io.github.longshou.mcyukkuri.settlement.HabitatLife habitat() { return habitat; }
    private io.github.longshou.mcyukkuri.settlement.ForageTrip forageTrip;
    /** Foraging trips of a household with young (father far, mother when short); read by status and the museum. */
    public io.github.longshou.mcyukkuri.settlement.ForageTrip forageTrip() { return forageTrip; }

    @Override public net.minecraft.world.entity.SpawnGroupData finalizeSpawn(net.minecraft.world.level.ServerLevelAccessor level,
            net.minecraft.world.DifficultyInstance difficulty, net.minecraft.world.entity.MobSpawnType reason,
            net.minecraft.world.entity.SpawnGroupData data) {
        var result = super.finalizeSpawn(level, difficulty, reason, data);
        subspecies.onNaturalSpawn(reason);
        if (reason == net.minecraft.world.entity.MobSpawnType.NATURAL || reason == net.minecraft.world.entity.MobSpawnType.CHUNK_GENERATION) {
            if (variant.isPredator()) predator.naturalSpawn(); else settlement.naturalSpawn();
        }
        return result;
    }
    public io.github.longshou.mcyukkuri.house.HouseLife house(){return house;}
    public ItemStack getCarriedItem(){return entityData.get(CARRYING);}
    public io.github.longshou.mcyukkuri.body.BodyLife body(){return body;}
    /** The status hub's per-individual part: lasting marks, perception and reactions (see the status package). */
    public io.github.longshou.mcyukkuri.status.StatusLife status(){return status;}
    public io.github.longshou.mcyukkuri.mind.MindLife mind(){return mind;}
    /** Dirt patches and scars drawn on the skin (synced; kinds dirt and scar). */
    public java.util.List<io.github.longshou.mcyukkuri.body.BodyWound> visibleMarks(){return marksCache;}
    public void syncMarks(CompoundTag marks){if(level().isClientSide)return;entityData.set(MARKS,marks);marksCache=io.github.longshou.mcyukkuri.status.Marks.read(marks);}
    public io.github.longshou.mcyukkuri.interaction.OperationLife operations(){return operations;}
    @Override protected boolean canAddPassenger(net.minecraft.world.entity.Entity passenger){
        return io.github.longshou.mcyukkuri.family.HatCarry.canSeat(this,passenger);
    }
    @Override public net.minecraft.world.entity.LivingEntity getControllingPassenger(){return null;}
    @Override protected void positionRider(net.minecraft.world.entity.Entity passenger,MoveFunction move){
        if(io.github.longshou.mcyukkuri.family.HatCarry.riding(this,passenger)){
            var point=io.github.longshou.mcyukkuri.family.HatCarry.seatWorld(this,0);
            move.accept(passenger,point.x,point.y,point.z);
        }else super.positionRider(passenger,move);
    }
    public String contactPose(){return entityData.get(CONTACT_POSE);}
    public float contactTurn(){return entityData.get(CONTACT_TURN);}
    public int visibleHairLoss(){return entityData.get(HAIR_LOSS);}
    public void syncOperation(String pose,float turn){entityData.set(CONTACT_POSE,pose);entityData.set(CONTACT_TURN,turn);if(pose.equals("NONE"))syncOperationAnchor(null);}
    public void syncOperationAnchor(net.minecraft.core.BlockPos anchor){entityData.set(CONTACT_ANCHOR,java.util.Optional.ofNullable(anchor));}
    public net.minecraft.core.BlockPos contactAnchor(){return entityData.get(CONTACT_ANCHOR).orElse(null);}
    public int visibleWound(){return entityData.get(WOUND);}
    public java.util.List<io.github.longshou.mcyukkuri.body.BodyWound> visibleWounds(){return io.github.longshou.mcyukkuri.body.BodyWound.read(entityData.get(WOUNDS));}
    public int visibleFilling(){return entityData.get(FILLING);}
    public void syncBody(){if(body==null||level().isClientSide)return;entityData.set(WOUND,Math.max(body.wound(),body.burns()));entityData.set(WOUNDS,io.github.longshou.mcyukkuri.body.BodyWound.write(body.wounds()));entityData.set(FILLING,body.filling());entityData.set(BANDAGED,body.bandage()>0);entityData.set(HAIR_LOSS,body.hairLoss());entityData.set(BODY_SIGNS,body.signs());}
    public int visibleSigns(){return entityData.get(BODY_SIGNS);}
    /** Vanilla would reject this hit as a repeat inside the hurt cooldown (LivingEntity.hurt); the body must not analyse it. */
    public boolean repeatHitIgnored(DamageSource source,float amount){return invulnerableTime>10&&!source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_COOLDOWN)&&amount<=lastHurt;}
    public boolean visibleBandage(){return entityData.get(BANDAGED);}
    public float growthScale() { return entityData.get(GROWTH_SCALE)*((subspeciesFlags()&io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.DEIBU_FLAG)!=0?1.5f:1); }
    public int lifeStage() { return entityData.get(LIFE_STAGE); }
    public int broodCount() { return entityData.get(BROOD_COUNT); }
    public int broodBornMask() { return entityData.get(BROOD_BORN); }
    public float broodProgress() { return entityData.get(BROOD_PROGRESS); }
    public float broodVariation() { return entityData.get(BROOD_VARIATION); }
    public boolean hasSpentStem() { return entityData.get(SPENT_STEM); }
    public float livePregnancyProgress() { return entityData.get(LIVE_PROGRESS); }
    public int liveDeliveryTicks() { return entityData.get(LIVE_DELIVERY); }
    public int liveChildCount() { return entityData.get(LIVE_CHILDREN).getInt("Count"); }
    public int liveBornMask() { return entityData.get(LIVE_CHILDREN).getInt("Born"); }
    public YukkuriVariant liveChildVariant(int index) {
        int ordinal=(entityData.get(LIVE_CHILDREN).getInt("Types")>>(index*4))&15;
        return YukkuriVariant.values()[Math.min(ordinal,YukkuriVariant.values().length-1)];
    }
    public String liveChildAssetId(int index) {
        int[] morphs=entityData.get(LIVE_CHILDREN).getIntArray("Morphs");
        int flags=index>=0&&index<morphs.length?morphs[index]:0;
        return io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.assetId(liveChildVariant(index),flags);
    }
    public YukkuriVariant broodVariant(int index) { return YukkuriVariant.values()[(entityData.get(BROOD_TYPES)>>(index*4))&15]; }
    public void syncFamily() {
        if(family==null||level().isClientSide)return;
        entityData.set(GROWTH_SCALE,family.scale());entityData.set(LIFE_STAGE,family.stage().ordinal());
        var brood=isAlive()?family.brood():null;int count=isAlive()?family.spentStemCount():0,born=(1<<count)-1,types=0;float progress=1,variation=family.spentStemVariation();
        entityData.set(AFTERMATH_STATE,brood!=null&&!brood.cancelled&&!brood.complete()&&brood.incidentSource!=null
                ?life.nutrition()<30||life.energy()<25?2:1:0);
        if(brood!=null&&!brood.cancelled&&brood.kind==io.github.longshou.mcyukkuri.family.FamilyData.BirthKind.STEM) {
            count=brood.children.size();born=0;progress=brood.progress();variation=brood.variation;
            for(int i=0;i<count;i++){var child=brood.children.get(i);if(child.born)born|=1<<i;types|=child.variant.ordinal()<<(i*4);}
        }
        entityData.set(BROOD_COUNT,count);entityData.set(BROOD_BORN,born);entityData.set(BROOD_TYPES,types);
        entityData.set(BROOD_PROGRESS,progress);entityData.set(BROOD_VARIATION,variation);entityData.set(SPENT_STEM,isAlive()&&family.spentStemCount()>0);
        var live=new CompoundTag();int delivery=-1;
        if(brood!=null&&!brood.cancelled&&!brood.complete()&&brood.kind==io.github.longshou.mcyukkuri.family.FamilyData.BirthKind.LIVE){
            live.putInt("Count",brood.children.size());int liveBorn=0,liveTypes=0;
            int[] morphs=new int[brood.children.size()];
            for(int i=0;i<brood.children.size();i++){var child=brood.children.get(i);if(child.born)liveBorn|=1<<i;liveTypes|=child.variant.ordinal()<<(i*4);morphs[i]=child.subspeciesFlags;}
            live.putIntArray("Morphs",morphs);
            live.putInt("Born",liveBorn);live.putInt("Types",liveTypes);delivery=Math.max(-1,brood.elapsed-brood.duration);
        }
        entityData.set(LIVE_CHILDREN,live);entityData.set(LIVE_DELIVERY,delivery);entityData.set(LIVE_PROGRESS,isAlive()?family.liveBelly():0f);
    }
    @Override protected net.minecraft.world.entity.EntityDimensions getDefaultDimensions(net.minecraft.world.entity.Pose pose) {
        if(family==null||subspecies==null||variant==null)return super.getDefaultDimensions(pose);
        return io.github.longshou.mcyukkuri.family.LiveBirthGeometry.bodyDimensions(this,growthScale(),livePregnancyProgress());
    }
    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);if(MARKS.equals(key))marksCache=io.github.longshou.mcyukkuri.status.Marks.read(entityData.get(MARKS));if(GROWTH_SCALE.equals(key)||LIVE_PROGRESS.equals(key)||SUBSPECIES.equals(key))refreshDimensions();
    }
    @Override public boolean isBaby() { return lifeStage()!=FamilyLife.Stage.ADULT.ordinal(); }
    @Override public boolean causeFallDamage(float distance,float multiplier,DamageSource damage) {
        return family!=null&&family.awaitingLanding()?false:super.causeFallDamage(distance,multiplier*io.github.longshou.mcyukkuri.life.Facilities.landing(io.github.longshou.mcyukkuri.life.Facilities.floor(this)),damage);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(SUBSPECIES, 0);
        builder.define(ANIMATION, "idle");
        builder.define(ANIMATION_START, 0L);
        builder.define(EXPRESSION, "neutral");
        builder.define(BEHAVIOR, "idle"); builder.define(DIALOGUE, "");
        builder.define(SPEECH_START, 0L); builder.define(SPEECH_END, 0L); builder.define(SPEECH_SEQUENCE, 0);
        builder.define(BEHAVIOR_START, 0L);
        builder.define(NUZZLE_PARTNER, -1); builder.define(CARE_PARTNER, -1); builder.define(CARE_PROGRESS, 0f);
        builder.define(CRAWLING, false); builder.define(CRAWL_START, 0L);
        builder.define(PREDATOR_FLIGHT, false);
        builder.define(MEDAL, 0); builder.define(MOUTH, ItemStack.EMPTY); builder.define(ACCESSORY, true);
        builder.define(AQUATIC_SERVING,ItemStack.EMPTY); builder.define(AQUATIC_CHILD,-1);
        builder.define(CARRYING,ItemStack.EMPTY);
        builder.define(WOUND,0);builder.define(BANDAGED,false);
        builder.define(WOUNDS,new CompoundTag());builder.define(FILLING,12);builder.define(BODY_SIGNS,0);
        builder.define(CONTACT_POSE,"NONE");builder.define(CONTACT_TURN,0f);builder.define(HAIR_LOSS,0);builder.define(CONTACT_ANCHOR,java.util.Optional.empty());
        builder.define(GROWTH_SCALE,1f);builder.define(LIFE_STAGE,FamilyLife.Stage.ADULT.ordinal());
        builder.define(BROOD_COUNT,0);builder.define(BROOD_BORN,0);builder.define(BROOD_TYPES,0);
        builder.define(BROOD_PROGRESS,0f);builder.define(BROOD_VARIATION,1f);builder.define(SPENT_STEM,false);
        builder.define(AFTERMATH_STATE,0);
        builder.define(LIVE_PROGRESS,0f);builder.define(LIVE_DELIVERY,-1);builder.define(LIVE_CHILDREN,new CompoundTag());
        builder.define(WATER_DISTRESS,new CompoundTag());
        builder.define(AGITATION,new CompoundTag());
        builder.define(BRAID_RESCUE,new CompoundTag());builder.define(AQUATIC_ALARM,new CompoundTag());
        builder.define(HAT_BOARDING,new CompoundTag());builder.define(AQUATIC_SELF_RESCUE,new CompoundTag());
        builder.define(WEAR,0);builder.define(MARKS,new CompoundTag());builder.define(HEADWEAR,100<<4);builder.define(KANROKEN,false);
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
        goalSelector.addGoal(0, new EscapeWaterGoal());
        goalSelector.addGoal(0, new HouseExitGoal());
        goalSelector.addGoal(0, new OperationGoal());
        goalSelector.addGoal(1, new FleeGoal());
        myon = new io.github.longshou.mcyukkuri.rare.MyonLife(this);
        goalSelector.addGoal(1, myon.goal());
        goalSelector.addGoal(1, new io.github.longshou.mcyukkuri.mind.MindLife.FrenzyGoal(this));
        sanction = new io.github.longshou.mcyukkuri.headwear.Sanction(this);
        goalSelector.addGoal(2, sanction);
        headwear = new io.github.longshou.mcyukkuri.headwear.Headwear(this);
        goalSelector.addGoal(3, headwear);
        goalSelector.addGoal(1, new io.github.longshou.mcyukkuri.status.Reactions.ThreatGoal(this));
        goalSelector.addGoal(2, new io.github.longshou.mcyukkuri.status.Reactions.WeatherGoal(this));
        goalSelector.addGoal(1, new ShellGoal());
        goalSelector.addGoal(2, new HoldActionGoal());
        goalSelector.addGoal(2, new CareReceiverGoal());
        goalSelector.addGoal(2, new ComfortReceiverGoal());
        goalSelector.addGoal(2, new io.github.longshou.mcyukkuri.pet.PetLife.SessionGoal(this));
        goalSelector.addGoal(3, new io.github.longshou.mcyukkuri.predator.PredatorLife.BehaviorGoal(this));
        aquaticLife = new io.github.longshou.mcyukkuri.subspecies.AquaticLife(this);
        goalSelector.addGoal(3, aquaticLife);
        goalSelector.addGoal(3, new ChildhoodGoal());
        goalSelector.addGoal(3, new LifeGoal());
        goalSelector.addGoal(3, new io.github.longshou.mcyukkuri.event.HomeClaimGoal(this));
        goalSelector.addGoal(4, new io.github.longshou.mcyukkuri.human.FactoryWorkGoal(this));
        goalSelector.addGoal(4, new TemptGoal(this, 1.15, Ingredient.of(FOOD), false) {
            @Override public boolean canUse() { return life.canEat() && super.canUse() && life.personality().fear(player.getUUID()) < 30; }
            @Override public boolean canContinueToUse() { return life.canEat() && super.canContinueToUse(); }
        });
        goalSelector.addGoal(5, new FamilyGoal());
        goalSelector.addGoal(5, new io.github.longshou.mcyukkuri.life.AgitationLife.BehaviorGoal(this));
        goalSelector.addGoal(1, new io.github.longshou.mcyukkuri.life.AgitationLife.HeldGoal(this));
        goalSelector.addGoal(4, new HouseGoal());
        forageTrip = new io.github.longshou.mcyukkuri.settlement.ForageTrip(this);
        goalSelector.addGoal(io.github.longshou.mcyukkuri.settlement.ForageTrip.GOAL_PRIORITY, forageTrip);
        goalSelector.addGoal(4, new io.github.longshou.mcyukkuri.settlement.ForageGoal(this));
        treasure = new io.github.longshou.mcyukkuri.social.TreasureLife(this);
        goalSelector.addGoal(4, treasure);
        cannibalism = new io.github.longshou.mcyukkuri.social.Cannibalism(this);
        goalSelector.addGoal(2, cannibalism);
        offering = new io.github.longshou.mcyukkuri.social.SelfOffering(this);
        goalSelector.addGoal(2, offering);
        goalSelector.addGoal(4, new HabitatGoal());
        independence = new io.github.longshou.mcyukkuri.family.IndependenceLife(this);
        // Priority 4: at 6 the (also priority 6) social goals kept the move flag whenever the child was seeking its parents' company, so the leave-taking never got its turn.
        goalSelector.addGoal(4, independence);
        goalSelector.addGoal(6, new SettlementDutyGoal());
        goalSelector.addGoal(6, new SettlementGoal());
        // A migration (discussion, packing, march) outranks daily, social and family routines; the life needs (3) still come first.
        goalSelector.addGoal(4, new MigrationGoal());
        goalSelector.addGoal(5, new io.github.longshou.mcyukkuri.event.WildEventGoal(this));
        goalSelector.addGoal(4, new ComfortGoal());
        goalSelector.addGoal(5, new DailyGoal());
        goalSelector.addGoal(6, new SocialGoal());
        goalSelector.addGoal(5, new GroupGoal());
        goalSelector.addGoal(6,new PlaygroundGoal());
        wintering = new io.github.longshou.mcyukkuri.season.WinteringLife(this);
        goalSelector.addGoal(7, wintering);
        goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 1.0) {
            // House residents with a nest or corner do not roam the room: their patrols are the scouting rounds of the house goal.
            @Override public boolean canUse() { return childhood.canLearn() && (!variant.isHouse() || house.homeless()) && super.canUse(); }
            @Override public boolean canContinueToUse() { return childhood.canLearn() && (!variant.isHouse() || house.homeless()) && super.canContinueToUse(); }
        });
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 6.0F));
        goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    /** One exclusive track: changing action or returning to idle discards every previous pose. */
    public boolean playAction(String id) {
        if (level().isClientSide || !isAlive()) return false;
        var action = YukkuriAction.byId(id);
        if (action.isEmpty()) return false;
        if (action.get()==YukkuriAction.SHELL_RETRACT
                &&(!shellRetraction.hasShell()||isInWaterOrBubble()||isOnFire()))return false;
        shellRetraction.reset();
        agitation.stop();
        operations.end();groupInteraction.stop();dailyLife.stop();woundComfort.stop();playLife.stop();life.interrupt(); social.stop();
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
        entityData.set(ANIMATION_START, level().getGameTime() - elapsedRetractionTicks);
        if (next == YukkuriAction.PUFF) speak("puff");
        return true;
    }

    private void setAnimation(YukkuriAction action, boolean restart) {
        if (restart || !getAnimationId().equals(action.id())) {
            entityData.set(ANIMATION_START, level().getGameTime());
            entityData.set(ANIMATION, action.id());
        }
    }

    /** The shell controller samples one existing clip; holding does not restart its inward movement. */
    public void poseShellRetraction(int frame) {
        if(level().isClientSide||!shellRetraction.hasShell()||frame<0)return;
        setAnimation(YukkuriAction.SHELL_RETRACT,false);
        entityData.set(ANIMATION_START,level().getGameTime()-frame);
    }

    @Override
    public void aiStep() {
        if (!level().isClientSide && !canCrawlNow()) syncCrawl(false);
        double movementX = getX(), movementZ = getZ();
        super.aiStep();
        double travelledX = getX() - movementX, travelledZ = getZ() - movementZ;
        if (level().isClientSide) return;
        if (!isAlive()) return;
        if (reactionTicks > 0) reactionTicks--;
        if (pendingSpeechTicks > 0 && --pendingSpeechTicks == 0) {
            if (speak(pendingSpeech) && pendingSpeech.equals("newborn")) io.github.longshou.mcyukkuri.family.BirthStaging.greeting(this);
        }
        if (level().getGameTime() >= speechEnd()) entityData.set(DIALOGUE, "");
        subspecies.tick();aquaticLife.clock();life.tick();waterDistress.tick();syncWaterDistress(); pet.tick(); social.tick();operations.tick(); family.tick();childhood.tick();house.tick();settlement.tick();habitat.tick();groupInteraction.clock();treasure.clock();headwear.clock();cannibalism.clock();grief.clock();offering.clock();playLife.clock();dailyLife.clock();woundComfort.clock();predator.tick();syncFamily();
        syncAquaticInteraction();
        status.tick();
        species.tick();
        myon.tick();entityData.set(KANROKEN,myon.hasKanroken());
        io.github.longshou.mcyukkuri.rare.RareForms.tick(this);
        agitation.clock();
        entityData.set(AGITATION, agitation.state());
        mind.tick();
        independence.clock();
        var familyPaddle=aquaticLife.familyPaddle().carried();
        entityData.set(CARRYING,(!familyPaddle.isEmpty()?familyPaddle:!family.carrying().isEmpty()?family.carrying():!independence.carrying().isEmpty()?independence.carrying():!settlement.duties().carrying().isEmpty()?settlement.duties().carrying():variant.isHouse()?house.carrying():habitat.carrying()).copy());
        entityData.set(NUZZLE_PARTNER,dailyLife.nuzzlePartnerId());
        int careTarget = family.careTargetId();
        float careProgress = family.careContactProgress() / 20f;
        if (aquaticLife.care().active()) {
            careTarget = aquaticLife.care().childId();
            careProgress = aquaticLife.care().contactProgress() / (aquaticLife.care().activity().equals("first_toilet_assist")
                    ? (float) io.github.longshou.mcyukkuri.family.ChildhoodLife.FIRST_TOILET_CONTACT_TICKS : 20f);
        }
        if (childhood.active() && childhood.careTargetId() != null) {
            var partner = ((ServerLevel) level()).getEntity(childhood.careTargetId());
            careTarget = partner == null ? -1 : partner.getId();
            careProgress = childhood.careProgress() / (float) io.github.longshou.mcyukkuri.family.ChildhoodLife.FIRST_TOILET_CONTACT_TICKS;
        }
        if (woundComfort.licking()) { careTarget = woundComfort.victimId(); careProgress = woundComfort.contactProgress(); }
        entityData.set(CARE_PARTNER, careTarget);
        entityData.set(CARE_PROGRESS, Mth.clamp(careProgress, 0, 1));
        if (!ItemStack.matches(entityData.get(AQUATIC_SERVING),aquaticLife.care().serving()))
            entityData.set(AQUATIC_SERVING,aquaticLife.care().serving().copy());
        entityData.set(AQUATIC_CHILD,aquaticLife.care().childId());
        body.tick();syncBody();
        surfaceLife.tick();
        if(!isAlive())return;
        if (!ItemStack.matches(entityData.get(MOUTH), life.mouth())) entityData.set(MOUTH, life.mouth().copy());
        entityData.set(MEDAL, pet.visibleGrade().id()); entityData.set(ACCESSORY, life.hasAccessory());
        entityData.set(WEAR, pet.care().wearBits());
        if (isOnFire() && panicTicks < 100) {
            panicTicks = 100;
            requestedAction = null;
            life.interrupt(); social.stop();
        }
        if(requestedAction==YukkuriAction.SHELL_RETRACT
                &&(!shellRetraction.hasShell()||isInWaterOrBubble()||isOnFire()))requestedAction=null;
        shellRetraction.tick(panicTicks);
        // Contact-driven rescue owns both participants' poses; water panic must not keep a held child paddling.
        if(family.braidRescueActive()&&!isCommandPlaying()&&!operations.active()){
            if(panicTicks>0)panicTicks--;
            String phase=braidRescuePhase();boolean parent=braidRescueState().getInt("ParentId")==getId();
            if(phase.startsWith("CRADLE_")){
                setBehavior(parent?"caregiver":"newborn");setAnimation(YukkuriAction.IDLE,false);
                entityData.set(EXPRESSION,parent?"closed_smile":"neutral");return;
            }
            // Reaching and gripping retain their real contact phases.
            String motion=parent?switch(phase){
                case "REACH"->"braid_rescue_reach";
                case "GRIP"->"braid_rescue_hold";
                case "OFFER","BITE","SLIDE"->"braid_rescue_offer";
                case "PUSH"->"braid_rescue_push";
                default->"braid_rescue_settle";
            }:family.heldByBraid()?"braid_be_rescued":waterDistress.activity();
            setBehavior(motion);setAnimation(io.github.longshou.mcyukkuri.social.EcologyPresentation.action(motion),false);
            entityData.set(ANIMATION_START,parent||family.heldByBraid()?braidRescueStartTick():waterDistressStartTick());
            entityData.set(EXPRESSION,switch(phase){
                case "PUSH","SLIDE"->parent?"closed_smile":"teary";
                case "SETTLE","RELEASE"->"neutral";
                default->"teary";
            });
            return;
        }
        // Actual crowd contact and boat motion take precedence over leisure or a stale newborn pose.
        // These poses never change physical support or grant water protection during a capsize.
        if(getVehicle() instanceof io.github.longshou.mcyukkuri.subspecies.HatBoatEntity movingHat
                &&(movingHat.rocking()||movingHat.capsizing()||!movingHat.crowdingState(this).equals("idle"))
                &&!isCommandPlaying()&&!operations.active()){
            if(panicTicks>0)panicTicks--;
            boolean captain=getUUID().equals(movingHat.ownerYukkuri());
            String crowd=movingHat.crowdingState(this);
            String motion=movingHat.capsizing()?"boat_capsizing":!crowd.equals("idle")?crowd:captain?"boat_steady":"boat_rocked";
            setBehavior(motion);
            setAnimation(movingHat.capsizing()||motion.equals("hat_stumble")||crowd.equals("idle")
                    ?captain?YukkuriAction.BOAT_STEADY:YukkuriAction.HAT_STUMBLE
                    :io.github.longshou.mcyukkuri.social.EcologyPresentation.action(motion),false);
            if(!movingHat.capsizing()&&!crowd.equals("idle"))entityData.set(ANIMATION_START,movingHat.crowdingPhaseStart(this));
            entityData.set(EXPRESSION,family.adult()?"surprise":"teary");
            return;
        }
        if(boardingState().getBoolean("Active")&&!isCommandPlaying()&&!operations.active()){
            if(panicTicks>0)panicTicks--;
            var boarding=boardingState();String phase=boarding.getString("Phase");
            String motion="hat_board_"+phase.toLowerCase(java.util.Locale.ROOT);
            setBehavior(motion);setAnimation(io.github.longshou.mcyukkuri.social.EcologyPresentation.action(motion),false);
            entityData.set(ANIMATION_START,boarding.getLong("Start"));entityData.set(EXPRESSION,"teary");return;
        }
        if(aquaticLife.selfRescue().active()&&!isCommandPlaying()&&!operations.active()){
            if(panicTicks>0)panicTicks--;
            String motion=aquaticLife.selfRescue().activity();setBehavior(motion);
            setAnimation(io.github.longshou.mcyukkuri.social.EcologyPresentation.action(motion),false);
            entityData.set(ANIMATION_START,aquaticLife.selfRescue().phaseStart());
            entityData.set(EXPRESSION,motion.equals("water_hat_safe")?"sad":"teary");return;
        }
        if ((waterDistress.active() || waterDistress.airbornePlunge()) && !isCommandPlaying() && !operations.active()) {
            if (panicTicks > 0) panicTicks--;
            String distress = waterDistress.airbornePlunge() ? "hat_slipping" : waterDistress.activity();
            setBehavior(distress);
            setAnimation(io.github.longshou.mcyukkuri.social.EcologyPresentation.action(distress), false);
            entityData.set(ANIMATION_START, waterDistressStartTick());
            entityData.set(EXPRESSION, waterDistress.phase() == io.github.longshou.mcyukkuri.life.WaterDistress.Phase.IMPACT ? "cry" : "teary");
            return;
        }
        if(!aquaticAlarmActivity().equals("idle")&&!isCommandPlaying()&&!operations.active()){
            if(panicTicks>0)panicTicks--;
            String alarm=aquaticAlarmActivity();setBehavior(alarm);
            YukkuriAction action=getVehicle() instanceof io.github.longshou.mcyukkuri.subspecies.HatBoatEntity boat
                    ?boatAction(boat,alarm):io.github.longshou.mcyukkuri.social.EcologyPresentation.action(alarm);
            if(!isPassenger()&&onGround()&&(travelledX*travelledX+travelledZ*travelledZ)>.000001)action=YukkuriAction.WADDLE;
            setAnimation(action,false);entityData.set(ANIMATION_START,aquaticAlarmStartTick());
            entityData.set(EXPRESSION,io.github.longshou.mcyukkuri.social.EcologyPresentation.expression(this,"teary"));
            return;
        }
        if (panicTicks > 0&&!isCommandPlaying()&&!operations.active()) {
            boolean shell = shellRetraction.active();
            if (shell) { navigation.stop(); }
            else setAnimation(isInWaterOrBubble()?YukkuriAction.WATER_STRUGGLE:shelteredInHat() ? YukkuriAction.BOAT_STEADY : panicTicks > 100 ? YukkuriAction.STARTLE : YukkuriAction.FLEE, false);
            panicTicks--;
            entityData.set(EXPRESSION, "surprise");
            setBehavior(isInWaterOrBubble() ? "escape_water" : shelteredInHat() ? "boat_steady" : shell ? "shell_retract" : "flee");
            return;
        }
        if(panicTicks>0)panicTicks--;
        if(shellRetraction.active()) {
            entityData.set(EXPRESSION,"neutral");setBehavior("shell_retract");return;
        }
        if (agitation.activePresentation()) {
            setBehavior(agitation.activity());
            setAnimation(travelledX * travelledX + travelledZ * travelledZ > .000001 && !agitation.poseLocked()
                    ? YukkuriAction.WADDLE : agitation.action(), false);
            entityData.set(EXPRESSION, agitation.expression());
            return;
        }
        if (requestedAction != null && !requestedAction.loops() && !requestedAction.holdsEndPose()
                && level().getGameTime() - getAnimationStartTick() >= requestedAction.durationTicks()) {
            requestedAction = null;
        }
        String behavior = mind.active() ? mind.activity() : status.reactions().active() ? status.reactions().activity() : predator.active() ? predator.activity() : sanction.active() ? sanction.activity() : isInWaterOrBubble() ? "escape_water" : !life.activity().equals("idle") ? life.activity()
                : careApproach.waiting()?careApproach.activity():!childhood.activity().equals("idle")?childhood.activity():aquaticLife.active()?aquaticLife.activity():groupInteraction.active()?groupInteraction.activity():headwear.active()?headwear.activity():playLife.active()?playLife.activity():woundComfort.active()?woundComfort.activity():dailyLife.active()?dailyLife.activity():io.github.longshou.mcyukkuri.event.WildEvents.reserved(this)?io.github.longshou.mcyukkuri.event.WildEvents.activity(this):family.active() ? family.activity() : house.active()?house.activity():habitat.active()?habitat.activity():independence.active()?independence.activity():settlement.duties().active()?settlement.duties().activity():settlement.active()?settlement.activity():social.active() ? social.activity() : family.activity();
        if (behavior.equals("boat_toilet") && childhood.firstToiletDone() && !life.hatToiletReleasing()) behavior = "seek_toilet";
        YukkuriAction action = requestedAction != null ? requestedAction
                : getVehicle() instanceof io.github.longshou.mcyukkuri.subspecies.HatBoatEntity boat ? boatAction(boat, behavior)
                : isInWaterOrBubble() ? YukkuriAction.WATER_STRUGGLE
                : travelledX * travelledX + travelledZ * travelledZ > 0.000001 && onGround() && !life.isSleeping()
                ? YukkuriAction.WADDLE
                : groupInteraction.active()?io.github.longshou.mcyukkuri.social.EcologyPresentation.group(groupInteraction.activity())
                : stationaryAction(behavior);
        String flightAnimation = requestedAction == null ? predator.animation() : null;
        if (flightAnimation != null) {
            if (!getAnimationId().equals(flightAnimation)) { entityData.set(ANIMATION, flightAnimation); entityData.set(ANIMATION_START, level().getGameTime()); }
        } else {
            setAnimation(action, false);
            if(behavior.equals("pairing_accept")&&action==YukkuriAction.GREET
                    ||behavior.equals("pairing")&&action==YukkuriAction.NUZZLE) {
                long start=family.courtAnimationStart();
                if(start>=0&&getAnimationStartTick()!=start)entityData.set(ANIMATION_START,start);
            }
            // Partners sample the authoritative contraction, rescaling the shorter hat clips.
            int phase = action == YukkuriAction.LABOR || action == YukkuriAction.BOAT_LABOR ? liveDeliveryTicks()
                    : (action == YukkuriAction.MIDWIFE || action == YukkuriAction.HAT_HOLD || action == YukkuriAction.HAT_HOLD_AQ)
                    && family.midwifeTarget() != null ? family.midwifeTarget().liveDeliveryTicks() : -1;
            if (phase >= 0) {
                long frame = Math.round(action == YukkuriAction.HAT_HOLD || action == YukkuriAction.HAT_HOLD_AQ
                        ? io.github.longshou.mcyukkuri.family.LiveBirthGeometry.hatAnimationTick(phase)
                        : action == YukkuriAction.MIDWIFE ? io.github.longshou.mcyukkuri.family.LiveBirthGeometry.partnerTick(phase)
                        : io.github.longshou.mcyukkuri.family.LiveBirthGeometry.animationTick(phase));
                long start = level().getGameTime() - frame;
                if (getAnimationStartTick() != start) entityData.set(ANIMATION_START, start);
            }
        }
        setBehavior(behavior);
        entityData.set(EXPRESSION, "predator_bite".equals(flightAnimation) ? "biting" : io.github.longshou.mcyukkuri.social.EcologyPresentation.expression(this,
                reactionTicks > 0 ? reactionExpression : body.dying() ? "blank_cry" : mind.face(body.weak() || settlement.society().dejected() ? "teary" : life.hasAccessory() ? "neutral" : "surprise")));
    }

    /** Behavior motion, else a short reaction, else the withdrawn droop of a yukkuri without its accessory. */
    private YukkuriAction stationaryAction(String behavior) {
        if (family.flying() && io.github.longshou.mcyukkuri.subspecies.HatVessel.aquatic(this)) return YukkuriAction.BOAT_BOARD;
        if (reactionTicks > 0 && reactionEvent.equals("birth_lost")) return YukkuriAction.BEREAVED;
        if(aftermathWeak()&&(behavior.equals("idle")||behavior.equals("tend_brood")))return YukkuriAction.DROOP;
        // A real successful conception gets a brief response before ordinary brooding resumes.
        // Food, sleep, danger, movement and other active duties retain their own presentation.
        if(reactionTicks>60&&reactionEvent.equals("expecting")
                &&(behavior.equals("idle")||behavior.equals("tend_brood")))return YukkuriAction.SHIAWASE;
        YukkuriAction action = io.github.longshou.mcyukkuri.social.EcologyPresentation.action(behavior);
        // House behaviors and predator hunts own their body clips on the client.
        if (action != YukkuriAction.IDLE || behavior.startsWith("house_") || predator.active() || isPassenger()) return action;
        if (reactionTicks > 0) action = io.github.longshou.mcyukkuri.social.EcologyPresentation.reaction(reactionEvent);
        // Back ashore with her hat, the aquatic Marisa shakes off the water.
        if (action == YukkuriAction.SHIAWASE && reactionEvent.equals("returned_accessory")
                && subspecies.kind() == io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.AQUATIC_MARISA) action = YukkuriAction.SHAKE;
        return action == YukkuriAction.IDLE && (!life.hasAccessory() || body.weak() || body.dying()) ? YukkuriAction.DROOP : action;
    }
    /** The aquatic owner's boat set, or the young aboard rocked in the hat. */
    private YukkuriAction boatAction(io.github.longshou.mcyukkuri.subspecies.HatBoatEntity boat, String behavior) {
        if(boat.rocking()||boat.capsizing())return getUUID().equals(boat.ownerYukkuri())?YukkuriAction.BOAT_STEADY:YukkuriAction.HAT_STUMBLE;
        if(behavior.startsWith("boat_alarm_")||behavior.equals("boat_answer_call")||behavior.equals("boat_calm"))
            return io.github.longshou.mcyukkuri.social.EcologyPresentation.action(behavior);
        if(behavior.equals("boat_flee")||behavior.equals("boat_return_called"))
            return getUUID().equals(boat.ownerYukkuri())&&boat.paddling()?YukkuriAction.BOAT_PADDLE:YukkuriAction.BOAT_FLEE;
        if(behavior.equals("boat_reassure")||behavior.equals("boat_rescue_young")||behavior.equals("boat_rescue_approach")||behavior.equals("boat_crowd_guard"))
            return getUUID().equals(boat.ownerYukkuri())&&boat.paddling()?YukkuriAction.BOAT_PADDLE:YukkuriAction.BOAT_REASSURE;
        if (getUUID().equals(boat.ownerYukkuri()))
            return io.github.longshou.mcyukkuri.social.EcologyPresentation.boat(this, boat, behavior, reactionTicks > 0 ? reactionEvent : "");
        if (behavior.equals("hat_walk") || behavior.equals("boat_be_carried"))
            return io.github.longshou.mcyukkuri.social.EcologyPresentation.action(behavior);
        // Real sleep, meals and ongoing care win over a lingering newborn reaction.
        return life.isSleeping() ? YukkuriAction.SLEEP : life.mealTicks() > 0 ? io.github.longshou.mcyukkuri.social.EcologyPresentation.action(behavior)
                : behavior.equals("newborn_crawl") || behavior.equals("learn_jump") || behavior.equals("boat_toilet")
                    || behavior.equals("first_toilet_wait") || behavior.equals("first_toilet_stimulated") ? io.github.longshou.mcyukkuri.social.EcologyPresentation.action(behavior)
                : behavior.equals("boat_listen") ? YukkuriAction.SWAY
                : behavior.equals("boat_receive_care") ? YukkuriAction.NUZZLE
                : behavior.equals("aquatic_watch_parent") ? YukkuriAction.HAT_CRADLE
                : behavior.equals("aquatic_answer_family") ? YukkuriAction.HAT_CRADLE
                : behavior.equals("boat_receive_paddle") ? YukkuriAction.HAT_CRADLE
                : behavior.equals("aquatic_paddle_practice") || behavior.equals("aquatic_imitate_paddle")
                    || behavior.equals("boat_follow_family") || behavior.equals("boat_return_family")
                    || behavior.equals("boat_guard_young") ? YukkuriAction.HAT_CRADLE
                : behavior.equals("voyage_launch") ? YukkuriAction.HAT_OFF_SELF_AQ
                : behavior.equals("voyage_proud") ? YukkuriAction.SHIAWASE
                : boat.changingSeat(this) && io.github.longshou.mcyukkuri.subspecies.HatVessel.aquatic(this) ? YukkuriAction.BOAT_BOARD
                : boat.onBrim(this) ? YukkuriAction.HAT_BRIM
                : reactionTicks > 0 && reactionEvent.equals("newborn") ? YukkuriAction.FIRST_WAKE : YukkuriAction.HAT_CRADLE;
    }

    /** Shell Marisa withdraws where it is instead of fleeing (development plan 4.4). */
    private boolean shellRetracts() {
        return shellRetraction.available();
    }

    private void setBehavior(String behavior) {
        if (!getBehaviorId().equals(behavior)) {
            entityData.set(BEHAVIOR, behavior); entityData.set(BEHAVIOR_START, level().getGameTime());
        }
    }

    /** A parent whose newborn was lost at birth: a blank stare, then crying and calling out (bereaved clip). */
    public void bereave() {
        if (level().isClientSide) return;
        reactionTicks = 100; reactionExpression = "surprise"; reactionEvent = "birth_lost"; mind.felt("birth_lost");
        pendingSpeech = "family_died"; pendingSpeechTicks = 32;
    }

    /** A reaction whose line is spoken after a delay, when the clip reaches its face (the newborn's first
     *  greeting waits for the first_wake clip to open its eyes and smile; project original timing). */
    public void showReactionDelayed(String expression, String event, int ticks, int speechDelayTicks) {
        if (level().isClientSide) return;
        reactionTicks = ticks; reactionExpression = expression; reactionEvent = event == null ? "" : event; mind.felt(reactionEvent);
        pendingSpeech = reactionEvent; pendingSpeechTicks = Math.max(1, speechDelayTicks);
    }

    public boolean hasReaction() { return reactionTicks > 0; }
    public void showReaction(String expression, String dialogue, int ticks) {
        if (level().isClientSide) return;
        reactionTicks = ticks; reactionExpression = expression; reactionEvent = dialogue == null ? "" : dialogue; mind.felt(reactionEvent);
        speak(dialogue);
    }

    /** Dialogue tier from the life stage: baby, juvenile and adult; an adult kept childish by voice flags stays "young". */
    public String speechTier() {
        int stage = lifeStage();
        if (stage == FamilyLife.Stage.BABY.ordinal()) return "baby";
        if (stage == FamilyLife.Stage.JUVENILE.ordinal()) return "juvenile";
        return family.youngVoice() ? "young" : "adult";
    }

    /** Say a quantity the way a yukkuri does: 1, 2, 3, then "lots" (see {@link io.github.longshou.mcyukkuri.dialogue.SpeechCount}). */
    public boolean speakCount(String base, int quantity) {
        String event = io.github.longshou.mcyukkuri.dialogue.SpeechCount.event(base, quantity, variant.dialogueSpecies(), speechTier());
        return event != null && speak(event);
    }

    public boolean speak(String event) {
        if (level().isClientSide || !isAlive()) return false;
        event = mind.voice(event); // a broken mind can only scream
        String species = variant.dialogueSpecies();
        var line = NativeDialogue.select(species, speechTier(), myon.quirk(), event, speechRandom);
        long now = level().getGameTime();
        if (line == null || !speechTiming.admit(this, now, line.ticks(), NativeDialogue.priority(event))) return false;
        entityData.set(DIALOGUE, line.key());
        entityData.set(SPEECH_START, now); entityData.set(SPEECH_END, speechTiming.end());
        entityData.set(SPEECH_SEQUENCE, speechSequence() + 1);
        return true;
    }

    /** Repeat a currently heard, catalogued line and retain its actual speaker as provenance. */
    public boolean imitateSpeech(YukkuriEntity teacher) {
        if (level().isClientSide || !isAlive() || teacher == this || !teacher.isAlive()
                || teacher.level() != level() || distanceToSqr(teacher) > 36 || !hasLineOfSight(teacher)) return false;
        var line = NativeDialogue.byKey(teacher.getDialogueKey());
        long now = level().getGameTime();
        if (line == null || teacher.speechEnd() <= now || !line.species().equals(teacher.getVariant().dialogueSpecies())
                || !java.util.Objects.equals(line.quirk(), line.species().equals(variant.dialogueSpecies()) ? myon.quirk()
                        : io.github.longshou.mcyukkuri.dialogue.SpeechQuirk.of(line.species(), getUUID()))
                || !speechTiming.admit(now, line.ticks(), 1)) return false;
        entityData.set(DIALOGUE, line.key());
        entityData.set(SPEECH_START, now); entityData.set(SPEECH_END, speechTiming.end());
        entityData.set(SPEECH_SEQUENCE, speechSequence() + 1);
        var learned = new CompoundTag(); learned.putUUID("Teacher", teacher.getUUID());
        learned.putString("Species", line.species()); learned.putString("Key", line.key());
        learned.putLong("HeardAt", now); learned.putInt("Uses", getPersistentData().getCompound("LearnedDialect").getInt("Uses") + 1);
        getPersistentData().put("LearnedDialect", learned);
        return true;
    }

    private boolean boutDamage;
    private float pendingHitHealth;
    public boolean hurtInBout(DamageSource source,float amount) {
        boutDamage=true;try{return hurt(source,amount);}finally{boutDamage=false;}
    }
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (shellRetraction.protects(source)) amount *= .5f; // Actual withdrawn shell takes half a blow [original]
        float incoming = amount;
        if (!level().isClientSide && body != null) {
            amount = body.guardCore(source, amount); // the centre filling keeps a body with an unhurt core alive (komicolle 11518802)
            // A blow turned aside by the core still lands (flinch, knockback, wound) without taking the last health.
            if (amount <= 0 && !body.absorbedBlow()) return false;
        }
        float before=getHealth();
        float previousHit=pendingHitHealth;
        pendingHitHealth=before;
        boolean damaged;
        try { damaged = super.hurt(source, amount); }
        finally { pendingHitHealth=previousHit; }
        if (damaged && amount < incoming) lastHurt = Math.max(lastHurt, incoming); // the cooldown remembers the real blow
        // Soaking is attrition too: panicking every time the rain stung (every 5 s, panic 5.6 s) kept soaked settlers from ever reaching cover.
        // A wound's own bleeding, infection or mould (body.tick) is not an attack: no startle, no panic, nothing interrupted.
        // Before this an unbandaged wound sent its owner into a 100 tick panic every ten seconds and cancelled any comforting kin.
        if (damaged && !level().isClientSide && isAlive() && (body.internalDamage() || io.github.longshou.mcyukkuri.life.YukkuriLife.isSoaking(source))) return damaged;
        if (damaged && !level().isClientSide && isAlive()) {
            agitation.stop();
            var struckPart=body.strikePart();
            body.injured(source,before-getHealth());
            headwear.struck(source,incoming,struckPart);
            status.hurt(source);
            groupInteraction.stop();dailyLife.stop();woundComfort.stop();playLife.stop();
            if(!boutDamage&&source.getEntity() instanceof YukkuriEntity attacker)io.github.longshou.mcyukkuri.social.GroupInteractionGoal.witnessedAttack(this,attacker);
            operations.damaged(source);
            settlement.attackedBy(source,false);
            if(source.getEntity() instanceof Player attackerPlayer)io.github.longshou.mcyukkuri.event.HomeClaim.attacked(this,attackerPlayer);
            requestedAction = null;
            life.interrupt(); social.stop();
            if(!boutDamage)life.personality().attacked(source.getEntity()==null?null:source.getEntity().getUUID(),source.getEntity() instanceof Player);
            panicTicks = 112;
            if (myon.standGround(source)) panicTicks = 0; // a healthy Myon fights back instead of fleeing
            navigation.stop();
            if (shellRetracts()) shellRetraction.tick(panicTicks);
            else setAnimation(YukkuriAction.STARTLE, true);
        }
        return damaged;
    }

    @Override
    public void die(DamageSource source) {
        if (!level().isClientSide) {
            pet.care().died();
            treasure.dropAll();
            myon.died();
            agitation.stop();
            entityData.set(AGITATION, agitation.state());
            family.cancelBraidRescue();
            if(!deathRecorded)body.fatalInjury(source,pendingHitHealth);
            if(!deathRecorded)settlement.attackedBy(source,true);
            groupInteraction.stop();dailyLife.stop();woundComfort.stop();playLife.stop();
            requestedAction = null;
            operations.end();
            if(!deathRecorded) {
                // Capture the active burden before family.died cancels and clears maternal gestation.
                var pregnancy=family.brood();
                boolean stemDepletion=source.is(net.minecraft.world.damagesource.DamageTypes.STARVE)&&life.nutrition()==0
                        &&pregnancy!=null&&!pregnancy.cancelled&&!pregnancy.complete()
                        &&pregnancy.kind==io.github.longshou.mcyukkuri.family.FamilyData.BirthKind.STEM&&pregnancy.incidentSource!=null;
                boolean sukkiriExhaustion=getPersistentData().getBoolean("SukkiriExhaustion");
                deathRecorded=true;aquaticLife.dropStick();family.died();house.dropCarrying();habitat.dropCarrying();independence.abandon();settlement.duties().dropCarrying();body.died(source,stemDepletion,sukkiriExhaustion);
            }
            life.interrupt(); social.stop(); life.releaseHome();
            setAnimation(YukkuriAction.IDLE, true);
            entityData.set(EXPRESSION, "neutral");
        }
        super.die(source);
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if(io.github.longshou.mcyukkuri.life.Consumables.water(stack))return io.github.longshou.mcyukkuri.life.Consumables.feed(this,player,hand);
        if (stack.isEmpty()) {
            if (!level().isClientSide) {
                if(player.isSecondaryUseActive())io.github.longshou.mcyukkuri.interaction.CareMenu.open(player,this);
                else if(!io.github.longshou.mcyukkuri.shop.ShopMenu.clickStock(player,this)&&social.talk(player,true))pet.care().feedback(player,io.github.longshou.mcyukkuri.interaction.CareAction.GENTLE);
            }
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        if (stack.is(Items.SHEARS) && player.isSecondaryUseActive()) {
            if (!level().isClientSide) {
                operations.perform(player,io.github.longshou.mcyukkuri.interaction.CareAction.TAKE_ACCESSORY);
            }
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        boolean prepared = family.needsMash() && io.github.longshou.mcyukkuri.life.InfantFood.isMash(stack);
        if (!prepared && io.github.longshou.mcyukkuri.life.Toxins.dose(stack) > 0 && !life.acceptsFood(stack)) { // bitter or poisonous: refused unless starving
            if (!level().isClientSide) showReaction("cheek_puff", "refuse", 60);
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        boolean sweet = io.github.longshou.mcyukkuri.life.Sweets.is(stack);
        boolean mayFeed = prepared ? life.canReceiveCareFood(stack) && player.distanceToSqr(this) <= 9 && player.hasLineOfSight(this)
                : life.acceptsFood(stack) && (life.canEat() || life.canTakeSweet(stack));
        if (!mayFeed) {
            if (sweet && pet.owned(player) && life.acceptsFood(stack) && life.nutrition() > io.github.longshou.mcyukkuri.life.Sweets.FULL) { // too full even for a treat
                if (!level().isClientSide) showReaction("neutral", "snack_refuse", 60);
                return InteractionResult.sidedSuccess(level().isClientSide);
            }
            return super.mobInteract(player, hand);
        }
        if (!level().isClientSide) {
            ItemStack one = stack.copyWithCount(1);
            boolean spoiled = !prepared && !sweet && pet.owned(player) && life.nutrition() > 60; // sweets have their own indulgence rule
            boolean fed = prepared ? life.beginPreparedMeal(one, player.getUUID()) : life.beginMeal(one, player.getUUID(), false);
            if (fed && spoiled) pet.addIndulgence(2);
            if (fed && sweet) pet.lessons().reward(player, one);
            if (fed && !player.getAbilities().instabuild) stack.shrink(1);
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    // pet harness and lead (docs/PET_SHOP.md section 12): the lead is tied to the harness ring on the back, never to the accessory
    public int visibleWear() { return entityData.get(WEAR); }
    public void setWear(int bits) { entityData.set(WEAR, bits); }
    /** Only a harnessed pet can be led; PetCare/LeashGuard further restrict this to the owner (the check below is the same on both sides). */
    @Override public boolean canBeLeashed() { return (entityData.get(WEAR) & 1) != 0; }
    @Override protected Vec3 getLeashOffset() { return new Vec3(0, getBbHeight() * .13, -getBbWidth() * .46); }
    /** It does not follow while something is urgent or it is busy: the lead only holds it back past 6 blocks. */
    @Override protected boolean shouldStayCloseToLeashHolder() {
        return !life.hasUrgentNeed() && !pet.busy() && life.mealTicks() == 0 && !isCommandPlaying() && !operations.active();
    }
    /** The lead goes back into the hands that held it instead of lying on the ground. */
    @Override public void dropLeash(boolean broadcast, boolean dropItem) {
        var holder = getLeashHolder();
        super.dropLeash(broadcast, false);
        if (dropItem && holder != null && !level().isClientSide) pet.care().returnLead(holder);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.put("YukkuriLife", life.save());
        tag.put("WaterDistress", waterDistress.save());
        tag.put("YukkuriAgitation", agitation.save());
        tag.put("YukkuriSubspecies", subspecies.save());
        tag.put("YukkuriPet", pet.save());
        tag.put("YukkuriDailyLife",dailyLife.save());
        tag.put("YukkuriSpecies",species.save());
        tag.put("YukkuriTreasure",treasure.save());
        tag.put("YukkuriHeadwear",headwear.save());
        tag.put("YukkuriOffering",offering.save());
        tag.put("YukkuriWoundComfort",woundComfort.save());
        tag.put("YukkuriGroupInteraction",groupInteraction.save());
        tag.put("YukkuriSurface",surfaceLife.save());
        tag.put("YukkuriPlay",playLife.save());
        tag.put("YukkuriFamily", family.save());tag.putBoolean("FamilyDeathRecorded",deathRecorded);
        tag.put("YukkuriChildhood", childhood.save());
        tag.put("YukkuriHouse",house.save());
        if (variant.isHouse()) {
            // Read-only observation for operators and automated checks: which goals are running right now.
            var running = new net.minecraft.nbt.ListTag();
            goalSelector.getAvailableGoals().stream().filter(net.minecraft.world.entity.ai.goal.WrappedGoal::isRunning)
                    .forEach(goal -> running.add(net.minecraft.nbt.StringTag.valueOf(goal.getGoal().getClass().getSimpleName() + "@" + goal.getPriority())));
            tag.put("YukkuriDebugGoals", running);
        }
        if (forageTrip != null) tag.put("YukkuriForageView", forageTrip.debug()); // read-only, for operators and checks
        tag.put("YukkuriHabitatView", habitat.debug());
        tag.put("YukkuriBody",body.save());
        tag.put("YukkuriStatus",status.save());
        tag.put("YukkuriMind",mind.save());
        tag.put("YukkuriMyon",myon.save());
        if(level() instanceof ServerLevel)try{tag.put("YukkuriStatusView",io.github.longshou.mcyukkuri.status.YukkuriStatus.snapshot(this));}catch(RuntimeException ignored){}
        tag.put("YukkuriOperations",operations.save());
        tag.put("YukkuriSettlement",settlement.save());
        tag.put("YukkuriHabitat",habitat.save());
        tag.put("YukkuriIndependence",independence.save());
        if (variant.isPredator()) tag.put("YukkuriPredator", predator.save());
        // Command poses and transient anatomy states never survive a world reload.
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        subspecies.load(tag.getCompound("YukkuriSubspecies"));
        life.load(tag.getCompound("YukkuriLife"));
        requestedAction = null;
        panicTicks = reactionTicks = 0;
        shellRetraction.reset();
        pet.load(tag.getCompound("YukkuriPet"));
        dailyLife.load(tag.getCompound("YukkuriDailyLife"));
        species.load(tag.getCompound("YukkuriSpecies"));
        treasure.load(tag.getCompound("YukkuriTreasure"));
        headwear.load(tag.getCompound("YukkuriHeadwear"));
        offering.load(tag.getCompound("YukkuriOffering"));
        myon.load(tag.getCompound("YukkuriMyon"));
        woundComfort.load(tag.getCompound("YukkuriWoundComfort"));
        groupInteraction.load(tag.getCompound("YukkuriGroupInteraction"));
        surfaceLife.load(tag.getCompound("YukkuriSurface"));
        playLife.load(tag.getCompound("YukkuriPlay"));
        setAnimation(YukkuriAction.IDLE, true);
        entityData.set(EXPRESSION, "neutral");
        entityData.set(MEDAL, pet.visibleGrade().id()); entityData.set(ACCESSORY, life.hasAccessory()); entityData.set(MOUTH, life.mouth().copy());
        entityData.set(WEAR, pet.care().wearBits());
        if(tag.contains("YukkuriFamily"))family.load(tag.getCompound("YukkuriFamily"));
        childhood.load(tag.getCompound("YukkuriChildhood"));
        deathRecorded=tag.getBoolean("FamilyDeathRecorded");
        if(tag.contains("YukkuriHouse"))house.load(tag.getCompound("YukkuriHouse"));
        if(tag.contains("YukkuriBody"))body.load(tag.getCompound("YukkuriBody"));
        status.load(tag.getCompound("YukkuriStatus"));
        mind.load(tag.getCompound("YukkuriMind"),tag.getCompound("YukkuriPet"));
        operations.load(tag.getCompound("YukkuriOperations"));
        waterDistress.load(tag.getCompound("WaterDistress"));
        syncWaterDistress();
        settlement.load(tag.getCompound("YukkuriSettlement"));
        habitat.load(tag.getCompound("YukkuriHabitat"));
        independence.load(tag.getCompound("YukkuriIndependence"));
        if (variant.isPredator()) { predator.load(tag.getCompound("YukkuriPredator")); setNoGravity(false); }
        agitation.load(tag.getCompound("YukkuriAgitation"));
        entityData.set(AGITATION, agitation.state());
        entityData.set(HAIR_LOSS,body.hairLoss());
    }

    private final class CareReceiverGoal extends Goal {
        CareReceiverGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return careApproach.waiting(); }
        @Override public boolean canContinueToUse() { return careApproach.waiting(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { careApproach.tickReceiver(); }
        @Override public void stop() { careApproach.cancelIncoming(); }
    }

    private final class ChildhoodGoal extends Goal {
        ChildhoodGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return childhood.canUse(); }
        @Override public boolean canContinueToUse() { return childhood.canContinue(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { childhood.tickGoal(); }
        @Override public void stop() { childhood.stop(); }
    }

    private final class OperationGoal extends Goal {
        OperationGoal(){setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK));}
        @Override public boolean canUse(){return operations.controlling();}
        @Override public boolean canContinueToUse(){return operations.controlling();}
        @Override public boolean requiresUpdateEveryTick(){return true;}
        @Override public void start(){navigation.stop();}
        @Override public void tick(){navigation.stop();}
    }

    private final class HoldActionGoal extends Goal {
        HoldActionGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return requestedAction != null; }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() { navigation.stop(); }
        @Override public void tick() { navigation.stop(); }
    }

    private final class ShellGoal extends Goal {
        ShellGoal(){setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK));}
        @Override public boolean canUse(){return shellRetraction.controlsMovement();}
        @Override public boolean canContinueToUse(){return shellRetraction.controlsMovement();}
        @Override public boolean requiresUpdateEveryTick(){return true;}
        @Override public void tick(){navigation.stop();}
    }

    private final class FleeGoal extends Goal {
        FleeGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return panicTicks > 0 && !shellRetracts() && !shelteredInHat(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() {
            if (panicTicks > 100) { navigation.stop(); return; }
            if (navigation.isDone() || tickCount % 10 == 0) {
                if (getLastHurtByMob() instanceof YukkuriEntity threat && threat.getVariant().isPredator()) {
                    if (pet.fleeToOwner()) return;
                    var safe = life.homeStand();
                    if (safe != null && life.navigate(safe, 1.7, false)) return;
                }
                Vec3 target = getLastHurtByMob() == null ? DefaultRandomPos.getPos(YukkuriEntity.this, 6, 3)
                        : DefaultRandomPos.getPosAway(YukkuriEntity.this, 6, 3, getLastHurtByMob().position());
                if (target != null) navigation.moveTo(target.x, target.y, target.z, 1.7);
            }
        }
        @Override public void stop() { navigation.stop(); }
    }

    private final class LifeGoal extends Goal {
        LifeGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return life.plan(); }
        @Override public boolean canContinueToUse() { return life.plan(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { life.tickGoal(); }
        @Override public void stop() { life.stopGoal(); }
    }
    private final class HabitatGoal extends Goal {
        HabitatGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return !io.github.longshou.mcyukkuri.event.HomeClaim.holds(YukkuriEntity.this) && habitat.canUse(); }
        @Override public boolean canContinueToUse() { return !io.github.longshou.mcyukkuri.event.HomeClaim.holds(YukkuriEntity.this) && habitat.canContinue(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { habitat.tickGoal(); }
        @Override public void stop() { habitat.stop(); }
    }

    private final class SettlementDutyGoal extends Goal {
        SettlementDutyGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return settlement.duties().canUse(); }
        @Override public boolean canContinueToUse() { return settlement.duties().canContinue(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { settlement.duties().tickGoal(); }
        @Override public void stop() { settlement.duties().stop(); }
    }

    private final class SettlementGoal extends Goal {
        SettlementGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return !io.github.longshou.mcyukkuri.event.HomeClaim.holds(YukkuriEntity.this) && !settlement.underway() && settlement.canUse(); }
        @Override public boolean canContinueToUse() { return !io.github.longshou.mcyukkuri.event.HomeClaim.holds(YukkuriEntity.this) && settlement.canContinue(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { settlement.tickGoal(); }
        @Override public void stop() { settlement.stop(); }
    }
    /** The same settlement walk while this member takes part in a migration that has not reached the new site. */
    private final class MigrationGoal extends Goal {
        MigrationGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return !io.github.longshou.mcyukkuri.event.HomeClaim.holds(YukkuriEntity.this) && settlement.underway() && settlement.canUse(); }
        @Override public boolean canContinueToUse() { return !io.github.longshou.mcyukkuri.event.HomeClaim.holds(YukkuriEntity.this) && settlement.canContinue(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { settlement.tickGoal(); }
        @Override public void stop() { settlement.stop(); }
    }

    private final class PlaygroundGoal extends Goal {
        PlaygroundGoal(){setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK,Flag.JUMP));}
        @Override public boolean canUse(){return playLife.canUse();}
        @Override public boolean canContinueToUse(){return playLife.canContinue();}
        @Override public boolean requiresUpdateEveryTick(){return true;}
        @Override public void tick(){playLife.tickGoal();}
        @Override public void stop(){playLife.stop();}
    }
    private final class GroupGoal extends Goal {
        GroupGoal(){setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK));}
        @Override public boolean canUse(){return groupInteraction.canUse();}
        @Override public boolean canContinueToUse(){return groupInteraction.canContinueToUse();}
        @Override public boolean requiresUpdateEveryTick(){return true;}
        @Override public void tick(){groupInteraction.tick();}
        @Override public void stop(){groupInteraction.stop();}
    }
    /** A mate or kin walks up to an injured companion and licks it in worry (comfort only, never treatment). */
    private final class ComfortGoal extends Goal {
        ComfortGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return woundComfort.plan(); }
        @Override public boolean canContinueToUse() { return woundComfort.licking() && woundComfort.valid(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { woundComfort.tickGoal(); }
        @Override public void stop() { woundComfort.stop(); }
    }
    /** The injured companion stays and faces the kin that came to lick it. */
    private final class ComfortReceiverGoal extends Goal {
        ComfortReceiverGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return woundComfort.receiving(); }
        @Override public boolean canContinueToUse() { return woundComfort.receiving(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { woundComfort.tickReceiver(); }
        @Override public void stop() { woundComfort.stop(); }
    }
    private final class DailyGoal extends Goal {
        DailyGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return dailyLife.plan(); }
        @Override public boolean canContinueToUse() { return dailyLife.valid(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { dailyLife.tickGoal(); }
        @Override public void stop() { dailyLife.stop(); }
    }
    private final class SocialGoal extends Goal {
        SocialGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return social.canUse(); }
        @Override public boolean canContinueToUse() { return social.canContinue(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { social.tickGoal(); }
        @Override public void stop() { social.stop(); }
    }
    private final class EscapeWaterGoal extends Goal {
        EscapeWaterGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP, Flag.LOOK)); }
        @Override public boolean canUse() { return isInWaterOrBubble() || waterDistress.restingBreath(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { waterDistress.escapeTick(); }
        @Override public void stop() { navigation.stop(); }
    }
    private final class HouseExitGoal extends Goal {
        HouseExitGoal(){setFlags(EnumSet.of(Flag.MOVE));}
        @Override public boolean canUse(){return house.mustExit();}
        @Override public boolean canContinueToUse(){return house.mustExit();}
        @Override public boolean requiresUpdateEveryTick(){return true;}
        @Override public void tick(){house.tickExit();}
        @Override public void stop(){house.stopExit();}
    }
    private final class HouseGoal extends Goal {
        HouseGoal(){setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK));}
        @Override public boolean canUse(){return house.canUse();}
        @Override public boolean canContinueToUse(){return house.canContinue();}
        @Override public boolean requiresUpdateEveryTick(){return true;}
        @Override public void tick(){house.tickGoal();}
        @Override public void stop(){house.stop();}
    }
    private final class FamilyGoal extends Goal {
        FamilyGoal() { setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK)); }
        @Override public boolean canUse() { return (!io.github.longshou.mcyukkuri.event.WildEvents.reserved(YukkuriEntity.this)||life.hasUrgentNeed())&&family.canUse(); }
        @Override public boolean canContinueToUse() { return (!io.github.longshou.mcyukkuri.event.WildEvents.reserved(YukkuriEntity.this)||life.hasUrgentNeed())&&family.canContinue(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { family.tickGoal(); }
        @Override public void stop() { family.stop(); }
    }
}
