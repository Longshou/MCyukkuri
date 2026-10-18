package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.interaction.CareAction;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.life.Sweets;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Disposition;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.notebook.SurvivalProgress;

/** Home lessons (docs/PET_SHOP.md section 4): a staged scene, an outcome, then a short window for the owner's reaction. Transient; only the cooldown is saved. */
public final class PetLessons {
    public static final String LOCK = "lesson";
    public static final int FEEDBACK_WINDOW = 80, COOLDOWN = 200, DAY = 24000, FULL_LESSONS = 8;
    public enum State { IDLE, RUNNING, AWAITING }
    private final YukkuriEntity animal;
    private final Random random = new Random();
    private State state = State.IDLE;
    private Ability kind;
    private UUID owner, markCard;
    private boolean won;
    private int feedbackLeft, today, dayClock, pendingTicks;
    private String pendingMove, pendingFace, pendingEvent;
    private EmotionEvent pendingEmotion;
    public PetLessons(YukkuriEntity animal) { this.animal = animal; }

    public State state() { return state; }
    public Ability kind() { return kind; }
    public boolean running() { return state == State.RUNNING; }
    public boolean awaitingFeedback() { return state == State.AWAITING && feedbackLeft > 0; }
    public int today() { return today; }
    public int dayClock() { return dayClock; }
    public void restore(int today, int dayClock) { this.today = Math.clamp(today, 0, 999); this.dayClock = Math.clamp(dayClock, 0, DAY); }
    private ServerLevel level() { return (ServerLevel)animal.level(); }

    // preconditions
    /** Whether the lesson's own precondition holds, ignoring the pet's readiness. */
    public boolean preconditionOk(Player p, Ability a) {
        return switch (a) {
            case ACADEMICS -> true;
            case MANNERS -> facility() != null;
            case WITS -> snack(p) != null;
            case POISE -> peer() != null;
        };
    }
    /** Language suffix of the first reason a lesson cannot start now, or null. */
    public String refusal(Player p, Ability a) {
        if (!animal.pet().readyFor(p)) return "not_ready";
        if (animal.pet().cooldown() > 0) return "cooldown";
        return preconditionOk(p, a) ? null : "need_" + a.key();
    }
    public boolean start(Player p, Ability a) {
        if (animal.level().isClientSide || state == State.RUNNING) return false;
        var why = refusal(p, a);
        if (why != null) { p.displayClientMessage(Component.translatable("pet.mcyukkuri.lesson.refuse." + why), true); return false; }
        var run = new Run(a, p.getUUID());
        if (!run.prepare(p)) { p.displayClientMessage(Component.translatable("pet.mcyukkuri.lesson.refuse.need_" + a.key()), true); return false; }
        if (!animal.pet().lock(LOCK, p.getUUID())) return false;
        clearMark(); state = State.RUNNING; kind = a; owner = p.getUUID(); feedbackLeft = 0; pendingTicks = 0; pendingEmotion = null;
        animal.pet().direct(run);
        p.displayClientMessage(Component.translatable("pet.mcyukkuri.lesson.started", animal.getDisplayName(), Component.translatable("pet.mcyukkuri.lesson." + a.key())), true);
        return true;
    }
    private record Stand(CareBlock.Kind facility, BlockPos pos, BlockPos stand) {}
    private Stand facility() {
        var origin = animal.blockPosition(); var found = new ArrayList<Stand>();
        for (BlockPos scan : BlockPos.betweenClosed(origin.offset(-8, -2, -8), origin.offset(8, 2, 8))) {
            if (!level().hasChunkAt(scan) || !(level().getBlockEntity(scan) instanceof CareBlockEntity care)) continue;
            var k = care.kind(); if (animal.distanceToSqr(Vec3.atCenterOf(scan)) > 64) continue;
            found.add(new Stand(k, scan.immutable(), null));
        }
        found.sort(Comparator.comparingDouble(s -> animal.distanceToSqr(Vec3.atCenterOf(s.pos))));
        int tries = 0;
        for (var s : found) {
            if (++tries > 6) break;
            var candidates = new ArrayList<BlockPos>();
            if (s.facility == CareBlock.Kind.MAT) candidates.add(s.pos.above());
            for (Direction d : Direction.Plane.HORIZONTAL) candidates.add(s.pos.relative(d));
            for (var c : candidates) if (animal.life().dryStand(c) && animal.life().reachable(c, false)) return new Stand(s.facility, s.pos, c.immutable());
        }
        return null;
    }
    private ItemStack snack(Player p) {
        var inv = p.getInventory();
        // The promise game is "wait quietly and get a sweet": candy, cookie or pudding (the exam uses the same cookie), not any food.
        for (int i = 0; i < inv.getContainerSize(); i++) { var s = inv.getItem(i); if (!s.isEmpty() && Sweets.is(s) && animal.life().acceptsFood(s)) return s; }
        return null;
    }
    private YukkuriEntity peer() {
        return level().getEntitiesOfClass(YukkuriEntity.class, animal.getBoundingBox().inflate(8), y -> y != animal && y.isAlive()).stream()
                .min(Comparator.comparingDouble(animal::distanceToSqr)).orElse(null);
    }

    // scene
    private final class Run implements PetLife.Director {
        final Ability kind; final UUID owner; final boolean win;
        int phase, t, failAt, a, b, answer, shown; boolean subtract;
        Stand stand; BlockPos meet; UUID peer, card, itemCard, answerCard; ItemStack snackShown = ItemStack.EMPTY;
        Run(Ability kind, UUID owner) {
            this.kind = kind; this.owner = owner;
            win = random.nextDouble() < animal.pet().successChance(kind, BadgeGrade.of(Math.max(1, animal.pet().grade().higher().id())));
        }
        boolean prepare(Player p) {
            switch (kind) {
                case MANNERS -> {
                    stand = facility(); if (stand == null) return false;
                    meet = stand.stand;
                    if (!win) { // wander off: only half of the way, and do it there
                        var mid = BlockPos.containing((animal.getX() + meet.getX() + .5) / 2, animal.getY(), (animal.getZ() + meet.getZ() + .5) / 2);
                        meet = animal.life().dryStand(mid) && animal.life().reachable(mid, false) ? mid : animal.blockPosition();
                    }
                }
                case ACADEMICS -> {
                    int max = animal.pet().grade().id() < BadgeGrade.SILVER.id() ? 5 : 10;
                    a = 1 + random.nextInt(max); b = 1 + random.nextInt(max); subtract = random.nextBoolean();
                    if (subtract && b > a) { int x = a; a = b; b = x; }
                    answer = subtract ? a - b : a + b;
                    shown = answer;
                    if (!win) { do shown = answer + (random.nextBoolean() ? 1 : -1) * (1 + random.nextInt(2)); while (shown < 0 || shown == answer); }
                }
                case WITS -> {
                    var s = snack(p); if (s == null) return false;
                    snackShown = s.copyWithCount(1); failAt = 40 + random.nextInt(60);
                }
                case POISE -> { var y = peer(); if (y == null) return false; peer = y.getUUID(); }
            }
            return true;
        }
        @Override public void tick(YukkuriEntity a0) {
            var p = level().getPlayerByUUID(owner);
            if (p == null || unsafe(a0, p)) { interrupt(p); return; }
            t++;
            switch (kind) { case MANNERS -> manners(p); case ACADEMICS -> academics(p); case WITS -> wits(p); case POISE -> poise(p); }
        }
        @Override public void stop(YukkuriEntity a0) {
            for (var id : new UUID[] {card, itemCard, answerCard}) if (id != null && a0.level() instanceof ServerLevel l) Staging.removeCard(l, id);
            card = itemCard = answerCard = null;
            if (state == State.RUNNING) state = State.IDLE;
            if (a0.isAlive() && a0.isCommandPlaying()) a0.playAction("idle");
        }
        private boolean unsafe(YukkuriEntity y, Player p) {
            return !y.isAlive() || !p.isAlive() || p.level() != y.level() || p.distanceToSqr(y) > 144 || y.isPanicking() || y.hurtTime > 0 || y.isPassenger()
                    || y.isOnFire() || y.isInWaterOrBubble() || y.operations().active() || y.isNoAi() || y.life().hasUrgentNeed();
        }
        private void next() { phase++; t = 0; }
        private void look(Player p) { animal.getLookControl().setLookAt(p, 30, 30); }
        /** Walk back until within a few blocks of the owner, then finish. */
        private void back(Player p) {
            look(p);
            if (animal.distanceToSqr(p) <= 9 || t > 100) { animal.getNavigation().stop(); finish(win); return; }
            if (t % 10 == 1) animal.getNavigation().moveTo(p.getX(), p.getY(), p.getZ(), 1.0);
        }
        private void manners(Player p) {
            switch (phase) {
                case 0 -> {
                    if (Staging.walkTo(animal, meet, 1.0) || t > 160) { animal.getNavigation().stop(); next(); }
                }
                case 1 -> {
                    var f = stand.facility; String move = f == CareBlock.Kind.BOWL ? "eat" : f == CareBlock.Kind.TOILET ? "brace" : "sleep";
                    int len = f == CareBlock.Kind.TOILET ? 40 : 60;
                    if (t == 1) {
                        animal.getLookControl().setLookAt(stand.pos.getX() + .5, stand.pos.getY() + .3, stand.pos.getZ() + .5);
                        String face = !win ? "confused" : f == CareBlock.Kind.BOWL ? "satisfied" : f == CareBlock.Kind.TOILET ? "neutral" : "sleepy";
                        animal.playAction(move); animal.showReaction(face, win ? "" : "exam_unsure", len + 10);
                    }
                    if (t >= len) { animal.playAction("idle"); if (win) Staging.sparkle(level(), animal.position().add(0, animal.getBbHeight() + .2, 0)); next(); }
                }
                default -> back(p);
            }
        }
        private void academics(Player p) {
            var level = level(); var head = animal.position().add(0, animal.getBbHeight() + .55, 0);
            look(p);
            if (phase == 0) {
                if (t == 1) {
                    card = Staging.card(level, p.position().add(animal.position()).scale(.5).add(0, 1.7, 0),
                            Component.translatable(subtract ? "pet.mcyukkuri.card.diff" : "pet.mcyukkuri.card.sum", a, b), 200, 1.3f);
                    animal.playAction("think");
                }
                if (t % 10 == 5) Staging.sweat(animal);
                if (t >= 40) { animal.speak("exam_answer"); answerCard = Staging.card(level, head, Component.translatable("pet.mcyukkuri.card.answer", shown), 80, 1.2f); next(); }
            } else if (phase == 1) {
                if (t == 40) {
                    animal.playAction("idle");
                    if (win) { Staging.note(level, head); Staging.sparkle(level, head); Staging.sound(level, animal.position(),
                            SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2f); animal.showReaction("happy", "", 60); }
                    else { Staging.sound(level, animal.position(), SoundEvents.VILLAGER_NO, 1.4f); animal.showReaction("confused", "exam_unsure", 60); }
                }
                if (t >= 70) finish(win);
            }
        }
        private void wits(Player p) {
            var level = level(); animal.getNavigation().stop(); look(p);
            if (phase == 0 && t == 1) {
                var above = p.position().add(0, p.getBbHeight() + .7, 0);
                card = Staging.card(level, above.add(0, .45, 0), Component.translatable("pet.mcyukkuri.card.promise"), 160, 1.0f);
                itemCard = Staging.itemCard(level, above.add(0, -.1, 0), snackShown, 160, .5f);
                animal.playAction("tremble"); animal.showReaction("neutral", "", 30);
            }
            if (phase == 0) {
                if (t == 30 || t == 80) animal.speak("exam_promise_wait");
                if (!win && t >= failAt) {
                    removeCards(); Staging.anger(level, animal.position().add(0, animal.getBbHeight(), 0));
                    animal.playAction("hop"); animal.showReaction(random.nextBoolean() ? "angry" : "cry", "exam_promise_fail", 60); next();
                } else if (win && t >= 120) {
                    removeCards(); animal.playAction("idle");
                    var s = snack(p); // the snack may have moved meanwhile; no snack, no meal, still counts as waited
                    if (s != null) {
                        var one = s.copyWithCount(1);
                        if (animal.life().beginMeal(one, p.getUUID(), false) && !p.getAbilities().instabuild) s.shrink(1);
                    }
                    Staging.sparkle(level, animal.position().add(0, animal.getBbHeight() + .2, 0)); next();
                }
            } else if (t >= (win ? 10 : 40)) finish(win);
        }
        private void removeCards() {
            if (card != null) Staging.removeCard(level(), card);
            if (itemCard != null) Staging.removeCard(level(), itemCard);
            card = itemCard = null;
        }
        private void poise(Player p) {
            var other = level().getEntity(peer) instanceof YukkuriEntity y && y.isAlive() ? y : null;
            if (other == null) { interrupt(p); return; }
            switch (phase) {
                case 0 -> {
                    animal.getLookControl().setLookAt(other, 30, 30);
                    if (animal.distanceToSqr(other) <= 4.5 || t > 160) { animal.getNavigation().stop(); next(); }
                    else if (t % 10 == 1) animal.getNavigation().moveTo(other.getX(), other.getY(), other.getZ(), 1.0);
                }
                case 1 -> {
                    animal.getLookControl().setLookAt(other, 30, 30);
                    if (win) {
                        if (t == 1) { animal.playAction("bow"); animal.sayBadge("peer"); animal.showReaction("happy", "", 60); }
                        if (t == 30) Staging.note(level(), animal.position().add(0, animal.getBbHeight() + .2, 0));
                        if (t >= 60) { animal.playAction("idle"); next(); }
                    } else {
                        if (t == 1) { animal.playAction("puff"); animal.showReaction("angry", "", 60); }
                        if (t == 40) { animal.playAction("push"); Staging.anger(level(), animal.position().add(0, animal.getBbHeight(), 0)); }
                        if (t >= 70) { animal.playAction("idle"); next(); }
                    }
                }
                default -> back(p);
            }
        }
    }

    private void interrupt(Player p) {
        if (state != State.RUNNING) return;
        animal.pet().unlock(LOCK); state = State.IDLE;
        if (p != null) p.displayClientMessage(Component.translatable("pet.mcyukkuri.lesson.interrupted", animal.getDisplayName()), true);
    }
    private void finish(boolean win) {
        if (state != State.RUNNING) return;
        var p = level().getPlayerByUUID(owner);
        animal.pet().setCooldown(COOLDOWN);
        if (dayClock <= 0) dayClock = 1; today++;
        won = win; state = State.AWAITING; feedbackLeft = FEEDBACK_WINDOW;
        animal.pet().unlock(LOCK); // Run.stop leaves AWAITING alone
        markCard = Staging.card(level(), markPos(), Component.translatable("pet.mcyukkuri.card.feedback"), FEEDBACK_WINDOW + 10, 1.6f);
        if (p != null) p.displayClientMessage(Component.translatable("pet.mcyukkuri.lesson." + (win ? "success" : "failure"), animal.getDisplayName()), true);
    }
    private Vec3 markPos() { return animal.position().add(0, animal.getBbHeight() + .6, 0); }
    private void clearMark() { if (markCard != null) Staging.removeCard(level(), markCard); markCard = null; }

    // owner's reaction
    /** Called by OperationLife before the normal effect of GENTLE, COLD and FLICK. */
    public void feedback(Player p, CareAction action) {
        if (!awaitingFeedback() || !p.getUUID().equals(owner)) return;
        var pet = animal.pet(); var personality = animal.life().personality();
        int gain; boolean praise = action == CareAction.GENTLE;
        // Punishment is never the best answer (wiki: punishing is not teaching): encouragement beats silence, a reprimand only matches silence, a flick teaches nothing.
        switch (action) {
            case GENTLE -> {
                gain = won ? 6 : 3;
                if (won) { pet.addStress(-3); personality.experienced(Disposition.ATTACHMENT, 1); } else { pet.addIndulgence(2); pet.addStress(-1); }
            }
            case COLD -> { gain = 1; pet.addStress(won ? 6 : 4); }
            default -> { gain = 0; pet.addStress(10); }
        }
        award(gain);
        if (praise && won) SurvivalProgress.award(p, "lesson_praised");
        pendingTicks = 2;
        pendingEmotion = praise ? EmotionEvent.PRAISED : null;
        if (praise) { pendingMove = "joy_jump"; pendingFace = "happy"; pendingEvent = "lesson_praised"; Staging.sparkle(level(), markPos()); }
        else if (action == CareAction.COLD) { pendingMove = "droop"; pendingFace = "teary"; pendingEvent = "lesson_scolded"; }
        else { pendingMove = "tremble"; pendingFace = "cry"; pendingEvent = "lesson_scolded"; }
        end();
    }
    /** A sweet fed while the pet waits for its owner's reaction: a prize after a success (pudding counts 1.5x), a bribe after a failure. One per lesson. */
    public void reward(Player p, ItemStack sweet) {
        if (!awaitingFeedback() || !p.getUUID().equals(owner) || !Sweets.is(sweet)) return;
        var pet = animal.pet();
        if (won) { award(Math.round(8 * Sweets.rewardFactor(sweet))); pet.addStress(-5); }
        else { award(1); pet.addIndulgence(3); }
        end();
    }
    private void award(int base) {
        if (today > FULL_LESSONS) base = (base + 1) / 2;
        animal.pet().learn(kind, base);
    }
    private void end() { state = State.IDLE; feedbackLeft = 0; clearMark(); }

    public void tick() {
        if (animal.level().isClientSide) return;
        if (dayClock > 0 && ++dayClock >= DAY) { dayClock = 0; today = 0; }
        if (pendingTicks > 0 && --pendingTicks == 0) {
            animal.playAction(pendingMove);
            animal.mind().felt(pendingEmotion); pendingEmotion = null;
            animal.showReaction(pendingFace, pendingEvent, 70);
        }
        if (state != State.AWAITING) return;
        if (!animal.isAlive()) { end(); return; }
        if (--feedbackLeft % 4 == 0) Staging.moveCard(level(), markCard, markPos());
        if (feedbackLeft <= 0) {
            award(won ? 3 : 1);
            animal.showReaction(won ? "satisfied" : "neutral", "", 50);
            end();
        }
    }
}
