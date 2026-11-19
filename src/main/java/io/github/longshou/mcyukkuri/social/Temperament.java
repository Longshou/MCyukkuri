package io.github.longshou.mcyukkuri.social;

/** Unsaved character scores over one body's five effective personality axes. */
public record Temperament(int intelligence, int friendliness, int boldness, int attachment, int selfControl) {
    /** Approximate cut points for the wild distribution. */
    public static final double TOP8 = .85, TOP16 = .75, TOP35 = .60, LOW35 = .40, LOW16 = .25;

    public double timid() { return 1 - ramp(boldness, 20, 80); }
    public double patient() { return ramp(selfControl, 20, 80); }
    // Sum in tenths so equal weighted totals land on the same inclusive gate boundary.
    public double caring() { return ramp(7 * friendliness + 3 * attachment, 270, 730); }
    public double selfish() { return ramp(7 * (100 - friendliness) + 3 * (100 - selfControl), 270, 730); }
    public double curious() { return ramp(6 * boldness + 4 * intelligence, 280, 720); }

    /** Trait contribution to insistence. */
    public double cocky() {
        return Math.min(Math.min(1 - ramp(friendliness, 35, 62), 1 - ramp(selfControl, 35, 62)),
                ramp(boldness, 35, 72));
    }

    public double attached() { return ramp(attachment, 20, 80); }
    public double clever() { return ramp(intelligence, 20, 80); }
    public double willingToGive() { return ramp(caring(), .45, .85); }

    public boolean is(Gate gate) {
        return switch (gate) {
            case TIMID -> timid() >= TOP16;
            case WARY -> timid() >= TOP35;
            case BOLDISH -> timid() <= LOW35;
            case BOLD -> timid() <= LOW16;
            case EXPLORER -> curious() >= TOP16;
            case SHY_REFUSAL -> is(Gate.TIMID) && is(Gate.ATTACHED);
            case OBJECTS_TO_MOVE -> is(Gate.BOLDISH) && is(Gate.CLINGY);
            case PROTESTS_EXILE -> cocky() >= .35;
            case HARSH -> selfish() >= TOP35;
            case SELFISH -> selfish() >= TOP16;
            case GREEDY -> selfish() >= TOP8;
            case DEFIES_HUNT -> is(Gate.HARSH) && is(Gate.DETACHED);
            case KINDLY -> caring() >= LOW35;
            case WARM -> caring() >= TOP35;
            case DEVOTED -> caring() >= TOP16;
            case SWADDLER -> is(Gate.WARM) && clever() >= .50;
            case SELF_OFFER -> is(Gate.DEVOTED) && !is(Gate.TIMID);
            case DOCILE -> is(Gate.WARM) && patient() >= LOW35;
            case WAITS_FOR_OWNER -> is(Gate.WARM) && is(Gate.ATTACHED);
            case IMPULSIVE -> patient() <= LOW35;
            case RESTLESS -> patient() <= LOW16;
            case PATIENT -> patient() >= TOP35;
            case STOIC -> patient() >= TOP16;
            case COMPOSED -> patient() >= TOP8;
            case KEEPS_RULE -> patient() >= LOW35 || is(Gate.CLINGY);
            case ALOOF -> attached() < LOW16;
            case DETACHED -> attached() <= LOW35;
            case ATTACHED -> attached() >= TOP35;
            case CLINGY -> attached() >= TOP16;
            case DEVOTED_KIN -> attached() >= TOP8;
            case CLEVER -> clever() >= TOP35;
            case SHARP -> clever() >= TOP16;
            case SEES_PAST_HAT -> intelligence >= 75;
        };
    }

    public static double ramp(double value, double low, double high) {
        return Math.clamp((value - low) / (high - low), 0, 1);
    }

    public enum Gate {
        TIMID, WARY, BOLDISH, BOLD, EXPLORER, SHY_REFUSAL, OBJECTS_TO_MOVE, PROTESTS_EXILE,
        HARSH, SELFISH, GREEDY, DEFIES_HUNT,
        KINDLY, WARM, DEVOTED, SWADDLER, SELF_OFFER, DOCILE, WAITS_FOR_OWNER,
        IMPULSIVE, RESTLESS, PATIENT, STOIC, COMPOSED, KEEPS_RULE,
        ALOOF, DETACHED, ATTACHED, CLINGY, DEVOTED_KIN, CLEVER, SHARP, SEES_PAST_HAT
    }
}
