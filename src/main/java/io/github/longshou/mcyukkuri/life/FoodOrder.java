package io.github.longshou.mcyukkuri.life;

import java.util.UUID;

/** Pure rules of a contested ground portion: who goes first, and how long a free body inspects strange food. */
final class FoodOrder {
    /** The gap in nutrition (0-100 scale) from which the hungrier of two bodies goes first; closer bodies split by id. */
    static final float HUNGER_PRECEDENCE = 25;
    /** From here down a body is too hungry to inspect anything. */
    static final float NO_INSPECTION_BELOW = 30;
    /** Inspect pause at the extremes: a bold clever body looks for 8 ticks, a timid dull one for 80. */
    private static final int BASE_TICKS = 20, TIMID_TICKS = 60;
    private static final double CLEVER_SHORTENS = .6;

    private FoodOrder() {}

    /** Antisymmetric: of two bodies at one portion exactly one yields to the other. */
    static boolean yieldsTo(float mine, UUID myId, float rival, UUID rivalId) {
        float gap = mine - rival;
        if (gap >= HUNGER_PRECEDENCE) return true;
        if (gap <= -HUNGER_PRECEDENCE) return false;
        return rivalId.compareTo(myId) < 0;
    }

    /** timid and clever are the 0..1 temperament scores. */
    static int inspectTicks(double timid, double clever) {
        return (int) Math.round((BASE_TICKS + TIMID_TICKS * timid) * (1 - CLEVER_SHORTENS * clever));
    }
}
