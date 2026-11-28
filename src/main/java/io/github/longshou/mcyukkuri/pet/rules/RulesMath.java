package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.social.Temperament;
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

    public static boolean receptive(int bond, float pressure, int attachment) {
        return bond >= 0 && (Temperament.ramp(bond, 0, 30) + pressure / 100.0) / 2 > .20 + .45 * attachment / 100;
    }

    /** The visitor notices a healthy, well-groomed pet; a hungry or tired visitor tends to itself. */
    public static boolean lures(float pressure, double bold, double nutrition, double energy,
                                double petNutrition, double petHygiene, int affinity) {
        double attraction = (petNutrition + petHygiene) / 200;
        double desire = Temperament.ramp(pressure, 40, 100) * (.35 + .65 * bold) * (.35 + .65 * attraction);
        return affinity >= 0 && desire > Math.max(.42 - .12 * Temperament.ramp(affinity, 0, 30),
                Math.max(1 - Temperament.ramp(nutrition, 40, 70), 1 - Temperament.ramp(energy, 30, 65)));
    }

    public static int visitsAfterReturn(int visits) { return visits + 1; }
    public static boolean witnessedCounter(boolean owner, boolean seesPet, boolean seesWild) {
        return owner && (seesPet || seesWild);
    }
    public static int visitsAfterCounter(int visits) { return Math.max(0, visits - 2); }
    public static int bondChange(int before, int after) { return (after - before) * 6; }
    public static int nextVisitTicks(double timid) { return 3000 + (int) (6000 * timid); }

    /** A thirty-point preference for the partner is a strong bond. Smoothstep keeps modest motives weak. */
    public static double departureChance(float pressure, int neglect, int attachment, int obedience, int selfControl,
                                         int toward, int ownerTrust, int visits) {
        double discontent = (neglect + 100 - attachment) / 200.0;
        double pull = Temperament.ramp(pressure, 70, 100) * discontent * (1 - obedience / 100.0)
                * (1 - selfControl / 200.0) * Temperament.ramp(toward - ownerTrust, 0, 30)
                * Temperament.ramp(visits, 1, 2);
        return pull * pull * (3 - 2 * pull);
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

    /** One Minecraft day: how long heightened rule-specific caution decays after witnessing a housemate punished. */
    public static final int CAUTION_WINDOW = 24000;

    public static double cautionBonus(long remainingTicks) {
        return remainingTicks <= 0 ? 0 : .18 * Math.clamp(remainingTicks / (double) CAUTION_WINDOW, 0, 1);
    }

    public static double hold(double patient, int obedience, double pressure) {
        return hold(patient, obedience, pressure, 0);
    }

    public static double hold(double patient, int obedience, double pressure, double caution) {
        return Math.clamp(.80 + .30 * (patient - .5) + .30 * (obedience / 100.0 - .5) - .40 * (pressure - .5) + caution, .05, .99);
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

    public record BystanderLearning(int obedience, int fear, int cautionTicks) {}

    /**
     * A same-owner pet that sees or hears a housemate caught or punished learns a fraction of the lesson: small rise in
     * obedience and fear of the owner (no closeness loss) plus heightened caution for that rule.
     */
    public static BystanderLearning bystanderLearning(boolean punished, boolean blow, double timid) {
        if (!punished) {
            return new BystanderLearning(1, timid >= .5 ? 3 : 2, CAUTION_WINDOW / 2);
        }
        int obedience = blow ? 2 : 1;
        int fear = blow ? (int) Math.round(5 + 3 * timid) : (int) Math.round(3 + 2 * timid);
        return new BystanderLearning(obedience, fear, CAUTION_WINDOW);
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
