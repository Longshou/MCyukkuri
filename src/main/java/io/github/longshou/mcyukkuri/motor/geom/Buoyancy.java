package io.github.longshou.mcyukkuri.motor.geom;

/** The body's water lift. No Minecraft types. */
public final class Buoyancy {
    private Buoyancy() {}

    /** Neutral near 56% immersion against water gravity; fatigue never removes this lift. */
    public static double bodyLift(double fraction, double verticalVelocity) {
        return Math.clamp(.005 + .045 * (fraction - .56) - .08 * verticalVelocity, -.018, .032);
    }

}
