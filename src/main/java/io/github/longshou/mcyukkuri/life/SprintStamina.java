package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriVariant;

/** A short-term locomotion reserve. The species row gives full-effort and full-rest ticks for a fit adult. */
public final class SprintStamina {
    public enum Build {
        ORDINARY(85, 90), ATHLETIC(115, 85), MYON(140, 80), HOUSE(58, 95),
        CAT(65, 50), FRAIL(38, 115), FLYER(680, 110);

        final int burst, recovery;
        Build(int burst, int recovery) { this.burst = burst; this.recovery = recovery; }

        static Build of(YukkuriVariant variant) {
            return switch (variant) {
                case REIMU, ALICE -> ORDINARY;
                case MARISA -> ATHLETIC;
                case MYON -> MYON;
                case HOUSE_REIMU, HOUSE_MARISA -> HOUSE;
                case CHEN -> CAT;
                case PATCHOULI -> FRAIL;
                case REMILIA, FLANDRE -> FLYER;
            };
        }
    }

    // Zero debt is a fresh body, including a save made before sprint stamina existed.
    private double debt;
    private boolean resting;

    double debt() { return debt; }
    boolean resting() { return resting; }
    void restore(double debt, boolean resting) { this.debt = debt; this.resting = resting; }

    /** Growth supplies the young body's stride; excess bulk adds load, including a Deibu's actual enlarged body. */
    static double capacity(Build build, double fitness, double growth, double size, double nutrition, boolean pet) {
        return build.burst * fitness * growth * (.7 + .3 * nutrition / 100)
                * (pet ? .85 : 1) / (1 + Math.abs(size - 1) * .5);
    }

    void run(double capacity) {
        debt = Math.min(1, debt + 1 / capacity);
        resting = debt == 1;
    }

    void recover(Build build) {
        debt = Math.max(0, debt - 1.0 / build.recovery);
        if (debt <= .20) resting = false;
    }

    /** Ease out of a burst over its last quarter. Spent wings still provide lift at a cruising pace. */
    double pace(boolean flying) {
        if (resting) return flying ? .5 : 0;
        return 1 - Math.max(0, debt - .75) * 1.6;
    }
}
