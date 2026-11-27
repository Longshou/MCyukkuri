package io.github.longshou.mcyukkuri.motor.geom;

/**
 * Pure pace law of a body on a player's lead. Vanilla owns the tether: the close-range follow aims the body at a point 2 blocks short
 * of the holder (and, a path ending within one block of its aim being trivial, a body settles anywhere up to about 3.9 blocks out),
 * the elastic pull begins at {@link #elasticStart}, and the lead breaks past 10. These numbers only choose the gait inside that
 * range (a walk, or the hop-run of a fast request) and the strength of a stray's pull-back bouts; nothing here writes position or speed.
 */
public final class LeadPace {
    /** Walk and hop-run requests: 1.25 is above the 1.15 fast line, so the body shows the hop-run clip and its sprint stamina applies. */
    public static final double WALK_REQUEST = 1.0, HOP_REQUEST = 1.25;
    /** The hop-run ends once the body is this close again: the farthest a vanilla follow settles (smoke: 2.4, 2.8 and 3.7 blocks). */
    public static final double CAUGHT_UP = 4.0;
    /** Vanilla's elastic pull begins at this much minus the two body widths ({@link #elasticStart}); the lead breaks at 10. */
    public static final double ELASTIC = 6.0;

    private LeadPace() {}

    /** Where vanilla's elastic pull begins for a body and a holder of these widths (blocks). */
    public static double elasticStart(double bodyWidth, double holderWidth) { return ELASTIC - bodyWidth - holderWidth; }

    /**
     * Distance past which the body starts to hurry (blocks, 4.2 to 4.5, always past {@link #CAUGHT_UP}): a bigger stride and a bolder
     * temper let it trail a little further, a clingy one keeps closer. {@code size} is the growth share of the body (0 to 1), the traits are 0-100.
     */
    public static double comfort(double size, int attachment, int boldness) {
        return Math.clamp(4.2 + .15 * Math.clamp(size, 0, 1) + .15 * (boldness - 50) / 50.0 - .15 * (attachment - 50) / 50.0, 4.2, 4.5);
    }

    /**
     * Distance at which a pulled body feels the rope go taut: a timid one just before the elastic begins, a bold one at it, never
     * before it has begun to hurry ({@code comfort} plus 0.3).
     */
    public static double tautAt(int boldness, double comfort, double elasticStart) {
        return Math.max(comfort + .3, elasticStart - .5 + .5 * Math.clamp(boldness, 0, 100) / 100.0);
    }

    /** Whether the body is trailing: it starts past its comfort and goes on until it has caught up, so the walk and the hop never flicker. */
    public static boolean trailing(boolean was, double distance, double comfort) {
        return was ? distance > CAUGHT_UP : distance > comfort;
    }

    /** Ticks a body must stand still before it counts as having settled. */
    public static final int SETTLE_TICKS = 20;

    /**
     * The exit of a hop that does not depend on where the vanilla follow stops: the body has stood still for {@link #SETTLE_TICKS}
     * ticks inside the elastic. (Smoke run 1: it stopped at 2.4 blocks, just past the first caught-up distance, and hopped in place for ever.)
     */
    public static boolean settled(int stillTicks, double distance, double elasticStart) {
        return stillTicks >= SETTLE_TICKS && distance < elasticStart;
    }

    /** The walk request of the lead's close-range follow. */
    public static double request(boolean hopping) { return hopping ? HOP_REQUEST : WALK_REQUEST; }

    /** How much strength is left, 0 (at the tired line) to 1 (full). */
    public static double reserve(double energy, double tiredLine) {
        return Math.clamp((energy - tiredLine) / (100 - tiredLine), 0, 1);
    }

    /** A stray's will to pull against the lead, 0.2 to 1: a friendly one gives in sooner. */
    public static double resist(int friendliness) { return Math.clamp(1 - .6 * friendliness / 100.0 - .1, .2, 1); }

    /** Ticks between two bouts: longer as the reserve falls, shorter for a stronger will. */
    public static int boutGap(double reserve, double resist) {
        return (int) Math.round((40 + 120 * (1 - Math.clamp(reserve, 0, 1))) / (.6 + resist));
    }

    /** Energy one bout costs, taken when it ends: 4 to 10 by the will. */
    public static float boutCost(double resist) { return (float) (4 + 6 * resist); }

    /** How long a bout lasts (110 to 160 ticks: the walk out along the slack, then the dig-in): a weaker body strains for less. */
    public static int boutTicks(double reserve) { return (int) Math.round(110 + 50 * Math.clamp(reserve, 0, 1)); }

    /** How far from the holder the body strains (blocks): 0.8 to 0.4 short of the elastic start, nearer as it weakens, so the pull never fights the walk. */
    public static double boutReach(double reserve, double elasticStart) { return elasticStart - .8 + .4 * Math.clamp(reserve, 0, 1); }
}
