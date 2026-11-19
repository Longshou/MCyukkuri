package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.social.Temperament;

public final class HomeTactic {
    private HomeTactic() {}

    // Adult walk coefficient in body/ability.json, also used for stationary pursuers.
    private static final double PURSUIT_FLOOR = .10;
    public static final double LEAD_AT = .40;

    public static double slack(Temperament t) {
        return .15 + .50 * (1 - t.intelligence() / 100.) + .30 * (1 - t.selfControl() / 100.);
    }

    public static double eta(double distance, double step) { return distance / Math.max(step, PURSUIT_FLOOR); }
    public static boolean inTime(double eta, double need, Temperament t) { return eta >= need * (1 + slack(t)); }

    public static boolean holdsThroughHit(int remaining, int press, Temperament t) {
        return remaining <= press * (.10 + .30 * t.selfControl() / 100.);
    }

    public static double lead(Temperament t, int fear, double health, int dependants) {
        if (dependants <= 0 || health < .5) return 0;
        double devotion = Temperament.ramp(.55 * t.attachment() + .45 * t.friendliness(), 40, 85);
        double clever = Temperament.ramp(t.intelligence(), 25, 80);
        double nerve = Temperament.ramp(t.boldness(), 15, 65);
        double dread = Temperament.ramp(fear, 30, 75);
        return devotion * (.35 + .65 * clever) * (.45 + .55 * nerve) * (1 - .6 * dread) * Math.min(1, .7 + .15 * dependants);
    }

    public static int leadTicks(Temperament t) { return 200 + 2 * t.attachment() + 2 * t.selfControl(); }
    public static double leadRange(Temperament t) { return 12 + .08 * t.intelligence(); }
    public static double tauntGap(Temperament t) { return 4 + .06 * t.boldness(); }
}
