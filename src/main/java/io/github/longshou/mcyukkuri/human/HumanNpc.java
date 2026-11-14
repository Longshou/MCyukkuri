package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.random.ModRandom;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriGroundNavigation;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.social.BadgeSocial;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.item.TreatmentItem;
import io.github.longshou.mcyukkuri.status.GameplayLog;
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
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
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
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import java.util.Objects;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.ServerLevelAccessor;

public final class HumanNpc extends PathfinderMob {
    public enum Look { ONIISAN, BROTHER, SISTER }
    public enum Job { RESIDENT, SHOPKEEPER, FACTORY, EXAMINER, KEEPER }
    public static final int DOSE_SWING_TICKS = 20, DOSE_TICKS = 30;
    public enum Attitude { CRUEL, CARING, NEUTRAL }
    private static final EntityDataAccessor<Integer> JOB = SynchedEntityData.defineId(HumanNpc.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ATTITUDE = SynchedEntityData.defineId(HumanNpc.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> RESTING = SynchedEntityData.defineId(HumanNpc.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> STREET_GRAIN = SynchedEntityData.defineId(HumanNpc.class, EntityDataSerializers.BOOLEAN);
    private final Look look;
    private BlockPos home;
    private String siteRole = "";
    private UUID customer;
    private boolean initialized;
    private long nextRestock;
    /** Grain the shop staff can hand out per game day (the stock is fed from here and from the pen bowls they fill). */
    private static final int SHOP_DAILY_GRAIN = 96;
    private int trades, delivered, careFood, medicine = 8, routineDelay;
    private int nextBadgeGreeting;
    private YukkuriEntity attention;
    final HumanStreet street = new HumanStreet(this);
    private final FactoryProduction production = new FactoryProduction(this);
    public FactoryProduction production() { return production; }
    /** Created on first use: registerGoals() runs inside the Mob constructor, before field initialisers. */
    private HumanWalk walk;
    private HumanWalk walk() { if (walk == null) walk = new HumanWalk(this); return walk; }
    /** UUID of the resident's own pet (the one it walks on the lead), or null. */
    public UUID walkPetId() { return walk().petId(); }
    /** Whether the resident is out on a walk with its pet on the lead right now. */
    public boolean walking() { return walk().walking(); }
    /** Gives the resident this individual as its companion (custody, harness); the resident walks it from the next morning, or at once via {@link #beginWalk()}. */
    public void adoptPet(YukkuriEntity pet) { walk().adopt(pet); }
    /** Starts the outing now (leash on, strolling near home); false when the pet is busy, away or cannot wear the lead. */
    public boolean beginWalk() { return walk().begin(); }

    public HumanNpc(EntityType<? extends HumanNpc> type, Level level, Look look) {
        super(type, level); this.look = look;
        setAttitude(look == Look.ONIISAN ? Attitude.CRUEL : Attitude.CARING);
        setPersistenceRequired(); ((GroundPathNavigation)getNavigation()).setCanOpenDoors(true);
    }
    public static AttributeSupplier.Builder attributes() { return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 24)
            .add(Attributes.MOVEMENT_SPEED, .26).add(Attributes.FOLLOW_RANGE, 24); }
    @Override protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b); ModRandom.construct(this); b.define(JOB, 0); b.define(ATTITUDE, 1); b.define(RESTING, false); b.define(STREET_GRAIN, false);
    }
    public Look look() { return look; }
    public Job job() { return Job.values()[entityData.get(JOB)]; }
    public Attitude attitude() { return Attitude.values()[entityData.get(ATTITUDE)]; }
    public void setJob(Job job) { entityData.set(JOB, job.ordinal()); }
    public void setAttitude(Attitude attitude) { entityData.set(ATTITUDE, attitude.ordinal()); }
    public String siteRole() { return siteRole; }
    public BlockPos home() { return home == null ? blockPosition() : home; }
    public boolean resting() { return entityData.get(RESTING); }
    public boolean showsStreetGrain() { return entityData.get(STREET_GRAIN); }
    public boolean kicking() { return street.kickPose > 0; }
    public boolean frowning() { return street.frownPose > 0; }
    boolean streetResident() { return job() == Job.RESIDENT && siteRole.isEmpty(); }
    boolean avoidingStreet() { return routineDelay < 0; }
    boolean streetObservable() { return street.observable(); }
    boolean streetActive() {
        return streetResident() && initialized && isAlive() && !isNoAi() && !avoidingStreet()
                && level().isDay() && !isTrading();
    }
    public boolean streetThreatTo(YukkuriEntity animal) { return street.threatTo(animal); }
    public static void witnessedStreetHurt(YukkuriEntity animal, Player player) { HumanStreet.witnessedHurt(animal, player); }
    public static void witnessedCropBite(YukkuriEntity animal, BlockPos crop) { HumanStreet.witnessedCropBite(animal, crop); }
    long streetFrownAfter() { return street.frownAfter(); }
    void streetFrown(long until) { street.frown(until); }
    public int careFood() { return careFood; }
    /** Takes up to {@code max} portions of grain from the finite daily supply (shop staff filling a bowl); returns how many. */
    public int drawCareFood(int max) { int taken = Math.min(max, careFood); careFood -= taken; return taken; }
    public int remainingTrades() { return Math.max(0, HumanWorld.TRADE_DAY_LIMIT.get() - trades); }
    boolean closedFactory() { return !FactoryProduction.ENABLED && job() == Job.FACTORY; }
    public boolean canReceiveInput() { return FactoryProduction.ENABLED && job() == Job.FACTORY && delivered < 4 && animals(Custody.Role.INPUT).size() < 2
            && (production.kind().equals("labor")||production.kind().isEmpty()||production.breeding().population()<8); }
    public boolean canReceiveParent(){return FactoryProduction.ENABLED&&job()==Job.FACTORY&&(production.kind().equals("bag")||production.kind().equals("manju"))&&delivered<4&&
            animals(Custody.Role.RESERVE_PARENT).size()<4&&production.breeding().population()<8;}
    public void usedTrade() { trades++; }
    public void receivedInput() { delivered++; }
    public boolean donateFood() { if (careFood > 248) return false; careFood += 8; return true; }
    void takeStreetGrain() { careFood++; }
    public boolean beginTrade(Player player) {
        if (isTrading() && !player.getUUID().equals(customer)) return false;
        customer = player.getUUID(); getNavigation().stop(); return true;
    }
    public void endTrade(Player player) { if (player.getUUID().equals(customer)) customer = null; }
    public boolean isTrading() {
        if (ExamSession.active(this)) return true;
        if (customer == null) return false;
        Player player = level().getPlayerByUUID(customer);
        return player != null && (player.containerMenu instanceof HumanMenu menu && menu.target() == this
                || player.containerMenu instanceof ShopMenu shop && shop.target() == this
                || player.containerMenu instanceof KeeperMenu keeper && keeper.target() == this
                || player.containerMenu instanceof ExamMenu exam && exam.target() == this);
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
        if ((reason == MobSpawnType.NATURAL || reason == MobSpawnType.CHUNK_GENERATION) && look != Look.ONIISAN && job() == Job.RESIDENT && getRandom().nextInt(100) < 45)
            HumanScenes.givePet(this, level.getLevel());
        return result;
    }
    @Override protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new PanicGoal(this, 1.25) {
            @Override public boolean canUse() { return !avoidingStreet() && super.canUse(); }
            @Override public boolean canContinueToUse() { return !avoidingStreet() && super.canContinueToUse(); }
        });
        goalSelector.addGoal(1, new CourierGoal());
        goalSelector.addGoal(2, new RoutineGoal());
        goalSelector.addGoal(3, walk().goal());
        goalSelector.addGoal(3, new OpenDoorGoal(this, true));
        goalSelector.addGoal(3, new StaffDoorGoal());
        goalSelector.addGoal(4, new MoveTowardsRestrictionGoal(this, .85));
        goalSelector.addGoal(4, new VillageBin.Sweep(this));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, .65) {
            @Override public boolean canUse() { return !streetResident() && !resting() && !isTrading() && super.canUse(); }
        });
        goalSelector.addGoal(5, new HumanStreet.Stroll(this));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 6));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }
    @Override protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || closedFactory()) return InteractionResult.PASS;
        if (!level().isClientSide) {
            ShopRegistry.get((ServerLevel) level()).adoptLegacy((ServerLevel) level(), this);
            if (player.isShiftKeyDown() && CommodityLife.sellCore(player, this)) return InteractionResult.sidedSuccess(false);
            if (job() == Job.SHOPKEEPER) ShopMenu.open(player, this);
            else if (job() == Job.KEEPER) KeeperMenu.open(player, this); // the matchmaker with its studs, not shop staff
            else if (job() == Job.EXAMINER) ExamMenu.open(player, this);
            else if (job() == Job.RESIDENT) HumanFolk.interact(player, this);
            else HumanMenu.open(player, this);
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }
    /** Shop staff on a job may route through their own shop's iron staff doors (ShopStaff.opensDoor); every other door is vanilla. */
    @Override protected PathNavigation createNavigation(Level level) {
        return new HumanNavigation(level);
    }

    public Path shopPath(BlockPos stand) { return shopPath(Set.of(stand), null); }

    public Path shopPath(Set<BlockPos> stands, AABB blocker) {
        return ((HumanNavigation) getNavigation()).shopPath(stands, blocker);
    }

    private final class HumanNavigation extends GroundPathNavigation {
        private AABB shopBlocker;

        private HumanNavigation(Level level) { super(HumanNpc.this, level); }

        @Override public Path createPath(BlockPos target, int accuracy) {
            return ShopStaff.active(HumanNpc.this) && accuracy == 0
                    ? shopPath(Set.of(target), null) : super.createPath(target, accuracy);
        }

        private Path shopPath(Set<BlockPos> stands, AABB blocker) {
            // A route between opposite rooms can be longer than the ordinary NPC follow range.
            float range = TravelRange.PATH_RANGE;
            setMaxVisitedNodesMultiplier(range / (float) HumanNpc.this.getAttributeValue(Attributes.FOLLOW_RANGE));
            shopBlocker = blocker;
            try { return createPath(stands, 8, false, 0, range); }
            finally { shopBlocker = null; resetMaxVisitedNodesMultiplier(); }
        }

        @Override public boolean shouldRecomputePath(BlockPos at) {
            // Opening or closing an authorized leaf leaves its path traversable.
            if (!ShopStaff.active(HumanNpc.this)) return super.shouldRecomputePath(at);
            var state = level.getBlockState(at);
            return !(state.getBlock() instanceof DoorBlock door && !door.type().canOpenByHand()
                    && ShopStaff.opensDoor(HumanNpc.this, at)) && super.shouldRecomputePath(at);
        }

        @Override protected PathFinder createPathFinder(int maxVisitedNodes) {
            nodeEvaluator = new WalkNodeEvaluator() {
                @Override public PathType getPathTypeOfMob(PathfindingContext context, int x, int y, int z, Mob mob) {
                    // An overlapping body must not mark the worker's own departure node blocked.
                    if (shopBlocker != null && !mob.blockPosition().equals(new BlockPos(x, y, z)) && mob.getDimensions(mob.getPose())
                            .makeBoundingBox(x + .5, y, z + .5).intersects(shopBlocker)) return PathType.BLOCKED;
                    return super.getPathTypeOfMob(context, x, y, z, mob);
                }

                @Override public PathType getPathType(PathfindingContext context, int x, int y, int z) {
                    var pos = new BlockPos(x, y, z); var state = context.getBlockState(pos);
                    if (state.getBlock() instanceof DoorBlock door && !door.type().canOpenByHand() && !state.getValue(DoorBlock.OPEN)
                            && ShopStaff.opensDoor(HumanNpc.this, pos)) return PathType.DOOR_WOOD_CLOSED;
                    return super.getPathType(context, x, y, z);
                }
            };
            nodeEvaluator.setCanPassDoors(true);
            return new PathFinder(nodeEvaluator, maxVisitedNodes);
        }
    }

    @Override public void die(DamageSource source) {
        if (!level().isClientSide) {
            production.dropContents();
            // A reserved dose is a real item in the hand; it falls where the body fell instead of vanishing with the corpse.
            if (getMainHandItem().getItem() instanceof TreatmentItem) setDropChance(EquipmentSlot.MAINHAND, 2.0f);
            // The loose piece it was carrying to a bin falls where the body fell, likewise.
            VillageBin.dropLitter(this);
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
    @Override public boolean hurt(DamageSource source, float amount) {
        boolean hurt = super.hurt(source, amount);
        if (hurt && isAlive() && !level().isClientSide && streetResident() && source.getEntity() instanceof Player) {
            routineDelay = -HumanStreet.DAY;
            street.cancel();
            if (walking()) walk().end();
            getNavigation().stop();
            GameplayLog.event(this, "npc-care", "action=street_go_home cause=player_hit ticks={}", -routineDelay);
        }
        return hurt;
    }
    @Override public void handleEntityEvent(byte id) {
        if (id == HumanStreet.KICK_EVENT) street.kickPose = HumanStreet.KICK_POSE_TICKS;
        else if (id == HumanStreet.FROWN_EVENT) street.frownPose = 30;
        else super.handleEntityEvent(id);
    }
    @Override public void aiStep() {
        Vec3 before = position();
        super.aiStep();
        if (street.kickPose > 0) street.kickPose--;
        if (street.frownPose > 0) street.frownPose--;
        if (level().isClientSide || !isAlive() || isNoAi() || closedFactory()) return;
        if (streetResident() && !getNavigation().isDone())
            YukkuriGroundNavigation.noticePassage(this, before);
        if (tickCount == 1) returnUnusedDose();
        // The summon/structure loader applies its world position after reading NBT.
        if (home == null) home = blockPosition();
        if (!initialized) {
            initialized = true;
            if (getTags().contains("mcy_street_local")) street.assignVillageAttitude();
            careFood = streetResident() ? attitude() == Attitude.CARING ? Math.min(8, HumanWorld.CARE_SUPPLIES.get()) : 0
                    : HumanWorld.CARE_SUPPLIES.get();
            nextRestock = level().getGameTime() + HumanStreet.DAY;
        }
        if ((tickCount == 1 || tickCount % 100 == 1 && (siteRole.equals("shop_keeper") || siteRole.equals("shop_sister")))
                && level() instanceof ServerLevel server) {
            var shops = ShopRegistry.get(server);
            shops.adoptLegacy(server, this);
            if (job() == Job.SHOPKEEPER) shops.of(this);
        }
        restrictTo(home(), 18);
        if (level().getGameTime() >= nextRestock) {
            trades = delivered = 0; nextRestock = level().getGameTime() + HumanStreet.DAY;
            if ((job() == Job.SHOPKEEPER || job() == Job.KEEPER) && careFood < SHOP_DAILY_GRAIN) careFood = SHOP_DAILY_GRAIN;
        }
        if (job() == Job.RESIDENT && walk().petId() != null) walk().tick();
        entityData.set(RESTING, !level().isDay());
        if (routineDelay != 0) routineDelay -= Integer.signum(routineDelay);
        street.tick();
        street.pickUpGrain();
        entityData.set(STREET_GRAIN, streetResident() && attitude() == Attitude.CARING && careFood > 0);
        if (tickCount % 100 == 0 && !resting() && !isTrading()) serviceFacilities();
        if (tickCount % 40 == 17 && tickCount >= nextBadgeGreeting && !resting() && !isTrading() && !avoidingStreet()
                && !ExamSession.active(this)) greetBadge();
        if (job() == Job.FACTORY) production.tick();
        if ((job() == Job.EXAMINER || job() == Job.SHOPKEEPER) && tickCount % 40 == 5 && !resting()) revokeVoidBadges();
        if (job() == Job.SHOPKEEPER && tickCount % 20 == 3) ShopStaff.tick(this);
    }
    private void greetBadge() {
        var viewer = level().getNearestPlayer(this, 12);
        if (viewer == null || !(level() instanceof ServerLevel server)) return;
        var guest = level().getEntitiesOfClass(YukkuriEntity.class, getBoundingBox().inflate(4), y ->
                y.isAlive() && !y.life().isSleeping() && !ExamSession.running(y) && hasLineOfSight(y)
                        && BadgeSocial.visibleRank(y).id() >= BadgeGrade.SILVER.id()).stream()
                .min(java.util.Comparator.comparingDouble(this::distanceToSqr)).orElse(null);
        if (guest == null) return;
        nextBadgeGreeting = tickCount + 1200;
        getLookControl().setLookAt(guest);
        Staging.card(server, position().add(0, getBbHeight() + .45, 0),
                Component.translatable("human.mcyukkuri.greet_badge", guest.getDisplayName()), 60, 1.0f);
    }
    /** M02: a badge on a yukkuri whose registry entry went void (abandoned while worn) is taken off by staff who see it. */
    private void revokeVoidBadges() {
        for (var y : level().getEntitiesOfClass(YukkuriEntity.class, getBoundingBox().inflate(6), v -> v.isAlive() && v.pet().voidBadge() && hasLineOfSight(v))) {
            y.pet().revoke(); swing(InteractionHand.MAIN_HAND); PetScenes.revoked(y);
            var near = level().getNearestPlayer(this, 16);
            if (near != null) near.displayClientMessage(Component.translatable("pet.mcyukkuri.abandon.revoked", y.getDisplayName()), true);
            return;
        }
    }
    /** Staff refill nearby bowls from finite saved supplies (shop staff walk to each bowl as a job instead, ShopStaff). */
    private void serviceFacilities() {
        if (job() == Job.RESIDENT || job() == Job.SHOPKEEPER || careFood <= 0) return;
        for (BlockPos p : BlockPos.betweenClosed(blockPosition().offset(-8, -1, -8), blockPosition().offset(8, 1, 8))) {
            if (!level().hasChunkAt(p) || !(level().getBlockEntity(p) instanceof CareBlockEntity bowl)
                    || bowl.kind() != CareBlock.Kind.BOWL || bowl.foodCount() >= 4) continue;
            for (int slot = 0; slot < bowl.getContainerSize(); slot++) if (bowl.getItem(slot).isEmpty()) {
                int count = Math.min(4, careFood); bowl.setItem(slot, new ItemStack(ModItems.GRAIN_REGULAR.get(), count)); careFood -= count;
                GameplayLog.event(this, "npc-care", "action=refill_bowl target={} food={}->{} transferred={}", p.toShortString(), careFood + count, careFood, count);
                return;
            }
        }
    }
    /** A free-roaming resident feeds and pets wild yukkuri and its own pet; other people's pets and shop stock are not its business (staff tend their site's animals). */
    boolean mayTend(YukkuriEntity y) {
        if (!siteRole.isEmpty()) return true;
        if (y.getLeashHolder() == this) return false; // the pet on the lead is walked, not fussed over
        return (Custody.view(y).owner() == null) || HumanCommerce.ownedBy(y, getUUID());
    }
    /** Contact is checked at execution, not only when the goal selects an animal. */
    public boolean attend(YukkuriEntity y) {
        if (closedFactory() || !y.isAlive() || y.level() != level() || distanceToSqr(y) > 9 || !hasLineOfSight(y) || !level().isDay()) return false;
        if (streetResident()) return street.care(y);
        if(job()==Job.FACTORY && production.pipeline().needsService(y))return production.process(y);
        boolean input = job() == Job.FACTORY && HumanCommerce.ownedBy(y, getUUID()) && (Custody.view(y).role() == Custody.Role.INPUT);
        if (input) {
            if (!production.kind().isEmpty()) return production.process(y);
            return false;
        }
        if (attitude() == Attitude.CRUEL || job() == Job.FACTORY && Custody.view(y).role() == Custody.Role.INPUT
                && !FactoryPipeline.stage(y).equals("observe")) return false;
        if (y.life().canEat() && careFood > 0 && y.life().beginMeal(new ItemStack(ModItems.GRAIN_REGULAR.get()), getUUID(), true)) {
            careFood--; swing(InteractionHand.MAIN_HAND);
            GameplayLog.event(this, "npc-care", "action=begin_meal target={} food={}->{} nutrition={} result=accepted", y.getUUID(), careFood + 1, careFood, y.life().nutrition());
            return true;
        }
        // Treatment is staged by RoutineGoal, with a real reserved dose in the vanilla hand slot.
        if (!y.isPanicking()) {
            y.life().personality().talk(getUUID(), true);
            y.showReaction("happy", "", 60); swing(InteractionHand.MAIN_HAND); return true;
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
     * (wooden doors stay with the vanilla goal). Nobody else opens these doors, so the animals behind them stay in their pens.
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
    private final class RoutineGoal extends Goal {
        /** Ticks in reach of the patient (the care cap) and ticks spent walking up to it (its own, longer cap). */
        private int elapsed, approach;
        private int careTicks;
        private TreatmentItem.Kind dose;
        private boolean goingHome() { return resting() || avoidingStreet(); }
        RoutineGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() {
            if (closedFactory()) return false;
            if (isTrading() || goingHome()) return true;
            // Shop staff tend their stock as jobs (ShopStaff) through the staff doors; this routine only keeps their trading and rest.
            if (routineDelay > 0 || job() == Job.SHOPKEEPER) return false;
            if (streetResident()) {
                attention = street.pick();
                return attention != null;
            }
            attention = level().getEntitiesOfClass(YukkuriEntity.class, getBoundingBox().inflate(job()==Job.FACTORY?32:12), y -> y.isAlive()
                    && (job() == Job.FACTORY && (production.kind().isEmpty()&&HumanCommerce.ownedBy(y, getUUID())&&
                            (Custody.view(y).role() == Custody.Role.INPUT)||production.pipeline().needsService(y))
                    || attitude() == Attitude.CARING && mayTend(y)
                            && (job() != Job.FACTORY || Custody.view(y).role() != Custody.Role.INPUT || FactoryPipeline.stage(y).equals("observe"))))
                    .stream().min(Comparator.comparingDouble(y -> distanceToSqr(y)
                            - (job()==Job.FACTORY && attitude()==Attitude.CARING && FactoryPipeline.stage(y).equals("observe") ? 1024 : 0))).orElse(null);
            return attention != null;
        }
        @Override public boolean canContinueToUse() {
            return isTrading() || goingHome() || attention != null && attention.isAlive() && elapsed < 140
                    && approach < (streetResident() ? HumanStreet.ROUTE_TICKS : 600);
        }
        @Override public void start() {
            elapsed = 0; approach = 0; careTicks = 0; dose = null;
            if (attention != null && streetResident()) street.begin(attention);
        }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() {
            if (ExamSession.active(HumanNpc.this)) return; // the exam session steers the examiner
            if (isTrading()) { getNavigation().stop(); return; }
            if (goingHome()) {
                street.cancel();
                attention = null;
                if (distanceToSqr(Vec3.atBottomCenterOf(home())) <= 2) getNavigation().stop();
                else if (tickCount % 40 == 1) getNavigation().moveTo(home().getX() + .5, home().getY(), home().getZ() + .5, .8);
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
                    || !patient.body().treatmentBlockReason().equals("none")
                    || job()==Job.FACTORY && production.pipeline().needsService(patient))return false;
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
        @Override public void stop() {
            // Trading and resting also use this goal, so only its own care routine returns a dose.
            if (dose != null) returnUnusedDose();
            street.cancel();
            dose=null; careTicks=0; attention = null; getNavigation().stop();
            if (routineDelay >= 0) routineDelay = Math.max(routineDelay, job() == Job.FACTORY ? 80 : 200);
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
        t.putInt("CareFood", careFood); t.putInt("Medicine", medicine); t.putInt("Trades", trades); t.putInt("Delivered", delivered); t.putLong("NextRestock",
                nextRestock);
        t.put("FactoryProduction", production.save()); walk().save(t);
        street.save(t);
        if (avoidingStreet()) t.putInt("StreetHomePause", routineDelay);
    }
    @Override public void readAdditionalSaveData(CompoundTag t) {
        super.readAdditionalSaveData(t); setJob(Job.values()[Math.clamp(t.getInt("HumanJob"), 0, Job.values().length - 1)]);
        if (t.contains("HumanAttitude")) setAttitude(Attitude.values()[Math.clamp(t.getInt("HumanAttitude"), 0, 2)]);
        siteRole = t.getString("SiteRole"); initialized = t.getBoolean("HumanInitialized");
        home = t.contains("HumanHome") ? BlockPos.of(t.getLong("HumanHome")) : null;
        production.load(t.getCompound("FactoryProduction")); walk().load(t);
        street.load(t);
        careFood = Math.clamp(t.getInt("CareFood"), 0, 256); medicine = t.contains("Medicine") ? Math.clamp(t.getInt("Medicine"), 0, 64) : 8;
        trades = Math.clamp(t.getInt("Trades"), 0, 64); delivered = Math.clamp(t.getInt("Delivered"), 0, 4); nextRestock = t.getLong("NextRestock");
        routineDelay = Math.clamp(t.getInt("StreetHomePause"), -HumanStreet.DAY, 0);
    }
}
