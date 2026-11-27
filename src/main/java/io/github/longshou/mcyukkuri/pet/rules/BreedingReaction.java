package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.social.Temperament;

/**
 * What one pet shows when it sees the owner flip the breeding house rule (after the rule-told bow). Pure: the same numbers always give
 * the same beat, so the difference between two pets is the difference between their numbers.
 */
public final class BreedingReaction {
    private BreedingReaction() {}

    /** The pet's one beat; {@code toward} is true when it glances at its partner afterwards. */
    public record Beat(String name, YukkuriAction action, String face, String event, int ticks, boolean toward) {}

    /** The bow of the rule-told is all it shows. */
    public static final Beat BOW_ONLY = new Beat("bow_only", null, "", "", 0, false);

    // pentUp runs 0-100 and a healthy adult reaches the middle band in about 1.4 days (PentUpMath); drive ramps over the band where
    // the pet starts to notice it (below 35 it hardly cares) to where it is pressing (75 and above).
    private static final double DRIVE_FROM = 25, DRIVE_TO = 75;
    private static final double NOTICES = .2, PRESSING = .6, FLUSHED = .5;
    /** A pet jumps for joy only when at least half-way bold (boldness 50 on the 20-80 ramp). */
    private static final double BOLD = .5;
    /** Fear of the owner from which a refusal makes it cower (the same band that makes a confiscated pet cower). */
    private static final int AFRAID = 30;
    /** Below this a pet answers a refusal with its own will; from here up it bows its head. */
    private static final int OBEDIENT = 50;
    /** Sharp and clinging are the top sixth of each axis (Temperament.TOP16). */
    private static final double SHARP_CLINGING = Temperament.TOP16;

    /**
     * @param allowed the rule was just turned off (breeding is allowed); false when it was turned on (refused)
     * @param pentUp  the pet's built-up urge, 0-100
     * @param obedience the pet's obedience, 0-100
     * @param fear    its fear of the owner, 0-100
     */
    public static Beat choose(boolean allowed, double pentUp, int obedience, int fear, Temperament temper) {
        double drive = Temperament.ramp(pentUp, DRIVE_FROM, DRIVE_TO);
        return allowed ? granted(drive, temper) : refused(drive, obedience, fear, temper);
    }

    private static Beat granted(double drive, Temperament temper) {
        if (drive < NOTICES) return BOW_ONLY;
        if (drive >= PRESSING && 1 - temper.timid() >= BOLD) return new Beat("allowed_joy", YukkuriAction.JOY_JUMP, "happy", "permit_glad", 50, true);
        return new Beat("allowed_shy", YukkuriAction.SHY, drive >= FLUSHED ? "flushed_smile" : "closed_smile", "permit_glad", 60, true);
    }

    private static Beat refused(double drive, int obedience, int fear, Temperament temper) {
        if (fear >= AFRAID) return new Beat("denied_cower", YukkuriAction.THUNDER_COWER, "afraid", "", 50, false);
        if (drive < NOTICES) return BOW_ONLY;
        if (drive >= PRESSING && obedience < OBEDIENT) return new Beat("denied_puff", YukkuriAction.PUFF, "cheek_puff", "sulk_huff", 60, false);
        if (temper.clever() >= SHARP_CLINGING && temper.attached() >= SHARP_CLINGING)
            return new Beat("denied_accept", YukkuriAction.BOW, "ashamed", "", 50, false);
        if (obedience >= OBEDIENT) return new Beat("denied_droop", YukkuriAction.DROOP, "teary", "", 60, false);
        return BOW_ONLY;
    }
}
