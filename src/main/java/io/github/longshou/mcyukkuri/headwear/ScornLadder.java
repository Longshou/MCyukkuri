package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure decision ladder for taunting a hatless individual and the target's repeated-scorn reaction.
 * Ranges are shifted continuously by personality and history; no Minecraft types so unit tests run offline.
 */
public final class ScornLadder {
    /** Quiet window (5 minutes) after which observer/target scorn rounds reset to a fresh first sight. */
    public static final int HISTORY_WINDOW = 6000;
    /** Duration a numbed target avoids approaching its taunter. */
    public static final int AVOID_TAUNTER_TICKS = 600;

    public record TaunterProfile(int friendliness, int selfControl, int boldness, int intelligence, boolean harsh) {}

    public record Taunt(int round, String beat, String event, String face, YukkuriAction clip,
                        boolean quietSnub, boolean bored, int cooldownMultiplier,
                        double zeal, double tedium, double speakChance) {}

    public enum TargetKind { SHOCK, ANSWER, NUMB, LEAVE }

    public record TargetProfile(int boldness, double selfEsteem, int fearOfTaunter, boolean starving, boolean sameGroup) {}

    public record TargetOutcome(TargetKind kind, String beat, String face, YukkuriAction clip, String event, boolean avoidTaunter) {}

    private ScornLadder() {}

    public static double ramp(double value, double low, double high) {
        return Math.clamp((value - low) / (high - low), 0.0, 1.0);
    }

    /** Taunter eagerness: lower friendliness, lower self-control and the harsh gate raise it. */
    public static double zeal(TaunterProfile p) {
        return Math.clamp(0.5 * (1.0 - p.friendliness() / 100.0)
                + 0.3 * (1.0 - p.selfControl() / 100.0)
                + (p.harsh() ? 0.2 : 0.0), 0.0, 1.0);
    }

    /** Growing boredom from rounds 2 to 6, slowed for zealous taunters. */
    public static double tedium(int rounds, double zeal) {
        return ramp(rounds, 2, 6) * (1.0 - 0.5 * zeal);
    }

    /** Probability of speaking instead of giving a quiet snub on this round. */
    public static double speakChance(int rounds, TaunterProfile p) {
        double z = zeal(p);
        return Math.clamp(z * (1.0 - tedium(rounds, z)) + 0.15, 0.0, 1.0);
    }

    /** Extra sanction escalation roll once a harsh non-kin taunter reaches round 4+. */
    public static int escalationBonus(int rounds, boolean harsh, boolean closeKin) {
        return rounds >= 4 && harsh && !closeKin ? 10 * rounds : 0;
    }

    /**
     * Choose the observer's taunt beat for {@code rounds} (>= 1).
     * Never returns the same spoken event as {@code lastTargetEvent} (prevents chorus parroting) or {@code lastPairEvent}.
     */
    public static Taunt choose(int rounds, TaunterProfile profile, boolean thirdPartyWitness, double roll,
                               String lastTargetEvent, String lastPairEvent) {
        int r = Math.max(1, rounds);
        double z = zeal(profile);
        double t = tedium(r, z);
        double chance = Math.clamp(z * (1.0 - t) + 0.15, 0.0, 1.0);
        String targetPrev = lastTargetEvent == null ? "" : lastTargetEvent;
        String pairPrev = lastPairEvent == null ? "" : lastPairEvent;

        if (r >= 4) {
            String face = profile.harsh() ? "down_angry" : z >= 0.45 ? "pursed" : "dull";
            return new Taunt(r, "scorn_bored", "", face, null, true, true, 2, z, t, chance);
        }
        if (r == 1 && !"hatless_scorn".equals(targetPrev)) {
            return new Taunt(1, "scorn_first", "hatless_scorn", profile.harsh() ? "fierce" : "sneer", null, false, false, 1, z, t, chance);
        }
        if (r >= 2 && roll >= chance) {
            return new Taunt(r, "scorn_snub", "", "pursed", null, true, false, 1, z, t, chance);
        }

        double jeerScore = (profile.harsh() ? 0.5 : 0.0)
                + 0.5 * ramp(profile.boldness(), 40, 85)
                - 0.35 * ramp(profile.friendliness(), 45, 85);
        double shooScore = 0.45 * ramp(profile.intelligence(), 40, 74)
                + 0.35 * ramp(profile.selfControl(), 40, 85)
                + 0.25 * ramp(profile.boldness(), 45, 85)
                - (profile.harsh() ? 0.25 : 0.0);
        double whisperScore = 0.50 * ramp(profile.friendliness(), 40, 85)
                + 0.50 * (1.0 - ramp(profile.boldness(), 20, 75));

        record Candidate(String beat, String event, String face, YukkuriAction clip, double score, boolean needsWitness) {}
        List<Candidate> ranked = new ArrayList<>(3);
        ranked.add(new Candidate("scorn_jeer", "hatless_jeer", profile.harsh() ? "fierce" : "smug", YukkuriAction.PUFF, jeerScore, false));
        ranked.add(new Candidate("scorn_shoo", "hatless_shoo", profile.harsh() ? "angry_stare" : "pursed", YukkuriAction.TURN, shooScore, false));
        ranked.add(new Candidate("scorn_whisper", "hatless_whisper", "pursed", null, whisperScore, true));
        ranked.sort(Comparator.comparingDouble(Candidate::score).reversed());

        // A friendly/timid body whose natural line is gossip whispers only when a third body sees it; alone it snubs quietly.
        if (ranked.getFirst().needsWitness() && !thirdPartyWitness) {
            return new Taunt(r, "scorn_snub", "", "pursed", null, true, false, 1, z, t, chance);
        }
        for (var c : ranked) {
            if (c.needsWitness() && !thirdPartyWitness) continue;
            if (c.event().equals(targetPrev)) continue;
            if (r >= 2 && c.event().equals(pairPrev)) continue;
            return new Taunt(r, c.beat(), c.event(), c.face(), c.clip(), false, false, 1, z, t, chance);
        }
        return new Taunt(r, "scorn_snub", "", "pursed", null, true, false, 1, z, t, chance);
    }

    /** Choose the target's reaction from its own stats, fear of the taunter, and how often it has been scorned. */
    public static TargetOutcome chooseTarget(int scornedRounds, boolean repeatedFromSameTaunter, TargetProfile p, double roll) {
        int r = Math.max(1, scornedRounds);
        if (p.fearOfTaunter() >= 45 && (repeatedFromSameTaunter || r >= 2)) {
            return new TargetOutcome(TargetKind.LEAVE, "scorned_leave", "teary", null, "", true);
        }
        if (r <= 1 && !repeatedFromSameTaunter) {
            String event = roll < (1.0 / 3.0) ? (p.sameGroup() ? "settlement_hurt" : "scorn_hurt") : "";
            return new TargetOutcome(TargetKind.SHOCK, "scorned_shock", "teary", null, event, false);
        }
        double answerDrive = 0.55 * ramp(p.boldness(), 40, 85) + 0.45 * ramp(p.selfEsteem(), 42, 70);
        double numbDrive = 0.55 * (1.0 - ramp(p.boldness(), 20, 65))
                + 0.45 * (1.0 - ramp(p.selfEsteem(), 30, 58))
                + 0.25 * ramp(r, 2, 6);
        if (!p.starving() && answerDrive >= 0.50 && answerDrive >= numbDrive) {
            return new TargetOutcome(TargetKind.ANSWER, "scorned_answer", "cheek_puff", YukkuriAction.PUFF, "hatless_answer", false);
        }
        if (numbDrive >= 0.45) {
            return new TargetOutcome(TargetKind.NUMB, "scorned_numb", "dull", YukkuriAction.DROOP, "", true);
        }
        String event = roll < (1.0 / 3.0) ? (p.sameGroup() ? "settlement_hurt" : "scorn_hurt") : "";
        return new TargetOutcome(TargetKind.SHOCK, "scorned_shock", "teary", null, event, false);
    }
}
