package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.random.ModRandom;

import io.github.longshou.mcyukkuri.entity.SceneHold;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriGroundNavigation;
import io.github.longshou.mcyukkuri.event.HomeClaim;
import io.github.longshou.mcyukkuri.interaction.InteractionDispatch;
import io.github.longshou.mcyukkuri.interaction.ItemContact;
import io.github.longshou.mcyukkuri.item.OperationToolItem;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.rules.HumanSight;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.play.PlayBlockEntity;
import io.github.longshou.mcyukkuri.item.TreatmentItem;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.Passage;
import io.github.longshou.mcyukkuri.motor.geom.DoorPassage;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.pet.PetScenes;
import io.github.longshou.mcyukkuri.pet.exam.ExamMenu;
import io.github.longshou.mcyukkuri.pet.exam.ExamSession;
import io.github.longshou.mcyukkuri.shop.CommodityLife;
import io.github.longshou.mcyukkuri.shop.KeeperMenu;
import io.github.longshou.mcyukkuri.shop.ShopMenu;
import io.github.longshou.mcyukkuri.shop.ShopRegistry;
import io.github.longshou.mcyukkuri.shop.ShopStaff;
import io.github.longshou.mcyukkuri.shop.ShopDoors;
import io.github.longshou.mcyukkuri.life.TravelRange;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.ServerLevelAccessor;

public final class HumanNpc extends PathfinderMob {
    /** CHILD is a park resident: it plays by itself (ChildPlay) and is outside every adult service and street duty. */
    public enum Look { ONIISAN, BROTHER, SISTER, CHILD }
    public enum Job { RESIDENT, SHOPKEEPER, FACTORY, EXAMINER }
    public static final int DOSE_SWING_TICKS = 20, DOSE_TICKS = 30;
    public enum Attitude { CRUEL, CARING, NEUTRAL }
    private static final EntityDataAccessor<Integer> JOB = SynchedEntityData.defineId(HumanNpc.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ATTITUDE = SynchedEntityData.defineId(HumanNpc.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> RESTING = SynchedEntityData.defineId(HumanNpc.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> STREET_GRAIN = SynchedEntityData.defineId(HumanNpc.class, EntityDataSerializers.BOOLEAN);
    /** 0 standing, else the horizontal direction (2D data value + 1) the owner faces while sitting on a bench board. Derived each tick, not saved. */
    private static final EntityDataAccessor<Integer> SEAT = SynchedEntityData.defineId(HumanNpc.class, EntityDataSerializers.INT);
    /** The child's gesture pose drawn by the client: see {@link ChildPlay.Pose}. */
    private static final EntityDataAccessor<Integer> CHILD_POSE = SynchedEntityData.defineId(HumanNpc.class, EntityDataSerializers.INT);
    private final Look look;
    private BlockPos home;
    private String siteRole = "";
    private UUID customer;
    private boolean initialized;
    private long nextRestock;
    /** Grain the shop staff can hand out per game day (the stock is fed from here and from the pen bowls they fill). */
    private static final int SHOP_DAILY_GRAIN = 96;
    private static final int YARD_COMPANION_CAP = 3;
    private BlockPos yardBowl, yardCrate;
    private int trades, careFood, medicine = 8, routineDelay;
    private long counterClosesAt;
    /** Next day's check of the corner shop's newspaper stack; not saved, a reload only re-checks a stack that is short. */
    private long nextNewspaper;
    private int nextBadgeGreeting;
    private YukkuriEntity attention;
    final HumanStreet street = new HumanStreet(this);
    private final LegacyFactoryOutput legacyOutput = new LegacyFactoryOutput();
    private UUID plantSite;
    /** Created on first use: registerGoals() runs inside the Mob constructor, before field initialisers. */
    private HumanWalk walk;
    private HumanWalk walk() { if (walk == null) walk = new HumanWalk(this); return walk; }
    private HumanStreet.Stroll stroll;
    private HumanStreet.Stroll stroll() { if (stroll == null) stroll = new HumanStreet.Stroll(this); return stroll; }
    public UUID walkPetId() { return animals(Custody.Role.COMPANION).stream().findFirst().map(Entity::getUUID).orElse(null); }
    public boolean walking() { return walk().walking() || stroll().outing(); }
    public boolean holdsLeadFor(YukkuriEntity pet) { return walk().leads(pet) || stroll().escorts(pet); }
    boolean leadingWalk() { return walk().walking(); }
    public boolean strollingWith(YukkuriEntity pet) { return stroll.escorts(pet); }
    public PlayBlockEntity parkFacility() { return stroll().parkFacility(); }
    public List<PlayBlockEntity> parkFacilities() { return stroll().parkFacilities(); }
    public boolean parkLingering() { return stroll().parkLingering(); }
    public boolean picnicActive() { return stroll().picnicActive(); }
    public boolean gatheringForReturn() { return stroll().gatheringForReturn(); }
    public net.minecraft.world.phys.AABB plazaPaving() { return stroll().plazaPaving(); }
    public boolean callingPet(YukkuriEntity pet) { return stroll().calling(pet); }
    /** Gives the resident this individual as its companion. */
    public void adoptPet(YukkuriEntity pet) { walk().adopt(pet); }
    /** Starts the outing now (leash on, strolling near home); false when the pet is busy, away or cannot wear the lead. */
    boolean beginWalk(YukkuriEntity pet) { return walk().begin(pet); }

    /** One contact at the current stand. The caller chooses when to approach and use its equipped tool. */
    public boolean useHeldTool(YukkuriEntity target) { return InteractionDispatch.useHeldTool(this, target); }

    public boolean releaseTool(YukkuriEntity target) { return InteractionDispatch.releaseTool(this, target); }

    boolean reachesPickup(ItemEntity item) {
        return level() instanceof ServerLevel && level() == item.level() && isAlive() && onGround() && !isPassenger()
                && item.isAlive()
                && getBoundingBox().inflate(1.5, .5, 1.5).intersects(item.getBoundingBox()) && hasLineOfSight(item);
    }

    public boolean pickUpTool(ItemEntity item) {
        if (!getMainHandItem().isEmpty() || item.hasPickUpDelay() || !reachesPickup(item)) return false;
        var stack = item.getItem();
        if (!(stack.getItem() instanceof OperationToolItem tool) || !tool.tool().staffUse()) return false;
        setItemSlot(EquipmentSlot.MAINHAND, stack);
        take(item, stack.getCount());
        item.discard();
        GameplayLog.event(this, "interaction", "tool picked up; drop={} item={} hand_count=0 -> {} damage={}",
                item.getUUID(), stack.getItem(), stack.getCount(), stack.getDamageValue());
        return true;
    }

    public boolean holdsPicnicFood() { return PicnicFood.apple(getMainHandItem()); }

    boolean pickUpFood(ItemEntity item) {
        var hand = getMainHandItem();
        var stack = item.getItem();
        if (!PicnicFood.apple(stack) || item.hasPickUpDelay() || !reachesPickup(item)) return false;
        var half = PicnicFood.collected(stack);
        if (!hand.isEmpty() && (!ItemStack.isSameItemSameComponents(hand, half) || hand.getCount() >= hand.getMaxStackSize())) return false;
        if (hand.isEmpty()) setItemSlot(EquipmentSlot.MAINHAND, half);
        else hand.grow(1);
        take(item, 1);
        stack.shrink(1);
        if (stack.isEmpty()) item.discard();
        else item.setItem(stack);
        return true;
    }

    private void dropPicnicFood(String reason) {
        if (!holdsPicnicFood()) return;
        var hand = getMainHandItem();
        var drop = ItemContact.release(this, PicnicFood.mark(hand), position().add(0, .2, 0), Vec3.ZERO);
        if (drop == null) {
            setDropChance(EquipmentSlot.MAINHAND, 2.0f);
            return;
        }
        stopUsingItem();
        setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        GameplayLog.event(this, "npc-care", "action=picnic_drop item={} count={} reason={} half={}",
                hand.getItem(), hand.getCount(), reason, drop.getUUID());
    }

    @Override protected void completeUsingItem() {
        var food = getUseItem();
        var item = food.getItem();
        int count = food.getCount();
        boolean picnic = !level().isClientSide && PicnicFood.apple(food);
        super.completeUsingItem();
        if (picnic && getMainHandItem().getCount() < count)
            GameplayLog.event(this, "npc-care", "action=picnic_ate item={} count=1", item);
    }

    public HumanNpc(EntityType<? extends HumanNpc> type, Level level, Look look) {
        super(type, level); this.look = look;
        setAttitude(look == Look.ONIISAN ? Attitude.CRUEL : look == Look.CHILD ? Attitude.NEUTRAL : Attitude.CARING);
        setPersistenceRequired();
        // The yarn ball and the bite of food it holds are real items: they fall where the child falls.
        if (look == Look.CHILD) { setDropChance(EquipmentSlot.MAINHAND, 2.0f); setDropChance(EquipmentSlot.OFFHAND, 2.0f); }
    }
    public static AttributeSupplier.Builder attributes() { return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 24)
            .add(Attributes.MOVEMENT_SPEED, .26).add(Attributes.FOLLOW_RANGE, 24); }
    @Override protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b); ModRandom.construct(this); b.define(JOB, 0); b.define(ATTITUDE, 1); b.define(RESTING, false); b.define(STREET_GRAIN, false);
        b.define(SEAT, 0); b.define(CHILD_POSE, 0);
    }
    public ChildPlay.Pose childPose() { return ChildPlay.Pose.values()[entityData.get(CHILD_POSE)]; }
    void setChildPose(ChildPlay.Pose pose) { entityData.set(CHILD_POSE, pose.ordinal()); }
    /** Sends the child back to its home cell for this long: the street's own go-home pause, which rain and predators use alike. */
    void goHome(int ticks) { routineDelay = Math.min(routineDelay, -ticks); }
    /** Sitting on a bench board: the pose is drawn by the client. The body turns across the bench, the head still follows what it watches. */
    @Override protected BodyRotationControl createBodyControl() {
        return new BodyRotationControl(this) {
            @Override public void clientTick() {
                if (!seated()) { super.clientTick(); return; }
                yBodyRot = seatFacing().toYRot();
                yHeadRot = Mth.rotateIfNecessary(yHeadRot, yBodyRot, getMaxHeadYRot());
            }
        };
    }
    public boolean seated() { return entityData.get(SEAT) != 0; }
    /** A pet running past must not shove the owner off the board. */
    @Override public boolean isPushable() { return !seated() && super.isPushable(); }
    public Direction seatFacing() { return Direction.from2DDataValue(entityData.get(SEAT) - 1); }
    /** Seated and not being carried along: the client draws the sit pose and lowers the body onto the board only then. */
    public boolean sitsNow() { return seated() && getDeltaMovement().horizontalDistanceSqr() < .002; }
    void sit(Direction facing) { entityData.set(SEAT, facing.get2DDataValue() + 1); }
    void standUp() { entityData.set(SEAT, 0); }
    public Look look() { return look; }
    public Job job() { return Job.values()[entityData.get(JOB)]; }
    public Attitude attitude() { return Attitude.values()[entityData.get(ATTITUDE)]; }
    public void setJob(Job job) { entityData.set(JOB, job.ordinal()); }
    public void setAttitude(Attitude attitude) { entityData.set(ATTITUDE, attitude.ordinal()); }
    public String siteRole() { return siteRole; }
    public UUID plantSite() { return plantSite; }
    public boolean sweeteningStaff() { return job() == Job.FACTORY && siteRole.equals("plant_sweetening") && plantSite != null; }
    void postAtPlant(UUID site, String role, BlockPos post) {
        plantSite = site;
        siteRole = role;
        home = post;
        setJob(Job.FACTORY);
        setAttitude(Attitude.NEUTRAL);
    }
    public BlockPos home() { return home == null ? blockPosition() : home; }
    public boolean resting() { return entityData.get(RESTING); }
    public boolean showsStreetGrain() { return entityData.get(STREET_GRAIN); }
    public boolean kicking() { return street.kickPose > 0; }
    public boolean frowning() { return street.frownPose > 0; }
    public boolean badgeCounter() { return job() == Job.RESIDENT && siteRole.equals("badge_counter"); }
    public boolean examAuthority() { return job() == Job.EXAMINER || badgeCounter(); }
    /** A child is no street resident: no scolding, tending, grain, witness duty or stroll. It only plays and goes home. */
    boolean streetResident() { return look != Look.CHILD && job() == Job.RESIDENT && (siteRole.isEmpty() || badgeCounter()); }
    public boolean atBadgeCounter() {
        return home != null && onGround() && distanceToSqr(Vec3.atBottomCenterOf(home)) < .64;
    }
    public boolean counterWorking() {
        return atBadgeCounter() && !inert() && !avoidingStreet() && !walking() && attention == null
                && getNavigation().isDone() && !street.helping() && !street.shooingCrop();
    }
    boolean avoidingStreet() { return routineDelay < 0; }
    boolean streetObservable() { return street.observable(); }
    boolean streetActive() {
        return level().isDay() && residentAvailable();
    }
    private boolean residentAvailable() {
        return streetResident() && initialized && isAlive() && !inert() && !avoidingStreet()
                && !stroll().outing() && !isTrading();
    }
    /** The resident's assigned stand, including at night. The caller checks physical presence. */
    public BlockPos householdHome() {
        return residentAvailable() && hurtTime == 0 ? home : null;
    }
    Counter counterWindow() { return stroll().counterWindow(); }
    boolean counterBusy() { return stroll().counterBusy(); }
    /** Whether this resident stands at a counter hatch serving it (the corner shop keeper, a market stall keeper). */
    public boolean attendingKiosk() { return counterWindow() != null; }
    public boolean offersKioskFoodTo(YukkuriEntity y) {
        var counter = counterWindow();
        return counter != null && !counterBusy() && street.counterGuest(y, counter) && counter.hasFood(y);
    }

    public boolean streetThreatTo(YukkuriEntity animal) { return street.threatTo(animal); }
    public static void witnessedStreetHurt(YukkuriEntity animal, Player player) { HumanStreet.witnessedHurt(animal, player); }
    public static void witnessedCropTheft(YukkuriEntity animal, BlockPos crop) { HumanStreet.witnessedCropTheft(animal, crop); }
    public static HumanNpc witnessedStallTake(YukkuriEntity animal, BlockPos crate, Vec3 contact) {
        return HumanStreet.witnessedStallTake(animal, crate, contact);
    }
    public static void witnessedBowlTake(YukkuriEntity animal, BlockPos bowl) {
        HumanStreet.witnessedBowlTake(animal, bowl);
    }
    boolean ownsYardBowl(BlockPos bowl) { return bowl.equals(yardBowl); }
    BlockPos yardBowl() { return yardBowl; }
    /** Its own pet ran to its feet about a beggar; the resident answers in its own manner (HumanStreet.helpPet). */
    public boolean helpPet(YukkuriEntity pet, YukkuriEntity stray) {
        if (inert()) return false;
        if (stroll().restingAtPlaza()) {
            street.scoldFromPlaza(pet, stray);
            return true;
        }
        if (stroll().outing()) return false;
        street.helpPet(pet, stray);
        return true;
    }
    /** Scenario hook: the next outing goes to the badge plaza without the choice roll. */
    public void forcePlaza() { stroll().forcePlaza(); }
    long streetFrownAfter() { return street.frownAfter(); }
    void streetFrown(long until) { street.frown(until); }
    public int careFood() { return careFood; }
    /** Takes up to {@code max} portions of grain from the finite daily supply (shop staff filling a bowl); returns how many. */
    public int drawCareFood(int max) { int taken = Math.min(max, careFood); careFood -= taken; return taken; }
    public int remainingTrades() { return Math.max(0, HumanWorld.TRADE_DAY_LIMIT.get() - trades); }
    public void usedTrade() { trades++; }
    public boolean donateFood() { if (careFood > 248) return false; careFood += 8; return true; }
    void takeStreetGrain() { careFood++; }
    public static boolean canServe(Player player, HumanNpc npc) {
        return npc != null && npc.job() != Job.FACTORY && npc.isAlive() && player.isAlive() && !player.isSpectator()
                && player.level() == npc.level() && player.distanceToSqr(npc) <= 36 && player.hasLineOfSight(npc);
    }
    public boolean beginTrade(Player player) {
        if (isTrading() && !player.getUUID().equals(customer)) return false;
        if (badgeCounter()) counterClosesAt = level().getGameTime() + 2400;
        customer = player.getUUID(); getNavigation().stop(); return true;
    }
    public void endTrade(Player player) { if (player.getUUID().equals(customer)) customer = null; }
    public boolean holdsCounterMenu(Player player) {
        return player.getUUID().equals(customer) && level().getGameTime() < counterClosesAt;
    }
    public boolean isTrading() {
        if (ExamSession.active(this)) return true;
        if (customer == null) return false;
        Player player = level().getPlayerByUUID(customer);
        return player != null && (player.containerMenu instanceof ShopMenu shop && shop.target() == this
                || player.containerMenu instanceof KeeperMenu keeper && keeper.target() == this
                || player.containerMenu instanceof ExamMenu exam && exam.target() == this
                && (!badgeCounter() || holdsCounterMenu(player)));
    }
    public List<YukkuriEntity> animals(Custody.Role role) {
        return animals(Objects.requireNonNull(role.id(), "UNKNOWN requires the original Use"));
    }
    /** Compatibility path also preserves exact matching of unknown legacy Use values. */
    public List<YukkuriEntity> animals(String use) {
        return level().getEntitiesOfClass(YukkuriEntity.class, getBoundingBox().inflate(48), y -> y.isAlive()
                && HumanCommerce.ownedBy(y, getUUID()) && Custody.view(y).rawUse().equals(use)).stream()
                .sorted(Comparator.comparing(Entity::getUUID)).toList();
    }
    /** A caretaker met in the open keeps a pet about half the time; commands and structure templates decide for themselves. */
    @Override public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
            MobSpawnType reason, SpawnGroupData data) {
        var result = super.finalizeSpawn(level, difficulty, reason, data);
        if ((reason == MobSpawnType.NATURAL || reason == MobSpawnType.CHUNK_GENERATION) && look != Look.ONIISAN && look != Look.CHILD && job() == Job.RESIDENT && getRandom().nextInt(100) < 45)
            HumanScenes.givePet(this, level.getLevel());
        return result;
    }
    @Override protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new PanicGoal(this, 1.25) {
            @Override public boolean canUse() { return !avoidingStreet() && super.canUse(); }
            @Override public boolean canContinueToUse() { return !avoidingStreet() && super.canContinueToUse(); }
        });
        goalSelector.addGoal(1, new AvoidEntityGoal<>(this, YukkuriEntity.class, 6, .85, 1.15,
                predator -> ((YukkuriEntity) predator).profile().dangerToYukkuri()) {
            @Override public boolean canUse() { return attendingKiosk() && super.canUse(); }
        });
        goalSelector.addGoal(1, new CourierGoal());
        goalSelector.addGoal(2, new CollectBadgeGoal());
        goalSelector.addGoal(2, new PlantTransfer(this));
        goalSelector.addGoal(2, new PlantSweetening(this));
        goalSelector.addGoal(2, new YardClaimGoal());
        goalSelector.addGoal(2, new YardBowlGoal());
        goalSelector.addGoal(2, new RoutineGoal());
        goalSelector.addGoal(3, new ShelterCartons(this));
        goalSelector.addGoal(3, walk().goal());
        goalSelector.addGoal(3, new StaffDoorGoal());
        goalSelector.addGoal(4, new ChildPlay(this));
        goalSelector.addGoal(4, new MoveTowardsRestrictionGoal(this, .85) {
            @Override public boolean canUse() { return !stroll().outing() && super.canUse(); }
        });
        goalSelector.addGoal(4, new VillageBin.Sweep(this));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, .65) {
            @Override public boolean canUse() { return job() != Job.SHOPKEEPER && job() != Job.FACTORY && !streetResident() && !resting() && !isTrading() && super.canUse(); }
        });
        goalSelector.addGoal(5, new CounterReturnGoal());
        goalSelector.addGoal(5, stroll());
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 6));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }
    @Override protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (job() == Job.FACTORY) return sweeteningStaff() ? PlantSweetening.receiveBasket(this, player, hand) : PlantReceiving.interact(player, this, hand);
        if (!level().isClientSide) {
            if (player.isShiftKeyDown() && CommodityLife.sellCore(player, this)) return InteractionResult.sidedSuccess(false);
            if (job() == Job.SHOPKEEPER) ShopMenu.open(player, this);
            else if (job() == Job.EXAMINER || badgeCounter() && atBadgeCounter()) ExamMenu.open(player, this);
            else HumanFolk.interact(player, this);
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    private final class CounterReturnGoal extends Goal {
        private Path route;
        private BlockPos failedFrom;

        CounterReturnGoal() { setFlags(EnumSet.of(Flag.MOVE)); }

        @Override public boolean canUse() {
            if (!badgeCounter() || home == null || !onGround() || atBadgeCounter() || isTrading()
                    || blockPosition().equals(failedFrom)) return false;
            route = getNavigation().createPath(home(), 0);
            if (route != null && route.canReach()) return true;
            failedFrom = blockPosition();
            GameplayLog.event(HumanNpc.this, "exam", "action=counter_return result=blocked post={}", home().toShortString());
            return false;
        }

        @Override public void start() {
            failedFrom = null;
            getNavigation().moveTo(route, .8);
        }

        @Override public boolean canContinueToUse() { return !isTrading() && !atBadgeCounter() && !getNavigation().isDone(); }

        @Override public void stop() {
            if (atBadgeCounter()) {
                stroll().restBeforeNextStroll();
                GameplayLog.event(HumanNpc.this, "exam", "action=counter_return result=arrived post={}", home().toShortString());
            } else if (getNavigation().isDone()) failedFrom = blockPosition();
            getNavigation().stop();
        }
    }

    @Override protected PathNavigation createNavigation(Level level) {
        return new HumanNavigation(level);
    }

    public Path shopPath(BlockPos stand) { return shopPath(Set.of(stand), null); }

    public Path shopPath(Set<BlockPos> stands, AABB blocker) {
        return ((HumanNavigation) getNavigation()).pathAround(stands, blocker);
    }

    boolean clearBeforeStopping(Vec3 target) { return ((HumanNavigation) getNavigation()).clearBeforeStopping(target); }
    Vec3 freeFloor(Vec3 target) { return ((HumanNavigation) getNavigation()).freeFloor(target); }
    Path yardPath(Set<BlockPos> stands, AABB blocker) {
        var nav = (HumanNavigation) getNavigation();
        return nav.pathAround(stands, blocker);
    }
    boolean finishingDoorway() { return ((HumanNavigation) getNavigation()).finishingDoorway(); }
    DoorExit crossedDoorway() { return ((HumanNavigation) getNavigation()).crossedDoorway(); }
    boolean holdsDoor(BlockPos at) { return isAlive() && !inert() && ((HumanNavigation) getNavigation()).holdsDoor(at); }
    boolean doorWaitingStand(DoorExit door) { return ((HumanNavigation) getNavigation()).doorWaitingStand(door); }
    public Vec3 doorFollowerStand(YukkuriEntity pet) { return stroll().doorFollowerStand(pet); }

    record DoorExit(BlockPos door, Direction exit) {
        Direction.Axis axis() { return exit.getAxis(); }
        boolean positive() { return exit.getAxisDirection() == Direction.AxisDirection.POSITIVE; }
        boolean cleared(Entity body) {
            var box = body.getBoundingBox();
            double origin = axis().choose(door.getX(), door.getY(), door.getZ());
            return DoorPassage.cleared(box.min(axis()) - origin, box.max(axis()) - origin, positive());
        }
        Vec3 stand(boolean keeper) {
            double offset = DoorPassage.stand(positive(), keeper) - .5;
            return Vec3.atBottomCenterOf(door).add(axis() == Direction.Axis.X ? offset : 0, 0,
                    axis() == Direction.Axis.Z ? offset : 0);
        }
        boolean keeperInPlace(HumanNpc keeper) {
            var target = stand(true);
            return keeper.onGround() && Math.abs(target.y - keeper.getY()) <= .5
                    && keeper.position().subtract(target).horizontalDistanceSqr() < .04;
        }
    }

    private final class HumanNavigation extends GroundPathNavigation {
        private AABB routeBlocker;
        private DoorExit lastExit, stoppingExit, clearedStop;
        private PassageRequest passing;
        private YieldApproach yieldApproach;

        private HumanNavigation(Level level) { super(HumanNpc.this, level); }

        @Override public Path createPath(BlockPos target, int accuracy) {
            // A shop round or a leashed outing (yard, street, park round a fence) outruns the ordinary follow range and node budget.
            if (accuracy == 0 && (ShopStaff.active(HumanNpc.this) || stroll().outing())) return pathAround(Set.of(target), null);
            // GroundPathNavigation lifts solid targets above the lintel. A doorway can itself be the destination.
            var door = handDoor(target);
            return door == null ? super.createPath(target, accuracy) : createPath(Set.of(door), accuracy);
        }

        private Path pathAround(Set<BlockPos> stands, AABB blocker) {
            // A route between opposite rooms can be longer than the ordinary NPC follow range.
            float range = TravelRange.PATH_RANGE;
            setMaxVisitedNodesMultiplier(range / (float) HumanNpc.this.getAttributeValue(Attributes.FOLLOW_RANGE));
            routeBlocker = blocker;
            try { return createPath(stands, 8, false, 0, range); }
            finally { routeBlocker = null; resetMaxVisitedNodesMultiplier(); }
        }

        @Override public boolean shouldRecomputePath(BlockPos at) {
            // Opening or closing an authorized leaf leaves its path traversable.
            if (!super.shouldRecomputePath(at)) return false;
            var state = level.getBlockState(at);
            return !(state.getBlock() instanceof DoorBlock door && (door.type().canOpenByHand()
                    || ShopStaff.active(HumanNpc.this) && ShopStaff.opensDoor(HumanNpc.this, at)));
        }

        @Override protected PathFinder createPathFinder(int maxVisitedNodes) {
            nodeEvaluator = new WalkNodeEvaluator() {
                @Override public PathType getPathTypeOfMob(PathfindingContext context, int x, int y, int z, Mob mob) {
                    // An overlapping body must not mark the worker's own departure node blocked.
                    var at = new BlockPos(x, y, z);
                    var type = super.getPathTypeOfMob(context, x, y, z, mob);
                    if (mob.blockPosition().equals(at)) return type;
                    var size = mob.getDimensions(mob.getPose());
                    if (routeBlocker != null && size.makeBoundingBox(x + .5, y, z + .5).intersects(routeBlocker)) return PathType.BLOCKED;
                    if (type != PathType.WALKABLE) return type;
                    // Thin overhead shapes can leave a walking node open while still clipping a human's head.
                    var above = at.above();
                    var ceiling = context.getBlockState(above).getCollisionShape(level, above);
                    if (ceiling.isEmpty()) return type;
                    var body = size.makeBoundingBox(x + .5, getFloorLevel(at), z + .5);
                    for (var part : ceiling.toAabbs()) if (part.move(above).intersects(body)) return PathType.BLOCKED;
                    return type;
                }

                @Override public PathType getPathType(PathfindingContext context, int x, int y, int z) {
                    var pos = new BlockPos(x, y, z); var state = context.getBlockState(pos);
                    if (state.getBlock() instanceof DoorBlock door && (door.type().canOpenByHand()
                            || !state.getValue(DoorBlock.OPEN) && ShopStaff.opensDoor(HumanNpc.this, pos))) return PathType.DOOR_WOOD_CLOSED;
                    return super.getPathType(context, x, y, z);
                }
            };
            nodeEvaluator.setCanPassDoors(true);
            nodeEvaluator.setCanOpenDoors(true);
            return new PathFinder(nodeEvaluator, maxVisitedNodes);
        }

        @Override public void tick() {
            if (giveWayAfterStop()) return;
            if (stoppingExit != null) {
                stepClearBeforeStopping();
                return;
            }
            if (!isDone()) {
                int index = path.getNextNodeIndex();
                var exit = nearbyExit();
                if (exit != null && HumanDoors.frame(exit.door()).intersects(mob.getBoundingBox())) lastExit = exit;
                if (giveWayAtDoor(exit)) return;
                openDoorAt(path.getNodePos(index));
                if (index + 1 < path.getNodeCount()) openDoorAt(path.getNodePos(index + 1));
            }
            var escort = stroll().doorWait();
            if (escort != null && escort.cleared(mob)) {
                lastTimeoutCheck = level.getGameTime();
                var stand = escort.keeperInPlace(HumanNpc.this) ? null : freeFloor(escort.stand(true));
                if (stand == null) Motor.waitOnFoot(mob);
                else mob.getMoveControl().setWantedPosition(stand.x, stand.y, stand.z, speedModifier);
                return;
            }
            if (stroll().waitingForCompanion()) {
                lastTimeoutCheck = level.getGameTime();
                Motor.waitOnFoot(mob);
                return;
            }
            super.tick();
        }

        private DoorExit doorExit(int index) {
            if (index < 0 || index + 1 >= path.getNodeCount()) return null;
            var door = handDoor(path.getNodePos(index));
            if (door == null) return null;
            var axis = level.getBlockState(door).getValue(DoorBlock.FACING).getAxis();
            var after = path.getNodePos(index + 1).subtract(door);
            int offset = axis.choose(after.getX(), after.getY(), after.getZ());
            return offset == 0 ? null : new DoorExit(door, exitDirection(axis, offset > 0));
        }

        private DoorExit nearbyExit() {
            if (isDone()) return null;
            int next = path.getNextNodeIndex();
            for (int i = Math.max(0, next - 1); i <= next + 1; i++) {
                var exit = doorExit(i);
                if (exit != null && !exit.cleared(mob)) return exit;
            }
            return null;
        }

        private DoorExit crossedDoorway() {
            return clearingDoorway() ? doorExit(path.getNextNodeIndex() - 1) : null;
        }

        private BlockPos overlappingDoorway() {
            var body = mob.getBoundingBox();
            var low = BlockPos.containing(body.minX, body.minY, body.minZ);
            var high = BlockPos.containing(body.maxX, body.maxY, body.maxZ);
            for (var pos : BlockPos.betweenClosed(low, high)) {
                if (!level.hasChunkAt(pos)) continue;
                var state = level.getBlockState(pos);
                if (!(state.getBlock() instanceof DoorBlock)) continue;
                var lower = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos.immutable();
                if (!level.getBlockState(lower).is(state.getBlock())) continue;
                var frame = HumanDoors.frame(lower);
                if (body.minY < frame.maxY && body.maxY > frame.minY
                        && DoorPassage.overlaps(body.minX - frame.minX, body.maxX - frame.minX)
                        && DoorPassage.overlaps(body.minZ - frame.minZ, body.maxZ - frame.minZ)) return lower;
            }
            return null;
        }

        private boolean clearBeforeStopping(Vec3 target) {
            if (finishingDoorway()) return false;
            var door = overlappingDoorway();
            if (door == null) return true;
            var axis = level.getBlockState(door).getValue(DoorBlock.FACING).getAxis();
            var planned = nearbyExit();
            if (planned != null && planned.equals(clearedStop) && planned.door().equals(door)) return false;
            if (planned == null || !planned.door().equals(door)) planned = lastExit;
            Boolean direction = planned != null && planned.door().equals(door) && planned.axis() == axis ? planned.positive() : null;
            double origin = axis.choose(door.getX(), door.getY(), door.getZ());
            stoppingExit = new DoorExit(door, exitDirection(axis,
                    DoorPassage.stopSide(direction, target.get(axis) - origin, mob.position().get(axis) - origin)));
            return false;
        }

        private boolean finishingDoorway() { return stoppingExit != null || passing != null; }

        private Direction exitDirection(Direction.Axis axis, boolean positive) {
            return Direction.fromAxisAndDirection(axis, positive ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
        }

        private void stepClearBeforeStopping() {
            var door = overlappingDoorway();
            if (door == null) {
                clearedStop = stoppingExit;
                stop();
                Motor.waitOnFoot(mob);
                GameplayLog.event(mob, "move", "action=door_clear_stop door={}", clearedStop.door().toShortString());
                return;
            }
            var axis = level.getBlockState(door).getValue(DoorBlock.FACING).getAxis();
            boolean positive = stoppingExit.axis() == axis ? stoppingExit.positive() : mob.position().get(axis) >= door.getCenter().get(axis);
            var stand = DoorPassage.stopStand(positive, side -> clearStopStand(new DoorExit(door, exitDirection(axis, side))));
            if (stand == null) Motor.waitOnFoot(mob);
            else {
                stoppingExit = new DoorExit(door, exitDirection(axis, stand.get(axis) > door.getCenter().get(axis)));
                mob.getMoveControl().setWantedPosition(stand.x, stand.y, stand.z, .8);
            }
        }

        private Vec3 clearStopStand(DoorExit exit) {
            var centre = clearStopStep(exit.stand(false));
            if (centre != null) return centre;
            var axis = exit.axis();
            double origin = axis.choose(exit.door().getX(), exit.door().getY(), exit.door().getZ());
            double edge = DoorPassage.stopEdge(exit.positive(), mob.getBbWidth() * .5, MoveControl.MIN_SPEED);
            return clearStopStep(mob.position().with(axis, origin + edge));
        }

        private Vec3 clearStopStep(Vec3 target) {
            return clearStopStep(mob.position(), target);
        }

        private Vec3 clearStopStep(Vec3 from, Vec3 target) {
            var stand = freeFloor(target);
            if (stand == null) return null;
            var offset = stand.subtract(from);
            var sweep = mob.getDimensions(mob.getPose()).makeBoundingBox(from.add(0, Math.max(0, offset.y), 0))
                    .expandTowards(offset.x, 0, offset.z);
            return level.noCollision(mob, sweep) ? stand : null;
        }

        @Override public void stop() {
            stoppingExit = null;
            super.stop();
        }

        private boolean doorWaitingStand(DoorExit door) {
            var follower = freeFloor(door.stand(false));
            var keeper = freeFloor(door.stand(true));
            if (follower == null || keeper == null) return false;
            var higher = follower.with(Direction.Axis.Y, Math.max(follower.y, keeper.y));
            return level.noCollision(mob, mob.getDimensions(mob.getPose()).makeBoundingBox(higher)
                    .expandTowards(keeper.x - follower.x, 0, keeper.z - follower.z));
        }

        private Vec3 freeFloor(Vec3 near) {
            var size = mob.getDimensions(mob.getPose());
            var stand = SurfaceApproach.restingFloor(level, size, near);
            if (stand == null) return null;
            var body = size.makeBoundingBox(stand);
            return level.noCollision(mob, body) && level.getEntitiesOfClass(LivingEntity.class, body,
                    other -> other != mob && other.isAlive() && !other.isSpectator()).isEmpty() ? stand : null;
        }

        private record PassageRequest(HumanNpc walker, DoorExit exit) {
            boolean pending(AABB frame) {
                var planned = ((HumanNavigation) walker.getNavigation()).nearbyExit();
                return walker.isAlive() && DoorPassage.awaitingPassage(exit.equals(planned),
                        frame.intersects(walker.getBoundingBox()), exit.cleared(walker));
            }
        }

        private record YieldApproach(DoorExit exit, Vec3 stand) {}

        private boolean giveWayAfterStop() {
            if (clearedStop == null) return false;
            var frame = HumanDoors.frame(clearedStop.door());
            if (passing != null && !passing.pending(frame)) {
                passing = null;
                yieldApproach = null;
            }
            if (passing == null) {
                if (!frame.inflate(3, 0, 3).intersects(mob.getBoundingBox())) {
                    clearedStop = null;
                    return false;
                }
                for (var other : level.getEntitiesOfClass(HumanNpc.class, frame.inflate(3, 0, 3),
                        candidate -> candidate != mob && candidate.isAlive() && !candidate.inert())) {
                    var nav = (HumanNavigation) other.getNavigation();
                    if (nav.passing != null) continue;
                    var exit = nav.nearbyExit();
                    if (exit != null && exit.door().equals(clearedStop.door())) {
                        passing = new PassageRequest(other, exit);
                        break;
                    }
                }
            }
            if (passing == null) return false;

            stoppingExit = null;
            lastTimeoutCheck = level.getGameTime();
            var walker = passing.walker();
            var stand = yieldStand(walker);
            if (stand == null || stand.equals(mob.position())) Motor.waitOnFoot(mob);
            else mob.getMoveControl().setWantedPosition(stand.x, stand.y, stand.z, .8);
            logDoorGiveWay(walker, clearedStop, stand != null);
            return true;
        }

        private Vec3 yieldStand(HumanNpc walker) {
            var exit = yieldApproach == null ? clearedStop : yieldApproach.exit();
            if (exit.cleared(mob)) {
                var stand = aside(exit, walker, mob.position(), this::clearStep);
                if (stand != null) return stand;
            }
            if (yieldApproach == null) {
                // A furnished approach can be single-file. Walk out ahead of the other resident if it has room to pass there.
                openDoorAt(clearedStop.door());
                yieldApproach = DoorPassage.stopStand(clearedStop.positive(), positive -> {
                    var side = new DoorExit(clearedStop.door(), exitDirection(clearedStop.axis(), positive));
                    var approach = clearStopStep(side.stand(side.equals(clearedStop)));
                    return approach != null && aside(side, walker, approach, to -> clearStopStep(approach, to) != null) != null
                            ? new YieldApproach(side, approach) : null;
                });
            }
            return yieldApproach == null ? null : clearStopStep(yieldApproach.stand());
        }

        private boolean giveWayAtDoor(DoorExit exit) {
            if (exit == null || mob.distanceToSqr(exit.stand(false)) > 9) return false;
            var frame = HumanDoors.frame(exit.door());
            boolean inFrame = frame.intersects(mob.getBoundingBox());
            for (var other : level.getEntitiesOfClass(HumanNpc.class, frame.inflate(1.25, 0, 1.25),
                    candidate -> candidate != mob && candidate.isAlive())) {
                var navigation = (HumanNavigation) other.getNavigation();
                if (navigation.passing != null && navigation.passing.walker() == mob) continue;
                var opposite = navigation.nearbyExit();
                boolean otherInFrame = frame.intersects(other.getBoundingBox());
                if (!(opposite != null && opposite.door().equals(exit.door()) && opposite.exit() == exit.exit().getOpposite())
                        && !otherInFrame) continue;
                if (opposite != null && opposite.door().equals(exit.door()) && opposite.exit() == exit.exit()) continue;
                if (!DoorPassage.yields(inFrame, otherInFrame, other.getUUID().compareTo(mob.getUUID()) < 0)) continue;
                var from = mob.position();
                var stand = aside(exit, other, from, this::clearStep);
                if (stand == null) {
                    var back = new DoorExit(exit.door(), exit.exit().getOpposite()).stand(true);
                    if (clearStep(back)) stand = back;
                }
                if (stand == null || mob.distanceToSqr(stand) < .04) Motor.waitOnFoot(mob);
                else mob.getMoveControl().setWantedPosition(stand.x, stand.y, stand.z, speedModifier);
                ++tick;
                doStuckDetection(getTempMobPos());
                logDoorGiveWay(other, exit, stand != null);
                return true;
            }
            return false;
        }

        private void logDoorGiveWay(HumanNpc walker, DoorExit exit, boolean aside) {
            int suppressed = GameplayLog.admit(mob, "move", "door_give_way", 40);
            if (suppressed >= 0) GameplayLog.limitedEvent(mob, "move", suppressed, "action=door_give_way walker={} door={} aside={}",
                    walker.getUUID(), exit.door().toShortString(), aside);
        }

        private Vec3 aside(DoorExit exit, HumanNpc walker, Vec3 from, Predicate<Vec3> clear) {
            var side = Vec3.atLowerCornerOf(exit.exit().getClockWise().getNormal());
            var offset = from.subtract(Vec3.atBottomCenterOf(exit.door()));
            if (offset.dot(side) < 0) side = side.reverse();
            double width = Passage.halfWidth(mob, side) + Passage.halfWidth(walker, side);
            return offset.dot(side) >= width + Passage.MARGIN ? from
                    : Passage.aside(from, offset, side, width, Passage.MARGIN + MoveControl.MIN_SPEED, clear);
        }

        private boolean clearStep(Vec3 stand) {
            return freeFloor(stand) != null && level.noCollision(mob, mob.getBoundingBox().expandTowards(stand.subtract(mob.position())));
        }

        @Override protected void followThePath() {
            // Reach the cell beyond the door before vanilla can cut the corner across the jamb.
            if (clearingDoorway()) doStuckDetection(getTempMobPos());
            else super.followThePath();
        }

        private boolean clearingDoorway() {
            if (isDone() || path.getPreviousNode() == null) return false;
            var door = handDoor(path.getPreviousNode().asBlockPos());
            return door != null && new AABB(door).expandTowards(0, 1, 0).intersects(mob.getBoundingBox());
        }

        private BlockPos handDoor(BlockPos pos) {
            if (!level.hasChunkAt(pos)) return null;
            var state = level.getBlockState(pos);
            if (!DoorBlock.isWoodenDoor(state)) return null;
            return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
        }

        private void openDoorAt(BlockPos pos) {
            // The next two nodes are within vanilla's door interaction reach only as we approach the doorway.
            if (mob.distanceToSqr(Vec3.atBottomCenterOf(pos)) > 2.25) return;
            var at = handDoor(pos);
            if (at == null) return;
            var state = level.getBlockState(at);
            if (state.getBlock() instanceof DoorBlock door && door.type().canOpenByHand() && !state.getValue(DoorBlock.OPEN)) {
                door.setOpen(mob, level, state, at, true);
                HumanDoors.opened((ServerLevel) level, at, HumanNpc.this);
                GameplayLog.event(mob, "npc-care", "action=walk_door result=opened door={}", at.toShortString());
            }
        }

        private boolean holdsDoor(BlockPos at) {
            if (!isDone()) {
                int next = path.getNextNodeIndex();
                for (int i = next; i < Math.min(next + 2, path.getNodeCount()); i++)
                    if (at.equals(handDoor(path.getNodePos(i)))) return true;
            }
            return leadThrough(HumanDoors.frame(at), level.getBlockState(at).getValue(DoorBlock.FACING).getAxis());
        }

        private boolean leadThrough(AABB doorway, Direction.Axis axis) {
            // A turn just outside can pull the lead sideways. Wait for the pet's body to clear the same side of the frame.
            boolean towardHighSide = mob.position().get(axis) >= doorway.getCenter().get(axis);
            return !level.getEntitiesOfClass(YukkuriEntity.class, doorway.inflate(Leashable.LEASH_TOO_FAR_DIST),
                    pet -> pet.isAlive() && pet.getLeashHolder() == mob && (towardHighSide
                            ? pet.getBoundingBox().min(axis) < doorway.max(axis)
                            : pet.getBoundingBox().max(axis) > doorway.min(axis))).isEmpty();
        }
    }

    @Override public void die(DamageSource source) {
        if (!level().isClientSide) {
            dropPicnicFood("death");
            ShelterCartons.releaseOnRemoval(this, "death");
            legacyOutput.collect(this);
            if (level() instanceof ServerLevel server) PlantReceiving.dropOnDeath(server, this);
            // Held doses and operation tools fall where the worker fell.
            if (getMainHandItem().getItem() instanceof TreatmentItem || getMainHandItem().getItem() instanceof OperationToolItem)
                setDropChance(EquipmentSlot.MAINHAND, 2.0f);
            // The loose piece it was carrying to a bin falls where the body fell, likewise.
            VillageBin.dropLitter(this);
            if (leadingWalk()) walk().end();
            if (level() instanceof ServerLevel sl && job() == Job.SHOPKEEPER) {
                ShopStaff.staffDied(this, sl);
                ShopRegistry.get(sl).staffDied(sl, this); // the shop closes only with its last staff member
            } else Custody.ownerDied(this);
        }
        super.die(source);
    }

    @Override public void onAddedToLevel() {
        super.onAddedToLevel();
        if (level().isClientSide) return;
        var data = getPersistentData();
        if (data.contains("VillageBinLitter")) {
            setItemInHand(InteractionHand.OFF_HAND, ItemStack.parseOptional(registryAccess(), data.getCompound("VillageBinLitter")));
            data.remove("VillageBinLitter");
        }
        VillageBin.dropLitter(this);
    }
    @Override public void remove(RemovalReason reason) {
        if (reason.shouldDestroy() && level() instanceof ServerLevel server && server.getEntity(getUUID()) == this) {
            dropPicnicFood("removal");
            ShelterCartons.releaseOnRemoval(this, "removal");
        }
        if (reason.shouldDestroy() && streetResident() && level() instanceof ServerLevel server
                && server.getEntity(getUUID()) == this && !CustodyData.get(server).deadNpc(getUUID())) {
            if (leadingWalk()) walk().end();
            Custody.ownerGone(server, getUUID());
        }
        super.remove(reason);
    }
    @Override public boolean hurt(DamageSource source, float amount) {
        boolean hurt = super.hurt(source, amount);
        if (hurt && isAlive() && !level().isClientSide && streetResident() && source.getEntity() instanceof Player) {
            routineDelay = -HumanStreet.DAY;
            street.cancel();
            ShelterCartons.dropHeldAtFeet(this, "player_hit");
            if (walk().walking()) walk().end();
            getNavigation().stop();
            GameplayLog.event(this, "npc-care", "action=street_go_home cause=player_hit ticks={}", -routineDelay);
        }
        return hurt;
    }
    @Override public void handleEntityEvent(byte id) {
        if (id == HumanStreet.KICK_EVENT) street.kickPose = HumanStreet.KICK_POSE_TICKS;
        else if (id == HumanStreet.FROWN_EVENT) street.frownPose = HumanStreet.FROWN_TICKS;
        else super.handleEntityEvent(id);
    }
    /** Decides nothing and starts nothing: deliberately NoAI, or in the scene start hold (see SceneHold). */
    public boolean inert() { return SceneHold.inert(this); }
    @Override protected boolean isImmobile() { return super.isImmobile() || SceneHold.held(this); }
    @Override public void aiStep() {
        Vec3 before = position();
        super.aiStep();
        if (street.kickPose > 0) street.kickPose--;
        if (street.frownPose > 0) street.frownPose--;
        if (level().isClientSide || !isAlive()) return;
        if (tickCount % 100 == 1) legacyOutput.collect(this);
        if (inert()) {
            if (job() == Job.SHOPKEEPER) ShopStaff.cancelWater(this, "no_ai");
            return;
        }
        if (job() == Job.FACTORY) return;
        if (streetResident() && !getNavigation().isDone())
            YukkuriGroundNavigation.noticePassage(this, before);
        if (tickCount == 1) returnUnusedDose();
        // The summon/structure loader applies its world position after reading NBT.
        if (home == null) {
            home = blockPosition();
            if (badgeCounter()) stroll().restBeforeNextStroll();
        }
        if (!initialized) {
            initialized = true;
            if (getTags().contains("mcy_street_local")) street.assignVillageAttitude();
            careFood = look == Look.CHILD ? 0 : streetResident() ? attitude() == Attitude.CARING ? Math.min(8, HumanWorld.CARE_SUPPLIES.get()) : 0
                    : HumanWorld.CARE_SUPPLIES.get();
            if (yardBowl != null) restockCareFood();
            nextRestock = level().getGameTime() + HumanStreet.DAY;
        }
        if (job() == Job.SHOPKEEPER && (tickCount == 1 || tickCount % 100 == 1) && level() instanceof ServerLevel server) {
            ShopRegistry.get(server).of(this);
        }
        restrictTo(home(), 18);
        if (level().getGameTime() >= nextRestock) {
            trades = 0; nextRestock = level().getGameTime() + HumanStreet.DAY;
            restockCareFood();
        }
        if (walk().walking()) walk().tick();
        entityData.set(RESTING, !level().isDay());
        if (routineDelay != 0) routineDelay -= Integer.signum(routineDelay);
        if (look == Look.CHILD && level().isRaining()) goHome(200);
        street.tick();
        if (attendingKiosk() && !counterBusy() && level().getGameTime() >= nextNewspaper) {
            nextNewspaper = level().getGameTime() + HumanStreet.DAY;
            NewspaperRestock.run(this, counterWindow());
        }
        street.pickUpGrain();
        entityData.set(STREET_GRAIN, streetResident() && attitude() == Attitude.CARING && careFood > 0);
        if (tickCount % 100 == 0 && !resting() && !isTrading()) serviceFacilities();
        if (tickCount % 40 == 17 && look != Look.CHILD && tickCount >= nextBadgeGreeting && !resting() && !isTrading() && !avoidingStreet()
                && !ExamSession.active(this)) greetBadge();
        if (job() == Job.SHOPKEEPER && tickCount % 20 == 3) ShopStaff.tick(this);
    }
    private void greetBadge() {
        var viewer = level().getNearestPlayer(this, 12);
        if (viewer == null || !(level() instanceof ServerLevel server)) return;
        var guest = level().getEntitiesOfClass(YukkuriEntity.class, getBoundingBox().inflate(4), y ->
                y.isAlive() && !y.life().isSleeping() && !ExamSession.running(y) && hasLineOfSight(y)
                        && y.pet().humanRank().id() >= BadgeGrade.SILVER.id()).stream()
                .min(java.util.Comparator.comparingDouble(this::distanceToSqr)).orElse(null);
        if (guest == null) return;
        nextBadgeGreeting = tickCount + 1200;
        getLookControl().setLookAt(guest);
        Staging.card(server, position().add(0, getBbHeight() + .45, 0),
                Component.translatable("human.mcyukkuri.greet_badge", guest.getDisplayName()), 60, 1.0f);
    }
    public boolean badgeStaff() { return job() == Job.EXAMINER || job() == Job.SHOPKEEPER; }
    private boolean collectible(YukkuriEntity y) {
        return y.pet().visibleGrade() != BadgeGrade.NONE && (y.pet().voidBadge() || y.pet().disqualified());
    }
    public boolean collectBadge(YukkuriEntity y) {
        if (!badgeStaff() || !collectible(y) || !getOffhandItem().isEmpty() || !HumanSight.sees(this, y)
                || !ShopStaff.reaches(this, y.getEyePosition(), null)) return false;
        var token = y.pet().token();
        var serial = y.pet().serial();
        setItemInHand(InteractionHand.OFF_HAND, token);
        setDropChance(EquipmentSlot.OFFHAND, 2f);
        y.pet().revoke();
        swing(InteractionHand.OFF_HAND);
        PetScenes.revoked(y);
        GameplayLog.event(this, "badge", "collected pet={} serial={} hand={} distance={} item={}",
                y.getUUID(), serial, InteractionHand.OFF_HAND, distanceTo(y), getOffhandItem().getItem());
        var near = level().getNearestPlayer(this, 16);
        if (near != null) near.displayClientMessage(Component.translatable("pet.mcyukkuri.abandon.revoked", y.getDisplayName()), true);
        return true;
    }
    private final class CollectBadgeGoal extends Goal {
        private YukkuriEntity subject;
        private Path path;
        private int nextScan;
        CollectBadgeGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        private boolean available() { return badgeStaff() && !resting() && !isTrading() && !ShopStaff.active(HumanNpc.this) && getOffhandItem().isEmpty(); }
        @Override public boolean canUse() {
            if (!available() || tickCount < nextScan) return false;
            nextScan = tickCount + 40;
            subject = level().getEntitiesOfClass(YukkuriEntity.class, getBoundingBox().inflate(6),
                    y -> collectible(y) && HumanSight.sees(HumanNpc.this, y)).stream()
                    .min(Comparator.comparingDouble(HumanNpc.this::distanceToSqr)).orElse(null);
            if (subject == null) return false;
            if (ShopStaff.reaches(HumanNpc.this, subject.getEyePosition(), null)) return true;
            path = getNavigation().createPath(subject, 0);
            return path != null && path.canReach();
        }
        @Override public void start() {
            if (!collectBadge(subject)) getNavigation().moveTo(path, .9);
        }
        @Override public boolean canContinueToUse() {
            return available() && subject.isAlive() && collectible(subject) && !getNavigation().isDone() && distanceToSqr(subject) <= 36;
        }
        @Override public void tick() {
            getLookControl().setLookAt(subject, 30, 30);
            if (collectBadge(subject)) getNavigation().stop();
        }
        @Override public void stop() { getNavigation().stop(); subject = null; path = null; }
    }
    void registerYardBowl(BlockPos bowl) {
        yardBowl = bowl;
        if (initialized) restockCareFood();
    }

    void registerYardCrate(BlockPos crate) { yardCrate = crate; }
    BlockPos yardCrate() { return yardCrate; }

    private void restockCareFood() {
        if (job() == Job.SHOPKEEPER) {
            careFood = Math.max(careFood, SHOP_DAILY_GRAIN);
        } else if (yardBowl != null && streetResident() && attitude() == Attitude.CARING) {
            int companions = Math.min(YARD_COMPANION_CAP, animals(Custody.Role.COMPANION).size());
            int supply = SHOP_DAILY_GRAIN / YARD_COMPANION_CAP * companions;
            if (careFood < supply) {
                GameplayLog.event(this, "npc-care", "action=yard_restock companions={} food={}->{}", companions, careFood, supply);
                careFood = supply;
            }
        }
    }

    /** Staff refill nearby bowls from finite saved supplies (shop staff walk to each bowl as a job instead, ShopStaff). */
    private void serviceFacilities() {
        if (job() == Job.RESIDENT || job() == Job.SHOPKEEPER || careFood <= 0) return;
        for (BlockPos p : BlockPos.betweenClosed(blockPosition().offset(-8, -1, -8), blockPosition().offset(8, 1, 8))) {
            if (!level().hasChunkAt(p) || !(level().getBlockEntity(p) instanceof CareBlockEntity bowl)
                    || bowl.kind() != CareBlock.Kind.BOWL || bowl.foodCount() >= 4) continue;
            if (fillRegularGrain(bowl)) return;
        }
    }
    private boolean fillRegularGrain(CareBlockEntity bowl) {
        for (int slot = 0; slot < bowl.getContainerSize(); slot++) if (bowl.getItem(slot).isEmpty()) {
            int count = drawCareFood(4);
            bowl.setItem(slot, new ItemStack(ModItems.GRAIN_REGULAR.get(), count));
            GameplayLog.event(this, "npc-care", "action=refill_bowl target={} food={}->{} transferred={}",
                    bowl.getBlockPos().toShortString(), careFood + count, careFood, count);
            return true;
        }
        return false;
    }
    /** A free-roaming resident feeds and pets wild yukkuri and its own pet; other people's pets and shop stock are not its business (staff tend their site's animals). */
    boolean mayTend(YukkuriEntity y) {
        if (!siteRole.isEmpty() && !badgeCounter()) return true;
        if (y.getLeashHolder() == this) return false; // the pet on the lead is walked, not fussed over
        return (Custody.view(y).owner() == null) || HumanCommerce.ownedBy(y, getUUID());
    }
    /** Contact is checked at execution, not only when the goal selects an animal. */
    public boolean attend(YukkuriEntity y) {
        if (job() == Job.FACTORY || !y.isAlive() || y.level() != level() || distanceToSqr(y) > 9 || !hasLineOfSight(y) || !level().isDay()) return false;
        if (streetResident()) return street.care(y);
        if (attitude() == Attitude.CRUEL) return false;
        if (y.life().canEat() && careFood > 0 && y.life().beginMeal(new ItemStack(ModItems.GRAIN_REGULAR.get()), getUUID(), true)) {
            careFood--; swing(InteractionHand.MAIN_HAND);
            GameplayLog.event(this, "npc-care", "action=begin_meal target={} food={}->{} nutrition={} result=accepted", y.getUUID(), careFood + 1, careFood, y.life().nutrition());
            return true;
        }
        // Treatment is staged by RoutineGoal, with a real reserved dose in the vanilla hand slot.
        if (!y.isPanicking()) {
            y.life().personality().talk(getUUID(), true);
            y.showReaction(YukkuriAction.MOOD_FLUTTER, "happy", "", 60); swing(InteractionHand.MAIN_HAND); return true;
        }
        return false;
    }
    /** While shop staff run a job (ShopStaff steers the walk), this holds the move and look flags so the routine, strolling and restriction goals stand aside. */
    private final class CourierGoal extends Goal {
        CourierGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return ShopStaff.active(HumanNpc.this); }
        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { ShopStaff.step(HumanNpc.this); }
        @Override public void stop() { ShopStaff.interrupt(HumanNpc.this); }
    }
    /**
     * On a job, shop staff open the iron staff door of their own shop that lies on their path and shut it once they are through
     * (the navigator handles hand-openable doors).
     */
    private final class StaffDoorGoal extends Goal {
        private BlockPos door;
        @Override public boolean canUse() {
            door = doorAhead();
            return door != null;
        }
        private BlockPos doorAhead() {
            if (!ShopStaff.active(HumanNpc.this) || isTrading() || hurtTime > 0) return null;
            var path = getNavigation().getPath();
            if (path == null || path.isDone()) return null;
            for (int i = path.getNextNodeIndex(); i < Math.min(path.getNextNodeIndex() + 2, path.getNodeCount()); i++) {
                var pos = path.getNodePos(i);
                if (distanceToSqr(pos.getX() + .5, getY(), pos.getZ() + .5) > 2.25) continue;
                var state = level().getBlockState(pos);
                if (state.getBlock() instanceof DoorBlock d && !d.type().canOpenByHand() && ShopStaff.opensDoor(HumanNpc.this, pos)) return pos;
            }
            return null;
        }
        @Override public boolean canContinueToUse() {
            return ShopStaff.active(HumanNpc.this)
                    && (ShopDoors.inDoorway(HumanNpc.this, door) || ShopDoors.holds(HumanNpc.this, doorAhead()));
        }
        @Override public void start() { ShopDoors.enter(HumanNpc.this, door); }
        @Override public void stop() { closeShopDoor(); }
    }
    public void closeShopDoor() { ShopDoors.leave(this); }

    /** The resident walks to its own bowl and uses the grain shown in its off hand. */
    private final class YardClaimGoal extends Goal {
        private static final double SIGHT = 12, CONTACT_SQ = 4;
        private YukkuriEntity squatter;
        private Path path;

        private YardClaimGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }

        private boolean watchingYard() {
            return yardBowl != null && attitude() == Attitude.CARING && streetActive() && !walking();
        }

        private boolean inOurYard(YukkuriEntity y) {
            if (!y.isAlive() || y.isPassenger() || y.isLeashed()) return false;
            var site = HomeClaim.occupiedNest(y);
            return site != null && TownMarkers.yardContains((ServerLevel) level(), yardBowl, site);
        }

        private boolean reaches(YukkuriEntity y) {
            return distanceToSqr(y) <= CONTACT_SQ && hasLineOfSight(y) && y.hasLineOfSight(HumanNpc.this);
        }

        @Override public boolean canUse() {
            if (tickCount % 21 != 0 || !watchingYard()) return false;
            squatter = level().getEntitiesOfClass(YukkuriEntity.class, getBoundingBox().inflate(SIGHT),
                    y -> distanceToSqr(y) <= SIGHT * SIGHT && hasLineOfSight(y) && inOurYard(y))
                    .stream().min(Comparator.comparingDouble(HumanNpc.this::distanceToSqr)).orElse(null);
            if (squatter == null) return false;
            if (reaches(squatter)) { path = null; return true; }
            path = getNavigation().createPath(squatter, 0);
            return path != null && path.canReach();
        }

        @Override public void start() {
            if (path != null) getNavigation().moveTo(path, .85);
            GameplayLog.event(HumanNpc.this, "npc-care", "action=yard_scold_approach target={} distance={}",
                    squatter.getUUID(), distanceTo(squatter));
        }

        @Override public boolean canContinueToUse() {
            return watchingYard() && inOurYard(squatter) && (reaches(squatter) || !getNavigation().isDone());
        }

        @Override public boolean requiresUpdateEveryTick() { return true; }

        @Override public void tick() {
            if (reaches(squatter) && HomeClaim.driveOff(squatter, HumanNpc.this)) {
                getNavigation().stop();
                street.scoldSquatter(squatter);
            }
        }

        @Override public void stop() { getNavigation().stop(); }
    }

    private final class YardBowlGoal extends Goal {
        private Path path;
        private YardBowlGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }

        private CareBlockEntity dueBowl() {
            return yardBowl != null && careFood > 0 && attitude() == Attitude.CARING && streetActive()
                    && getOffhandItem().isEmpty() && level().hasChunkAt(yardBowl)
                    && level().getBlockEntity(yardBowl) instanceof CareBlockEntity bowl
                    && bowl.kind() == CareBlock.Kind.BOWL && bowl.foodCount() < 2
                    && bowl.hasAnyMatching(ItemStack::isEmpty) ? bowl : null;
        }

        private boolean reachesBowl() {
            return ShopStaff.reaches(HumanNpc.this, yardBowl.getCenter(), yardBowl);
        }

        @Override public boolean canUse() {
            // Goal selection runs on alternating ticks, so this interval must be odd.
            if (tickCount % 101 != 0 || dueBowl() == null || animals(Custody.Role.COMPANION).isEmpty()) return false;
            path = null;
            if (reachesBowl()) return true;
            var stands = new HashSet<BlockPos>();
            // A doorway is a valid path target, but the owner stopping there keeps the closed door between it and the bowl.
            for (var direction : Direction.Plane.HORIZONTAL) {
                var stand = yardBowl.relative(direction);
                if (level().getBlockState(stand).getCollisionShape(level(), stand).isEmpty()) stands.add(stand);
            }
            path = getNavigation().createPath(stands, 0);
            return path != null && path.canReach();
        }

        @Override public void start() { if (path != null) getNavigation().moveTo(path, .85); }
        @Override public boolean canContinueToUse() { return dueBowl() != null && (reachesBowl() || !getNavigation().isDone()); }
        @Override public void tick() {
            var bowl = dueBowl();
            if (bowl == null || !reachesBowl()) return;
            var point = yardBowl.getCenter();
            getLookControl().setLookAt(point.x, point.y, point.z);
            if (fillRegularGrain(bowl)) swing(InteractionHand.OFF_HAND);
        }
        @Override public void stop() { getNavigation().stop(); }
    }

    private final class RoutineGoal extends Goal {
        /** Ticks in reach of the patient (the care cap) and ticks spent walking up to it (its own, longer cap). */
        private int elapsed, approach;
        private int careTicks;
        private TreatmentItem.Kind dose;
        private boolean goingHome() { return (resting() || avoidingStreet()) && !stroll().outing(); }
        RoutineGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() {
            if (job() == Job.FACTORY) return false;
            if (street.helping() || street.shooingCrop()) return true;
            if (isTrading() || goingHome()) return true;
            // Shop staff tend their stock as jobs (ShopStaff) through the staff doors; this routine only keeps their trading and rest.
            if (routineDelay > 0 || job() == Job.SHOPKEEPER) return false;
            if (streetResident()) {
                attention = street.pick();
                return attention != null && !(stroll().canUse() && !stroll().readyPets().isEmpty());
            }
            if (attitude() != Attitude.CARING) return false;
            attention = level().getEntitiesOfClass(YukkuriEntity.class, getBoundingBox().inflate(12), y -> y.isAlive() && mayTend(y))
                    .stream().min(Comparator.comparingDouble(HumanNpc.this::distanceToSqr)).orElse(null);
            return attention != null;
        }
        @Override public boolean canContinueToUse() {
            return isTrading() || goingHome() || street.helping() || street.shooingCrop() || attention != null && attention.isAlive() && elapsed < 140
                    && approach < (streetResident() ? HumanStreet.ROUTE_TICKS : 600);
        }
        @Override public void start() {
            elapsed = 0; approach = 0; careTicks = 0; dose = null;
            if (!street.helping() && attention != null && streetResident()) street.begin(attention);
        }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() {
            if (ExamSession.steer(HumanNpc.this)) return;
            if (isTrading()) { street.stopHelp(); street.stopCropShoo(); getNavigation().stop(); return; }
            if (street.shooingCrop()) {
                clearAttention();
                street.cropShooStep();
                return;
            }
            if (resting() && street.helping()) {
                street.stopHelp();
                getNavigation().stop();
            }
            if (street.helping()) {
                clearAttention();
                street.helpStep();
                return;
            }
            if (goingHome()) {
                clearAttention();
                var home = Vec3.atBottomCenterOf(home());
                if (finishingDoorway() || distanceToSqr(home) <= 2) {
                    if (clearBeforeStopping(home)) getNavigation().stop();
                } else if (tickCount % 40 == 1) getNavigation().moveTo(home.x, home.y, home.z, 0, .8);
                return;
            }
            if (attention == null) return;
            if (streetResident() && elapsed >= 140) return;
            if (streetResident() && street.reacting()) {
                if (!street.step(attention)) elapsed = 140;
                return;
            }
            if (streetResident() && !street.canCare(attention)) { elapsed = 140; return; }
            getLookControl().setLookAt(attention, 30, 30);
            if (distanceToSqr(attention) > 4 || !hasLineOfSight(attention)) {
                careTicks = 0;
                if (approach++ % 20 == 0 && !getNavigation().moveTo(attention, .85) && streetResident()) elapsed = 140;
            } else {
                elapsed++;
                getNavigation().stop();
                if (careTicks < 0) { careTicks++; return; }
                if (dose == null && !prepareDose(attention)) { attend(attention); elapsed = 140; return; }
                if (++careTicks == DOSE_SWING_TICKS) swing(InteractionHand.MAIN_HAND);
                if (careTicks < DOSE_TICKS) return;
                if (!level().isDay() || attitude()!=Attitude.CARING || !mayTend(attention)) {
                    GameplayLog.event(HumanNpc.this,"npc-care","action=treatment_refused reason=dose_or_care_context_changed target={}",attention.getUUID());
                    elapsed=140; return;
                }
                if (!applyDose(attention, dose)) { elapsed=140; return; }
                if (streetResident()) elapsed = 140;
                dose=null; careTicks=-20;
            }
        }
        private boolean prepareDose(YukkuriEntity patient) {
            if(attitude()!=Attitude.CARING || !mayTend(patient) || !level().isDay()
                    || !patient.body().treatmentBlockReason().equals("none")) return false;
            boolean physical=patient.body().footBurned() || patient.body().wounds().stream().anyMatch(w -> switch(w.kind()) {
                case TEAR,DENT,SCRATCH,SCORCH -> true;
                default -> false;
            });
            var desired=physical ? TreatmentItem.Kind.ORANGE_JUICE
                    : patient.status().marks().scarCount()>0 ? TreatmentItem.Kind.FLOUR_PASTE
                    : patient.getHealth()<patient.getMaxHealth() && !(patient.life().canEat() && patient.life().nutrition()<10 && careFood>0)
                            ? TreatmentItem.Kind.ORANGE_JUICE : null;
            if(desired==null || !reserveDose(patient, desired))return false;
            dose=desired;
            return true;
        }
        private void clearAttention() {
            if (dose != null) returnUnusedDose();
            street.cancel();
            dose=null; careTicks=0; attention = null;
        }
        @Override public void stop() {
            street.stopHelp();
            street.stopCropShoo();
            clearAttention();
            getNavigation().stop();
            if (routineDelay >= 0) routineDelay = Math.max(routineDelay, 200);
        }
    }

    public boolean hasMedicine() { return medicine > 0; }

    public boolean reserveDose(YukkuriEntity patient, TreatmentItem.Kind dose) {
        var item = TreatmentItem.doseItem(dose);
        if (!getMainHandItem().isEmpty()) return getMainHandItem().is(item);
        if (!hasMedicine()) return false;
        medicine--;
        setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(item));
        setDropChance(EquipmentSlot.MAINHAND, 0);
        GameplayLog.event(this, "npc-care", "action=prepare_dose target={} treatment={} medicine={} reserved=1",
                patient.getUUID(), dose, medicine);
        return true;
    }

    public boolean applyDose(YukkuriEntity patient, TreatmentItem.Kind dose) {
        boolean applied = getMainHandItem().is(TreatmentItem.doseItem(dose)) && TreatmentItem.apply(patient, dose);
        if (applied) getMainHandItem().shrink(1);
        GameplayLog.event(this, "npc-care", "action=treatment target={} treatment={} medicine={} reserved=1 result={}",
                patient.getUUID(), dose, medicine, applied ? "applied" : "refused");
        return applied;
    }

    /** A dose survives saving as equipment. Return it to the same supplies when its job ends or is gone. */
    public void returnUnusedDose() {
        var hand = getMainHandItem();
        if (!isAlive() || !(hand.getItem() instanceof TreatmentItem)) return;
        medicine += hand.getCount();
        setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        GameplayLog.event(this, "npc-care", "action=return_unused_dose medicine={}", medicine);
    }

    @Override public void addAdditionalSaveData(CompoundTag t) {
        super.addAdditionalSaveData(t); t.putInt("HumanJob", job().ordinal()); t.putInt("HumanAttitude", attitude().ordinal()); t.putString("SiteRole", siteRole);
        t.putBoolean("HumanInitialized", initialized); if (home != null) t.putLong("HumanHome", home.asLong());
        if (yardBowl != null) t.putLong("YardBowl", yardBowl.asLong());
        if (yardCrate != null) t.putLong("YardCrate", yardCrate.asLong());
        t.putInt("CareFood", careFood); t.putInt("Medicine", medicine); t.putInt("Trades", trades); t.putLong("NextRestock", nextRestock);
        if (plantSite != null) t.putUUID("PlantSite", plantSite);
        legacyOutput.save(t, registryAccess());
        walk().save(t);
        stroll().save(t);
        street.save(t);
        if (avoidingStreet()) t.putInt("StreetHomePause", routineDelay);
    }
    @Override public void readAdditionalSaveData(CompoundTag t) {
        super.readAdditionalSaveData(t); setJob(Job.values()[Math.clamp(t.getInt("HumanJob"), 0, Job.values().length - 1)]);
        if (t.contains("HumanAttitude")) setAttitude(Attitude.values()[Math.clamp(t.getInt("HumanAttitude"), 0, 2)]);
        siteRole = t.getString("SiteRole"); initialized = t.getBoolean("HumanInitialized");
        home = t.contains("HumanHome") ? BlockPos.of(t.getLong("HumanHome")) : null;
        yardBowl = t.contains("YardBowl") ? BlockPos.of(t.getLong("YardBowl")) : null;
        yardCrate = t.contains("YardCrate") ? BlockPos.of(t.getLong("YardCrate")) : null;
        plantSite = t.hasUUID("PlantSite") ? t.getUUID("PlantSite") : null;
        legacyOutput.load(t, registryAccess());
        walk().load(t);
        stroll().load(t);
        street.load(t);
        careFood = Math.clamp(t.getInt("CareFood"), 0, 256); medicine = t.contains("Medicine") ? Math.clamp(t.getInt("Medicine"), 0, 64) : 8;
        trades = Math.clamp(t.getInt("Trades"), 0, 64); nextRestock = t.getLong("NextRestock");
        routineDelay = Math.clamp(t.getInt("StreetHomePause"), -HumanStreet.DAY, 0);
    }
}
