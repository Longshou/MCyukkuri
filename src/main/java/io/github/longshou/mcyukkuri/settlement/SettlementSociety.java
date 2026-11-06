package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;

/** Actual nearby witnesses create social consequences; a missing hat alone creates none. */
public final class SettlementSociety {
    /** The same observer rejects the same member again only after a longer gap: one rejection per encounter. */
    private static final long REJECT_GAP = 200, ENCOUNTER_GAP = 1200;
    private final YukkuriEntity entity;
    public SettlementSociety(YukkuriEntity entity) { this.entity = entity; }
    private ServerLevel level() { return (ServerLevel)entity.level(); }
    public SettlementData.Role role() {
        return entity.level().isClientSide ? SettlementData.Role.FREE
                : SettlementData.get(level()).role(entity.settlement().group(), entity.getUUID());
    }
    /** Stands in the group as an 嗯嗯奴隶 (settlement/PitSlave owns what that life is). */
    public boolean slave() { return role() == SettlementData.Role.UNUN_SLAVE; }
    /** Current pit slave, or a former slave whose colony accepted it gently: only their own pit/social pressures are bounded. */
    public boolean pitPressureProtected() {
        var record = record();
        return record != null && (record.role == SettlementData.Role.UNUN_SLAVE || record.hatlessAccepted);
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
    /** The colony's lasting acceptance of this hatless member, distinct from an observer's innate recognition. */
    public static boolean acceptsHatless(YukkuriEntity observer, YukkuriEntity target) {
        if (!sameGroup(observer, target)) return false;
        var record = target.settlement().society().record();
        return record != null && record.hatlessAccepted;
    }
    /** The group's own 嗯嗯奴隶, or a hatless member the colony has accepted: never beaten for being hatless. */
    public static boolean sparesHatlessBlame(YukkuriEntity observer, YukkuriEntity target) {
        return sameGroup(observer, target)
                && (target.settlement().society().slave() || acceptsHatless(observer, target));
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
                && member.life().mealTicks() == 0 && !member.family().active() && !member.isPassenger();
    }
    /** Recognition past a missing hat is the observer's intellect (headwear.Recognition). */
    public static boolean rejects(YukkuriEntity observer, YukkuriEntity target) {
        return sameGroup(observer, target) && accepted(observer) && harsh(observer)
                && !acceptsHatless(observer, target) && !observer.life().canRecognize(target);
    }
    public static boolean avoids(YukkuriEntity observer, YukkuriEntity target) {
        if (!sameGroup(observer, target)) return false;
        if (rejects(observer, target)) return true;
        var record = target.settlement().society().record();
        return record != null && observer.getUUID().equals(record.observer)
                && record.rememberedUntil > observer.level().getGameTime();
    }
    public void tick() {
        if (entity.tickCount % 20 == 0 && entity.isAlive() && entity.settlement().group() != null) comfortSlave();
        if (entity.tickCount % 100 != 0 || !entity.isAlive()) return;
        var group = entity.settlement().group();
        if (group == null) return;
        // The target need not be free to act: nearby witnesses can reject a hatless member even while it eats, sleeps or panics.
        if (available(entity)) LeaderSuccession.observe(entity, group);
        var data = SettlementData.get(level());
        var record = data.member(group, entity.getUUID());
        long now = level().getGameTime();
        // Handles the original accessory being returned while transported to another dimension.
        if (entity.life().hasAccessory() && record.rejections > 0) { restored("accessory"); return; }
        if (now < record.nextObservation) return;
        var box = entity.getBoundingBox().inflate(6);
        // A slave already lives at the bottom of the pit: only real contact from a gentle member (kindContact) changes its standing;
        // the harsh witnesses below are what put a member there.
        if (record.role == SettlementData.Role.UNUN_SLAVE) return;
        // Sustained kindness is durable colony acceptance itself (`Member.hatlessAccepted`): there is no second timer for it.
        if (record.hatlessAccepted) return;
        var observers = LoadedGroupMembers.query(level(), group.id, box,
                peer -> available(peer) && rejects(peer, entity) && peer.hasLineOfSight(entity)
                        && !(peer.getUUID().equals(record.observer) && now - (record.nextObservation - REJECT_GAP) < ENCOUNTER_GAP));
        var observer = LoadedGroupMembers.minimum(level(), box, observers, entity::distanceToSqr);
        if (observer == null) return;
        record.nextObservation = now + REJECT_GAP; record.observer = observer.getUUID();
        int before = record.rejections;
        record.rejections = Math.min(5, before + 1);
        if (before < 5) {
            YukkuriWorldData.get(level()).relate(observer.getUUID(), entity.getUUID(), -2);
            entity.life().personality().decided("settlement_rejected");
            GameplayLog.event(entity, "settlement", "hatless rejected rejections={} observer={} role={}", record.rejections,
                    observer.getUUID(), record.role);
        }
        if (record.rejections >= 2 && accepted(entity)) record.role = SettlementData.Role.SHUNNED;
        if (record.rejections >= 5 && record.role == SettlementData.Role.SHUNNED) {
            var p = entity.life().personality();
            // These thresholds are project design. Attachment alone is never equated with obedience.
            boolean docile = entity.family().adult() && p.friendliness() >= 60 && p.selfControl() >= Temperament.SELF_CONTROL_AT_50
                    && p.attachment() >= Temperament.ATTACHMENT_AT_55;
            // A colony keeps one 嗯嗯奴隶 (project design, feedback 4): its place in the pit is single, the next one is driven off.
            boolean taken = data.hasSlave(group, entity.getUUID());
            record.role = docile && !taken ? SettlementData.Role.UNUN_SLAVE : SettlementData.Role.OUTCAST;
            if (before < 5) {
                CommunityRules.hatlessOutcome(entity, group, record.role);
                GameplayLog.event(entity, "settlement", "hatless outcome role={} rejections={} observer={} docile={} slave_place_taken={}",
                        record.role, record.rejections, observer.getUUID(), docile, taken);
            }
        }
        String event = record.role == SettlementData.Role.OUTCAST ? "settlement_drive"
                : record.role == SettlementData.Role.UNUN_SLAVE ? "settlement_assign" : "settlement_shun";
        // An assignment is a bounded order by a real member, never remote group omniscience.
        if (record.role == SettlementData.Role.UNUN_SLAVE) {
            data.assign(group,record,observer.getUUID(),now+1200);
        }
        observer.getLookControl().setLookAt(entity, 25, 25);
        observer.showReaction("angry", event, 80);
        observer.mind().perform("puff", 60);
        if (record.role == SettlementData.Role.UNUN_SLAVE) entity.mind().feltSlavePressure(EmotionEvent.SUBMITTED);
        else entity.mind().felt(EmotionEvent.SOCIAL_HURT);
        entity.showReaction("teary", record.role == SettlementData.Role.UNUN_SLAVE ? "settlement_submit" : "settlement_hurt", 100);
        // The rejected one withdraws from the one who turned on it (a slave is sent to the pit instead).
        if (record.role != SettlementData.Role.UNUN_SLAVE) entity.frightenFrom(observer);
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
            slave.mind().feltSlavePressure(EmotionEvent.SUBMITTED);
            slave.showReaction("teary", "settlement_submit", 100);
            GameplayLog.event(slave, "settlement", "pit mockery witnesses={} excretor={} hygiene={} -> {}", witnesses.size(), entity.getUUID(),
                    before, slave.life().hygiene());
        }
    }

    /**
     * A gentle, recognising member that finds the group's slave beside it at the pit (typically just after its own relief there) goes over
     * and nuzzles it (the shared nuzzle session); each finished nuzzle is one kind treatment (kindContact).
     */
    private void comfortSlave() {
        if (!Temperament.lenient(entity.life().personality()) || !accepted(entity) || !available(entity)) return;
        var group = entity.settlement().group();
        var box = entity.getBoundingBox().inflate(8);
        var slave = LoadedGroupMembers.minimum(level(), box, LoadedGroupMembers.query(level(), group.id, box,
                peer -> peer.settlement().society().slave() && entity.life().canRecognize(peer) && entity.hasLineOfSight(peer)),
                entity::distanceToSqr);
        if (slave != null) entity.dailyLife().console(slave);
    }

    /** A gentle member's nuzzle with this slave has finished. */
    public void kindContact(YukkuriEntity member) {
        var record = record();
        record.gentleness++;
        YukkuriWorldData.get(level()).relate(member.getUUID(), entity.getUUID(), 2);
        entity.mind().felt(EmotionEvent.COMFORTED);
        entity.showReaction("teary", "gentle", 100);
        GameplayLog.event(entity, "settlement", "pit slave treated by={} gentleness={}/{}", member.getUUID(), record.gentleness,
                SettlementData.GENTLE_TREATMENTS);
        if (record.gentleness >= SettlementData.GENTLE_TREATMENTS) restored("gentle_treatment");
        SettlementData.get(level()).setDirty();
    }

    public void restored(String cause) {
        var group = entity.settlement().group();
        if (group == null) return;
        var record = record();
        if (record.rejections == 0) return;
        int gentleness = record.gentleness;
        SettlementData.get(level()).restore(group, entity.getUUID(), level().getGameTime(), cause.equals("gentle_treatment"));
        entity.settlement().duties().cancelAssignment();
        entity.life().personality().decided("settlement_restored");
        GameplayLog.event(entity, "settlement", "hatless outcome role={} cause={} gentleness={}", role(), cause, gentleness);
        // No affinity reset: the actual prior interactions remain in YukkuriWorldData.
    }
    public boolean dejected() {
        var role = role();
        return role == SettlementData.Role.SHUNNED || role == SettlementData.Role.OUTCAST || role == SettlementData.Role.UNUN_SLAVE;
    }
}
