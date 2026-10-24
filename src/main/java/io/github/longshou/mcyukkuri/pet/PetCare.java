package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.interaction.CareAction;
import io.github.longshou.mcyukkuri.item.SwaddleStyle;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Everyday care of an owner's pet that is not part of the badge record: sweets, habit praise, requests, walks, harness and swaddle
 * (docs/PET_SHOP.md). Server-authoritative; it only ticks while the pet is loaded and nothing here depends on frame rate.
 * All counters are per game day (24000 ticks of game time) and reset the first time the pet notices a new day.
 */
public final class PetCare {
    public static final long DAY = 24000L;
    /** Habit praise: ticks the owner has to react, praise gains per game day and owner distance. */
    public static final int HABIT_WINDOW = 80, HABITS_PER_DAY = 6, HABIT_RANGE = 12;
    /** Ticks before the owner can ask the same pet again. */
    public static final int ASK_COOLDOWN = 100;
    private final YukkuriEntity animal;
    private final PetRequests requests;
    private final PetWalk walk;
    private UUID wearOwner;
    private int swaddleClock;
    private int day = Integer.MIN_VALUE, snacks, habits, habitLeft;
    private long askUntil;
    private boolean harness, swaddle;
    private SwaddleStyle swaddleStyle = SwaddleStyle.KNIT;
    private UUID habitCard;

    public PetCare(YukkuriEntity animal) { this.animal = animal; requests = new PetRequests(animal); walk = new PetWalk(animal); }
    public PetRequests requests() { return requests; }
    public PetWalk walk() { return walk; }

    // worn gear: the harness (a body band with a back ring for the lead, never the accessory) and the swaddle (a warming cloth for the young)
    public boolean harnessed() { return harness; }
    public boolean swaddled() { return swaddle; }
    /**
     * Bit 0 harness, bit 1 swaddle, bits 6-8 the swaddle's style: what the client draws and what the lead check reads (the leaf swaddle
     * adds its own bits, YukkuriEntity.wornBits).
     */
    public int wearBits() { return (harness ? 1 : 0) | (swaddle ? 2 | swaddleStyle.ordinal() << 6 : 0); }
    private void syncWear() { animal.syncWear(); }
    private void say(Player player, String key) { player.displayClientMessage(Component.translatable("pet.mcyukkuri." + key, animal.getDisplayName()), true); }
    /** Whether this player may lead the pet right now (its owner, harness on, not busy): the lead guard asks this before vanilla ties the lead. */
    public String leashRefusal(Player player) {
        var pet = animal.pet();
        if (!pet.isPet() || !pet.owned(player)) return "not_yours";
        if (!harness) return "need_harness";
        if (pet.busy()) return "busy";
        return null;
    }
    /** Right-click with the harness item: fit it on the owner's own independent pet. */
    public boolean putOnHarness(Player player, ItemStack stack) {
        var pet = animal.pet();
        String why = !pet.isPet() || !pet.owned(player) ? "not_yours" : harness ? "already" : !animal.childhood().canLiveIndependently() ? "too_young"
                : player.distanceToSqr(animal) > 9 ? "too_far" : pet.busy() || !pet.calm() ? "not_calm" : null;
        if (why != null) { say(player, "harness.refuse." + why); return false; }
        harness = true; wearOwner = player.getUUID(); syncWear();
        if (!player.getAbilities().instabuild) stack.shrink(1);
        pet.contact(player.getUUID(), true);
        animal.playAction("greet"); animal.showReaction("happy", "harness_on", 80);
        return true;
    }
    /** A resident (not a player) fits the harness on its own pet before a walk on the lead; only call after the owner is set (a change of owner strips worn gear). */
    public void fitHarnessFor(UUID wearer) {
        if (harness) return;
        harness = true; wearOwner = wearer; syncWear();
    }
    /** Right-click with the swaddle cloth: wrap the owner's own baby or young one (never one that can live on its own). */
    public boolean putOnSwaddle(Player player, ItemStack stack) {
        var pet = animal.pet();
        String why = !pet.isPet() || !pet.owned(player) ? "not_yours" : swaddle ? "already" : !swaddleAge() ? "too_old" : player.distanceToSqr(animal) > 9 ? "too_far"
                : animal.isInWaterOrBubble() || animal.isOnFire() || animal.isPanicking() ? "not_calm" : null;
        if (why != null) { say(player, "swaddle.refuse." + why); return false; }
        swaddle = true; swaddleStyle = SwaddleStyle.of(stack); wearOwner = player.getUUID(); swaddleClock = 0; syncWear();
        if (!player.getAbilities().instabuild) stack.shrink(1);
        GameplayLog.event(animal, "interaction", "swaddle on player={} style={}", player.getUUID(), swaddleStyle.id);
        pet.contact(player.getUUID(), true);
        animal.showReaction("happy", "swaddle_on", 80);
        requests.answer(PetRequests.Kind.WARM);
        return true;
    }
    /** CareAction.UNWRAP. */
    public boolean takeOffSwaddle(Player player) {
        if (!swaddle || !animal.pet().owned(player)) return false;
        swaddle = false; syncWear();
        giveBack(swaddleStyle.stack(), player);
        GameplayLog.event(animal, "interaction", "swaddle off reason=owner player={} style={}", player.getUUID(), swaddleStyle.id);
        return true;
    }
    /** Only a baby or young one that cannot live independently yet is swaddled; adults never. */
    private boolean swaddleAge() { return !animal.family().adult() && !animal.childhood().canLiveIndependently(); }
    /** CareAction.REMOVE_HARNESS: the harness comes off (the lead is returned first) and goes back to the owner. */
    public boolean takeOffHarness(Player player) {
        if (!harness || !animal.pet().owned(player)) return false;
        if (animal.isLeashed()) animal.dropLeash(true, true);
        harness = false; syncWear();
        giveBack(new ItemStack(ModItems.PET_HARNESS.get()), player);
        return true;
    }
    /** The lead goes back to the player who held it (nothing for creative mode); at a fence post or for a dead holder it drops at the pet. */
    public void returnLead(Entity holder) {
        var lead = new ItemStack(Items.LEAD);
        if (holder instanceof Player p && p.isAlive()) { if (!p.hasInfiniteMaterials() && !p.getInventory().add(lead)) p.drop(lead, false); }
        else animal.spawnAtLocation(lead);
    }
    private void giveBack(ItemStack stack, Player preferred) {
        Player to = preferred != null ? preferred : wearOwner == null ? null : animal.level().getPlayerByUUID(wearOwner);
        if (to != null && to.isAlive() && to.level() == animal.level()) { if (!to.getInventory().add(stack)) to.drop(stack, false); }
        else animal.spawnAtLocation(stack);
    }
    /** Ownership ends or changes (sale, adoption, abandonment): the lead is released and the worn gear goes back to whoever fitted it.
     *  The same owner taking the pet again (a carrier trip) keeps the gear on. */
    public void ownerChanged(UUID newOwner) {
        if (animal.level().isClientSide) return;
        if (animal.isLeashed()) animal.dropLeash(true, true);
        if (newOwner != null && newOwner.equals(wearOwner)) return;
        stripWear(null);
        PetPrivileges.note(animal); // a bought pet's badge counts for its new owner
    }
    /** Death: the gear stays behind where the pet fell. */
    public void died() {
        if (animal.level().isClientSide) return;
        if (animal.isLeashed()) animal.dropLeash(true, true);
        if (harness) animal.spawnAtLocation(new ItemStack(ModItems.PET_HARNESS.get()));
        if (swaddle) { animal.spawnAtLocation(swaddleStyle.stack()); GameplayLog.event(animal, "interaction", "swaddle off reason=death dropped=1 style={}", swaddleStyle.id); }
        harness = false; swaddle = false; syncWear();
    }
    /** Any session lock (exam, lesson, sale, matchmaking) and carrier capture release the lead; the owner gets it back. */
    public void released() {
        if (animal.level().isClientSide) return;
        if (animal.isLeashed()) animal.dropLeash(true, true);
        PetPrivileges.note(animal); // about to leave the world in a carrier or a session: the owner's standing is on record
    }
    private void stripWear(Player preferred) {
        if (harness) giveBack(new ItemStack(ModItems.PET_HARNESS.get()), preferred);
        if (swaddle) { giveBack(swaddleStyle.stack(), preferred); GameplayLog.event(animal, "interaction", "swaddle off reason=owner_changed style={}", swaddleStyle.id); }
        harness = false; swaddle = false; syncWear();
    }
    private void tickWear() {
        if (animal.isLeashed()) {
            var holder = animal.getLeashHolder();
            if (!harness || !animal.pet().isPet() || holder instanceof Player p && !animal.pet().ownedBy(p.getUUID())) animal.dropLeash(true, true);
        }
        if (swaddle) {
            // The cloth comes off by itself once the young one can live on its own, or when it is soaked or burning; the owner gets it back.
            if (animal.tickCount % 20 == 5 && (!animal.pet().isPet() || !swaddleAge() || animal.isInWaterOrBubble() || animal.isOnFire())) {
                swaddle = false; syncWear(); giveBack(swaddleStyle.stack(), null);
                GameplayLog.event(animal, "interaction", "swaddle off reason={} style={}", !animal.pet().isPet() ? "not_pet" : !swaddleAge() ? "outgrown"
                        : animal.isOnFire() ? "fire" : "water", swaddleStyle.id);
            } else if (++swaddleClock >= 1200) { swaddleClock = 0; animal.pet().addStress(-1); } // calms it: stress eases twice as fast while swaddled
        }
        if (animal.level().getGameTime() % 40 == 0 && animal.visibleWear() != animal.wornBits()) syncWear();
    }

    private int today() { return (int)(animal.level().getGameTime() / DAY); }
    /** Rolls the daily counters over; returns whether a new day started since the last call. */
    private boolean rollDay() {
        int now = today();
        if (day == now) return false;
        boolean first = day == Integer.MIN_VALUE;
        day = now; snacks = 0; habits = 0;
        requests.newDay(!first); walk.newDay();
        return !first;
    }
    private ServerLevel level() { return (ServerLevel) animal.level(); }

    // habit praise: after the pet used its bowl, toilet or bed on its own, a short window where gentle care teaches manners
    public int habitsToday() { rollDay(); return habits; }
    public boolean habitWindow() { return habitLeft > 0; }
    /** PetLife.routineCompleted: 0 bowl, 1 toilet, 2 bed. Opens the praise window when the owner is watching. */
    public void habit(int kind) {
        if (animal.level().isClientSide || !animal.isAlive() || !animal.pet().isPet() || habitLeft > 0 || animal.pet().lessons().awaitingFeedback()) return;
        rollDay();
        if (habits >= HABITS_PER_DAY) return;
        var owner = animal.level().getPlayerByUUID(animal.pet().owner());
        if (owner == null || !owner.isAlive() || owner.isSpectator() || owner.distanceToSqr(animal) > HABIT_RANGE * HABIT_RANGE || !owner.hasLineOfSight(animal)) return;
        habitLeft = HABIT_WINDOW;
        habitCard = Staging.card(level(), markPos(), Component.translatable("pet.mcyukkuri.card.feedback"), HABIT_WINDOW + 10, 1.6f);
    }
    private Vec3 markPos() { return animal.position().add(0, animal.getBbHeight() + .6, 0); }
    private void closeHabit() { habitLeft = 0; if (habitCard != null) Staging.removeCard(level(), habitCard); habitCard = null; }
    /**
     * The owner's reaction after a care action succeeded (OperationLife and the empty-hand talk). Gentle care inside the habit window is praise
     * (a little stress relief); a reprimand or a flick teaches nothing and only stresses the pet.
     */
    public void feedback(Player player, CareAction action) {
        if (animal.level().isClientSide || !animal.pet().owned(player)) return;
        // Gentle words or a rub answer a pet that asked for affection; singing and exercise are answered when they finish (boredom drops).
        if (requests.pending() == PetRequests.Kind.NUZZLE && (action == CareAction.GENTLE || action == CareAction.RUB)) {
            animal.dailyLife().comfort(15); requests.answer(PetRequests.Kind.NUZZLE);
        }
        if (habitLeft > 0) {
            if (action == CareAction.GENTLE) {
                habits++;
                animal.pet().addStress(-1);
                animal.playAction("joy_jump"); animal.mind().felt(EmotionEvent.PRAISED); animal.showReaction("happy", "lesson_praised", 70); Staging.sparkle(level(), markPos());
                closeHabit();
            } else if (action == CareAction.COLD || action == CareAction.FLICK) {
                animal.pet().addStress(action == CareAction.COLD ? 2 : 4);
                closeHabit();
            }
        }
    }

    // "ask how it is" (CareAction.ASK): say the open request again, otherwise tell the mood; what it can explain grows with its lessons
    public boolean ask(Player player) {
        var pet = animal.pet(); var life = animal.life(); var daily = animal.dailyLife();
        if (animal.level().isClientSide || !pet.isPet() || !pet.owned(player) || pet.busy() || life.isSleeping() || animal.isPanicking() || life.mealTicks() > 0) return false;
        long now = animal.level().getGameTime();
        if (now < askUntil) return false;
        askUntil = now + ASK_COOLDOWN;
        if (requests.repeat()) return true;
        boolean bad = pet.stress() >= BondLevels.UNEASE_SWEETENED || pet.neglect() >= 30 || life.hasUrgentNeed() || life.temperature().cold() || requests.sulking();
        boolean good = !bad && pet.stress() <= BondLevels.UNEASE_LOW_LIMIT && pet.neglect() == 0 && daily.affectionNeed() < 50 && daily.boredom() < 50 && life.nutrition() >= 50;
        String mood = bad ? "bad" : good ? "good" : "ok";
        animal.playAction(bad ? "droop" : good ? "greet" : "look_around");
        animal.showReaction(bad ? "teary" : good ? "happy" : "neutral", "mood_" + mood, 90);
        if (pet.lessons().knows(PetSkill.READING)) player.displayClientMessage(Component.translatable("pet.mcyukkuri.ask.mood." + mood, animal.getDisplayName()), true);
        if (!good && life.personality().intelligence() >= 50) {
            String why = life.nutrition() < 45 ? "hungry" : life.temperature().cold() ? "cold" : requests.sulking() || requests.ignoredToday() > 0 ? "ignored"
                    : pet.neglect() >= 30 || daily.affectionNeed() >= 65 ? "lonely" : daily.boredom() >= 65 ? "bored" : life.energy() < 30 ? "tired"
                    : life.hygiene() < 40 ? "dirty" : pet.stress() >= BondLevels.UNEASE_SWEETENED ? "stressed" : "";
            if (!why.isEmpty()) player.sendSystemMessage(Component.translatable("pet.mcyukkuri.ask.reason." + why, animal.getDisplayName()));
        }
        return true;
    }

    // sweets
    public int snacksToday() { rollDay(); return snacks; }
    /** Counts one sweet eaten today and returns the new count. */
    public int countSnack() { rollDay(); return ++snacks; }
    /** The pet swallowed a sweet from its owner. */
    public void snackEaten() { requests.answer(PetRequests.Kind.SNACK); }

    public void tick() {
        if (animal.level().isClientSide) return;
        rollDay();
        requests.tick(); walk.tick(); tickWear();
        if (animal.tickCount % 100 == 61 && animal.pet().grade() != BadgeGrade.NONE) PetPrivileges.note(animal); // keeps the owner's recorded standing and its expiry current
        if (habitLeft > 0) {
            if (!animal.isAlive() || --habitLeft <= 0) closeHabit();
            else if (habitLeft % 4 == 0) Staging.moveCard(level(), habitCard, markPos());
        }
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putInt("Day", day); tag.putInt("Snacks", snacks); tag.putInt("Habits", habits);
        tag.putBoolean("Harness", harness); tag.putBoolean("Swaddle", swaddle); tag.putString("SwaddleStyle", swaddleStyle.id); tag.put("Requests", requests.save());
        tag.putInt("WalkUnits", walk.saveUnits()); tag.putDouble("WalkCarry", walk.carry()); if (wearOwner != null) tag.putUUID("WearOwner", wearOwner);
        return tag;
    }
    public void load(CompoundTag tag) {
        day = tag.contains("Day") ? tag.getInt("Day") : Integer.MIN_VALUE;
        snacks = Math.clamp(tag.getInt("Snacks"), 0, 99); habits = Math.clamp(tag.getInt("Habits"), 0, 99);
        habitLeft = 0; habitCard = null; askUntil = 0;
        harness = tag.getBoolean("Harness"); swaddle = tag.getBoolean("Swaddle"); swaddleStyle = SwaddleStyle.byId(tag.getString("SwaddleStyle")); requests.load(tag.getCompound("Requests"));
        walk.load(tag.getInt("WalkUnits"), tag.getDouble("WalkCarry")); wearOwner = tag.hasUUID("WearOwner") ? tag.getUUID("WearOwner") : null;
    }
}
