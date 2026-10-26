package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.social.Temperament;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import java.util.Locale;

/**
 * The communal hunt (共同狩猎) of a led community, called by the leader on its own (ruling C10: no player orders).
 * Lecture basis: komicolle 40720 No.11518859 ("the group elects a leader and hunts together"), No.11518847 (fathers
 * look for food, mothers raise the young; yukkuri grandly call all gathering "hunting"; parents may take grown children
 * along). "Hunting" therefore is real gathering of meadow plants ({@link Grazing}), the same work as a family trip.
 *
 * <p>Project design: on a fair morning (day time 1000-6000, in famine until 7500) when the common store is below its
 * target ({@link Rations#storeTarget}), the leader calls the adults within {@value #CALL_RANGE} blocks. Able adults
 * come (pregnant members, elders and, outside famine, parents of babies on mash stay home); a defiant one (low
 * attachment, selfish, little self-control) refuses, which the leader judges as {@link CommunityRules.Offence#DEFIANCE}.
 * The party gathers at the centre, walks out behind the leader to a meadow {@code radius} blocks away, each member
 * gathers a load of up to {@link ForageTrip#FATHER_LOAD} real portions, carries the levy (a third, {@link Levy}) into the
 * common store and the rest home to its own household. One hunt a day, two in famine. Family trips go on as usual (user
 * correction 2026-10-18: the households live on their own foraging, the commons only on the levy), and they pay a
 * smaller levy too.
 *
 * <p>The movement itself is {@link ForageTrip} (roles CHIEF and HUNTER); this class only coordinates. The hunt state is
 * transient; finished hunts are recorded in {@link Commons#hunts}.
 */
public final class CommonHunt {
    public static final int MAX_PARTY = 6, CALL_RANGE = 32, MUSTER_TICKS = 600, HUNT_TICKS = 9600, RETRY_TICKS = 600;
    static final int RADIUS = 64, FAMINE_RADIUS = 96;

    /** The community organises hunts: rules, an active leader and a common store. */
    public static boolean organised(ServerLevel level, SettlementData.Group group) {
        return CommunityRules.ruled(group) && SettlementData.get(level).activeLeader(group) != null && !CommonStore.piles(level, group).isEmpty();
    }
    public static boolean organised(YukkuriEntity entity) {
        return entity.level() instanceof ServerLevel level && organised(level, entity.settlement().group());
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
        return member.level() instanceof ServerLevel level && level.getEntity(hunt.chief) instanceof YukkuriEntity chief && chief.isAlive() ? chief : null;
    }

    // ---- reports from the trips ----------------------------------------------------------------------------------
    static void delivered(YukkuriEntity member, int portions) {
        var hunt = of(member);
        if (hunt != null && hunt.phase != Commons.HuntPhase.IDLE && hunt.party.contains(member.getUUID())) hunt.delivered += portions;
    }
    /** What a hunter carries home after paying the levy. */
    static void kept(YukkuriEntity member, int portions) {
        var hunt = of(member);
        if (hunt != null && hunt.phase != Commons.HuntPhase.IDLE && hunt.party.contains(member.getUUID())) hunt.kept += portions;
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
        if (hunt.phase == Commons.HuntPhase.IDLE && now - hunt.calledAt >= RETRY_TICKS) call(leader, group, false);
    }

    /** The group keeps its real party and cargo accounting even when the caller of the hunt is lost. */
    static void advance(ServerLevel level, SettlementData.Group group) {
        var hunt = group.commons.hunt;
        if (hunt.phase == Commons.HuntPhase.IDLE) return;
        long now = level.getGameTime();
        var chief = hunt.chief != null && level.getEntity(hunt.chief) instanceof YukkuriEntity y && y.isAlive() ? y : null;
        var known = hunt.chief == null ? null : FamilyData.get(level).person(hunt.chief);
        boolean lost = hunt.chief == null || known != null && (known.status == FamilyData.Status.DEAD || known.status == FamilyData.Status.REMOVED)
                || chief != null && (!group.id.equals(chief.settlement().groupId()) || !hunt.chief.equals(group.leader));
        boolean unable = chief != null && (chief.isNoAi() || chief.isPanicking() || chief.life().isSleeping()
                || chief.life().hasUrgentNeed() || chief.isCommandPlaying() || chief.operations().active());
        boolean missing = chief == null && now >= (hunt.phase == Commons.HuntPhase.MUSTER ? hunt.departAt : hunt.deadline);
        if (hunt.phase != Commons.HuntPhase.RETURN && (lost || unable || missing || now >= hunt.deadline)) {
            hunt.phase = Commons.HuntPhase.RETURN;
            hunt.outcome = lost ? "leader_lost" : unable ? "leader_unable" : missing ? "leader_absent" : "late";
            hunt.deadline = Math.min(hunt.deadline, now);
            group.commons.note(now, "hunt_recall", hunt.chief, hunt.outcome + " party " + hunt.party.size());
            SettlementData.get(level).setDirty();
        }
        if (hunt.phase == Commons.HuntPhase.MUSTER) {
            if (chief != null) muster(level, group, chief, now);
            return;
        }
        boolean over = true;
        for (UUID id : hunt.party) {
            if (hunt.done.contains(id)) continue;
            var person = FamilyData.get(level).person(id);
            if (person != null && (person.status == FamilyData.Status.DEAD || person.status == FamilyData.Status.REMOVED)) continue;
            // OUT may precede a drafted member's first Goal tick. Only its done report ends that participation.
            // On recall an idle loaded member has no trip to return; an unloaded one may still carry food.
            if (hunt.phase == Commons.HuntPhase.OUT || !(level.getEntity(id) instanceof YukkuriEntity member)
                    || member.forageTrip().hunting()) { over = false; break; }
        }
        if (over || now > hunt.deadline + 2400)
            finish(level, group, chief, over ? (hunt.outcome.isEmpty() ? "home" : hunt.outcome) : "return_overdue:" + hunt.outcome);
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
        return m.getRandom().nextFloat() >= p.helpProbability(true, false);
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
        if (hunt.phase != Commons.HuntPhase.IDLE) return "already_" + hunt.phase.name().toLowerCase(Locale.ROOT);
        if (!organised(level, group)) return "not_organised";
        if (Migration.moving(group)) return "migrating";
        hunt.calledAt = now;
        if (!force) {
            if (time < 1000 || time >= (famine >= 2 ? 7500 : 6000) || level.isRaining() || level.isThundering()) return "not_now";
            if (hunt.today >= (famine >= 2 ? 2 : 1)) return "done_today";
            if (famine < 2 && CommonStore.stock(level, group) >= Rations.storeTarget(level, group)) return "store_full";
            if (SettlementSeasons.confined(leader)) return "confined";
        }
        if (!able(leader, 2)) return "leader_unable";
        int radius = famine >= 2 ? FAMINE_RADIUS : RADIUS;
        var dest = leader.forageTrip().destinationFrom(group.center, radius);
        if (dest == null) {
            // No meadow left within reach: once a day this counts as a failed hunt (a worn-out territory, see Migration).
            long day = level.getDayTime() / 24000;
            if (!force && group.commons.noMeadowDay != day) {
                group.commons.noMeadowDay = day; group.commons.huntFails++; group.commons.outing(false);
                group.commons.note(now, "hunt_no_meadow", leader.getUUID(), "radius " + radius + " fails " + group.commons.huntFails);
            }
            return "no_meadow";
        }
        var box = leader.getBoundingBox().inflate(CALL_RANGE);
        var nearby = LoadedGroupMembers.query(level, group.id, box, m -> m != leader && m.isAlive()
                && group.role(m.getUUID()) == SettlementData.Role.MEMBER && m.family().adult() && !m.life().isSleeping());
        // Conservative fallback before able/EventHooks or refusal side effects; never pre-run those to narrow ties.
        if (LoadedGroupMembers.hasTies(nearby, leader::distanceToSqr)) nearby = LoadedGroupMembers.encounterOrder(level, box, nearby);
        var candidates = new ArrayList<YukkuriEntity>();
        for (var m : nearby) if (m.life().canRecognize(leader)) candidates.add(m);
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
        hunt.departed = false;
        hunt.chief = leader.getUUID();
        hunt.muster = group.center;
        hunt.dest = dest;
        hunt.radius = radius;
        hunt.departAt = now + MUSTER_TICKS;
        hunt.deadline = hunt.departAt + HUNT_TICKS;
        hunt.party.clear(); hunt.done.clear(); hunt.delivered = 0; hunt.kept = 0; hunt.outcome = "";
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
        hunt.departed = true;
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
        record.putInt("Kept", hunt.kept);
        record.putInt("Radius", hunt.radius);
        if (hunt.dest != null) record.putString("Dest", hunt.dest.toShortString());
        record.putString("Outcome", outcome);
        record.putBoolean("Departed", hunt.departed);
        if (hunt.chief != null) record.putUUID("Chief", hunt.chief);
        group.commons.huntRecord(record);
        group.commons.note(now, "hunt_end", hunt.chief, "party " + hunt.party.size() + " levy " + hunt.delivered + " kept " + hunt.kept + " " + outcome);
        if (hunt.departed) {
            hunt.today++;
            boolean fruitful = hunt.delivered + hunt.kept > 0;
            group.commons.outing(fruitful);
            group.commons.huntFails = fruitful ? 0 : group.commons.huntFails + 1;
        }
        if (hunt.delivered + hunt.kept > 0 && leader != null) leader.showReaction("happy", "hunt_back", 80);
        hunt.phase = Commons.HuntPhase.IDLE;
        hunt.party.clear(); hunt.done.clear(); hunt.defied.clear();
        hunt.calledAt = now;
        SettlementData.get(level).setDirty();
    }

    private CommonHunt() {}
}
