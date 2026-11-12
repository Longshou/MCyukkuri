package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.interaction.CareAction;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.life.Sweets;
import io.github.longshou.mcyukkuri.notebook.SurvivalProgress;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Practice belongs to the individual. A lesson observes the same outcome as everyday behavior. */
public final class PetLessons {
    public static final String LOCK = "lesson";
    public static final int FEEDBACK_WINDOW = 80, COOLDOWN = 200, DAY = 24000;
    private static final int WILD_DECAY = 7 * DAY;
    public enum State { IDLE, RUNNING, AWAITING }

    private final YukkuriEntity animal;
    private final int[] experience = new int[PetSkill.values().length];
    private State state = State.IDLE;
    private PetSkill kind;
    private UUID owner, markCard;
    private boolean completed;
    private boolean coached;
    private int feedbackLeft, today, dayClock, routineObserveLeft, wildDays = -1;
    private long nextStudy;
    private PetSkill lastStudy;
    private int lastSeenYoung;

    public PetLessons(YukkuriEntity animal) { this.animal = animal; }
    public State state() { return state; }
    public PetSkill kind() { return kind; }
    public boolean running() { return state == State.RUNNING; }
    public boolean awaitingFeedback() { return state == State.AWAITING && feedbackLeft > 0; }
    public int today() { return today; }
    public int dayClock() { return dayClock; }
    public int xp(PetSkill skill) { return experience[skill.ordinal()]; }
    public int level(PetSkill skill) { return PetSkill.levelAt(xp(skill)); }
    /** Exam eligibility keeps a single projection of practiced skill. */
    public boolean knows(PetSkill skill) { return level(skill) >= 2; }

    public boolean practicing(PetSkill skill) { return running() && kind == skill; }
    public boolean directed() { return running() && LOCK.equals(animal.pet().busyWith()); }

    public boolean studyWith(Player player) {
        var exercise = studyExample(player);
        if (exercise == null) return false;
        PetStudy.answer(animal, exercise);
        return true;
    }

    public PetStudy.Exercise studyExample(Player player) {
        long now = level().getGameTime();
        if (!animal.pet().isPet() || state != State.IDLE || now < nextStudy || !animal.childhood().canLearn()) return null;
        nextStudy = now + 600;
        PetSkill first = lastStudy == PetSkill.READING ? PetSkill.ARITHMETIC : PetSkill.READING;
        var exercise = PetStudy.find(animal, player, first);
        if (exercise == null) exercise = PetStudy.find(animal, player, first == PetSkill.READING ? PetSkill.ARITHMETIC : PetSkill.READING);
        if (exercise != null) lastStudy = exercise.skill();
        return exercise;
    }

    boolean hasCountedYoung() { return lastSeenYoung > 0; }
    boolean noticesMissingYoung(int count) {
        boolean missing = count < lastSeenYoung;
        lastSeenYoung = count;
        return missing;
    }

    public boolean attempt(PetSkill skill) {
        var p = animal.life().personality();
        double aptitude = (p.selfControl() * .65 + p.intelligence() * .35) / 100;
        double chance = Math.clamp(.17 + .15 * level(skill) + .27 * aptitude - .001 * animal.mind().stressView(), .08, .98);
        double roll = animal.getRandom().nextDouble();
        boolean success = roll < chance;
        GameplayLog.limited(animal, "lesson", "attempt." + skill, 200,
                "skill attempt skill={} level={} chance={} roll={} success={}", skill, level(skill), chance, roll, success);
        return success;
    }

    /** Call once at a real action's outcome. Repeated sampling or planning never awards practice. */
    public void performed(PetSkill skill, boolean success) {
        if (!animal.pet().isPet() || animal.level().isClientSide) return;
        boolean lesson = practicing(skill);
        if (success) addPractice(skill, lesson && coached ? 6 : 2, lesson && coached ? "lesson" : "daily");
        if (lesson) finishLesson(success);
    }

    private void addPractice(PetSkill skill, int gain, String source) {
        int before = xp(skill), tier = level(skill);
        experience[skill.ordinal()] = Math.min(PetSkill.maximumXp(), before + gain);
        if (before != xp(skill)) GameplayLog.limited(animal, "lesson", "practice." + skill + "." + source, 100,
                "skill practice skill={} source={} xp={} -> {} level={} -> {}", skill, source, before, xp(skill), tier, level(skill));
    }

    void separated() { wildDays = 0; }
    void reunited() { wildDays = -1; }

    private void tickWildLife() {
        if (wildDays < 0) return;
        if (!animal.status().identity().wild()) { wildDays = 0; return; }
        if (++wildDays < WILD_DECAY) return;
        wildDays = -1;
        for (var skill : PetSkill.values()) {
            int tier = level(skill);
            if (tier == 0) continue;
            experience[skill.ordinal()] = PetSkill.xpForLevel(tier - 1);
            GameplayLog.event(animal, "lesson", "skill faded skill={} level={} -> {} cause=extended_wild_life", skill, tier, tier - 1);
        }
    }

    public void restore(int today, int dayClock) { this.today = Math.clamp(today, 0, 999); this.dayClock = Math.clamp(dayClock, 0, DAY); }
    public void save(CompoundTag tag) {
        var skills = new CompoundTag();
        for (var skill : PetSkill.values()) if (xp(skill) > 0) skills.putInt(skill.key(), xp(skill));
        tag.put("SkillExperience", skills);
        if (wildDays >= 0) tag.putInt("SkillWildTicks", wildDays);
    }
    public void load(CompoundTag tag) {
        var skills = tag.getCompound("SkillExperience");
        int mask = tag.contains("SkillExperience") ? 0 : tag.getInt("KnownSkills");
        for (var skill : PetSkill.values())
            experience[skill.ordinal()] = (mask & 1 << skill.ordinal()) != 0 ? PetSkill.xpForLevel(2)
                    : Math.clamp(skills.getInt(skill.key()), 0, PetSkill.maximumXp());
        wildDays = tag.contains("SkillWildTicks") ? Math.clamp(tag.getInt("SkillWildTicks"), 0, WILD_DECAY - 1) : -1;
    }
    private ServerLevel level() { return (ServerLevel) animal.level(); }

    public boolean preconditionOk(Player player, PetSkill skill) {
        if (skill == null) return false;
        return switch (skill) {
            case WAIT -> snack(player) != null;
            case FOOD_BOWL, TOILET -> facility(skill) != null;
            case BEDTIME -> animal.life().homeStand() != null;
            case READING, ARITHMETIC -> PetStudy.find(animal, player, skill) != null;
            case GREETING -> PetStudy.visitor(animal) != null;
            case HARNESS -> animal.getLeashHolder() == player;
        };
    }
    /** Preview stays side-effect free. */
    public String refusal(Player player, PetSkill skill) {
        if (animal.isPanicking() || animal.life().personality().fear(player.getUUID()) >= BondLevels.SOCIAL_FEAR_LIMIT) return "scared";
        if (!animal.childhood().canLearn() || !animal.subspecies().acceptsLesson(player.getUUID())) return "distracted";
        if (animal.life().nutrition() < 30) return "hungry";
        if (animal.life().energy() < 25 || animal.life().isSleeping()) return "tired";
        if (animal.isPassenger() || animal.isCommandPlaying() || animal.life().mealTicks() > 0) return "distracted";
        if (!animal.pet().isPet() || !animal.pet().readyFor(player)) return "not_ready";
        if (animal.pet().cooldown() > 0) return "cooldown";
        return preconditionOk(player, skill) ? null : "need_" + skill.key();
    }
    public boolean start(Player player, PetSkill skill) {
        return request(player, skill, true);
    }
    public boolean orderWait(Player player) {
        return request(player, PetSkill.WAIT, false);
    }
    private boolean request(Player player, PetSkill skill, boolean lesson) {
        if (animal.level().isClientSide || state != State.IDLE || skill == null) return false;
        var reason = refusal(player, skill);
        if (reason != null) {
            GameplayLog.limited(animal, "lesson", "start." + reason, 20,
                    "lesson refused owner={} skill={} reason={}", player.getUUID(), skill, reason);
            animal.showReaction("teary", switch (reason) {
                case "scared" -> "afraid";
                case "hungry" -> "hungry";
                case "tired" -> "sleepy";
                default -> "lesson_refused";
            }, 60);
            player.displayClientMessage(Component.translatable("pet.mcyukkuri.lesson.refuse." + reason), true); return false;
        }
        // A button press is the actual lesson request.
        coached = lesson;
        return begin(player,skill);
    }
    private boolean begin(Player player,PetSkill skill) {
        if (skill != PetSkill.WAIT && skill != PetSkill.READING && skill != PetSkill.ARITHMETIC) {
            state = State.RUNNING; kind = skill; owner = player.getUUID(); completed = false; routineObserveLeft = 1200;
            var example = switch (skill) {
                case FOOD_BOWL, TOILET -> Vec3.atCenterOf(facility(skill).block());
                case BEDTIME -> Vec3.atCenterOf(animal.life().homeStand());
                case GREETING -> PetStudy.visitor(animal).position();
                default -> player.position();
            };
            markCard = Staging.card(level(), example.add(0, 1.2, 0),
                    Component.translatable("pet.mcyukkuri.skill." + skill.key()), ClipMarkers.of("think").length(), 1.2f);
            animal.getLookControl().setLookAt(example.x, example.y, example.z);
            animal.showReaction("surprise", "lesson_start", 60);
            animal.life().replanNow();
            GameplayLog.event(animal, "lesson", "lesson started owner={} skill={} mode=routine", owner, skill);
            player.displayClientMessage(Component.translatable("pet.mcyukkuri.lesson.started", animal.getDisplayName(), Component.translatable("pet.mcyukkuri.skill." + skill.key())), true);
            return true;
        }
        var run = new Run(player, skill);
        if (!animal.pet().lock(LOCK, player.getUUID())) return false;
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
        if (animal.family().carry().mouthBusy()) return null;
        for (var stack : player.getHandSlots())
            if (Sweets.is(stack) && animal.life().acceptsFood(stack) && animal.life().canTakeSweet(stack)) return stack;
        return null;
    }

    public void swallowed(UUID feeder, ItemStack food) {
        if (practicing(PetSkill.WAIT) && animal.pet().director() instanceof Run run
                && run.snackGiven && owner.equals(feeder) && Sweets.is(food)) run.swallowed = true;
    }

    private final class Run implements PetLife.Director {
        private final Player player;
        private final PetSkill skill;
        private final PetStudy.Exercise exercise;
        private int ticks, waitTicks;
        private UUID card;
        private boolean snackGiven, swallowed, success;

        Run(Player player, PetSkill skill) {
            this.player = player; this.skill = skill;
            exercise = skill == PetSkill.WAIT ? null : PetStudy.find(animal, player, skill);
        }
        @Override public void tick(YukkuriEntity ignored) {
            if (!player.isAlive() || player.level() != animal.level() || player.distanceToSqr(animal) > 144 || animal.isPanicking()
                    || animal.isPassenger() || animal.operations().active() || animal.life().nutrition() < 15 || animal.life().energy() < 15
                    || animal.isInWaterOrBubble() || animal.isOnFire() || animal.hurtTime > 0) { interrupt(); return; }
            ticks++;
            switch (skill) {
                case WAIT -> waitForSnack();
                case READING, ARITHMETIC -> study();
                default -> interrupt();
            }
        }
        private void waitForSnack() {
            animal.getNavigation().stop();
            animal.getLookControl().setLookAt(player, 30, 30);
            if (ticks == 1) {
                waitTicks = animal.life().personality().waitingTicks();
                success = attempt(skill);
                animal.showReaction("drool", "lesson_start", waitTicks);
                card = Staging.card(level(), player.position().add(0, player.getBbHeight() + 1.1, 0),
                        Component.translatable("pet.mcyukkuri.card.promise"), waitTicks + FEEDBACK_WINDOW, 1.0f);
            }
            if (!snackGiven && ticks < (success ? waitTicks : waitTicks / 2)) return;
            if (!snackGiven) {
                var sweet = snack(player);
                if (sweet == null || animal.distanceToSqr(player) > 4 || !animal.hasLineOfSight(player)) { interrupt(); return; }
                var one = sweet.copyWithCount(1);
                if (!animal.life().beginMeal(one, player.getUUID(), false)) { interrupt(); return; }
                if (!player.getAbilities().instabuild) sweet.shrink(1);
                snackGiven = true;
                animal.showReaction(success ? "happy" : "teary", success ? "wait_learned" : "lesson_refused", 60);
                GameplayLog.event(animal, "lesson", "wait treat taken owner={} early={} waited={} required={} item={}",
                        player.getUUID(), !success, ticks, waitTicks, one.getItem());
            }
            if (snackGiven && animal.life().mealTicks() == 0) {
                if (swallowed) performed(skill, success);
                else interrupt();
            }
        }
        private void study() {
            animal.getNavigation().stop();
            if (ticks == 1) {
                card = Staging.card(level(), player.position().add(0, player.getBbHeight() + .55, 0),
                        exercise.example(), ClipMarkers.of("think").length() + FEEDBACK_WINDOW, 1.2f);
                animal.showReaction("surprise", "lesson_start", ClipMarkers.of("think").length());
            }
            if (ticks >= ClipMarkers.of("think").length()) {
                var actual = PetStudy.find(animal, player, skill);
                if (actual == null || !exercise.sameExample(actual)) { interrupt(); return; }
                PetStudy.answer(animal, actual);
            }
        }
        private void interrupt() { if (card != null) Staging.removeCard(level(), card); PetLessons.this.interrupt(player); }
        @Override public void stop(YukkuriEntity ignored) {
            if (card != null) Staging.removeCard(level(), card);
            if (state == State.RUNNING) {
                state = State.IDLE;
            }
        }
    }

    private void finishLesson(boolean success) {
        if (state != State.RUNNING) return;
        completed = success;
        routineObserveLeft = 0;
        animal.pet().setCooldown(COOLDOWN);
        if (dayClock <= 0) dayClock = 1;
        today++; state = State.AWAITING; feedbackLeft = FEEDBACK_WINDOW;
        GameplayLog.event(animal, "lesson", "lesson ended owner={} skill={} success={} xp={} awaitingFeedbackTicks={}",
                owner, kind, success, xp(kind), feedbackLeft);
        animal.pet().unlock(LOCK);
        if (markCard != null) Staging.removeCard(level(), markCard);
        markCard = Staging.card(level(), animal.position().add(0, animal.getBbHeight() + .6, 0), Component.translatable("pet.mcyukkuri.card.feedback"), FEEDBACK_WINDOW + 10, 1.6f);
        var player = level().getPlayerByUUID(owner);
        if (player != null) player.displayClientMessage(Component.translatable("pet.mcyukkuri.lesson." + (success ? "success" : "failure"), animal.getDisplayName()), true);
    }
    private void interrupt(Player player) {
        if (state != State.RUNNING) return;
        animal.pet().unlock(LOCK); state = State.IDLE;
        GameplayLog.event(animal, "lesson", "lesson interrupted owner={} skill={} nutrition={} energy={} panic={} water={} fire={} hurtTime={}",
                owner, kind, animal.life().nutrition(), animal.life().energy(), animal.isPanicking(),
                animal.isInWaterOrBubble(), animal.isOnFire(), animal.hurtTime);
        player.displayClientMessage(Component.translatable("pet.mcyukkuri.lesson.interrupted", animal.getDisplayName()), true);
    }
    public void praise(Player player) {
        if (!awaitingFeedback() || !player.getUUID().equals(owner)) return;
        if (completed) { addPractice(kind, 3, "praise"); animal.pet().addStress(-3); SurvivalProgress.award(player, "lesson_praised"); animal.playAction("joy_jump"); }
        end();
    }
    public void reprimand(Player player, CareAction action) {
        if (!awaitingFeedback() || !player.getUUID().equals(owner)) return;
        addPractice(kind, 1, "correction");
        animal.pet().addStress(action == CareAction.FLICK ? 10 : 4);
        end();
    }
    public void reward(Player player, ItemStack sweet) { if (awaitingFeedback() && player.getUUID().equals(owner) && Sweets.is(sweet)) { if (completed) { addPractice(kind, 5, "treat"); animal.pet().addStress(-5); } end(); } }
    private void end() {
        state = State.IDLE;
        feedbackLeft = routineObserveLeft = 0;
        kind = null;
        owner = null;
        if (markCard != null) Staging.removeCard(level(), markCard);
        markCard = null;
    }
    public void tick() {
        if (animal.level().isClientSide) return;
        tickWildLife();
        if (dayClock > 0 && ++dayClock >= DAY) { dayClock = 0; today = 0; }
        if (state == State.RUNNING && routineObserveLeft > 0) {
            var player = level().getPlayerByUUID(owner);
            if (player == null || player.distanceToSqr(animal) > 144 || animal.isPanicking() || !animal.isAlive()
                    || animal.isPassenger() || animal.operations().active() || animal.isInWaterOrBubble() || animal.hurtTime > 0
                    || !animal.pet().owned(player)) end();
            else if (--routineObserveLeft == 0) finishLesson(false);
            return;
        }
        if (state == State.AWAITING && (!animal.isAlive() || --feedbackLeft <= 0)) end();
    }
}
