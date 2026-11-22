package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriVariant;

/**
 * A short-term locomotion reserve. The species row gives full-effort and full-rest ticks for a fit adult of average
 * constitution; growth stage and the inherited constitution move both. It is slow to spend and slower to refill, and it
 * only recovers its drive after resting well below the line where it ran out, so a body does not flip between
 * exhausted and recovered.
 */
public final class SprintStamina {
    public enum Build {
        ORDINARY(170, 225), ATHLETIC(230, 215), MYON(280, 200), HOUSE(116, 240),
        CAT(130, 125), FRAIL(76, 290), FLYER(1360, 275);

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

    /** A rested body takes up the drive again at this debt, well below the full debt that stopped it. */
    private static final double RESUME = .15;

    // Zero debt is a fresh body, including a save made before sprint stamina existed.
    private double debt;
    private boolean resting;

    double debt() { return debt; }
    boolean resting() { return resting; }
    void restore(double debt, boolean resting) { this.debt = debt; this.resting = resting; }

    /**
     * Inherited constitution, 0.8 to 1.2: the mean of the two innate axes that carry spirit and perseverance
     * (boldness and self-control, percent). Birth inheritance already blends the parents and adds its own spread.
     */
    static double constitution(int boldness, int selfControl) {
        return .8 + .4 * (Math.clamp(boldness, 0, 100) + Math.clamp(selfControl, 0, 100)) / 200;
    }

    /** Growth supplies the young body's stride; excess bulk adds load, including a Deibu's actual enlarged body. */
    static double capacity(Build build, double fitness, double growth, double size, double nutrition, boolean pet,
                           double constitution) {
        return build.burst * fitness * growth * constitution * (.7 + .3 * nutrition / 100)
                * (pet ? .85 : 1) / (1 + Math.abs(size - 1) * .5);
    }

    /** Ticks of full rest: a young body bounces back sooner, a hardy one sooner still. */
    static double restTicks(Build build, double growth, double constitution) {
        return build.recovery * (.5 + .5 * Math.clamp(growth, 0, 1)) / constitution;
    }

    void run(double capacity) {
        debt = Math.min(1, debt + 1 / capacity);
        resting = debt == 1;
    }

    void recover(double restTicks) {
        debt = Math.max(0, debt - 1 / restTicks);
        if (debt <= RESUME) resting = false;
    }

    /** Ease out of a burst over its last quarter, then recover at half pace. */
    double pace() {
        if (resting) return .5;
        return 1 - Math.max(0, debt - .75) * 1.6;
    }
}
