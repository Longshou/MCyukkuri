package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.status.GameplayLog;

import io.github.longshou.mcyukkuri.mind.EmotionCourse;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.BehaviorContinuity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import io.github.longshou.mcyukkuri.settlement.SettlementSociety;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.social.PairContact;
import io.github.longshou.mcyukkuri.social.PairContact.Pose;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.UUID;
import java.util.Locale;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.event.WildEvents;

/**
 * A grown child leaves its parents (project design). When it is old and fit enough, and the day suits it, it walks up to a
 * parent, the two say goodbye (docs/FEATURES.md 离巢告别), and the child then simply becomes a vagrant looking for a new nest
 * of its own: its bed in the parents' home is released, its kinship and affinity are kept. Finding or digging the new home
 * belongs to {@code HabitatLife}; nothing here builds.
 * <p>The goodbye, by their values: the child says it is going (teary when attached); the parent answers, glad and blessing
 * when cool, sobbing "come back sometimes" when attached; an attached pair nuzzle for a while (longer the more attached), a
 * cool child only bows; it says "I'm off" and walks away, looking back once or twice when attached; the parent watches it go,
 * and an attached parent is left grieving a little (mind/EmotionCourse). The parent's part is its own goal ({@link Task#SEE_OFF})
 * that it takes up only when free; the child's farewell never waits for it, so neither can be stuck by the other: a child
 * that cannot get close tries to say goodbye from a stand in sight of the parent.
 */
public final class IndependenceLife extends Goal {
    private enum Task { NONE, FAREWELL, DEPART, SEE_OFF }
    private static final int CHECK_INTERVAL = 1200, FAREWELL_LIMIT = 600, CALL_RANGE = 4, PARENT_RANGE = 24;
    private static final int PARENT_CHECK_INTERVAL = 200, PARENT_WAIT_TICKS = 8 * PARENT_CHECK_INTERVAL;
    /** Beats of the exchange, in ticks from the first word: the parent's answer, then the nuzzle (or the bow). */
    private static final int ANSWER = 40, CLOSE = 80, BOW_TICKS = 24, GO_LINE = 30;
    private static final int DEPART_LIMIT = 500, DEPART_DISTANCE = 10, SEE_OFF_LIMIT = 1800;
    private final YukkuriEntity entity;
    /** The parents' home that was left; the leaver never moves back into it. */
    private GlobalPos origin;
    private boolean independent, armed, household;
    private UUID visitor;
    private Task task = Task.NONE;
    private int wait = CHECK_INTERVAL, elapsed, contact, loadGrace, visits;
    private int parentWait = -1;
    private boolean running;
    private Vec3 target;
    private int stalled;
    private Vec3 progress;
    private final FarewellStands<Vec3> stands = new FarewellStands<>((a, b) -> a.distanceToSqr(b) < .0625);
    // The exchange as decided at its first word, the walk away and its looks back. Transient.
    private boolean touching, teary;
    private int closeTicks, lookBacks, lookBackTicks, departTicks;
    private PairContact pair;
    private Vec3 away;
    // The parent's side: the child it is seeing off and until when the request stands.
    private UUID seeing;
    private long seeUntil;
    private boolean sobbing;

    public IndependenceLife(YukkuriEntity entity) { this.entity = entity; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
    private ServerLevel level() { return (ServerLevel)entity.level(); }
    public boolean active() { return running && task != Task.NONE; }
    /** Kept for callers of the old construction project; leaving no longer involves building. */
    public boolean building() { return false; }
    /** The child has left its parents. It stays true for life, whether it already has a new home or is still looking for one. */
    public boolean independent() { return independent; }
    public ItemStack carrying() { return ItemStack.EMPTY; }
    public int work() { return 0; }
    public int visits() { return visits; }
    public int rainLosses() { return 0; }
    public GlobalPos site() { return null; }
    public GlobalPos origin() { return origin; }
    public String activity() {
        return switch (task) {
            case FAREWELL -> !said || contact >= ANSWER && contact < CLOSE ? Behaviors.INDEPENDENCE_SEE_OFF
                    : contact < ANSWER ? Behaviors.INDEPENDENCE_FAREWELL
                    : contact < closeEnd() ? nuzzling() ? Behaviors.INDEPENDENCE_SEE_OFF : Behaviors.INDEPENDENCE_BOW
                    : Behaviors.INDEPENDENCE_FAREWELL;
            case DEPART -> lookBackTicks > 0 ? Behaviors.INDEPENDENCE_LOOK_BACK : Behaviors.INDEPENDENCE_SEE_OFF;
            case SEE_OFF -> sobbing ? Behaviors.INDEPENDENCE_SOB : seenBeat() >= ANSWER && seenBeat() < CLOSE
                    ? entity.life().personality().temperament().is(Gate.ATTACHED) ? Behaviors.INDEPENDENCE_SOB : Behaviors.INDEPENDENCE_ANSWER
                    : Behaviors.INDEPENDENCE_SEE_OFF;
            case NONE -> Behaviors.IDLE;
        };
    }
    private boolean said;
    private int closeEnd() { return CLOSE + closeTicks; }
    private boolean nuzzling() { return touching && closeTicks > BOW_TICKS; }
    /** The child's beat as its parent reads it: -1 while it walks up, then ticks since its first word; MAX once it walks away. */
    private int beat() { return task == Task.FAREWELL ? said ? contact : -1 : task == Task.DEPART ? Integer.MAX_VALUE : -2; }
    private YukkuriEntity seen() { return seeing != null && level().getEntity(seeing) instanceof YukkuriEntity child ? child : null; }
    private int seenBeat() { var child = seen(); return child == null ? -2 : child.independence().beat(); }
    /** The old household chores no longer yield to a building project. */
    public boolean householdOuting() { return false; }
    /** True for the home this individual left: it does not move back into it. */
    public boolean avoids(BlockPos core) { return independent && origin != null && origin.dimension().equals(level().dimension()) && origin.pos().equals(core); }
    /** Scenario hook: the next daytime opportunity is taken, with no dice. */
    public void arm() { armed = true; wait = 0; }

    private boolean wild() {
        return !entity.profile().dangerToYukkuri() && entity.status().identity().free();
    }
    /** Unknown/unloaded living children are conservatively treated as dependent. */
    private boolean hasDependents() {
        var data = FamilyData.get(level());
        if (data.busyParent(entity.getUUID())) return true;
        for (var id : data.children(entity.getUUID())) {
            var person = data.person(id);
            if (person == null || person.status == FamilyData.Status.DEAD || person.status == FamilyData.Status.REMOVED) continue;
            if (!(level().getEntity(id) instanceof YukkuriEntity child) || !child.family().adult()) return true;
        }
        return false;
    }
    /** Why the individual is not leaving yet ("ok" when it would); for the notebook and the playground. */
    public String why() {
        if (independent) return "left";
        if (!wild()) return "not_wild";
        if (!entity.childhood().canLiveIndependently()) return "not_capable";
        if (entity.family().elder()) return "elder";
        if (entity.family().partner() != null) return "partnered";
        if (entity.family().recovery() > 0) return "recovering";
        if (hasDependents()) return "dependents";
        var home = entity.habitat().shelter();
        if (home == null) return "no_home";
        if (!home.usable(entity)) return "bed_unusable";
        if (!(home.owns(entity.family().mother()) || home.owns(entity.family().father()))) return "parents_elsewhere";
        var group = entity.settlement().group(); var role = entity.settlement().society().record();
        if (group != null && (role == null || role.role != SettlementData.Role.MEMBER || role.rejections != 0)) return "disgrace";
        var block = block();
        if (block != null) return "not_ready:" + block;
        if (!member()) return "not_member";
        return "ok";
    }
    /** A capable late juvenile (or an adult still at home) that has a usable bed in a home its parent owns. */
    public boolean eligible() {
        if (independent || !wild() || !entity.childhood().canLiveIndependently() || entity.family().elder() || entity.family().partner() != null
                || entity.family().recovery() > 0 || hasDependents()) return false;
        var home = entity.habitat().shelter();
        var group = entity.settlement().group();
        var role = entity.settlement().society().record();
        // Do not escape lost-accessory consequences by leaving: a community member in disgrace stays where it is.
        if (group != null && (role == null || role.role != SettlementData.Role.MEMBER || role.rejections != 0)) return false;
        return home != null && home.usable(entity) && (home.owns(entity.family().mother()) || home.owns(entity.family().father()));
    }
    /** A family without a community record is fine; a community member must be in good standing. */
    private boolean member() { return entity.settlement().group() == null || SettlementSociety.accepted(entity); }
    private boolean ready() { return block() == null; }
    /** The first thing that keeps the individual from walking off right now, or null. */
    private String block() {
        if (!wild()) return "wild";
        if (!entity.isAlive() || entity.isNoAi()) return "alive";
        if (entity.isPassenger() || entity.isLeashed()) return "carried";
        if (entity.isPanicking() || entity.isCommandPlaying() || entity.operations().active()) return "busy";
        if (entity.family().active() || entity.family().recovery() != 0) return "family";
        if (entity.life().hasUrgentNeed()) return "urgent_need";
        if (entity.life().isSleeping()) return "sleeping";
        if (entity.life().mealTicks() != 0) return "eating";
        if (entity.life().nutrition() < 45) return "hungry";
        if (entity.life().energy() < 40) return "tired";
        if (entity.life().dryness() >= 40) return "thirsty";
        if (entity.isInWaterOrBubble() || entity.isOnFire() || entity.shellRetraction().active()) return "hazard";
        if (WildEvents.reserved(entity)) return "wild_event";
        if (!entity.habitat().carrying().isEmpty() || !entity.settlement().duties().carrying().isEmpty()) return "carrying";
        return null;
    }
    /** Loaded-time clock only; a paused or captured individual never leaves offline. */
    public void clock() {
        if (entity.level().isClientSide || entity.isNoAi()) return;
        if (seeing != null && task != Task.SEE_OFF && !attached()) {
            var child = seen();
            if (child == null || !child.isAlive() || !coolWindow(child).open(entity.distanceToSqr(child))) seeing = null;
        }
        if (parentWait > 0) parentWait--;
        if (loadGrace > 0) { loadGrace--; return; }
        if (wait > 0) wait--;
    }
    public int departureChance() {
        var home = entity.habitat().shelter();
        if (home == null) return 0;
        // Original balancing, not a density inferred from EH11's panels: a crowded home pushes its grown young out sooner.
        int chance = home.crowded() ? 30 : 10;
        if (entity.life().personality().temperament().is(Gate.CLINGY)) chance -= 5;
        return chance;
    }
    @Override public boolean canUse() {
        if (independent || loadGrace > 0 || wait > 0 || !ready() || !member()) return false;
        wait = armed ? 100 : CHECK_INTERVAL;
        if (!eligible()) return false;
        // Departures are by day. A night-time roll is not spent: look again soon, not a full interval later.
        if (level().isNight()) { wait = 200; return false; }
        if (!armed && parentWait < 0) {
            int roll = entity.getRandom().nextInt(100);
            int chance = departureChance();
            GameplayLog.event(entity, "independence", "departure decision chance={} roll={} accepted={}", chance, roll, roll < chance);
            if (roll >= chance) return false;
        }
        var parent = parentNearby();
        if (parent == null) {
            // The parent has time to come home even while another goal owns this child's movement.
            if (parentWait < 0) {
                parentWait = PARENT_WAIT_TICKS;
                GameplayLog.limited(entity, "independence", "parent.wait", PARENT_WAIT_TICKS,
                        "farewell waiting reason=no_available_parent ticks={}", parentWait);
            }
            if (parentWait > 0) { wait = PARENT_CHECK_INTERVAL; return false; }
            leave();
            return false;
        }
        parentWait = -1;
        visitor = parent.getUUID(); task = Task.FAREWELL; return true;
    }
    @Override public void start() {
        running = true; elapsed = contact = departTicks = lookBackTicks = 0; target = progress = null; stalled = 0; said = false; sobbing = false;
        if (task == Task.SEE_OFF) {
            GameplayLog.event(entity, "independence", "see off started child={}", seeing);
            return;
        }
        GameplayLog.event(entity, "independence", "farewell started parent={}", visitor);
        if (level().getEntity(visitor) instanceof YukkuriEntity parent) parent.independence().seeOff(entity);
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    /** Once on its way the farewell only gives way to real danger or need; picking up a bite of grass or a mild hunger do not call it off. */
    private boolean steady() {
        return wild() && entity.isAlive() && !entity.isNoAi() && !entity.isPassenger() && !entity.isLeashed() && !entity.isPanicking()
                && !entity.isCommandPlaying() && !entity.operations().active() && !entity.life().hasUrgentNeed() && !entity.life().isSleeping()
                && !entity.isInWaterOrBubble() && !entity.isOnFire() && !entity.shellRetraction().active();
    }
    @Override public boolean canContinueToUse() {
        if (!active() || !steady()) return false;
        return switch (task) {
            case FAREWELL -> !independent && (said || elapsed < FAREWELL_LIMIT + GO_LINE);
            case DEPART -> departTicks < DEPART_LIMIT;
            case SEE_OFF -> seeOffValid();
            case NONE -> false;
        };
    }
    @Override public void tick() {
        if (!canContinueToUse()) { stop(); return; }
        elapsed++;
        switch (task) {
            case FAREWELL -> farewell();
            case DEPART -> depart();
            case SEE_OFF -> seeOff();
            case NONE -> {}
        }
    }

    // ---- the child's goodbye -----------------------------------------------------------------------------------------------------
    private void farewell() {
        if (!(level().getEntity(visitor) instanceof YukkuriEntity parent) || !parent.isAlive() || parent.isPanicking() || parent.isCommandPlaying()) {
            stop(); return;
        }
        if (!said) {
            double reach = (entity.getBbWidth() + parent.getBbWidth()) * .5 + .15;
            double distance = entity.distanceToSqr(parent);
            boolean visible = FarewellWindow.inRange(distance) && parent.hasLineOfSight(entity);
            boolean close = visible && distance <= (reach + 1) * (reach + 1);
            boolean canWalk = !close && elapsed < FAREWELL_LIMIT;
            if (canWalk && target == null) {
                target = standNear(parent, reach, at -> Math.abs(at.y - parent.getY()) <= .35 && visibleFrom(parent, at));
                if (target == null)
                    target = standNear(parent, reach + .65, at -> visibleFrom(parent, at));
                if (target == null && !visible)
                    target = standNear(parent, CALL_RANGE - .5, at -> visibleFrom(parent, at));
            }
            switch (FarewellStands.exchange(close, canWalk && target != null, visible)) {
                case CLOSE -> begin(parent, true);
                case CALL -> {
                    SurfaceApproach.halt(entity);
                    if (!facePartner(parent)) return;
                    begin(parent, false);
                }
                case WALK -> { approach(parent); return; }
                case UNREACHABLE -> {
                    GameplayLog.limited(entity, "independence", "farewell.sight", CHECK_INTERVAL,
                            "farewell interrupted reason=no_visible_stand parent={}", visitor);
                    stop();
                    wait = CHECK_INTERVAL;
                    return;
                }
            }
        }
        double touch = (entity.getBbWidth() + parent.getBbWidth()) * .5 + .25;
        if (pair != null) {
            if (!pair.finished() && canNuzzle(parent, touch)) {
                SurfaceApproach.halt(entity);
                entity.getLookControl().setLookAt(parent, 25, 25);
                pair.publish(0);
                return;
            }
            releaseNuzzle();
        }
        if (contact == closeEnd() && entity.showReaction(teary ? "teary_smile" : "happy", "", GO_LINE)) entity.speakCue("farewell_go");
        // Close the last gap while the parent answers. The waiting window ends even if the parent stays busy.
        if (nuzzling() && contact >= CLOSE - 20 && contact < closeEnd() && entity.distanceToSqr(parent) > touch * touch) {
            // A moving parent can outwalk the old aim before the teen gets there.
            double reach = touch - SurfaceApproach.ARRIVAL;
            if (target != null && target.distanceToSqr(parent.position()) > reach * reach) {
                stands.retire(target);
                target = progress = null;
            }
            if (target == null) target = standNear(parent, touch - .10, at -> Math.abs(at.y - parent.getY()) <= .35
                    && at.distanceToSqr(parent.position()) <= reach * reach);
            if (target != null) approach(parent);
            else SurfaceApproach.halt(entity);
        } else SurfaceApproach.halt(entity);
        if (!touching) facePartner(parent);
        entity.getLookControl().setLookAt(parent, 25, 25);
        contact++;
        if (contact == 1) entity.showReaction(teary ? "teary_smile" : "happy", teary ? "farewell_say_teary" : "farewell_say", ANSWER);
        if (contact == ANSWER && parent.independence().attached() && !parent.independence().seeingOff(entity)) {
            // A busy attached parent can still react from where it is.
            if (!parent.life().isSleeping()) { parent.mind().felt(EmotionEvent.COMFORTED); parent.showReactionDelayed("happy", "gentle", 80, 12); }
        }
        if (contact >= CLOSE && contact <= closeEnd() && nuzzling()) {
            if (canNuzzle(parent, touch)) {
                pair = new PairContact(entity, parent, Pose.FAREWELL, closeTicks);
                pair.publish(0);
                entity.showReaction(teary ? "teary_smile" : "closed_smile", "", closeTicks);
                GameplayLog.event(entity, "independence", "farewell rub started parent={} ticks={} distance={}",
                        parent.getUUID(), closeTicks, entity.distanceTo(parent));
            } else if (contact == closeEnd()) {
                GameplayLog.event(entity, "independence", "farewell rub unavailable parent={} seeingOff={} distance={}",
                        parent.getUUID(), parent.independence().seeingOff(entity), entity.distanceTo(parent));
                contact = CLOSE;
                closeTicks = BOW_TICKS;
            }
        }
        if (contact >= closeEnd() + GO_LINE) {
            leave();
            beginDeparture(parent);
        }
    }

    private boolean canNuzzle(YukkuriEntity parent, double touch) {
        return parent.independence().seeingOff(entity) && parent.independence().parentFree()
                && entity.onGround() && parent.onGround() && entity.distanceToSqr(parent) <= touch * touch
                && entity.hasLineOfSight(parent);
    }

    private void releaseNuzzle() {
        int shown = pair.release();
        GameplayLog.event(entity, "independence", "farewell rub released parent={} ticks={} planned={}", visitor, shown, closeTicks);
        pair = null;
        contact = closeEnd();
    }

    /** Either participant can release a rub if its body is taken over. */
    public void interruptContact() {
        if (pair != null) releaseNuzzle();
        if (task == Task.SEE_OFF) {
            var child = seen();
            if (child != null && child.independence().pair != null) child.independence().releaseNuzzle();
        }
    }

    /** The exchange is decided at the first word, from both their values. */
    private void begin(YukkuriEntity parent, boolean close) {
        said = true; touching = close; contact = 0; stalled = 0; target = progress = null;
        stands.clear();
        var t = entity.life().personality().temperament();
        int own = t.attachment(), theirs = parent.life().personality().attachment();
        teary = t.is(Gate.ATTACHED);
        // An attached pair nuzzle the longer the more attached they are; a cool pair or one called from afar only bows.
        int bond = own + theirs;
        closeTicks = close && bond >= 100 ? 40 + (bond - 100) / 2 : BOW_TICKS;
        lookBacks = t.is(Gate.DEVOTED_KIN) ? 2 : t.is(Gate.DETACHED) ? 0 : 1;
        looked = 0;
        SurfaceApproach.halt(entity);
        GameplayLog.event(entity, "independence", "farewell exchange parent={} touching={} attachment={} parentAttachment={} nuzzleTicks={} lookBacks={} attachmentScore={}",
                parent.getUUID(), close, own, theirs, nuzzling() ? closeTicks : 0, lookBacks, String.format(Locale.ROOT, "%.2f", t.attached()));
    }

    /** It walks away from the parent, stopping to look back once or twice when attached; then the habitat takes over. */
    private void beginDeparture(YukkuriEntity parent) {
        task = Task.DEPART; departTicks = 0; lookBackTicks = 0; away = null;
        stands.clear();
        GameplayLog.event(entity, "independence", "departure walk parent={} lookBacks={}", parent.getUUID(), lookBacks);
    }

    private void depart() {
        var parent = level().getEntity(visitor) instanceof YukkuriEntity p && p.isAlive() ? p : null;
        double from = parent == null ? 0 : Math.sqrt(entity.distanceToSqr(parent));
        if (lookBackTicks > 0) {
            SurfaceApproach.halt(entity);
            if (parent != null) entity.getLookControl().setLookAt(parent, 30, 30);
            if (--lookBackTicks == 0) GameplayLog.event(entity, "independence", "looked back parent={} distance={} count={}/{}", visitor, Math.round(from), looked, lookBacks);
            return;
        }
        if (parent == null) { stop(); return; }
        if (away == null) {
            away = departureStand(parent);
            if (away == null) { stop(); return; }
            GameplayLog.event(entity, "independence", "farewell departure stand parent={} stand={}", visitor, away);
        }
        // A look back at about 4 and 7 blocks (or a single one at 5); the most attached call out at the last one.
        double mark = lookBacks >= 2 ? (looked == 0 ? 4 : 7) : 5;
        if (looked < lookBacks && from >= mark) {
            looked++;
            lookBackTicks = 40 + entity.life().personality().attachment() / 4;
            SurfaceApproach.halt(entity);
            SurfaceApproach.face(entity, SurfaceApproach.yaw(entity.position(), parent.position()));
            if (entity.life().personality().temperament().is(Gate.DEVOTED_KIN) && looked == lookBacks)
                entity.showReaction("teary_smile", "farewell_look_back", lookBackTicks);
            else entity.showReaction(teary ? "teary_smile" : "happy", "", lookBackTicks);
            return;
        }
        // The look-back beats have their own countdown and do not spend the walk's time allowance.
        departTicks++;
        var result = SurfaceApproach.walkResult(entity, away, parent, .8);
        if (result == SurfaceApproach.Result.BLOCKED) reject(away, "blocked");
        else if (result == SurfaceApproach.Result.ARRIVED) stop(true);
    }
    private int looked;

    // ---- the parent's side -------------------------------------------------------------------------------------------------------
    public Goal seeOffGoal() { return new SeeOffGoal(); }

    private final class SeeOffGoal extends Goal {
        SeeOffGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return task == Task.NONE && seeOffReady(); }
        @Override public void start() { task = Task.SEE_OFF; IndependenceLife.this.start(); }
        @Override public boolean canContinueToUse() { return task == Task.SEE_OFF && IndependenceLife.this.canContinueToUse(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { IndependenceLife.this.tick(); }
        @Override public void stop() { if (task == Task.SEE_OFF) IndependenceLife.this.stop(); }
        @Override public String toString() { return "SeeOffGoal"; }
    }

    /** A child of this parent has begun its goodbye to it; the parent sees it off when it is free to. */
    void seeOff(YukkuriEntity child) { seeing = child.getUUID(); seeUntil = level().getGameTime() + SEE_OFF_LIMIT; }
    boolean seeingOff(YukkuriEntity child) { return task == Task.SEE_OFF && running && child.getUUID().equals(seeing); }
    private boolean attached() { return entity.life().personality().temperament().is(Gate.ATTACHED); }
    private boolean parentFree() {
        return FarewellArbitration.canAnswer(steady() && !entity.life().protectsStep() && !BehaviorContinuity.emergency(entity), this::parentOnDuty);
    }
    private boolean parentOnDuty() {
        var family = entity.family();
        var settlement = entity.settlement();
        return entity.duty().claimed() || family.active() || family.claimHomeWatch() || family.stockedChildMealDue()
                || entity.forageTrip().outing() || settlement.underway() || settlement.duties().pit().penned()
                || settlement.duties().workPending() || WildEvents.reserved(entity);
    }
    private boolean seeOffReady() {
        if (seeing == null) return false;
        var child = seen();
        boolean attached = attached();
        if (child == null || !child.isAlive() || (attached
                ? level().getGameTime() > seeUntil || child.independence().beat() == -2
                : !coolWindow(child).open(entity.distanceToSqr(child)))) {
            seeing = null; return false;
        }
        return FarewellWindow.inRange(entity.distanceToSqr(child)) && (attached || coolCanAnswer(child)) && parentFree();
    }
    private boolean seeOffValid() {
        var child = seen();
        if (child == null || !child.isAlive() || !parentFree() || !FarewellWindow.inRange(entity.distanceToSqr(child))) return false;
        if (attached()) return level().getGameTime() <= seeUntil && child.independence().beat() != -2;
        return coolWindow(child) == FarewellWindow.EXCHANGE || contact == 0 && coolCanAnswer(child);
    }
    private FarewellWindow coolWindow(YukkuriEntity child) {
        var leaver = child.independence();
        if (!entity.getUUID().equals(leaver.visitor)) return FarewellWindow.CLOSED;
        return switch (leaver.task) {
            case FAREWELL -> FarewellWindow.EXCHANGE;
            case DEPART -> FarewellWindow.DEPARTURE;
            case NONE -> !leaver.household ? FarewellWindow.ONWARD : FarewellWindow.CLOSED;
            case SEE_OFF -> FarewellWindow.CLOSED;
        };
    }
    private boolean coolCanAnswer(YukkuriEntity child) {
        var window = coolWindow(child);
        boolean walkingAway = (child.getX() - child.xo) * (child.getX() - entity.getX())
                + (child.getZ() - child.zo) * (child.getZ() - entity.getZ()) > 0;
        return window.canAnswer(entity.hasLineOfSight(child), walkingAway);
    }
    private void seeOff() {
        var child = seen();
        var t = entity.life().personality().temperament();
        boolean attached = t.is(Gate.ATTACHED);
        boolean late = !attached && coolWindow(child) != FarewellWindow.EXCHANGE;
        int beat = child.independence().beat();
        SurfaceApproach.halt(entity);
        entity.getLookControl().setLookAt(child, 30, 30);
        if (late ? !facePartner(child) : beat < ANSWER) return;
        if (contact == 0) {
            // Its answer, once, by its own attachment: a glad blessing, or a sob and "come back sometimes".
            if (!attached && (!entity.hasLineOfSight(child) || !entity.showReaction("happy", "farewell_bless", 60))) return;
            contact = 1;
            entity.mind().felt(EmotionEvent.COMFORTED);
            if (attached) entity.showReaction("teary", "farewell_cry", 60);
            GameplayLog.event(entity, "independence", "see off answer child={} attached={} attachment={} attachmentScore={} late={} distance={}",
                    seeing, attached, t.attachment(), String.format(Locale.ROOT, "%.2f", t.attached()), late, entity.distanceTo(child));
        }
        // While the child walks away the attached one sobs where it stands.
        sobbing = attached && beat == Integer.MAX_VALUE;
        if (beat == Integer.MAX_VALUE && contact == 1 && attached) { contact = 2; entity.showReaction("cry", "", 80); }
    }

    private void seeOffEnded() {
        var child = seen();
        boolean gone = child != null && child.independence().independent();
        var t = entity.life().personality().temperament();
        int attachment = t.attachment();
        GameplayLog.event(entity, "independence", "see off ended child={} departed={} attachment={} attachmentScore={}", seeing, gone, attachment,
                String.format(Locale.ROOT, "%.2f", t.attached()));
        // An attached parent watches the road for a while; a cool one goes back to its day.
        if (gone && t.is(Gate.ATTACHED))
            entity.mind().feeling().begin(EmotionCourse.Kind.PARTING, .15f + .25f * (attachment - 55) / 45f, seeing, child.blockPosition());
        if (t.is(Gate.ATTACHED) || contact > 0) seeing = null;
    }
    private YukkuriEntity parentNearby() {
        var home = entity.habitat().shelter();
        if (home == null) return null;
        return level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(PARENT_RANGE), other -> other != entity && other.isAlive()
                        && (Bonds.parentOf(other, entity))
                        && !other.isPanicking() && !other.isCommandPlaying() && !other.operations().active() && !other.family().active()
                        && !other.life().isSleeping() && !other.life().hasUrgentNeed() && home.owns(other.getUUID())
                        && entity.life().canRecognize(other) && other.life().canRecognize(entity))
                .stream().filter(other -> other.hasLineOfSight(entity) || entity.life().reachable(other.blockPosition(), false))
                .min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
    }
    /** The child lets go of its place in the parents' home and becomes a vagrant. Kinship and affinity stay. */
    private void leave() {
        var home = entity.habitat().shelter();
        if (home != null) origin = GlobalPos.of(level().dimension(), home.getBlockPos());
        independent = true; armed = false; household = false; visits++;
        // Keep a little extra fondness for the parents they just said goodbye to.
        var relations = YukkuriWorldData.get(level());
        for (var parent : new UUID[]{entity.family().mother(), entity.family().father()}) {
            if (parent == null) continue;
            relations.relate(entity.getUUID(), parent, 2); relations.relate(parent, entity.getUUID(), 2);
        }
        entity.habitat().release();
        entity.life().releaseHome();
        entity.setPersistenceRequired();
        GameplayLog.event(entity, "independence", "departed origin={} parent={} contact={} elapsed={}",
                origin, visitor, contact, elapsed);
        parentWait = -1;
    }

    /** Called by the habitat when the leaver has taken a home of its own: a loose family group gets a household of its own. */
    public void settled(ShelterBlockEntity home) {
        if (!independent || household || home == null) return;
        var group = entity.settlement().group();
        household = true;
        GameplayLog.event(entity, "independence", "household recorded home={} group={}", home.getBlockPos(), group == null ? null : group.id);
        if (group != null && group.kind != SettlementData.Kind.FAMILY) return;   // a village keeps its member in the village
        if (home.owns(entity.getUUID()) && home.usable(entity) && (group == null || !group.center.equals(home.getBlockPos())))
            entity.settlement().establishHousehold(home);
    }
    private Vec3 departureStand(YukkuriEntity parent) {
        Vec3 stand = standNear(parent, DEPART_DISTANCE, at -> true);
        // A shorter route can still pass the second look-back mark at seven blocks.
        if (stand == null && lookBacks == 2) stand = standNear(parent, 8, at -> true);
        return stand == null ? standNear(parent, DEPART_DISTANCE / 2.0, at -> true) : stand;
    }

    private Vec3 standNear(YukkuriEntity parent, double distance, Predicate<Vec3> suitable) {
        var candidates = SurfaceApproach.contactStands(entity, parent.position(), distance, parent, suitable);
        return stands.next(candidates, at -> Double.isFinite(SurfaceApproach.routeLength(entity, at, parent.getBoundingBox())));
    }

    private boolean visibleFrom(YukkuriEntity parent, Vec3 stand) {
        return level().clip(new ClipContext(parent.getEyePosition(), stand.add(0, entity.getEyeHeight(), 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, parent)).getType() == HitResult.Type.MISS;
    }

    private boolean facePartner(YukkuriEntity partner) {
        float yaw = SurfaceApproach.yaw(entity.position(), partner.position());
        SurfaceApproach.face(entity, yaw);
        return Math.abs(Mth.wrapDegrees(yaw - entity.getYRot())) <= Face.FACE_ARRIVED;
    }

    private void approach(YukkuriEntity parent) {
        if (progress == null || entity.position().distanceToSqr(progress) > .0036) {
            progress = entity.position(); stalled = 0;
        } else stalled++;
        if (stalled >= 60) { reject(target, "stalled"); return; }
        var result = SurfaceApproach.walkToContact(entity, target, parent, .8);
        if (result == SurfaceApproach.Result.BLOCKED) reject(target, "blocked");
        else if (result == SurfaceApproach.Result.ARRIVED) {
            stands.refuse(target);
            target = progress = null;
        }
    }

    private void reject(Vec3 stand, String reason) {
        stands.refuse(stand);
        GameplayLog.event(entity, "independence", "farewell stand refused phase={} stand={} reason={}", task, stand, reason);
        target = progress = away = null;
        SurfaceApproach.halt(entity);
    }
    @Override public void stop() { stop(false); }
    private void stop(boolean reachedStand) {
        // The selector also stops a farewell that already ended itself during tick().
        if (task == Task.NONE) return;
        interruptContact();
        if (task == Task.SEE_OFF) seeOffEnded();
        // A farewell that was already heard by the parent is not taken back by an interruption: the leaving goes ahead.
        if (task == Task.FAREWELL && said && contact >= ANSWER && !independent) leave();
        if (task == Task.FAREWELL || task == Task.DEPART) GameplayLog.limited(entity, "independence", "farewell.stop", 200,
                "farewell stopped parent={} departed={} elapsed={} contact={} stalled={} walked={}", visitor, independent, elapsed, contact, stalled, departTicks);
        // Keep the farewell partner through the ordinary walk after a successful departure handoff.
        if (!reachedStand) visitor = null;
        task = Task.NONE; running = false; elapsed = contact = departTicks = lookBackTicks = 0; wait = 40; target = progress = null;
        stalled = 0; said = false; sobbing = false; away = null;
        stands.clear();
        SurfaceApproach.halt(entity);
    }
    public void dropCarrying() {}
    /** Nothing is held across a departure any more; kept for the entity's death and reload hooks. */
    public void abandon() { stop(); wait = CHECK_INTERVAL; }
    public Component observation() {
        String status = independent ? household ? "independent" : "vagrant" : task == Task.FAREWELL ? "farewell" : eligible() ? "eligible" : "family";
        var shelter = entity.habitat().shelter();
        return Component.translatable("notebook.mcyukkuri.independence", Component.translatable("independence.mcyukkuri." + status),
                origin == null ? "—" : origin.pos().toShortString(),
                shelter == null ? "—" : shelter.getBlockPos().toShortString(), entity.habitat().digProgress());
    }
    public CompoundTag save() {
        var tag = new CompoundTag(); FamilyData.putPosition(tag, "Origin", origin);
        tag.putString("Why", why()); tag.putBoolean("Force", armed); tag.putBoolean("Independent", independent); tag.putBoolean("Household",
                household); tag.putInt("Wait", wait); tag.putInt("Visits", visits);
        if (parentWait >= 0) tag.putInt("ParentWait", parentWait);
        return tag;
    }
    public void load(CompoundTag tag) {
        interruptContact();
        origin = FamilyData.position(tag, "Origin");
        // Saves of the old construction project that had finished a new home count as left; unfinished projects are dropped.
        independent = tag.getBoolean("Independent") || tag.contains("EstablishedHome");
        household = tag.getBoolean("Household") || tag.contains("EstablishedHome");
        armed = tag.getBoolean("Force");
        wait = tag.contains("Wait") ? Math.clamp(tag.getInt("Wait"), 0, CHECK_INTERVAL) : CHECK_INTERVAL;
        if (armed) wait = 0;
        visits = Math.max(0, tag.getInt("Visits"));
        parentWait = tag.contains("ParentWait") ? tag.getInt("ParentWait") : -1;
        task = Task.NONE; running = false; visitor = null; elapsed = contact = 0; loadGrace = 100;
        target = progress = null; stalled = 0; stands.clear();
    }
}
