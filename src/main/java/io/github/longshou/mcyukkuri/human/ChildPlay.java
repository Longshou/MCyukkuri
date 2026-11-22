package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.life.Consumables;
import io.github.longshou.mcyukkuri.life.Toxins;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.interaction.ItemContact;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A park child teases or plays with an animal in sight (EH29). One encounter is one gesture, chosen by the child's own roll from what it
 * has in hand and who the animal is: BALL (rolls its yarn ball past the animal), CRUMB (sets one bite of food on the ground and backs off),
 * POKE (crouches and wiggles a finger from two blocks) or CHASE (runs at a bold adult and stops two blocks short). Each shows a one-second
 * telegraph first, never touches the animal, and is answered once by the animal from its own values (YukkuriSocial.answerChildOffer); the
 * child then reacts to what it saw (wave, shrug, apologetic). Items are real: the ball is fetched back only when loose and within reach,
 * the crumb is the child's one bite and stays where it lies, and nothing is regenerated.
 */
public final class ChildPlay extends Goal {
    /** What the client draws of the gesture: the ball drawn back, a raised hand, a crouch, a poking finger, a run, a shrug, an apology. */
    public enum Pose { NONE, DRAW, WAVE, CROUCH, POKE, RUN, SHRUG, SORRY }
    /** The four gestures with their stand distance from the animal and the distance window the child accepts before it starts. */
    public enum Gesture {
        BALL(3.5, 2.0, 4.5), CRUMB(3.5, 2.0, 4.5), POKE(2.4, 1.9, 3.0), CHASE(5.5, 4.0, 7.0);
        final double stand, near, far;
        Gesture(double stand, double near, double far) { this.stand = stand; this.near = near; this.far = far; }
    }
    /** How the animal answered (written by YukkuriSocial, read by the child): played back or stood its ground, watched, went to its owner, flinched away, was truly frightened, or ignored it. */
    public enum Answer { PLAY, WATCH, OWNER, FLINCH, SCARED, IGNORE }
    private enum Phase { APPROACH, SHOW, WATCH, RECOVER, BACK_OFF, RUN, PARTING }

    /** The child notices an animal within this many blocks (design: 8). */
    private static final int SEE = 8;
    /** A pet is only played with while its owner is this close and in sight. */
    private static final double OWNER_NEAR = 12;
    private static final int SCAN = 20;
    /** One second of telegraph (crouch, drawn-back ball, wave) before every gesture. */
    private static final int TELEGRAPH = 20;
    /** Walk-up and gesture share a hard 20 s limit. */
    private static final int GESTURE_LIMIT = 400;
    /** A roll passes this far beside the animal's centre and travels this far beyond it. */
    private static final double LATERAL = 1.4, BEYOND = 1.2;
    /** Roll speed in blocks per tick (2.4 blocks/s): the item's own friction would stop a single push within a block. */
    private static final double ROLL_SPEED = .12;
    /** 3 s of watching after a refusal, 8 s when the animal actually plays or when a crumb is waiting to be eaten. */
    private static final int WATCH_SHORT = 60, WATCH_LONG = 160;
    private static final int WAVE_TICKS = 40, WAVE_SHORT = 24;
    /** Interest in one animal lasts 40-100 s from the first gesture; the same pair waits 3-6 min before another. */
    private static final int ENGAGED_MIN = 800, ENGAGED_SPAN = 1200, COOLDOWN_MIN = 3600, COOLDOWN_SPAN = 3600, RETRY_PAUSE = 200;
    private static final int OFFER_TICKS = 100;
    /** The crumb lies this far in front of the child (two blocks short of the animal at the 3.5 stand) and the child then steps this far back. */
    private static final double CRUMB_AHEAD = 1.5, BACK_OFF = 2.0;
    private static final int BACK_OFF_TICKS = 50;
    /** A run ends this close to the animal (two blocks short of its body), or after this long, and never touches. */
    private static final double CHASE_STOP = 2.4;
    private static final int RUN_LIMIT = 100;
    private static final double RUN_SPEED = 1.45;
    /** Only an animal that looks bold (boldness at or above this) is chased. */
    private static final int BOLD_LOOKING = 55;
    /** A child that frightened an animal for real stops for the rest of the day. */
    private static final int QUIT_TICKS = 24000;
    /** Rolls and runs still waiting for the animal's answer, by animal. Server-thread only; an entry lives five seconds at most. */
    private record Roll(UUID child, UUID item, Gesture gesture, long at) {}
    private static final Map<UUID, Roll> ROLLS = new HashMap<>();
    /** The animal's latest answer to a child, by animal. Server-thread only; pruned as it ages. */
    private record Reply(UUID child, Answer answer, long at) {}
    private static final Map<UUID, Reply> REPLIES = new HashMap<>();
    private static final int REPLY_TICKS = 300;

    private final HumanNpc npc;
    private final Map<UUID, Long> cooldown = new HashMap<>();
    /** Animals this child has already chased: never twice on the same target. */
    private final Set<UUID> chased = new HashSet<>();
    private Phase phase = Phase.APPROACH;
    private Gesture gesture = Gesture.BALL;
    private YukkuriEntity target;
    private ItemEntity item;
    private Vec3 stand, rollDir = Vec3.ZERO, backTo;
    private double rollLeft;
    private int total, clock, watchEnd, engagedUntil, waveLeft, nextScan;
    private long quitUntil;
    private boolean done, playing;
    private String parting = "";

    ChildPlay(HumanNpc npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** A gesture the animal can see, still waiting for its answer; {@code item} is the ball or crumb, null for a poke or a run. */
    public record Offer(HumanNpc child, ItemEntity item, Gesture gesture) {}

    /** The gesture a child made to this animal within the last five seconds, or null. */
    public static Offer offerTo(YukkuriEntity animal) {
        var roll = ROLLS.get(animal.getUUID());
        if (roll == null || !(animal.level() instanceof ServerLevel level)) return null;
        if (level.getGameTime() - roll.at() >= OFFER_TICKS) { ROLLS.remove(animal.getUUID()); return null; }
        if (!(level.getEntity(roll.child()) instanceof HumanNpc child) || !child.isAlive() || !animal.hasLineOfSight(child)) return null;
        if (roll.item() == null) return new Offer(child, null, roll.gesture());
        if (level.getEntity(roll.item()) instanceof ItemEntity thing && thing.isAlive() && animal.hasLineOfSight(thing))
            return new Offer(child, thing, roll.gesture());
        return null;
    }

    /** One answer per gesture: later evaluations never answer the same one again; the child reads what the animal did. */
    public static void answered(YukkuriEntity animal, Answer answer) {
        var roll = ROLLS.remove(animal.getUUID());
        if (roll == null) return;
        long now = animal.level().getGameTime();
        REPLIES.values().removeIf(old -> now - old.at() >= REPLY_TICKS);
        REPLIES.put(animal.getUUID(), new Reply(roll.child(), answer, now));
    }

    private static Answer replyOf(YukkuriEntity animal, UUID child) {
        var reply = REPLIES.get(animal.getUUID());
        return reply != null && reply.child().equals(child) && animal.level().getGameTime() - reply.at() < REPLY_TICKS ? reply.answer() : null;
    }

    /** The living owner of a pet when that owner is close and in sight of it, else null: a pet looks to this person first. */
    public static LivingEntity ownerNear(YukkuriEntity pet) {
        if (!pet.pet().isPet() || pet.pet().owner() == null || !(pet.level() instanceof ServerLevel level)) return null;
        var found = level.getEntity(pet.pet().owner());
        return found instanceof LivingEntity owner && owner.isAlive() && pet.distanceToSqr(owner) <= OWNER_NEAR * OWNER_NEAR
                && pet.hasLineOfSight(owner) ? owner : null;
    }

    @Override public boolean canUse() {
        if (npc.look() != HumanNpc.Look.CHILD || npc.tickCount < nextScan) return false;
        nextScan = npc.tickCount + SCAN;
        if (!(npc.level() instanceof ServerLevel level) || !npc.isAlive() || npc.isNoAi() || !npc.onGround() || !level.isDay()
                || level.isRaining() || npc.resting() || npc.avoidingStreet() || level.getGameTime() < quitUntil || playerNear()) return false;
        if (predatorNear()) {
            npc.goHome(600);
            GameplayLog.event(npc, "child", "go_home reason=predator");
            return false;
        }
        long now = level.getGameTime();
        cooldown.values().removeIf(until -> until <= now);
        var candidates = level.getEntitiesOfClass(YukkuriEntity.class, npc.getBoundingBox().inflate(SEE, 2, SEE), this::eligible).stream()
                .filter(y -> cooldown.getOrDefault(y.getUUID(), 0L) <= now && y.social().readyForChildOffer()).sorted(Comparator.comparingDouble(npc::distanceToSqr)).toList();
        for (var candidate : candidates) {
            var chosen = pick(candidate);
            if (chosen == null) continue;
            gesture = chosen;
            stand = findStand(candidate);
            if (stand == null) {
                // Nothing changed for this animal until it moves or the child does: wait out a short pause rather than rescanning it every second.
                cooldown.put(candidate.getUUID(), now + COOLDOWN_MIN / 3);
                continue;
            }
            target = candidate;
            return true;
        }
        return false;
    }

    /**
     * The child's own roll for this animal: with its ball in hand it shows the ball, otherwise a crumb when it holds food this animal would
     * take, a poke for a grown animal, a run only at an adult that looks bold and has not been chased before. Null when nothing fits.
     */
    private Gesture pick(YukkuriEntity y) {
        if (npc.getMainHandItem().is(ModItems.TOY.get())) return Gesture.BALL;
        var options = new ArrayList<Gesture>();
        var weights = new ArrayList<Integer>();
        if (crumbFor(y)) { options.add(Gesture.CRUMB); weights.add(3); }
        boolean grown = y.family().stage() == FamilyLife.Stage.ADULT;
        if (grown) { options.add(Gesture.POKE); weights.add(2); }
        if (grown && y.life().personality().boldness() >= BOLD_LOOKING && !chased.contains(y.getUUID())) { options.add(Gesture.CHASE); weights.add(2); }
        if (options.isEmpty()) return null;
        int roll = npc.getRandom().nextInt(weights.stream().mapToInt(Integer::intValue).sum());
        for (int i = 0; i < options.size(); i++) {
            roll -= weights.get(i);
            if (roll < 0) return options.get(i);
        }
        return options.get(0);
    }

    /** One bite of plain food in the free hand that this animal would take: never a dose, never a toxic or bitter item. */
    private boolean crumbFor(YukkuriEntity y) {
        var food = npc.getOffhandItem();
        return !food.isEmpty() && Consumables.kind(food) == null && Toxins.dose(food) == 0 && y.life().seeksFood(food);
    }

    @Override public void start() {
        var level = (ServerLevel) npc.level();
        phase = Phase.APPROACH;
        done = false;
        total = clock = waveLeft = 0;
        item = null;
        playing = false;
        parting = "";
        cooldown.put(target.getUUID(), level.getGameTime() + RETRY_PAUSE);   // the long pause starts only once the animal answered (answeredPause)
        npc.getNavigation().moveTo(stand.x, stand.y, stand.z, .9);
        GameplayLog.event(npc, "child", "approach gesture={} target={} identity={} distance={} stand={}", gesture, target.getUUID(),
                target.status().identity().id(), npc.distanceTo(target), GameplayLog.at(stand));
    }

    @Override public boolean canContinueToUse() {
        return !done && npc.isAlive() && !npc.isNoAi() && !npc.avoidingStreet() && npc.level().isDay() && !npc.level().isRaining();
    }

    @Override public boolean requiresUpdateEveryTick() { return true; }

    @Override public void tick() {
        total++;
        if (npc.tickCount % 10 == 0 && predatorNear()) {
            npc.goHome(600);
            finish("predator");
            return;
        }
        if (waveLeft > 0 && --waveLeft == 0) npc.setChildPose(Pose.NONE);
        switch (phase) {
            case APPROACH -> approach();
            case SHOW -> show();
            case WATCH -> { if (gesture == Gesture.BALL) watchBall(); else watchAnswer(); }
            case RECOVER -> recover();
            case BACK_OFF -> backOff();
            case RUN -> run();
            case PARTING -> { if (waveLeft <= 0) finish(parting); }
        }
    }

    @Override public void stop() {
        npc.setChildPose(Pose.NONE);
        npc.getNavigation().stop();
        target = null;
        item = null;
    }

    private void approach() {
        if (!eligible(target)) { finish("target_left"); return; }
        npc.getLookControl().setLookAt(target, 30, 30);
        double distance = Math.sqrt(horizontalSqr(npc.position(), target.position()));
        if (distance >= gesture.near && distance <= gesture.far && npc.hasLineOfSight(target) && npc.getNavigation().isDone()) {
            npc.getNavigation().stop();
            phase = Phase.SHOW;
            clock = 0;
            npc.setChildPose(switch (gesture) {
                case BALL -> Pose.DRAW;
                case CRUMB, POKE -> Pose.CROUCH;
                case CHASE -> Pose.WAVE;
            });
            return;
        }
        if (total >= GESTURE_LIMIT) { finish("approach_timeout"); return; }
        if (npc.getNavigation().isDone() && total % 20 == 0) {
            stand = findStand(target);
            if (stand == null || !npc.getNavigation().moveTo(stand.x, stand.y, stand.z, .9)) finish("no_stand");
        }
    }

    private void show() {
        npc.getLookControl().setLookAt(target, 30, 30);
        double distance = Math.sqrt(horizontalSqr(npc.position(), target.position()));
        if (!eligible(target) || distance < gesture.near || distance > gesture.far + 1 || total >= GESTURE_LIMIT) {
            finish(total >= GESTURE_LIMIT ? "gesture_timeout" : "target_moved");
            return;
        }
        if (playerNear()) return;   // the gesture pauses while someone is at the child's side
        if (++clock < TELEGRAPH) return;
        if (!target.social().readyForChildOffer()) { finish("target_busy"); return; }   // busy with its own life: nothing to answer with, try again soon
        switch (gesture) {
            case BALL -> roll();
            case CRUMB -> placeCrumb();
            case POKE -> poke();
            case CHASE -> startRun();
        }
    }

    /** The one hand-off: the ball leaves the hand and becomes a loose item in the same tick, or the hand keeps it. */
    private void roll() {
        var hand = npc.getMainHandItem();
        var from = npc.position().add(Vec3.directionFromRotation(0, npc.yBodyRot).scale(.35)).add(0, .5, 0);
        var toward = horizontal(target.position().subtract(from));
        var side = new Vec3(-toward.z, 0, toward.x);
        double first = npc.getRandom().nextBoolean() ? 1 : -1, sign = 0, length = 0;
        Vec3 chosen = null;
        for (double candidate : new double[]{first, -first}) {
            var aim = target.position().add(side.scale(LATERAL * candidate));
            var direction = horizontal(aim.subtract(from));
            double reach = Math.sqrt(horizontalSqr(from, aim)) + BEYOND;
            if (lineRollable(from, direction, reach)) { chosen = direction; length = reach; sign = candidate; break; }
        }
        if (chosen == null) { finish("no_clear_roll"); return; }
        var dropped = ItemContact.release(npc, hand.copyWithCount(1), from, Vec3.ZERO);
        if (dropped == null) { finish("release_refused"); return; }
        hand.shrink(1);
        if (hand.isEmpty()) npc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        offer(dropped);
        item = dropped;
        rollDir = chosen;
        rollLeft = length;
        npc.swing(InteractionHand.MAIN_HAND);
        npc.setChildPose(Pose.NONE);
        phase = Phase.WATCH;
        clock = 0;
        watchEnd = WATCH_SHORT;
        engagedUntil = total + ENGAGED_MIN + npc.getRandom().nextInt(ENGAGED_SPAN);
        GameplayLog.event(npc, "child", "roll target={} ball={} item={} distance={} lateral={} length={}", target.getUUID(),
                dropped.getUUID(), dropped.getItem().getItem(), Math.sqrt(horizontalSqr(npc.position(), target.position())),
                LATERAL * sign, length);
    }

    /** The one bite leaves the free hand onto the floor in front of the child, or the hand keeps it; then the child steps back. */
    private void placeCrumb() {
        var hand = npc.getOffhandItem();
        var toward = horizontal(target.position().subtract(npc.position()));
        if (hand.isEmpty() || !lineRollable(npc.position().add(0, .5, 0), toward, CRUMB_AHEAD)) { finish("no_clear_floor"); return; }
        var at = npc.position().add(toward.scale(CRUMB_AHEAD)).add(0, .3, 0);
        var dropped = ItemContact.release(npc, hand.copyWithCount(1), at, Vec3.ZERO);
        if (dropped == null) { finish("release_refused"); return; }
        hand.shrink(1);
        if (hand.isEmpty()) npc.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        item = dropped;
        npc.swing(InteractionHand.OFF_HAND);
        npc.setChildPose(Pose.NONE);
        var back = npc.position().subtract(toward.scale(BACK_OFF));
        var feet = standAt(back.x, npc.getY(), back.z);
        backTo = feet;
        if (feet != null) npc.getNavigation().moveTo(feet.x, feet.y, feet.z, .8);
        phase = Phase.BACK_OFF;
        clock = 0;
        GameplayLog.event(npc, "child", "crumb target={} item={} drop={} backs_off={}", target.getUUID(), dropped.getItem().getItem(),
                dropped.getUUID(), feet != null);
    }

    /** The child steps two blocks back from the crumb; only then does the animal get to see it as offered. */
    private void backOff() {
        npc.getLookControl().setLookAt(target, 30, 30);
        if (item == null || !item.isAlive()) { finish("crumb_gone"); return; }
        if (++clock < BACK_OFF_TICKS && backTo != null && !npc.getNavigation().isDone()) return;
        npc.getNavigation().stop();
        offer(item);
        phase = Phase.WATCH;
        clock = 0;
        watchEnd = WATCH_LONG;
    }

    /** A finger or stem at arm's length from two blocks away: the pose is the whole gesture, no hand goes near the animal. */
    private void poke() {
        npc.setChildPose(Pose.POKE);
        offer(null);
        phase = Phase.WATCH;
        clock = 0;
        watchEnd = WATCH_SHORT;
        GameplayLog.event(npc, "child", "poke target={} distance={}", target.getUUID(), Math.sqrt(horizontalSqr(npc.position(), target.position())));
    }

    private void startRun() {
        npc.setChildPose(Pose.RUN);
        phase = Phase.RUN;
        clock = 0;
        chased.add(target.getUUID());
        if (chased.size() > 32) { chased.clear(); chased.add(target.getUUID()); }
        npc.getNavigation().moveTo(target, RUN_SPEED);
        GameplayLog.event(npc, "child", "run target={} distance={}", target.getUUID(), Math.sqrt(horizontalSqr(npc.position(), target.position())));
    }

    /** Arms out at the animal until two blocks short, then a stop: never touching, never a second lap. */
    private void run() {
        npc.getLookControl().setLookAt(target, 30, 30);
        double distance = Math.sqrt(horizontalSqr(npc.position(), target.position()));
        if (!target.isAlive() || playerNear() || clock >= RUN_LIMIT || !npc.hasLineOfSight(target)) {
            npc.setChildPose(Pose.NONE);
            finish(!target.isAlive() ? "target_gone" : clock >= RUN_LIMIT ? "chase_lost" : "chase_blocked");
            return;
        }
        if (distance <= CHASE_STOP) {
            npc.getNavigation().stop();
            npc.setChildPose(Pose.NONE);
            offer(null);
            phase = Phase.WATCH;
            clock = 0;
            watchEnd = WATCH_SHORT;
            GameplayLog.event(npc, "child", "chase_stop target={} distance={}", target.getUUID(), distance);
            return;
        }
        if (clock++ % 5 == 0) npc.getNavigation().moveTo(target, RUN_SPEED);
    }

    /** Poke, crumb and run: watch what the animal does, then answer it with a wave, a shrug or an apology. */
    private void watchAnswer() {
        clock++;
        if (target != null) npc.getLookControl().setLookAt(target, 30, 30);
        var answer = replyOf(target, npc.getUUID());
        boolean eaten = gesture == Gesture.CRUMB && (item == null || !item.isAlive());
        if (clock < watchEnd && !eaten) return;
        Pose pose = Pose.NONE;
        int ticks = WAVE_TICKS;
        if (answer == Answer.SCARED) {
            pose = Pose.SORRY;
            quitUntil = npc.level().getGameTime() + QUIT_TICKS;   // frightened for real: the child is sorry and stops for the day
            ticks = WAVE_TICKS * 2;
        } else if (answer == Answer.FLINCH || answer == Answer.OWNER) { pose = Pose.SHRUG; ticks = WAVE_SHORT; }
        else if (answer == Answer.PLAY || eaten) pose = Pose.WAVE;
        answeredPause(answer);
        GameplayLog.event(npc, "child", "reaction={} answer={} gesture={} target={}", pose, answer, gesture, target.getUUID());
        npc.setChildPose(pose);
        waveLeft = pose == Pose.NONE ? 0 : ticks;
        parting = "answered";
        phase = Phase.PARTING;
    }

    private void watchBall() {
        clock++;
        if (target != null) npc.getLookControl().setLookAt(target, 30, 30);
        if (!item.isAlive()) { finish("ball_gone"); return; }
        push();
        if (clock == WATCH_SHORT && playing()) watchEnd = WATCH_LONG;
        if (clock < watchEnd) return;
        String reaction = playing() ? "played" : target.social().retreatingFrom(npc) ? "fled" : "watched";
        if (!reaction.equals("watched")) {
            npc.setChildPose(Pose.WAVE);
            waveLeft = reaction.equals("played") ? WAVE_TICKS : WAVE_SHORT;
        }
        answeredPause(replyOf(target, npc.getUUID()));
        GameplayLog.event(npc, "child", "reaction={} target={} ball={}", reaction, target.getUUID(), item.getUUID());
        phase = Phase.RECOVER;
        clock = 0;
    }

    /** The ball keeps rolling at walking pace until it has gone past the animal, hits something or an animal takes it up. */
    private void push() {
        if (rollLeft <= 0) return;
        if (playing()) { rollLeft = 0; return; }   // the animal has taken the ball up: the child's roll is over, the ball must not glide on its own
        if (item.horizontalCollision || item.isInWater() || !item.onGround() && item.getDeltaMovement().y < -.3) { rollLeft = 0; return; }
        Motor.velocity(item, new Vec3(rollDir.x * ROLL_SPEED, item.getDeltaMovement().y, rollDir.z * ROLL_SPEED), "child_roll");
        item.hurtMarked = true;
        rollLeft -= ROLL_SPEED;
    }

    private void recover() {
        clock++;
        if (!item.isAlive()) { finish("ball_gone"); return; }
        push();
        if (item.isInWater() || item.isInLava()) { finish("ball_unreachable"); return; }
        if (total >= engagedUntil) { finish("bored"); return; }
        npc.getLookControl().setLookAt(item, 30, 30);
        if (playing() || rollLeft > 0) { npc.getNavigation().stop(); return; }   // the ball is still the animal's, or still rolling
        if (npc.reachesPickup(item) && !item.hasPickUpDelay() && npc.getMainHandItem().isEmpty()) {
            var stack = item.getItem();
            npc.setItemSlot(EquipmentSlot.MAINHAND, stack.copy());
            npc.take(item, stack.getCount());
            item.discard();
            GameplayLog.event(npc, "child", "ball_recovered ball={} item={} count={}", item.getUUID(), stack.getItem(), stack.getCount());
            finish("recovered");
            return;
        }
        if (clock % 20 == 1 && !npc.getNavigation().moveTo(item, .9) && !npc.reachesPickup(item)) finish("ball_unreachable");
    }

    /** A real answered encounter (the animal replied, even only by watching) rests this animal for minutes; an unanswered or moved-away one only for the short retry pause. */
    private void answeredPause(Answer answer) {
        if (answer != null && answer != Answer.IGNORE)
            cooldown.put(target.getUUID(), npc.level().getGameTime() + COOLDOWN_MIN + npc.getRandom().nextInt(COOLDOWN_SPAN));
    }

    private void finish(String reason) {
        GameplayLog.event(npc, "child", "end reason={} gesture={} phase={} ticks={} hand={}", reason, gesture, phase, total, npc.getMainHandItem().getItem());
        done = true;
    }

    /** Shows the animal this gesture (and its item, if any); it answers once through YukkuriSocial. */
    private void offer(ItemEntity thing) {
        long now = npc.level().getGameTime();
        ROLLS.values().removeIf(old -> now - old.at() >= OFFER_TICKS);
        ROLLS.put(target.getUUID(), new Roll(npc.getUUID(), thing == null ? null : thing.getUUID(), gesture, now));
        REPLIES.remove(target.getUUID());
    }

    /** Some animal is in its own toy session on this ball (looked up every fifth tick). */
    private boolean playing() {
        if (npc.tickCount % 5 == 0) playing = !npc.level().getEntitiesOfClass(YukkuriEntity.class, item.getBoundingBox().inflate(SEE),
                y -> y.social().playingWith(item)).isEmpty();
        return playing;
    }

    private boolean eligible(YukkuriEntity y) {
        var identity = y.status().identity();
        boolean kept = identity.companion() && ownerNear(y) != null;
        return y.isAlive() && (identity.free() && !identity.wild() || kept) && !y.isPassenger() && !y.isVehicle() && !y.isLeashed()
                && !y.life().isSleeping() && !y.profile().dangerToYukkuri() && y.body().healthRatio() >= .4
                && y.family().stage() != FamilyLife.Stage.BABY && !y.isPanicking() && !y.isInWaterOrBubble()
                && !y.isCommandPlaying() && !y.operations().active()
                && npc.distanceToSqr(y) <= (SEE + 1) * (SEE + 1) && npc.hasLineOfSight(y);
    }

    private boolean playerNear() {
        return npc.level().getNearestPlayer(npc, 3) != null;
    }

    private boolean predatorNear() {
        return !npc.level().getEntitiesOfClass(YukkuriEntity.class, npc.getBoundingBox().inflate(SEE, 2, SEE),
                y -> y.isAlive() && y.profile().dangerToYukkuri()).isEmpty();
    }

    /** A dry stand at this gesture's distance from the animal on the child's side, in sight of it and reachable on foot. */
    private Vec3 findStand(YukkuriEntity y) {
        var away = horizontal(npc.position().subtract(y.position()));
        if (away.lengthSqr() < 1e-6) away = new Vec3(1, 0, 0);
        for (double turn : new double[]{0, 30, -30, 60, -60, 90, -90}) {
            double rad = Math.toRadians(turn);
            var dir = new Vec3(away.x * Math.cos(rad) - away.z * Math.sin(rad), 0, away.x * Math.sin(rad) + away.z * Math.cos(rad));
            var at = y.position().add(dir.scale(gesture.stand));
            var feet = standAt(at.x, y.getY(), at.z);
            if (feet == null) continue;
            var eye = feet.add(0, npc.getEyeHeight(), 0);
            if (npc.level().clip(new ClipContext(eye, y.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, npc))
                    .getType() != HitResult.Type.MISS) continue;
            var path = npc.getNavigation().createPath(BlockPos.containing(feet), 0);
            if (path != null && path.canReach()) return feet;
        }
        return null;
    }

    /** Feet position on dry ground with room for the child's body, within a block of the given floor, or null. */
    private Vec3 standAt(double x, double refY, double z) {
        var level = npc.level();
        for (int dy : new int[]{0, 1, -1}) {
            var pos = new BlockPos(Mth.floor(x), Mth.floor(refY) + dy, Mth.floor(z));
            var below = pos.below();
            if (level.getBlockState(below).getCollisionShape(level, below).isEmpty() || !level.getFluidState(pos).isEmpty()
                    || !level.getFluidState(below).isEmpty()) continue;
            double top = below.getY() + level.getBlockState(below).getCollisionShape(level, below).max(Direction.Axis.Y);
            var box = npc.getDimensions(npc.getPose()).makeBoundingBox(x, top, z);
            if (level.noCollision(npc, box)) return new Vec3(x, top, z);
        }
        return null;
    }

    /**
     * Dry, open floor for the whole length, so the item neither drops into water nor stops against a block. A low block in the feet cell
     * (a path, 15/16 high, the child's own floor) is floor, not an obstacle: only its real collision top above the child's feet blocks.
     */
    private boolean lineRollable(Vec3 from, Vec3 direction, double length) {
        var level = npc.level();
        for (double t = .4; t <= length; t += .4) {
            var at = from.add(direction.scale(t));
            var feet = new BlockPos(Mth.floor(at.x), npc.getBlockY(), Mth.floor(at.z));
            var below = feet.below();
            var shape = level.getBlockState(feet).getCollisionShape(level, feet);
            boolean blocked = !shape.isEmpty() && feet.getY() + shape.max(Direction.Axis.Y) > npc.getY() + .1;
            if (blocked || !level.getFluidState(feet).isEmpty()
                    || level.getBlockState(below).getCollisionShape(level, below).isEmpty() || !level.getFluidState(below).isEmpty()) return false;
        }
        return true;
    }

    private static Vec3 horizontal(Vec3 v) {
        var flat = new Vec3(v.x, 0, v.z);
        return flat.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : flat.normalize();
    }

    private static double horizontalSqr(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return dx * dx + dz * dz;
    }
}
