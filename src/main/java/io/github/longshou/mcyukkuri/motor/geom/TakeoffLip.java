package io.github.longshou.mcyukkuri.motor.geom;

import java.util.function.DoublePredicate;

/**
 * A take-off stand may sit a hair below the ground it hops onto (a hull floor a few centimetres under the bank top), so a wide body
 * beside the bank starts with its lowest edge inside the bank block. Its first rise leaves that block at once, so the part of the
 * start box sunk in by less than {@link #MAX} is not an obstruction; a deeper one still is. Pure Java.
 */
public final class TakeoffLip {
    /** Deepest sunk start (blocks) that still counts as standing beside the ground; the search step. */
    public static final double MAX = .15, STEP = .01;

    private TakeoffLip() {}

    /** The smallest lift (0 when the start is clear already) that frees the start box, or -1 when none up to {@link #MAX} does. */
    public static double lift(DoublePredicate clearAfterLift) {
        if (clearAfterLift.test(0)) return 0;
        for (double lift = STEP; lift <= MAX + 1e-9; lift += STEP) if (clearAfterLift.test(lift)) return lift;
        return -1;
    }

    /** How far a box at {@code rise} above the start is still held up by the lip {@code lift}: none once it has risen by it. */
    public static double held(double lift, double rise) { return Math.max(0, lift - rise); }

    /**
     * The landing side of the same lip: a host stand (a hat stop riding the water) may itself sit a hair below the bank top it lies
     * beside, and the rider on it is carried, not pushed by the block. A falling box is held up by the landing lip until it stands
     * on the stand; a rising one never is by it.
     * @param startLip lip of the take-off still unspent (0 when none), {@code rise} the box's height above the start
     * @param landing lip of the landing stand, {@code above} the box's height above that stand, {@code descending} whether the box is falling
     * @return how far the box is held up out of the block this sample
     */
    public static double lifted(double startLip, double rise, double landing, double above, boolean descending) {
        return Math.max(held(startLip, rise), descending ? held(landing, above) : 0);
    }
}
