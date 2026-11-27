package io.github.longshou.mcyukkuri.social;

/**
 * The one strength-and-will formula behind every contest between two bodies (a bed lip, a sumo bout, a doll round's defence).
 * Pure numbers, no entity types. A contestant's score is its will plus a bounded edge for being the bigger body; the gap between the
 * two scores picks the process, and a logistic draw picks the winner, so a bold small body sometimes beats a timid heavy one.
 */
public final class ContestOdds {
    private ContestOdds() {}

    /** How far a gap of scores must reach before a draw would only be noise: the stronger simply wins. */
    static final double LOPSIDED_GAP = 60;
    /** Logistic spread of the draw: a gap of this size is a three-in-four win. */
    static final double SPREAD = 20;
    /** Points a body twice as mighty as the other gains over it. */
    static final double MIGHT_WEIGHT = 35;
    static final int MIN_PUSHES = 3, MAX_PUSHES = 7;

    public enum Process {
        /** One side backs off at once. */
        LOPSIDED,
        /** Push and pull until the draw falls. */
        TUSSLE
    }

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

    /** A gap this wide is no contest; a pair that may not fight (owned by one hand) never tussles. */
    public static Process process(double gap, boolean mayPush) {
        return !mayPush || Math.abs(gap) >= LOPSIDED_GAP ? Process.LOPSIDED : Process.TUSSLE;
    }

    /** Whether the first side of a gap wins: certain when the gap is lopsided, otherwise the draw {@code roll} (0..1) under its chance. */
    public static boolean firstWins(double gap, double roll) {
        if (Math.abs(gap) >= LOPSIDED_GAP) return gap > 0;
        return roll < winChance(gap);
    }

    /** Pushes a tussle lasts: the closer the pair, the longer. */
    public static int pushes(double gap) {
        double closeness = 1 - Math.min(1, Math.abs(gap) / LOPSIDED_GAP);
        return MIN_PUSHES + (int) Math.round((MAX_PUSHES - MIN_PUSHES) * closeness);
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
