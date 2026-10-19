package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.body.DetachedItems;
import io.github.longshou.mcyukkuri.body.BodyFood;
import io.github.longshou.mcyukkuri.body.MouthPlacement;
import io.github.longshou.mcyukkuri.event.WildCards;
import io.github.longshou.mcyukkuri.headwear.Recognition;
import io.github.longshou.mcyukkuri.interaction.ItemContact;
import io.github.longshou.mcyukkuri.play.PlayBlock;
import io.github.longshou.mcyukkuri.rare.RareForms;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;

/** Loaded-time physiology and interruptible, server-authoritative life activities. */
public final class YukkuriLife {
    private enum Task { NONE, FOOD, WINTER_FOOD, HOME, TOILET, GROUND_TOILET, SLEEP, DRINK, TEMPERATURE }
    private final YukkuriEntity entity;
    private final TemperatureLife temperature;
    private YukkuriPersonality personality;
    private float nutrition = 60, energy = 80, hygiene = 100, bladder, wetness;
    private long ageTicks;
    private int metabolismTicks, energyTicks, hygieneTicks;
    private int immersionTicks;
    private final LifeMeal meal = new LifeMeal(this);
    private final LifeHome home = new LifeHome(this);
    private int homeWait, planWait, restWait = 200, sleepTicks, toiletTicks;
    private Task task = Task.NONE;
    private ItemEntity targetFood;
    private BlockPos targetBlock;
    private int pursuitTicks, queueTicks, winterNeedCheckAt;
    private final LifeAccessory accessory = new LifeAccessory(this);
    private int socialWait;
    private float dryness;
    private int drinkTicks;
    private final LifeDoses doses = new LifeDoses(this);
    private int exerciseReliefTicks;
    private boolean toiletAccident;
    private final HatToilet hatToilet = new HatToilet(this);

    public YukkuriLife(YukkuriEntity entity) { this.entity = entity; temperature = new TemperatureLife(entity); personality = new YukkuriPersonality(entity.getUUID()); }
    YukkuriEntity entity() { return entity; }
    HomeProvider homeProvider() { return HomeProvider.forEntity(entity); }
    void emptyBladder() { bladder = 0; }
    void relieveBladder(float amount) { bladder -= amount; }
    LifeDoses doses() { return doses; }
    void addNutrition(float amount) { nutrition = Math.min(100, nutrition + amount); }
    void addBladder(float amount) { bladder = Math.min(100, bladder + amount); }
    void increaseDryness(float amount) { dryness = Math.min(100, dryness + amount); }
    void relieveDryness(float amount) { dryness = Math.max(0, dryness - amount); }
    void leaked() { bladder=Math.max(0,bladder-40);hygiene=Math.max(0,hygiene-15); }
    void beginConsumption() { sleepTicks=0;task=Task.NONE; }
    void beginDrugSleep() { task=Task.SLEEP;sleepTicks=0; }
    void beginDoseMeal(ItemStack one, UUID player, boolean returnBottle) {
        meal.beginDose(one, player, returnBottle);
    }
    void deferHomeMaintenance() { homeWait = 200; }
    void finishConsumption() { task = Task.NONE; planWait = 20; }
    public TemperatureLife temperature() { return temperature; }
    public YukkuriPersonality personality() { return personality; }
    public float nutrition() { return nutrition; }
    public boolean spendNutrition(float amount) {
        if(!Float.isFinite(amount)||amount<0||nutrition<amount)return false;nutrition-=amount;return true;
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
    public boolean completeAssistedFirstToilet(YukkuriEntity parent){ return hatToilet.completeAssistedFirstToilet(parent); }
    public boolean hatToiletReleasing(){ return hatToilet.hatToiletReleasing(); }
    /** Supported hat passengers can relieve themselves without turning a bodily need into a shore trip. */
    public boolean toiletInHat(HatBoatEntity boat){ return hatToilet.toiletInHat(boat); }
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
    public long ageTicks() { return ageTicks; }
    public GlobalPos home(){ return home.home(); }
    public boolean naturalHome(){ return home.naturalHome(); }
    public ItemStack mouth(){ return meal.mouth(); }
    /** A non-item mouthful taken from a living body or remains. */
    public BodyFood.Bite mouthBodyFood() { return meal.mouthBodyFood(); }
    public boolean hasMouthFood() { return meal.hasMouthFood(); }
    public int mealTicks(){ return meal.mealTicks(); }
    public boolean hasAccessory(){ return accessory.hasAccessory(); }
    /** The hat is simply not worn (a young who has none yet): no token, nothing to be returned. */
    public void removeAccessory(){ accessory.removeAccessory(); }
    public int socialWait() { return socialWait; }
    public void deferSocial(int ticks) { socialWait = Math.max(socialWait, ticks); }
    /** Recognition by accessory, or past a missing one by intellect alone (headwear.Recognition; kin are no exception). */
    public boolean canRecognize(YukkuriEntity target) { return Recognition.recognizes(entity, target); }
    public boolean needsFood(){ return meal.needsFood(); }
    private boolean edible(ItemStack food){ return meal.edible(food); }
    public boolean acceptsFood(ItemStack food){ return meal.acceptsFood(food); }
    private boolean readyForFood(){ return meal.readyForFood(); }
    /** Food gathered or offered as a normal meal is for a child that can chew independently. */
    public boolean canEat(){ return meal.canEat(); }
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
    public boolean hasUrgentNeed() { return nutrition < 30 || energy < 25 || bladder >= 90 && !constipated() || entity.isInWaterOrBubble() || entity.isOnFire() || wetness > 40; }
    public boolean isSleeping() { return task == Task.SLEEP && sleepTicks >= 20 && sleepTicks < 620 || entity.house().sleeping(); }
    /** The house goal owns the physical entry and waking sequence; only real grounded rest restores energy. */
    public void restInHouse() {
        if (!entity.level().isClientSide && entity.house().sleeping() && !entity.isPanicking()
                && !entity.isInWaterOrBubble() && !entity.isOnFire() && mealTicks() == 0) energy = Math.min(100, energy + .5f);
    }
    /** Hat sleep uses the same physiology and completed-sleep growth as a terrestrial nest. */
    public void restInHat(HatBoatEntity boat) {
        if (!boat.protects(entity) || entity.isPanicking() || entity.life().mealTicks() > 0
                || boat.flooding() > 0 || level().isRainingAt(entity.blockPosition())) { wakeInHat(); return; }
        if (!level().isNight() && energy > restThreshold() && task != Task.SLEEP) return;
        if (task != Task.SLEEP) { task = Task.SLEEP; sleepTicks = 0; targetBlock = null; targetFood = null; }
        sleepTicks = Math.min(600, sleepTicks+1);
        if (sleepTicks == 20) entity.speak("sleep");
        if (sleepTicks >= 20) energy = Math.min(100, energy+.5f);
        doses.tickDrugSleep();
        if (drugSleep() == 0 && (nutrition < 15 || !level().isNight() && sleepTicks >= 220 && energy >= 95)) wakeInHat();
    }
    public void wakeInHat() {
        if (task != Task.SLEEP) return;
        boolean rested = sleepTicks >= 220;
        task = Task.NONE; sleepTicks = 0; restWait = restInterval();
        if (rested && !entity.family().woke()) entity.showReaction("neutral", "awake", 40);
    }
    public boolean wantsCrawl() {
        return task == Task.FOOD && mealTicks() == 0 && targetFood != null && targetFood.isAlive()
                && entity.distanceToSqr(targetFood) < 1;
    }
    public String activity() {
        if (mealTicks() > 0) {
            if (meal.preparedMeal()) return mealTicks() < 53 ? Behaviors.RECEIVE_MASH : Behaviors.MEAL_FINISH;
            return mealTicks() < 10 ? Behaviors.TAKE_FOOD : mealTicks() < 45 ? Behaviors.CHEW : mealTicks() < 53 ? Behaviors.SWALLOW : Behaviors.MEAL_FINISH;
        }
        if (task == Task.SLEEP) return sleepTicks < 20 ? Behaviors.SLEEP_ENTER : sleepTicks >= 620 ? Behaviors.WAKE : Behaviors.SLEEP;
        return switch (task) { case FOOD -> Behaviors.SEEK_FOOD; case WINTER_FOOD -> Behaviors.SHELTER_STOCK; case HOME -> Behaviors.RETURN_HOME; case TOILET -> toiletTicks > 0
                ? Behaviors.TOILET : Behaviors.SEEK_TOILET;
            case GROUND_TOILET -> toiletTicks > 0 ? toiletAccident ? Behaviors.TOILET_ACCIDENT : Behaviors.GROUND_TOILET : Behaviors.SEEK_TOILET;
            case DRINK -> drinkTicks>0?Behaviors.DRINK:Behaviors.SEEK_WATER; case TEMPERATURE -> temperature.activity(); default -> Behaviors.IDLE; };
    }
    ServerLevel level() { return (ServerLevel)entity.level(); }
    /** Rain or leaks softening the skin: attrition, not an attack and not drowning (a bite of the death record said "drown" for it). */
    public static final ResourceKey<DamageType> SOAKING =
            ResourceKey.create(Registries.DAMAGE_TYPE, MCYukkuri.id("soaking"));
    public static boolean isSoaking(DamageSource source) { return source.typeHolder().is(SOAKING); }
    private DamageSource soaking() {
        return new DamageSource(entity.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(SOAKING));
    }
    private int restThreshold() { return entity.family().needsMash() ? 65 : 35; }
    private int restInterval() { return entity.family().needsMash() ? 200 : 1200; }
    boolean restTime() { return entity.profile().houseBody() ? !level().isNight() : level().isNight() || RareForms.summerDormant(entity); }

    public void tick() {
        if (entity.level().isClientSide || !entity.isAlive()) return;
        // Body contact with water hurts every species, even with AI disabled. A dry hat is not immersion.
        if (!entity.isInWaterOrBubble()) immersionTicks = 0;
        else if (++immersionTicks >= immersionInterval()) {
            immersionTicks = 0;
            entity.hurt(level().damageSources().drown(), 1);
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
        if (++metabolismTicks >= Math.max(20, YukkuriConfig.HUNGER_INTERVAL.get() / (entity.family().needsMash() ? 2 : 1) * (entity.profile().hungerIntervalMultiplier()))) {
            metabolismTicks = 0; nutrition = Math.max(0, nutrition - (isSleeping() ? 1 : 2));
            if (nutrition == 0) entity.hurt(level().damageSources().starve(), 1);
        }
        if (++energyTicks >= Math.max(20,
                YukkuriConfig.ENERGY_INTERVAL.get() / (entity.family().needsMash() ? 2 : 1))) { energyTicks = 0; if (!isSleeping()) energy = Math.max(0, energy - entity.species().drain()); }
        if (++hygieneTicks >= YukkuriConfig.HYGIENE_INTERVAL.get()) { hygieneTicks = 0; hygiene = Math.max(0, hygiene - 1); }
        if (ageTicks % 20 == 0) {
            if (entity.isInWaterOrBubble()) wetness = Math.min(100, wetness + entity.subspecies().waterGain(8));
            else if (entity.habitat().leaking() || level().isRainingAt(entity.blockPosition())) wetness = Math.min(100, wetness + entity.subspecies().waterGain(2));
            else wetness = Math.max(0, wetness - 3);
            if (!entity.isInWaterOrBubble() && wetness >= YukkuriConfig.WET_DAMAGE_THRESHOLD.get() && ageTicks % 100 == 0)
                entity.hurt(soaking(), 1);
        }
        temperature.tick();
        if (!entity.isAlive()) return;
        if (entity.isPanicking() || entity.isOnFire() || entity.isInWaterOrBubble()) {
            if (mealTicks() > 0 || task != Task.NONE || sprayDelay()>0 || drugSleep()>0) interrupt();
            return;
        }
        doses.tickSpray();
        if (!meal.tickChew()) return;
        if (homeWait == 0) { homeWait = 200; if(maintainHome())homeWait=20; }
    }

    /** Caller transfers exactly one real item before invoking this method. */
    public boolean beginMeal(ItemStack transferred, UUID fromPlayer, boolean alreadyShared){ return meal.beginMeal(transferred, fromPlayer, alreadyShared); }
    /** A player can offer an already prepared serving, but a dependent child still cannot pick it up alone. */
    public boolean beginPreparedMeal(ItemStack prepared, UUID fromPlayer){ return meal.beginPreparedMeal(prepared, fromPlayer); }
    /** A biological parent must physically reach the child before transferring its prepared serving. */
    public boolean beginParentMeal(YukkuriEntity caregiver, ItemStack prepared){ return meal.beginParentMeal(caregiver, prepared); }
    /** A hat passenger receives the serving through the same real mouth contact as a child ashore. */
    public boolean beginBraidMeal(YukkuriEntity caregiver, ItemStack prepared){ return meal.beginBraidMeal(caregiver, prepared); }
    private boolean startMeal(ItemStack transferred, UUID fromPlayer, boolean alreadyShared, String reaction){ return meal.startMeal(transferred, fromPlayer, alreadyShared, reaction); }
    /** Badge social (B4): a hungry peer is begging at this serving, so the bite waits mid-chew for up to {@code ticks}. False unless a bite is really in the mouth. */
    public boolean holdMeal(int ticks){ return meal.holdMeal(ticks); }
    public void releaseMeal(){ meal.releaseMeal(); }
    public boolean mealHeld(){ return meal.mealHeld(); }
    /** A bully takes the serving out of this mouth (headwear.Sanction): it eats it if it can, otherwise the bite is knocked out. */
    public boolean snatchedBy(YukkuriEntity thief){ return meal.snatchedBy(thief); }
    /** Real transfer of the held serving to a hungry peer (same rules and rewards as tryShare, but asked for by a begging scene). */
    public boolean handOverMouth(YukkuriEntity peer, String event){ return meal.handOverMouth(peer, event); }
    private void tryShare(){ meal.tryShare(); }
    public void interrupt() {
        WildCards.mealInterrupted(entity);
        temperature.stop();
        entity.dailyLife().stop();
        if (!meal.releaseForInterrupt()) return;
        meal.clearInterrupted(); doses.clearReturnBottle();
        doses.stopSedation();drinkTicks=0;
        sleepTicks = toiletTicks = 0; hatToilet.interrupt(); task = Task.NONE; targetFood = null; targetBlock = null;
        if (entity.getVehicle() instanceof HatBoatEntity boat) boat.releaseToiletSeat(entity);
        planWait = 20; entity.getNavigation().stop();
    }
    public boolean wash(UUID player) {
        if (hygiene >= 99 || entity.isInWaterOrBubble() || entity.isOnFire()) return false;
        hygiene = Math.min(100, hygiene + 40); wetness = Math.min(100, wetness + 5);
        personality.washed(player); entity.showReaction("happy", "washed", 60); return true;
    }
    public UUID takeAccessory(UUID player){ return accessory.takeAccessory(player); }
    /** Voluntarily using one's own hat as a vessel is not an accessory theft. */
    public UUID stowAccessory(){ return accessory.stowAccessory(); }
    /** A child's folded vessel retains its identity through repeated refuge and relaunch. */
    public UUID stowAccessory(UUID retainedToken){ return accessory.stowAccessory(retainedToken); }
    /** Keep the locating index with the owner; return authority remains the token on both owner and physical boat. */
    public UUID stowAccessory(UUID retainedToken,UUID boat){ return accessory.stowAccessory(retainedToken, boat); }
    public UUID takeAccessory(UUID player,UUID token){ return accessory.takeAccessory(player, token); }
    public boolean returnAccessory(UUID owner, UUID token, UUID player){ return accessory.returnAccessory(owner, token, player); }
    /** Its own accessory is whole on its head again (pieces knit together, headwear.Headwear): no token was involved. */
    public void wearAccessory(){ accessory.wearAccessory(); }
    public boolean matchesAccessory(UUID owner,UUID token){ return accessory.matchesAccessory(owner, token); }
    public boolean matchesCustody(UUID boat,UUID owner,UUID token){ return accessory.matchesCustody(boat, owner, token); }
    public void confirmCustody(UUID boat,UUID owner,UUID token){ accessory.confirmCustody(boat, owner, token); }
    public void restoreCustody(UUID boat,UUID owner,UUID token){ accessory.restoreCustody(boat, owner, token); }
    /** A stowed, token-matched physical hat is still this individual's accessory. */
    public boolean accessoryCustodied(){ return accessory.custodied(); }
    public UUID custodyBoat(){ return accessory.custodyBoat(); }
    public boolean accessoryCustodyEnded(){ return accessory.custodyEnded(); }
    public String accessoryCustodyDetail(){ return accessory.custodyDetail(); }
    public boolean relinquishCustody(UUID boat,UUID owner,UUID token,UUID player){ return accessory.relinquishCustody(boat, owner, token, player); }

    public boolean plan() {
        if (entity.family().awaitingLanding()) return false;
        if (entity.shelteredInHat()) return false;
        if (entity.predator().active()) return false;
        if (entity.isPanicking() || entity.isCommandPlaying() || entity.isInWaterOrBubble()) return false;
        if (mealTicks() > 0) return true;
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
        if (task == Task.TEMPERATURE) {
            if (temperature.canContinue()) return true;
            stopGoal(); planWait = 0;
        }
        if ((task == Task.SLEEP || task == Task.HOME) && drugSleep() == 0 && temperature.unsafeSleep() && temperature.plan()) {
            task = Task.TEMPERATURE; sleepTicks = 0; return true;
        }
        if (task != Task.NONE) return true;
        if(drugSleep()>0){task=Task.SLEEP;sleepTicks=20;return true;}
        if (planWait > 0) return false;
        planWait = 20;
        if(dryness>=40||spicyTicks()>0){targetBlock=nearestDrink();if(targetBlock!=null){task=Task.DRINK;drinkTicks=pursuitTicks=0;return true;}}
        // Getting out of the rain is the status module's weather reaction (Reactions.WeatherGoal); here only the walk back to the own nest.
        if (rainOutside() && !covered(entity.blockPosition()) && nutrition >= 30 && !entity.settlement().underway()) {
            targetBlock = homeStand();
            if (targetBlock != null && covered(targetBlock)) { task = Task.HOME; pursuitTicks = 0; return true; }
        }
        // A full bowel must not be starved by a permanently stocked food bowl.
        if (bladder >= 70 && planToilet()) return true;
        // Executable urgent needs above still interrupt a stocked nest, shelter or egg-care routine.
        switch (homeProvider().beforeFood(this)) {
            case TEMPERATURE -> { task = Task.TEMPERATURE; return true; }
            case ROUTINE -> { return false; }
            case CONTINUE -> { }
        }
        if (canEat() && entity.wintering().stockedHome() != null) {
            if (temperature.unsafeSleep() && temperature.plan()) { task = Task.TEMPERATURE; return true; }
            // Activate pending care before this new food route could monopolize the higher-priority life goal.
            if (!temperature.unsafeSleep() && !hasUrgentNeed() && entity.family().canUse()) return false;
            if (entity.wintering().planMeal()) { task = Task.WINTER_FOOD; pursuitTicks = 0; winterNeedCheckAt = 20; return true; }
        }
        if (canEat() && EventHooks.canEntityGrief(level(), entity)) {
            targetFood = level().getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(8), item ->
                    item.isAlive() && !item.hasPickUpDelay() && acceptsFood(item.getItem())).stream()
                    .sorted(Comparator.comparingInt((ItemEntity item) -> FoodTaste.rank(item.getItem()))
                            .thenComparingDouble(entity::distanceToSqr))
                    .filter(item -> entity.distanceToSqr(item)<.5 && entity.hasLineOfSight(item) || reachable(item.blockPosition(),false))
                    .findFirst().orElse(null);
            targetBlock = nearestCare(CareBlock.Kind.BOWL, true);
            if (targetFood != null || targetBlock != null) { task = Task.FOOD; pursuitTicks = queueTicks = 0; return true; }
        }
        if (bladder < 70 && planToilet()) return true;
        if (temperature.plan()) { task = Task.TEMPERATURE; return true; }
        if (restWait == 0 && (energy <= restThreshold() || restTime())
                && homeProvider().ordinaryRestAllowed(this)) {
            // On a migration there is no burrow to walk back to: rest on dry ground where it stands, then walk on.
            if (entity.settlement().underway()) {
                if (dryStand(entity.blockPosition()) && restable(entity.blockPosition())) { task = Task.SLEEP; sleepTicks = 0; return true; }
                return false;
            }
            targetBlock = homeStand();
            if (targetBlock == null && entity.habitat().vagrant() && homeProvider().temporaryCoverAllowed()) targetBlock = nearestDryCover();
            if (targetBlock != null && (entity.distanceToSqr(Vec3.atBottomCenterOf(targetBlock)) > .16 || !restable(entity.blockPosition()))) { task = Task.HOME; pursuitTicks = 0; return true; }
            if (targetBlock != null || energy <= 10 || entity.family().needsMash() && entity.childhood().landed()
                    && dryStand(entity.blockPosition()) && restable(entity.blockPosition())) { task = Task.SLEEP; sleepTicks = 0; return true; }
        }
        return false;
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
    private boolean planToilet() {
        if (!entity.childhood().firstToiletDone() || bladder < 40 || constipated()) return false;
        targetBlock = nearestCare(CareBlock.Kind.TOILET, false);
        if (targetBlock != null) {
            task = Task.TOILET; toiletTicks = pursuitTicks = 0; toiletAccident = false; return true;
        }
        boolean pet = (Custody.view(entity).owner() != null)
                || home() != null && !naturalHome();
        if (bladder < (pet ? 90 : 70)) return false;
        targetBlock = null;
        if (!pet) {
            for (BlockPos candidate : BlockPos.betweenClosed(entity.blockPosition().offset(-4,0,-4),entity.blockPosition().offset(4,0,4))) {
                if (home() != null && home().dimension().equals(level().dimension()) && candidate.distSqr(home().pos()) < 4) continue;
                if (!dryStand(candidate) || !reachable(candidate, false)) continue;
                if (!level().getEntitiesOfClass(ItemEntity.class,new AABB(candidate).inflate(1),i->acceptsFood(i.getItem())).isEmpty()) continue;
                if (targetBlock == null || candidate.distSqr(entity.blockPosition()) < targetBlock.distSqr(entity.blockPosition())) targetBlock = candidate.immutable();
            }
        }
        toiletAccident = pet || targetBlock == null;
        if (targetBlock == null && bladder >= 90 && dryStand(entity.blockPosition())) targetBlock = entity.blockPosition();
        if (targetBlock == null) return false;
        task = Task.GROUND_TOILET; toiletTicks = pursuitTicks = 0; return true;
    }
    public void tickGoal() {
        if (mealTicks() > 0) { entity.getNavigation().stop(); return; }
        if (entity.isPanicking() || entity.isCommandPlaying() || entity.isInWaterOrBubble()) { stopGoal(); return; }
        pursuitTicks++;
        switch (task) {
            case TEMPERATURE -> {
                if (!temperature.tickGoal()) stopGoal();
                else if (restWait == 0 && (energy <= restThreshold() || restTime()) && temperature.beginSleepAtRefuge()) {
                    task = Task.SLEEP; sleepTicks = 0; targetBlock = null;
                }
            }
            case DRINK -> {
                if(targetBlock==null||!level().getFluidState(targetBlock).is(FluidTags.WATER)||pursuitTicks>240){stopGoal();return;}
                if(entity.distanceToSqr(Vec3.atCenterOf(targetBlock))<2.25&&seesWater(targetBlock)){
                    entity.getNavigation().stop();entity.getLookControl().setLookAt(targetBlock.getX()+.5,targetBlock.getY()+.4,targetBlock.getZ()+.5,20,20);
                    if(++drinkTicks>=40){
                        var pool=PlayBlock.facility(level(),targetBlock);
                        if(pool==null||pool.spendWater())drink(30);
                        task=Task.NONE;drinkTicks=0;planWait=40;entity.gameEvent(GameEvent.DRINK);
                    }
                }else if(pursuitTicks%10==1){var stand=drinkStand(targetBlock);if(stand==null||!navigate(stand,1,false))stopGoal();}
            }
            case FOOD -> seekFood();
            case WINTER_FOOD -> { if (!entity.wintering().tickMeal()) stopGoal(); }
            case HOME -> {
                if (targetBlock == null || !dryStand(targetBlock) || !restable(targetBlock) || pursuitTicks > 600 || nutrition < 30) { stopGoal(); homeWait = 0; return; }
                // Vanilla pathing ends up to ~0.55 block short of the last node, so the final step is steered
                // on the supported surface; otherwise the 0.4-block arrival test below could never be met.
                Vec3 bedCentre = Vec3.atBottomCenterOf(targetBlock);
                if (entity.distanceToSqr(bedCentre) >= .16) SurfaceApproach.walk(entity, bedCentre, 1.15);
                if (entity.distanceToSqr(bedCentre) < .16 && restable(entity.blockPosition())) {
                    SurfaceApproach.halt(entity);
                    if (restWait == 0 && (energy <= restThreshold() || restTime())) { task = Task.SLEEP; sleepTicks = 0; }
                    // Awake arrival ends the trip, even while rain continues above the roof.
                    else { task = Task.NONE; targetBlock = null; planWait = 0; }
                } else if (pursuitTicks > 60 && entity.getNavigation().isDone() && entity.distanceToSqr(bedCentre) > 4 && !reachable(targetBlock, false)) stopGoal();
            }
            case TOILET -> {
                if (!entity.childhood().firstToiletDone()) { stopGoal(); return; }
                if (targetBlock == null || !(level().getBlockEntity(targetBlock) instanceof CareBlockEntity care) || care.kind() != CareBlock.Kind.TOILET
                        || care.waste() >= care.capacity() || pursuitTicks > 240) { stopGoal(); return; }
                if (constipated() || bladder < 40 || ToiletContact.facilitySeat(entity, targetBlock) == null) { stopGoal(); return; }
                if (ToiletContact.approachFacility(entity, targetBlock)) {
                    if (++toiletTicks == 1) entity.speak("need_toilet");
                    // The stool leaves the anus at the end of the second push; the clip then relaxes for a few ticks.
                    if (toiletTicks == ToiletContact.DROP_TICKS) {
                        if (ToiletContact.facilityContact(entity, targetBlock) && care.relieve(DetachedItems.waste(entity))) {
                            bladder -= 40; entity.pet().routineCompleted(1); entity.showReaction("happy", "toilet_done", 60);
                        } else { stopGoal(); return; }
                    }
                    if (toiletTicks >= ToiletContact.RELEASE_TICKS) stopGoal();
                } else toiletTicks = 0;
            }
            case GROUND_TOILET -> {
                if (!entity.childhood().firstToiletDone()) { stopGoal(); return; }
                if (targetBlock == null || !dryStand(targetBlock) || constipated() || pursuitTicks > 240) { stopGoal(); return; }
                if (entity.onGround() && SurfaceApproach.supported(entity, entity.position()) && entity.distanceToSqr(Vec3.atBottomCenterOf(targetBlock)) <= .25) {
                    SurfaceApproach.halt(entity);
                    if (++toiletTicks == 1) entity.speak("need_toilet");
                    if (toiletTicks == ToiletContact.DROP_TICKS) {
                        // From the anus of this very model at this moment of the clip (see ToiletContact.anus).
                        if (bladder >= 40 && ToiletContact.drop(entity, ToiletContact.anus(entity, ToiletContact.DROP_TICKS))) {
                            bladder -= 40;
                            if (toiletAccident) hygiene = Math.max(0,hygiene-20);
                            entity.showReaction(toiletAccident?"teary":"happy", "toilet_done",60);
                        } else { stopGoal(); return; }
                    }
                    if (toiletTicks >= ToiletContact.RELEASE_TICKS) stopGoal();
                } else { toiletTicks=0; if(pursuitTicks%10==1&&!navigate(targetBlock,1,false))stopGoal(); }
            }
            case SLEEP -> {
                if (temperature.hasTemporarySleepSite() && !temperature.canSleepAtRefuge()) { stopGoal(); break; }
                if(level().isRainingAt(entity.blockPosition())){doses.clearDrugSleep();stopGoal();homeWait=0;break;}
                entity.getNavigation().stop(); sleepTicks++;
                if(drugSleep()>0){doses.tickDrugSleep();sleepTicks=Math.min(sleepTicks,600);}
                if (sleepTicks == 20) entity.speak("sleep");
                if (sleepTicks >= 20 && sleepTicks < 620) energy = Math.min(100, energy + .5f);
                if (drugSleep()==0 && sleepTicks >= 220 && (energy >= 95 || nutrition < 15
                        || homeProvider().finishRestEarly(this))) sleepTicks = Math.max(sleepTicks, 620);
                if (sleepTicks >= 640) {
                    entity.pet().routineCompleted(2);
                    temperature.stop();
                    task = Task.NONE; restWait = restInterval(); sleepTicks = 0;
                    if(!entity.family().woke())entity.showReaction("neutral", "awake", 60);
                }
            }
            default -> { }
        }
    }
    private void seekFood() {
        if (!canEat() || !EventHooks.canEntityGrief(level(), entity) || pursuitTicks > 240) { stopGoal(); return; }
        if (targetFood != null && (!targetFood.isAlive() || targetFood.hasPickUpDelay() || !acceptsFood(targetFood.getItem()))) targetFood = null;
        if (targetFood != null) {
            entity.getLookControl().setLookAt(targetFood, 20, 20);
            if (ItemContact.reaches(entity, targetFood)) {
                if (!WildCards.mayTakeFood(entity,targetFood)) { entity.getNavigation().stop(); return; }
                var rivals = level().getEntitiesOfClass(YukkuriEntity.class, targetFood.getBoundingBox().inflate(1.5), other ->
                        other != entity && other.isAlive() && other.life().canEat() && entity.hasLineOfSight(other));
                int wait=personality.queueDuration(nutrition);
                if (!rivals.isEmpty() && queueTicks++ < wait) {
                    entity.getNavigation().stop(); if (queueTicks == 1) {personality.decided("wait_food");entity.showReaction("neutral", "wait_turn", wait);} return;
                }
                ItemStack one = targetFood.getItem().copyWithCount(1);
                if (beginMeal(one, null, false)) {
                    ItemStack remaining = targetFood.getItem().copy(); remaining.shrink(1);
                    if (remaining.isEmpty()) targetFood.discard(); else targetFood.setItem(remaining);
                    WildCards.foodTaken(entity,targetFood);
                    if (!rivals.isEmpty() && wait < 35 && nutrition>15) {
                        for (var rival : rivals) WildCards.competing(entity, rival);
                        entity.showReaction("angry", "mine", 80);
                        personality.decided("claim_food");
                        for(var rival:rivals)rival.life().personality().environment().record(entity.getUUID(),"food_competition",-3);
                        for (YukkuriEntity rival : rivals) if (rival.life().canRecognize(entity)) YukkuriWorldData.get(level()).relate(rival.getUUID(), entity.getUUID(), -3);
                    }
                }
            } else {
                Vec3 stand = ItemContact.approach(entity, targetFood.position());
                SurfaceApproach.walk(entity, stand, 1.15);
                SurfaceApproach.face(entity, SurfaceApproach.yaw(entity.position(), targetFood.position()));
            }
        } else if (targetBlock != null && level().getBlockEntity(targetBlock) instanceof CareBlockEntity bowl && bowl.hasFood(this::acceptsFood)) {
            Vec3 center = Vec3.atBottomCenterOf(targetBlock).add(0, .16, 0);
            Vec3 toward = entity.position().subtract(center).multiply(1,0,1);
            if (toward.lengthSqr() < .001) toward = new Vec3(0,0,1);
            toward = toward.normalize();
            Vec3 contact = center.add(toward.scale(.40));
            if (ItemContact.reaches(entity, contact, .06)) {
                ItemStack one = bowl.takeFood(this::acceptsFood);
                if (!one.isEmpty() && !beginMeal(one, null, false)) {
                    // Only reachable if another rule invalidates eating inside this call.
                    ItemEntity restored = entity.spawnAtLocation(one); if (restored != null) restored.setPickUpDelay(40);
                } else if (!one.isEmpty()) meal.bowlMeal();
            } else {
                Vec3 stand = contact.add(toward.scale(Math.max(entity.getBbWidth()*.5+.04,
                        MouthPlacement.position(entity).subtract(entity.position()).horizontalDistance())));
                stand = new Vec3(stand.x, entity.getY(), stand.z);
                SurfaceApproach.walk(entity, stand, 1.15);
                SurfaceApproach.face(entity, SurfaceApproach.yaw(entity.position(), center));
            }
        } else stopGoal();
    }
    private boolean clearContact(BlockPos pos) {
        return level().clip(new ClipContext(entity.getEyePosition(), Vec3.atCenterOf(pos),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity)).getBlockPos().equals(pos)
                || entity.blockPosition().distManhattan(pos) <= 1;
    }
    public void stopGoal() {
        temperature.stop();
        entity.wintering().stopMeal();
        task = Task.NONE; targetFood = null; targetBlock = null; sleepTicks = toiletTicks = 0; planWait = 30;
        entity.getNavigation().stop();
    }

    public boolean dryStand(BlockPos pos){ return home.dryStand(pos); }
    private boolean restable(BlockPos pos){ return home.restable(pos); }
    private BlockPos nearestDryCover(){ return home.nearestDryCover(); }
    public boolean covered(BlockPos pos){ return home.covered(pos); }
    private boolean rainOutside(){ return home.rainOutside(); }
    public BlockPos entrance(BlockPos nest){ return home.entrance(nest); }
    public boolean assignHome(GlobalPos selected){ return home.assignHome(selected); }
    public boolean assignNaturalHome(BlockPos selected){ return home.assignNaturalHome(selected); }
    public void releaseHome(){ home.releaseHome(); }
    public BlockPos homeStand(){ return home.homeStand(); }
    private boolean maintainHome(){ return home.maintainHome(); }
    private BlockPos nearestCare(CareBlock.Kind kind, boolean requireFood){ return home.nearestCare(kind, requireFood); }
    /** The latrine ground of a planned site, up to 24 blocks away (the ordinary search only looks 8 blocks around). */
    private BlockPos siteToilet(){ return home.siteToilet(); }
    private boolean seesWater(BlockPos water){ return home.seesWater(water); }
    private BlockPos drinkStand(BlockPos water){ return home.drinkStand(water); }
    boolean hasReachableDrink(){ return home.hasReachableDrink(); }
    private BlockPos nearestDrink(){ return home.nearestDrink(); }
    /** The shallow pool of a planned site: within 32 blocks the whole community drinks there. */
    private BlockPos siteWater(){ return home.siteWater(); }
    private boolean navigateNear(BlockPos target, double speed) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos stand = target.relative(direction);
            if (dryStand(stand) && navigate(stand, speed, false)) return true;
        }
        return false;
    }
    private Path path(BlockPos target, boolean escapeWater) {
        float old = entity.getPathfindingMalus(PathType.WATER);
        if (escapeWater) entity.setPathfindingMalus(PathType.WATER, 0);
        Path path;
        try { path = entity.getNavigation().createPath(target, 0); }
        finally { if (escapeWater) entity.setPathfindingMalus(PathType.WATER, old); }
        if (path == null || !path.canReach() || !escapeWater && !entity.aquaticLife().shore().allows(path)) return null;
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
    public boolean reachable(BlockPos target, boolean escapeWater) { return trustRoutes || path(target, escapeWater) != null; }
    public boolean navigate(BlockPos target, double speed, boolean escapeWater) {
        Path path = path(target, escapeWater); return path != null && entity.getNavigation().moveTo(path, speed);
    }
    public void escapeWater() {
        entity.waterDistress().escapeTick();
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
        tag.putInt("RestWait", restWait); tag.putInt("SocialWait", socialWait);
        if (entity.shelteredInHat() && task == Task.SLEEP) tag.putInt("HatSleepTicks", sleepTicks);
        meal.save(tag);
        home.save(tag);
        accessory.save(tag);
        tag.put("Personality", personality.save()); return tag;
    }
    private static float bounded(CompoundTag tag, String name, float fallback) {
        float value = tag.contains(name, Tag.TAG_ANY_NUMERIC) ? tag.getFloat(name) : fallback;
        return Float.isFinite(value) ? Math.clamp(value, 0, 100) : fallback;
    }
    /** Aquatic Marisa still drowns, but takes three ordinary immersion intervals to lose each health point. */
    private int immersionInterval() { return entity.profile().aquatic() ? 60 : 20; }
    public void load(CompoundTag tag) {
        hatToilet.load();
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
        hygieneTicks = Math.clamp(tag.getInt("HygieneProgress"),0,23999); restWait = Math.clamp(tag.getInt("RestWait"),0,1200);
        socialWait = Math.clamp(tag.getInt("SocialWait"),0,2400);
        meal.load(tag);
        home.load(tag); accessory.load(tag);
        personality=new YukkuriPersonality(entity.getUUID()); personality.load(tag.getCompound("Personality"));
        task=Task.NONE; targetFood=null; targetBlock=null; sleepTicks=toiletTicks=0; homeWait=20; planWait=0;
        if (tag.contains("HatSleepTicks")) { task=Task.SLEEP; sleepTicks=Math.clamp(tag.getInt("HatSleepTicks"),0,600); }
    }
}
