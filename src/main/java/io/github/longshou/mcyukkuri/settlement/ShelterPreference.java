package io.github.longshou.mcyukkuri.settlement;

/** The gain in cover has to pay for getting there and approaching any person at the opening. */
final class ShelterPreference {
    static double need(double energy, boolean resting, double coldDeficit, double wetness) {
        double exposure = Math.max(Math.min(1, coldDeficit), wetness / 100);
        return Math.max(exposure, Math.max(1 - energy / 100, resting ? .38 : 0));
    }

    static boolean worthMoving(double need, double energy, boolean underCover, double routeLength, double wariness) {
        double benefit = need * (underCover ? .65 : 1);
        double effort = routeLength * (.028 + .065 * (1 - energy / 100));
        return benefit > effort + wariness;
    }

    private ShelterPreference() {}
}
