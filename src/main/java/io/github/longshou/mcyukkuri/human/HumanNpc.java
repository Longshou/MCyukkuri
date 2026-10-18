package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.*;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.pet.PetScenes;
import io.github.longshou.mcyukkuri.pet.exam.ExamMenu;
import io.github.longshou.mcyukkuri.pet.exam.ExamSession;
import io.github.longshou.mcyukkuri.shop.CommodityLife;
import io.github.longshou.mcyukkuri.shop.KeeperMenu;
import io.github.longshou.mcyukkuri.shop.ShopCourier;
import io.github.longshou.mcyukkuri.shop.ShopDeliveries;
import io.github.longshou.mcyukkuri.shop.ShopKeeping;
import io.github.longshou.mcyukkuri.shop.ShopMenu;
import io.github.longshou.mcyukkuri.shop.ShopRegistry;
import java.util.Objects;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.ServerLevelAccessor;

public final class HumanNpc extends PathfinderMob {
    public enum Look { ONIISAN, BROTHER, SISTER }
    public enum Job { RESIDENT, SHOPKEEPER, FACTORY, EXAMINER, KEEPER }
    public enum Attitude { CRUEL, CARING, NEUTRAL }
    private static final EntityDataAccessor<Integer> JOB = SynchedEntityData.defineId(HumanNpc.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ATTITUDE = SynchedEntityData.defineId(HumanNpc.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> RESTING = SynchedEntityData.defineId(HumanNpc.class, EntityDataSerializers.BOOLEAN);
    private final Look look;
    private BlockPos home, lostAt;
    private String siteRole = "";
    private UUID lostAnimal, questPlayer, customer;
    private boolean questComplete, initialized;
    private long nextRestock, nextBreed;
    /** Grain the shop staff can hand out per game day (the stock is fed from here and from the pen bowls they fill). */
    private static final int SHOP_DAILY_GRAIN = 96;
    private int trades, delivered, careFood, medicine = 8, routineDelay;
    private YukkuriEntity attention;
    private final FactoryProduction production = new FactoryProduction(this);
    public FactoryProduction production() { return production; }
    /** Created on first use: registerGoals() runs inside the Mob constructor, before field initialisers. */
    private HumanWalk walk;
    private HumanWalk walk() { if (walk == null) walk = new HumanWalk(this); return walk; }
    private boolean shopRegistered;
    /** UUID of the resident's own pet (the one it walks on the lead), or null. */
    public UUID walkPetId() { return walk().petId(); }
    /** Whether the resident is out on a walk with its pet on the lead right now. */
    public boolean walking() { return walk().walking(); }
    /** Gives the resident this individual as its companion (custody, harness); the resident walks it from the next morning, or at once via {@link #beginWalk()}. */
    public void adoptPet(YukkuriEntity pet) { walk().adopt(pet); }
    /** Starts the outing now (leash on, strolling near home); false when the pet is busy, away or cannot wear the lead. */
    public boolean beginWalk() { return walk().begin(); }
    public long walksDone() { return walk().walks(); }

    public HumanNpc(EntityType<? extends HumanNpc> type, Level level, Look look) {
        super(type, level); this.look = look;
        setAttitude(look == Look.ONIISAN ? Attitude.CRUEL : Attitude.CARING);
        setPersistenceRequired(); ((GroundPathNavigation)getNavigation()).setCanOpenDoors(true);
    }
    public static AttributeSupplier.Builder attributes() { return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 24)
            .add(Attributes.MOVEMENT_SPEED, .26).add(Attributes.FOLLOW_RANGE, 24); }
    @Override protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b); b.define(JOB, 0); b.define(ATTITUDE, 1); b.define(RESTING, false);
    }
    public Look look() { return look; }
    public Job job() { return Job.values()[entityData.get(JOB)]; }
    public Attitude attitude() { return Attitude.values()[entityData.get(ATTITUDE)]; }
    public void setJob(Job job) { entityData.set(JOB, job.ordinal()); }
    public void setAttitude(Attitude attitude) { entityData.set(ATTITUDE, attitude.ordinal()); }
    public String siteRole() { return siteRole; }
    /** Game time of the keeper's next stud pairing; 0 until first scheduled. */
    public long nextBreed() { return nextBreed; }
    public void setNextBreed(long time) { nextBreed = time; }
    public BlockPos home() { return home == null ? blockPosition() : home; }
    public boolean resting() { return entityData.get(RESTING); }
    public int careFood() { return careFood; }
    public int remainingTrades() { return Math.max(0, HumanWorld.TRADE_DAY_LIMIT.get() - trades); }
    public boolean canReceiveInput() { return job() == Job.FACTORY && delivered < 4 && animals(Custody.Role.INPUT).size() < 2
            && (production.kind().equals("labor")||production.kind().isEmpty()||production.breeding().population()<8); }
    public boolean canReceiveParent(){return job()==Job.FACTORY&&(production.kind().equals("bag")||production.kind().equals("manju"))&&delivered<4&&
            animals(Custody.Role.RESERVE_PARENT).size()<4&&production.breeding().population()<8;}
    public UUID lostAnimal() { return lostAnimal; }
    public UUID questPlayer() { return questPlayer; }
    public boolean questComplete() { return questComplete; }
    public void setLostAnimal(UUID id, BlockPos pos) { if (lostAnimal == null) { lostAnimal = id; lostAt = pos.immutable(); } }
    public void completeQuest() { questComplete = true; }
    public void usedTrade() { trades++; }
    public void receivedInput() { delivered++; }
    public boolean donateFood() { if (careFood > 248) return false; careFood += 8; return true; }
    public boolean acceptQuest(Player player) {
        if (attitude() != Attitude.CARING || lostAnimal == null || questComplete || questPlayer != null && !questPlayer.equals(player.getUUID())) return false;
        questPlayer = player.getUUID();
        player.sendSystemMessage(Component.translatable("human.mcyukkuri.quest_location", lostAt.getX(), lostAt.getY(), lostAt.getZ(), lostAnimal.toString().substring(0, 8)));
        return true;
    }
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
        goalSelector.addGoal(1, new PanicGoal(this, 1.25));
        goalSelector.addGoal(1, new CourierGoal());
        goalSelector.addGoal(2, new RoutineGoal());
        goalSelector.addGoal(3, walk().goal());
        goalSelector.addGoal(3, new OpenDoorGoal(this, true));
        goalSelector.addGoal(4, new MoveTowardsRestrictionGoal(this, .85));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, .65) {
            @Override public boolean canUse() { return !resting() && !isTrading() && super.canUse(); }
        });
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 6));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }
    @Override protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!level().isClientSide) {
            if (player.isShiftKeyDown() && CommodityLife.sellCore(player, this)) return InteractionResult.sidedSuccess(false);
            if (job() == Job.SHOPKEEPER) ShopMenu.open(player, this);
            else if (job() == Job.KEEPER) KeeperMenu.open(player, this);
            else if (job() == Job.EXAMINER) ExamMenu.open(player, this);
            else if (job() == Job.RESIDENT) HumanFolk.interact(player, this);
            else HumanMenu.open(player, this);
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }
    @Override public void die(DamageSource source) {
        if (!level().isClientSide) {
            Custody.ownerDied(this);
            production.dropContents();
            if (level() instanceof ServerLevel sl && job() == Job.SHOPKEEPER) ShopRegistry.get(sl).close(getUUID());
        }
        super.die(source);
    }
    @Override public void aiStep() {
        super.aiStep();
        if (level().isClientSide || !isAlive() || isNoAi()) return;
        if (!initialized) { initialized = true; home = blockPosition(); careFood = HumanWorld.CARE_SUPPLIES.get(); nextRestock = level().getGameTime() + 24000; }
        restrictTo(home(), 18);
        if (level().getGameTime() >= nextRestock) {
            trades = delivered = 0; nextRestock = level().getGameTime() + 24000;
            if (job() == Job.RESIDENT && careFood < HumanWorld.CARE_SUPPLIES.get()) careFood = HumanWorld.CARE_SUPPLIES.get(); // a resident buys more grain each day
            else if ((job() == Job.SHOPKEEPER || job() == Job.KEEPER) && careFood < SHOP_DAILY_GRAIN) careFood = SHOP_DAILY_GRAIN; // the shop's own feed budget: shelf stock must not starve
        }
        if (job() == Job.SHOPKEEPER && !shopRegistered && tickCount % 20 == 1 && level() instanceof ServerLevel sl) {
            ShopRegistry.get(sl).register(this); shopRegistered = true;
        }
        if (job() == Job.RESIDENT && walk().petId() != null) walk().tick();
        entityData.set(RESTING, !level().isDay());
        if (routineDelay > 0) routineDelay--;
        if (tickCount % 100 == 0 && !resting() && !isTrading()) serviceFacilities();
        if (job() == Job.FACTORY) { production.tick(); if (tickCount % 100 == 9) ShopDeliveries.registerFactory(this); }
        if (job() == Job.SHOPKEEPER && tickCount % 200 == 11 && !resting()) CommodityLife.tickStock(this);
        if ((job() == Job.EXAMINER || job() == Job.SHOPKEEPER) && tickCount % 40 == 5 && !resting()) revokeVoidBadges();
        if (job() == Job.SHOPKEEPER && tickCount % 100 == 7) ShopDeliveries.register(this);
        if (job() == Job.SHOPKEEPER && tickCount % 20 == 3 && !resting()) ShopDeliveries.arrive(this, (ServerLevel) level(), level().getGameTime());
        if (job() == Job.KEEPER && tickCount % 20 == 0) ShopKeeping.tick(this);
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
    /** Staff refill nearby bowls from finite saved supplies, through their normal service aisle. */
    private void serviceFacilities() {
        if (job() == Job.RESIDENT || careFood <= 0) return;
        for (BlockPos p : BlockPos.betweenClosed(blockPosition().offset(-8, -1, -8), blockPosition().offset(8, 1, 8))) {
            if (!level().hasChunkAt(p) || !(level().getBlockEntity(p) instanceof CareBlockEntity bowl)
                    || bowl.kind() != CareBlock.Kind.BOWL || bowl.foodCount() >= 4) continue;
            for (int slot = 0; slot < bowl.getContainerSize(); slot++) if (bowl.getItem(slot).isEmpty()) {
                int count = Math.min(4, careFood); bowl.setItem(slot, new ItemStack(ModItems.GRAIN_REGULAR.get(), count)); careFood -= count; return;
            }
        }
    }
    /** A free-roaming resident feeds and pets wild yukkuri and its own pet; other people's pets and shop stock are not its business (staff tend their site's animals). */
    private boolean mayTend(YukkuriEntity y) {
        if (!siteRole.isEmpty()) return true;
        if (y.getLeashHolder() == this) return false; // the pet on the lead is walked, not fussed over
        return (Custody.view(y).owner() == null) || HumanCommerce.ownedBy(y, getUUID());
    }
    /** Unsold shop stock is fed less with every ration cut (CommodityLife): it is only fed once it is properly hungry. */
    private static boolean rationAllows(YukkuriEntity y) {
        int cuts = CommodityLife.rationCuts(y);
        return cuts <= 0 || y.life().nutrition() < Math.max(12f, 50f - 8f * Math.min(cuts, 5));
    }
    public boolean canTorment(YukkuriEntity y) {
        return HumanWorld.CRUELTY.get() && attitude() == Attitude.CRUEL && y.isAlive() && !HumanCommerce.protectedAnimal(y)
                && !y.isPassenger() && !y.operations().controlling();
    }
    /** Contact is checked at execution, not only when the goal selects an animal. */
    public boolean attend(YukkuriEntity y) {
        if (!y.isAlive() || y.level() != level() || distanceToSqr(y) > 9 || !hasLineOfSight(y) || !level().isDay()) return false;
        if(job()==Job.FACTORY && production.pipeline().needsService(y))return production.process(y);
        boolean input = job() == Job.FACTORY && HumanCommerce.ownedBy(y, getUUID()) && (Custody.view(y).role() == Custody.Role.INPUT);
        if (input) {
            if (!production.kind().isEmpty()) return production.process(y);
            y.body().releaseFilling("factory"); y.hurt(damageSources().mobAttack(this), 2); swing(InteractionHand.MAIN_HAND); return true;
        }
        if (canTorment(y)) {
            boolean intervened = !level().getEntitiesOfClass(HumanNpc.class, y.getBoundingBox().inflate(8), n -> n != this
                    && n.attitude() == Attitude.CARING && n.isAlive() && n.hasLineOfSight(y) && n.hasLineOfSight(this)).isEmpty();
            if (intervened) { y.showReaction("angry", "", 60); routineDelay = 600; return false; }
            boolean hit=y.hurt(damageSources().mobAttack(this), 1); swing(InteractionHand.MAIN_HAND); return hit;
        }
        if (attitude() == Attitude.CRUEL || (Custody.view(y).role() == Custody.Role.INPUT) && !FactoryPipeline.stage(y).equals("observe")) return false;
        if (y.life().canEat() && careFood > 0 && rationAllows(y) && y.life().beginMeal(new ItemStack(ModItems.GRAIN_REGULAR.get()), getUUID(), true)) {
            careFood--; swing(InteractionHand.MAIN_HAND); return true;
        }
        if (medicine > 0 && (y.body().treat(false) || y.body().treat(true))) { medicine--; swing(InteractionHand.MAIN_HAND); return true; }
        if (!y.isPanicking()) {
            y.life().personality().talk(getUUID(), true);
            if (!y.life().hasUrgentNeed() && tickCount % 3 == 0) y.life().personality().lessonCompleted(getUUID());
            y.showReaction("happy", "", 60); swing(InteractionHand.MAIN_HAND); return true;
        }
        return false;
    }
    /** While a keeper runs a courier job (ShopCourier steers the walk), this holds the move and look flags so the routine, strolling and restriction goals stand aside. */
    private final class CourierGoal extends Goal {
        CourierGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return job() == Job.KEEPER && ShopCourier.active(HumanNpc.this); }
        @Override public boolean canContinueToUse() { return canUse(); }
    }
    private final class RoutineGoal extends Goal {
        private int elapsed;
        RoutineGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() {
            if (isTrading() || resting()) return true;
            if (routineDelay > 0) return false;
            attention = level().getEntitiesOfClass(YukkuriEntity.class, getBoundingBox().inflate(job()==Job.FACTORY?32:12), y -> y.isAlive()
                    && (job() == Job.FACTORY && (production.kind().isEmpty()&&HumanCommerce.ownedBy(y, getUUID())&&
                            (Custody.view(y).role() == Custody.Role.INPUT)||production.pipeline().needsService(y))
                    || canTorment(y) || attitude() == Attitude.CARING && mayTend(y) && (Custody.view(y).role() != Custody.Role.INPUT || FactoryPipeline.stage(y).equals("observe"))))
                    .stream().min(Comparator.comparingDouble(y -> distanceToSqr(y)
                            - (attitude()==Attitude.CARING && FactoryPipeline.stage(y).equals("observe") ? 1024 : 0))).orElse(null);
            return attention != null;
        }
        @Override public boolean canContinueToUse() { return isTrading() || resting() || attention != null && attention.isAlive() && elapsed < 140; }
        @Override public void start() { elapsed = 0; }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() {
            if (ExamSession.active(HumanNpc.this)) return; // the exam session steers the examiner
            if (isTrading()) { getNavigation().stop(); return; }
            if (resting()) {
                if (distanceToSqr(Vec3.atBottomCenterOf(home())) > 2) getNavigation().moveTo(home().getX() + .5, home().getY(), home().getZ() + .5, .8);
                else getNavigation().stop();
                return;
            }
            elapsed++; if (attention == null) return;
            getLookControl().setLookAt(attention, 30, 30);
            if (distanceToSqr(attention) > 4) { if (elapsed % 20 == 1) getNavigation().moveTo(attention, .85); }
            else { getNavigation().stop(); attend(attention); elapsed = 140; }
        }
        @Override public void stop() { attention = null; getNavigation().stop(); routineDelay = Math.max(routineDelay, job() == Job.FACTORY ? 80 : 200); }
    }
    @Override public void addAdditionalSaveData(CompoundTag t) {
        super.addAdditionalSaveData(t); t.putInt("HumanJob", job().ordinal()); t.putInt("HumanAttitude", attitude().ordinal()); t.putString("SiteRole", siteRole);
        t.putBoolean("HumanInitialized", initialized); if (home != null) t.putLong("HumanHome", home.asLong());
        t.putInt("CareFood", careFood); t.putInt("Medicine", medicine); t.putInt("Trades", trades); t.putInt("Delivered", delivered); t.putLong("NextRestock",
                nextRestock); t.putLong("NextBreed", nextBreed);
        t.put("FactoryProduction", production.save()); walk().save(t);
        if (lostAnimal != null) t.putUUID("LostAnimal", lostAnimal); if (lostAt != null) t.putLong("LostAt", lostAt.asLong());
        if (questPlayer != null) t.putUUID("QuestPlayer", questPlayer); t.putBoolean("QuestComplete", questComplete);
    }
    @Override public void readAdditionalSaveData(CompoundTag t) {
        super.readAdditionalSaveData(t); setJob(Job.values()[Math.clamp(t.getInt("HumanJob"), 0, Job.values().length - 1)]);
        if (t.contains("HumanAttitude")) setAttitude(Attitude.values()[Math.clamp(t.getInt("HumanAttitude"), 0, 2)]);
        siteRole = t.getString("SiteRole"); initialized = t.getBoolean("HumanInitialized"); home = t.contains("HumanHome") ? BlockPos.of(t.getLong("HumanHome")) : null;
        production.load(t.getCompound("FactoryProduction")); walk().load(t);
        careFood = Math.clamp(t.getInt("CareFood"), 0, 256); medicine = t.contains("Medicine") ? Math.clamp(t.getInt("Medicine"), 0, 64) : 8;
        trades = Math.clamp(t.getInt("Trades"), 0, 64); delivered = Math.clamp(t.getInt("Delivered"), 0, 4); nextRestock = t.getLong("NextRestock"); nextBreed = t.getLong("NextBreed");
        lostAnimal = t.hasUUID("LostAnimal") ? t.getUUID("LostAnimal") : null; lostAt = t.contains("LostAt") ? BlockPos.of(t.getLong("LostAt")) : blockPosition();
        questPlayer = t.hasUUID("QuestPlayer") ? t.getUUID("QuestPlayer") : null; questComplete = t.getBoolean("QuestComplete");
    }
}
