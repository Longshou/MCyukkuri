package io.github.longshou.mcyukkuri.pet.rules;

import java.util.OptionalLong;

/** Learned habits are bounded around the pet's current temperament. */
public final class RulesMath {
    private RulesMath() {}

    public static int meekness(double patient, double timid) {
        return (int) Math.round(50 * patient + 50 * timid);
    }

    public static int capShift(int shift) { return Math.clamp(shift, -50, 50); }

    public static int obedience(int meekness, int shift) { return Math.clamp(meekness + shift, 0, 100); }

    public record Discipline(int closeness, int learning) {}

    public static Discipline discipline(boolean owner, boolean hurt, OptionalLong breachAge) {
        if (!owner) return new Discipline(hurt ? -20 : -6, 0);
        int gain = hurt ? 4 : 2;
        // A punishment within one minute of the deed makes twice the impression.
        boolean fresh = breachAge.isPresent() && breachAge.getAsLong() <= 1200;
        return new Discipline(0, fresh ? 2 * gain : gain);
    }

    public enum Feedback {
        PRAISE(1), CORRECTION(1), REWARD(1), SWEET(-3), DEMAND(-3), FAVOR(-2);

        public final int gain;
        Feedback(int gain) { this.gain = gain; }

        public static Feedback sweet(boolean earned, boolean demanded) {
            return earned ? REWARD : demanded ? DEMAND : SWEET;
        }
    }
}
