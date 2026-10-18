package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.cognition.Cognition;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import java.util.Comparator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Original local succession rule, only for a confirmed vacancy in an existing forest office. */
public final class LeaderSuccession {
    public static final int DELAY_TICKS = 600;
    private static final int SAMPLE_TICKS = 20, CENTER_RADIUS = 24, WITNESS_RADIUS = 6;
    private static final Comparator<YukkuriEntity> RANK = Comparator
            .comparingInt((YukkuriEntity y) -> Cognition.get(y).ability()).reversed()
            .thenComparing(Comparator.comparingInt((YukkuriEntity y) -> y.life().personality().selfControl()).reversed())
            .thenComparing(Comparator.comparingLong((YukkuriEntity y) -> Math.max(0, y.family().age() - y.family().maturityAge())).reversed())
            .thenComparing(y -> y.getUUID().toString());

    /** Every loaded member may call this; the shared group anchor admits at most one sample per interval. */
    public static void tick(ServerLevel level, SettlementData data, SettlementData.Group group) {
        if (group.kind != SettlementData.Kind.FOREST || !group.hasLeaderOffice) return;
        long now = level.getGameTime();
        long previous = group.lastSuccessionSample;
        if (previous != Long.MIN_VALUE && now >= previous && now - previous < SAMPLE_TICKS) return;
        group.lastSuccessionSample = now;
        data.refreshLeader(level, group);
        if (group.leader != null || !group.vacancyConfirmed) { data.resetSuccession(group); return; }
        if (!level.hasChunkAt(group.center)) { data.resetSuccession(group); return; }
        var members = LoadedGroupMembers.query(level, group.id, new AABB(group.center).inflate(CENTER_RADIUS),
                y -> available(level, data, group, y)).stream().sorted(RANK).toList();
        YukkuriEntity candidate = null, witness = null;
        for (var proposed : members) {
            var seen = members.stream().filter(other -> other != proposed && proposed.distanceToSqr(other) <= WITNESS_RADIUS * WITNESS_RADIUS
                    && proposed.hasLineOfSight(other) && other.hasLineOfSight(proposed)
                    && proposed.life().canRecognize(other) && other.life().canRecognize(proposed))
                    .min(Comparator.comparing(y -> y.getUUID().toString())).orElse(null);
            if (seen != null) { candidate = proposed; witness = seen; break; }
        }
        if (candidate == null) { data.resetSuccession(group); return; }
        boolean same = candidate.getUUID().equals(group.successor) && witness.getUUID().equals(group.successionWitness);
        boolean consecutive = previous != Long.MIN_VALUE && now > previous && now - previous == SAMPLE_TICKS;
        if (!same || previous != Long.MIN_VALUE && !consecutive) group.successionTicks = 0;
        group.successor = candidate.getUUID(); group.successionWitness = witness.getUUID();
        if (same && consecutive) group.successionTicks += SAMPLE_TICKS;
        data.setDirty();
        if (group.successionTicks >= DELAY_TICKS && data.appoint(group, candidate.getUUID())) {
            candidate.getLookControl().setLookAt(witness, 25, 25);
            witness.getLookControl().setLookAt(candidate, 25, 25);
            candidate.showReaction("happy", "hello", 60);
        }
    }

    private static boolean available(ServerLevel level, SettlementData data, SettlementData.Group group, YukkuriEntity y) {
        var member = data.existingMember(group, y.getUUID());
        var known = FamilyData.get(level).person(y.getUUID());
        var human = Custody.view(y);
        return member != null && member.role == SettlementData.Role.MEMBER && member.rejections == 0
                && y.settlement().group() == group && level.getEntity(y.getUUID()) == y
                && known != null && known.status == FamilyData.Status.ALIVE
                && !y.profile().houseBody() && !y.profile().dangerToYukkuri()
                && !y.profile().aquatic()
                && human.wild()
                && (y.life().home() == null || y.life().naturalHome())
                && y.distanceToSqr(Vec3.atBottomCenterOf(group.center)) <= CENTER_RADIUS * CENTER_RADIUS
                && y.isAlive() && !y.isRemoved() && !y.isNoAi() && y.family().adult() && y.life().hasAccessory()
                && !y.isPanicking() && !y.isCommandPlaying() && !y.operations().active() && !y.isPassenger() && !y.isLeashed()
                && !y.isInWaterOrBubble() && !y.isOnFire() && !y.life().hasUrgentNeed()
                && Behaviors.IDLE.equals(y.life().activity()) && !y.social().active()
                && !y.life().isSleeping() && y.life().mealTicks() == 0 && !y.family().active() && y.family().recovery() == 0
                && !FamilyData.get(level).busyParent(y.getUUID()) && !y.family().hasHungryChild()
                && !y.dailyLife().active() && !y.groupInteraction().active() && !y.playLife().active()
                && !y.independence().active() && !y.independence().building() && !y.independence().householdOuting()
                && y.independence().carrying().isEmpty() && !y.habitat().active() && y.habitat().carrying().isEmpty()
                && !y.settlement().duties().active() && !y.wintering().active()
                && !io.github.longshou.mcyukkuri.event.WildEvents.reserved(y);
    }
    private LeaderSuccession() {}
}
