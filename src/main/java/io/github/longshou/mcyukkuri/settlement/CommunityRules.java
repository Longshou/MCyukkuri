package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.social.Temperament;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Community rules (族规), judgement and punishment (package 12).
 *
 * <p>Lecture basis: komicolle 40720 No.11518859 (a community elects a leader; rules forbid eating one's own kind and limit
 * births, "to avoid unfairness and self-destruction"), No.11518794 (without proper rules yukkuri breed until the stores
 * are gone and many break the rules), No.11518829 (whatever "cannot take it easy" is sanctioned; whether one can take it
 * easy depends on having an intact accessory; the sanction is bullying, not killing, user correction 2026-10-18), No.11518847 (eating one's own kind is forbidden but not rare:
 * without its accessory the victim is not recognised as kin). Ruling C10: the leader organises, nobody is given orders by
 * the player; C11: eating or killing an unrecognised (hatless) individual is not a kin offence.
 *
 * <p>Project design (numbers, ladder and witnesses): only a community with a leader office has rules; an offence counts
 * only when a real member saw it (awake, within range, line of sight, recognising the offender); the leader judges when
 * the offender is in front of it, or after a while in its absence. Severity = the offence's base + earlier offences of
 * the last three days (+1 for theft in famine, +1 under a harsh leader, -1 under a lenient one):
 * 1 rebuke, 2 ration cut for two days, 3 expulsion for three days, 4 expulsion plus bullying ({@link Sanction}); a return
 * is judged again, so the bullying repeats for as long as the offender keeps coming back.
 * Every punishment raises the offender's unease ({@code mind().add}); witnesses and the expelled one's family feel it too.
 */
public final class CommunityRules {
    public enum Offence {
        THEFT("theft", 1, Commons.NO_THEFT), DEFIANCE("defiance", 1, Commons.OBEY_CALL),
        ILLICIT_BIRTH("illicit_birth", 2, Commons.BIRTH_LIMIT), HATLESS("hatless", 2, Commons.NO_HATLESS),
        RETURNED_EXILE("returned_exile", 3, Commons.OBEY_CALL),
        KIN_KILL("kin_kill", 4, Commons.NO_KIN_KILL), CANNIBAL("cannibal", 4, Commons.NO_CANNIBAL);
        public final String id; public final int base, rule;
        Offence(String id, int base, int rule) { this.id = id; this.base = base; this.rule = rule; }
        public static Offence byId(String id) {
            for (var o : values()) if (o.id.equals(id)) return o;
            return null;
        }
    }
    public enum Punishment {
        NONE, REBUKE, RATION_CUT, EXPEL, SANCTION;
        public String id() { return name().toLowerCase(Locale.ROOT); }
        static Punishment of(int score) { return score <= 1 ? REBUKE : score == 2 ? RATION_CUT : score == 3 ? EXPEL : SANCTION; }
    }

    /** [original] distances and durations. */
    static final double WITNESS_RANGE = 10, JUDGE_RANGE = 24, PATROL_RANGE = 8, TERRITORY = 40;
    public static final long RATION_CUT_TICKS = 48000, BANISH_TICKS = 72000, MEMORY_TICKS = 72000;
    static final long ABSENT_AFTER = 2400, LAPSE_AFTER = 24000, INTRUSION_GAP = 600, COURTSHIP_MEMORY = 48000;
    private static final int PENDING_MAX = 16;

    // ---- who has rules ------------------------------------------------------------------------------------------
    /** A forest community with a leader office keeps rules (also during a vacancy); leaderless groups and families have none. */
    public static boolean ruled(SettlementData.Group group) {
        return group != null && group.kind == SettlementData.Kind.FOREST && group.hasLeaderOffice;
    }
    public static boolean rule(SettlementData.Group group, int bit) { return ruled(group) && group.commons.has(bit); }
    static YukkuriEntity leader(ServerLevel level, SettlementData.Group group) {
        UUID id = SettlementData.get(level).activeLeader(group);
        return id != null && level.getEntity(id) instanceof YukkuriEntity y && y.isAlive() ? y : null;
    }
    private static boolean wild(YukkuriEntity entity) {
        var human = Custody.view(entity);
        return human.wild();
    }

    // ---- detection ----------------------------------------------------------------------------------------------
    /** The best real witness of {@code offender}: an accepted, awake member who sees and recognises it; the leader first. */
    public static YukkuriEntity witness(YukkuriEntity offender, SettlementData.Group group, double range) {
        if (group == null) return null;
        UUID leaderId = group.leader;
        var box = offender.getBoundingBox().inflate(range);
        Predicate<YukkuriEntity> eligible = p -> p != offender && p.isAlive()
                        && !p.isNoAi() && group.id.equals(p.settlement().groupId()) && SettlementSociety.accepted(p) && !p.life().isSleeping()
                        && p.hasLineOfSight(offender) && p.life().canRecognize(offender);
        ToDoubleFunction<YukkuriEntity> score = p -> (p.getUUID().equals(leaderId) ? -10000 : 0) + p.distanceToSqr(offender);
        if (!(offender.level() instanceof ServerLevel level))
            return offender.level().getEntitiesOfClass(YukkuriEntity.class, box, eligible).stream().min(Comparator.comparingDouble(score)).orElse(null);
        return LoadedGroupMembers.minimum(level, box, LoadedGroupMembers.query(level, group.id, box, eligible), score);
    }

    /** Detector entry: looks for a witness in the offender's own community; an unseen offence is only logged. */
    public static boolean observed(YukkuriEntity offender, Offence offence, String detail) {
        if (!(offender.level() instanceof ServerLevel level)) return false;
        var group = offender.settlement().group();
        if (!rule(group, offence.rule)) return false;
        var seen = witness(offender, group, WITNESS_RANGE);
        if (seen == null) {
            group.commons.note(level.getGameTime(), "unseen_" + offence.id, offender.getUUID(), detail);
            SettlementData.get(level).setDirty();
            return false;
        }
        return report(offender, group, offence, seen, detail);
    }

    /**
     * Public entry for every detector (this package, and others such as cannibalism): {@code witness} really saw
     * {@code offender} commit {@code offence} against the rules of {@code group}. The leader judges it later
     * ({@link #tickLeader}). Returns true when the offence was recorded.
     */
    public static boolean report(YukkuriEntity offender, SettlementData.Group group, Offence offence, YukkuriEntity witness, String detail) {
        if (!(offender.level() instanceof ServerLevel level) || !rule(group, offence.rule)) return false;
        var commons = group.commons;
        long now = level.getGameTime();
        if (offender.getUUID().equals(group.leader)) {
            // Nobody judges the leader: the members only feel the unfairness.
            commons.note(now, "unjudged_" + offence.id, offender.getUUID(), detail);
            if (witness != null) witness.mind().add(3, "unfair_leader");
            SettlementData.get(level).setDirty();
            return false;
        }
        for (var p : commons.pending) if (p.offender.equals(offender.getUUID()) && p.offence.equals(offence.id) && now - p.time < 600) return false;
        if (commons.pending.size() >= PENDING_MAX) commons.removeFirstPending();
        var p = new Commons.Pending();
        p.offender = offender.getUUID(); p.witness = witness == null ? null : witness.getUUID();
        p.offence = offence.id; p.detail = detail; p.time = now;
        commons.addPending(p);
        commons.note(now, "offence_" + offence.id, offender.getUUID(), (detail == null ? "" : detail) + (witness == null ? "" : " seen_by " + witness.getUUID().toString().substring(0, 8)));
        if (witness != null) {
            witness.getLookControl().setLookAt(offender, 25, 25);
            witness.showReaction("angry", "heard", 60);
            witness.mind().add(offence.base >= 4 ? 10 : 2, "rule_broken");
        }
        SettlementData.get(level).setDirty();
        return true;
    }

    // ---- judgement -----------------------------------------------------------------------------------------------
    static void tickLeader(YukkuriEntity leader, SettlementData.Group group) {
        if (leader.level() instanceof ServerLevel level) Judgement.lapse(level, group);
    }

    /** The sentence the leader would give now (also used by the test command). */
    public static Punishment sentence(SettlementData.Group group, YukkuriEntity offender, Offence offence, YukkuriEntity leader, long now) {
        var s = group.commons.existing(offender.getUUID());
        int prior = s == null || now - s.lastOffence > MEMORY_TICKS ? 0 : Math.min(2, s.offences);
        int score = offence.base + prior;
        if (offence == Offence.THEFT && group.commons.famine >= 2) score++;
        if (leader != null) {
            var p = leader.life().personality();
            if (p.friendliness() <= 35) score++;
            else if (Temperament.lenient(p) && offence.base <= 2) score--;
        }
        // The young are scolded and go hungry, but are not driven out or killed for their own deeds.
        if (!offender.family().adult()) score = Math.min(score, 2);
        return Punishment.of(Math.max(1, score));
    }

    /** Applies the leader's judgement now. {@code present}: the offender stands in front of the leader. */
    public static Punishment judge(ServerLevel level, SettlementData.Group group, YukkuriEntity leader, YukkuriEntity offender, Offence offence, String detail, boolean present) {
        long now = level.getGameTime();
        var commons = group.commons;
        var punishment = sentence(group, offender, offence, leader, now);
        var s = commons.standing(offender.getUUID());
        if (now - s.lastOffence > MEMORY_TICKS) s.offences = 0;
        s.offences++; s.lastOffence = now;
        if (present && leader != null) leader.getLookControl().setLookAt(offender, 25, 25);
        switch (punishment) {
            case REBUKE -> rebuke(leader, offender, present);
            case RATION_CUT -> {
                rebuke(leader, offender, present);
                s.rationCutUntil = now + RATION_CUT_TICKS;
                offender.mind().add(10, "ration_cut");
            }
            case EXPEL -> exile(offender, group, leader, now, offence.id);
            case SANCTION -> {
                // Bullied by members of its own community first (they are drawn while it still belongs), then expelled.
                boolean taken = Sanction.punish(offender, leader, Sanction.SANCTION, offence.id);
                commons.note(now, taken ? "sanction" : "sanction_deferred", offender.getUUID(), offence.id);
                exile(offender, group, leader, now, offence.id);
            }
            default -> { }
        }
        commons.note(now, "judged_" + punishment.id(), offender.getUUID(), offence.id + (present ? " present" : " absent") + (detail == null || detail.isEmpty() ? "" : " " + detail));
        SettlementData.get(level).setDirty();
        return punishment;
    }

    private static void rebuke(YukkuriEntity leader, YukkuriEntity offender, boolean present) {
        if (present && leader != null) {
            leader.showReaction("angry", "rule_rebuke", 80);
            if (leader.level() instanceof ServerLevel level) YukkuriWorldData.get(level).relate(leader.getUUID(), offender.getUUID(), -2);
        }
        offender.showReaction("teary", "rule_punished", 100);
        offender.mind().add(present ? 6 : 3, "rule_rebuke");
    }

    /**
     * Expulsion (驱逐): the offender leaves the community and its burrow there, is barred from the territory for
     * {@link #BANISH_TICKS}, and is chased off if the leader is near. Kinship is untouched; the family stays.
     */
    public static void exile(YukkuriEntity offender, SettlementData.Group group, YukkuriEntity by, long now, String why) {
        if (!(offender.level() instanceof ServerLevel level)) return;
        var commons = group.commons;
        commons.standing(offender.getUUID()).banishedUntil = now + BANISH_TICKS;
        commons.hunt.party.remove(offender.getUUID());
        var home = offender.habitat().shelter();
        if (home != null && home.getBlockPos().distSqr(group.center) <= TERRITORY * TERRITORY) offender.habitat().release();
        boolean wasMember = group.id.equals(offender.settlement().groupId());
        if (wasMember) offender.settlement().exile();
        if (by != null) {
            by.showReaction("angry", "settlement_drive", 80);
            if (by.distanceToSqr(offender) <= 16 * 16) offender.frightenFrom(by);
        }
        offender.mind().felt(EmotionEvent.SOCIAL_HURT); offender.showReaction("teary", "settlement_hurt", 100);
        offender.mind().add(20, "exiled");
        // The family left behind feels the disgrace.
        var kin = Bonds.exileRelatives(offender);
        for (UUID id : kin) if (level.getEntity(id) instanceof YukkuriEntity relative && relative.isAlive()
                && group.id.equals(relative.settlement().groupId())) relative.mind().add(5, "kin_exiled");
        commons.note(now, "exiled", offender.getUUID(), why);
        SettlementData.get(level).setDirty();
    }

    public static boolean banished(SettlementData.Group group, UUID id, long now) {
        var s = group == null ? null : group.commons.existing(id);
        return s != null && s.banishedUntil > now;
    }

    /** True when {@code pos} lies in the territory of a community that has banished {@code entity}: it must not settle there. */
    public static boolean barred(YukkuriEntity entity, BlockPos pos) {
        if (!(entity.level() instanceof ServerLevel level)) return false;
        long now = level.getGameTime();
        for (var group : SettlementData.get(level).groups())
            if (ruled(group) && banished(group, entity.getUUID(), now) && pos.distSqr(group.center) <= TERRITORY * TERRITORY) return true;
        return false;
    }

    // ---- patrol: strangers without accessories, returning exiles, visible illicit pregnancies -------------------
    /** Every ~10 s per accepted, awake member of a ruled community (called from SettlementLife). */
    static void patrol(YukkuriEntity member, SettlementData.Group group) {
        if (!(member.level() instanceof ServerLevel level) || !ruled(group) || !SettlementSociety.accepted(member) || member.life().isSleeping()
                || member.isPanicking() || member.isNoAi()) return;
        if (member.blockPosition().distSqr(group.center) > TERRITORY * TERRITORY) return;
        long now = level.getGameTime();
        var commons = group.commons;
        var near = level.getEntitiesOfClass(YukkuriEntity.class, member.getBoundingBox().inflate(PATROL_RANGE), other -> other != member && other.isAlive()
                && !other.isNoAi() && !other.profile().dangerToYukkuri() && !other.profile().houseBody() && wild(other) && member.hasLineOfSight(other));
        for (var other : near) {
            boolean same = group.id.equals(other.settlement().groupId());
            if (same) {
                // A pregnancy the rules forbade is visible to everyone (the belly or the stem).
                var s = commons.existing(other.getUUID());
                if (s != null && s.illicitCourtship > 0 && now - s.illicitCourtship < COURTSHIP_MEMORY && (pregnant(other) || partnerPregnant(level, other))
                        && member.life().canRecognize(other)) {
                    s.illicitCourtship = 0;
                    report(other, group, Offence.ILLICIT_BIRTH, member, "pregnancy");
                }
                continue;
            }
            if (other.blockPosition().distSqr(group.center) > TERRITORY * TERRITORY) continue;
            var s = commons.standing(other.getUUID());
            if (now - s.lastIntrusion < INTRUSION_GAP) continue;
            boolean recognised = member.life().canRecognize(other);
            if (recognised && banished(group, other.getUUID(), now)) {
                s.lastIntrusion = now;
                report(other, group, Offence.RETURNED_EXILE, member, "came_back");
            } else if (!recognised && group.commons.has(Commons.NO_HATLESS)) {
                var p = member.life().personality();
                if (p.friendliness() > 50
                        && p.friendliness() > 40) { member.mind().felt(EmotionEvent.REJECTED); member.showReaction("surprise", "unrecognized",
                        60); s.lastIntrusion = now; continue; }
                if (now - s.lastIntrusion > 24000) s.intrusions = 0;
                s.lastIntrusion = now; s.intrusions++;
                intruder(level, group, member, other, s.intrusions, now);
            }
        }
    }

    /** A hatless stranger is "garbage that cannot take it easy": driven off at once, bullied (sanction) when it keeps coming. */
    private static void intruder(ServerLevel level, SettlementData.Group group, YukkuriEntity member, YukkuriEntity stranger, int times, long now) {
        var commons = group.commons;
        member.getLookControl().setLookAt(stranger, 25, 25);
        if (times >= 2 && Sanction.punish(stranger, member, Sanction.SANCTION, Offence.HATLESS.id)) {
            commons.note(now, "sanction", stranger.getUUID(), "hatless intruder x" + times);
        } else {
            if (times >= 2) commons.note(now, "sanction_deferred", stranger.getUUID(), "hatless intruder x" + times);
            member.showReaction("angry", "settlement_drive", 80);
            stranger.frightenFrom(member);
            stranger.mind().felt(EmotionEvent.SOCIAL_HURT); stranger.showReaction("teary", "settlement_hurt", 100);
            stranger.mind().add(10, "driven_off");
            commons.note(now, "driven_" + Offence.HATLESS.id, stranger.getUUID(), "by " + member.getUUID().toString().substring(0, 8) + " x" + times);
        }
        SettlementData.get(level).setDirty();
    }

    /** The existing loss-of-accessory flow (SettlementSociety) reached its end: recorded in the community log. */
    static void hatlessOutcome(YukkuriEntity member, SettlementData.Group group, SettlementData.Role role) {
        if (!(member.level() instanceof ServerLevel level) || !ruled(group)) return;
        group.commons.note(level.getGameTime(), "hatless_" + role.name().toLowerCase(Locale.ROOT), member.getUUID(), "member lost its accessory");
    }

    // ---- kin killing and cannibalism ---------------------------------------------------------------------------
    /** From SettlementLife.attackedBy: a member killed by a recognising member of the same community. */
    static void attacked(YukkuriEntity victim, DamageSource source, boolean fatal) {
        if (!fatal || !(victim.level() instanceof ServerLevel level) || !(source.getEntity() instanceof YukkuriEntity attacker)
                || attacker.profile().dangerToYukkuri() || !SettlementSociety.sameGroup(attacker, victim)) return;
        var group = attacker.settlement().group();
        if (!rule(group, Commons.NO_KIN_KILL)) return;
        if (!attacker.life().canRecognize(victim)) {
            // Ruling C11: without its accessory the victim was not recognised as kin; not a kin killing.
            group.commons.note(level.getGameTime(), "killed_unrecognised", attacker.getUUID(), victim.getUUID().toString().substring(0, 8));
            return;
        }
        var seen = witness(attacker, group, 12);
        if (seen == null) { group.commons.note(level.getGameTime(), "unseen_" + Offence.KIN_KILL.id, attacker.getUUID(), ""); return; }
        report(attacker, group, Offence.KIN_KILL, seen, "victim " + victim.getUUID().toString().substring(0, 8));
    }

    /**
     * Hook for the cannibalism package: {@code eater} ate (part of) {@code victim} — a living or dead member of its kind.
     * {@code recognised}: the eater recognised the victim as kin (accessory present, or an observer clever enough,
     * {@code headwear.Recognition}). Eating
     * the unrecognised is not an offence (ruling C11).
     */
    public static void ateKin(YukkuriEntity eater, YukkuriEntity victim, boolean recognised) {
        if (!(eater.level() instanceof ServerLevel level)) return;
        var group = eater.settlement().group();
        if (!rule(group, Commons.NO_CANNIBAL)) return;
        if (!recognised) {
            group.commons.note(level.getGameTime(), "ate_unrecognised", eater.getUUID(), victim == null ? "" : victim.getUUID().toString().substring(0, 8));
            return;
        }
        observed(eater, Offence.CANNIBAL, victim == null ? "remains" : "victim " + victim.getUUID().toString().substring(0, 8));
    }

    // ---- the birth rule ------------------------------------------------------------------------------------------
    static boolean pregnant(YukkuriEntity entity) {
        var brood = entity.family().brood();
        return brood != null && !brood.cancelled && !brood.complete();
    }
    private static boolean partnerPregnant(ServerLevel level, YukkuriEntity entity) {
        var id = entity.family().partner();
        return id != null && level.getEntity(id) instanceof YukkuriEntity partner && partner.isAlive() && pregnant(partner);
    }
    /** Loaded young (not adult) living in {@code home}. */
    public static int dependants(ServerLevel level, ShelterBlockEntity home) {
        if (home == null) return 0;
        int count = 0;
        for (UUID id : home.residentIds()) if (level.getEntity(id) instanceof YukkuriEntity y && y.isAlive() && !y.family().adult()) count++;
        return count;
    }

    /**
     * Called from {@link SettlementSeasons#mayCourt} before a courtship starts. The birth rule stops a household that already
     * raises {@link Commons#birthLimit} young, and everyone in a famine. Law-abiding individuals refrain; a defiant one
     * (low self-control, low attachment) courts anyway and is marked, so that the pregnancy, once seen, is judged.
     */
    public static boolean mayCourt(YukkuriEntity entity) {
        if (!(entity.level() instanceof ServerLevel level)) return true;
        var group = entity.settlement().group();
        if (!rule(group, Commons.BIRTH_LIMIT) || !SettlementSociety.accepted(entity)) return true;
        var commons = group.commons;
        boolean famine = commons.famine >= 2;
        if (!famine && dependants(level, entity.habitat().shelter()) < commons.birthLimit) return true;
        long now = level.getGameTime();
        var s = commons.standing(entity.getUUID());
        var p = entity.life().personality();
        if (p.selfControl() >= Temperament.SELF_CONTROL_AT_45 || p.attachment() >= Temperament.ATTACHMENT_AT_65) {
            if (now - s.lastBlocked > 2400) {
                s.lastBlocked = now;
                entity.showReaction("teary", "rule_blocked", 80);
                commons.note(now, "birth_refrained", entity.getUUID(), famine ? "famine" : "limit " + commons.birthLimit);
                SettlementData.get(level).setDirty();
            }
            return false;
        }
        if (s.illicitCourtship == 0 || now - s.illicitCourtship > COURTSHIP_MEMORY) {
            commons.note(now, "birth_defied", entity.getUUID(), famine ? "famine" : "limit " + commons.birthLimit);
            SettlementData.get(level).setDirty();
        }
        s.illicitCourtship = now;
        return true;
    }

    private CommunityRules() {}
}
