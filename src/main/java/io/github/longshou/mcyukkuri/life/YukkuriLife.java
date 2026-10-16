package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.body.FillingResource;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

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
    private ItemStack mouth = ItemStack.EMPTY;
    private int mealTicks;
    private UUID feeder;
    private boolean sharedMeal, preparedMeal;
    private boolean bowlMeal;
    private GlobalPos home;
    private boolean naturalHome;
    private int homeWait, planWait, restWait = 200, sleepTicks, toiletTicks;
    private Task task = Task.NONE;
    private ItemEntity targetFood;
    private BlockPos targetBlock;
    private int pursuitTicks, queueTicks, winterNeedCheckAt;
    private boolean accessory = true;
    private UUID accessoryToken;
    private int socialWait;
    private float dryness;
    private int spicyTicks, sprayDelay, drugSleep, drinkTicks;
    private boolean returnDoseBottle;
    private int exerciseReliefTicks;
    private boolean toiletAccident;
    private int hatToiletTicks;

    public YukkuriLife(YukkuriEntity entity) { this.entity = entity; temperature = new TemperatureLife(entity); personality = new YukkuriPersonality(entity.getUUID()); }
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
    public float hygiene() { return hygiene; }
    public float bladder() { return bladder; }
    /** Only the completed newborn-care contact may retire this first bowel load. */
    public boolean completeAssistedFirstToilet() {
        if (!(entity.level() instanceof ServerLevel server) || entity.childhood().firstToiletDone()
                || bladder < 10 || !entity.isAlive() || entity.isPanicking() || entity.isOnFire()
                || entity.isInWaterOrBubble() || isSleeping() || mealTicks > 0) return false;
        Vec3 rear = entity.getVehicle() instanceof io.github.longshou.mcyukkuri.subspecies.HatBoatEntity boat
                ? boat.toiletDropPosition(entity)
                : entity.position().subtract(entity.getLookAngle().multiply(1, 0, 1).normalize().scale(entity.getBbWidth() * .55)).add(0, .04, 0);
        if (rear == null) return false;
        var waste = new ItemEntity(server, rear.x, rear.y, rear.z, io.github.longshou.mcyukkuri.body.DetachedItems.waste(entity));
        waste.setDefaultPickUpDelay(); waste.setDeltaMovement(Vec3.ZERO);
        if (!server.noCollision(waste, waste.getBoundingBox())) return false;
        if (!server.addFreshEntity(waste)) return false;
        bladder = 0;
        entity.showReaction("happy", "toilet_done", 60);
        return true;
    }
    /** Supported hat passengers can relieve themselves without turning a bodily need into a shore trip. */
    public boolean toiletInHat(io.github.longshou.mcyukkuri.subspecies.HatBoatEntity boat) {
        if (!boat.protects(entity) || !entity.childhood().firstToiletDone() || bladder < 40 || constipated()
                || entity.isPanicking() || entity.isOnFire() || isSleeping() || mealTicks > 0
                || entity.isCommandPlaying() || entity.operations().active()) {
            hatToiletTicks = 0; boat.releaseToiletSeat(entity); return false;
        }
        if (!boat.prepareToiletSeat(entity)) { hatToiletTicks = 0; return false; }
        if (!boat.toiletSeatReady(entity)) { hatToiletTicks = 0; return true; }
        boat.faceToiletOutward(entity);
        if (++hatToiletTicks < 40) return true;
        Vec3 rear = boat.toiletDropPosition(entity);
        if (rear == null) { hatToiletTicks = 0; boat.releaseToiletSeat(entity); return false; }
        var waste = new ItemEntity(level(), rear.x, rear.y, rear.z, io.github.longshou.mcyukkuri.body.DetachedItems.waste(entity));
        waste.setDefaultPickUpDelay(); waste.setDeltaMovement(Vec3.ZERO);
        if (level().addFreshEntity(waste)) { bladder -= 40; entity.showReaction("happy", "toilet_done", 60); }
        hatToiletTicks = 0;
        boat.releaseToiletSeat(entity);
        return true;
    }
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
    public int spicyTicks(){return spicyTicks;}
    public int sprayDelay(){return sprayDelay;}
    public int drugSleep(){return drugSleep;}
    public void drink(float relief){dryness=Math.max(0,dryness-relief);spicyTicks=Math.max(0,spicyTicks-Math.round(relief*4));bladder=Math.min(100,bladder+2);}
    public boolean canTakeDose(ItemStack dose){return Consumables.oral(dose)&&!entity.family().awaitingLanding()&&mouth.isEmpty()&&mealTicks==0&&entity.isAlive()&&!entity.isNoAi()
            &&!entity.isPanicking()&&!entity.isCommandPlaying()&&!entity.operations().active()&&!isSleeping()&&!entity.isInWaterOrBubble()&&Consumables.applicable(entity,dose);}
    public boolean beginDose(ItemStack one,UUID player,boolean returnBottle){
        if(one.getCount()!=1||!canTakeDose(one))return false;
        temperature.stop();
        mouth=one.copy();feeder=player;sharedMeal=true;returnDoseBottle=returnBottle;mealTicks=1;
        sleepTicks=0;task=Task.NONE;entity.family().stop();entity.social().stop();entity.groupInteraction().stop();entity.playLife().stop();
        entity.getNavigation().stop();entity.setPersistenceRequired();return true;
    }
    public boolean canSpray(){return entity.isAlive()&&!entity.isNoAi()&&!entity.isPanicking()&&!entity.isCommandPlaying()&&!entity.operations().active()
            &&!isSleeping()&&!entity.isInWaterOrBubble()&&!entity.isOnFire()&&sprayDelay==0&&drugSleep==0;}
    public boolean spray(){
        if(!canSpray())return false;interrupt();sprayDelay=90;entity.setPersistenceRequired();
        level().sendParticles(ParticleTypes.CLOUD,entity.getX(),entity.getEyeY(),entity.getZ(),8,.15,.08,.15,.015);return true;
    }
    public void poolRinse(){hygiene=Math.min(100,hygiene+15);wetness=Math.min(100,wetness+5);}
    public long ageTicks() { return ageTicks; }
    public GlobalPos home() { return home; }
    public boolean naturalHome() { return naturalHome; }
    public ItemStack mouth() { return mouth; }
    public int mealTicks() { return mealTicks; }
    public boolean hasAccessory() { return accessory; }
    public int socialWait() { return socialWait; }
    public void deferSocial(int ticks) { socialWait = Math.max(socialWait, ticks); }
    public boolean canRecognize(YukkuriEntity target) { return entity.pet().grade() == io.github.longshou.mcyukkuri.pet.BadgeGrade.GOLD || target.life().hasAccessory()
            || target.getVehicle() instanceof io.github.longshou.mcyukkuri.subspecies.HatBoatEntity boat
            && target.getUUID().equals(boat.ownerYukkuri()) && boat.protects(target); }
    public boolean needsFood() { return nutrition <= 75 && mouth.isEmpty() && mealTicks == 0; }
    private boolean edible(ItemStack food){return food.is(YukkuriEntity.FOOD)||Consumables.kind(food)==Consumables.Kind.SPICY||FillingResource.isFilling(food)||entity.getVariant().isHouse()&&food.is(ModItems.FOOD_SCRAP.get());}
    public boolean acceptsFood(ItemStack food){return edible(food)&&(!FillingResource.isFilling(food)||FillingResource.acceptable(entity,food));}
    private boolean readyForFood() { return needsFood() && !entity.family().awaitingLanding() && !entity.isPanicking() && !entity.isCommandPlaying() && entity.isAlive(); }
    /** Food gathered or offered as a normal meal is for a child that can chew independently. */
    public boolean canEat() { return readyForFood() && entity.family().canEatIndependently() && entity.childhood().canEatSolid(); }
    /** A parent may select this child for a real serving even while ordinary self-feeding is unavailable. */
    public boolean canReceiveCareFood() { return readyForFood(); }
    public boolean canReceiveCareFood(ItemStack food) { return canReceiveCareFood() && acceptsFood(food); }
    public boolean hasUrgentNeed() { return nutrition < 30 || energy < 25 || bladder >= 90 && !constipated() || entity.isInWaterOrBubble() || entity.isOnFire() || wetness > 40; }
    public boolean isSleeping() { return task == Task.SLEEP && sleepTicks >= 20 && sleepTicks < 620; }
    /** Hat sleep uses the same physiology and completed-sleep growth as a terrestrial nest. */
    public void restInHat(io.github.longshou.mcyukkuri.subspecies.HatBoatEntity boat) {
        if (!boat.protects(entity) || entity.isPanicking() || entity.life().mealTicks() > 0
                || boat.flooding() > 0 || level().isRainingAt(entity.blockPosition())) { wakeInHat(); return; }
        if (!level().isNight() && energy > restThreshold() && task != Task.SLEEP) return;
        if (task != Task.SLEEP) { task = Task.SLEEP; sleepTicks = 0; targetBlock = null; targetFood = null; }
        sleepTicks = Math.min(600, sleepTicks+1);
        if (sleepTicks == 20) entity.speak("sleep");
        if (sleepTicks >= 20) energy = Math.min(100, energy+.5f);
        if (drugSleep > 0) drugSleep--;
        if (drugSleep == 0 && (nutrition < 15 || !level().isNight() && sleepTicks >= 220 && energy >= 95)) wakeInHat();
    }
    public void wakeInHat() {
        if (task != Task.SLEEP) return;
        boolean rested = sleepTicks >= 220;
        task = Task.NONE; sleepTicks = 0; restWait = restInterval();
        if (rested && !entity.family().woke()) entity.showReaction("neutral", "awake", 40);
    }
    public boolean wantsCrawl() {
        return task == Task.FOOD && mealTicks == 0 && targetFood != null && targetFood.isAlive()
                && entity.distanceToSqr(targetFood) < 1;
    }
    public String activity() {
        if (mealTicks > 0) {
            if (preparedMeal) return mealTicks < 53 ? "receive_mash" : "meal_finish";
            return mealTicks < 10 ? "take_food" : mealTicks < 45 ? "chew" : mealTicks < 53 ? "swallow" : "meal_finish";
        }
        if (task == Task.SLEEP) return sleepTicks < 20 ? "sleep_enter" : sleepTicks >= 620 ? "wake" : "sleep";
        return switch (task) { case FOOD -> "seek_food"; case WINTER_FOOD -> "shelter_stock"; case HOME -> "return_home"; case TOILET -> toiletTicks > 0 ? "toilet" : "seek_toilet";
            case GROUND_TOILET -> toiletTicks > 0 ? toiletAccident ? "toilet_accident" : "ground_toilet" : "seek_toilet";
            case DRINK -> drinkTicks>0?"drink":"seek_water"; case TEMPERATURE -> temperature.activity(); default -> "idle"; };
    }
    private ServerLevel level() { return (ServerLevel)entity.level(); }
    private int restThreshold() { return entity.family().needsMash() ? 65 : 35; }
    private int restInterval() { return entity.family().needsMash() ? 200 : 1200; }

    public void tick() {
        if (entity.level().isClientSide || !entity.isAlive()) return;
        // Body contact with water hurts every species, even with AI disabled. A dry hat is not immersion.
        if (!entity.isInWaterOrBubble()) immersionTicks = 0;
        else if (++immersionTicks >= 20) {
            immersionTicks = 0;
            entity.hurt(level().damageSources().drown(), 1);
        }
        if (!entity.isAlive() || entity.isNoAi()) return;
        ageTicks++;
        personality.tick();
        if(exerciseReliefTicks>0)exerciseReliefTicks--;
        if (socialWait > 0) socialWait--;
        if (restWait > 0) restWait--;
        if (planWait > 0) planWait--;
        if (homeWait > 0) homeWait--;
        if(spicyTicks>0&&--spicyTicks%80==0)entity.body().irritated();
        if (++metabolismTicks >= Math.max(20, YukkuriConfig.HUNGER_INTERVAL.get() / (entity.family().needsMash() ? 2 : 1))) {
            metabolismTicks = 0; nutrition = Math.max(0, nutrition - 1);
            if (nutrition == 0) entity.hurt(level().damageSources().starve(), 1);
        }
        if (++energyTicks >= Math.max(20, YukkuriConfig.ENERGY_INTERVAL.get() / (entity.family().needsMash() ? 2 : 1))) { energyTicks = 0; if (!isSleeping()) energy = Math.max(0, energy - 1); }
        if (++hygieneTicks >= YukkuriConfig.HYGIENE_INTERVAL.get()) { hygieneTicks = 0; hygiene = Math.max(0, hygiene - 1); }
        if (ageTicks % 20 == 0) {
            if (entity.isInWaterOrBubble()) wetness = Math.min(100, wetness + entity.subspecies().waterGain(8));
            else if (entity.habitat().leaking() || level().isRainingAt(entity.blockPosition())) wetness = Math.min(100, wetness + entity.subspecies().waterGain(2));
            else wetness = Math.max(0, wetness - 3);
            if (!entity.isInWaterOrBubble() && wetness >= YukkuriConfig.WET_DAMAGE_THRESHOLD.get() && ageTicks % 100 == 0)
                entity.hurt(level().damageSources().drown(), 1);
        }
        temperature.tick();
        if (!entity.isAlive()) return;
        if (entity.isPanicking() || entity.isOnFire() || entity.isInWaterOrBubble()) {
            if (mealTicks > 0 || task != Task.NONE || sprayDelay>0 || drugSleep>0) interrupt();
            return;
        }
        if(sprayDelay>0&&--sprayDelay==0){interrupt();drugSleep=400;task=Task.SLEEP;sleepTicks=0;entity.family().stop();entity.social().stop();entity.groupInteraction().stop();entity.playLife().stop();}
        if (mealTicks > 0) {
            if (!mouth.isEmpty() && !Consumables.oral(mouth) && !acceptsFood(mouth)) { interrupt(); return; }
            entity.getNavigation().stop();
            mealTicks++;
            if (mealTicks == 6 && !sharedMeal && nutrition >= 35) tryShare();
            if (mealTicks == 45 && !mouth.isEmpty()) {
                if(Consumables.oral(mouth)){
                    var returned=Consumables.swallow(entity,mouth)?returnDoseBottle?new ItemStack(net.minecraft.world.item.Items.GLASS_BOTTLE):ItemStack.EMPTY:mouth.copy();
                    mouth=ItemStack.EMPTY;
                    if(!returned.isEmpty()){
                        var player=feeder==null?null:level().getPlayerByUUID(feeder);
                        if(player==null||!player.isAlive()||player.distanceToSqr(entity)>16||!player.getInventory().add(returned))entity.spawnAtLocation(returned);
                    }
                    returnDoseBottle=false;feeder=null;entity.gameEvent(GameEvent.DRINK);
                }else{
                var food = mouth.get(DataComponents.FOOD);
                boolean scrap=mouth.is(ModItems.FOOD_SCRAP.get());
                boolean filling=FillingResource.isFilling(mouth);
                boolean spicy=Consumables.kind(mouth)==Consumables.Kind.SPICY;
                float gain = filling?FillingResource.nutrition(mouth):scrap?3:food == null ? 20 : Math.clamp(food.nutrition() * 5, 10, 35);
                if(mouth.is(ModItems.GRAIN_BUDGET.get()))gain=12;
                else if(mouth.is(ModItems.GRAIN_PREMIUM.get()))gain=30;
                if(spicy){gain=12;spicyTicks=240;dryness=Math.min(100,dryness+45);}
                else if(mouth.is(ModItems.GRAIN_BUDGET.get())||mouth.is(ModItems.GRAIN_REGULAR.get())||mouth.is(ModItems.GRAIN_PREMIUM.get())||mouth.is(net.minecraft.world.item.Items.COOKIE))dryness=Math.min(100,dryness+15);
                if(!scrap&&!filling){var residue=entity.spawnAtLocation(new ItemStack(ModItems.FOOD_SCRAP.get()));if(residue!=null)residue.setPickUpDelay(40);}
                if(filling)FillingResource.swallowed(entity,mouth);
                // The held item is retired at the same server step that grants nutrition.
                mouth = ItemStack.EMPTY;
                nutrition = Math.min(100, nutrition + gain); bladder = Math.min(100, bladder + gain * .5f);
                if(!filling&&!spicy)entity.heal(2); personality.fed(feeder); entity.pet().contact(feeder, true); io.github.longshou.mcyukkuri.event.WildEvents.fed(entity,feeder); feeder = null;
                if (bowlMeal) { entity.pet().routineCompleted(0); bowlMeal = false; }
                entity.gameEvent(GameEvent.EAT); entity.showReaction(spicy?"surprise":"happy", spicy?"hurt":"fed", 100);
                if(!spicy)level().sendParticles(ParticleTypes.HEART, entity.getX(), entity.getY()+entity.getBbHeight(), entity.getZ(), 3, .1, .05, .1, 0);
                }
            }
            if (mealTicks >= 64) { mealTicks = 0; sharedMeal = false; preparedMeal = false; task = Task.NONE; planWait = 20; }
        }
        if (homeWait == 0) { homeWait = 200; maintainHome(); }
    }

    /** Caller transfers exactly one real item before invoking this method. */
    public boolean beginMeal(ItemStack transferred, UUID fromPlayer, boolean alreadyShared) {
        if (!canEat() || transferred.isEmpty() || !acceptsFood(transferred) || transferred.getCount() != 1) return false;
        return startMeal(transferred, fromPlayer, alreadyShared, "take_food");
    }
    /** A player can offer an already prepared serving, but a dependent child still cannot pick it up alone. */
    public boolean beginPreparedMeal(ItemStack prepared, UUID fromPlayer) {
        var player = fromPlayer == null ? null : level().getPlayerByUUID(fromPlayer);
        if (player == null || !player.isAlive() || player.distanceToSqr(entity) > 9 || !player.hasLineOfSight(entity)
                || !entity.family().needsMash() || !canReceiveCareFood(prepared) || !InfantFood.isMash(prepared) || prepared.getCount() != 1) return false;
        return startMeal(prepared, fromPlayer, true, "receive_mash");
    }
    /** A biological parent must physically reach the child before transferring its prepared serving. */
    public boolean beginParentMeal(YukkuriEntity caregiver, ItemStack prepared) {
        if (caregiver == null || caregiver.level() != entity.level() || !caregiver.family().caresFor(entity)
                || !io.github.longshou.mcyukkuri.family.FamilyFeedingContact.mouthsMeet(caregiver, entity)) return false;
        return entity.family().needsMash()
                && canReceiveCareFood(prepared) && InfantFood.isMash(prepared) && prepared.getCount() == 1
                && startMeal(prepared, null, true, "receive_mash");
    }
    /** A hat passenger receives the serving through the same real mouth contact as a child ashore. */
    public boolean beginBraidMeal(YukkuriEntity caregiver, ItemStack prepared) {
        if (caregiver == null || caregiver.level() != entity.level() || !caregiver.family().caresFor(entity)
                || !(caregiver.getVehicle() instanceof io.github.longshou.mcyukkuri.subspecies.HatBoatEntity boat)
                || entity.getVehicle() != boat || !boat.protects(caregiver) || !boat.protects(entity)
                || !io.github.longshou.mcyukkuri.family.FamilyFeedingContact.mouthsMeet(caregiver, entity)) return false;
        return entity.family().needsMash() && canReceiveCareFood(prepared) && InfantFood.isMash(prepared) && prepared.getCount() == 1
                && startMeal(prepared, null, true, "receive_mash");
    }
    private boolean startMeal(ItemStack transferred, UUID fromPlayer, boolean alreadyShared, String reaction) {
        temperature.stop();
        entity.dailyLife().stop();
        bowlMeal = false; mouth = transferred.copy(); feeder = fromPlayer; sharedMeal = alreadyShared; preparedMeal = InfantFood.isMash(transferred); mealTicks = 1;
        sleepTicks = 0; task = Task.NONE; entity.getNavigation().stop(); entity.setPersistenceRequired();
        entity.showReaction("happy", reaction, 60); return true;
    }
    private void tryShare() {
        var peer = level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(1.5), other ->
                other != entity && other.isAlive() && other.life().canEat() && other.life().nutrition() < nutrition - 15
                        && canRecognize(other) && entity.hasLineOfSight(other)).stream()
                .sorted(Comparator.comparingInt(other->entity.family().caresFor(other)?0:1)).findFirst().orElse(null);
        if (peer == null || mouth.isEmpty()) return;
        int score=personality.shareScore(nutrition,peer.life().nutrition(),entity.family().caresFor(peer),YukkuriWorldData.get(level()).affinity(entity.getUUID(),peer.getUUID()));
        if(score<50){personality.decided("keep_food");return;}
        ItemStack transferred = mouth.copy();
        if (peer.life().beginMeal(transferred, feeder, true)) {
            mouth = ItemStack.EMPTY; feeder = null; mealTicks = 0; sharedMeal = false; preparedMeal = false;
            YukkuriWorldData.get(level()).relate(peer.getUUID(), entity.getUUID(), 8);
            personality.experienced(Trait.CARE, 1); entity.showReaction("happy", "share", 100);
            personality.decided("share_food");peer.life().personality().environment().record(entity.getUUID(),"shared_food",4);
        }
    }
    public void interrupt() {
        io.github.longshou.mcyukkuri.event.WildCards.mealInterrupted(entity);
        temperature.stop();
        entity.dailyLife().stop();
        if (!mouth.isEmpty()) {
            ItemEntity dropped = entity.spawnAtLocation(mouth.copy());
            if (dropped != null) dropped.setPickUpDelay(60);
        }
        mouth = ItemStack.EMPTY; mealTicks = 0; feeder = null; sharedMeal = false;preparedMeal=false;returnDoseBottle=false;
        sprayDelay=drugSleep=drinkTicks=0;
        sleepTicks = toiletTicks = 0; task = Task.NONE; targetFood = null; targetBlock = null;
        planWait = 20; entity.getNavigation().stop();
    }
    public boolean wash(UUID player) {
        if (hygiene >= 99 || entity.isInWaterOrBubble() || entity.isOnFire()) return false;
        hygiene = Math.min(100, hygiene + 40); wetness = Math.min(100, wetness + 5);
        personality.washed(player); entity.showReaction("happy", "washed", 60); return true;
    }
    public UUID takeAccessory(UUID player) {
        return takeAccessory(player,UUID.randomUUID());
    }
    /** Voluntarily using one's own hat as a vessel is not an accessory theft. */
    public UUID stowAccessory() {
        if (!accessory) return null;
        accessory = false; accessoryToken = UUID.randomUUID(); return accessoryToken;
    }
    public UUID takeAccessory(UUID player,UUID token) {
        if (!accessory) return null;
        accessory = false; accessoryToken = token; personality.accessoryTaken(player);
        entity.showReaction("surprise", "lost_accessory", 100); return accessoryToken;
    }
    public boolean returnAccessory(UUID owner, UUID token, UUID player) {
        if(!matchesAccessory(owner,token))return false;
        accessory = true; accessoryToken = null; personality.accessoryReturned(player);
        entity.settlement().society().restored();
        entity.showReaction("happy", "returned_accessory", 100); return true;
    }
    public boolean matchesAccessory(UUID owner,UUID token){return !accessory&&entity.getUUID().equals(owner)&&accessoryToken!=null&&accessoryToken.equals(token);}

    public boolean plan() {
        if (entity.family().awaitingLanding()) return false;
        if (entity.shelteredInHat()) return false;
        if (entity.predator().active()) return false;
        if (entity.isPanicking() || entity.isCommandPlaying() || entity.isInWaterOrBubble()) return false;
        if (mealTicks > 0) return true;
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
        if ((task == Task.SLEEP || task == Task.HOME) && drugSleep == 0 && temperature.unsafeSleep() && temperature.plan()) {
            task = Task.TEMPERATURE; sleepTicks = 0; return true;
        }
        if (task != Task.NONE) return true;
        if(drugSleep>0){task=Task.SLEEP;sleepTicks=20;return true;}
        if (planWait > 0) return false;
        planWait = 20;
        if(dryness>=40||spicyTicks>0){targetBlock=nearestDrink();if(targetBlock!=null){task=Task.DRINK;drinkTicks=pursuitTicks=0;return true;}}
        if (rainOutside() && !covered(entity.blockPosition()) && nutrition >= 30) {
            targetBlock = homeStand();
            if(targetBlock==null||!covered(targetBlock))targetBlock=nearestDryCover();
            if (targetBlock != null) { task = Task.HOME; pursuitTicks = 0; return true; }
        }
        // A full bowel must not be starved by a permanently stocked food bowl.
        if (bladder >= 70 && planToilet()) return true;
        if (canEat() && entity.wintering().stockedHome() != null) {
            if (temperature.unsafeSleep() && temperature.plan()) { task = Task.TEMPERATURE; return true; }
            // Activate pending care before this new food route could monopolize the higher-priority life goal.
            if (!temperature.unsafeSleep() && !hasUrgentNeed() && entity.family().canUse()) return false;
            if (entity.wintering().planMeal()) { task = Task.WINTER_FOOD; pursuitTicks = 0; winterNeedCheckAt = 20; return true; }
        }
        if (canEat() && EventHooks.canEntityGrief(level(), entity)) {
            targetFood = level().getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(8), item ->
                    item.isAlive() && !item.hasPickUpDelay() && acceptsFood(item.getItem())).stream()
                    .sorted(Comparator.comparingDouble(entity::distanceToSqr))
                    .filter(item -> entity.distanceToSqr(item)<.5 && entity.hasLineOfSight(item) || reachable(item.blockPosition(),false))
                    .findFirst().orElse(null);
            targetBlock = nearestCare(CareBlock.Kind.BOWL, true);
            if (targetFood != null || targetBlock != null) { task = Task.FOOD; pursuitTicks = queueTicks = 0; return true; }
        }
        if (bladder < 70 && planToilet()) return true;
        if (temperature.plan()) { task = Task.TEMPERATURE; return true; }
        if (restWait == 0 && (energy <= restThreshold() || level().isNight())) {
            targetBlock = homeStand();
            if (targetBlock != null && (entity.distanceToSqr(Vec3.atBottomCenterOf(targetBlock)) > .16 || !restable(entity.blockPosition()))) { task = Task.HOME; pursuitTicks = 0; return true; }
            if (targetBlock != null || energy <= 10 || entity.family().needsMash() && entity.childhood().landed()
                    && dryStand(entity.blockPosition()) && restable(entity.blockPosition())) { task = Task.SLEEP; sleepTicks = 0; return true; }
        }
        return false;
    }
    /** Unmet needs are not prohibitions on food: only an executable higher-priority plan takes over. */
    private boolean planWinterPriority() {
        if (dryness >= 40 || spicyTicks > 0) {
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
        boolean pet = io.github.longshou.mcyukkuri.human.HumanCommerce.data(entity).hasUUID("Owner")
                || home != null && !naturalHome;
        if (bladder < (pet ? 90 : 70)) return false;
        targetBlock = null;
        if (!pet) {
            for (BlockPos candidate : BlockPos.betweenClosed(entity.blockPosition().offset(-4,0,-4),entity.blockPosition().offset(4,0,4))) {
                if (home != null && home.dimension().equals(level().dimension()) && candidate.distSqr(home.pos()) < 4) continue;
                if (!dryStand(candidate) || !reachable(candidate, false)) continue;
                if (!level().getEntitiesOfClass(ItemEntity.class,new net.minecraft.world.phys.AABB(candidate).inflate(1),i->acceptsFood(i.getItem())).isEmpty()) continue;
                if (targetBlock == null || candidate.distSqr(entity.blockPosition()) < targetBlock.distSqr(entity.blockPosition())) targetBlock = candidate.immutable();
            }
        }
        toiletAccident = pet || targetBlock == null;
        if (targetBlock == null && bladder >= 90 && dryStand(entity.blockPosition())) targetBlock = entity.blockPosition();
        if (targetBlock == null) return false;
        task = Task.GROUND_TOILET; toiletTicks = pursuitTicks = 0; return true;
    }
    public void tickGoal() {
        if (mealTicks > 0) { entity.getNavigation().stop(); return; }
        if (entity.isPanicking() || entity.isCommandPlaying() || entity.isInWaterOrBubble()) { stopGoal(); return; }
        pursuitTicks++;
        switch (task) {
            case TEMPERATURE -> {
                if (!temperature.tickGoal()) stopGoal();
                else if (restWait == 0 && (energy <= restThreshold() || level().isNight()) && temperature.beginSleepAtRefuge()) {
                    task = Task.SLEEP; sleepTicks = 0; targetBlock = null;
                }
            }
            case DRINK -> {
                if(targetBlock==null||!level().getFluidState(targetBlock).is(net.minecraft.tags.FluidTags.WATER)||pursuitTicks>240){stopGoal();return;}
                if(entity.distanceToSqr(Vec3.atCenterOf(targetBlock))<2.25&&seesWater(targetBlock)){
                    entity.getNavigation().stop();entity.getLookControl().setLookAt(targetBlock.getX()+.5,targetBlock.getY()+.4,targetBlock.getZ()+.5,20,20);
                    if(++drinkTicks>=40){
                        var pool=io.github.longshou.mcyukkuri.play.PlayBlock.facility(level(),targetBlock);
                        if(pool==null||pool.spendWater())drink(30);
                        task=Task.NONE;drinkTicks=0;planWait=40;entity.gameEvent(GameEvent.DRINK);
                    }
                }else if(pursuitTicks%10==1){var stand=drinkStand(targetBlock);if(stand==null||!navigate(stand,1,false))stopGoal();}
            }
            case FOOD -> seekFood();
            case WINTER_FOOD -> { if (!entity.wintering().tickMeal()) stopGoal(); }
            case HOME -> {
                if (targetBlock == null || !dryStand(targetBlock) || !restable(targetBlock) || pursuitTicks > 240 || nutrition < 30) { stopGoal(); homeWait = 0; return; }
                if (entity.distanceToSqr(Vec3.atBottomCenterOf(targetBlock)) < .16 && restable(entity.blockPosition())) {
                    entity.getNavigation().stop();
                    if (restWait == 0 && (energy <= restThreshold() || level().isNight())) { task = Task.SLEEP; sleepTicks = 0; }
                    // Awake arrival ends the trip, even while rain continues above the roof.
                    else { task = Task.NONE; targetBlock = null; planWait = 0; }
                } else if (pursuitTicks % 10 == 1 && !navigate(targetBlock, 1.15, false)) stopGoal();
            }
            case TOILET -> {
                if (!entity.childhood().firstToiletDone()) { stopGoal(); return; }
                if (!(level().getBlockEntity(targetBlock) instanceof CareBlockEntity care) || care.kind() != CareBlock.Kind.TOILET
                        || care.waste() >= care.capacity() || pursuitTicks > 240) { stopGoal(); return; }
                if (entity.distanceToSqr(Vec3.atCenterOf(targetBlock)) < 2.25) {
                    entity.getNavigation().stop(); toiletTicks++;
                    if (toiletTicks == 1) entity.speak("need_toilet");
                    if (toiletTicks >= 40) {
                        if (bladder >= 40 && !constipated() && care.relieve(io.github.longshou.mcyukkuri.body.DetachedItems.waste(entity))) { bladder -= 40; entity.pet().routineCompleted(1); entity.showReaction("happy","toilet_done",60); }
                        toiletTicks = 0; task = Task.NONE;
                    }
                } else if (pursuitTicks % 10 == 1 && !navigateNear(targetBlock, 1)) stopGoal();
            }
            case GROUND_TOILET -> {
                if (!entity.childhood().firstToiletDone()) { stopGoal(); return; }
                if (targetBlock == null || !dryStand(targetBlock) || constipated() || pursuitTicks > 240) { stopGoal(); return; }
                if (entity.distanceToSqr(Vec3.atBottomCenterOf(targetBlock)) <= .25) {
                    entity.getNavigation().stop();
                    if (++toiletTicks == 1) entity.speak("need_toilet");
                    if (toiletTicks >= 40) {
                        Vec3 rear = entity.position().subtract(entity.getLookAngle().multiply(1,0,1).normalize().scale(entity.getBbWidth()*.55));
                        var waste = new ItemEntity(level(),rear.x,rear.y+.04,rear.z,io.github.longshou.mcyukkuri.body.DetachedItems.waste(entity));
                        waste.setDefaultPickUpDelay(); waste.setDeltaMovement(Vec3.ZERO);
                        if (!level().noCollision(waste,waste.getBoundingBox())) waste.setPos(entity.position());
                        if (bladder >= 40 && level().addFreshEntity(waste)) {
                            bladder -= 40;
                            if (toiletAccident) hygiene = Math.max(0,hygiene-20);
                            entity.showReaction(toiletAccident?"teary":"happy", "toilet_done",60);
                        }
                        stopGoal();
                    }
                } else { toiletTicks=0; if(pursuitTicks%10==1&&!navigate(targetBlock,1,false))stopGoal(); }
            }
            case SLEEP -> {
                if (temperature.hasTemporarySleepSite() && !temperature.canSleepAtRefuge()) { stopGoal(); break; }
                if(level().isRainingAt(entity.blockPosition())){drugSleep=0;stopGoal();homeWait=0;break;}
                entity.getNavigation().stop(); sleepTicks++;
                if(drugSleep>0){drugSleep--;sleepTicks=Math.min(sleepTicks,600);}
                if (sleepTicks == 20) entity.speak("sleep");
                if (sleepTicks >= 20 && sleepTicks < 620) energy = Math.min(100, energy + .5f);
                if (drugSleep==0 && sleepTicks >= 220 && (energy >= 95 || nutrition < 15)) sleepTicks = Math.max(sleepTicks, 620);
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
            if (entity.distanceToSqr(targetFood) < .5 && entity.hasLineOfSight(targetFood)) {
                if (!io.github.longshou.mcyukkuri.event.WildCards.mayTakeFood(entity,targetFood)) { entity.getNavigation().stop(); return; }
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
                    io.github.longshou.mcyukkuri.event.WildCards.foodTaken(entity,targetFood);
                    if (!rivals.isEmpty() && wait < 35 && nutrition>15) {
                        for (var rival : rivals) io.github.longshou.mcyukkuri.event.WildCards.competing(entity, rival);
                        entity.showReaction("angry", "mine", 80);
                        personality.decided("claim_food");
                        for(var rival:rivals)rival.life().personality().environment().record(entity.getUUID(),"food_competition",-3);
                        for (YukkuriEntity rival : rivals) if (rival.life().canRecognize(entity)) YukkuriWorldData.get(level()).relate(rival.getUUID(), entity.getUUID(), -3);
                    }
                }
            } else if (pursuitTicks % 10 == 1 && !navigate(targetFood.blockPosition(), 1.15, false)) targetFood = null;
        } else if (targetBlock != null && level().getBlockEntity(targetBlock) instanceof CareBlockEntity bowl && bowl.hasFood(this::acceptsFood)) {
            if (entity.distanceToSqr(Vec3.atCenterOf(targetBlock)) < 2.25 && clearContact(targetBlock)) {
                ItemStack one = bowl.takeFood(this::acceptsFood);
                if (!one.isEmpty() && !beginMeal(one, null, false)) {
                    // Only reachable if another rule invalidates eating inside this call.
                    ItemEntity restored = entity.spawnAtLocation(one); if (restored != null) restored.setPickUpDelay(40);
                } else if (!one.isEmpty()) bowlMeal = true;
            } else if (pursuitTicks % 10 == 1 && !navigateNear(targetBlock, 1.15)) stopGoal();
        } else stopGoal();
    }
    private boolean clearContact(BlockPos pos) {
        return level().clip(new net.minecraft.world.level.ClipContext(entity.getEyePosition(), Vec3.atCenterOf(pos),
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, entity)).getBlockPos().equals(pos)
                || entity.blockPosition().distManhattan(pos) <= 1;
    }
    public void stopGoal() {
        temperature.stop();
        entity.wintering().stopMeal();
        task = Task.NONE; targetFood = null; targetBlock = null; sleepTicks = toiletTicks = 0; planWait = 30;
        entity.getNavigation().stop();
    }

    public boolean dryStand(BlockPos pos) {
        return level().hasChunkAt(pos) && level().getFluidState(pos).isEmpty() && level().getFluidState(pos.below()).isEmpty()
                && level().noCollision(entity, new net.minecraft.world.phys.AABB(pos.getX()+.5-entity.getBbWidth()/2,
                        pos.getY(),pos.getZ()+.5-entity.getBbWidth()/2,pos.getX()+.5+entity.getBbWidth()/2,
                        pos.getY()+entity.getBbHeight(),pos.getZ()+.5+entity.getBbWidth()/2))
                && level().getBlockState(pos.below()).isFaceSturdy(level(), pos.below(), Direction.UP);
    }
    private boolean restable(BlockPos pos) {
        if(covered(pos))return true;
        var home=entity.habitat().shelter();
        return home!=null&&home.owns(entity.getUUID())&&home.contains(pos)&&home.sound()&&!level().isRainingAt(pos);
    }
    private BlockPos nearestDryCover() {
        BlockPos best=null;double distance=Double.MAX_VALUE;
        for(var pos:BlockPos.betweenClosed(entity.blockPosition().offset(-8,-2,-8),entity.blockPosition().offset(8,2,8))) {
            if(!dryStand(pos)||!covered(pos))continue;
            var owner=io.github.longshou.mcyukkuri.settlement.ShelterBlock.shelter(level(),pos);
            if(owner!=null&&!owner.owns(entity.getUUID()))continue;
            double candidate=entity.distanceToSqr(Vec3.atBottomCenterOf(pos));
            if(candidate<distance&&reachable(pos,false)){best=pos.immutable();distance=candidate;}
        }
        return best;
    }
    public boolean covered(BlockPos pos) {
        var shelter=io.github.longshou.mcyukkuri.settlement.ShelterBlock.shelter(level(),pos);
        if(shelter!=null)return shelter.rainSheltered(pos);
        // Skylight propagation can lag behind a removed roof; shelter follows actual blocks immediately.
        return level().hasChunkAt(pos) && level().getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()) > pos.getY();
    }
    private boolean rainOutside() {
        return level().isRaining() && level().isRainingAt(level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING,entity.blockPosition()));
    }
    public BlockPos entrance(BlockPos nest) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos pos = nest.relative(direction);
            if (dryStand(pos) && covered(pos)) return pos;
        }
        return null;
    }
    public boolean assignHome(GlobalPos selected) {
        if (!selected.dimension().equals(level().dimension()) || !level().hasChunkAt(selected.pos())
                || entity.distanceToSqr(Vec3.atCenterOf(selected.pos())) > 256) return false;
        var wild=io.github.longshou.mcyukkuri.settlement.ShelterBlock.shelter(level(),selected.pos());
        if(wild!=null)return entity.habitat().choose(wild);
        if (!(level().getBlockEntity(selected.pos()) instanceof CareBlockEntity care) || care.kind() != CareBlock.Kind.NEST) return false;
        if (level().getBlockState(selected.pos()).is(io.github.longshou.mcyukkuri.registry.ModBlocks.PREDATOR_NEST.get()) && !entity.getVariant().isPredator()) return false;
        BlockPos stand = entrance(selected.pos());
        if (stand == null || !(reachable(stand, false) || entity.getVariant().isPredator() && entity.predator().canFlyTo(stand))
                || care.resident() != null && !care.resident().equals(entity.getUUID())) return false;
        releaseHome();
        if (!care.claim(entity.getUUID())) return false;
        entity.habitat().release();
        home = selected; naturalHome = false; homeWait = 200; entity.setPersistenceRequired(); return true;
    }
    public boolean assignNaturalHome(BlockPos selected) {
        var shelter=io.github.longshou.mcyukkuri.settlement.ShelterBlock.shelter(level(),selected);
        if(shelter!=null&&!shelter.owns(entity.getUUID()))return false;
        if (entity.distanceToSqr(Vec3.atBottomCenterOf(selected)) > 256 || !dryStand(selected) || !(shelter!=null&&shelter.owns(entity.getUUID())&&shelter.sound()||covered(selected))
                || !reachable(selected, false)) return false;
        releaseHome(); home = GlobalPos.of(level().dimension(), selected.immutable()); naturalHome = true;
        homeWait = 200; entity.setPersistenceRequired(); return true;
    }
    public void releaseHome() {
        if (home != null && !naturalHome && home.dimension().equals(level().dimension()) && level().hasChunkAt(home.pos())
                && level().getBlockEntity(home.pos()) instanceof CareBlockEntity care) care.release(entity.getUUID());
        home = null; naturalHome = false;
    }
    public BlockPos homeStand() {
        if(entity.habitat().hasMemory())return entity.habitat().homeStand();
        if(entity.getVariant().isHouse()&&entity.house().homeStand()!=null)return entity.house().homeStand();
        if (home == null || !home.dimension().equals(level().dimension()) || !level().hasChunkAt(home.pos())) return null;
        if (naturalHome) return io.github.longshou.mcyukkuri.settlement.ShelterBlock.shelter(level(),home.pos())==null
                && dryStand(home.pos()) && covered(home.pos()) ? home.pos() : null;
        if (level().getBlockEntity(home.pos()) instanceof CareBlockEntity care && entity.getUUID().equals(care.resident())) return entrance(home.pos());
        return null;
    }
    private void maintainHome() {
        if(entity.habitat().hasMemory()) {
            if(entity.habitat().unavailable()||entity.habitat().homeStand()!=null)return;
            entity.habitat().release();
        }
        if (home != null) {
            // Unloaded or other-dimension homes remain memories, not destroyed homes.
            if (!home.dimension().equals(level().dimension()) || !level().hasChunkAt(home.pos())) return;
            if (homeStand() != null) return;
            releaseHome();
        }
        if (entity.getVariant().isPredator()) { if (entity.predator().findNest(false)) return; }
        else if(entity.habitat().findHome())return;
        BlockPos nest = nearestCare(CareBlock.Kind.NEST, false);
        if (nest != null && assignHome(GlobalPos.of(level().dimension(), nest))) return;
        BlockPos best = null; double distance = Double.MAX_VALUE;
        for (BlockPos scan : BlockPos.betweenClosed(entity.blockPosition().offset(-8,-2,-8), entity.blockPosition().offset(8,2,8))) {
            if(io.github.longshou.mcyukkuri.settlement.ShelterBlock.shelter(level(),scan)!=null)continue;
            if (!dryStand(scan) || !covered(scan)) continue;
            double candidate = entity.distanceToSqr(Vec3.atBottomCenterOf(scan));
            if (candidate < distance && reachable(scan, false)) { best = scan.immutable(); distance = candidate; }
        }
        if (best != null) { home = GlobalPos.of(level().dimension(), best); naturalHome = true; }
    }
    private BlockPos nearestCare(CareBlock.Kind kind, boolean requireFood) {
        BlockPos best = null; double distance = Double.MAX_VALUE;
        for (BlockPos scan : BlockPos.betweenClosed(entity.blockPosition().offset(-8,-2,-8), entity.blockPosition().offset(8,2,8))) {
            if (!level().hasChunkAt(scan) || !(level().getBlockEntity(scan) instanceof CareBlockEntity care) || care.kind() != kind) continue;
            if (requireFood && !care.hasFood(this::acceptsFood) || kind == CareBlock.Kind.TOILET && care.waste() >= care.capacity()
                    || kind == CareBlock.Kind.NEST && care.resident() != null && !care.resident().equals(entity.getUUID())) continue;
            double candidate = entity.distanceToSqr(Vec3.atCenterOf(scan));
            if (candidate < distance) {
                boolean access = false;
                if (kind == CareBlock.Kind.NEST) {
                    BlockPos stand = entrance(scan); access = stand != null && reachable(stand,false);
                } else for (Direction direction : Direction.Plane.HORIZONTAL) {
                    BlockPos stand = scan.relative(direction);
                    if (dryStand(stand) && reachable(stand,false)) { access=true; break; }
                }
                if (access) { best = scan.immutable(); distance = candidate; }
            }
        }
        return best;
    }
    private boolean seesWater(BlockPos water){
        var surface=new Vec3(water.getX()+.5,water.getY()+level().getFluidState(water).getHeight(level(),water)+.05,water.getZ()+.5);
        var hit=level().clip(new net.minecraft.world.level.ClipContext(entity.getEyePosition(),surface,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,entity));
        return hit.getType()==net.minecraft.world.phys.HitResult.Type.MISS||hit.getBlockPos().equals(water);
    }
    private BlockPos drinkStand(BlockPos water){
        BlockPos best=null;double distance=Double.MAX_VALUE;
        for(var side:Direction.Plane.HORIZONTAL)for(int rise=0;rise<=1;rise++){
            var stand=water.relative(side).above(rise);double d=entity.distanceToSqr(Vec3.atBottomCenterOf(stand));
            if(d<distance&&dryStand(stand)&&reachable(stand,false)){best=stand;distance=d;}
        }
        return best;
    }
    boolean hasReachableDrink() { return nearestDrink() != null; }
    private BlockPos nearestDrink(){
        BlockPos best=null;double distance=Double.MAX_VALUE;
        for(var water:BlockPos.betweenClosed(entity.blockPosition().offset(-6,-2,-6),entity.blockPosition().offset(6,1,6))){
            if(!level().hasChunkAt(water)||!level().getFluidState(water).is(net.minecraft.tags.FluidTags.WATER))continue;
            double d=entity.distanceToSqr(Vec3.atCenterOf(water));
            if(d<distance&&drinkStand(water)!=null){best=water.immutable();distance=d;}
        }
        return best;
    }
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
    public boolean reachable(BlockPos target, boolean escapeWater) { return path(target, escapeWater) != null; }
    public boolean navigate(BlockPos target, double speed, boolean escapeWater) {
        Path path = path(target, escapeWater); return path != null && entity.getNavigation().moveTo(path, speed);
    }
    public void escapeWater() {
        entity.getJumpControl().jump();
        if (entity.tickCount % 10 != 0 && !entity.getNavigation().isDone()) return;
        BlockPos best = null; double distance = Double.MAX_VALUE;
        for (BlockPos scan : BlockPos.betweenClosed(entity.blockPosition().offset(-6,-1,-6), entity.blockPosition().offset(6,3,6))) {
            double candidate = entity.distanceToSqr(Vec3.atBottomCenterOf(scan));
            if (candidate < distance && dryStand(scan) && reachable(scan, true)) { best = scan.immutable(); distance = candidate; }
        }
        if (best != null) navigate(best, 1.4, true);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); tag.putInt("Version", 1);
        tag.putFloat("Nutrition", nutrition); tag.putFloat("Energy", energy); tag.putFloat("Hygiene", hygiene);
        tag.putFloat("Bladder", bladder); tag.putFloat("Wetness", wetness); tag.putLong("AgeTicks", ageTicks);
        tag.putInt("ImmersionTicks", immersionTicks);
        tag.put("Temperature", temperature.save());
        tag.put("Wintering", entity.wintering().save());
        tag.putInt("ExerciseReliefTicks",exerciseReliefTicks);
        tag.putFloat("Dryness",dryness);tag.putInt("SpicyTicks",spicyTicks);tag.putInt("SprayDelay",sprayDelay);tag.putInt("DrugSleep",drugSleep);tag.putBoolean("ReturnDoseBottle",returnDoseBottle);
        tag.putInt("HungerProgress", metabolismTicks); tag.putInt("EnergyProgress", energyTicks); tag.putInt("HygieneProgress", hygieneTicks);
        tag.putInt("RestWait", restWait); tag.putInt("SocialWait", socialWait);
        if (entity.shelteredInHat() && task == Task.SLEEP) tag.putInt("HatSleepTicks", sleepTicks);
        if (!mouth.isEmpty()) tag.put("Mouth", mouth.save(entity.registryAccess()));
        tag.putInt("MealTicks", mealTicks); tag.putBoolean("SharedMeal", sharedMeal); tag.putBoolean("PreparedMeal",preparedMeal); tag.putBoolean("BowlMeal", bowlMeal); if (feeder != null) tag.putUUID("Feeder", feeder);
        if (home != null) { tag.putString("HomeDimension", home.dimension().location().toString()); tag.putLong("HomePos", home.pos().asLong()); tag.putBoolean("NaturalHome", naturalHome); }
        tag.putBoolean("Accessory", accessory); if (accessoryToken != null) tag.putUUID("AccessoryToken", accessoryToken);
        tag.put("Personality", personality.save()); return tag;
    }
    private static float bounded(CompoundTag tag, String name, float fallback) {
        float value = tag.contains(name, Tag.TAG_ANY_NUMERIC) ? tag.getFloat(name) : fallback;
        return Float.isFinite(value) ? Math.clamp(value, 0, 100) : fallback;
    }
    public void load(CompoundTag tag) {
        nutrition = bounded(tag,"Nutrition",60); energy = bounded(tag,"Energy",80); hygiene = bounded(tag,"Hygiene",100);
        bladder = bounded(tag,"Bladder",0); wetness = bounded(tag,"Wetness",0); ageTicks = Math.max(0,tag.getLong("AgeTicks"));
        immersionTicks = Math.clamp(tag.getInt("ImmersionTicks"), 0, 19);
        temperature.load(tag.getCompound("Temperature"));
        entity.wintering().load(tag.getCompound("Wintering"));
        exerciseReliefTicks=Math.clamp(tag.getInt("ExerciseReliefTicks"),0,1200);
        dryness=bounded(tag,"Dryness",0);spicyTicks=Math.clamp(tag.getInt("SpicyTicks"),0,240);sprayDelay=Math.clamp(tag.getInt("SprayDelay"),0,90);drugSleep=Math.clamp(tag.getInt("DrugSleep"),0,400);returnDoseBottle=tag.getBoolean("ReturnDoseBottle");
        metabolismTicks = Math.clamp(tag.getInt("HungerProgress"),0,23999); energyTicks = Math.clamp(tag.getInt("EnergyProgress"),0,23999);
        hygieneTicks = Math.clamp(tag.getInt("HygieneProgress"),0,23999); restWait = Math.clamp(tag.getInt("RestWait"),0,1200);
        socialWait = Math.clamp(tag.getInt("SocialWait"),0,2400);
        mouth = tag.contains("Mouth",Tag.TAG_COMPOUND) ? ItemStack.parseOptional(entity.registryAccess(),tag.getCompound("Mouth")) : ItemStack.EMPTY;
        if (!mouth.isEmpty() && ((!edible(mouth)&&!Consumables.oral(mouth)) || mouth.getCount()!=1)) mouth=ItemStack.EMPTY;
        mealTicks = Math.clamp(tag.getInt("MealTicks"),0,63);
        if (!mouth.isEmpty()) mealTicks = Math.clamp(mealTicks,1,44);
        else if (mealTicks < 45) mealTicks=0;
        sharedMeal=tag.getBoolean("SharedMeal"); preparedMeal=tag.getBoolean("PreparedMeal") || InfantFood.isMash(mouth); bowlMeal=tag.getBoolean("BowlMeal"); feeder=tag.hasUUID("Feeder")?tag.getUUID("Feeder"):null;
        home = null;
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("HomeDimension"));
        if (dimension!=null && tag.contains("HomePos")) home=GlobalPos.of(ResourceKey.create(Registries.DIMENSION,dimension),BlockPos.of(tag.getLong("HomePos")));
        naturalHome=tag.getBoolean("NaturalHome"); accessory=!tag.contains("Accessory")||tag.getBoolean("Accessory");
        accessoryToken=tag.hasUUID("AccessoryToken")?tag.getUUID("AccessoryToken"):null;
        personality=new YukkuriPersonality(entity.getUUID()); personality.load(tag.getCompound("Personality"));
        task=Task.NONE; targetFood=null; targetBlock=null; sleepTicks=toiletTicks=0; homeWait=20; planWait=0;
        if (tag.contains("HatSleepTicks")) { task=Task.SLEEP; sleepTicks=Math.clamp(tag.getInt("HatSleepTicks"),0,600); }
    }
}
