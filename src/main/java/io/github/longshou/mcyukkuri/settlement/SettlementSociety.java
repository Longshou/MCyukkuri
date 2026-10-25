package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;

/** Actual nearby witnesses create social consequences; a missing hat alone creates none. */
public final class SettlementSociety {
    private final YukkuriEntity entity;
    public SettlementSociety(YukkuriEntity entity) { this.entity = entity; }
    private ServerLevel level() { return (ServerLevel)entity.level(); }
    public SettlementData.Role role() {
        return entity.level().isClientSide ? SettlementData.Role.FREE
                : SettlementData.get(level()).role(entity.settlement().group(), entity.getUUID());
    }
    public SettlementData.Member record() {
        if (entity.level().isClientSide) return null;
        var group = entity.settlement().group();
        return group == null ? null : SettlementData.get(level()).member(group, entity.getUUID());
    }
    public static boolean sameGroup(YukkuriEntity first, YukkuriEntity second) {
        var group = first.settlement().group();
        var other = second.settlement().group();
        return first != second && first.level() == second.level() && group != null && other != null && group.id.equals(other.id);
    }
    public static boolean accepted(YukkuriEntity member) {
        var role = member.settlement().society().role();
        return role == SettlementData.Role.MEMBER || role == SettlementData.Role.LEADER;
    }
    private static boolean harsh(YukkuriEntity observer) {
        var p = observer.life().personality();
        return Temperament.harshForSettlement(p);
    }
    private static boolean available(YukkuriEntity member) {
        return member.isAlive() && !member.isNoAi() && !member.isPanicking() && !member.isCommandPlaying()
                && !member.operations().active() && !member.life().isSleeping() && !member.life().hasUrgentNeed()
                && member.life().mealTicks() == 0 && !member.family().active() && (!member.isPassenger() || steadyAfloat(member));
    }
    /** Boat riders observe only from a stable own hull; passengers, crossings and rescues never create a remote social event. */
    private static boolean steadyAfloat(YukkuriEntity member) {
        if (!member.profile().aquatic() || !(member.getVehicle() instanceof HatBoatEntity boat)) return false;
        return member.getUUID().equals(boat.ownerYukkuri()) && boat.isAfloat() && boat.protects(member)
                && !boat.unsteadyMotion() && boat.flooding() == 0 && !boat.paddling()
                && !boat.landing() && !boat.hasIncoming();
    }
    /** Recognition past a missing hat is the observer's intellect (headwear.Recognition); a working own hat boat still counts as the hat. */
    public static boolean rejects(YukkuriEntity observer, YukkuriEntity target) {
        return sameGroup(observer, target) && accepted(observer) && harsh(observer)
                && !observer.life().canRecognize(target);
    }
    public static boolean avoids(YukkuriEntity observer, YukkuriEntity target) {
        if (!sameGroup(observer, target)) return false;
        if (rejects(observer, target)) return true;
        var record = target.settlement().society().record();
        return record != null && observer.getUUID().equals(record.observer)
                && record.rememberedUntil > observer.level().getGameTime();
    }
    public void tick() {
        if (entity.tickCount % 100 != 0 || !available(entity)) return;
        var group = entity.settlement().group();
        if (group == null) return;
        var data = SettlementData.get(level());
        var record = data.member(group, entity.getUUID());
        long now = level().getGameTime();
        // Handles the original accessory being returned while transported to another dimension.
        if (entity.life().hasAccessory() && record.rejections > 0) { restored(); return; }
        if (now < record.nextObservation) return;
        var box = entity.getBoundingBox().inflate(6);
        var observers = LoadedGroupMembers.query(level(), group.id, box,
                peer -> available(peer) && rejects(peer, entity) && peer.hasLineOfSight(entity));
        var observer = LoadedGroupMembers.minimum(level(), box, observers, entity::distanceToSqr);
        if (observer == null) return;
        record.nextObservation = now + 200; record.observer = observer.getUUID();
        int before = record.rejections;
        record.rejections = Math.min(5, before + 1);
        if (before < 5) {
            YukkuriWorldData.get(level()).relate(observer.getUUID(), entity.getUUID(), -2);
            entity.life().personality().decided("settlement_rejected");
        }
        if (record.rejections >= 2 && accepted(entity)) record.role = SettlementData.Role.SHUNNED;
        if (record.rejections >= 5 && record.role == SettlementData.Role.SHUNNED) {
            var p = entity.life().personality();
            // These thresholds are project design. Attachment alone is never equated with obedience.
            boolean stays = entity.family().adult() && p.friendliness() >= 60 && p.selfControl() >= Temperament.SELF_CONTROL_AT_50
                    && p.attachment() >= Temperament.ATTACHMENT_AT_55;
            record.role = stays ? SettlementData.Role.UNUN_SLAVE : SettlementData.Role.OUTCAST;
            if (before < 5) CommunityRules.hatlessOutcome(entity, group, record.role);
        }
        String event = record.role == SettlementData.Role.OUTCAST ? "settlement_drive"
                : record.role == SettlementData.Role.UNUN_SLAVE ? "settlement_assign" : "settlement_shun";
        // An assignment is a bounded order by a real member, never remote group omniscience.
        if (record.role == SettlementData.Role.UNUN_SLAVE) {
            data.assign(group,record,observer.getUUID(),now+1200);
        }
        observer.getLookControl().setLookAt(entity, 25, 25);
        observer.showReaction("angry", event, 80);
        entity.mind().felt(record.role == SettlementData.Role.UNUN_SLAVE ? EmotionEvent.SUBMITTED : EmotionEvent.SOCIAL_HURT); entity.showReaction("teary",
                record.role == SettlementData.Role.UNUN_SLAVE ? "settlement_submit" : "settlement_hurt", 100);
        data.setDirty();
    }
    /** After a member's own mockery of a slave kept in the pit: one per slave per 400 ticks. Transient. */
    private static final Map<YukkuriEntity, Long> MOCKED = new WeakHashMap<>();

    /**
     * A member has just let go at the pit bank. Any 嗯嗯奴隶 of the group in sight of it is jeered at by the harsh members who can
     * see it (up to three, including this one): their angry reaction and a small, real drop of their regard for it; the slave
     * feels submitted and is splashed (hygiene). Project design; the lines wait for the dialogue pass.
     */
    public void pitExcreted() {
        var group = entity.settlement().group();
        if (group == null || !accepted(entity)) return;
        long now = level().getGameTime();
        for (var slave : LoadedGroupMembers.query(level(), group.id, entity.getBoundingBox().inflate(7),
                peer -> peer.isAlive() && peer.settlement().society().role() == SettlementData.Role.UNUN_SLAVE && entity.hasLineOfSight(peer))) {
            var last = MOCKED.get(slave);
            if (last != null && now - last < 400) continue;
            MOCKED.put(slave, now);
            var witnesses = new ArrayList<>(LoadedGroupMembers.query(level(), group.id, slave.getBoundingBox().inflate(6),
                    peer -> peer != slave && peer.isAlive() && accepted(peer) && harsh(peer) && peer.hasLineOfSight(slave)));
            if (witnesses.size() > 3) witnesses.subList(3, witnesses.size()).clear();
            for (var witness : witnesses) {
                witness.getLookControl().setLookAt(slave, 25, 25);
                witness.showReaction("angry", "settlement_assign", 80);
                YukkuriWorldData.get(level()).relate(witness.getUUID(), slave.getUUID(), -1);
            }
            float before = slave.life().hygiene();
            slave.life().changeHygiene(-3);
            slave.mind().felt(EmotionEvent.SUBMITTED);
            slave.showReaction("teary", "settlement_submit", 100);
            GameplayLog.event(slave, "settlement", "pit mockery witnesses={} excretor={} hygiene={} -> {}", witnesses.size(), entity.getUUID(),
                    before, slave.life().hygiene());
        }
    }

    public void restored() {
        var group = entity.settlement().group();
        if (group == null) return;
        var record = record();
        if (record.rejections == 0) return;
        SettlementData.get(level()).restore(group, entity.getUUID(), level().getGameTime());
        entity.settlement().duties().cancelAssignment();
        entity.life().personality().decided("settlement_restored");
        // No affinity reset: the actual prior interactions remain in YukkuriWorldData.
    }
    public boolean dejected() {
        var role = role();
        return role == SettlementData.Role.SHUNNED || role == SettlementData.Role.OUTCAST || role == SettlementData.Role.UNUN_SLAVE;
    }
}
