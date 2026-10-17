package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.interaction.CareAction;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Everyday care of an owner's pet that is not part of the badge record: sweets, habit praise, requests, walks, harness and swaddle
 * (docs/PET_SHOP.md section 12). Server-authoritative; it only ticks while the pet is loaded and nothing here depends on frame rate.
 * All counters are per game day (24000 ticks of game time) and reset the first time the pet notices a new day.
 */
public final class PetCare {
    public static final long DAY = 24000L;
    /** Habit praise: ticks the owner has to react, praise gains per game day, owner distance, manners gain, and idle days before one manners point is lost. */
    public static final int HABIT_WINDOW = 80, HABITS_PER_DAY = 6, HABIT_RANGE = 12, HABIT_GAIN = 2, DECAY_DAYS = 5;
    private final YukkuriEntity animal;
    private int day = Integer.MIN_VALUE, snacks, habits, idleDays, habitLeft;
    private UUID habitCard;

    public PetCare(YukkuriEntity animal) { this.animal = animal; }

    private int today() { return (int)(animal.level().getGameTime() / DAY); }
    /** Rolls the daily counters over; returns whether a new day started since the last call. */
    private boolean rollDay() {
        int now = today();
        if (day == now) return false;
        boolean first = day == Integer.MIN_VALUE;
        day = now; snacks = 0; habits = 0;
        if (first) return false;
        if (animal.pet().isPet() && ++idleDays >= DECAY_DAYS) { // manners fade slowly without practice: one point per five idle game days
            idleDays = 0;
            if (animal.pet().ability(Ability.MANNERS) > 0) animal.pet().setAbility(Ability.MANNERS, animal.pet().ability(Ability.MANNERS) - 1);
        }
        return true;
    }
    private ServerLevel level() { return (ServerLevel) animal.level(); }

    // habit praise: after the pet used its bowl, toilet or bed on its own, a short window where gentle care teaches manners
    /** Manners were practised (a habit praised or a manners lesson), so the fading clock starts again. */
    public void practiced() { idleDays = 0; }
    public int idleDays() { return idleDays; }
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
     * (manners +2 times the pedigree factor, a little stress relief); a reprimand or a flick teaches nothing and only stresses the pet.
     */
    public void feedback(Player player, CareAction action) {
        if (animal.level().isClientSide || !animal.pet().owned(player)) return;
        if (habitLeft > 0) {
            if (action == CareAction.GENTLE) {
                habits++;
                animal.pet().learn(Ability.MANNERS, HABIT_GAIN);
                animal.pet().addStress(-1);
                practiced();
                animal.playAction("joy_jump"); animal.showReaction("happy", "lesson_praised", 70); Staging.sparkle(level(), markPos());
                closeHabit();
            } else if (action == CareAction.COLD || action == CareAction.FLICK) {
                animal.pet().addStress(action == CareAction.COLD ? 2 : 4);
                closeHabit();
            }
        }
    }

    // sweets
    public int snacksToday() { rollDay(); return snacks; }
    /** Counts one sweet eaten today and returns the new count. */
    public int countSnack() { rollDay(); return ++snacks; }
    /** The pet swallowed a sweet from its owner. */
    public void snackEaten() {}

    public void tick() {
        if (animal.level().isClientSide) return;
        rollDay();
        if (habitLeft > 0) {
            if (!animal.isAlive() || --habitLeft <= 0) closeHabit();
            else if (habitLeft % 4 == 0) Staging.moveCard(level(), habitCard, markPos());
        }
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putInt("Day", day); tag.putInt("Snacks", snacks); tag.putInt("Habits", habits); tag.putInt("IdleDays", idleDays);
        return tag;
    }
    public void load(CompoundTag tag) {
        day = tag.contains("Day") ? tag.getInt("Day") : Integer.MIN_VALUE;
        snacks = Math.clamp(tag.getInt("Snacks"), 0, 99); habits = Math.clamp(tag.getInt("Habits"), 0, 99); idleDays = Math.clamp(tag.getInt("IdleDays"), 0, 99);
        habitLeft = 0; habitCard = null;
    }
}
