package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.presentation.FaceTier;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.mind.EmotionCourse;
import io.github.longshou.mcyukkuri.mind.Ousting.Answer;
import io.github.longshou.mcyukkuri.perception.Cue;
import io.github.longshou.mcyukkuri.perception.Senses;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.social.Departure;
import io.github.longshou.mcyukkuri.social.SeveranceReaction;
import io.github.longshou.mcyukkuri.social.SeveranceVerdict.Verdict;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.EnumSet;
import java.util.HashSet;
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
public final class DrivenOut extends Goal {
    public enum Phase { NONE, WAITING, GOING }

    /** Ticks an order stays owed (the child must first reach dry ground; the walk itself is limited by {@link Departure}). */
    private static final int WINDOW = 2400, BUDGET = 5000;
    /** A shore watch runs until the tie is cut (the child's own walk budget). */
    private static final int WATCH_CAP = BUDGET;
    /** The range it is driven out of (blocks round where she stood), the sight it counts as gone from, and the distance that is gone anyway. */
    private static final int RANGE = 7, GONE_NEAR = 8, GONE_FAR = 16, SIBLING_SIGHT = 12, SAY_PRIORITY = 4;
    /** Lines take turns: its plea or turn waits for a neighbour's line (the parent's verdict) to end, at most this long (ticks); within this range (blocks). */
    private static final int TURN_CAP = 120, TURN_RANGE = 6;

    private final YukkuriEntity entity;
    private final Departure departure;
    private final Set<UUID> witnessed = new HashSet<>();
    private Departure.BannedRange banned;
    private UUID driver, watchParent, watchChild;
    /** The stance face a watching sibling keeps through the severance, renewed while the watch lasts. */
    private String watchFace;
    private Verdict verdict;
    private String reason = "";
    private Phase phase = Phase.NONE;
    /** The tick the order lapses at: {@link #WINDOW} to reach dry ground, then {@link #BUDGET} for the whole departure. */
    private long deadline, watchUntil, watchOrderBy;
    private Vec3 centre;
    private boolean begun;

    public DrivenOut(YukkuriEntity entity) {
        this.entity = entity;
        departure = new Departure(entity, new Departure.Turn(entity, 2 * TURN_RANGE, TURN_CAP));
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
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

    private void clearWatch() {
        watchParent = watchChild = null;
        watchFace = null;
        watchUntil = watchOrderBy = 0;
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
        return new Departure.Terms("family", "driven out", centre, RANGE, parent(), driver, 1f, false, true, true, true, null, this::siblingsCry);
    }

    /** Perceiving ashore siblings react at once when the parent starts pronouncing severance and stay watching through the confrontation
     *  and the walk out until the tie is cut, so none wanders off or wears a plain or happy face during it. */
    public static void witnessVerdict(YukkuriEntity parent, YukkuriEntity child, int verdictTicks) {
        if (!(parent.level() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        for (var sibling : level.getEntitiesOfClass(YukkuriEntity.class, parent.getBoundingBox().inflate(24),
                y -> y != child && y != parent && y.isAlive() && !y.family().adult() && Bonds.parentOf(parent, y)
                        && !y.isInWaterOrBubble() && !y.body().dying() && !y.life().isSleeping())) {
            var via = Senses.perceives(sibling, parent.getEyePosition(), null, Cue.DEPARTURE_CRY);
            if (via == null) continue;
            var stance = SeveranceReaction.siblingStance(sibling.life().personality().temperament());
            sibling.getLookControl().setLookAt(parent, 30, 30);
            sibling.forceReaction(stance.verdictFace(), "", FaceTier.Tier.LONG);
            sibling.beat("sibling_verdict", stance.verdictFace(), "");
            watch(sibling, parent, child, stance.verdictFace(), now + verdictTicks + 20);
            GameplayLog.event(sibling, "family", "driven out sibling witnessed verdict child={} stance={} via={}",
                    child.getUUID(), stance, via);
        }
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

    @Override public void stop() {
        SurfaceApproach.halt(entity);
        if (phase == Phase.GOING) phase = Phase.WAITING;
        else if (watchTarget() == null) clearWatch();
    }
}
