package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.RemainsEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.headwear.Recognition;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.social.Bonds;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A vacant forest office is filled only after two actual members have met and recognised one another. */
public final class LeaderSuccession {
    public static final int DELAY_TICKS = 600;
    private static final int SAMPLE_TICKS = 20, CENTER_RADIUS = 24, WITNESS_RADIUS = 6;
    private static final long ATTEMPT_TICKS = 1800, RETRY_TICKS = 200;
    /** Strict contact is runtime evidence: restarts never invent an offline interval. */
    private static final Map<SettlementData.Group, Long> LAST_STRICT_SAMPLE = new WeakHashMap<>();
    private static final Comparator<YukkuriEntity> RANK = Comparator
            .comparingInt((YukkuriEntity y) -> y.life().personality().intelligence()).reversed()
            .thenComparing(Comparator.comparingInt((YukkuriEntity y) -> y.life().personality().selfControl()).reversed())
            .thenComparing(Comparator.comparingLong((YukkuriEntity y) -> Math.max(0, y.family().age() - y.family().maturityAge())).reversed())
            .thenComparing(y -> y.getUUID().toString());

    /** The Post ticker checks this once per participating group, including ticks without a 100-tick sample. */
    static boolean due(SettlementData.Group group, long now) {
        if (group.kind != SettlementData.Kind.FOREST || !group.hasLeaderOffice) return false;
        long previous = group.lastSuccessionSample;
        return previous == Long.MIN_VALUE || now < previous || now - previous >= SAMPLE_TICKS;
    }

    /**
     * The ordinary settlement Goal asks this before seasonal gathering or migration. It only returns the current meeting
     * place for one chosen member, so the existing goal remains responsible for pathing, physiological blocks and priority.
     */
    static BlockPos meetingTarget(YukkuriEntity member) {
        if (!(member.level() instanceof ServerLevel level)) return null;
        var group = member.settlement().group();
        if (group == null || !group.vacant() || group.successor == null || group.successionWitness == null) return null;
        if (!member.getUUID().equals(group.successor) && !member.getUUID().equals(group.successionWitness)) return null;
        long now = level.getGameTime();
        BlockPos point = meetingPoint(group, now);
        if (point == null || group.successionAt == null || !point.equals(group.successionAt) || now >= group.successionUntil) return null;
        var data = SettlementData.get(level);
        // This also ends an already-running ordinary Goal when food, child care or sleep takes priority; the selected
        // pair and its accumulated strict time remain in the group record for the later retry.
        if (!strictAvailable(level, data, group, member, point, now)) return null;
        return point;
    }

    static void sample(ServerLevel level, SettlementData data, SettlementData.Group group, long now) {
        if (!group.vacant()) {
            stopAttempt(data, group, now, 0, "office_filled");
            return;
        }
        if (group.successor != null || group.successionWitness != null) {
            continueAttempt(level, data, group, now);
            return;
        }
        if (now < group.successionRetry) return;
        BlockPos point = meetingPoint(group, now);
        if (point == null || !level.hasChunkAt(point)) return;
        var members = LoadedGroupMembers.query(level, group.id, new AABB(point).inflate(CENTER_RADIUS),
                y -> strictAvailable(level, data, group, y, point, now)).stream().sorted(RANK).toList();
        for (var candidate : members) {
            var witness = members.stream().filter(other -> other != candidate && canStartTogether(candidate, other))
                    .min(Comparator.comparing(y -> y.getUUID().toString())).orElse(null);
            if (witness == null) continue;
            group.successor = candidate.getUUID();
            group.successionWitness = witness.getUUID();
            group.successionTicks = 0;
            group.successionAt = point.immutable();
            group.successionUntil = now + ATTEMPT_TICKS;
            group.successionRetry = 0;
            LAST_STRICT_SAMPLE.remove(group);
            group.commons.note(now, "succession_meeting", candidate.getUUID(), "witness=" + witness.getUUID()
                    + " at=" + point.toShortString() + " until=" + group.successionUntil);
            data.setDirty();
            return;
        }
        // No candidate has been assigned, so this is only a transient throttle rather than a persisted election state.
        group.successionRetry = now + RETRY_TICKS;
    }

    private static void continueAttempt(ServerLevel level, SettlementData data, SettlementData.Group group, long now) {
        if (now >= group.successionUntil) {
            stopAttempt(data, group, now, now + RETRY_TICKS, "attempt_timeout");
            return;
        }
        var candidate = loaded(level, group.successor);
        var witness = loaded(level, group.successionWitness);
        // An unloaded member is not reclassified as absent. The bounded attempt simply waits for an actual contact.
        if (candidate == null || witness == null) {
            LAST_STRICT_SAMPLE.remove(group);
            return;
        }
        if (!coreEligible(level, data, group, candidate) || !coreEligible(level, data, group, witness)) {
            stopAttempt(data, group, now, now + RETRY_TICKS, "candidate_ineligible");
            return;
        }
        BlockPos point = meetingPoint(group, now);
        if (point == null || !level.hasChunkAt(point)) {
            LAST_STRICT_SAMPLE.remove(group);
            return;
        }
        if (!point.equals(group.successionAt)) {
            group.successionAt = point.immutable();
            data.setDirty();
        }
        if (!strictAvailable(level, data, group, candidate, point, now)
                || !strictAvailable(level, data, group, witness, point, now)
                || !mutualContact(candidate, witness)) {
            // Food, child care and other short-lived duties hold the existing pair.  A later strict interval begins
            // afresh from the retained counter; it never claims the blocked twenty ticks as recognition time.
            LAST_STRICT_SAMPLE.remove(group);
            return;
        }
        Long lastStrict = LAST_STRICT_SAMPLE.put(group, now);
        if (lastStrict != null && now > lastStrict && now - lastStrict == SAMPLE_TICKS) {
            group.successionTicks = Math.min(DELAY_TICKS, group.successionTicks + SAMPLE_TICKS);
            data.setDirty();
        }
        BlockPos meeting = group.successionAt;
        UUID witnessId = witness.getUUID();
        if (group.successionTicks >= DELAY_TICKS && data.appoint(group, candidate.getUUID())) {
            LAST_STRICT_SAMPLE.remove(group);
            group.commons.note(now, "succession_appointed", candidate.getUUID(), "witness=" + witnessId
                    + " at=" + (meeting == null ? "none" : meeting.toShortString()));
            data.setDirty();
            candidate.getLookControl().setLookAt(witness, 25, 25);
            witness.getLookControl().setLookAt(candidate, 25, 25);
            candidate.showReaction("happy", "hello", 60);
        }
    }

    private static void stopAttempt(SettlementData data, SettlementData.Group group, long now, long retry, String reason) {
        LAST_STRICT_SAMPLE.remove(group);
        UUID actor = group.successor != null ? group.successor : group.successionWitness;
        if (actor != null) group.commons.note(now, "succession_end", actor, reason + " witness=" + group.successionWitness
                + " ticks=" + group.successionTicks);
        data.resetSuccession(group);
        group.successionRetry = retry;
    }

    /** DISCUSS uses the old centre; PREPARE owns its own work; only settled marchers may meet at the new address. */
    private static BlockPos meetingPoint(SettlementData.Group group, long now) {
        return switch (Migration.phase(group, now)) {
            case NONE, DISCUSS -> group.center;
            case PREPARE -> null;
            case MARCH -> group.migrateTo;
        };
    }

    private static boolean atMeeting(YukkuriEntity member, SettlementData.Group group, BlockPos point, long now) {
        return Migration.phase(group, now) == Migration.Phase.MARCH
                ? member.settlement().settledAt(point)
                : member.distanceToSqr(Vec3.atBottomCenterOf(point)) <= CENTER_RADIUS * CENTER_RADIUS;
    }

    /** Candidates see one another locally before they walk together. */
    private static boolean canStartTogether(YukkuriEntity candidate, YukkuriEntity witness) {
        return candidate.distanceToSqr(witness) <= CENTER_RADIUS * CENTER_RADIUS
                && candidate.hasLineOfSight(witness) && witness.hasLineOfSight(candidate)
                && candidate.life().canRecognize(witness) && witness.life().canRecognize(candidate);
    }

    private static YukkuriEntity loaded(ServerLevel level, UUID id) {
        return id != null && level.getEntity(id) instanceof YukkuriEntity member ? member : null;
    }

    /** Only lost group standing, personhood or adult qualification clears an already selected pair. */
    private static boolean coreEligible(ServerLevel level, SettlementData data, SettlementData.Group group, YukkuriEntity member) {
        var record = data.existingMember(group, member.getUUID());
        var known = FamilyData.get(level).person(member.getUUID());
        return record != null && record.role == SettlementData.Role.MEMBER && record.rejections == 0
                && member.settlement().group() == group && level.getEntity(member.getUUID()) == member
                && known != null && known.status == FamilyData.Status.ALIVE
                && !member.profile().houseBody() && !member.profile().dangerToYukkuri()
                && member.isAlive() && !member.isRemoved() && !member.isNoAi() && member.family().adult() && member.life().hasAccessory();
    }

    /** Strict samples additionally require a nearby, settled, undistracted pair with mutual recognition. */
    private static boolean strictAvailable(ServerLevel level, SettlementData data, SettlementData.Group group, YukkuriEntity member,
                                           BlockPos point, long now) {
        return coreEligible(level, data, group, member) && atMeeting(member, group, point, now)
                && Custody.view(member).wild() && (member.life().home() == null || member.life().naturalHome())
                && !member.isPanicking() && !member.isCommandPlaying() && !member.operations().active()
                && !member.isPassenger() && !member.isLeashed() && !member.isInWaterOrBubble() && !member.isOnFire()
                && !member.life().hasUrgentNeed() && Behaviors.IDLE.equals(member.life().activity()) && !member.social().active()
                && !member.life().isSleeping() && member.life().mealTicks() == 0 && !member.family().active() && member.family().recovery() == 0
                && !FamilyData.get(level).busyParent(member.getUUID()) && !member.family().hasHungryChild()
                && !member.dailyLife().active() && !member.groupInteraction().active() && !member.playLife().active()
                && !member.forageTrip().outing()
                && !member.independence().active() && !member.independence().building() && !member.independence().householdOuting()
                && member.independence().carrying().isEmpty() && !member.habitat().active() && member.habitat().carrying().isEmpty()
                && !member.settlement().duties().active() && !member.wintering().active() && !SettlementEvents.reserved(member);
    }

    private static boolean mutualContact(YukkuriEntity first, YukkuriEntity second) {
        return first != second && first.distanceToSqr(second) <= WITNESS_RADIUS * WITNESS_RADIUS
                && first.hasLineOfSight(second) && second.hasLineOfSight(first)
                && first.life().canRecognize(second) && second.life().canRecognize(first);
    }

    /** Called from the once-only terminal death path before the living entity leaves the local scene. */
    static void died(YukkuriEntity victim, SettlementData.Group group) {
        if (!(victim.level() instanceof ServerLevel level) || group == null || !victim.getUUID().equals(group.leader)) return;
        var data = SettlementData.get(level);
        data.leaderDied(group, victim.getUUID(), level.getGameTime());
        if (!group.leaderDied || !victim.getUUID().equals(group.formerLeader)) return;
        for (var witness : LoadedGroupMembers.query(level, group.id, victim.getBoundingBox().inflate(10),
                other -> other != victim && liveWitness(other) && other.hasLineOfSight(victim) && other.life().canRecognize(victim))) {
            boolean closeKin = Bonds.careOrMate(witness, victim);
            markKnown(data, group, witness, closeKin, "death_witness");
        }
    }

    /** A group member learns only by seeing the identified physical remains or meeting a member who already knows. */
    static void observe(YukkuriEntity observer, SettlementData.Group group) {
        if (!(observer.level() instanceof ServerLevel level) || group == null || !group.leaderDied || group.formerLeader == null
                || group.leaderLossAt <= 0 || !liveWitness(observer)) return;
        var data = SettlementData.get(level);
        var record = data.existingMember(group, observer.getUUID());
        if (record == null || record.knownLeaderLoss >= group.leaderLossAt) return;
        var nearby = observer.getBoundingBox().inflate(WITNESS_RADIUS);
        for (var remains : level.getEntitiesOfClass(RemainsEntity.class, nearby, body -> actualLeaderRemains(observer, group, body))) {
            CompoundTag body = remains.data();
            boolean closeKin = Bonds.corpseKin(observer, group.formerLeader, body.hasUUID("Partner") ? body.getUUID("Partner") : null);
            markKnown(data, group, observer, closeKin, "body_witness");
            return;
        }
        var sources = LoadedGroupMembers.query(level, group.id, nearby,
                peer -> peer != observer && liveWitness(peer) && !peer.isPanicking() && !peer.isCommandPlaying()
                        && !peer.operations().active() && !peer.life().hasUrgentNeed() && peer.life().mealTicks() == 0
                        && !peer.family().active() && known(data, group, peer)
                        && mutualContact(observer, peer));
        var source = LoadedGroupMembers.minimum(level, nearby, sources, observer::distanceToSqr);
        if (source != null) markKnown(data, group, observer, closeToFormer(observer, group), "heard");
    }

    private static boolean actualLeaderRemains(YukkuriEntity observer, SettlementData.Group group, RemainsEntity remains) {
        if (remains.isRemoved() || !observer.hasLineOfSight(remains)) return false;
        CompoundTag body = remains.data();
        return body.hasUUID("Identity") && group.formerLeader.equals(body.getUUID("Identity"))
                && Recognition.remainsRecognized(observer, body);
    }

    private static boolean known(SettlementData data, SettlementData.Group group, YukkuriEntity member) {
        var record = data.existingMember(group, member.getUUID());
        return record != null && record.knownLeaderLoss >= group.leaderLossAt;
    }

    private static boolean liveWitness(YukkuriEntity member) {
        return member.isAlive() && !member.isRemoved() && !member.isNoAi() && !member.life().isSleeping();
    }

    private static boolean closeToFormer(YukkuriEntity member, SettlementData.Group group) {
        return FamilyData.get((ServerLevel) member.level()).socialKin(member.getUUID(), group.formerLeader)
                || group.formerLeader.equals(member.family().partner());
    }

    private static void markKnown(SettlementData data, SettlementData.Group group, YukkuriEntity member, boolean closeKin, String source) {
        var record = data.existingMember(group, member.getUUID());
        if (record == null || record.knownLeaderLoss >= group.leaderLossAt) return;
        record.knownLeaderLoss = group.leaderLossAt;
        if (closeKin) {
            // Direct witnesses and body witnesses retain their pre-existing bereavement chain. A relative who only hears
            // the news gets a small, visible response without being misreported as an eye-witnessed family death.
            if ("heard".equals(source)) {
                member.mind().add(2, "leader_news");
                member.showReaction("teary", "heard", 80);
            }
        } else {
            member.mind().felt(EmotionEvent.FRIGHTENED);
            member.showReaction("surprise", "afraid", 80);
        }
        group.commons.note(((ServerLevel) member.level()).getGameTime(), "leader_news", member.getUUID(), source);
        data.setDirty();
    }

    private LeaderSuccession() {}
}
