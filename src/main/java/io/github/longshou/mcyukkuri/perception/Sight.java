package io.github.longshou.mcyukkuri.perception;

/** What a body can see, as pure intervals shifted by its stats and state; the geometry (rays, glass) lives in {@link Senses}. */
public final class Sight {
    /** Anything this close is seen whatever the facing. */
    public static final double NEAR = 2.5;
    /** Sight range in blocks: the least intelligent see about ten, the keenest sixteen; a felt need stretches it for the thing it wants. */
    private static final double RANGE_LOW = 10, RANGE_HIGH = 16, SALIENT = 1.25;
    /** Half angle of the view cone in degrees: a timid body scans wide, a bold one is narrower; panic narrows it to this share. */
    private static final double CONE_TIMID = 110, CONE_BOLD = 70, PANIC_CONE = .6;

    private Sight() {}

    public static double ramp(double value, double low, double high) {
        return Math.clamp((value - low) / (high - low), 0, 1);
    }

    public static double range(int intelligence, boolean salient) {
        return (RANGE_LOW + (RANGE_HIGH - RANGE_LOW) * ramp(intelligence, 20, 80)) * (salient ? SALIENT : 1);
    }

    /** Cosine of the cone half angle; the cone is wider than a half plane for a timid body (negative cosine). */
    public static double coneCos(int boldness, boolean panicking) {
        double half = CONE_TIMID - (CONE_TIMID - CONE_BOLD) * ramp(boldness, 20, 80);
        return Math.cos(Math.toRadians(half * (panicking ? PANIC_CONE : 1)));
    }

    /** Awake, within range and inside the view cone, or very near. The one sight rule: events and still things alike. */
    public static boolean inView(boolean awake, double distanceSq, double facingDot, double range, double coneCos) {
        return awake && distanceSq <= range * range && (distanceSq <= NEAR * NEAR || facingDot >= coneCos);
    }
}
