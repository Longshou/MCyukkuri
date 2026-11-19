package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.status.Identity;
import java.util.OptionalLong;

/** Learned habits are bounded around the pet's current temperament. */
public final class RulesMath {
    private RulesMath() {}

    public static int meekness(double patient, double timid) {
        return (int) Math.round(50 * patient + 50 * timid);
    }

    public static int capShift(int shift) { return Math.clamp(shift, -50, 50); }

    public static int obedience(int meekness, int shift) { return Math.clamp(meekness + shift, 0, 100); }

    public static boolean demandsSnack(int obedience) { return obedience < 65; }

    public static boolean drawnTo(Identity partner, int toward, int returned, int ownerTrust) {
        return partner.wild() && toward > ownerTrust
                && toward >= BondLevels.AFFINITY_COOPERATIVE && returned >= BondLevels.AFFINITY_COOPERATIVE;
    }

    public static final int DEPARTURE_PRESSURE = 70;

    /** A thirty-point preference for the partner is a strong bond. Smoothstep keeps modest motives weak. */
    public static double departureChance(float pressure, int neglect, int attachment, int obedience, int toward, int ownerTrust) {
        double discontent = (neglect + 100 - attachment) / 200.0;
        double pull = Temperament.ramp(pressure, DEPARTURE_PRESSURE, 100) * discontent * (1 - obedience / 100.0)
                * Temperament.ramp(toward - ownerTrust, 0, 30);
        return pull * pull * (3 - 2 * pull);
    }

    /** The wild adult weighs its own bond against wariness and the need to eat or rest. */
    public static boolean wantsToJoin(int returnBond, double timid, double nutrition, double energy) {
        double hunger = 1 - Temperament.ramp(nutrition, 40, 70);
        double fatigue = 1 - Temperament.ramp(energy, 30, 65);
        return Temperament.ramp(returnBond, 0, 30) > Math.max(.25 + .5 * timid, Math.max(hunger, fatigue));
    }

    public enum Meeting { CALLING, MISSED, APPROACHING, TOGETHER }

    public static Meeting meeting(boolean answered, boolean beside, long elapsed, int lookTicks) {
        if (answered) return beside ? Meeting.TOGETHER : Meeting.APPROACHING;
        return elapsed < lookTicks ? Meeting.CALLING : Meeting.MISSED;
    }

    public static String interrupted(boolean ownerNear, boolean together, boolean freeToTravel, boolean familyNeedsCare) {
        if (ownerNear) return "owner_intervened";
        if (!together) return "partner_lost";
        if (!freeToTravel) return "unable_to_travel";
        return familyNeedsCare ? "family_care" : null;
    }

    public static boolean departed(boolean petArrived, boolean wildArrived, double petFromYardSq,
                                   double wildFromYardSq, double gapSq) {
        return petArrived && wildArrived && petFromYardSq > 24 * 24 && wildFromYardSq > 24 * 24 && gapSq <= 4 * 4;
    }

    public enum Choice {
        ALLOW, HOLD, WAIT, BREAK;

        public boolean breached(boolean performed) { return this == BREAK && performed; }
    }

    public static double starvingGuard(double nutrition) { return Math.clamp((nutrition - 10) / 20, 0, 1); }

    /** A hesitation uses a quarter of the normal queue patience, up to four seconds. */
    public static int servingWait(int waitingTicks, int waitGrade) {
        return Math.min(80, (int) Math.round(waitingTicks * .25 * (1 + .5 * waitGrade)));
    }

    public static double hold(double patient, int obedience, double pressure) {
        return Math.clamp(.80 + .30 * (patient - .5) + .30 * (obedience / 100.0 - .5) - .40 * (pressure - .5), .05, .99);
    }

    public static Choice choose(double roll, double hold, double clever, boolean watched, double restraint) {
        if (roll < hold * restraint) return Choice.HOLD;
        return watched && clever * restraint >= .5 ? Choice.WAIT : Choice.BREAK;
    }

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
