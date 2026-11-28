package io.github.longshou.mcyukkuri.social;

import java.util.UUID;

/**
 * The one strength-and-will formula behind every contest between two bodies (a bed lip, a sumo bout, a doll round's defence).
 * Pure numbers, no entity types. A contestant's score is its will plus a bounded edge for being the bigger body; the gap between the
 * two scores picks who is ahead, a logistic draw picks the winner of an even bout, and the pair's heat (how much the prize matters
 * times how hot the two tempers are, less the bond between them) picks which of the five ways the pair settles it.
 */
public final class ContestOdds {
    private ContestOdds() {}

    /** How far a gap of scores must reach before a draw would only be noise: the stronger simply wins. */
    static final double LOPSIDED_GAP = 60;
    /** Logistic spread of the draw: a gap of this size is a three-in-four win. */
    static final double SPREAD = 20;
    /** Points a body twice as mighty as the other gains over it. */
    static final double MIGHT_WEIGHT = 35;

    /** One contestant: {@code need} (0..100) is how badly it wants the prize; {@code fear} is its fear of the other. */
    public record Side(double might, int boldness, int friendliness, int fear, int need) {}

    /** Body strength: width, species might, health and what energy is left (a floor keeps a tired body a contestant). */
    public static double might(double width, double speciesMight, double healthRatio, double energy) {
        return width * speciesMight * healthRatio * Math.max(.15, energy / 100.0);
    }

    /** The edge of the bigger body, in score points, bounded to plus or minus {@link #MIGHT_WEIGHT}. */
    public static double mightEdge(double own, double other) {
        return MIGHT_WEIGHT * Math.clamp(own / Math.max(.01, other) - 1, -1, 1);
    }

    /** Will: boldness and need push forward; friendliness and fear of the other hold back. */
    public static double will(Side side) {
        return side.boldness + side.need * .5 - side.friendliness * .3 - side.fear * .5;
    }

    public static double score(Side own, Side other) {
        return will(own) + mightEdge(own.might, other.might);
    }

    /** Positive when {@code a} is ahead of {@code b}; antisymmetric in its arguments up to the might edge's clamp. */
    public static double gap(Side a, Side b) {
        return score(a, b) - score(b, a);
    }

    /** Chance that the side ahead by {@code gap} wins the draw. */
    public static double winChance(double gap) {
        return 1 / (1 + Math.exp(-gap / SPREAD));
    }

    /** Whether the first side of a gap wins: certain when the gap is lopsided, otherwise the draw {@code roll} (0..1) under its chance. */
    public static boolean firstWins(double gap, double roll) {
        if (Math.abs(gap) >= LOPSIDED_GAP) return gap > 0;
        return roll < winChance(gap);
    }

    /** The five ways two bodies settle one prize, from a gentle bow to a real fight. */
    public enum Level { POLITE_YIELD, COMPLAIN_YIELD, SUMO_DECIDE, PUFF_DECIDE, ATTACK_DECIDE }

    /** Heat boundaries where the way of settling changes; kept as bands, not exact cuts, by the hash jitter. */
    static final double WARM = 25, PUFF_HEAT = 64, ATTACK_HEAT = 82;
    /** A body's readiness for a real fight: its health body stays above the give-up line and it has breath left. */
    static final double FIGHT_HEALTH = .45, FIGHT_ENERGY = 20;
    /** A heat this high makes even a clearly weaker side less willing to give way at once. */
    static final double YIELD_BASE = 45, YIELD_PER_HEAT = .25;

    /** One body's readiness to fight: hot temper and a sound body. */
    public static double temper(int boldness, int friendliness, int selfControl) {
        return .40 * boldness + .35 * (100 - friendliness) + .25 * (100 - selfControl);
    }

    /** How much the pair's bond cools the bout: average closeness/2, plus a brake for one owner's pets and for kin. */
    public static double bondBrake(double affinityAToB, double affinityBToA, boolean sameOwner, boolean kin) {
        return Math.clamp((affinityAToB + affinityBToA) / 4, -15, 25) + (sameOwner ? 25 : 0) + (kin ? 20 : 0);
    }

    /** The prize's pull on the pair: both bodies' importance, the needier one weighing more. */
    public static int pairImportance(int first, int second) {
        return (int) Math.round(.60 * Math.max(first, second) + .40 * Math.min(first, second));
    }

    /** Scene heat: prize pull and both tempers less the bond brake, plus the pair's own small hash jitter. */
    public static double heat(int pairImportance, double avgTemper, double bondBrake, int jitter) {
        return Math.clamp(.45 * pairImportance + .45 * avgTemper - bondBrake + jitter, 0, 100);
    }

    /** The gap at which the weaker side no longer contests: a hotter, weightier prize makes it hold on longer. */
    public static double yieldGap(double heat) {
        return YIELD_BASE + YIELD_PER_HEAT * heat;
    }

    /** A stable per-pair jitter in -5..5, so the same two bodies read the same scene the same way each time. */
    public static int jitter(UUID a, UUID b) {
        long hash = a.getMostSignificantBits() ^ a.getLeastSignificantBits()
                ^ b.getMostSignificantBits() ^ b.getLeastSignificantBits();
        return (int) Math.floorMod(hash, 11) - 5;
    }

    /** Whether neither side holds a decisive edge: the pair must settle it by some exchange rather than a yield. */
    public static boolean even(double gap, double heat) {
        return heat >= WARM && Math.abs(gap) < yieldGap(heat);
    }

    /**
     * The way the pair settles the prize. An edge wide enough (or a scene too cool to care) is given way at once; a yielding body
     * that is gentle or close bows, one that still minds complains. An even pair sums when warm, puffs when hot, and only two fit,
     * hot, unfriendly bodies with no bond actually charge each other; a pair that may not push or is too weak to fight stops at puffs.
     */
    public static Level level(double gap, double heat, boolean mayPush, boolean fitToFight, boolean yielderGentle) {
        if (!even(gap, heat)) return yielderGentle ? Level.POLITE_YIELD : Level.COMPLAIN_YIELD;
        if (!mayPush) return heat >= PUFF_HEAT ? Level.PUFF_DECIDE : Level.COMPLAIN_YIELD;
        if (heat < PUFF_HEAT) return Level.SUMO_DECIDE;
        if (heat < ATTACK_HEAT || !fitToFight) return Level.PUFF_DECIDE;
        return Level.ATTACK_DECIDE;
    }

    /** How many puffs the pair blows: the closer the bout, the longer the display. */
    public static int puffRounds(double gap, double heat) {
        double closeness = 1 - Math.min(1, Math.abs(gap) / Math.max(1, yieldGap(heat)));
        return 2 + (int) Math.round(2 * closeness);
    }

    /** Whether a body is sound enough to trade real blows without nearing the give-up line. */
    public static boolean fitToFight(double health, double maxHealth, double energy) {
        return health > Math.max(3.0, maxHealth * FIGHT_HEALTH) && energy >= FIGHT_ENERGY;
    }

    /** Whether a body goes into the face-off with a glare rather than a flinch. */
    public static boolean defiant(int boldness) {
        return boldness >= 50;
    }

    /** The four varied faces a body pushed back in friendly sumo can show: embarrassed, startled, flustered, indignant. */
    static final String[] SUMO_PUSHED_FACES = { "ashamed", "surprise", "teary_pant", "angry" };

    /**
     * Picks one of the four pushed-back faces for a sumo shove or push-out, weighted by boldness and self-control while keeping
     * every face reachable and excluding {@code previousFace} so consecutive shoves vary cleanly.
     */
    public static String sumoPushedFace(int boldness, int selfControl, String previousFace, double roll) {
        double b = Math.clamp(boldness, 0, 100) / 100.0;
        double c = Math.clamp(selfControl, 0, 100) / 100.0;
        double[] weights = {
            0.25 + 0.55 * (1.0 - b) + 0.25 * c,
            0.35 + 0.30 * (1.0 - b) + 0.15 * (1.0 - c),
            0.25 + 0.45 * (1.0 - c) + 0.20 * (1.0 - b),
            0.25 + 0.65 * b + 0.25 * (1.0 - c)
        };
        double total = 0;
        for (int i = 0; i < SUMO_PUSHED_FACES.length; i++) {
            if (SUMO_PUSHED_FACES[i].equals(previousFace)) weights[i] = 0;
            total += weights[i];
        }
        double target = Math.clamp(roll, 0.0, 0.999999999) * total;
        double acc = 0;
        for (int i = 0; i < SUMO_PUSHED_FACES.length; i++) {
            if (weights[i] <= 0) continue;
            acc += weights[i];
            if (target < acc) return SUMO_PUSHED_FACES[i];
        }
        return SUMO_PUSHED_FACES[0].equals(previousFace) ? SUMO_PUSHED_FACES[1] : SUMO_PUSHED_FACES[0];
    }

    /**
     * The contest loser's parting face from its own personality: fierce sulk or haughty hmph for a defiant body, teary for a timid
     * one. Never {@code cheek_puff}, so a loser walking away never puffs its cheeks while backing off.
     */
    public static String contestLoserFace(int boldness, int selfControl, int friendliness) {
        if (!defiant(boldness)) return "teary";
        return selfControl < 50 || friendliness < 45 ? "down_angry" : "hmph";
    }
}
