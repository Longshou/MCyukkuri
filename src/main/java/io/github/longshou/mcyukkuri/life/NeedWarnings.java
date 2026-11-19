package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.social.Temperament;

/** One warning-band width is one unit of severity. Declaration order breaks physical ties. */
public record NeedWarnings(int mask, Need leading) {
    public enum Need {
        URINE, STOOL, THIRST, SLEEP, HUNGER, COLD, OVERHEATING, BREEDING;

        public int bit() { return 1 << ordinal(); }
    }

    private static final Need[] ORDER = Need.values();

    public static NeedWarnings sample(float urine, float bowel, float dryness, float energy, float nutrition,
                                      double felt, double cold, double hot, boolean breeding) {
        // Start warning in the last tenth of the configured comfort span on either side.
        double margin = (hot - cold) * .1;
        int mask = 0;
        Need leading = null;
        double strongest = -1;
        for (var need : ORDER) {
            double severity = switch (need) {
                case URINE -> (urine - 30) / 10.0;
                case STOOL -> (bowel - 30) / 10.0;
                case THIRST -> (dryness - 30) / 10.0;
                case SLEEP -> (40 - energy) / 15.0;
                case HUNGER -> (50 - nutrition) / 20.0;
                case COLD -> (cold + margin - felt) / margin;
                case OVERHEATING -> (felt - (hot - margin)) / margin;
                case BREEDING -> breeding ? 0 : -1;
            };
            if (severity < 0) continue;
            mask |= need.bit();
            if (need != Need.BREEDING && severity > strongest) {
                leading = need;
                strongest = severity;
            }
        }
        return new NeedWarnings(mask, leading);
    }

    public static double insistence(Temperament traits) {
        return (2 + traits.selfish() + traits.cocky() - traits.patient() - traits.timid()) / 4;
    }
}
