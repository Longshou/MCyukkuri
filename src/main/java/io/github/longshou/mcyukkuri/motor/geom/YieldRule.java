package io.github.longshou.mcyukkuri.motor.geom;

/**
 * Who gives way when two walking bodies meet in a lane, as plain numbers: the cost of yielding is the walk to a passing place
 * plus how strongly the body keeps its line, and the lower cost yields. A body that keeps its line strongly (heavier, carrying a
 * child, urgent, bold, sure of itself) pays more; one with a place close behind pays less; one whose way back is a known dead end
 * pays the most.
 */
public final class YieldRule {
    /** How far behind a body a passing place is looked for (blocks), and the search spacing. */
    public static final double REACH = 12, STATION = .5;
    /** Blocks of walking one unit of {@link #score} is worth, and the cost difference inside which the lower id yields. */
    public static final double K = 2, DEAD_BAND = .25;

    private YieldRule() {}

    /** Positive: A keeps its line against B. Each term is a difference, so the score is antisymmetric. */
    public static double score(double massA, double massB, boolean carryingA, boolean carryingB, double urgencyA, double urgencyB,
                               int boldnessA, int boldnessB, double confidenceA, double confidenceB) {
        return Math.log(massA / massB)
                + .6 * (carryingA ? 1 : 0) - .6 * (carryingB ? 1 : 0)
                + .3 * (urgencyA - urgencyB)
                + .4 * (boldnessA - boldnessB) / 100.0
                + .3 * (confidenceA - confidenceB);
    }

    /**
     * The walk's cost: its length when a place was found; with none, an open way back (the clear run reached the search's end) costs
     * REACH + 4 and a way back that ends in a wall short of it 3 x REACH, so the body with the dead end behind it is never sent into it.
     */
    public static double walkCost(boolean found, double length, double clearRun) {
        if (found) return length;
        return clearRun >= REACH - STATION ? REACH + 4 : 3 * REACH;
    }

    /** A body's cost of yielding: the walk plus how strongly it keeps its line. */
    public static double cost(double walk, double keepScore) { return walk + K * keepScore; }

    public static boolean tie(double costA, double costB) { return Math.abs(costA - costB) < DEAD_BAND; }

    /** A yields when its cost is lower; inside the dead band the lower id yields. */
    public static boolean aYields(double costA, double costB, int idA, int idB) {
        return tie(costA, costB) ? idA < idB : costA < costB;
    }

    /**
     * The same decision when a body may be unable to yield at all (it keeps its place, is leashed or panicking): the one that can yields;
     * when both can, the lower cost does. Swapping the bodies swaps the answer, and with at least one able exactly one yields.
     */
    public static boolean aYields(boolean aCan, boolean bCan, double costA, double costB, int idA, int idB) {
        return aCan && (!bCan || aYields(costA, costB, idA, idB));
    }
}
