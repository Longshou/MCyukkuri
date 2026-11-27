package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.perception.Sight;

/**
 * Pure stat-scaled intervals and presentation choices for going under cover from rain. Rain wets a body every 20 ticks;
 * how much of it a body takes before it turns for cover is an interval shifted by its personality — timid and clever
 * bodies start at the first drops, bold ones only once it is really wet — not a fixed trigger.
 */
public final class RainShelter {
    /** Wetness of rain on the body at which a body goes for cover: from the first drops (timid) to a soaking (bold). */
    private static final double TIMID = .5, BOLD = 5.5;
    /** How much of that interval a clever body takes off (it notices the weather sooner). */
    private static final double NOTICE = .3;

    private RainShelter() {}

    /** Wetness on this body at which rain is worth leaving: timid/clever near zero, bold later. */
    public static float tolerance(int boldness, int intelligence) {
        double later = Sight.ramp(boldness, 20, 80);
        double notice = 1 - NOTICE * Sight.ramp(intelligence, 20, 80);
        return (float) (TIMID + (BOLD - TIMID) * later * notice);
    }

    /** Worried face at the first drops: a timid body tears up, a bold one only looks up. */
    public static String face(int boldness) { return boldness < 40 ? "teary" : "neutral"; }

    /** The one short line spoken when a body turns for cover. */
    public static String line() { return "rain_run"; }
}
