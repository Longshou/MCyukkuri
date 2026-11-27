package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.social.Temperament;
import java.util.List;

/**
 * What one pet shows when it sees or hears the owner change a house rule (after the rule-told bow), for every rule alike. Pure: the
 * same numbers always give the same beats, so the difference between two pets is the difference between their numbers. Every beat is
 * the reaction of a low pet to the one it fears and reveres: it bows, cowers, thanks or sulks within limits, and the sulker still yields.
 */
public final class RuleReaction {
    private RuleReaction() {}

    /** One beat; {@code toward} is true when the pet glances at the one the rule is about (its partner) afterwards. */
    public record Beat(String name, YukkuriAction action, String face, String event, int ticks, boolean toward) {}

    // How much the pet wants what the rule withholds, 0-100: below 25 it hardly cares, from 75 it is pressing (a healthy adult's
    // breeding urge reaches the middle band in about 1.4 days, PentUpMath; the other rules use their own tempting pressure).
    private static final double DRIVE_FROM = 25, DRIVE_TO = 75;
    private static final double NOTICES = .2, PRESSING = .6, FLUSHED = .5;
    /** A pet jumps for joy only when at least half-way bold (boldness 50 on the 20-80 ramp). */
    private static final double BOLD = .5;
    /** Fear of the owner from which a new rule makes it cower (the same band that makes a confiscated pet cower). */
    private static final int AFRAID = 30;
    /** Below this a pet answers a new rule with its own will; from here up it bows its head. */
    private static final int OBEDIENT = 50;
    /** Sharp and clinging are the top sixth of each axis (Temperament.TOP16). */
    private static final double SHARP_CLINGING = Temperament.TOP16;
    /** Each beat holds long enough for its line (a line takes about 70-100 ticks unvoiced) to start inside it. */
    private static final int SHORT = 50, HELD = 60;

    /**
     * @param loosened  the rule was just turned off; false when it was turned on (a new restriction)
     * @param urge      how much the pet wants what the rule withholds, 0-1 (see {@code Conscience.urge})
     * @param obedience the pet's obedience, 0-100
     * @param fear      its fear of the owner, 0-100
     * @param glad      the line it says when a loosened rule delights it ({@code permit_glad} for the breeding rule, else {@code rule_loosen})
     */
    public static List<Beat> choose(boolean loosened, double urge, int obedience, int fear, Temperament temper, String glad) {
        double drive = Temperament.ramp(urge * 100, DRIVE_FROM, DRIVE_TO);
        return loosened ? loosened(drive, temper, glad) : tightened(drive, obedience, fear, temper);
    }

    private static List<Beat> loosened(double drive, Temperament temper, String glad) {
        if (drive < NOTICES) return List.of(new Beat("loosen_nod", YukkuriAction.BOW, "closed_smile", glad, SHORT, false));
        if (drive >= PRESSING && 1 - temper.timid() >= BOLD)
            return List.of(new Beat("loosen_joy", YukkuriAction.JOY_JUMP, "happy", glad, SHORT, true),
                    new Beat("loosen_thanks", YukkuriAction.BOW, "closed_smile", "rule_grateful", HELD, false));
        return List.of(new Beat("loosen_shy", YukkuriAction.SHY, drive >= FLUSHED ? "flushed_smile" : "closed_smile", glad, HELD, true));
    }

    private static List<Beat> tightened(double drive, int obedience, int fear, Temperament temper) {
        if (fear >= AFRAID) return List.of(new Beat("tighten_cower", YukkuriAction.THUNDER_COWER, "afraid", "rule_cower", HELD, false));
        if (drive < NOTICES) return List.of(new Beat("tighten_nod", YukkuriAction.BOW, "look_down", "rule_tighten", SHORT, false));
        if (drive >= PRESSING && obedience < OBEDIENT)
            return List.of(new Beat("tighten_puff", YukkuriAction.PUFF, "cheek_puff", "sulk_huff", HELD, false),
                    new Beat("tighten_yield", YukkuriAction.BOW, "ashamed", "rule_yield", HELD, false));
        if (temper.clever() >= SHARP_CLINGING && temper.attached() >= SHARP_CLINGING)
            return List.of(new Beat("tighten_accept", YukkuriAction.BOW, "ashamed", "rule_tighten", SHORT, false));
        if (obedience >= OBEDIENT) return List.of(new Beat("tighten_droop", YukkuriAction.DROOP, "teary", "rule_tighten", HELD, false));
        return List.of(new Beat("tighten_nod", YukkuriAction.BOW, "look_down", "rule_tighten", SHORT, false));
    }
}
