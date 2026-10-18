package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The communal hunt (共同狩猎) of a led community, called by the leader on its own (ruling C10: no player orders).
 * Lecture basis: komicolle 40720 No.11518859 ("the group elects a leader and hunts together"), No.11518847 (fathers
 * look for food, mothers raise the young; yukkuri grandly call all gathering "hunting"; parents may take grown children
 * along). "Hunting" therefore is real gathering of meadow plants ({@link Grazing}), the same work as a family trip.
 *
 * <p>Project design: on a fair morning (day time 1000-6000, in famine until 7500) when the common store is below about
 * three days of the community's rations, the leader calls the adults within {@value #CALL_RANGE} blocks. Able adults
 * come (pregnant members, elders and, outside famine, parents of babies on mash stay home); a defiant one (low
 * attachment, selfish, little self-control) refuses, which the leader judges as {@link CommunityRules.Offence#DEFIANCE}.
 * The party gathers at the centre, walks out behind the leader to a meadow {@code radius} blocks away, each member
 * gathers a load of up to {@link ForageTrip#FATHER_LOAD} real portions, and carries it into the common store. One hunt a
 * day, two in famine. In such a community fathers no longer make their own routine trips (the store feeds the
 * households through {@link Rations}), except in famine; mothers still go out when their own store is empty.
 *
 * <p>The movement itself is {@link ForageTrip} (roles CHIEF and HUNTER); this class only coordinates. The hunt state is
 * transient; finished hunts are recorded in {@link Commons#hunts}.
 */
public final class CommonHunt {
    public static final int MAX_PARTY = 6, CALL_RANGE = 32, MUSTER_TICKS = 600, HUNT_TICKS = 4800, RETRY_TICKS = 600;
    static final int RADIUS = 32, FAMINE_RADIUS = 48;

    /** The community organises hunts: rules, an active leader and a common store. */
    public static boolean organised(ServerLevel level, SettlementData.Group group) {
        return CommunityRules.ruled(group) && SettlementData.get(level).activeLeader(group) != null && !CommonStore.piles(level, group).isEmpty();
    }
    public static boolean organised(YukkuriEntity entity) {
        return entity.level() instanceof ServerLevel level && organised(level, entity.settlement().group());
    }
    /** A father's own routine trip yields to the communal hunt, except in famine. */
    public static boolean familyTripsYield(YukkuriEntity entity) {
        var group = entity.settlement().group();
        return group != null && group.commons.famine < 2 && organised(entity);
    }

    public static Commons.Hunt of(YukkuriEntity entity) {
        var group = entity.settlement().group();
        return group == null ? null : group.commons.hunt;
    }
    /** The hunt this member belongs to and has not finished yet (mustering or out), or null. */
    public static Commons.Hunt drafted(YukkuriEntity entity) {
        var hunt = of(entity);
        if (hunt == null || hunt.phase == Commons.HuntPhase.IDLE || hunt.phase == Commons.HuntPhase.RETURN) return null;
        UUID id = entity.getUUID();
        return hunt.party.contains(id) && !hunt.done.contains(id) ? hunt : null;
    }
    static YukkuriEntity chief(YukkuriEntity member) {
        var hunt = of(member);
        if (hunt == null || hunt.chief == null || member.getUUID().equals(hunt.chief)) return null;
        return member.level().getEntity(hunt.chief) instanceof YukkuriEntity chief && chief.isAlive() ? chief : null;
    }

    // ---- reports from the trips ----------------------------------------------------------------------------------
    static void delivered(YukkuriEntity member, int portions) {
        var hunt = of(member);
        if (hunt != null && hunt.phase != Commons.HuntPhase.IDLE && hunt.party.contains(member.getUUID())) hunt.delivered += portions;
    }
    static void done(YukkuriEntity member, String why) {
        var hunt = of(member);
        if (hunt != null && hunt.party.contains(member.getUUID())) hunt.done.add(member.getUUID());
    }
    /** The leader turned back (full, barren, dusk, rain...): everybody comes home. */
    static void chiefRecall(YukkuriEntity chief, String why) {
        var hunt = of(chief);
        if (hunt != null && chief.getUUID().equals(hunt.chief) && (hunt.phase == Commons.HuntPhase.OUT || hunt.phase == Commons.HuntPhase.MUSTER)) {
            hunt.phase = Commons.HuntPhase.RETURN;
            hunt.outcome = why;
        }
    }

    // ---- leader side, about every 5 s ------------------------------------------------------------------------------
    static void tickLeader(YukkuriEntity leader, SettlementData.Group group) {
        if (!(leader.level() instanceof ServerLevel level)) return;
        var hunt = group.commons.hunt;
        long now = level.getGameTime(), day = level.getDayTime() / 24000;
        if (hunt.day != day) { hunt.day = day; hunt.today = 0; }
        switch (hunt.phase) {
            case IDLE -> { if (now - hunt.calledAt >= RETRY_TICKS) call(leader, group, false); }
            case MUSTER -> muster(level, group, leader, now);
            case OUT, RETURN -> {
                boolean over = true;
                for (UUID id : hunt.party) {
                    if (hunt.done.contains(id)) continue;
                    if (level.getEntity(id) instanceof YukkuriEntity m && m.isAlive() && m.forageTrip() != null && m.forageTrip().hunting()) { over = false; break; }
                }
                if (over || now > hunt.deadline + 2400) finish(level, group, leader, over ? (hunt.outcome.isEmpty() ? "home" : hunt.outcome) : "overdue");
            }
        }
    }

    private static boolean able(YukkuriEntity m, int famine) {
        if (m.forageTrip() == null || !m.forageTrip().able() || m.forageTrip().outing()) return false;
        if (m.family().elder() || CommunityRules.pregnant(m)) return false;
        if (m.life().nutrition() < 35 || m.life().energy() < 40 || m.getHealth() < m.getMaxHealth() * .6f || m.life().hasUrgentNeed()) return false;
        if (famine < 2 && hasBaby(m)) return false;
        return true;
    }
    private static boolean hasBaby(YukkuriEntity parent) {
        var level = (ServerLevel)parent.level();
        for (UUID id : FamilyData.get(level).children(parent.getUUID()))
            if (level.getEntity(id) instanceof YukkuriEntity child && child.isAlive() && child.family().needsMash()) return true;
        return false;
    }
    private static boolean defiant(YukkuriEntity m) {
        var p = m.life().personality();
        return p.attachment() < 40 && p.selfishness() >= 60 && p.selfControl() < 50;
    }
    private static int target(ServerLevel level, SettlementData.Group group) {
        int mouths = level.getEntitiesOfClass(YukkuriEntity.class, new AABB(group.center).inflate(64), e -> e.isAlive() && group.id.equals(e.settlement().groupId())).size();
        int wanted = (int)Math.ceil(mouths * Rations.PER_MOUTH * 3);
        return Math.min(wanted, (int)(CommonStore.capacity(level, group) * .8));
    }

    /**
     * The leader calls a hunt. Without {@code force} the time, weather, store and daily limit apply; with it (test
     * command) only the people and the meadow must be there. Returns null on success, otherwise the reason.
     */
    public static String call(YukkuriEntity leader, SettlementData.Group group, boolean force) {
        if (!(leader.level() instanceof ServerLevel level)) return "client";
        var hunt = group.commons.hunt;
        long now = level.getGameTime(), time = level.getDayTime() % 24000;
        int famine = group.commons.famine;
        if (hunt.phase != Commons.HuntPhase.IDLE) return "already_" + hunt.phase.name().toLowerCase(java.util.Locale.ROOT);
        if (!organised(level, group)) return "not_organised";
        if (Migration.moving(group)) return "migrating";
        hunt.calledAt = now;
        if (!force) {
            if (time < 1000 || time >= (famine >= 2 ? 7500 : 6000) || level.isRaining() || level.isThundering()) return "not_now";
            if (hunt.today >= (famine >= 2 ? 2 : 1)) return "done_today";
            if (famine < 2 && CommonStore.stock(level, group) >= target(level, group)) return "store_full";
            if (Seasons.confined(leader)) return "confined";
        }
        if (!able(leader, 2)) return "leader_unable";
        int radius = famine >= 2 ? FAMINE_RADIUS : RADIUS;
        var dest = leader.forageTrip().destinationFrom(group.center, radius);
        if (dest == null) return "no_meadow";
        var candidates = level.getEntitiesOfClass(YukkuriEntity.class, leader.getBoundingBox().inflate(CALL_RANGE), m -> m != leader && m.isAlive()
                && group.id.equals(m.settlement().groupId()) && group.role(m.getUUID()) == SettlementData.Role.MEMBER && m.family().adult()
                && !m.life().isSleeping() && m.life().canRecognize(leader));
        candidates.sort(Comparator.comparingDouble(leader::distanceToSqr));
        var party = new ArrayList<YukkuriEntity>();
        hunt.defied.clear();
        for (var m : candidates) {
            if (party.size() >= MAX_PARTY - 1) break;
            if (!able(m, famine)) continue;
            if (defiant(m)) {
                hunt.defied.add(m.getUUID());
                m.showReaction("angry", "hunt_refuse", 60);
                continue;
            }
            party.add(m);
        }
        for (UUID id : hunt.defied) if (level.getEntity(id) instanceof YukkuriEntity m)
            CommunityRules.report(m, group, CommunityRules.Offence.DEFIANCE, leader, "refused_hunt");
        if (party.isEmpty()) {
            group.commons.note(now, "hunt_no_party", leader.getUUID(), "candidates " + candidates.size() + " defied " + hunt.defied.size());
            return "no_hunters";
        }
        hunt.phase = Commons.HuntPhase.MUSTER;
        hunt.chief = leader.getUUID();
        hunt.muster = group.center;
        hunt.dest = dest;
        hunt.radius = radius;
        hunt.departAt = now + MUSTER_TICKS;
        hunt.deadline = hunt.departAt + HUNT_TICKS;
        hunt.party.clear(); hunt.done.clear(); hunt.delivered = 0; hunt.outcome = "";
        hunt.party.add(leader.getUUID());
        for (var m : party) { hunt.party.add(m.getUUID()); m.showReaction("happy", "hunt_join", 60); }
        leader.showReaction("happy", "hunt_call", 80);
        group.commons.note(now, "hunt_call", leader.getUUID(), "party " + hunt.party.size() + " dest " + dest.toShortString() + " defied " + hunt.defied.size()
                + (famine >= 2 ? " famine" : ""));
        SettlementData.get(level).setDirty();
        return null;
    }

    /** Leave once everybody stands at the gathering point or the muster time is up; too few makes the leader give up. */
    private static void muster(ServerLevel level, SettlementData.Group group, YukkuriEntity leader, long now) {
        var hunt = group.commons.hunt;
        var here = new ArrayList<UUID>();
        boolean all = true;
        for (UUID id : hunt.party) {
            if (level.getEntity(id) instanceof YukkuriEntity m && m.isAlive() && m.distanceToSqr(Vec3.atBottomCenterOf(hunt.muster)) <= 6 * 6) here.add(id);
            else all = false;
        }
        if (!all && now < hunt.departAt) return;
        // The absent are excused (busy with a need); only those who refused at the call were defiant.
        hunt.party.retainAll(here);
        if (hunt.party.size() < 2 || !hunt.party.contains(hunt.chief)) { finish(level, group, leader, "too_few"); return; }
        hunt.phase = Commons.HuntPhase.OUT;
        hunt.deadline = now + HUNT_TICKS;
        group.commons.note(now, "hunt_depart", leader.getUUID(), "party " + hunt.party.size());
    }

    private static void finish(ServerLevel level, SettlementData.Group group, YukkuriEntity leader, String outcome) {
        var hunt = group.commons.hunt;
        long now = level.getGameTime();
        var record = new CompoundTag();
        record.putLong("Day", level.getDayTime() / 24000);
        record.putLong("Called", hunt.departAt - MUSTER_TICKS);
        record.putLong("Ended", now);
        record.putInt("Party", hunt.party.size());
        record.putInt("Defied", hunt.defied.size());
        record.putInt("Delivered", hunt.delivered);
        record.putInt("Radius", hunt.radius);
        if (hunt.dest != null) record.putString("Dest", hunt.dest.toShortString());
        record.putString("Outcome", outcome);
        if (hunt.chief != null) record.putUUID("Chief", hunt.chief);
        group.commons.huntRecord(record);
        group.commons.note(now, "hunt_end", hunt.chief, "party " + hunt.party.size() + " delivered " + hunt.delivered + " " + outcome);
        if (!"too_few".equals(outcome)) hunt.today++;
        if (hunt.delivered > 0 && leader != null) leader.showReaction("happy", "hunt_back", 80);
        hunt.phase = Commons.HuntPhase.IDLE;
        hunt.party.clear(); hunt.done.clear(); hunt.defied.clear();
        hunt.calledAt = now;
        SettlementData.get(level).setDirty();
    }

    private CommonHunt() {}
}
