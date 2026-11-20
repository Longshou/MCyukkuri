package io.github.longshou.mcyukkuri.life;

/** Temperature is measured in biome-temperature units. One unit of deficit gives the age's full reference rate. */
final class ThermalComfort {
    static final int HEAT_RADIUS = 4, HEAT_HEIGHT = 2;
    private static final double REFUGE_GAIN = .15;

    private ThermalComfort() {}

    static double exposureRate(double felt, double coldLimit, double ageRate) {
        double deficit = coldLimit - felt;
        return deficit > 0 ? ageRate * deficit : Math.max(-2, 2 * deficit);
    }

    static boolean warmerRefuge(double here, double there, double hotLimit) {
        return there >= here + REFUGE_GAIN && there < hotLimit;
    }

    static double radiantWarmth(double strength, double distanceSquared, int height, int verticalReach) {
        if (distanceSquared > HEAT_RADIUS * HEAT_RADIUS || Math.abs(height) > verticalReach) return 0;
        return strength * (1 - Math.sqrt(distanceSquared) / 6);
    }

    static double preferredFireDistance(double boldness, double width, double deficit, boolean remembersBurn,
                                        boolean shieldingChild, boolean withParent) {
        // Keep a block between the family rings. A remembered burn adds room; deep cold pulls inward at most a fifth of a block.
        double preferred = shieldingChild ? 3 : withParent ? 2 : 3 - (3 * boldness + Math.min(1, width)) / 4;
        return Math.max(remembersBurn ? 3 : 2, preferred + (remembersBurn ? .6 : 0) - .2 * Math.min(1, deficit));
    }
}
