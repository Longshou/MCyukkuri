package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.social.SocialDisposition;
import io.github.longshou.mcyukkuri.social.BondLevels;

import io.github.longshou.mcyukkuri.family.ParentCarry;

import io.github.longshou.mcyukkuri.settlement.GroupPits;
import io.github.longshou.mcyukkuri.settlement.FoundShelter;
import io.github.longshou.mcyukkuri.settlement.GarbageSearch;
import io.github.longshou.mcyukkuri.settlement.Grazing;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import io.github.longshou.mcyukkuri.family.FamilyNest;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.MouthOccupancy;
import io.github.longshou.mcyukkuri.motor.action.GlassPress;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Identity;
import io.github.longshou.mcyukkuri.status.LearnedFear;
import io.github.longshou.mcyukkuri.pet.PetSkill;
import io.github.longshou.mcyukkuri.pet.social.BadgeArcs;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriGroundNavigation;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.body.DetachedItems;
import io.github.longshou.mcyukkuri.body.BodyFood;
import io.github.longshou.mcyukkuri.event.WildCards;
import io.github.longshou.mcyukkuri.headwear.HatCustody;
import io.github.longshou.mcyukkuri.headwear.Recognition;
import io.github.longshou.mcyukkuri.interaction.ItemContact;
import io.github.longshou.mcyukkuri.play.PlayBlock;
import io.github.longshou.mcyukkuri.rare.RareForms;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/** Loaded-time physiology and interruptible, server-authoritative life activities. */
public final class YukkuriLife {
    private enum Task { NONE, FOOD, FOOD_GLASS, WINTER_FOOD, HOME, TOILET, GROUND_TOILET, SLEEP, DRINK, TEMPERATURE, FOUND_COVER, GARBAGE, CROP }
    private final YukkuriEntity entity;
    private final SurfaceApproach approach = new SurfaceApproach();
    private final TemperatureLife temperature;
    private final FoundShelter foundCover;
    private final GarbageSearch garbage;
    private YukkuriPersonality personality;
    private float nutrition = 60, energy = 80, hygiene = 100, bladder, wetness;
    /** 舌肥 (FG-03): how choosey this body is about ordinary and coarse food; innate from the UUID, then moved by meals. */
    private float pickiness;
    private long ageTicks;
    private int metabolismTicks, energyTicks, hygieneTicks;
    private int immersionTicks, dryTicks;
    private static final int DRY_RESET_TICKS = 40;
    /** Project design: the asleep stretch of a full life sleep before the wake clip (about 31 s); a sleep taken at the body's rest time goes on past it. */
    private static final int SLEEP_TICKS = 620;
    private final LifeMeal meal = new LifeMeal(this);
    private final LifeHome home = new LifeHome(this);
    private int homeWait, planWait, restWait = 200, sleepTicks, toiletTicks;
    /** Project design: the {@link #sleepTicks} at which the wake clip has run out and the body gets up; 0 while the sleep is still asleep. */
    private int wakeAt;
    /** What ended a sleep that got up before its own wake tick (hunger, a real need, a child waiting); null for the rested wake. */
    private String wakeCause;
    private Task task = Task.NONE;
    private ItemEntity targetFood;
    private Entity foodGlassTarget;
    private Vec3 foodGlassStand;
    /** Leave blocked food alone for a while, while still considering meals that can be reached. */
    private static final int FOOD_GLASS_COOLDOWN = 400;
    private long foodGlassAfter;
    private final MotorOwner foodGlass = new MotorOwner() {
        @Override public String ownerId() { return Behaviors.SEEK_FOOD; }
        @Override public boolean active() { return task == Task.FOOD_GLASS; }
    };
    private BlockPos targetBlock;
    private BlockPos avoidedField;
    private long avoidFieldUntil, cropSearchAfter;
    private int cropLook;
    private ItemContact.BowlStand bowlStand;
    /** Only rejected food destinations in reach of the present approach origin. */
    private final Map<BlockPos, Vec3> refusedFood = new HashMap<>();
    private Vec3 refusedFoodFrom;
    private BlockPos refusedBed;
    private int pursuitTicks, queueTicks, queueBudget, winterNeedCheckAt;
    private record RuleChoice(int until, boolean follows) {}
    private final EnumMap<PetSkill, RuleChoice> ruleChoices = new EnumMap<>(PetSkill.class);
    private boolean useBowlNicely;
    private final LifeAccessory accessory;
    private int socialWait;
    private float dryness;
    private int drinkTicks;
    private long streetDrinkAfter;
    private final LifeDoses doses = new LifeDoses(this);
    private int exerciseReliefTicks;
    private boolean toiletAccident;
    /** The current ground toilet trip goes to the community's waste pit (settlement/GroupPits), not to nearby ground. */
    private boolean toiletPit;
    /** The pit cell the portion is meant for while {@link #toiletPit}: the member stands on the bank beside it, never in it. */
    private BlockPos toiletPitCell;
    private int pitEdgeTicks;
    /** A pit trip may cross the whole site; a trip to nearby ground gives up much sooner. */
    private static final int PIT_TRIP_TICKS = 900, GROUND_TRIP_TICKS = 240;
    private final HotFloorEscape hotFloor;
    private int starvingTicks, fatigueTicks, fatigueLength;
    private boolean observedNeedSlowness;
    private boolean napReady, fullFatigueSleep;
    /** Project design: awake ticks that must pass after a short nap before the next roll; transient, not saved. */
    private int napCooldown;
    private static final int NAP_MIN_GAP = 300;
    /** Project design: baseline thirst, +1 dryness per minute awake (two for the young), i.e. the drink threshold of 40 after about 40 minutes; severe thirst only slows. */
    private static final int THIRST_INTERVAL = 1200;
    private static final float SEVERE_THIRST = 90;
    private int thirstTicks;

    public YukkuriLife(YukkuriEntity entity) {
        this.entity = entity;
        // Hat custody captures the owner during construction, so bind the entity first.
        accessory = new LifeAccessory(this);
        temperature = new TemperatureLife(entity);
        foundCover = new FoundShelter(entity);
        garbage = new GarbageSearch(entity);
        personality = new YukkuriPersonality(entity);
        pickiness = Taste.innate(entity.getUUID());
        hotFloor = new HotFloorEscape(entity);
    }
    public HotFloorEscape hotFloor() { return hotFloor; }
    SurfaceApproach approach() { return approach; }
    /** Project design: equal food units, smaller daily needs for smaller life stages. */
    public float nutritionDemand() { return switch(entity.family().stage()) { case BABY -> .06f; case JUVENILE -> entity.family().subadult() ? .5f : .18f; case ADULT -> 1f; }; }
    public int sleepDuration() { return 200 + 4 * personality.boldness(); }
    public String fatigueDetail() { return "ready="+napReady+" gap="+napCooldown+" ticks="+fatigueTicks+" duration="+fatigueLength+" full="+fullFatigueSleep+" starvation="+starvingTicks; }
    public boolean fatigueActive() { return fatigueTicks > 0; }
    public boolean canFatigueRest() {
        return entity.isAlive() && !entity.isNoAi() && !entity.isPassenger() && entity.onGround()
                && !entity.isPanicking() && !entity.isCommandPlaying() && !entity.operations().active()
                && !entity.family().awaitingLanding() && !entity.family().laboring() && !entity.family().birthDuty()
                && !entity.isOnFire() && !entity.isInWaterOrBubble() && !hotFloor.active() && !entity.body().dying()
                && !level().isRainingAt(entity.blockPosition()) && !temperature.unsafeSleep();
    }
    /** A nap is leisure: a body that really needs food stays awake to eat it; only a full sleep at energy 0 still starts. */
    public boolean wantsFatigueRest() {
        return !isSleeping() && !entity.family().stemFoodUrgent()
                && (energy <= 0 || napReady && energy <= 25 && nutrition >= 30) && canFatigueRest();
    }
    public void startFatigueRest() {
        interrupt();
        fullFatigueSleep = energy <= 0;
        fatigueLength = fullFatigueSleep ? sleepDuration() : 20 + entity.getRandom().nextInt(41);
        fatigueTicks = 1; napReady = false;
        SurfaceApproach.halt(entity);
        GameplayLog.event(entity,"sleep","begin source=fatigue full={} duration={} boldness={} energy={}",fullFatigueSleep,fatigueLength,personality.boldness(),energy);
    }
    public void tickFatigueRest() {
        // An ended rest stays ended: the goal may still be ticked once before its continue check, and counting on from zero there
        // would start a nap of its own that no start ever logged and that never ends.
        if (fatigueTicks == 0) return;
        SurfaceApproach.halt(entity);
        // A nap is leisure: real hunger ends it so the body can eat (a full sleep at energy 0 goes on to the end).
        if (!fullFatigueSleep && nutrition < 30) { stopFatigueRest("hunger"); return; }
        energy = Math.min(100, energy + (fullFatigueSleep ? 100f/fatigueLength : .05f));
        fatigueTicks++;
        if (!fullFatigueSleep) {
            if (fatigueTicks > fatigueLength) stopFatigueRest();
            return;
        }
        // A full sleep at energy 0 ends by the same decisions as the ordinary life sleep: a real need ends it at once, and at the
        // body's own sleep time it goes on until that time is over instead of ending at its full length.
        String need = fatigueTicks % 20 == 0 ? needWakeCause() : null;
        if (need != null) stopFatigueRest(need);
        else if (fatigueTicks > fatigueLength && !sleepTime()) stopFatigueRest("rested");
    }
    public void stopFatigueRest() { stopFatigueRest(null); }
    /** Ends the rest; {@code cause} names the real reason when one ended it, and the [sleep] end line then says night= too. */
    private void stopFatigueRest(String cause) {
        if(fatigueTicks == 0)return;
        accountEnvironmentalWake();
        if (cause == null) GameplayLog.event(entity,"sleep","end source=fatigue full={} ticks={} energy={}",fullFatigueSleep,fatigueTicks,energy);
        else GameplayLog.event(entity,"sleep","end source=fatigue full={} cause={} night={} ticks={} energy={}",
                fullFatigueSleep,cause,level().isNight(),fatigueTicks,energy);
        boolean rested = fullFatigueSleep && fatigueTicks > fatigueLength;
        if(!fullFatigueSleep) napCooldown = NAP_MIN_GAP;
        fatigueTicks = 0; napReady = false;
        if(rested) { restWait = restInterval(); entity.family().woke(); }
    }
    public void wakeForDanger(String reason) {
        if (!isSleeping()) return;
        float before=energy;
        energy=Math.max(50,energy);
        GameplayLog.event(entity,"sleep","woken by danger reason={} sleepiness={} -> {}",reason,100-before,100-energy);
        stopFatigueRest();
        interrupt();
        if(entity.house().sleeping())entity.house().stop();
    }

    public void wakeForService() {
        if (!isSleeping()) return;
        interrupt();
        if (entity.house().sleeping()) entity.house().stop();
    }
    private void accountEnvironmentalWake() {
        if(energy>=50 || !isSleeping())return;
        if(entity.isPanicking() || entity.isOnFire() || entity.isInWaterOrBubble() || hotFloor.active()
                || level().isRainingAt(entity.blockPosition()) || temperature.unsafeSleep()) {
            GameplayLog.event(entity,"sleep","woken during interruption sleepiness={} -> 50",100-energy);
            energy=50;
        }
    }
    YukkuriEntity entity() { return entity; }
    HomeProvider homeProvider() { return HomeProvider.forEntity(entity); }
    void emptyBladder() { bladder = 0; }
    LifeDoses doses() { return doses; }
    void addNutrition(float amount) {
        float before = nutrition; nutrition = Math.min(100, nutrition + amount);
        if (nutrition > before) entity.social().ate(nutrition);
        nutritionBoundary(before, "intake");
    }
    void addBladder(float amount) { bladder = Math.min(100, bladder + amount); }
    void increaseDryness(float amount) { dryness = Math.min(100, dryness + amount); }
    void relieveDryness(float amount) { dryness = Math.max(0, dryness - amount); }
    private void releaseWaste() {
        double volume=DetachedItems.wasteAmount(entity);
        bladder=Math.max(0,bladder-(float)(40*volume/DetachedItems.wasteVolume(entity)));
    }
    void leaked() { releaseWaste();hygiene=Math.max(0,hygiene-15); }
    /** No sleep bookkeeping outlives a sleep: the next one starts from the enter clip with no wake tick. */
    private void resetSleep() { sleepTicks = 0; wakeAt = 0; wakeCause = null; bedRead = null; }
    /** The graded bed it is sleeping in, read from its own values (NestComfort) each time the sleep checks it lies inside; null
     *  when it is not asleep in its own graded bed. Not saved: a reloaded sleep reads its bed again at the next check. */
    private NestComfort.Read bedRead;
    public NestComfort.Read bedRead() { return bedRead; }
    /** The last bed it looked over on lying down, and whether it refused it then (NestComfort.settle). Not saved. */
    private record BedLook(BlockPos bed, int grade, long tick, boolean refused) {}
    private BedLook bedLook;
    /** A look this recent is not repeated when it lies down again (woken and back, a nap after the night). */
    private static final int BED_LOOK_MEMORY = 6000;
    /** The sleep ends at this very tick because something real ended it; [sleep] end names it. */
    private void wakeNow(String cause) { wakeAt = sleepTicks; wakeCause = cause; }
    void beginConsumption() { resetSleep();task=Task.NONE; }
    void beginDrugSleep() { task=Task.SLEEP;resetSleep(); }
    boolean beginDoseMeal(ItemStack one, UUID player, boolean returnBottle) {
        return meal.beginDose(one, player, returnBottle);
    }
    void deferHomeMaintenance() { homeWait = 200; }
    public void seekHomeNow() { homeWait = 0; }
    public boolean mayReturnToBed(BlockPos bed) { return !bed.equals(refusedBed); }

    public boolean walkToBed(BlockPos bed, Vec3 position, double speed) {
        SurfaceApproach.walk(entity, position, speed);
        if (entity.childhood().shouldStayWithParents()
                && SurfaceApproach.lastResult(entity, position) == SurfaceApproach.Result.BLOCKED
                && SurfaceApproach.why(entity, position).startsWith("no_complete_surface_route")) {
            refusedBed = bed;
            return false;
        }
        return true;
    }
    void finishConsumption() { task = Task.NONE; planWait = 20; }
    public TemperatureLife temperature() { return temperature; }
    public YukkuriPersonality personality() { return personality; }
    public int pickiness() { return Math.round(pickiness); }
    public void setPickiness(int value) {
        int before = pickiness();
        pickiness = Math.clamp(value, 0, 100);
        GameplayLog.limited(entity, "meal", "taste.set", 200, "taste set pickiness={} -> {}", before, pickiness());
    }
    public void adjustPickiness(int delta) { setPickiness(pickiness() + delta); }
    public float nutrition() { return nutrition; }
    public boolean spendNutrition(float amount) {
        if(!Float.isFinite(amount)||amount<0||nutrition<amount)return false;
        float before = nutrition; nutrition-=amount; nutritionBoundary(before, "spent"); return true;
    }
    private static int nutritionBand(float value) { return value <= 0 ? 0 : value < 10 ? 1 : value < 30 ? 2 : 3; }
    private void nutritionBoundary(float before, String cause) {
        if (nutritionBand(before) == nutritionBand(nutrition)) return;
        GameplayLog.limited(entity, "needs", "nutrition." + nutritionBand(nutrition), 200,
                "nutrition threshold before={} after={} cause={} lifeTask={} mealTicks={} energy={}",
                before, nutrition, cause, task, mealTicks(), energy);
    }
    public float energy() { return energy; }
    public boolean spendEnergy(float amount) {
        if(!Float.isFinite(amount)||amount<0||energy<amount)return false;energy-=amount;return true;
    }
    public void applyEnvironment(int extraWetness,int hygieneLoss,int energyCost) {
        wetness=Math.clamp(wetness+extraWetness,0,100);hygiene=Math.max(0,hygiene-hygieneLoss);energy=Math.max(0,energy-energyCost);
    }
    /** Controlled write used by the status registry (scenarios, commands); everything else reacts to the new value through the ordinary logic. */
    public boolean setNeed(String need, float value) {
        if (!Float.isFinite(value)) return false;
        float v = Math.clamp(value, 0, 100);
        switch (need) {
            case "nutrition" -> nutrition = v;
            case "energy" -> energy = v;
            case "hygiene" -> hygiene = v;
            case "bladder" -> bladder = v;
            case "wetness" -> wetness = v;
            case "dryness" -> dryness = v;
            default -> { return false; }
        }
        return true;
    }
    public float hygiene() { return hygiene; }
    public float bladder() { return bladder; }
    /** A newborn's first stool is already stored at birth; only the assisted contact below may retire it. */
    public void primeFirstStool() { bladder = Math.max(bladder, 12); }
    /** Only the completed newborn-care contact may retire this first bowel load. */
    public boolean completeAssistedFirstToilet(YukkuriEntity parent){
        if (!(entity.level() instanceof ServerLevel) || !entity.childhood().assistedReleaseReady(parent)
                || bladder < 10 || !entity.isAlive() || entity.isPanicking() || entity.isOnFire()
                || entity.isInWaterOrBubble() || isSleeping() || mealTicks() > 0) return false;
        var rear = ToiletContact.anus(entity, 0);
        if (rear == null) return false;
        if (!ToiletContact.drop(entity, rear)) return false;
        releaseWaste(); personality().relieveFear();
        entity.showReaction("happy", "toilet_done", 60);
        return true;
    }
    /** Leaf swaddle (family/LeafSwaddle): one load goes into the closed wrap, with the same bladder and hygiene cost as a leak. */
    public void releaseIntoWrap() { leaked(); }
    /** Leaf swaddle care: after the parent's verified rear contact the opened child drops one real load from its anus. */
    public boolean assistedRelease() {
        if (!(entity.level() instanceof ServerLevel) || bladder < 10 || constipated() || !entity.isAlive() || entity.isPanicking() || entity.isOnFire()
                || entity.isInWaterOrBubble() || isSleeping() || mealTicks() > 0 || entity.leafSwaddle().wrapped()) return false;
        if (!ToiletContact.drop(entity, ToiletContact.anus(entity, 0))) return false;
        releaseWaste(); personality.relieveFear();
        entity.showReaction("happy", "toilet_done", 60);
        return true;
    }
    /**
     * Afloat (aquatic/BoatToilet): the body strains over the water and the stool leaves its rear at {@code at} with {@code velocity}
     * (blocks per tick); the same load and relief as a ground toilet. The flying item, or null when nothing left the body.
     */
    public ItemEntity relieveOutboard(Vec3 at, Vec3 velocity) {
        if (!(entity.level() instanceof ServerLevel) || bladder < 10 || constipated() || !entity.isAlive() || isSleeping() || mealTicks() > 0) return null;
        var stool = ToiletContact.launch(entity, at, velocity);
        if (stool == null) return null;
        float before = bladder;
        releaseWaste(); personality.relieveFear();
        entity.showReaction("happy", "toilet_done", 60);
        GameplayLog.event(entity, "needs", "relieved source=boat at={} bladder={} -> {}", GameplayLog.at(at), before, bladder);
        return stool;
    }
    /** A real cleaning or soiling of the body surface, bounded like every other hygiene change. */
    public void changeHygiene(float delta) { if (Float.isFinite(delta)) hygiene = Math.clamp(hygiene + delta, 0, 100); }
    public float wetness() { return wetness; }
    public float dryness(){return dryness;}
    void addDryness(float amount) { if (Float.isFinite(amount) && amount > 0) dryness = Math.min(100, dryness + amount); }
    public boolean constipated(){return dryness>=70 && exerciseReliefTicks==0;}
    public int exerciseReliefTicks(){return exerciseReliefTicks;}
    /** Exercise helps the young empty their bowel; it does not hydrate or remove stored waste. */
    public void finishExercise(){
        if (!entity.childhood().canExercise()) return;
        spendEnergy(3);
        if(!entity.family().adult())exerciseReliefTicks=1200;
    }
    public int spicyTicks(){ return doses.spicyTicks(); }
    public int sprayDelay(){ return doses.sprayDelay(); }
    public int drugSleep(){ return doses.drugSleep(); }
    public void drink(float relief){ doses.drink(relief); }
    public boolean canTakeDose(ItemStack dose){ return doses.canTakeDose(dose); }
    public boolean beginDose(ItemStack one,UUID player,boolean returnBottle){ return doses.beginDose(one, player, returnBottle); }
    public boolean canSpray(){ return doses.canSpray(); }
    /** Waiting for or deep in the spray's drugged sleep: it cannot resist being handled. */
    public boolean sedated(){ return doses.sedated(); }
    /** A keeper's drop-off lets a sedated animal sleep only a little longer in its pen. */
    public void shortenDrugSleep(int ticks){ doses.shortenDrugSleep(ticks); }
    /** The single reading point of emotional load for involuntary release: PetLife.stress today (the unified unease of the mood system plugs in here). */
    public int distress(){ return doses.distress(); } // the shared unease (mind/MindLife), for every individual
    public static final int DISTRESS_LEAK=80;
    /**
     * Sudden fright or extreme distress makes the body let go by itself (komicolle 11518802), adults included.
     * The chance grows with the bladder; {@code force} skips the roll (status tool, scenes).
     */
    public boolean involuntaryRelease(String cause, boolean force){ return doses.involuntaryRelease(cause, force); }
    public boolean addicted(){ return doses.addicted(); }
    public int flourDoses(){ return doses.flourDoses(); }
    public int sinceFlour(){ return doses.sinceFlour(); }
    /** Wheat flour powder: filling, but a throat-drying habit; three doses close together and the body wants more (komicolle 11518847). */
    public boolean takeFlour(){ return doses.takeFlour(); }
    private void emotionTick(){ doses.emotionTick(); }
    public boolean spray(){ return doses.spray(); }
    public void poolRinse(){hygiene=Math.min(100,hygiene+15);wetness=Math.min(100,wetness+5);}
    /** A finished shake throws half the water off the body (project design); returns the wetness before. */
    public float shakeOff(){float before=wetness;wetness=wetness/2;return before;}
    public long ageTicks() { return ageTicks; }
    public GlobalPos home(){ return home.home(); }
    public boolean naturalHome(){ return home.naturalHome(); }
    public ItemStack mouth(){ return meal.mouth(); }
    /** A non-item mouthful taken from a living body or remains. */
    public BodyFood.Bite mouthBodyFood() { return meal.mouthBodyFood(); }
    public boolean hasMouthFood() { return meal.hasMouthFood(); }
    public int mealTicks(){ return meal.mealTicks(); }
    /** Ticks this body has waited its turn at a contested meal; 0 when it is not heading for food. */
    public int queuedTicks() { return task == Task.FOOD ? queueTicks : 0; }
    public boolean hasAccessory(){ return accessory.hasAccessory(); }
    /** The hat is simply not worn (a young who has none yet): no token, nothing to be returned. */
    public void removeAccessory(){ accessory.removeAccessory("removed"); }
    /** The hat is gone for the stated cause (destroyed, burned, sunk, owner died). */
    public void removeAccessory(String cause){ accessory.removeAccessory(cause); }
    public int socialWait() { return socialWait; }
    public void deferSocial(int ticks) { socialWait = Math.max(socialWait, ticks); }
    /** Recognition by accessory, or past a missing one by intellect alone (headwear.Recognition; kin are no exception). */
    public boolean canRecognize(YukkuriEntity target) { return Recognition.recognizes(entity, target); }
    public boolean needsFood(){ return meal.needsFood(); }
    private boolean edible(ItemStack food){ return meal.edible(food); }
    public boolean seeksFood(ItemStack food) { return meal.seeksFood(food); }
    boolean seeksFood(ItemStack food, UUID giver) { return meal.seeksFood(food, giver); }
    public boolean seeksOfferedFood(Player giver) {
        return seeksOfferedFood(giver.getMainHandItem(), giver) || seeksOfferedFood(giver.getOffhandItem(), giver);
    }
    private boolean seeksOfferedFood(ItemStack food, Player giver) {
        return food.is(YukkuriEntity.FOOD) && meal.seeksFood(food, giver.getUUID());
    }
    public boolean acceptsFood(ItemStack food){ return meal.acceptsFood(food); }
    private boolean readyForFood(){ return meal.readyForFood(); }
    /** Food gathered or offered as a normal meal is for a child that can chew independently. */
    public boolean canEat(){ return meal.canEat(); }
    public boolean pursuingFoodOnLead() { return task == Task.FOOD && targetFood != null && entity.pet().isPet() && entity.getLeashHolder() != null; }
    /** Checks mouth capacity and current eating state before a source resource is consumed. */
    public boolean canAcceptBodyFood() { return meal.canAcceptBodyFood(); }
    public boolean canAcceptBodyFood(BodyFood.Bite bite) { return meal.canAcceptBodyFood(bite); }
    /** Starts chewing a body bite. Its nutrition is granted only at the swallow tick. */
    public boolean acceptBodyFood(BodyFood.Bite bite) { return meal.acceptBodyFood(bite); }
    /** A sweet is taken on top of a meal ("second stomach") as long as the pet is not completely full. */
    public boolean canTakeSweet(ItemStack food){ return meal.canTakeSweet(food); }
    /** A parent may select this child for a real serving even while ordinary self-feeding is unavailable. */
    public boolean canReceiveCareFood(){ return meal.canReceiveCareFood(); }
    public boolean canReceiveCareFood(ItemStack food){ return meal.canReceiveCareFood(food); }
    public boolean hasUrgentNeed() { return nutrition < 30 || entity.family().stemFoodUrgent() || hasUrgentNonFoodNeed(); }
    public boolean hasUrgentNonFoodNeed() { return energy < 25 || bladder >= 90 && !constipated() || entity.isInWaterOrBubble() || entity.isOnFire() || wetness > 40; }
    /** Thirst at the threshold the daytime plan drinks by (dryness 40 or something spicy in the mouth), and only with a drink really in reach. */
    private boolean thirstUrgent() { return (dryness >= 40 || spicyTicks() > 0) && nearestDrink() != null; }
    /** Bladder at {@link #hasUrgentNeed()}'s threshold, and only when the daytime toilet plan has somewhere to go, so waking always ends in relief. */
    private boolean bladderUrgent() {
        return bladder >= 90 && !constipated() && entity.childhood().firstToiletDone() && !entity.leafSwaddle().wrapped()
                && (dryStand(entity.blockPosition()) || nearestCare(CareBlock.Kind.TOILET, false) != null);
    }
    /** The real need that ends any full life sleep at once (same thresholds the daytime plan uses), or null: a drink in reach, a full
     * bladder with somewhere to go, or an own hungry child awake within the family care range. */
    private String needWakeCause() {
        if (thirstUrgent()) return "thirst";
        if (bladderUrgent()) return "toilet";
        return entity.family().hasChildAwaitingFeed() ? "child_hungry" : null;
    }
    public boolean isSleeping() { return fatigueActive() || task == Task.SLEEP && sleepTicks >= 20 && wakeAt == 0 || entity.house().sleeping(); }
    public void wakeForStemFood() {
        if (drugSleep()>0) return;
        if (fatigueActive()) stopFatigueRest("stem_food");
        if (task==Task.SLEEP) stopGoal();
        planWait=0;
    }
    /** The house goal owns the physical entry and waking sequence; only real grounded rest restores energy. */
    public void restInHouse() {
        if (!entity.level().isClientSide && entity.house().sleeping() && !entity.isPanicking()
                && !entity.isInWaterOrBubble() && !entity.isOnFire() && mealTicks() == 0) energy = Math.min(100, energy + .5f);
    }
    public void wakeInHat() {
        if (task != Task.SLEEP) return;
        boolean rested = sleepTicks >= 220;
        if(sleepTicks>=20)GameplayLog.event(entity,"sleep","end source=hat ticks={} energy={} nutrition={} rested={}",sleepTicks,energy,nutrition,rested);
        task = Task.NONE; resetSleep(); restWait = restInterval();
        if (rested && !entity.family().woke()) entity.showReaction("neutral", "awake", 40);
    }
    public boolean wantsCrawl() {
        return task == Task.FOOD && mealTicks() == 0 && targetFood != null && targetFood.isAlive()
                && entity.distanceToSqr(targetFood) < 1;
    }
    public String activity() {
        if (fatigueActive()) return Behaviors.SLEEP;
        if (mealTicks() > 0) {
            String after = meal.content() ? Behaviors.MEAL_CONTENT : Behaviors.MEAL_FINISH;
            if (meal.preparedMeal()) return mealTicks() < 53 ? Behaviors.RECEIVE_MASH : after;
            return mealTicks() < 10 ? Behaviors.TAKE_FOOD : mealTicks() < 45 ? Behaviors.CHEW : mealTicks() < 53 ? Behaviors.SWALLOW : after;
        }
        if (task == Task.SLEEP) return sleepTicks < 20 ? Behaviors.SLEEP_ENTER : wakeAt > 0 ? Behaviors.WAKE : Behaviors.SLEEP;
        return switch (task) { case FOOD, FOOD_GLASS, CROP -> Behaviors.SEEK_FOOD; case WINTER_FOOD -> Behaviors.SHELTER_STOCK; case HOME -> Behaviors.RETURN_HOME; case TOILET -> toiletTicks > 0
                ? Behaviors.TOILET : Behaviors.SEEK_TOILET;
            case GROUND_TOILET -> toiletTicks > 0 ? toiletAccident ? Behaviors.TOILET_ACCIDENT : Behaviors.GROUND_TOILET : Behaviors.SEEK_TOILET;
            case DRINK -> drinkTicks>0?Behaviors.DRINK:Behaviors.SEEK_WATER; case TEMPERATURE -> temperature.activity();
            case FOUND_COVER -> foundCover.activity(); case GARBAGE -> garbage.activity(); default -> Behaviors.IDLE; };
    }
    public ShelterBlockEntity foundShelter() { return foundCover.inside(); }
    boolean usingPaperBedding(BlockPos pos) { return foundCover.usingPaperAt(pos); }
    public boolean targetingFoundShelter(BlockPos pos) { return foundCover.targeting(pos); }
    public boolean leavingFoundShelter() { return foundCover.exiting(); }
    public boolean usingGarbage(BlockPos pos) { return garbage.targeting(pos); }
    public boolean ownsStreetCover(BlockPos pos) { return foundCover.ownsStreetCover(pos); }
    public long yieldFoundShelter(BlockPos pos) { return foundCover.yield(pos); }
    public void foundShelterRemoved(BlockPos pos, boolean wet) {
        if (foundCover.targeting(pos)) stopGoal();
        foundCover.removed(pos, wet);
    }
    ServerLevel level() { return (ServerLevel)entity.level(); }
    /** Rain or leaks softening the skin: attrition, not an attack and not drowning (a bite of the death record said "drown" for it). */
    public static final ResourceKey<DamageType> SOAKING =
            ResourceKey.create(Registries.DAMAGE_TYPE, MCYukkuri.id("soaking"));
    public static boolean isSoaking(DamageSource source) { return source.typeHolder().is(SOAKING); }
    /** Damage that comes from being in water (drowning, soaking): it only takes health, it never interrupts, startles or pushes anything. */
    public static boolean isWaterDamage(DamageSource source) { return source.is(DamageTypeTags.IS_DROWNING); }
    private DamageSource soaking() {
        return new DamageSource(entity.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(SOAKING));
    }
    private int restThreshold() { return entity.family().needsMash() ? 65 : 35; }
    private int restInterval() { return entity.family().needsMash() ? 200 : 1200; }
    /** Tired enough, or it is its rest time: sleep, not some other pastime, is what it wants next. */
    public boolean drowsy() { return energy <= restThreshold() || restTime(); }
    boolean restTime() { return entity.profile().houseBody() ? !level().isNight() : level().isNight() || RareForms.summerDormant(entity); }
    /** Project design: the day/night half of the rest time, which is what a sleep runs out: the night for ordinary bodies, the day for a house
     * body. Seasonal dormancy (a summer Kotatsu) makes the body drowsy all day but never ends, so it does not hold one sleep going. Every life
     * sleep (the ordinary one and the energy-0 fatigue one) ends by this: it goes on while it is the body's own sleep time. */
    private boolean sleepTime() { return entity.profile().houseBody() ? !level().isNight() : level().isNight(); }

    public void tick() {
        if (entity.level().isClientSide || !entity.isAlive()) return;
        // Body contact with water hurts every species, even with AI disabled.
        // The soak timer only clears after 40 continuous dry ticks, so the dry ticks of a climb cannot cancel drowning damage.
        if (!entity.isInWaterOrBubble()) { if (++dryTicks >= DRY_RESET_TICKS) immersionTicks = 0; }
        else {
            dryTicks = 0;
            if (++immersionTicks >= immersionInterval()) {
                immersionTicks = 0;
                entity.hurt(level().damageSources().drown(), 1);
            }
        }
        if (!entity.isAlive()) return;
        accessory.tick();
        if (entity.isNoAi()) return;
        ageTicks++;
        personality.tick();
        if(exerciseReliefTicks>0)exerciseReliefTicks--;
        if (socialWait > 0) socialWait--;
        if (restWait > 0) restWait--;
        if (planWait > 0) planWait--;
        if (homeWait > 0) homeWait--;
        doses.tickSpice();
        emotionTick();
        if (++metabolismTicks >= Math.max(20, YukkuriConfig.HUNGER_INTERVAL.get() * entity.profile().hungerIntervalMultiplier())) {
            float before = nutrition;
            metabolismTicks = 0; nutrition = Math.max(0, nutrition - (isSleeping() ? 1 : 2) * nutritionDemand());
            nutritionBoundary(before, "metabolism");
        }
        // An actively growing stem draws from the body itself whenever nutrition runs out.
        if (nutrition > 0 || entity.family().growingStem()) starvingTicks = 0;
        else if (++starvingTicks >= Math.max(1200, YukkuriConfig.HUNGER_INTERVAL.get()*5)) {
            starvingTicks=0;
            float before=entity.getHealth();
            boolean accepted=entity.hurt(level().damageSources().starve(),1);
            GameplayLog.event(entity,"needs","starvation damage accepted={} health={} -> {}",accepted,before,entity.getHealth());
        }
        if (ageTicks % 20 == 0) {
            if(nutrition < 10)entity.addEffect(new MobEffectInstance(MobEffects.WEAKNESS,40,0,false,false,true));
            boolean slow = nutrition < 10 || energy <= 25 || dryness >= SEVERE_THIRST;
            if(slow)entity.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,40,1,false,false,true));
            if(slow != observedNeedSlowness) {
                observedNeedSlowness = slow;
                GameplayLog.event(entity,"needs","slowness renewal={} nutrition={} energy={} effect={}",slow,nutrition,energy,entity.getEffect(MobEffects.MOVEMENT_SLOWDOWN));
            }
        }
        if (!isSleeping() && ++thirstTicks >= (entity.family().adult() ? THIRST_INTERVAL : THIRST_INTERVAL * 2)) {
            thirstTicks = 0; addDryness(1);
            if (dryness == 40 || dryness == SEVERE_THIRST) GameplayLog.event(entity,"needs","thirst baseline dryness={} threshold={}",dryness,dryness >= SEVERE_THIRST ? "severe" : "drink");
        }
        if(energy > 25)napReady=false;
        else if(napCooldown > 0) { if(!isSleeping()) napCooldown--; }
        else if(!isSleeping() && !napReady && entity.getRandom().nextInt(300)==0)napReady=true;
        if (++energyTicks >= Math.max(20,
                YukkuriConfig.ENERGY_INTERVAL.get() / (entity.family().needsMash() ? 2 : 1))) { energyTicks = 0; if (!isSleeping()) energy = Math.max(0, energy - entity.species().drain()); }
        if (++hygieneTicks >= YukkuriConfig.HYGIENE_INTERVAL.get()) { hygieneTicks = 0; hygiene = Math.max(0, hygiene - 1); }
        if (ageTicks % 20 == 0) {
            if (entity.isInWaterOrBubble()) wetness = Math.min(100, wetness + entity.subspecies().waterGain(8));
            else if (entity.habitat().leaking() || level().isRainingAt(entity.blockPosition())) wetness = Math.min(100, wetness + (entity.profile().aquatic() ? 1.5f : entity.subspecies().waterGain(3)));
            else wetness = Math.max(0, wetness - 3);
            if (!entity.isInWaterOrBubble() && wetness >= YukkuriConfig.WET_DAMAGE_THRESHOLD.get() && ageTicks % (entity.profile().aquatic() ? 200 : 100) == 0) {
                float before = entity.getHealth();
                boolean accepted = entity.hurt(soaking(), 1);
                GameplayLog.event(entity,"needs","soaking damage wetness={} aquatic={} accepted={} health={} -> {}",wetness,entity.profile().aquatic(),accepted,before,entity.getHealth());
            }
        }
        temperature.tick();
        if (!entity.isAlive()) return;
        if(isSleeping() && (entity.isPanicking() || entity.isOnFire() || entity.isInWaterOrBubble()
                || level().isRainingAt(entity.blockPosition()) || temperature.unsafeSleep())) wakeForDanger("environment");
        if (entity.isPanicking() || entity.isOnFire() || entity.isInWaterOrBubble()) {
            if (mealTicks() > 0 || task != Task.NONE || sprayDelay()>0 || drugSleep()>0) interrupt();
            return;
        }
        doses.tickSpray();
        if (!meal.tickChew()) return;
        if (homeWait == 0) { homeWait = 200; if(maintainHome())homeWait=20; }
    }

    /** The caller commits exactly one real portion when the mouth accepts it. */
    public boolean beginMeal(ItemStack transferred, UUID fromPlayer, boolean alreadyShared){ return meal.beginMeal(transferred, fromPlayer, alreadyShared); }
    public boolean beginOfferedMeal(ItemStack food, Player giver) {
        return meal.beginOfferedMeal(food, giver.getUUID(), !giver.getAbilities().instabuild);
    }
    public boolean beginCropMeal(ItemStack serving, BooleanSupplier harvest) { return meal.beginHarvestMeal(serving, harvest); }
    public boolean avoidsCrop(BlockPos crop) {
        return avoidedField != null && level().getGameTime() < avoidFieldUntil && avoidedField.distSqr(crop) <= 64;
    }
    public void avoidField(BlockPos crop) {
        avoidedField = crop.immutable();
        avoidFieldUntil = level().getGameTime() + 24000;
    }
    /** A body with a fixed duty can take a reachable portion through the ordinary meal owner without walking. */
    public void eatAtStand() {
        if (entity.tickCount % 10 != 0 || !canEat() || !EventHooks.canEntityGrief(level(), entity)) return;
        var items = level().getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(.5), item ->
                seeksFood(item.getItem()) && ItemContact.reaches(entity,item) && WildCards.mayTakeFood(entity,item));
        var food = items.stream().min(Comparator.comparingInt((ItemEntity it) -> FoodTaste.rank(it.getItem()))
                .thenComparingDouble(entity::distanceToSqr)).orElse(null);
        if (food != null) takeGroundFood(food);
    }
    public boolean beginCarriedMeal(MouthOccupancy.Purpose source) { return meal.beginCarriedMeal(source); }
    /** A player can offer an already prepared serving, but a dependent child still cannot pick it up alone. */
    public boolean beginPreparedMeal(ItemStack prepared, UUID fromPlayer){ return meal.beginPreparedMeal(prepared, fromPlayer); }
    public boolean beginStaffMeal(ItemStack portion, UUID staff){ return meal.beginStaffMeal(portion, staff); }
    /** A biological parent must physically reach the child before transferring its prepared serving. */
    public boolean beginParentMeal(YukkuriEntity caregiver, ItemStack prepared){ return meal.beginParentMeal(caregiver, prepared); }
    public boolean beginKindnessMeal(YukkuriEntity giver, ItemStack food) { return meal.beginKindnessMeal(giver, food); }
    public boolean prepareKindnessFood() { return meal.prepareKindnessFood(); }
    public static boolean kindnessFood(ItemStack food) { return LifeMeal.kindnessFood(food); }
    public boolean canOfferMeal() { return meal.canOfferMeal(); }
    public boolean passCarriedMeal(YukkuriEntity peer, boolean share) { return meal.passCarriedMeal(peer, share); }
    public boolean finishCarriedMeal(UUID giver) { return meal.beginCarriedMeal(MouthOccupancy.Purpose.KINDNESS, giver, true); }
    /** A bully takes the serving out of this mouth (headwear.Sanction): it eats it if it can, otherwise the bite is knocked out. */
    public boolean snatchedBy(YukkuriEntity thief){ return meal.snatchedBy(thief); }
    private void tryShare(){ meal.tryShare(); }
    public void releaseMouthForDeath() { meal.releaseForInterrupt(); }
    public void clearDisplacedMeal() {
        meal.clearInterrupted();
        doses.clearReturnBottle();
    }

    public void interrupt() {
        accountEnvironmentalWake();
        stopFatigueRest();
        WildCards.mealInterrupted(entity);
        temperature.stop();
        entity.dailyLife().stop();
        if (!meal.releaseForInterrupt()) return;
        foundCover.stop();
        garbage.stop();
        if(task==Task.SLEEP&&sleepTicks>=20)GameplayLog.limited(entity,"sleep","interrupt",200,
                "interrupted source=life ticks={} energy={} nutrition={} panic={} water={} fire={}",
                sleepTicks,energy,nutrition,entity.isPanicking(),entity.isInWaterOrBubble(),entity.isOnFire());
        meal.clearInterrupted(); doses.clearReturnBottle();
        doses.stopSedation();drinkTicks=0;
        endFoodGlass();
        resetSleep(); toiletTicks = 0; task = Task.NONE; targetFood = null; targetBlock = null;
        planWait = 20; entity.getNavigation().stop();
    }
    public boolean wash(UUID player) {
        var actor = level().getEntity(player);
        if (actor == null) actor = level().getPlayerByUUID(player);
        return wash(player, actor);
    }

    public boolean wash(Player player) { return wash(player.getUUID(), player); }

    private boolean wash(UUID player, Entity actor) {
        var marks = entity.status().marks();
        int dirt = marks.dirt();
        if ((hygiene >= 99 && marks.grime() == 0) || entity.isInWaterOrBubble() || entity.isOnFire()
                || isSleeping() || entity.isPanicking() || entity.operations().active() || entity.isCommandPlaying()
                || entity.family().birthDuty() || entity.body().dying()) return false;
        var contact = SocialDisposition.contact(entity, actor);
        var response = contact.response(true);
        float before = hygiene;
        hygiene = Math.min(100, hygiene + 40); wetness = Math.min(100, wetness + 5);
        marks.washed();
        personality.washed(player);
        switch (response) {
            case GUARDED -> entity.showReaction(YukkuriAction.TREMBLE, "teary", "");
            case DEFIANT -> entity.showReaction(YukkuriAction.STRUGGLE, "angry", "refuse");
            case GRATEFUL -> entity.showReaction(YukkuriAction.BOW, "closed_smile", "request_thanks");
            default -> entity.showReaction("happy", "washed", 60);
        }
        GameplayLog.event(entity, "interaction", "wash player={} response={} esteem={} wariness={} hygiene={}->{} dirt={}->{}",
                player, response.id(), contact.esteem(), contact.wariness(), before, hygiene, dirt, marks.dirt());
        return true;
    }
    /** The worn hat leaves the head as the placed hat {@code hat}; returns the custody token it carries. */
    public UUID stowAccessory(UUID hat){ return accessory.stowAccessory(hat); }
    public UUID takeAccessory(UUID player){ return accessory.takeAccessory(player); }
    public UUID takeAccessory(UUID player,UUID token){ return accessory.takeAccessory(player, token); }
    public boolean returnAccessory(UUID owner, UUID token, UUID player){ return accessory.returnAccessory(owner, token, player); }
    /** Receive a compatible loose physical accessory and its repair progress after releasing any held fragments. */
    public boolean receiveAccessory(ItemStack stack,UUID player){ return accessory.receiveAccessory(stack,player); }
    /** Read-only refusal category for a different intact accessory. */
    public String accessoryReceiptRefusalReason(){ return accessory.accessoryReceiptRefusalReason(); }
    /** Retire a loaded former owner's token immediately after an accepted whole-accessory transfer. */
    public boolean retireTransferredAccessory(UUID token,UUID recipient){ return accessory.retireTransferredAccessory(token,recipient); }
    /** Its own accessory is whole on its head again (pieces knit together, headwear.Headwear): no token was involved. */
    public boolean wearAccessory(){ return accessory.wearAccessory(); }
    public boolean matchesAccessory(UUID owner,UUID token){ return accessory.matchesAccessory(owner, token); }
    /** Where the hat is: the one record every read of its whereabouts goes through. */
    public HatCustody custody(){ return accessory.custody(); }
    /** A stowed, token-matched physical hat is still this individual's accessory. */
    public boolean accessoryCustodied(){ return accessory.custodied(); }
    public boolean relinquishCustody(UUID owner,UUID token,UUID player,String cause){ return accessory.relinquishCustody(owner, token, player, cause); }

    public boolean protectsStep() {
        return mealTicks() > 0 || task == Task.SLEEP || toiletTicks > 0 || task == Task.DRINK && drinkTicks > 0;
    }

    /** Food was just seen by another goal: let the next plan run now instead of after its wait. */
    public void replanNow() { planWait = 0; }
    public boolean canUseCareFacility(CareBlock.Kind kind) {
        return nearestCare(kind, kind == CareBlock.Kind.BOWL) != null;
    }

    public boolean plan() {
        if (entity.family().awaitingLanding()) return false;
        if (task == Task.HOME && ParentCarry.awaitsCarrier(entity)) { stopGoal(); return false; }
        if (entity.predator().active()) return false;
        if (entity.isPanicking() || entity.isCommandPlaying() || entity.isInWaterOrBubble()) return false;
        if (mealTicks() > 0) return true;
        if (BadgeArcs.running(entity) && (!hasUrgentNeed() || BadgeArcs.handlesFoodHunger(entity))) return false;
        if (task == Task.WINTER_FOOD) {
            if (entity.wintering().continueMeal()) {
                if (pursuitTicks >= winterNeedCheckAt) {
                    winterNeedCheckAt = pursuitTicks + 20;
                    if (planWinterPriority()) return true;
                }
                if (!hasUrgentNeed() && entity.family().canUse()) { stopGoal(); return false; }
                return true;
            }
            stopGoal(); planWait = 0;
        }
        if (task == Task.SLEEP && temperature.hasTemporarySleepSite() && !temperature.canSleepAtRefuge()) {
            stopGoal(); return false;
        }
        if (task == Task.SLEEP && foundCover.active()) {
            if (foundCover.checkRest(foundCover.restingAtBench() && wakeAt > 0)) return true;
            resetSleep();
            if (foundCover.exiting()) { task = Task.FOUND_COVER; return true; }
            stopGoal(); return false;
        }
        if (task == Task.TEMPERATURE) {
            if (temperature.canContinue()) return true;
            stopGoal(); planWait = 0;
        }
        if ((task == Task.SLEEP || task == Task.HOME) && drugSleep() == 0 && temperature.unsafeSleep() && temperature.plan()) {
            task = Task.TEMPERATURE; resetSleep(); return true;
        }
        if ((task == Task.FOOD || task == Task.FOOD_GLASS) && entity.forageTrip().provisioningFirst()) { stopGoal(); return false; }
        if (task != Task.NONE) return true;
        if(drugSleep()>0){task=Task.SLEEP;resetSleep();sleepTicks=20;return true;}
        var inside = ShelterBlock.foundInterior(level(), entity.blockPosition());
        if (inside != null && !entity.isPassenger()) {
            if (!foundCover.planInside(inside, !ParentCarry.awaitsCarrier(entity))) return false;
            task = Task.FOUND_COVER;
            return true;
        }
        if (planWait > 0) return false;
        planWait = 20;
        if(dryness>=40||spicyTicks()>0 || wantsStreetRinse()){targetBlock=nearestDrink();if(targetBlock!=null){task=Task.DRINK;drinkTicks=pursuitTicks=0;return true;}}
        // Getting out of the rain is the status module's weather reaction (Reactions.WeatherGoal); here only the walk back to the own nest.
        if (rainOutside() && !covered(entity.blockPosition()) && nutrition >= 30 && !entity.settlement().underway() && !ParentCarry.awaitsCarrier(entity)) {
            targetBlock = homeStand();
            if (targetBlock != null && covered(targetBlock) && mayReturnToBed(targetBlock)) { task = Task.HOME; pursuitTicks = 0; return true; }
        }
        // A full bowel must not be starved by a permanently stocked food bowl.
        if (bladder >= 70 && planToilet()) return true;
        // Executable urgent needs above still interrupt a stocked nest, shelter or egg-care routine.
        switch (homeProvider().beforeFood(this)) {
            case TEMPERATURE -> { task = Task.TEMPERATURE; return true; }
            case ROUTINE -> { return false; }
            case CONTINUE -> { }
        }
        boolean penned = penned();
        if (!penned && canEat() && entity.wintering().stockedHome() != null) {
            if (temperature.unsafeSleep() && temperature.plan()) { task = Task.TEMPERATURE; return true; }
            // Activate pending care before this new food route could monopolize the higher-priority life goal.
            if (!temperature.unsafeSleep() && !hasUrgentNeed() && entity.family().canUse()) return false;
            if (entity.wintering().planMeal()) { task = Task.WINTER_FOOD; pursuitTicks = 0; winterNeedCheckAt = 20; return true; }
        }
        if (!penned && canEat() && !entity.forageTrip().provisioningFirst()) {
            boolean canCollect = EventHooks.canEntityGrief(level(), entity);
            var offered = level().getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(TravelRange.search(entity,8)), item ->
                    item.isAlive() && seeksFood(item.getItem()));
            // One pass in the species' own order (taste, then nearness): the first portion it can really walk to is the meal, and
            // when none is, the same pass has already counted why each was passed over for the readout below.
            boolean ignoresScraps = entity.pet().care().walk().ignoresScraps();
            boolean mayPress = !ignoresScraps && level().getGameTime() >= foodGlassAfter;
            Entity glassFood = null;
            Vec3 glassStand = null;
            targetFood = null; int delayed = 0, outOfBabyRange = 0, unreachable = 0;
            for (var item : offered.stream().sorted(Comparator.comparingInt((ItemEntity it) -> FoodTaste.rank(it.getItem()))
                    .thenComparingDouble(entity::distanceToSqr)).toList()) {
                if (!TravelRange.babyDestination(entity, item.blockPosition())) { outOfBabyRange++; continue; }
                if (canCollect && !item.hasPickUpDelay() && foodRouteAvailable(item.position())
                        && (entity.distanceToSqr(item) < .5 && entity.hasLineOfSight(item) || reachable(item.blockPosition(), false))) {
                    targetFood = item;
                    break;
                }
                if (mayPress && glassFood == null) {
                    glassStand = foodWindowStand(item);
                    if (glassStand != null && !reachable(item.blockPosition(), false)) glassFood = item;
                }
                if (item.hasPickUpDelay()) delayed++;
                else unreachable++;
            }
            if (canCollect && targetFood == null && !offered.isEmpty()) GameplayLog.limited(entity, "meal", "food_plan_refused", 400,
                    "loose food in reach but not planned items={} pickupDelay={} outOfBabyRange={} unreachable={} task={} nutrition={}",
                    offered.size(), delayed, outOfBabyRange, unreachable, task, nutrition);
            targetBlock = canCollect ? nearestCare(CareBlock.Kind.BOWL, true) : null;
            if (canCollect && targetFood == null && targetBlock == null) refuseReachableServing();
            if (ignoresScraps) targetFood = null;
            useBowlNicely = targetBlock != null && followsRule(PetSkill.FOOD_BOWL);
            if (useBowlNicely && entity.pet().isPet()) targetFood = null;
            if (targetFood != null || targetBlock != null) {
                bowlStand = null;
                task = Task.FOOD; pursuitTicks = queueTicks = 0; queueBudget = personality.waitingTicks(); return true;
            }
            if (mayPress && glassFood == null) {
                // A visible serving in someone's hand is a nearby invitation. Hunger extends interest from four to eight blocks.
                double range = Mth.lerp(1 - SocialDisposition.ramp(nutrition, 15, LifeMeal.FULL), 4, 8);
                for (var player : level().getEntitiesOfClass(Player.class, entity.getBoundingBox().inflate(range),
                        p -> entity.distanceToSqr(p) <= range * range && seesOfferedWindowFood(p)).stream()
                        .sorted(Comparator.comparingDouble(entity::distanceToSqr)).toList()) {
                    glassStand = foodWindowStand(player);
                    if (glassStand != null && !reachable(player.blockPosition(), false)) { glassFood = player; break; }
                }
            }
            if (glassFood != null) {
                foodGlassTarget = glassFood;
                foodGlassStand = glassStand;
                task = Task.FOOD_GLASS;
                pursuitTicks = 0;
                GameplayLog.limited(entity, "meal", "glass_food_planned", 400,
                        "food behind glass target={} kind={} stand={} nutrition={}",
                        glassFood.getUUID(), glassFood instanceof ItemEntity ? "loose" : "held", GameplayLog.at(glassStand), nutrition);
                return true;
            }
        }
        if (bladder < 70 && planToilet()) return true;
        if (!penned && nutrition < 30 && canEat() && level().getGameTime() >= cropSearchAfter
                && Grazing.streetForager(entity) && EventHooks.canEntityGrief(level(), entity)) {
            cropSearchAfter = level().getGameTime() + 80;
            targetBlock = Grazing.nearbyCrop(entity);
            if (targetBlock != null) { task = Task.CROP; cropLook = pursuitTicks = 0; return true; }
        }
        if (!penned) {
            boolean freeToShelter = !ParentCarry.awaitsCarrier(entity);
            boolean rainFirst = freeToShelter && foundCover.seekingStreetRoof();
            if (rainFirst && foundCover.plan()) { task = Task.FOUND_COVER; return true; }
            if (EventHooks.canEntityGrief(level(), entity) && garbage.plan()) { task = Task.GARBAGE; return true; }
            if (freeToShelter && !rainFirst && foundCover.plan()) { task = Task.FOUND_COVER; return true; }
        }
        if (temperature.plan()) { task = Task.TEMPERATURE; return true; }
        if (!entity.family().stemFoodUrgent() && (restWait == 0 || entity.pet().lessons().practicing(PetSkill.BEDTIME)) && (energy <= restThreshold() || restTime())
                && homeProvider().ordinaryRestAllowed(this)) {
            // On a migration there is no burrow to walk back to: rest on dry ground where it stands, then walk on.
            if (entity.settlement().underway()) {
                if (dryStand(entity.blockPosition()) && restable(entity.blockPosition())) { task = Task.SLEEP; resetSleep(); return true; }
                return false;
            }
            // A slave kept in the waste pit has no bed to go to: it sleeps where it is once it cannot go on (energy 10 or less).
            targetBlock = penned || ParentCarry.awaitsCarrier(entity) ? null : homeStand();
            if (targetBlock != null && entity.pet().isPet() && !followsRule(PetSkill.BEDTIME)
                    && !level().isRainingAt(entity.blockPosition()) && SurfaceApproach.supportedGround(entity, entity.position())) {
                targetBlock = null;
                task = Task.SLEEP; resetSleep(); return true;
            }
            var nestOccupancy = targetBlock == null ? null : FamilyNest.bedOccupancy(entity, targetBlock);
            if (targetBlock != null && entity.family().prepareHomeCarry()) return false;
            boolean dependentAtHome = entity.childhood().shouldStayWithParents() && nestOccupancy == Occupancy.INSIDE_SUPPORTED;
            boolean bedRefused = targetBlock != null && !mayReturnToBed(targetBlock) && !dependentAtHome;
            if (bedRefused) targetBlock = null;
            if (targetBlock != null && !dependentAtHome && (entity.distanceToSqr(Vec3.atBottomCenterOf(targetBlock)) > .16 || !restable(entity.blockPosition())
                    || nestOccupancy != null && nestOccupancy != Occupancy.INSIDE_SUPPORTED)) { task = Task.HOME; pursuitTicks = 0; return true; }
            // A slave in the open pit, or shop stock kept without a bed (the oni shop's pens), curls up where it stands at rest
            // time: dry ground is all it has.
            boolean penSleep = (penned || targetBlock == null && entity.status().identity() == Identity.STOCK)
                    && dryStand(entity.blockPosition()) && !level().isRainingAt(entity.blockPosition());
            boolean restShort = bedRefused && SurfaceApproach.supportedGround(entity, entity.position()) && !level().isRainingAt(entity.blockPosition());
            if (targetBlock != null && !dependentAtHome && settleIntoBed()) return false;
            if (targetBlock != null || energy <= 10 || penSleep || restShort || entity.family().needsMash() && entity.childhood().landed()
                    && dryStand(entity.blockPosition()) && restable(entity.blockPosition())) { task = Task.SLEEP; resetSleep(); return true; }
        }
        return false;
    }
    /**
     * Lying down in its own graded bed it first looks it over (NestComfort.settle): true while that reaction plays or after it
     * refused the bed, so it stays up for now (restWait); the next look after a refusal gives in. A recent look is not repeated.
     */
    private boolean settleIntoBed() {
        var bed = FamilyNest.care(entity);
        if (bed == null || !bed.getBlockPos().equals(targetBlock) || bed.grade() == 0) return false;
        long now = level().getGameTime();
        boolean recent = bedLook != null && bedLook.bed().equals(bed.getBlockPos())
                && bedLook.grade() == bed.grade() && now - bedLook.tick() < BED_LOOK_MEMORY;
        if (recent && !bedLook.refused()) return false;
        var look = NestComfort.settle(entity, bed, recent, energy, entity.pet().lessons().practicing(PetSkill.BEDTIME));
        bedLook = new BedLook(bed.getBlockPos(), bed.grade(), now, look.refused());
        if (look.delay() == 0) return false;
        restWait = look.delay();
        targetBlock = null;
        return true;
    }
    /** Unmet needs are not prohibitions on food: only an executable higher-priority plan takes over. */
    private boolean planWinterPriority() {
        if (dryness >= 40 || spicyTicks() > 0) {
            var water = nearestDrink();
            if (water != null) {
                entity.wintering().stopMeal(); targetBlock = water; task = Task.DRINK;
                drinkTicks = pursuitTicks = 0; return true;
            }
        }
        if (bladder >= 70 && planToilet()) { entity.wintering().stopMeal(); return true; }
        if (temperature.unsafeSleep() && temperature.plan()) {
            entity.wintering().stopMeal(); task = Task.TEMPERATURE; pursuitTicks = 0; return true;
        }
        return false;
    }
    private boolean followsRule(PetSkill skill) {
        if (!entity.pet().isPet() || nutrition < 15 || energy < 15) return true;
        if (skill == PetSkill.FOOD_BOWL) return entity.pet().lessons().everydayAttempt(skill);
        var choice = ruleChoices.get(skill);
        if (choice == null || entity.tickCount >= choice.until()) {
            choice = new RuleChoice(entity.tickCount + 1200, entity.pet().lessons().everydayAttempt(skill));
            ruleChoices.put(skill, choice);
        }
        return choice.follows();
    }
    private boolean planToilet() {
        // A closed leaf swaddle keeps the young one from going by itself; only a parent's care opens it (family/LeafSwaddle).
        if (!entity.childhood().firstToiletDone() || bladder < 40 || constipated() || entity.leafSwaddle().wrapped()) return false;
        var toilet = nearestCare(CareBlock.Kind.TOILET, false);
        boolean missedToilet = toilet != null && !followsRule(PetSkill.TOILET);
        var here = entity.blockPosition();
        boolean groundReady = bladder >= (missedToilet ? 70 : 90) && dryStand(here);
        if (groundReady) groundReady = !GroupPits.inCells(GroupPits.avoidedCells(entity), here);
        if (toilet != null && (!missedToilet || bladder >= 70 && !groundReady)) {
            targetBlock = toilet;
            task = Task.TOILET; toiletTicks = pursuitTicks = 0; toiletAccident = false; return true;
        }
        boolean pet = (Custody.view(entity).owner() != null)
                || home() != null && !naturalHome();
        if (bladder < (pet && !missedToilet ? 90 : 70)) return false;
        targetBlock = null; toiletPit = false; toiletPitCell = null; pitEdgeTicks = 0;
        // A community member goes to the group's pit; lone wild yukkuri and families use ground away from home.
        var spot = pet ? null : GroupPits.spot(entity);
        if (spot != null) {
            targetBlock = spot.stand(); toiletPit = true; toiletPitCell = spot.pit(); toiletAccident = false;
            task = Task.GROUND_TOILET; toiletTicks = pursuitTicks = 0;
            GameplayLog.event(entity,"needs","toilet pit trip start pit={} stand={} distance={} bladder={} portions={}",spot.pit(),spot.stand(),
                    String.format("%.1f",Math.sqrt(spot.stand().distSqr(entity.blockPosition()))),bladder,GroupPits.waste((ServerLevel)level(),spot.pit()));
            return true;
        }
        var found = foundShelter();
        if (!missedToilet && found != null && dryStand(found.entrance()) && reachable(found.entrance(), false)) {
            targetBlock = found.entrance(); task = Task.GROUND_TOILET; toiletTicks = pursuitTicks = 0; toiletAccident = false;
            return true;
        }
        if (!pet) {
            var avoidedPit = GroupPits.avoidedCells(entity);
            var candidates = new ArrayList<BlockPos>();
            for (BlockPos candidate : BlockPos.betweenClosed(entity.blockPosition().offset(-4,0,-4),entity.blockPosition().offset(4,0,4))) {
                if (home() != null && home().dimension().equals(level().dimension()) && candidate.distSqr(home().pos()) < 16) continue;
                if (GroupPits.inCells(avoidedPit, candidate) || !dryStand(candidate)) continue;
                if (!level().getEntitiesOfClass(ItemEntity.class,new AABB(candidate).inflate(1),i->acceptsFood(i.getItem())).isEmpty()) continue;
                candidates.add(candidate.immutable());
            }
            candidates.sort(Comparator.comparingDouble(c -> c.distSqr(entity.blockPosition())));
            for (int i = 0; i < Math.min(6, candidates.size()) && targetBlock == null; i++) if (reachable(candidates.get(i), false)) targetBlock = candidates.get(i);
        }
        toiletAccident = pet || targetBlock == null;
        if (targetBlock == null && groundReady) targetBlock = here;
        if (targetBlock == null) return false;
        task = Task.GROUND_TOILET; toiletTicks = pursuitTicks = 0; return true;
    }
    public void tickGoal() {
        if (mealTicks() > 0) { entity.getNavigation().stop(); return; }
        if (entity.isPanicking() || entity.isCommandPlaying() || entity.isInWaterOrBubble()) { stopGoal(); return; }
        pursuitTicks++;
        switch (task) {
            case FOUND_COVER -> {
                var step = foundCover.tick();
                if (step == FoundShelter.Step.ENDED) stopGoal();
                else if (step == FoundShelter.Step.RESTING && drowsy() && !entity.family().stemFoodUrgent() && foundCover.readyToSleep()) {
                    task = Task.SLEEP; targetBlock = null; resetSleep();
                }
            }
            case GARBAGE -> { if (!garbage.tick()) stopGoal(); }
            case CROP -> {
                if (pursuitTicks > 200 || !Grazing.streetForager(entity) || !canEat() || !Grazing.cropAt(entity, targetBlock)) {
                    stopGoal();
                } else if (Grazing.reachesCrop(entity, targetBlock)) {
                    entity.getNavigation().stop();
                    entity.getLookControl().setLookAt(targetBlock.getX()+.5, targetBlock.getY()+.25, targetBlock.getZ()+.5);
                    if (++cropLook >= 20 && !Grazing.biteCrop(entity, targetBlock)) stopGoal();
                } else {
                    cropLook = 0;
                    if (pursuitTicks % 10 == 1 && !navigate(targetBlock, 1, false)) stopGoal();
                }
            }
            case TEMPERATURE -> {
                if (!temperature.tickGoal()) stopGoal();
                else if (!entity.family().stemFoodUrgent() && restWait == 0 && (energy <= restThreshold() || restTime()) && temperature.beginSleepAtRefuge()) {
                    task = Task.SLEEP; resetSleep(); targetBlock = null;
                }
            }
            case DRINK -> {
                if(targetBlock==null||!drinkableWater(targetBlock)||pursuitTicks>240){stopGoal();return;}
                if(entity.distanceToSqr(Vec3.atCenterOf(targetBlock))<2.25&&seesWater(targetBlock)
                        && SurfaceApproach.supportedGround(entity, entity.position())){
                    entity.getNavigation().stop();entity.getLookControl().setLookAt(targetBlock.getX()+.5,targetBlock.getY()+.4,targetBlock.getZ()+.5,20,20);
                    if(++drinkTicks>=40){
                        var pool=PlayBlock.facility(level(),targetBlock);
                        if(pool==null||pool.spendWater()){
                            float before=dryness;
                            drink(30);
                            if (FoundShelter.streetUser(entity) && pool == null) {
                                if (hygiene < 60) poolRinse();
                                streetDrinkAfter = level().getGameTime() + 2400;
                            }
                            GameplayLog.event(entity,"needs","drink source={} dryness={} -> {} pool={}",targetBlock,before,dryness,pool!=null);
                        } else GameplayLog.limited(entity,"needs","water_empty",200,"drink refused source={} reason=pool_empty dryness={}",targetBlock,dryness);
                        task=Task.NONE;drinkTicks=0;planWait=40;entity.gameEvent(GameEvent.DRINK);
                    }
                }else {
                    drinkTicks = 0;
                    if (pursuitTicks%10==1){var stand=drinkStand(targetBlock);if(stand==null||!navigate(stand,1,false))stopGoal();}
                }
            }
            case FOOD -> seekFood();
            case FOOD_GLASS -> seekFoodAtGlass();
            case WINTER_FOOD -> { if (!entity.wintering().tickMeal()) stopGoal(); }
            case HOME -> {
                if (targetBlock == null || !restable(targetBlock) || nutrition < 30) { stopGoal(); homeWait = 0; return; }
                var nestOccupancy = FamilyNest.bedOccupancy(entity, targetBlock);
                boolean inside = nestOccupancy == null || nestOccupancy == Occupancy.INSIDE_SUPPORTED;
                // A dependent already supported inside the assigned bed can rest at its actual feet.
                boolean restingHere = nestOccupancy == Occupancy.INSIDE_SUPPORTED && entity.childhood().shouldStayWithParents();
                if (!restingHere && !dryStand(targetBlock)) { stopGoal(); homeWait = 0; return; }
                // The same pursuit budget ends a walk that never gets the whole footprint into the bed (no new wait).
                if (pursuitTicks > 600) {
                    if (!inside) GameplayLog.limited(entity, "home", "nest_not_inside", 200,
                            "home walk stopped reason=nest_not_inside nest={} occupancy={} pursuitTicks={}", targetBlock, nestOccupancy, pursuitTicks);
                    stopGoal(); homeWait = 0; return;
                }
                // Vanilla pathing ends up to ~0.55 block short of the last node, so the final step is steered
                // on the supported surface; otherwise the 0.4-block arrival test below could never be met.
                Vec3 bedCentre = restingHere ? entity.position() : FamilyNest.restPosition(entity,targetBlock);
                if(bedCentre==null){
                    if(!inside) GameplayLog.limited(entity, "home", "nest_not_inside", 200,
                            "home walk stopped reason=nest_not_inside nest={} occupancy={} cause=no_rest_place", targetBlock, nestOccupancy);
                    stopGoal();return;
                }
                if (!restingHere && (entity.distanceToSqr(bedCentre) >= .16 || !inside)
                        && (!mayReturnToBed(targetBlock) || !walkToBed(targetBlock, bedCentre, 1.15))) {
                    stopGoal();
                    return;
                }
                if (restingHere || inside && entity.distanceToSqr(bedCentre) < .16 && restable(entity.blockPosition())) {
                    SurfaceApproach.halt(entity);
                    if (!entity.family().stemFoodUrgent() && (restWait == 0 || entity.pet().lessons().practicing(PetSkill.BEDTIME)) && (energy <= restThreshold() || restTime())) {
                        // Arrived to sleep: it looks its own bed over first (it may stay up a while, see settleIntoBed).
                        if (settleIntoBed()) { task = Task.NONE; planWait = 0; } else { task = Task.SLEEP; resetSleep(); }
                    }
                    // Awake arrival ends the trip, even while rain continues above the roof.
                    else { task = Task.NONE; targetBlock = null; planWait = 0; }
                } else if (pursuitTicks > 60 && entity.getNavigation().isDone() && entity.distanceToSqr(bedCentre) > 4 && !reachable(targetBlock, false)) stopGoal();
            }
            case TOILET -> {
                if (!entity.childhood().firstToiletDone()) { stopGoal(); return; }
                if (targetBlock == null || !(level().getBlockEntity(targetBlock) instanceof CareBlockEntity care) || care.kind() != CareBlock.Kind.TOILET
                        || !care.acceptsWaste(DetachedItems.waste(entity)) || pursuitTicks > 240) { stopGoal(); return; }
                if (constipated() || bladder < 40 || ToiletContact.facilitySeat(entity, targetBlock) == null) { stopGoal(); return; }
                if (ToiletContact.approachFacility(entity, targetBlock)) {
                    if (++toiletTicks == 1) entity.speak("need_toilet");
                    // The stool leaves the anus at the end of the second push; the clip then relaxes for a few ticks.
                    if (toiletTicks == ToiletContact.DROP_TICKS) {
                        float before=bladder;
                        if (ToiletContact.facilityContact(entity, targetBlock) && care.relieve(DetachedItems.waste(entity))) {
                            releaseWaste(); personality.relieveFear(); ruleChoices.remove(PetSkill.TOILET);
                            entity.pet().routineCompleted(1); entity.showReaction("happy", "toilet_done", 60);
                            GameplayLog.event(entity,"needs","relieved source=facility at={} bladder={} -> {} volume={}",targetBlock,before,bladder,care.waste());
                        } else { stopGoal(); return; }
                    }
                    if (toiletTicks >= ToiletContact.RELEASE_TICKS) stopGoal();
                } else toiletTicks = 0;
            }
            case GROUND_TOILET -> {
                if (!entity.childhood().firstToiletDone()) { stopGoal(); return; }
                if (targetBlock == null || !dryStand(targetBlock) || constipated() || pursuitTicks > (toiletPit ? PIT_TRIP_TICKS : GROUND_TRIP_TICKS)) {
                    if (toiletPit && targetBlock != null) GroupPits.refuse(entity, pursuitTicks > PIT_TRIP_TICKS ? "timeout" : "cell_lost", targetBlock);
                    stopGoal(); return;
                }
                if (toiletPit && toiletPitCell != null && !pitBankReady()) { toiletTicks = 0; return; }
                if (toiletPit && toiletPitCell != null || entity.onGround() && SurfaceApproach.supported(entity, entity.position()) && entity.distanceToSqr(Vec3.atBottomCenterOf(targetBlock)) <= .25) {
                    SurfaceApproach.halt(entity);
                    if (++toiletTicks == 1) {
                        entity.speak("need_toilet");
                        if (toiletPit) GameplayLog.event(entity,"needs","toilet pit edge reached stand={} pit={} portions={}",targetBlock,toiletPitCell,
                                GroupPits.waste((ServerLevel)level(),toiletPitCell));
                    }
                    if (toiletTicks == ToiletContact.DROP_TICKS) {
                        // From the anus of this very model at this moment of the clip (see ToiletContact.anus); at the pit the portion lands in the pit cell behind it.
                        float before=bladder;
                        if (bladder >= 40 && ToiletContact.drop(entity, ToiletContact.anus(entity, ToiletContact.DROP_TICKS), toiletPit ? toiletPitCell : null)) {
                            releaseWaste(); personality.relieveFear(); ruleChoices.remove(PetSkill.TOILET);
                            GameplayLog.event(entity,"needs","relieved source={} at={} bladder={} -> {} accident={}",toiletPit?"pit":"ground",targetBlock,before,bladder,toiletAccident);
                            if (toiletAccident) {
                                hygiene = Math.max(0,hygiene-20);
                                entity.pet().lessons().performed(PetSkill.TOILET, false);
                            }
                            entity.showReaction(toiletAccident?"teary":"happy", "toilet_done",60);
                            if (toiletPit) entity.settlement().society().pitExcreted();
                        } else { stopGoal(); return; }
                    }
                    if (toiletTicks >= ToiletContact.RELEASE_TICKS) stopGoal();
                } else {
                    toiletTicks=0;
                    if(pursuitTicks%10==1&&!travel(targetBlock,1)){ if(toiletPit)GroupPits.refuse(entity,"no_path",targetBlock); stopGoal(); }
                }
            }
            case SLEEP -> {
                if (temperature.hasTemporarySleepSite() && !temperature.canSleepAtRefuge()) { stopGoal(); break; }
                if(level().isRainingAt(entity.blockPosition())){doses.clearDrugSleep();stopGoal();homeWait=0;break;}
                entity.getNavigation().stop(); sleepTicks++;
                boolean drugged = drugSleep() > 0;
                if(drugged){doses.tickDrugSleep();sleepTicks=Math.min(sleepTicks,600);}
                // A nest admits only a whole footprint inside it: rolling or being pushed onto the rim ends the sleep.
                // Sampled once a second, not every tick, so a sleeping body costs no geometry work per tick.
                var site = targetBlock != null ? targetBlock : entity.blockPosition();
                boolean sampleBed = sleepTicks % 20 == 0;
                var nestOccupancy = sampleBed ? FamilyNest.bedOccupancy(entity, site) : null;
                if (nestOccupancy != null && nestOccupancy != Occupancy.INSIDE_SUPPORTED) {
                    GameplayLog.limited(entity, "home", "nest_not_inside", 200,
                            "sleep stopped reason=nest_not_inside nest={} occupancy={} ticks={}", site, nestOccupancy, sleepTicks);
                    stopGoal(); break;
                }
                if (sampleBed) {
                    var bed = nestOccupancy == null ? null : FamilyNest.care(entity);
                    bedRead = bed != null && bed.getBlockPos().equals(site) && bed.grade() > 0 ? NestComfort.read(entity, bed.grade()) : null;
                }
                if (sleepTicks == 20) {
                    entity.speak("sleep");
                    GameplayLog.event(entity,"sleep","begin source=life occupancy={} bed={} energy={} nutrition={} drugTicks={} reason={}",
                            nestOccupancy == null ? "none" : nestOccupancy.name(), nestOccupancy == null ? "none" : site,
                            energy,nutrition,drugSleep(),restTime()?"rest_time":"energy");
                    if (!drugged && entity.pet().isPet()) {
                        ruleChoices.remove(PetSkill.BEDTIME);
                        boolean settled = targetBlock != null && targetBlock.equals(homeStand())
                                && (nestOccupancy == null || nestOccupancy == Occupancy.INSIDE_SUPPORTED);
                        if (settled) {
                            entity.pet().routineCompleted(2);
                            Staging.card(level(), entity.position().add(0, entity.getBbHeight() + 1, 0),
                                    Component.translatable("pet.mcyukkuri.practice.good_night"), 60, 1.2f);
                        } else entity.pet().lessons().performed(PetSkill.BEDTIME, false);
                    }
                }
                if (sleepTicks >= 20 && wakeAt == 0) energy = Math.min(100, energy + .5f * (bedRead == null ? 1 : bedRead.rest()));
                // Waking rested it lies there one long stretch and yawn (the wake clip); hunger gets it straight up to eat.
                int stretch = ClipMarkers.of(Behaviors.WAKE).length();
                if (drugged && drugSleep() == 0) wakeAt = SLEEP_TICKS + stretch; // a drug's sleep keeps its own finite wake
                else if (wakeAt == 0 && drugSleep() == 0) {
                    // Real needs end a sleep at once, with the thresholds the daytime plan uses (checked once a second, like the nest sample).
                    String need = sleepTicks % 20 == 0 ? needWakeCause() : null;
                    if (sleepTicks >= 20 && foundCover.humanNearBench()) wakeAt = sleepTicks + stretch;
                    else if (sleepTicks >= sleepDuration() && nutrition < 15) wakeNow("hunger");
                    else if (need != null) wakeNow(need);
                    // A sleep taken at its own rest time (the night, or a house body's day) goes on past the full stretch, so a night sleep
                    // lasts until that time is over; the rested wake clip then runs as usual.
                    else if (sleepTicks >= SLEEP_TICKS && (!sleepTime() || foundCover.restingAtBench())) wakeAt = sleepTicks + stretch;
                }
                if (wakeAt > 0 && sleepTicks >= wakeAt) {
                    // The sleep ran past a full one only because its sleep time was still on, so it got up at that time's end (dawn; dusk for a
                    // house body). [sleep] end is the one line a playtest asks "why did it wake" of.
                    String cause = wakeCause != null ? wakeCause : sleepTicks > SLEEP_TICKS + stretch ? "rested_dawn" : "rested";
                    GameplayLog.event(entity,"sleep","end source=life cause={} night={} ticks={} energy={} nutrition={} stretched={}",
                            cause,level().isNight(),sleepTicks,energy,nutrition,sleepTicks > SLEEP_TICKS);
                    temperature.stop();
                    boolean leaving = foundCover.leaveAfterSleep();
                    if (!leaving) foundCover.stop();
                    task = leaving ? Task.FOUND_COVER : Task.NONE; restWait = restInterval(); resetSleep();
                    if(!entity.family().woke())entity.showReaction("neutral", "awake", 60);
                }
            }
            default -> { }
        }
    }
    /**
     * The pit trip's last steps: reach the bank cell, step to its brink and turn the back to the pit. False while still on the way
     * (the caller waits); after 60 ticks without reaching the brink the member lets go where it stands on the bank.
     */
    private boolean pitBankReady() {
        boolean resident = entity.settlement().duties().pit().slave();
        if (!resident && targetBlock.getY() <= toiletPitCell.getY()) { stopGoal(); return false; }
        var bank = Vec3.atBottomCenterOf(targetBlock);
        if (!entity.onGround() || entity.position().subtract(bank).horizontalDistanceSqr() > 1.0) {
            if (pursuitTicks % 10 == 1 && !travel(targetBlock, 1)) { GroupPits.refuse(entity, "no_path", targetBlock); stopGoal(); }
            return false;
        }
        boolean onStand = targetBlock.equals(entity.blockPosition());
        // A timeout can relax the brink alignment only after reaching the rim cell itself.
        if (toiletTicks == 0 && (pitEdgeTicks <= 60 || !onStand && !resident)) {
            var edge = GroupPits.edgePoint(entity, targetBlock, toiletPitCell);
            if (!SurfaceApproach.walk(entity, edge, 1.0) && ++pitEdgeTicks <= 60) return false;
        }
        if (!resident && !onStand) return false;
        SurfaceApproach.halt(entity);
        float away = SurfaceApproach.yaw(entity.position(), entity.position().add(targetBlock.getX() - toiletPitCell.getX(), 0, targetBlock.getZ() - toiletPitCell.getZ()));
        SurfaceApproach.face(entity, away);
        return Math.abs(Mth.wrapDegrees(entity.getYRot() - away)) <= 6;
    }
    /** A slave kept in its group's waste pit lives on what lies there (settlement/PitSlave): no food or bed errands outside it. */
    private boolean penned() { return entity.settlement().duties().pit().penned(); }

    boolean foodRouteAvailable(Vec3 source) {
        if (refusedFoodFrom == null || entity.distanceToSqr(refusedFoodFrom) >= 4) return true;
        var refused = refusedFood.get(BlockPos.containing(source));
        return refused == null || refused.distanceToSqr(source) >= .16;
    }

    private void approachFood(Vec3 source, Vec3 stand, Vec3 face) {
        SurfaceApproach.walk(entity, stand, 1.15);
        if (SurfaceApproach.lastResult(entity, stand) == SurfaceApproach.Result.BLOCKED) {
            refuseFoodRoute(source, stand, SurfaceApproach.why(entity, stand));
            return;
        }
        SurfaceApproach.face(entity, SurfaceApproach.yaw(entity.position(), face));
    }

    private void refuseFoodRoute(Vec3 source, Vec3 stand, String reason) {
        // Two blocks means a new approach origin, beyond the small motions at a blocked last step.
        if (refusedFoodFrom == null || entity.distanceToSqr(refusedFoodFrom) >= 4) {
            refusedFoodFrom = entity.position();
            refusedFood.clear();
        }
        refusedFood.put(BlockPos.containing(source), source);
        GameplayLog.limited(entity,"meal","food_route",200,"food approach refused source={} stand={} reason={}",
                GameplayLog.at(source), stand == null ? "-" : GameplayLog.at(stand), reason);
        SurfaceApproach.stopWalking(entity);
        stopGoal();
        replanNow();
    }

    private Vec3 foodWindowStand(Entity food) {
        var hit = GlassSight.glassBetween(entity, entity.getEyePosition(), food);
        if (hit == null) return null;
        var stand = GlassSight.stand(entity, food, hit.getBlockPos());
        return stand != null && TravelRange.babyDestination(entity, BlockPos.containing(stand)) ? stand : null;
    }

    private boolean seesOfferedWindowFood(Player player) {
        return !player.isSpectator() && seeksOfferedFood(player) && (LearnedFear.hungerOverrides(entity)
                || personality.fear(player.getUUID()) < BondLevels.SOCIAL_FEAR_LIMIT);
    }

    private void seekFoodAtGlass() {
        if (!canEat() || pursuitTicks > 240 || !foodGlassTarget.isAlive() || foodGlassTarget.level() != level()
                || !(foodGlassTarget instanceof ItemEntity item ? seeksFood(item.getItem()) : seesOfferedWindowFood((Player) foodGlassTarget))) {
            stopGoal();
            replanNow();
            return;
        }
        if (entity.motor().owner() == foodGlass) return;
        if (foodGlassStand != null) {
            if (SurfaceApproach.walk(entity, foodGlassStand, 1.15)) {
                foodGlassStand = null;
                var press = GlassPress.toward(entity, foodGlassTarget);
                if (press != null && entity.motor().run(foodGlass, press)) return;
            } else if (SurfaceApproach.lastResult(entity, foodGlassStand) != SurfaceApproach.Result.BLOCKED) return;
        }
        stopGoal();
        replanNow();
    }

    private void endFoodGlass() {
        if (task != Task.FOOD_GLASS) return;
        foodGlassTarget = null;
        foodGlassStand = null;
        foodGlassAfter = level().getGameTime() + FOOD_GLASS_COOLDOWN;
        entity.motor().cancel(foodGlass, "food_glass_ended");
        SurfaceApproach.halt(entity);
    }

    private void seekFood() {
        if (!canEat() || !EventHooks.canEntityGrief(level(), entity) || pursuitTicks > 240) { stopGoal(); return; }
        if (targetFood != null && (!targetFood.isAlive() || targetFood.hasPickUpDelay() || !seeksFood(targetFood.getItem()))) targetFood = null;
        if (targetFood != null) {
            entity.getLookControl().setLookAt(targetFood, 20, 20);
            if (ItemContact.reaches(entity, targetFood)) {
                if (!WildCards.mayTakeFood(entity,targetFood)) { entity.getNavigation().stop(); return; }
                var rivals = level().getEntitiesOfClass(YukkuriEntity.class, targetFood.getBoundingBox().inflate(1.5), other ->
                        other != entity && other.isAlive() && other.life().canEat() && entity.hasLineOfSight(other));
                int wait=nutrition<15?0:queueBudget;
                // First come eats: wait only behind a rival that has queued longer, never behind one that arrived later.
                boolean behindRival = rivals.stream().anyMatch(rival -> rival.life().queuedTicks() > queueTicks);
                if (behindRival && queueTicks++ < wait) {
                    entity.getNavigation().stop(); if (queueTicks == 1) {personality.decided("wait_food");entity.showReaction("neutral", "wait_turn", wait);} return;
                }
                if (takeGroundFood(targetFood)) {
                    if (!useBowlNicely && targetBlock != null
                            && level().getBlockEntity(targetBlock) instanceof CareBlockEntity bowl && bowl.hasFood(this::seeksFood)) {
                        GameplayLog.event(entity, "lesson", "bowl skipped for loose food bowl={}", targetBlock);
                        entity.pet().lessons().performed(PetSkill.FOOD_BOWL, false);
                    }
                    if (!rivals.isEmpty() && wait < 35 && nutrition>15) {
                        for (var rival : rivals) WildCards.competing(entity, rival);
                        entity.showReaction("angry", "mine", 80);
                        personality.decided("claim_food");
                        for(var rival:rivals)rival.mind().add(3, "food_competition");
                        for (YukkuriEntity rival : rivals) if (rival.life().canRecognize(entity)) YukkuriWorldData.get(level()).relate(rival.getUUID(), entity.getUUID(), -3);
                    }
                }
            } else {
                Vec3 source = targetFood.position();
                approachFood(source, ItemContact.approach(entity, source), source);
            }
        } else if (targetBlock != null && level().getBlockEntity(targetBlock) instanceof CareBlockEntity bowl && bowl.hasFood(this::seeksFood)) {
            Vec3 source = Vec3.atBottomCenterOf(targetBlock);
            if (bowlStand == null) bowlStand = ItemContact.bowlStand(entity, targetBlock);
            if (bowlStand == null) {
                refuseFoodRoute(source, null, "no_reachable_bowl_face");
                return;
            }
            if (ItemContact.reaches(entity, bowlStand.contact(), .06)) {
                if (useBowlNicely) {
                    boolean taken = bowl.handFood(this::seeksFood, one -> {
                        if (!beginMeal(one, null, false)) return false;
                        meal.bowlMeal();
                        GameplayLog.event(entity, "meal", "pickup item={} count={} source={} holder=mouth", one.getItem(), one.getCount(), bowl.getBlockPos());
                        return true;
                    });
                    if (!taken) stopGoal();
                } else {
                    entity.showReaction("surprise", "mine", 45);
                    if (bowl.handFood(this::seeksFood, one -> {
                        var dropped = ItemContact.release(entity, one, 0);
                        if (dropped == null) return false;
                        targetFood = dropped;
                        GameplayLog.event(entity, "meal", "bowl spilled item={} source={} drop={} count={}",
                                one.getItem(), bowl.getBlockPos(), dropped.getUUID(), one.getCount());
                        return true;
                    })) {
                        entity.pet().lessons().performed(PetSkill.FOOD_BOWL, false);
                        targetBlock = null;
                    } else stopGoal();
                }
            } else {
                approachFood(source, bowlStand.feet(), bowlStand.contact());
            }
        } else stopGoal();
    }
    /** A serving already at mouth reach can draw a complaint. */
    private void refuseReachableServing() {
        for (var item : level().getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(.6), item ->
                item.isAlive() && !item.hasPickUpDelay() && acceptsFood(item.getItem()) && entity.hasLineOfSight(item)
                        && ItemContact.reaches(entity, item))) {
            if (meal.refuseAtReach(item.getItem(), null)) return;
        }
        var feet = entity.blockPosition();
        for (var at : BlockPos.betweenClosed(feet.offset(-1, -1, -1), feet.offset(1, 1, 1))) {
            if (!(level().getBlockEntity(at) instanceof CareBlockEntity bowl)) continue;
            var food = bowl.peekFood(this::acceptsFood);
            if (food.isEmpty()) continue;
            var stand = ItemContact.bowlStand(entity, at);
            if (stand != null && ItemContact.reaches(entity, stand.contact(), .06) && meal.refuseAtReach(food, null)) return;
        }
    }

    private boolean takeGroundFood(ItemEntity food) {
        var one = food.getItem().copyWithCount(1);
        if (!beginMeal(one, null, false)) return false;
        var remaining = food.getItem().copy();
        remaining.shrink(1);
        if (remaining.isEmpty()) food.discard(); else food.setItem(remaining);
        GameplayLog.event(entity, "meal", "pickup item={} count=1 source={} remaining={} holder=mouth",
                one.getItem(), food.getUUID(), remaining.getCount());
        WildCards.foodTaken(entity, food);
        return true;
    }
    private boolean clearContact(BlockPos pos) {
        return level().clip(new ClipContext(entity.getEyePosition(), Vec3.atCenterOf(pos),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity)).getBlockPos().equals(pos)
                || entity.blockPosition().distManhattan(pos) <= 1;
    }
    public void stopGoal() {
        accountEnvironmentalWake();
        if(task==Task.SLEEP&&sleepTicks>=20)GameplayLog.limited(entity,"sleep","stop",200,
                "stopped source=life ticks={} energy={} nutrition={} panic={} water={} rain={}",
                sleepTicks,energy,nutrition,entity.isPanicking(),entity.isInWaterOrBubble(),level().isRainingAt(entity.blockPosition()));
        temperature.stop();
        foundCover.stop();
        garbage.stop();
        entity.wintering().stopMeal();
        endFoodGlass();
        task = Task.NONE; targetFood = null; targetBlock = null; toiletPitCell = null; pitEdgeTicks = 0; resetSleep(); toiletTicks = 0; planWait = 30;
        entity.getNavigation().stop();
    }

    public boolean dryStand(BlockPos pos){ return home.dryStand(pos); }
    private boolean restable(BlockPos pos){ return home.restable(pos); }
    public boolean covered(BlockPos pos){ return home.covered(pos); }
    private boolean rainOutside(){ return home.rainOutside(); }
    public BlockPos entrance(BlockPos nest){ var at=home.entrance(nest); return at==null?null:BlockPos.containing(at); }
    /** The supported stand at its real coordinates, for callers that walk to it. */
    public Vec3 homeApproach(BlockPos nest) {
        var bed = NestDoorway.bed(entity, nest);
        if (bed != null) return NestDoorway.stand(entity, bed);
        var entrance = home.entrance(nest);
        var near = entrance == null ? Vec3.atBottomCenterOf(nest) : entrance;
        return SurfaceApproach.restingFloor(entity, near.add(0, .1, 0));
    }
    public boolean assignHome(GlobalPos selected){ return home.assignHome(selected); }
    public Vec3 homeEntrance(BlockPos nest) { return home.entrance(nest); }
    public boolean assignNaturalHome(BlockPos selected){ return home.assignNaturalHome(selected); }
    /** Check every route before changing any address, then release the former den beds. */
    public static boolean assignNaturalHomeTogether(BlockPos selected,List<YukkuriEntity> members) {
        if(members.stream().anyMatch(y->!y.life().home.canAssignNaturalHome(selected)))return false;
        for(var member:members)member.life().home.commitNaturalHome(selected);
        for(var member:members)member.habitat().release();
        return true;
    }
    public void releaseHome(){ home.releaseHome(); }
    /** An individual without an address takes the nearest free nest its stage and body fit (also the shared nest's release of a grown child). */
    boolean claimNearestNest(){ return home.claimNearestNest(); }
    public BlockPos homeStand(){ return home.homeStand(); }
    private boolean maintainHome(){ return home.maintainHome(); }
    private BlockPos nearestCare(CareBlock.Kind kind, boolean requireFood){ return home.nearestCare(kind, requireFood); }
    private boolean seesWater(BlockPos water){ return home.seesWater(water); }
    private BlockPos drinkStand(BlockPos water){ return home.drinkStand(water); }
    boolean hasReachableDrink(){ return home.hasReachableDrink(); }
    private BlockPos nearestDrink(){ return home.nearestDrink(); }
    boolean drinkableWater(BlockPos at) {
        if (!level().hasChunkAt(at)) return false;
        var water = level().getFluidState(at);
        return water.is(FluidTags.WATER) && (!FoundShelter.streetUser(entity) || water.isSource());
    }
    private boolean wantsStreetRinse() {
        return hygiene < 60 && nutrition >= 30 && !rainOutside() && FoundShelter.streetUser(entity)
                && level().getGameTime() >= streetDrinkAfter;
    }
    /** The shallow pool of a planned site: within 32 blocks the whole community drinks there. */
    private BlockPos siteWater(){ return home.siteWater(); }
    private Path path(BlockPos target, boolean escapeWater) {
        // A nest occupies the feet cell with a fractional-height floor. Vanilla's walk node
        // sits at floor(y + collisionShape.max(Y) + .5), which for a low nest is the nest cell itself.
        BlockState targetState = level().getBlockState(target);
        if(targetState.getBlock() instanceof CareBlock care&&care.kind()==CareBlock.Kind.NEST) {
            VoxelShape shape = targetState.getCollisionShape(level(), target);
            if(!shape.isEmpty()) target = new BlockPos(target.getX(), (int)Math.floor(target.getY() + shape.max(Direction.Axis.Y) + .5), target.getZ());
        }
        float old = entity.getPathfindingMalus(PathType.WATER);
        if (escapeWater) entity.setPathfindingMalus(PathType.WATER, 0);
        Path path;
        try { path = entity.getNavigation().createPath(target, 0); }
        finally { if (escapeWater) entity.setPathfindingMalus(PathType.WATER, old); }
        if (path == null || !path.canReach()) return null;
        boolean leftWater = !escapeWater;
        for (int i=0; i<path.getNodeCount(); i++) {
            var node = path.getNode(i);
            boolean wet = !level().getFluidState(new BlockPos(node.x,node.y,node.z)).isEmpty();
            if (wet && leftWater) return null;
            if (!wet) leftWater = true;
        }
        return path;
    }
    /** Set only while a household moves into a home it has already inspected: the pathfinder's short range must not veto a walk of a few dozen blocks. */
    private boolean trustRoutes;
    public void trustRoutes(boolean value) { trustRoutes = value; }
    /** Answers of the last {@link #REACH_MEMO} ticks from this block: asking again about an unchanged target costs no new route plan. */
    private static final int REACH_MEMO = 40;
    private final java.util.HashMap<Long, Boolean> reachMemo = new java.util.HashMap<>();
    private BlockPos reachFrom;
    private long reachSince;
    public boolean reachable(BlockPos target, boolean escapeWater) {
        if (trustRoutes) return true;
        long now = level().getGameTime();
        var here = entity.blockPosition();
        if (!here.equals(reachFrom) || now - reachSince > REACH_MEMO || reachMemo.size() >= 32) { reachMemo.clear(); reachFrom = here; reachSince = now; }
        return reachMemo.computeIfAbsent(target.asLong() * 2 + (escapeWater ? 1 : 0), key -> path(target, escapeWater) != null);
    }

    /** Full dry route for a land march. Planning leaves the actor's current movement alone. */
    public Path dryTravelPath(Set<BlockPos> targets) {
        var navigation = (YukkuriGroundNavigation)entity.getNavigation();
        return dryTravel(navigation.createTravelPath(targets));
    }

    public boolean dryRouteFrom(BlockPos from, Set<BlockPos> targets) {
        var navigation = (YukkuriGroundNavigation)entity.getNavigation();
        return dryStand(from) && dryTravel(navigation.surveyTravelPath(from, targets)) != null;
    }

    private Path dryTravel(Path route) {
        if (route == null || !route.canReach()) return null;
        for (int i = 0; i < route.getNodeCount(); i++) {
            var node = route.getNodePos(i);
            if (!level().hasChunkAt(node) || !level().getFluidState(node).isEmpty()
                    || !level().getFluidState(node.below()).isEmpty()) return null;
        }
        return route;
    }
    /** Walk to a dry target on land: full paths within range, partial paths farther away until the full check comes near.
     * A step or short fall continues on landing; a genuinely unreachable destination still fails. */
    public boolean travel(BlockPos target, double speed) {
        if (entity.getNavigation() instanceof YukkuriGroundNavigation ground && !ground.canPlanApproach()) return true;
        if (target.distSqr(entity.blockPosition()) <= GroupPits.NEAR * GroupPits.NEAR) return navigate(target, speed, false);
        return entity.getNavigation().moveTo(target.getX() + .5, target.getY(), target.getZ() + .5, speed);
    }
    public boolean navigate(BlockPos target, double speed, boolean escapeWater) {
        // This is a movement request, unlike reachable(): failure must not leave an earlier destination running.
        return entity.getNavigation().moveTo(path(target, escapeWater), speed);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); tag.putInt("Version", 1);
        tag.putFloat("Nutrition", nutrition); tag.putFloat("Energy", energy); tag.putFloat("Hygiene", hygiene);
        tag.putFloat("Bladder", bladder); tag.putFloat("Wetness", wetness); tag.putLong("AgeTicks", ageTicks);
        tag.putInt("ImmersionTicks", immersionTicks);
        tag.put("Temperature", temperature.save());
        tag.put("Wintering", entity.wintering().save());
        tag.putInt("ExerciseReliefTicks",exerciseReliefTicks);
        tag.putFloat("Dryness",dryness);doses.save(tag);
        tag.putInt("HungerProgress", metabolismTicks); tag.putInt("EnergyProgress", energyTicks); tag.putInt("HygieneProgress", hygieneTicks);
        tag.putInt("StarvingTicks", starvingTicks);
        tag.putInt("RestWait", restWait); tag.putInt("SocialWait", socialWait);
        meal.save(tag);
        home.save(tag);
        accessory.save(tag);
        tag.putFloat("Pickiness", pickiness);
        tag.put("Personality", personality.save()); return tag;
    }
    private static float bounded(CompoundTag tag, String name, float fallback) {
        float value = tag.contains(name, Tag.TAG_ANY_NUMERIC) ? tag.getFloat(name) : fallback;
        return Float.isFinite(value) ? Math.clamp(value, 0, 100) : fallback;
    }
    /** Aquatic Marisa still drowns, but takes three ordinary immersion intervals to lose each health point. */
    private int immersionInterval() { return entity.profile().aquatic() ? 60 : 20; }
    public void load(CompoundTag tag) {
        nutrition = bounded(tag,"Nutrition",60); energy = bounded(tag,"Energy",80); hygiene = bounded(tag,"Hygiene",100);
        bladder = bounded(tag,"Bladder",0); wetness = bounded(tag,"Wetness",0); ageTicks = Math.max(0,tag.getLong("AgeTicks"));
        // Old saves recorded a 0..19 phase of the universal twenty-tick clock.  Keep that phase intact while
        // allowing new aquatic saves to retain the longer sixty-tick phase.
        immersionTicks = Math.clamp(tag.getInt("ImmersionTicks"), 0, immersionInterval()-1);
        temperature.load(tag.getCompound("Temperature"));
        entity.wintering().load(tag.getCompound("Wintering"));
        exerciseReliefTicks=Math.clamp(tag.getInt("ExerciseReliefTicks"),0,1200);
        dryness=bounded(tag,"Dryness",0);doses.load(tag);
        metabolismTicks = Math.clamp(tag.getInt("HungerProgress"),0,23999); energyTicks = Math.clamp(tag.getInt("EnergyProgress"),0,23999);
        starvingTicks = Math.clamp(tag.getInt("StarvingTicks"),0,Math.max(1200,YukkuriConfig.HUNGER_INTERVAL.get()*5)-1);
        fatigueTicks=0; napReady=false;
        hygieneTicks = Math.clamp(tag.getInt("HygieneProgress"),0,23999); restWait = Math.clamp(tag.getInt("RestWait"),0,1200);
        socialWait = Math.clamp(tag.getInt("SocialWait"),0,2400);
        meal.load(tag);
        home.load(tag); accessory.load(tag);
        pickiness=bounded(tag,"Pickiness",Taste.innate(entity.getUUID()));
        personality=new YukkuriPersonality(entity); personality.load(tag.getCompound("Personality"));
        task=Task.NONE; targetFood=null; targetBlock=null; refusedFood.clear(); refusedFoodFrom=null; resetSleep(); toiletTicks=0; homeWait=20; planWait=0;
    }
}
