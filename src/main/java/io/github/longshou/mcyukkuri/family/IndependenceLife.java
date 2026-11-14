package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.status.GameplayLog;

import io.github.longshou.mcyukkuri.mind.EmotionCourse;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import io.github.longshou.mcyukkuri.settlement.SettlementSociety;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.social.Temperament;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.item.ItemStack;
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
 * that cannot reach a parent within {@link #FAREWELL_LIMIT} ticks says goodbye from a few blocks off, or leaves anyway.
 */
public final class IndependenceLife extends Goal {
    private enum Task { NONE, FAREWELL, DEPART, SEE_OFF }
    private static final int CHECK_INTERVAL = 1200, FAREWELL_LIMIT = 600, CALL_RANGE = 4, PARENT_RANGE = 24, WAITS_FOR_PARENT = 8;
    /** Beats of the exchange, in ticks from the first word: the parent's answer, then the nuzzle (or the bow). */
    private static final int ANSWER = 40, CLOSE = 80, BOW_TICKS = 24, GO_LINE = 30;
    private static final int DEPART_LIMIT = 500, DEPART_DISTANCE = 10, SEE_OFF_LIMIT = 1800;
    private final YukkuriEntity entity;
    /** The parents' home that was left; the leaver never moves back into it. */
    private GlobalPos origin;
    private boolean independent, armed, household;
    private UUID visitor;
    private Task task = Task.NONE;
    private int wait = CHECK_INTERVAL, elapsed, contact, loadGrace, parentWaits, visits;
    private boolean running;
    private Vec3 target;
    private int stalled;
    private Vec3 progress;
    // The exchange as decided at its first word, the walk away and its looks back. Transient.
    private boolean touching, teary;
    private int closeTicks, lookBacks, lookBackTicks, departTicks;
    private BlockPos away;
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
                    : contact < closeEnd() ? nuzzling() ? Behaviors.INDEPENDENCE_NUZZLE : Behaviors.INDEPENDENCE_BOW
                    : Behaviors.INDEPENDENCE_FAREWELL;
            case DEPART -> lookBackTicks > 0 ? Behaviors.INDEPENDENCE_LOOK_BACK : Behaviors.INDEPENDENCE_SEE_OFF;
            case SEE_OFF -> sobbing ? Behaviors.INDEPENDENCE_SOB : seenBeat() >= ANSWER && seenBeat() < CLOSE
                    ? entity.life().personality().attachment() >= 55 ? Behaviors.INDEPENDENCE_SOB : Behaviors.INDEPENDENCE_ANSWER
                    : seenBeat() >= CLOSE && seenBeat() < seenCloseEnd() && seenNuzzle() ? Behaviors.INDEPENDENCE_NUZZLE
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
    private int seenCloseEnd() { var child = seen(); return child == null ? 0 : child.independence().closeEnd(); }
    private boolean seenNuzzle() { var child = seen(); return child != null && child.independence().nuzzling(); }
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
        if (loadGrace > 0) { loadGrace--; return; }
        if (wait > 0) wait--;
    }
    public int departureChance() {
        var home = entity.habitat().shelter();
        if (home == null) return 0;
        // Original balancing, not a density inferred from EH11's panels: a crowded home pushes its grown young out sooner.
        int chance = home.crowded() ? 30 : 10;
        if (entity.life().personality().attachment() >= Temperament.ATTACHMENT_AT_65) chance -= 5;
        return chance;
    }
    @Override public boolean canUse() {
        // The parent's side comes first: a child it recognises is saying goodbye to it.
        if (seeOffReady()) { task = Task.SEE_OFF; return true; }
        if (independent || loadGrace > 0 || wait > 0 || !ready() || !member()) return false;
        wait = armed ? 100 : CHECK_INTERVAL;
        if (!eligible()) return false;
        // Departures are by day. A night-time roll is not spent: look again soon, not a full interval later.
        if (level().isNight()) { wait = 200; return false; }
        if (!armed) {
            int roll = entity.getRandom().nextInt(100);
            int chance = departureChance();
            GameplayLog.event(entity, "independence", "departure decision chance={} roll={} accepted={}", chance, roll, roll < chance);
            if (roll >= chance) return false;
        }
        var parent = parentNearby();
        if (parent == null) {
            // Wants to say goodbye: waits a while for a parent to come home, then goes without.
            if (++parentWaits <= WAITS_FOR_PARENT) {
                GameplayLog.limited(entity, "independence", "parent.wait", 1200,
                        "farewell waiting reason=no_available_parent waits={}", parentWaits);
                wait = 200; return false;
            }
            leave();
            return false;
        }
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
            case FAREWELL -> !independent && elapsed < FAREWELL_LIMIT + closeEnd() + GO_LINE + 20;
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
            leave(); stop(); return;
        }
        if (!said) {
            // Walks right up to the parent so they can touch; a parent it cannot reach is called from a few blocks off in sight.
            double reach = (entity.getBbWidth() + parent.getBbWidth()) * .5 + .15;
            boolean close = entity.distanceToSqr(parent) <= (reach + .6) * (reach + .6) && entity.hasLineOfSight(parent);
            boolean callable = entity.distanceToSqr(parent) <= CALL_RANGE * CALL_RANGE && entity.hasLineOfSight(parent);
            var stand = close ? null : contactStand(parent);
            if (!close && !(callable && (stand == null || stalled >= 60 || elapsed > FAREWELL_LIMIT / 2))) {
                if (elapsed > FAREWELL_LIMIT || stand == null && !callable) { leave(); stop(); return; }
                if (stand != null) approach(stand, parent); else SurfaceApproach.halt(entity);
                return;
            }
            begin(parent, close);
        }
        SurfaceApproach.halt(entity);
        entity.getLookControl().setLookAt(parent, 25, 25);
        contact++;
        if (contact == 1) entity.showReaction(teary ? "teary_smile" : "happy", teary ? "farewell_say_teary" : "farewell_say", ANSWER);
        if (contact == ANSWER && !parent.independence().seeingOff(entity)) {
            // A parent busy elsewhere still hears it: the old glad answer from where it is.
            if (!parent.life().isSleeping()) { parent.mind().felt(EmotionEvent.COMFORTED); parent.showReactionDelayed("happy", "gentle", 80, 12); }
        }
        if (contact == CLOSE && nuzzling()) entity.showReaction(teary ? "teary_smile" : "closed_smile", "", closeTicks);
        if (contact == closeEnd()) entity.showReaction(teary ? "teary_smile" : "happy", "farewell_go", GO_LINE);
        if (contact >= closeEnd() + GO_LINE) {
            leave();
            beginDeparture(parent);
        }
    }

    /** The exchange is decided at the first word, from both their values. */
    private void begin(YukkuriEntity parent, boolean close) {
        said = true; touching = close; contact = 0;
        int own = entity.life().personality().attachment(), theirs = parent.life().personality().attachment();
        teary = own >= 55;
        // An attached pair nuzzle the longer the more attached they are; a cool pair or one called from afar only bows.
        int bond = own + theirs;
        closeTicks = close && bond >= 100 ? 40 + (bond - 100) / 2 : BOW_TICKS;
        lookBacks = own >= 70 ? 2 : own >= 40 ? 1 : 0;
        looked = 0;
        SurfaceApproach.halt(entity);
        GameplayLog.event(entity, "independence", "farewell exchange parent={} touching={} attachment={} parentAttachment={} nuzzleTicks={} lookBacks={}",
                parent.getUUID(), close, own, theirs, nuzzling() ? closeTicks : 0, lookBacks);
    }

    /** It walks away from the parent, stopping to look back once or twice when attached; then the habitat takes over. */
    private void beginDeparture(YukkuriEntity parent) {
        task = Task.DEPART; departTicks = 0; lookBackTicks = 0;
        var aim = DefaultRandomPos.getPosAway(entity, DEPART_DISTANCE + 2, 3, parent.position());
        away = aim == null ? null : BlockPos.containing(aim);
        GameplayLog.event(entity, "independence", "departure walk parent={} away={} lookBacks={}", parent.getUUID(), away, lookBacks);
    }

    private void depart() {
        departTicks++;
        var parent = level().getEntity(visitor) instanceof YukkuriEntity p && p.isAlive() ? p : null;
        double from = parent == null ? 0 : Math.sqrt(entity.distanceToSqr(parent));
        if (lookBackTicks > 0) {
            SurfaceApproach.halt(entity);
            if (parent != null) entity.getLookControl().setLookAt(parent, 30, 30);
            if (--lookBackTicks == 0) GameplayLog.event(entity, "independence", "looked back parent={} distance={} count={}/{}", visitor, Math.round(from), looked, lookBacks);
            return;
        }
        if (parent == null || from >= DEPART_DISTANCE || away == null) { stop(); return; }
        // A look back at about 4 and 7 blocks (or a single one at 5); the most attached call out at the last one.
        double mark = lookBacks >= 2 ? (looked == 0 ? 4 : 7) : 5;
        if (looked < lookBacks && from >= mark) {
            looked++;
            lookBackTicks = 40 + entity.life().personality().attachment() / 4;
            SurfaceApproach.halt(entity);
            SurfaceApproach.face(entity, SurfaceApproach.yaw(entity.position(), parent.position()));
            if (entity.life().personality().attachment() >= 70 && looked == lookBacks)
                entity.showReaction("teary_smile", "farewell_look_back", lookBackTicks);
            else entity.showReaction(teary ? "teary_smile" : "happy", "", lookBackTicks);
            return;
        }
        if (departTicks % 20 == 1 && !entity.life().navigate(away, .8, false)) { stop(); }
    }
    private int looked;

    // ---- the parent's side -------------------------------------------------------------------------------------------------------
    /** A child of this parent has begun its goodbye to it; the parent sees it off when it is free to. */
    void seeOff(YukkuriEntity child) { seeing = child.getUUID(); seeUntil = level().getGameTime() + SEE_OFF_LIMIT; }
    boolean seeingOff(YukkuriEntity child) { return task == Task.SEE_OFF && running && child.getUUID().equals(seeing); }
    private boolean parentFree() {
        return entity.isAlive() && !entity.isNoAi() && !entity.isPassenger() && !entity.isPanicking() && !entity.isCommandPlaying()
                && !entity.operations().active() && !entity.life().hasUrgentNeed() && !entity.life().isSleeping()
                && !entity.isInWaterOrBubble() && !entity.isOnFire() && entity.life().mealTicks() == 0 && !entity.family().laboring();
    }
    private boolean seeOffReady() {
        if (seeing == null) return false;
        var child = seen();
        if (level().getGameTime() > seeUntil || child == null || !child.isAlive() || child.independence().beat() == -2) {
            seeing = null; return false;
        }
        return parentFree() && entity.distanceToSqr(child) <= PARENT_RANGE * PARENT_RANGE;
    }
    private boolean seeOffValid() {
        var child = seen();
        return child != null && child.isAlive() && parentFree() && level().getGameTime() <= seeUntil && child.independence().beat() != -2
                && entity.distanceToSqr(child) <= 16 * 16;
    }
    private void seeOff() {
        var child = seen();
        int beat = child.independence().beat();
        SurfaceApproach.halt(entity);
        entity.getLookControl().setLookAt(child, 30, 30);
        if (beat < ANSWER) return;
        boolean attached = entity.life().personality().attachment() >= 55;
        if (contact == 0) {
            // Its answer, once, by its own attachment: a glad blessing, or a sob and "come back sometimes".
            contact = 1;
            entity.mind().felt(EmotionEvent.COMFORTED);
            entity.showReaction(attached ? "teary" : "happy", attached ? "farewell_cry" : "farewell_bless", 60);
            GameplayLog.event(entity, "independence", "see off answer child={} attached={} attachment={}", seeing, attached,
                    entity.life().personality().attachment());
        }
        // While the child walks away the attached one sobs where it stands; the cool one just watches it go.
        sobbing = attached && beat == Integer.MAX_VALUE;
        if (beat == Integer.MAX_VALUE && contact == 1 && attached) { contact = 2; entity.showReaction("cry", "", 80); }
    }

    private void seeOffEnded() {
        var child = seen();
        boolean gone = child != null && child.independence().independent();
        int attachment = entity.life().personality().attachment();
        GameplayLog.event(entity, "independence", "see off ended child={} departed={} attachment={}", seeing, gone, attachment);
        // An attached parent is left missing it for a while (a light grief course); a cool one goes back to its day.
        if (gone && attachment >= 55)
            entity.mind().feeling().begin(EmotionCourse.Kind.GRIEF, .15f + .25f * (attachment - 55) / 45f, seeing, child.blockPosition());
        seeing = null;
    }
    private YukkuriEntity parentNearby() {
        var home = entity.habitat().shelter();
        if (home == null) return null;
        return level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(PARENT_RANGE), other -> other != entity && other.isAlive()
                        && (Bonds.parentOf(other, entity))
                        && !other.isPanicking() && !other.isCommandPlaying() && !other.operations().active() && !other.family().active()
                        && !other.life().isSleeping() && !other.life().hasUrgentNeed() && home.owns(other.getUUID())
                        && entity.life().canRecognize(other) && other.life().canRecognize(entity))
                .stream().filter(other -> entity.life().reachable(other.blockPosition(), false))
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
        GameplayLog.event(entity, "independence", "departed origin={} parent={} contact={} elapsed={} parentWaits={}",
                origin, visitor, contact, elapsed, parentWaits);
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
    private Vec3 contactStand(YukkuriEntity other) {
        double reach = (entity.getBbWidth() + other.getBbWidth()) * .5 + .15;
        return SurfaceApproach.beside(entity,other,reach,.35);
    }
    private void approach(Vec3 destination,YukkuriEntity parent) {
        if (target == null || target.distanceToSqr(destination) > .0025) { target = destination; progress = entity.position(); stalled = 0; }
        if (progress == null || entity.position().distanceToSqr(progress) > .0036) { progress = entity.position(); stalled = 0; } else stalled++;
        if (stalled >= 120) { elapsed = FAREWELL_LIMIT; return; }
        SurfaceApproach.walkAround(entity,destination,parent,.8);
    }
    @Override public void stop() {
        if (task == Task.SEE_OFF) seeOffEnded();
        // A farewell that was already heard by the parent is not taken back by an interruption: the leaving goes ahead.
        if (task == Task.FAREWELL && said && contact >= ANSWER && !independent) leave();
        if (task == Task.FAREWELL || task == Task.DEPART) GameplayLog.limited(entity, "independence", "farewell.stop", 200,
                "farewell stopped parent={} departed={} elapsed={} contact={} stalled={} walked={}", visitor, independent, elapsed, contact, stalled, departTicks);
        task = Task.NONE; running = false; visitor = null; elapsed = contact = departTicks = lookBackTicks = 0; wait = 40; target = progress = null;
        stalled = 0; said = false; sobbing = false; away = null;
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
        return tag;
    }
    public void load(CompoundTag tag) {
        origin = FamilyData.position(tag, "Origin");
        // Saves of the old construction project that had finished a new home count as left; unfinished projects are dropped.
        independent = tag.getBoolean("Independent") || tag.contains("EstablishedHome");
        household = tag.getBoolean("Household") || tag.contains("EstablishedHome");
        armed = tag.getBoolean("Force");
        wait = tag.contains("Wait") ? Math.clamp(tag.getInt("Wait"), 0, CHECK_INTERVAL) : CHECK_INTERVAL;
        if (armed) wait = 0;
        visits = Math.max(0, tag.getInt("Visits"));
        task = Task.NONE; running = false; visitor = null; elapsed = contact = 0; loadGrace = 100; parentWaits = 0;
        target = progress = null; stalled = 0;
    }
}
