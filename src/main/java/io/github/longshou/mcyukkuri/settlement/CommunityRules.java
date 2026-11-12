package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.headwear.Recognition;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.social.Temperament;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Community rules (族规) and offence detection (package 12).
 *
 * <p>Lecture basis: komicolle 40720 No.11518859 (a community elects a leader; rules forbid eating one's own kind and limit
 * births, "to avoid unfairness and self-destruction"), No.11518794 (without proper rules yukkuri breed until the stores
 * are gone and many break the rules), No.11518829 (whatever "cannot take it easy" is sanctioned; whether one can take it
 * easy depends on having an intact accessory; the sanction is bullying, not killing, user correction 2026-10-18), No.11518847 (eating one's own kind is forbidden but not rare:
 * without its accessory the victim is not recognised as kin). Ruling C10: the leader organises, nobody is given orders by
 * the player; C11: eating or killing an unrecognised (hatless) individual is not a kin offence.
 *
 * <p>Project design (numbers and witnesses): only a community with a leader office has rules; an offence counts
 * only when a real member saw it (awake, within range, line of sight, recognising the offender), and is recorded in the
 * community log. A hatless stranger is driven off and, on repeat, bullied ({@link Sanction}).
 */
public final class CommunityRules {
    public enum Offence {
        THEFT("theft", 1, Commons.NO_THEFT), DEFIANCE("defiance", 1, Commons.OBEY_CALL),
        ILLICIT_BIRTH("illicit_birth", 2, Commons.BIRTH_LIMIT), HATLESS("hatless", 2, Commons.NO_HATLESS),
        KIN_KILL("kin_kill", 4, Commons.NO_KIN_KILL), CANNIBAL("cannibal", 4, Commons.NO_CANNIBAL);
        public final String id; public final int base, rule;
        Offence(String id, int base, int rule) { this.id = id; this.base = base; this.rule = rule; }
    }
    /** [original] distances and durations. */
    static final double WITNESS_RANGE = 10, PATROL_RANGE = 8, TERRITORY = 40;
    static final long INTRUSION_GAP = 600, COURTSHIP_MEMORY = 48000;

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
     * {@code offender} commit {@code offence} against the rules of {@code group}. Returns true when the offence was recorded.
     */
    public static boolean report(YukkuriEntity offender, SettlementData.Group group, Offence offence, YukkuriEntity witness, String detail) {
        if (!(offender.level() instanceof ServerLevel level) || !rule(group, offence.rule)) return false;
        var commons = group.commons;
        long now = level.getGameTime();
        commons.note(now, "offence_" + offence.id, offender.getUUID(), (detail == null ? "" : detail) + (witness == null ? "" : " seen_by " + witness.getUUID().toString().substring(0, 8)));
        if (witness != null) {
            witness.getLookControl().setLookAt(offender, 25, 25);
            witness.showReaction("angry", "heard", 60);
            witness.mind().add(offence.base >= 4 ? 10 : 2, "rule_broken");
        }
        SettlementData.get(level).setDirty();
        return true;
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
                && !other.isNoAi() && !other.profile().dangerToYukkuri() && other.status().identity().free() && member.hasLineOfSight(other));
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
            if (Recognition.unrecognizedHatless(member, other)
                    && group.commons.has(Commons.NO_HATLESS)) {
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
     * (low self-control, low attachment) courts anyway and is marked so a witnessed pregnancy can be reported.
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
