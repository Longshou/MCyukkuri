package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.server.level.ServerLevel;

/**
 * The physical hearing for one pending community-rule case.
 *
 * <p>The authoritative case remains in {@link Commons.Pending}. This component only makes its owner approach, face and
 * hear the case. Therefore an interrupted or unloaded entity never removes a pending offence; a current leader can
 * resume it, and a successor can reclaim it.
 */
public final class Judgement {
    private static final double FACE_RANGE = 3.0;
    private static final long HEARING_TICKS = 80;
    private static final long APPROACH_TIMEOUT = 600;
    private final YukkuriEntity entity;
    private long interruptedUntil;
    private SettlementData.Group currentGroup;
    private Commons.Pending current;

    public Judgement(YukkuriEntity entity) { this.entity = entity; }

    /** A component bridge calls this only as a real MOVE/LOOK Goal. */
    public boolean canUse() {
        if (!ready()) return false;
        var pending = leaderCase();
        if (pending == null) pending = accusedCase();
        return pending != null;
    }

    /** Whether this entity may hold still for the other participant's active hearing. */
    public boolean available() { return ready(); }

    /** The selected presentation source observes this only while the bridge Goal is running. */
    public String activity() {
        if (current == null) return Behaviors.IDLE;
        return current.hearing == Commons.Hearing.APPROACH ? Behaviors.JUDGEMENT_APPROACH : Behaviors.JUDGEMENT_HEARING;
    }

    public void tickGoal() {
        if (current == null) return;
        if (leaderCase() == current) {
            tickLeader(current);
            return;
        }
        if (accusedCase() == current) tickAccused(current);
    }

    /** A pre-empting goal must only stop movement; the group-owned pending case stays intact. */
    public void stop() {
        current = null;
        currentGroup = null;
        entity.getNavigation().stop();
    }

    /** Physical danger yields the MOVE/LOOK slot briefly without discarding the group-owned pending case. */
    public void interrupt() {
        if (entity.level() instanceof ServerLevel level) interruptedUntil = Math.max(interruptedUntil, level.getGameTime() + 40);
        stop();
    }

    /** Operator forcing starts the same physical sequence instead of applying a sentence synchronously. */
    static int force(ServerLevel level, SettlementData.Group group) {
        int count = 0;
        for (var pending : group.commons.pending) {
            pending.forced = true;
            count++;
        }
        if (count > 0) SettlementData.get(level).setDirty();
        return count;
    }

    /** Retires only entries that the former direct path could no longer judge; it never applies a sentence. */
    static void lapse(ServerLevel level, SettlementData.Group group) {
        var commons = group.commons;
        long now = level.getGameTime();
        boolean changed = false;
        for (var it = commons.pending.iterator(); it.hasNext();) {
            var pending = it.next();
            var offence = CommunityRules.Offence.byId(pending.offence);
            if (offence == null) {
                it.remove();
                commons.pendingRemoved(pending);
                commons.note(now, "lapsed_unknown", pending.offender, pending.offence == null ? "" : pending.offence);
                changed = true;
                continue;
            }
            if (!(level.getEntity(pending.offender) instanceof YukkuriEntity offender) || !offender.isAlive()) {
                if (now - pending.time > CommunityRules.LAPSE_AFTER) {
                    it.remove();
                    commons.pendingRemoved(pending);
                    commons.note(now, "lapsed_" + offence.id, pending.offender, "offender_gone");
                    changed = true;
                } else if (pending.hearing != Commons.Hearing.NONE) {
                    clearHearing(pending);
                    changed = true;
                }
            }
        }
        if (changed) SettlementData.get(level).setDirty();
    }

    private boolean ready() {
        return entity.level() instanceof ServerLevel level && level.getGameTime() >= interruptedUntil && entity.isAlive() && !entity.isNoAi() && !entity.isPanicking()
                && !entity.isInWaterOrBubble() && !entity.life().isSleeping() && !entity.life().hasUrgentNeed()
                && entity.life().mealTicks() == 0 && !entity.operations().controlling();
    }

    private Commons.Pending leaderCase() {
        if (!(entity.level() instanceof ServerLevel level)) return null;
        var group = entity.settlement().group();
        if (!CommunityRules.ruled(group) || CommunityRules.leader(level, group) != entity) return null;
        long now = level.getGameTime();
        for (var pending : group.commons.pending) {
            if (CommunityRules.Offence.byId(pending.offence) == null) continue;
            if (pending.hearing != Commons.Hearing.NONE) {
                if (!(level.getEntity(pending.offender) instanceof YukkuriEntity offender) || !offender.isAlive()) continue;
                if (pending.hearing != Commons.Hearing.ABSENT && !offender.judgement().available()) return null;
                // Resuming a stopped goal or a loaded case must earn a fresh face-to-face hearing.
                if (current != pending && pending.hearing == Commons.Hearing.HEARING) {
                    pending.hearing = Commons.Hearing.APPROACH;
                    pending.hearingSince = now;
                    SettlementData.get(level).setDirty();
                }
                if (!claim(level, group, pending, pending.hearing, now)) return null;
                select(group, pending, offender);
                return pending;
            }
            if (!(level.getEntity(pending.offender) instanceof YukkuriEntity offender) || !offender.isAlive()) continue;
            if (!offender.judgement().available()) continue;
            boolean present = offender.distanceToSqr(entity) <= CommunityRules.JUDGE_RANGE * CommunityRules.JUDGE_RANGE
                    && entity.hasLineOfSight(offender);
            if (!pending.forced && !present && now - pending.time < CommunityRules.ABSENT_AFTER) continue;
            Commons.Hearing phase = !pending.forced && !present && now - pending.time >= CommunityRules.ABSENT_AFTER
                    ? Commons.Hearing.ABSENT : Commons.Hearing.APPROACH;
            if (!claim(level, group, pending, phase, now)) continue;
            select(group, pending, offender);
            return pending;
        }
        return null;
    }

    private Commons.Pending accusedCase() {
        if (!(entity.level() instanceof ServerLevel level)) return null;
        if (current == null || currentGroup == null || !currentGroup.commons.pending.contains(current)
                || !entity.getUUID().equals(current.offender) || current.hearing == Commons.Hearing.NONE
                || current.hearing == Commons.Hearing.ABSENT) return null;
        var leader = CommunityRules.leader(level, currentGroup);
        return leader != null && leader.getUUID().equals(current.hearingLeader) ? current : null;
    }

    private void tickLeader(Commons.Pending pending) {
        var level = (ServerLevel)entity.level();
        var group = entity.settlement().group();
        if (group == null || CommunityRules.leader(level, group) != entity || !group.commons.pending.contains(pending)) return;
        long now = level.getGameTime();
        var offender = level.getEntity(pending.offender) instanceof YukkuriEntity value && value.isAlive() ? value : null;
        if (offender == null) {
            clearHearing(pending);
            SettlementData.get(level).setDirty();
            return;
        }
        if (pending.hearing != Commons.Hearing.ABSENT && !offender.judgement().available()) {
            entity.getNavigation().stop();
            return;
        }
        if (pending.hearing == Commons.Hearing.APPROACH) {
            if (now - pending.hearingSince > APPROACH_TIMEOUT) {
                pending.forced = false;
                clearHearing(pending);
                group.commons.note(now, "judge_deferred", offender.getUUID(), "approach_timeout");
                SettlementData.get(level).setDirty();
                return;
            }
            entity.getLookControl().setLookAt(offender, 30, 30);
            if (entity.distanceToSqr(offender) > FACE_RANGE * FACE_RANGE || !entity.hasLineOfSight(offender)) {
                if (entity.tickCount % 10 == 0) entity.life().navigate(offender.blockPosition(), 1.0, false);
                return;
            }
            entity.getNavigation().stop();
            pending.hearing = Commons.Hearing.HEARING;
            pending.hearingSince = now;
            announce(level, group, pending, offender);
            SettlementData.get(level).setDirty();
            return;
        }
        if (pending.hearing == Commons.Hearing.ABSENT) {
            entity.getNavigation().stop();
            if (now - pending.hearingSince >= HEARING_TICKS) conclude(level, group, pending, offender, false);
            return;
        }
        entity.getNavigation().stop();
        entity.getLookControl().setLookAt(offender, 30, 30);
        if (entity.distanceToSqr(offender) > FACE_RANGE * FACE_RANGE || !entity.hasLineOfSight(offender)) {
            pending.hearing = Commons.Hearing.APPROACH;
            pending.hearingSince = now;
            SettlementData.get(level).setDirty();
            return;
        }
        if (now - pending.hearingSince >= HEARING_TICKS) conclude(level, group, pending, offender, true);
    }

    private void tickAccused(Commons.Pending pending) {
        var level = (ServerLevel)entity.level();
        var leader = level.getEntity(pending.hearingLeader) instanceof YukkuriEntity value && value.isAlive() ? value : null;
        if (leader == null) return;
        entity.getLookControl().setLookAt(leader, 30, 30);
        if (pending.hearing == Commons.Hearing.APPROACH
                && (entity.distanceToSqr(leader) > FACE_RANGE * FACE_RANGE || !entity.hasLineOfSight(leader))) {
            if (entity.tickCount % 10 == 0) entity.life().navigate(leader.blockPosition(), .9, false);
            return;
        }
        entity.getNavigation().stop();
    }

    private void select(SettlementData.Group group, Commons.Pending pending, YukkuriEntity offender) {
        currentGroup = group;
        current = pending;
        if (pending.hearing != Commons.Hearing.ABSENT && offender != null && offender != entity) {
            offender.judgement().bindAccused(group, pending);
        }
    }

    private void bindAccused(SettlementData.Group group, Commons.Pending pending) {
        currentGroup = group;
        current = pending;
    }

    private static boolean claim(ServerLevel level, SettlementData.Group group, Commons.Pending pending, Commons.Hearing phase, long now) {
        var leader = CommunityRules.leader(level, group);
        if (leader == null) return false;
        if (leader.getUUID().equals(pending.hearingLeader) && pending.hearing != Commons.Hearing.NONE) return true;
        pending.hearingLeader = leader.getUUID();
        pending.hearing = phase;
        pending.hearingSince = now;
        if (phase == Commons.Hearing.ABSENT) leader.showReaction("angry", "rule_rebuke", (int)HEARING_TICKS);
        SettlementData.get(level).setDirty();
        return true;
    }

    private static void clearHearing(Commons.Pending pending) {
        pending.hearingLeader = null;
        pending.hearing = Commons.Hearing.NONE;
        pending.hearingSince = 0;
    }

    private static void announce(ServerLevel level, SettlementData.Group group, Commons.Pending pending, YukkuriEntity offender) {
        var leader = level.getEntity(pending.hearingLeader) instanceof YukkuriEntity value ? value : null;
        if (leader == null) return;
        leader.showReaction("angry", "rule_rebuke", (int)HEARING_TICKS);
        offender.showReaction("teary", "rule_punished", (int)HEARING_TICKS);
        for (var witness : level.getEntitiesOfClass(YukkuriEntity.class, offender.getBoundingBox().inflate(CommunityRules.WITNESS_RANGE),
                value -> value != leader && value != offender && value.isAlive() && !value.isNoAi()
                        && group.id.equals(value.settlement().groupId()) && SettlementSociety.accepted(value)
                        && !value.life().isSleeping() && value.hasLineOfSight(offender) && value.life().canRecognize(offender))) {
            witness.getLookControl().setLookAt(offender, 25, 25);
            witness.showReaction("surprise", "heard", 50);
        }
    }

    private static void conclude(ServerLevel level, SettlementData.Group group, Commons.Pending pending, YukkuriEntity offender, boolean present) {
        var offence = CommunityRules.Offence.byId(pending.offence);
        var leader = CommunityRules.leader(level, group);
        if (offence == null || leader == null || !leader.getUUID().equals(pending.hearingLeader) || !group.commons.pending.contains(pending)) return;
        CommunityRules.judge(level, group, leader, offender, offence, pending.detail, present);
        group.commons.removePending(pending);
        SettlementData.get(level).setDirty();
    }
}
