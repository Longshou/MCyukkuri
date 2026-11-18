package io.github.longshou.mcyukkuri.life;

/** Temperature is measured in biome-temperature units. One unit of deficit gives the age's full reference rate. */
final class ThermalComfort {
    private static final double REFUGE_GAIN = .15;

    private ThermalComfort() {}

    static double exposureRate(double felt, double coldLimit, double ageRate) {
        double deficit = coldLimit - felt;
        return deficit > 0 ? ageRate * deficit : Math.max(-2, 2 * deficit);
    }

    static boolean warmerRefuge(double here, double there, double hotLimit) {
        return there >= here + REFUGE_GAIN && there < hotLimit;
    }
}
