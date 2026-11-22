package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.interaction.ItemContact;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A park child shows a free stray its yarn ball and rolls it past (EH29-B). One roll is one invitation: the child stops 3-4 blocks
 * away, draws the ball back for a second, rolls it to one side of the animal (never at its face) and watches. The animal answers by
 * its own values (YukkuriSocial.answerChildOffer reads the tagged ball). The child fetches the same ball back only once play is over
 * and it lies loose within reach; otherwise it leaves it. The ball is a real item entity and nothing regenerates it.
 */
public final class ChildPlay extends Goal {
    /** What the client draws of the gesture: the ball drawn back before the roll, a raised hand after the animal's answer. */
    public enum Pose { NONE, DRAW, WAVE }
    private enum Phase { APPROACH, SHOW, WATCH, RECOVER }

    /** The child notices a stray within this many blocks (design: 8). */
    private static final int SEE = 8;
    private static final int SCAN = 20;
    /** Stand distance from the animal: 3-4 blocks. */
    private static final double STAND = 3.5, STAND_NEAR = 2.0, STAND_FAR = 4.5;
    /** The ball is drawn back for one second before it leaves the hand. */
    private static final int TELEGRAPH = 20;
    /** Walk-up and gesture share a hard 20 s limit. */
    private static final int GESTURE_LIMIT = 400;
    /** A roll passes this far beside the animal's centre and travels this far beyond it. */
    private static final double LATERAL = 1.4, BEYOND = 1.2;
    /** Roll speed in blocks per tick (2.4 blocks/s): the item's own friction would stop a single push within a block. */
    private static final double ROLL_SPEED = .12;
    /** 3 s of watching after a refusal, 8 s when the animal actually plays. */
    private static final int WATCH_SHORT = 60, WATCH_LONG = 160;
    private static final int WAVE_TICKS = 40, WAVE_SHORT = 24;
    /** Interest in one animal lasts 40-100 s from the roll; the same pair waits 3-6 min before another roll. */
    private static final int ENGAGED_MIN = 800, ENGAGED_SPAN = 1200, COOLDOWN_MIN = 3600, COOLDOWN_SPAN = 3600;
    private static final int OFFER_TICKS = 100;
    /** Rolls still waiting for the animal's answer, by animal. Server-thread only; an entry lives five seconds at most. */
    private record Roll(UUID child, UUID ball, long at) {}
    private static final Map<UUID, Roll> ROLLS = new HashMap<>();

    private final HumanNpc npc;
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private Phase phase = Phase.APPROACH;
    private YukkuriEntity target;
    private ItemEntity ball;
    private Vec3 stand, rollDir = Vec3.ZERO;
    private double rollLeft;
    private int total, clock, watchEnd, engagedUntil, waveLeft, nextScan;
    private boolean done, playing;

    ChildPlay(HumanNpc npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** A rolled ball the animal can see, still waiting for its answer. */
    public record Offer(HumanNpc child, ItemEntity ball) {}

    /** The ball a child rolled to this animal within the last five seconds, or null. */
    public static Offer offerTo(YukkuriEntity animal) {
        var roll = ROLLS.get(animal.getUUID());
        if (roll == null || !(animal.level() instanceof ServerLevel level)) return null;
        if (level.getGameTime() - roll.at() >= OFFER_TICKS) { ROLLS.remove(animal.getUUID()); return null; }
        if (level.getEntity(roll.child()) instanceof HumanNpc child && child.isAlive()
                && level.getEntity(roll.ball()) instanceof ItemEntity ball && ball.isAlive()
                && animal.hasLineOfSight(child) && animal.hasLineOfSight(ball)) return new Offer(child, ball);
        return null;
    }

    /** One answer per roll: later evaluations never answer the same ball again. */
    public static void answered(YukkuriEntity animal) { ROLLS.remove(animal.getUUID()); }

    @Override public boolean canUse() {
        if (npc.look() != HumanNpc.Look.CHILD || npc.tickCount < nextScan) return false;
        nextScan = npc.tickCount + SCAN;
        if (!(npc.level() instanceof ServerLevel level) || !npc.isAlive() || npc.isNoAi() || !npc.onGround() || !level.isDay()
                || level.isRaining() || npc.resting() || npc.avoidingStreet() || !npc.getMainHandItem().is(ModItems.TOY.get())
                || playerNear()) return false;
        if (predatorNear()) {
            npc.goHome(600);
            GameplayLog.event(npc, "child", "go_home reason=predator");
            return false;
        }
        long now = level.getGameTime();
        cooldown.values().removeIf(until -> until <= now);
        target = level.getEntitiesOfClass(YukkuriEntity.class, npc.getBoundingBox().inflate(SEE, 2, SEE), this::eligible).stream()
                .filter(y -> cooldown.getOrDefault(y.getUUID(), 0L) <= now).min(Comparator.comparingDouble(npc::distanceToSqr)).orElse(null);
        if (target == null) return false;
        stand = findStand(target);
        if (stand == null) {
            // Nothing changed for this animal until it moves or the child does: wait out a short pause rather than rescanning it every second.
            cooldown.put(target.getUUID(), now + COOLDOWN_MIN / 3);
            return false;
        }
        return true;
    }

    @Override public void start() {
        var level = (ServerLevel) npc.level();
        phase = Phase.APPROACH;
        done = false;
        total = clock = 0;
        ball = null;
        playing = false;
        cooldown.put(target.getUUID(), level.getGameTime() + COOLDOWN_MIN + npc.getRandom().nextInt(COOLDOWN_SPAN));
        npc.getNavigation().moveTo(stand.x, stand.y, stand.z, .9);
        GameplayLog.event(npc, "child", "approach target={} identity={} distance={} stand={}", target.getUUID(),
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
            case WATCH -> watch();
            case RECOVER -> recover();
        }
    }

    @Override public void stop() {
        npc.setChildPose(Pose.NONE);
        npc.getNavigation().stop();
        target = null;
        ball = null;
    }

    private void approach() {
        if (!eligible(target)) { finish("target_left"); return; }
        npc.getLookControl().setLookAt(target, 30, 30);
        double distance = Math.sqrt(horizontalSqr(npc.position(), target.position()));
        if (distance >= STAND_NEAR && distance <= STAND_FAR && npc.hasLineOfSight(target) && npc.getNavigation().isDone()) {
            npc.getNavigation().stop();
            phase = Phase.SHOW;
            clock = 0;
            npc.setChildPose(Pose.DRAW);
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
        if (!eligible(target) || distance < STAND_NEAR || distance > STAND_FAR + 1 || total >= GESTURE_LIMIT) {
            finish(total >= GESTURE_LIMIT ? "gesture_timeout" : "target_moved");
            return;
        }
        if (playerNear()) return;   // the gesture pauses while someone is at the child's side
        if (++clock < TELEGRAPH) return;
        roll();
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
        long now = npc.level().getGameTime();
        ROLLS.values().removeIf(old -> now - old.at() >= OFFER_TICKS);
        ROLLS.put(target.getUUID(), new Roll(npc.getUUID(), dropped.getUUID(), now));
        ball = dropped;
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

    private void watch() {
        clock++;
        if (target != null) npc.getLookControl().setLookAt(target, 30, 30);
        if (!ball.isAlive()) { finish("ball_gone"); return; }
        push();
        if (clock == WATCH_SHORT && playing()) watchEnd = WATCH_LONG;
        if (clock < watchEnd) return;
        String reaction = playing() ? "played" : target.social().retreatingFrom(npc) ? "fled" : "watched";
        if (!reaction.equals("watched")) {
            npc.setChildPose(Pose.WAVE);
            waveLeft = reaction.equals("played") ? WAVE_TICKS : WAVE_SHORT;
        }
        GameplayLog.event(npc, "child", "reaction={} target={} ball={}", reaction, target.getUUID(), ball.getUUID());
        phase = Phase.RECOVER;
        clock = 0;
    }

    /** The ball keeps rolling at walking pace until it has gone past the animal, hits something or an animal takes it up. */
    private void push() {
        if (rollLeft <= 0 || playing()) return;
        if (ball.horizontalCollision || ball.isInWater() || !ball.onGround() && ball.getDeltaMovement().y < -.3) { rollLeft = 0; return; }
        Motor.velocity(ball, new Vec3(rollDir.x * ROLL_SPEED, ball.getDeltaMovement().y, rollDir.z * ROLL_SPEED), "child_roll");
        ball.hurtMarked = true;
        rollLeft -= ROLL_SPEED;
    }

    private void recover() {
        clock++;
        if (!ball.isAlive()) { finish("ball_gone"); return; }
        push();
        if (ball.isInWater() || ball.isInLava()) { finish("ball_unreachable"); return; }
        if (total >= engagedUntil) { finish("bored"); return; }
        npc.getLookControl().setLookAt(ball, 30, 30);
        if (playing() || rollLeft > 0) { npc.getNavigation().stop(); return; }   // the ball is still the animal's, or still rolling
        if (npc.reachesPickup(ball) && !ball.hasPickUpDelay() && npc.getMainHandItem().isEmpty()) {
            var stack = ball.getItem();
            npc.setItemSlot(EquipmentSlot.MAINHAND, stack.copy());
            npc.take(ball, stack.getCount());
            ball.discard();
            GameplayLog.event(npc, "child", "ball_recovered ball={} item={} count={}", ball.getUUID(), stack.getItem(), stack.getCount());
            finish("recovered");
            return;
        }
        if (clock % 20 == 1 && !npc.getNavigation().moveTo(ball, .9) && !npc.reachesPickup(ball)) finish("ball_unreachable");
    }

    private void finish(String reason) {
        GameplayLog.event(npc, "child", "end reason={} phase={} ticks={} hand={}", reason, phase, total, npc.getMainHandItem().getItem());
        done = true;
    }

    /** Some animal is in its own toy session on this ball (looked up every fifth tick). */
    private boolean playing() {
        if (npc.tickCount % 5 == 0) playing = !npc.level().getEntitiesOfClass(YukkuriEntity.class, ball.getBoundingBox().inflate(SEE),
                y -> y.social().playingWith(ball)).isEmpty();
        return playing;
    }

    private boolean eligible(YukkuriEntity y) {
        var identity = y.status().identity();
        return y.isAlive() && identity.free() && !identity.wild() && !y.isPassenger() && !y.isVehicle() && !y.isLeashed()
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

    /** A dry stand 3-4 blocks from the animal on the child's side, in sight of it and reachable on foot. */
    private Vec3 findStand(YukkuriEntity y) {
        var away = horizontal(npc.position().subtract(y.position()));
        if (away.lengthSqr() < 1e-6) away = new Vec3(1, 0, 0);
        for (double turn : new double[]{0, 30, -30, 60, -60, 90, -90}) {
            double rad = Math.toRadians(turn);
            var dir = new Vec3(away.x * Math.cos(rad) - away.z * Math.sin(rad), 0, away.x * Math.sin(rad) + away.z * Math.cos(rad));
            var at = y.position().add(dir.scale(STAND));
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

    /** Feet position on dry ground with room for the child's body, within a block of the animal's floor, or null. */
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

    /** Dry, open floor for the whole rolled length, so the ball neither drops into water nor stops against a block. */
    private boolean lineRollable(Vec3 from, Vec3 direction, double length) {
        var level = npc.level();
        for (double t = .4; t <= length; t += .4) {
            var at = from.add(direction.scale(t));
            var feet = new BlockPos(Mth.floor(at.x), npc.getBlockY(), Mth.floor(at.z));
            var below = feet.below();
            if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() || !level.getFluidState(feet).isEmpty()
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
