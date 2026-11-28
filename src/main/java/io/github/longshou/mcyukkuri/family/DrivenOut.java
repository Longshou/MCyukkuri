package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.presentation.FaceTier;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.mind.EmotionCourse;
import io.github.longshou.mcyukkuri.mind.Ousting.Answer;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.perception.Cue;
import io.github.longshou.mcyukkuri.perception.Senses;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.social.Departure;
import io.github.longshou.mcyukkuri.social.SeveranceReaction;
import io.github.longshou.mcyukkuri.social.SeveranceReaction.ParentStepInAnswer;
import io.github.longshou.mcyukkuri.social.SeveranceVerdict.Verdict;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * The child side of a parent's verdict to drive it out (docs/FEATURES.md 载重与断亲). The parent only gives the order; the child owns
 * its departure: once it stands on dry ground it answers by its own character (it pleads and steps up to her, protests, or only
 * stares), turns, and walks away over dry ground ({@link Departure}, the same exit the mould exile takes) until it is truly gone, out
 * of her sight or far off. Only then is the tie cut ({@link Departure#parted}: the child no longer expects her, she does not take it
 * back), the home it had released, and its siblings and a tearful parent left with the parting feeling. A child that never gets
 * away within {@link #WINDOW} is not cut and is her child again.
 * <p>From the order until the cut the child is no longer hers ({@link Bonds#parentOf}) and does not look for her ({@link FamilyLife#expectsCare}).
 */
public final class DrivenOut extends Goal implements MotorOwner {
    public enum Phase { NONE, WAITING, GOING }
    /** Phases of a bold attached sibling stepping between the parent and the driven child to plead (a plea, never a veto). */
    private enum StepIn { NONE, WALKING, PLEADING, STEP_ASIDE, DONE }

    /** Ticks an order stays owed (the child must first reach dry ground; the walk itself is limited by {@link Departure}). */
    private static final int WINDOW = 2400, BUDGET = 5000;
    /** A shore watch runs until the tie is cut (the child's own walk budget). */
    private static final int WATCH_CAP = BUDGET;
    /** The range it is driven out of (blocks round where she stood), the sight it counts as gone from, and the distance that is gone anyway. */
    private static final int RANGE = 7, GONE_NEAR = 8, GONE_FAR = 16, SIBLING_SIGHT = 12, SAY_PRIORITY = 4;
    /** Lines take turns: its plea or turn waits for a neighbour's line (the parent's verdict) to end, at most this long (ticks); within this range (blocks). */
    private static final int TURN_CAP = 120, TURN_RANGE = 6;
    /** Finite budgets (ticks) for a sibling stepping in: walking to the stand, waiting to speak, holding its plea for the parent to hear,
     *  waiting for the parent's answer, and reacting/stepping aside afterwards. Every step falls back cleanly when its budget runs out. */
    private static final int STEPIN_WALK_CAP = 200, PLEA_TURN_CAP = 80, PLEA_HEAR_TICKS = 35, STEPIN_PLEA_CAP = 300, STEPIN_ASIDE_CAP = 80;
    /** Front-shield angles (degrees) tried around the parent->child line when the exact midpoint is blocked. */
    private static final double[] SHIELD_ANGLES = {25, -25, 50, -50};

    private final YukkuriEntity entity;
    private final Departure departure;
    private final Departure.Turn stepInTurn;
    private final Set<UUID> witnessed = new HashSet<>();
    private Departure.BannedRange banned;
    private UUID driver, watchParent, watchChild;
    /** The stance face a watching sibling keeps through the severance, renewed while the watch lasts. */
    private String watchFace;
    private Verdict verdict;
    private String reason = "";
    private Phase phase = Phase.NONE;
    private StepIn stepIn = StepIn.NONE;
    private Action stepInAction;
    /** The tick the order lapses at: {@link #WINDOW} to reach dry ground, then {@link #BUDGET} for the whole departure. */
    private long deadline, watchUntil, watchOrderBy, stepInDeadline, pleaSpokenAt;
    private Vec3 centre, stepInParentStart, stepInChildStart;
    private boolean begun, stepInArrived, stepInRetargeted, stepInAfterDone, stepInAsideMoved;
    private String stepInFallbackReason;

    public DrivenOut(YukkuriEntity entity) {
        this.entity = entity;
        departure = new Departure(entity, new Departure.Turn(entity, 2 * TURN_RANGE, TURN_CAP));
        stepInTurn = new Departure.Turn(entity, 2 * TURN_RANGE, PLEA_TURN_CAP);
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override public String ownerId() { return "driven_out"; }
    @Override public boolean active() { return stepInAction != null && phase == Phase.NONE && watchTarget() != null; }

    @Override public void finished(Action action, Outcome outcome) {
        if (action != stepInAction) return;
        stepInAction = null;
        if (stepIn == StepIn.WALKING) {
            if (outcome instanceof Outcome.Done) {
                stepInArrived = true;
            } else {
                var parent = watchParent == null ? null : level().getEntity(watchParent) instanceof YukkuriEntity p && p.isAlive() ? p : null;
                var child = watchChild == null ? null : level().getEntity(watchChild) instanceof YukkuriEntity c && c.isAlive() ? c : null;
                boolean moved = !stepInRetargeted && parent != null && child != null
                        && (stepInParentStart != null && parent.position().distanceToSqr(stepInParentStart) > .25
                                || stepInChildStart != null && child.position().distanceToSqr(stepInChildStart) > .25);
                if (moved) stepInRetargeted = true;
                else stepInFallbackReason = outcome.reason() != null
                        ? outcome.reason().name().toLowerCase(Locale.ROOT)
                        : outcome.label().toLowerCase(Locale.ROOT);
            }
        } else if (stepIn == StepIn.STEP_ASIDE && stepInAfterDone) {
            stepIn = StepIn.DONE;
        }
    }

    private ServerLevel level() { return (ServerLevel) entity.level(); }

    /** The parent who is driving it out, or null. */
    public UUID driver() { return driver; }
    public Phase phase() { return phase; }
    public Verdict verdict() { return verdict; }
    /** It stands in the hold of its plea or protest, before it turns to go. */
    public boolean holding() { return phase == Phase.GOING && departure.holding(); }
    /** It has already turned away to leave. */
    public boolean turned() { return phase == Phase.GOING && departure.turned(); }
    /** The pleader stands at her feet and waits for her answer. */
    public boolean pleaderAtParent() { return holding() && departure.atDriver(); }
    /** She turned it away: its plea ends. */
    public void rebuffed(boolean shoved) { departure.rebuff(shoved); }
    public String activity() { return phase == Phase.GOING ? departure.activity() : null; }

    private YukkuriEntity parent() {
        return driver != null && level().getEntity(driver) instanceof YukkuriEntity p && p.isAlive() ? p : null;
    }

    /** Whether {@code pos} is outside the home range this child was permanently driven out of. */
    public boolean allowsAddress(BlockPos pos) {
        return banned == null || banned.allows(entity.level(), pos);
    }

    public void save(CompoundTag tag) {
        if (banned != null) banned.save(tag, "DrivenOutBanned");
    }

    public void load(CompoundTag tag) {
        banned = Departure.BannedRange.load(tag, "DrivenOutBanned");
    }

    /** The parent's order: it is no longer hers from here on; it leaves as soon as it stands on dry ground. */
    public void order(YukkuriEntity parent, Verdict verdict, String reason) {
        driver = parent.getUUID();
        this.verdict = verdict;
        this.reason = reason;
        phase = Phase.WAITING;
        deadline = level().getGameTime() + WINDOW;
        centre = parent.position();
        begun = false;
        witnessed.clear();
        GameplayLog.event(entity, "family", "driven out ordered parent={} verdict={} in_water={} distance={}",
                driver, verdict, entity.isInWaterOrBubble(), Math.round(entity.distanceTo(parent)));
    }

    private void lapse(String why) {
        GameplayLog.event(entity, "family", "driven out lapsed reason={} parent={} phase={}", why, driver, phase);
        clear();
    }

    private void clear() {
        driver = null;
        verdict = null;
        phase = Phase.NONE;
        begun = false;
        witnessed.clear();
        SurfaceApproach.halt(entity);
    }

    private boolean available() {
        return entity.isAlive() && !entity.isNoAi() && !entity.body().dying() && !entity.isPassenger() && !entity.isInWaterOrBubble()
                && !entity.species().catchingBreath()
                && entity.onGround() && !entity.isPanicking() && !entity.isOnFire() && !entity.isCommandPlaying()
                && !entity.operations().active() && !entity.life().isSleeping() && !entity.life().hasUrgentNeed();
    }

    /** Why the order can no longer be carried out, or null. */
    private String stale() {
        if (!entity.isAlive()) return "child_gone";
        if (level().getGameTime() > deadline) return "timeout";
        return null;
    }

    private void stopStepInMotor(String why) {
        if (stepInAction != null) {
            stepInAction = null;
            if (entity.motor().owner() == this) entity.motor().cancel(this, why);
        }
    }

    private void clearWatch() {
        stopStepInMotor("watch_cleared");
        watchParent = watchChild = null;
        watchFace = null;
        watchUntil = watchOrderBy = stepInDeadline = pleaSpokenAt = 0;
        stepIn = StepIn.NONE;
        stepInArrived = stepInRetargeted = stepInAfterDone = stepInAsideMoved = false;
        stepInParentStart = stepInChildStart = null;
        stepInFallbackReason = null;
    }

    /** Whether this sibling is actively stepping in, holding its plea, or yielding aside after the parent's answer for {@code (parent, child)}. */
    public boolean isIntercedingFor(YukkuriEntity parent, YukkuriEntity child) {
        return phase == Phase.NONE && (stepIn == StepIn.WALKING || stepIn == StepIn.PLEADING || stepIn == StepIn.STEP_ASIDE)
                && parent != null && child != null
                && parent.getUUID().equals(watchParent) && child.getUUID().equals(watchChild)
                && entity.isAlive() && !entity.isInWaterOrBubble() && !entity.isPassenger() && !child.drivenOut().turned();
    }

    /** Whether this sibling has reached the parent, spoken its plea and held it long enough for the parent to answer. */
    public boolean isPleadingAt(YukkuriEntity parent, YukkuriEntity child) {
        return isIntercedingFor(parent, child) && stepIn == StepIn.PLEADING
                && pleaSpokenAt > 0 && level().getGameTime() - pleaSpokenAt >= PLEA_HEAR_TICKS;
    }

    /** The bold attached sibling currently pleading at {@code parent} on {@code child}'s behalf, or null. */
    public static YukkuriEntity pleadingSibling(YukkuriEntity parent, YukkuriEntity child) {
        if (parent == null || child == null || !(parent.level() instanceof ServerLevel level)) return null;
        return level.getEntitiesOfClass(YukkuriEntity.class, parent.getBoundingBox().inflate(16),
                y -> y != parent && y != child && y.isAlive() && y.drivenOut().isPleadingAt(parent, child))
                .stream().min(Comparator.comparingDouble(parent::distanceToSqr)).orElse(null);
    }

    /** The bold attached sibling currently walking in or pleading on {@code child}'s behalf, or null. */
    public static YukkuriEntity intercedingSibling(YukkuriEntity parent, YukkuriEntity child) {
        if (parent == null || child == null || !(parent.level() instanceof ServerLevel level)) return null;
        return level.getEntitiesOfClass(YukkuriEntity.class, parent.getBoundingBox().inflate(16),
                y -> y != parent && y != child && y.isAlive() && y.drivenOut().isIntercedingFor(parent, child))
                .stream().min(Comparator.comparingDouble(parent::distanceToSqr)).orElse(null);
    }

    /** Whether any sibling is actively stepping in or pleading for {@code child} before {@code parent}. */
    public static boolean intercessionActive(YukkuriEntity parent, YukkuriEntity child) {
        return intercedingSibling(parent, child) != null;
    }

    /** The parent answered this sibling's plea (soothed, nudged back, or ignored): it reacts and yields the line without vetoing the severance. */
    public void stepInAnswered(ParentStepInAnswer answer) {
        if (stepIn != StepIn.PLEADING) return;
        var after = SeveranceReaction.siblingAfterStepIn(answer);
        watchFace = after.face();
        stepInAfterDone = false;
        stepInAsideMoved = false;
        int clipTicks = after.clip() == null ? 0 : Math.max(40, after.clip().durationTicks());
        boolean armed = entity.showWithDeferredLine(after.clip(), clipTicks, after.face(), after.line(), FaceTier.Tier.LONG, SAY_PRIORITY, said -> {
            stepInAfterDone = true;
            entity.beat(after.beat(), after.face(), said.spoken() ? after.line() : "");
            GameplayLog.event(entity, "family", "driven out sibling stepin answered answer={} tick={} event={} spoken={}",
                    answer, said.tick() < 0 ? "none" : said.tick(), after.line(), said.spoken());
        });
        if (!armed) stepInAfterDone = true;
        stepIn = StepIn.STEP_ASIDE;
        stepInDeadline = level().getGameTime() + STEPIN_ASIDE_CAP;
        stepInAction = null;
    }

    /** Keeps a witness at the child's side from the verdict to the cut, and the stance face it shows meanwhile; {@code orderBy} is when
     *  the child should have begun its walk, after which a child still idle ends the watch. */
    private static void watch(YukkuriEntity sibling, YukkuriEntity parent, YukkuriEntity child, String face, long orderBy) {
        var watch = sibling.drivenOut();
        if (watch.watchChild == null) {
            watch.watchParent = parent.getUUID();
            watch.watchChild = child.getUUID();
            watch.watchOrderBy = orderBy;
            watch.watchUntil = orderBy + WATCH_CAP;
        }
        watch.watchFace = face;
    }

    private YukkuriEntity watchTarget() {
        if (watchChild == null || watchParent == null) return null;
        long now = level().getGameTime();
        if (now > watchUntil) { clearWatch(); return null; }
        var parent = level().getEntity(watchParent) instanceof YukkuriEntity p && p.isAlive() ? p : null;
        var child = level().getEntity(watchChild) instanceof YukkuriEntity c && c.isAlive() ? c : null;
        if (parent == null || child == null || entity.distanceToSqr(child) > 24 * 24) { clearWatch(); return null; }
        // The watch runs to the cut, not to the child's turn: at the turn its stance face is just beginning, and a sibling left to its own
        // drives wanders off (and, far enough away, breaks into tears) before the tie is severed.
        if (now > watchOrderBy && child.drivenOut().phase() == Phase.NONE) { clearWatch(); return null; }
        return child;
    }

    @Override public boolean canUse() {
        if (phase != Phase.NONE) {
            String why = stale();
            if (why != null) { lapse(why); return false; }
            return available();
        }
        return available() && watchTarget() != null;
    }

    @Override public boolean canContinueToUse() {
        if (phase == Phase.GOING) {
            String why = stale();
            if (why != null) { lapse(why); return false; }
            return available();
        }
        if (phase != Phase.NONE) return false;
        return available() && watchTarget() != null;
    }

    @Override public boolean requiresUpdateEveryTick() { return true; }

    @Override public void start() {
        if (phase == Phase.NONE) {
            SurfaceApproach.halt(entity);
            return;
        }
        clearWatch();
        phase = Phase.GOING;
        var terms = terms();
        if (begun) departure.resume(terms);
        else {
            begun = true;
            deadline = level().getGameTime() + BUDGET;
            departure.begin(terms);
            var parent = parent();
            GameplayLog.event(entity, "family", "driven out begins parent={} verdict={} distance={} answer={} pleads={} lookBacks={}",
                    driver, verdict, parent == null ? -1 : Math.round(entity.distanceTo(parent)), departure.answer(), departure.pleads(),
                    departure.lookBacks());
        }
    }

    private Departure.Terms terms() {
        return new Departure.Terms("family", "driven out", centre, RANGE, parent(), driver, 1f, false, true, true, true, null,
                this::siblingsCry, () -> intercessionActive(parent(), entity));
    }

    /** Perceiving ashore siblings react at once when the parent starts pronouncing severance and stay watching through the confrontation
     *  and the walk out until the tie is cut, so none wanders off or wears a plain or happy face during it. At most one nearest bold
     *  attached sibling that is free on dry ground steps forward between the parent and the driven child to plead. */
    public static void witnessVerdict(YukkuriEntity parent, YukkuriEntity child, int verdictTicks) {
        if (!(parent.level() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        YukkuriEntity intercessor = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (var sibling : level.getEntitiesOfClass(YukkuriEntity.class, parent.getBoundingBox().inflate(24),
                y -> y != child && y != parent && y.isAlive() && !y.family().adult() && Bonds.parentOf(parent, y)
                        && !y.body().dying() && !y.life().isSleeping())) {
            var via = Senses.perceives(sibling, parent.getEyePosition(), null, Cue.DEPARTURE_CRY);
            if (via == null) continue;
            var t = sibling.life().personality().temperament();
            if (sibling.isInWaterOrBubble() || sibling.isPassenger()) {
                if (SeveranceReaction.siblingStepsIn(t)) {
                    GameplayLog.event(sibling, "family", "driven out sibling stepin skipped child={} reason={}",
                            child.getUUID(), sibling.isInWaterOrBubble() ? "in_water" : "aboard");
                }
                continue;
            }
            var stance = SeveranceReaction.siblingStance(t);
            sibling.getLookControl().setLookAt(parent, 30, 30);
            sibling.forceReaction(stance.verdictFace(), "", FaceTier.Tier.LONG);
            sibling.beat("sibling_verdict", stance.verdictFace(), "");
            watch(sibling, parent, child, stance.verdictFace(), now + verdictTicks + 20);
            GameplayLog.event(sibling, "family", "driven out sibling witnessed verdict child={} stance={} via={}",
                    child.getUUID(), stance, via);
            if (SeveranceReaction.siblingStepsIn(t) && !sibling.isPanicking() && !sibling.operations().active()
                    && !sibling.life().hasUrgentNeed()) {
                double d = sibling.distanceToSqr(parent);
                if (d < bestDist) { bestDist = d; intercessor = sibling; }
            }
        }
        if (intercessor != null && intercedingSibling(parent, child) == null) {
            intercessor.drivenOut().beginStepIn(parent, child, now);
        }
    }

    private void beginStepIn(YukkuriEntity parent, YukkuriEntity child, long now) {
        stepIn = StepIn.WALKING;
        watchFace = "teary_frown";
        stepInDeadline = now + STEPIN_WALK_CAP;
        pleaSpokenAt = 0;
        stepInArrived = stepInRetargeted = stepInAfterDone = stepInAsideMoved = false;
        stepInFallbackReason = null;
        stepInParentStart = parent.position();
        stepInChildStart = child.position();
        GameplayLog.event(entity, "family", "driven out sibling stepin start child={} parent={} distance={}",
                child.getUUID(), parent.getUUID(), Math.round(entity.distanceTo(parent) * 100) / 100.0);
    }

    private void endStepIn(String why) {
        stopStepInMotor("stepin_" + why);
        stepInFallbackReason = null;
        stepInArrived = false;
        if (stepIn != StepIn.DONE && stepIn != StepIn.NONE) {
            GameplayLog.event(entity, "family", "driven out sibling stepin fallback child={} reason={} phase={}", watchChild, why, stepIn);
        }
        stepIn = StepIn.DONE;
        var stance = SeveranceReaction.siblingStance(entity.life().personality().temperament());
        watchFace = stance.rebuffFace();
    }

    /** Perceiving ashore siblings react when the parent turns her back or shoves a pleading child, holding their expression through the turn. */
    public static void witnessRebuff(YukkuriEntity parent, YukkuriEntity child) {
        if (!(parent.level() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        for (var sibling : level.getEntitiesOfClass(YukkuriEntity.class, parent.getBoundingBox().inflate(24),
                y -> y != child && y != parent && y.isAlive() && !y.family().adult() && Bonds.parentOf(parent, y)
                        && !y.isInWaterOrBubble() && !y.body().dying() && !y.life().isSleeping())) {
            var via = Senses.perceives(sibling, child.getEyePosition(), null, Cue.DEPARTURE_CRY);
            if (via == null) continue;
            var stance = SeveranceReaction.siblingStance(sibling.life().personality().temperament());
            sibling.getLookControl().setLookAt(child, 30, 30);
            sibling.forceReaction(stance.rebuffFace(), "", FaceTier.Tier.LONG);
            sibling.beat("sibling_rebuff", stance.rebuffFace(), "");
            watch(sibling, parent, child, stance.rebuffFace(), now);
        }
    }

    /** It turns to go: only siblings that actually perceived it (saw or heard via {@link Senses}) answer by their own temperament and hold LONG. */
    private void siblingsCry(Answer answer) {
        var parent = parent();
        if (parent == null) return;
        long now = level().getGameTime();
        for (var sibling : level().getEntitiesOfClass(YukkuriEntity.class, parent.getBoundingBox().inflate(24),
                y -> y != entity && y.isAlive() && !y.family().adult() && Bonds.parentOf(parent, y))) {
            var via = Senses.perceives(sibling, entity.getEyePosition(), null, Cue.DEPARTURE_CRY);
            if (via == null) {
                String missed = sibling.distanceToSqr(entity) > SIBLING_SIGHT * SIBLING_SIGHT ? "out_of_range" : "not_perceived";
                GameplayLog.event(sibling, "family", "driven out sibling missed departure child={} reason={}", entity.getUUID(), missed);
                continue;
            }
            witnessed.add(sibling.getUUID());
            var stance = SeveranceReaction.siblingStance(sibling.life().personality().temperament());
            String face = stance.turnFace(), line = stance.turnLine();
            YukkuriAction clip = stance.turnClip();
            int clipTicks = clip == null ? 0 : Math.max(40, clip.durationTicks());
            sibling.getLookControl().setLookAt(entity, 30, 30);
            watch(sibling, parent, entity, face, now);
            // Its line follows the one leaving on the shared speech clock (the turn's own line would otherwise swallow it).
            sibling.showWithDeferredLine(clip, clipTicks, face, line, FaceTier.Tier.LONG, SAY_PRIORITY, said -> {
                sibling.beat("sibling_cry", face, said.spoken() ? line : "");
                GameplayLog.event(sibling, "family", "driven out sibling cry tick={} event={} stance={} holdEnd={} spoken={}",
                        said.tick() < 0 ? "none" : said.tick(), line, stance, said.holdEnd(), said.spoken());
            });
            GameplayLog.event(sibling, "family", "driven out sibling cries child={} via={} stance={}", entity.getUUID(), via, stance);
        }
    }

    @Override public void tick() {
        if (phase == Phase.NONE) {
            var target = watchTarget();
            if (target == null) return;
            if (stepIn != StepIn.NONE && stepIn != StepIn.DONE) {
                tickStepIn(target);
                return;
            }
            SurfaceApproach.stopWalking(entity);
            entity.getLookControl().setLookAt(target, 30, 30);
            // One stance for the whole severance, topped up as its hold lapses: an ordinary beat, so the child's turn and the sibling's
            // own reaction (a clip or a LONG beat) still replace it, and no bound hold can swallow them.
            if (watchFace != null) entity.showReaction(watchFace, "", FaceTier.Tier.MEDIUM);
            return;
        }
        // A terminal tick can end the order before the selector clears this goal.
        if (phase != Phase.GOING) return;
        switch (departure.tick()) {
            case RUNNING -> {}
            case FAILED -> {
                String failure = departure.failure();
                lapse("walk_" + failure);
            }
            case OUT -> {
                var parent = parent();
                double distance = parent == null ? GONE_FAR : entity.distanceTo(parent);
                boolean seen = parent != null && parent.hasLineOfSight(entity);
                if (distance >= GONE_FAR || distance >= GONE_NEAR && !seen) departed(parent, distance, seen);
                else departure.extend(3);
            }
        }
    }

    /** Truly gone: now the tie is cut (even if the parent is unloaded or dead), the old range is banned permanently, the child is a stray,
     *  and only the ones left behind that perceived it feel the parting. */
    private void departed(YukkuriEntity parent, double distance, boolean seen) {
        GameplayLog.event(entity, "family", "driven out departed parent={} verdict={} distance={} sight={} answer={}",
                driver, verdict, Math.round(distance * 10) / 10.0, seen, departure.answer());
        banned = new Departure.BannedRange(GlobalPos.of(level().dimension(), BlockPos.containing(centre)), GONE_FAR);
        Departure.parted(level(), driver, parent, entity, verdict, reason);
        if (parent != null) {
            for (var sibling : level().getEntitiesOfClass(YukkuriEntity.class, parent.getBoundingBox().inflate(24),
                    y -> y != entity && y.isAlive() && !y.family().adult() && Bonds.parentOf(parent, y) && witnessed.contains(y.getUUID()))) {
                var t = sibling.life().personality().temperament();
                var stance = SeveranceReaction.siblingStance(t);
                sibling.drivenOut().clearWatch();
                if (stance.partsWithGrief(t)) {
                    sibling.mind().feeling().begin(EmotionCourse.Kind.PARTING, .5f, entity.getUUID(), entity.blockPosition());
                    Departure.parting(sibling);
                    GameplayLog.event(sibling, "family", "driven out sibling parts child={} stance={}", entity.getUUID(), stance);
                } else {
                    String face = stance.detachedAfterFace();
                    sibling.forceReaction(face, "", FaceTier.Tier.MEDIUM);
                    sibling.beat("sibling_part_detached", face, "");
                    GameplayLog.event(sibling, "family", "driven out sibling detached child={} stance={}", entity.getUUID(), stance);
                }
            }
        }
        entity.habitat().release();
        entity.life().releaseHome();
        entity.settlement().exile();
        departure.feltLoss();
        clear();
        entity.habitat().seekHomeNow();
    }

    /** Drives the bold attached sibling's step-in plea through the shared motor (Walk + yield), with a fallback at every step. */
    private void tickStepIn(YukkuriEntity child) {
        long now = level().getGameTime();
        var parent = watchParent == null ? null : level().getEntity(watchParent) instanceof YukkuriEntity p && p.isAlive() ? p : null;
        if (parent == null || child.drivenOut().turned()) {
            endStepIn(parent == null ? "parent_gone" : "child_turned");
            return;
        }
        if (stepIn == StepIn.WALKING) {
            if (stepInFallbackReason != null) { endStepIn(stepInFallbackReason); return; }
            if (child.isInWaterOrBubble() || child.species().catchingBreath() || !parent.onGround()) {
                SurfaceApproach.stopWalking(entity);
                entity.getLookControl().setLookAt(parent, 30, 30);
                if (stepInAction == null) stepInDeadline = now + STEPIN_WALK_CAP;
                if (watchFace != null) entity.showReaction(watchFace, "", FaceTier.Tier.MEDIUM);
                return;
            }
            if (now > stepInDeadline) { endStepIn("walk_timeout"); return; }
            if (stepInArrived) {
                stepInArrived = false;
                if (ContactGeometry.inMouthReach(parent, entity)) enterPleading(parent, now);
                else endStepIn("short_of_parent");
                return;
            }
            if (stepInAction != null && ContactGeometry.inMouthReach(parent, entity)
                    && (onChildSide(entity.position(), parent.position(), child.position()) || entity.getNavigation().isDone())) {
                stopStepInMotor("in_reach");
                enterPleading(parent, now);
                return;
            }
            if (stepInAction == null) {
                if (ContactGeometry.inMouthReach(parent, entity) && onChildSide(entity.position(), parent.position(), child.position())) {
                    enterPleading(parent, now);
                    return;
                }
                var stand = betweenStand(entity, parent, child);
                if (stand == null) { endStepIn("no_stand"); return; }
                stepInParentStart = parent.position();
                stepInChildStart = child.position();
                var walk = Walk.stand(stand, 1.0, Walk.STAND_ARRIVED);
                if (!entity.motor().run(this, walk)) { endStepIn("walk_refused"); return; }
                stepInAction = walk;
            }
            if (watchFace != null) entity.showReaction(watchFace, "", FaceTier.Tier.MEDIUM);
            return;
        }
        if (stepIn == StepIn.PLEADING) {
            SurfaceApproach.stopWalking(entity);
            entity.getLookControl().setLookAt(parent, 30, 30);
            if (now > stepInDeadline) { endStepIn("plea_timeout"); return; }
            if (pleaSpokenAt == 0) {
                if (entity.quiet() && stepInTurn.getAsBoolean()) {
                    var t = entity.life().personality().temperament();
                    var beat = SeveranceReaction.siblingStepInPlea(t);
                    watchFace = beat.face();
                    entity.forceReaction(beat.clip(), beat.face(), "", FaceTier.Tier.LONG);
                    boolean spoken = entity.speakCue(beat.line(), SeveranceReaction.speechStyle(t), SAY_PRIORITY);
                    entity.beat(beat.beat(), beat.face(), spoken ? beat.line() : "");
                    pleaSpokenAt = now;
                    stepInDeadline = now + STEPIN_PLEA_CAP;
                    GameplayLog.event(entity, "family", "driven out sibling stepped in child={} parent={} distance={} clip={} spoken={}",
                            child.getUUID(), parent.getUUID(), Math.round(entity.distanceTo(parent) * 100) / 100.0, beat.clip().id(), spoken);
                } else if (watchFace != null) {
                    entity.showReaction(watchFace, "", FaceTier.Tier.MEDIUM);
                }
            } else if (watchFace != null) {
                entity.showReaction(watchFace, "", FaceTier.Tier.MEDIUM);
            }
            return;
        }
        if (stepIn == StepIn.STEP_ASIDE) {
            if (now > stepInDeadline) {
                stopStepInMotor("step_aside_timeout");
                stepIn = StepIn.DONE;
                return;
            }
            if (stepInAction == null && !stepInAsideMoved) {
                stepInAsideMoved = true;
                if (inCorridor(entity.position(), parent.position(), child.position())) {
                    var stand = asideStand(entity, parent, child);
                    if (stand != null) {
                        var walk = Walk.stand(stand, 1.0, Walk.STAND_ARRIVED);
                        if (entity.motor().run(this, walk)) stepInAction = walk;
                    }
                }
            }
            if (stepInAction == null && stepInAsideMoved && stepInAfterDone) {
                stepIn = StepIn.DONE;
                return;
            }
            if (stepInAction == null) {
                SurfaceApproach.stopWalking(entity);
                entity.getLookControl().setLookAt(parent, 30, 30);
            }
            if (watchFace != null) entity.showReaction(watchFace, "", FaceTier.Tier.MEDIUM);
        }
    }

    private void enterPleading(YukkuriEntity parent, long now) {
        SurfaceApproach.stopWalking(entity);
        SurfaceApproach.face(entity, SurfaceApproach.yaw(entity.position(), parent.position()));
        entity.getLookControl().setLookAt(parent, 30, 30);
        stepIn = StepIn.PLEADING;
        watchFace = SeveranceReaction.siblingStepInPlea(entity.life().personality().temperament()).face();
        pleaSpokenAt = 0;
        stepInDeadline = now + STEPIN_PLEA_CAP;
    }

    private static boolean onChildSide(Vec3 pos, Vec3 parentPos, Vec3 childPos) {
        Vec3 toChild = childPos.subtract(parentPos).multiply(1, 0, 1);
        if (toChild.lengthSqr() < 1e-4) return true;
        return pos.subtract(parentPos).multiply(1, 0, 1).dot(toChild) > 0;
    }

    private static boolean inCorridor(Vec3 pos, Vec3 parentPos, Vec3 childPos) {
        Vec3 ab = childPos.subtract(parentPos).multiply(1, 0, 1);
        double len = ab.length();
        if (len < 1e-4) return false;
        Vec3 u = ab.scale(1 / len);
        Vec3 ap = pos.subtract(parentPos).multiply(1, 0, 1);
        double proj = ap.dot(u);
        if (proj < 0 || proj > len) return false;
        double perp = ap.subtract(u.scale(proj)).length();
        return perp < .75;
    }

    private static Vec3 validStepInStand(YukkuriEntity sibling, YukkuriEntity parent, Vec3 raw) {
        var at = SurfaceApproach.floor(sibling, raw);
        if (at == null || !SurfaceApproach.stands(sibling, at)
                || !SurfaceApproach.hasDryBankBuffer(sibling, at, sibling.getMaxFallDistance())
                || !ContactGeometry.inMouthReach(sibling, at, parent)
                || !SurfaceApproach.roomAt(sibling, at, parent)
                || !SurfaceApproach.canApproach(sibling, at)) return null;
        return at;
    }

    /** Picks a reachable dry stand between {@code parent} and {@code child} within mouth reach of {@code parent}, falling back to
     *  front-shield angles or the nearest clear stand beside {@code parent}. */
    private static Vec3 betweenStand(YukkuriEntity sibling, YukkuriEntity parent, YukkuriEntity child) {
        Vec3 p = parent.position(), c = child.position();
        Vec3 delta = c.subtract(p).multiply(1, 0, 1);
        double dist = delta.length();
        Vec3 u = dist > 1e-4 ? delta.scale(1 / dist) : Vec3.directionFromRotation(0, parent.getYRot()).multiply(1, 0, 1).normalize();
        double minParent = (parent.getBbWidth() + sibling.getBbWidth()) * .5 + .12;
        double minChild = (child.getBbWidth() + sibling.getBbWidth()) * .5 + .12;
        if (dist >= minParent + minChild) {
            double mid = Math.clamp(dist * .5, minParent, Math.min(dist - minChild, minParent + .20));
            var hit = validStepInStand(sibling, parent, p.add(u.scale(mid)));
            if (hit != null) return hit;
        }
        var front = validStepInStand(sibling, parent, p.add(u.scale(minParent)));
        if (front != null) return front;
        for (double deg : SHIELD_ANGLES) {
            Vec3 dir = u.yRot((float) Math.toRadians(deg));
            var hit = validStepInStand(sibling, parent, p.add(dir.scale(minParent + .04)));
            if (hit != null) return hit;
        }
        return SurfaceApproach.beside(sibling, parent, minParent, ContactGeometry.GRAB_HEIGHT, Double.POSITIVE_INFINITY,
                at -> SurfaceApproach.hasDryBankBuffer(sibling, at, sibling.getMaxFallDistance())
                        && ContactGeometry.inMouthReach(sibling, at, parent) && SurfaceApproach.roomAt(sibling, at, parent));
    }

    /** Picks a reachable dry stand off the {@code parent -> child} line so the driven child has a clear path to step up. */
    private static Vec3 asideStand(YukkuriEntity sibling, YukkuriEntity parent, YukkuriEntity child) {
        Vec3 p = parent.position(), c = child.position();
        Vec3 delta = c.subtract(p).multiply(1, 0, 1);
        Vec3 u = delta.lengthSqr() > 1e-4 ? delta.normalize() : Vec3.directionFromRotation(0, parent.getYRot()).multiply(1, 0, 1).normalize();
        Vec3 right = new Vec3(-u.z, 0, u.x);
        double sideSign = sibling.position().subtract(p).multiply(1, 0, 1).dot(right) >= 0 ? 1 : -1;
        double offset = (parent.getBbWidth() + sibling.getBbWidth()) * .5 + .6;
        for (double sign : new double[]{sideSign, -sideSign}) {
            Vec3 raw = p.add(right.scale(sign * offset)).add(u.scale(.25));
            var at = SurfaceApproach.floor(sibling, raw);
            if (at != null && SurfaceApproach.stands(sibling, at)
                    && SurfaceApproach.hasDryBankBuffer(sibling, at, sibling.getMaxFallDistance())
                    && SurfaceApproach.roomAt(sibling, at, parent) && SurfaceApproach.canApproach(sibling, at)) return at;
        }
        return null;
    }

    @Override public void stop() {
        boolean breathPause = phase == Phase.NONE && stepIn == StepIn.WALKING && entity.species().catchingBreath();
        stopStepInMotor(breathPause ? "catching_breath" : "driven_out_stop");
        if (!breathPause && phase == Phase.NONE && stepIn != StepIn.NONE && stepIn != StepIn.DONE) {
            GameplayLog.event(entity, "family", "driven out sibling stepin fallback child={} reason=interrupted phase={}", watchChild, stepIn);
            stepIn = StepIn.DONE;
        }
        SurfaceApproach.halt(entity);
        if (phase == Phase.GOING) phase = Phase.WAITING;
        else if (watchTarget() == null) clearWatch();
    }
}
