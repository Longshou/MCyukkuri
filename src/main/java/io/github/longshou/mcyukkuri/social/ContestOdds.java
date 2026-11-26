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
}
