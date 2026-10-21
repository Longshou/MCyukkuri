package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.interaction.CareAction;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.life.Sweets;
import io.github.longshou.mcyukkuri.notebook.SurvivalProgress;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import io.github.longshou.mcyukkuri.subspecies.AquaticLife.ShoreWork;
import java.util.EnumSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Concrete home lessons. A real task can establish a skill once; no lesson score is retained. */
public final class PetLessons {
    public static final String LOCK = "lesson";
    public static final int FEEDBACK_WINDOW = 80, COOLDOWN = 200, DAY = 24000;
    public enum State { IDLE, SHORE_PENDING, RUNNING, AWAITING }

    private final YukkuriEntity animal;
    private final EnumSet<PetSkill> known = EnumSet.noneOf(PetSkill.class);
    private State state = State.IDLE;
    private PetSkill kind;
    private UUID owner, markCard;
    private boolean completed;
    private int feedbackLeft, today, dayClock, routineObserveLeft;
    /** One submitted lesson may wait for the passenger's real shore landing; no lock, fee or lesson result exists yet. */
    private long shorePendingUntil;

    public PetLessons(YukkuriEntity animal) { this.animal = animal; }
    public State state() { return state; }
    public PetSkill kind() { return kind; }
    public boolean running() { return state == State.RUNNING; }
    public boolean awaitingFeedback() { return state == State.AWAITING && feedbackLeft > 0; }
    public int today() { return today; }
    public int dayClock() { return dayClock; }
    public boolean knows(PetSkill skill) { return skill != null && known.contains(skill); }
    public EnumSet<PetSkill> known() { return known.clone(); }

    /**
     * Called only at a concrete task's finish. It draws once to establish an unknown skill and gives
     * the personality-owned, first-learning intelligence experience. Repeated use has no score or
     * further attribute side effect.
     */
    public boolean complete(PetSkill skill) {
        if (skill == null || known.contains(skill) || animal.level().isClientSide) return false;
        var personality = animal.life().personality();
        double roll = animal.getRandom().nextDouble();
        double chance = personality.learningProbability(animal.mind().stressView());
        if (roll >= chance) {
            GameplayLog.limited(animal, "lesson", "learn.failed." + skill, 1200,
                    "learning declined skill={} chance={} roll={} stress={}", skill, chance, roll, animal.mind().stressView());
            return false;
        }
        known.add(skill);
        personality.experienced(Trait.INTELLIGENCE, 8);
        GameplayLog.event(animal, "lesson", "skill learned skill={} chance={} roll={} intelligence={}",
                skill, chance, roll, personality.intelligence());
        return true;
    }

    public void restore(int today, int dayClock) { this.today = Math.clamp(today, 0, 999); this.dayClock = Math.clamp(dayClock, 0, DAY); }
    public void save(CompoundTag tag) {
        int mask = 0;
        for (var skill : known) mask |= 1 << skill.ordinal();
        tag.putInt("KnownSkills", mask);
    }
    public void load(CompoundTag tag) {
        known.clear(); int mask = tag.getInt("KnownSkills");
        for (var skill : PetSkill.values()) if ((mask & 1 << skill.ordinal()) != 0) known.add(skill);
    }
    private ServerLevel level() { return (ServerLevel) animal.level(); }

    public boolean preconditionOk(Player player, PetSkill skill) {
        if (skill == null) return false;
        return switch (skill) {
            case WAIT -> snack(player) != null;
            case FOOD_BOWL, TOILET, BEDTIME -> facility(skill) != null;
            case READING, ARITHMETIC -> true;
        };
    }
    public String refusal(Player player, PetSkill skill) { return refusal(player,skill,false); }
    /** Preview stays side-effect free. A committed request may validate everything but its current boat position. */
    private String refusal(Player player, PetSkill skill,boolean requireDryFeet) {
        if (knows(skill)) return "already_known";
        if (requireDryFeet && animal.isPassenger()) return "not_ready";
        if (!(requireDryFeet ? animal.pet().readyFor(player) : animal.pet().readyForShoreRequest(player))) return "not_ready";
        if (animal.pet().cooldown() > 0) return "cooldown";
        return preconditionOk(player, skill) ? null : "need_" + skill.key();
    }
    public boolean start(Player player, PetSkill skill) {
        if (animal.level().isClientSide || state != State.IDLE || skill == null) return false;
        var reason = refusal(player, skill, false);
        if (reason != null) {
            GameplayLog.limited(animal, "lesson", "start." + reason, 20,
                    "lesson refused owner={} skill={} reason={}", player.getUUID(), skill, reason);
            player.displayClientMessage(Component.translatable("pet.mcyukkuri.lesson.refuse." + reason), true); return false;
        }
        // A button press is the actual lesson request. Keep it bounded until the captain completes the
        // ordinary landing, then validate the same request again before creating any lesson state.
        if (animal.isPassenger()) {
            if (!animal.aquaticLife().requestShoreWork(ShoreWork.HUMAN)) {
                player.displayClientMessage(Component.translatable("pet.mcyukkuri.lesson.refuse.not_ready"), true); return false;
            }
            state=State.SHORE_PENDING;kind=skill;owner=player.getUUID();shorePendingUntil=level().getGameTime()+600;
            GameplayLog.event(animal,"lesson","lesson shore pending owner={} skill={} until={}",owner,skill,shorePendingUntil);
            return true;
        }
        return begin(player,skill);
    }
    /** The pending callback and the direct player request share this only-after-landing start path. */
    private boolean begin(Player player,PetSkill skill) {
        var reason=refusal(player,skill,true);
        if(reason!=null)return false;
        animal.aquaticLife().holdShoreWork(ShoreWork.HUMAN);
        // Facilities keep their ordinary life state machine. This observation never performs a
        // timed imitation: success arrives only through PetLife.routineCompleted after food,
        // toileting, or a full rest has actually completed.
        if (skill == PetSkill.FOOD_BOWL || skill == PetSkill.TOILET || skill == PetSkill.BEDTIME) {
            state = State.RUNNING; kind = skill; owner = player.getUUID(); completed = false; routineObserveLeft = 720;
            GameplayLog.event(animal, "lesson", "lesson started owner={} skill={} mode=routine", owner, skill);
            player.displayClientMessage(Component.translatable("pet.mcyukkuri.lesson.started", animal.getDisplayName(), Component.translatable("pet.mcyukkuri.skill." + skill.key())), true);
            return true;
        }
        var run = new Run(player, skill);
        if (!run.prepare() || !animal.pet().lock(LOCK, player.getUUID())) {
            animal.aquaticLife().releaseShoreWork(ShoreWork.HUMAN); return false;
        }
        state = State.RUNNING; kind = skill; owner = player.getUUID(); completed = false;
        GameplayLog.event(animal, "lesson", "lesson started owner={} skill={} mode=directed", owner, skill);
        animal.pet().direct(run);
        player.displayClientMessage(Component.translatable("pet.mcyukkuri.lesson.started", animal.getDisplayName(), Component.translatable("pet.mcyukkuri.skill." + skill.key())), true);
        return true;
    }

    private record Stand(BlockPos block, BlockPos stand, CareBlock.Kind kind) {}
    private Stand facility(PetSkill skill) {
        CareBlock.Kind needed = switch (skill) {
            case FOOD_BOWL -> CareBlock.Kind.BOWL;
            case TOILET -> CareBlock.Kind.TOILET;
            case BEDTIME -> CareBlock.Kind.MAT;
            default -> null;
        };
        if (needed == null) return null;
        var origin = animal.blockPosition(); Stand best = null; double nearest = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-8, -2, -8), origin.offset(8, 2, 8))) {
            if (!level().hasChunkAt(pos) || !(level().getBlockEntity(pos) instanceof CareBlockEntity care) || care.kind() != needed) continue;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                var stand = pos.relative(d);
                if (!animal.life().dryStand(stand) || !animal.life().reachable(stand, false)) continue;
                double distance = animal.distanceToSqr(Vec3.atCenterOf(stand));
                if (distance < nearest) { nearest = distance; best = new Stand(pos.immutable(), stand.immutable(), needed); }
            }
        }
        return best;
    }
    private ItemStack snack(Player player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            var stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && Sweets.is(stack) && animal.life().acceptsFood(stack)) return stack;
        }
        return null;
    }

    private final class Run implements PetLife.Director {
        private final Player player;
        private final PetSkill skill;
        private Stand stand;
        private int ticks, waitTicks;
        private UUID card;
        private boolean snackGiven, answerGiven;
        private float nutritionBefore;

        Run(Player player, PetSkill skill) { this.player = player; this.skill = skill; }
        boolean prepare() {
            stand = switch (skill) { case FOOD_BOWL, TOILET, BEDTIME -> facility(skill); default -> null; };
            return stand != null || skill != PetSkill.FOOD_BOWL && skill != PetSkill.TOILET && skill != PetSkill.BEDTIME;
        }
        @Override public void tick(YukkuriEntity ignored) {
            if (!player.isAlive() || player.level() != animal.level() || player.distanceToSqr(animal) > 144 || animal.isPanicking()
                    || animal.life().nutrition() < 15 || animal.life().energy() < 15 || animal.isInWaterOrBubble() || animal.isOnFire() || animal.hurtTime > 0) { interrupt(); return; }
            ticks++;
            switch (skill) {
                case WAIT -> waitForSnack();
                case READING, ARITHMETIC -> study();
                default -> interrupt();
            }
        }
        private void waitForSnack() {
            animal.getNavigation().stop();
            if (ticks == 1) {
                waitTicks = animal.life().personality().waitingTicks();
                animal.playAction("tremble"); card = Staging.card(level(), player.position().add(0, player.getBbHeight() + 1.1, 0), Component.translatable("pet.mcyukkuri.card.promise"), waitTicks + 130, 1.0f);
            }
            if (!snackGiven && ticks < waitTicks) return;
            if (!snackGiven) {
                var sweet = snack(player);
                if (sweet == null) { interrupt(); return; }
                var one = sweet.copyWithCount(1);
                nutritionBefore = animal.life().nutrition();
                if (!animal.life().beginMeal(one, player.getUUID(), false)) { interrupt(); return; }
                if (!player.getAbilities().instabuild) sweet.shrink(1);
                snackGiven = true;
            }
            if (snackGiven && animal.life().mealTicks() == 0 && animal.life().nutrition() > nutritionBefore) finish();
        }
        private void study() {
            animal.getNavigation().stop();
            if (ticks == 1) {
                card = Staging.card(level(), animal.position().add(0, animal.getBbHeight() + .55, 0), Component.translatable(skill == PetSkill.READING ? "pet.mcyukkuri.card.read" : "pet.mcyukkuri.card.sum"), 100, 1.2f);
                animal.playAction("think");
            }
            if (ticks == 50) answerGiven = animal.speak("exam_answer");
            if (ticks >= 80) { if (answerGiven) finish(); else interrupt(); }
        }
        private void finish() {
            if (card != null) Staging.removeCard(level(), card);
            animal.playAction("idle"); completed = complete(skill); finishLesson(completed);
        }
        private void interrupt() { if (card != null) Staging.removeCard(level(), card); PetLessons.this.interrupt(player); }
        @Override public void stop(YukkuriEntity ignored) {
            if (card != null) Staging.removeCard(level(), card);
            if (state == State.RUNNING) {
                state = State.IDLE;
                animal.aquaticLife().releaseShoreWork(ShoreWork.HUMAN);
            }
        }
    }

    private void finishLesson(boolean learnedNow) {
        if (state != State.RUNNING) return;
        animal.pet().setCooldown(COOLDOWN);
        if (dayClock <= 0) dayClock = 1;
        today++; state = State.AWAITING; feedbackLeft = FEEDBACK_WINDOW;
        GameplayLog.event(animal, "lesson", "lesson ended owner={} skill={} learnedNow={} awaitingFeedbackTicks={}",
                owner, kind, learnedNow, feedbackLeft);
        animal.pet().unlock(LOCK);
        animal.aquaticLife().releaseShoreWork(ShoreWork.HUMAN);
        markCard = Staging.card(level(), animal.position().add(0, animal.getBbHeight() + .6, 0), Component.translatable("pet.mcyukkuri.card.feedback"), FEEDBACK_WINDOW + 10, 1.6f);
        var player = level().getPlayerByUUID(owner);
        if (player != null) player.displayClientMessage(Component.translatable("pet.mcyukkuri.lesson." + (learnedNow ? "success" : "failure"), animal.getDisplayName()), true);
    }
    private void interrupt(Player player) {
        if (state != State.RUNNING) return;
        animal.pet().unlock(LOCK); state = State.IDLE;
        animal.aquaticLife().releaseShoreWork(ShoreWork.HUMAN);
        GameplayLog.event(animal, "lesson", "lesson interrupted owner={} skill={} nutrition={} energy={} panic={} water={} fire={} hurtTime={}",
                owner, kind, animal.life().nutrition(), animal.life().energy(), animal.isPanicking(),
                animal.isInWaterOrBubble(), animal.isOnFire(), animal.hurtTime);
        player.displayClientMessage(Component.translatable("pet.mcyukkuri.lesson.interrupted", animal.getDisplayName()), true);
    }
    public void feedback(Player player, CareAction action) {
        if (!awaitingFeedback() || !player.getUUID().equals(owner)) return;
        if (action == CareAction.GENTLE && completed) { animal.pet().addStress(-3); SurvivalProgress.award(player, "lesson_praised"); animal.playAction("joy_jump"); }
        else if (action == CareAction.FLICK) animal.pet().addStress(10);
        else if (action == CareAction.COLD) animal.pet().addStress(4);
        end();
    }
    public void reward(Player player, ItemStack sweet) { if (awaitingFeedback() && player.getUUID().equals(owner) && Sweets.is(sweet)) { if (completed) animal.pet().addStress(-5); end(); } }
    private void end() { state = State.IDLE; feedbackLeft = 0; if (markCard != null) Staging.removeCard(level(), markCard); markCard = null; }
    public void tick() {
        if (animal.level().isClientSide) return;
        if (dayClock > 0 && ++dayClock >= DAY) { dayClock = 0; today = 0; }
        if(state==State.SHORE_PENDING){
            var player=owner==null?null:level().getPlayerByUUID(owner);
            var skill=kind;
            if(player==null||!player.isAlive()||player.level()!=animal.level()||skill==null||level().getGameTime()>=shorePendingUntil){
                GameplayLog.event(animal,"lesson","lesson shore pending cancelled owner={} skill={} reason={}",owner,skill,
                        player==null?"owner_unavailable":skill==null?"missing_skill":"landing_timeout");
                state=State.IDLE;kind=null;owner=null;shorePendingUntil=0;animal.aquaticLife().releaseShoreWork(ShoreWork.HUMAN);return;
            }
            if(animal.isPassenger()){
                if(!animal.aquaticLife().requestShoreWork(ShoreWork.HUMAN)){
                    state=State.IDLE;kind=null;owner=null;shorePendingUntil=0;animal.aquaticLife().releaseShoreWork(ShoreWork.HUMAN);
                    player.displayClientMessage(Component.translatable("pet.mcyukkuri.lesson.refuse.not_ready"), true);
                }
                return;
            }
            if (!animal.onGround() || animal.aquaticLife().shoreVisitRequested()) return;
            state=State.IDLE;kind=null;owner=null;shorePendingUntil=0;
            if(!begin(player,skill)){
                animal.aquaticLife().releaseShoreWork(ShoreWork.HUMAN);
                player.displayClientMessage(Component.translatable("pet.mcyukkuri.lesson.refuse.not_ready"), true);
            }
            return;
        }
        if (state == State.RUNNING) animal.aquaticLife().holdShoreWork(ShoreWork.HUMAN);
        if (state == State.RUNNING && routineObserveLeft > 0) {
            if (knows(kind)) { completed = true; routineObserveLeft = 0; finishLesson(true); }
            else if (--routineObserveLeft == 0) {
                state = State.IDLE; kind = null; owner = null;
                animal.aquaticLife().releaseShoreWork(ShoreWork.HUMAN);
            }
            return;
        }
        if (state == State.AWAITING && (!animal.isAlive() || --feedbackLeft <= 0)) end();
    }
}
