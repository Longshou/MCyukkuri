package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.social.Temperament;
import java.util.List;

/**
 * Pure, stat-driven beats for house-rule performances:
 * <ul>
 *   <li>{@link #choose} — the pet whose own rule was just changed</li>
 *   <li>{@link #witness} — another owned pet who saw or heard the owner change a housemate's rule (its own rules stay untouched)</li>
 *   <li>{@link #hold} — a pet restraining itself and obeying a tempted rule</li>
 * </ul>
 * Every beat uses expressive library faces (never blank {@code look_down}, {@code pursed} or {@code endure}) and holds long enough
 * for its line to start cleanly.
 */
public final class RuleReaction {
    private RuleReaction() {}

    /** One beat; {@code toward} is true when the pet glances at the relevant peer (partner or housemate) alongside/after the beat. */
    public record Beat(String name, YukkuriAction action, String face, String event, int ticks, boolean toward) {}

    // How much the pet wants what the rule withholds, 0-100: below 25 it hardly cares, from 75 it is pressing.
    private static final double DRIVE_FROM = 25, DRIVE_TO = 75;
    private static final double NOTICES = .2, PRESSING = .6, FLUSHED = .5;
    /** A pet jumps for joy or puffs in envy only when at least half-way bold (boldness 50 on the 20-80 ramp). */
    private static final double BOLD = .5;
    /** Fear of the owner from which a tightened rule makes it cower; a milder threshold makes a witness flinch nervously. */
    private static final int AFRAID = 30, WARY_FEAR = 25;
    /** Below this a pet briefly pouts at a tightened rule before yielding; from here up it bows its head directly. */
    private static final int OBEDIENT = 50;
    /** Sharp and clinging are the top sixth of each axis (Temperament.TOP16). */
    private static final double SHARP_CLINGING = Temperament.TOP16;
    /** Each beat holds well above the 0.5s face-fit floor and long enough for its line to begin cleanly. */
    private static final int SHORT = 55, HELD = 65, HOLD_TICKS = 50;

    /**
     * Reaction of the target pet whose own rule was just toggled.
     *
     * @param loosened  true when the restriction was lifted; false when turned on
     * @param urge      how much this pet wants what the rule withholds, 0-1 ({@code Conscience.urge})
     * @param obedience this pet's obedience, 0-100
     * @param fear      this pet's fear of the owner, 0-100
     * @param glad      the glad line for a loosened rule that excites this pet ({@code permit_glad} for breeding, else {@code rule_loosen})
     */
    public static List<Beat> choose(boolean loosened, double urge, int obedience, int fear, Temperament temper, String glad) {
        double drive = Temperament.ramp(urge * 100, DRIVE_FROM, DRIVE_TO);
        return loosened ? loosened(drive, fear, temper, glad) : tightened(drive, obedience, fear, temper);
    }

    private static List<Beat> loosened(double drive, int fear, Temperament temper, String glad) {
        if (drive < NOTICES) {
            String face = temper.timid() >= .6 ? "shy_peek" : "closed_smile";
            return List.of(new Beat("loosen_nod", YukkuriAction.BOW, face, "rule_loosen", SHORT, false));
        }
        if (drive >= PRESSING && 1 - temper.timid() >= BOLD) {
            String thanksFace = fear >= 20 ? "shy_peek" : "open_smile";
            return List.of(
                    new Beat("loosen_joy", YukkuriAction.JOY_JUMP, "happy", glad, SHORT, true),
                    new Beat("loosen_thanks", YukkuriAction.BOW, thanksFace, "rule_grateful", HELD, false));
        }
        var shy = new Beat("loosen_shy", YukkuriAction.SHY, drive >= FLUSHED ? "flushed_smile" : "shy_peek", glad, HELD, true);
        if (fear >= AFRAID) {
            return List.of(shy, new Beat("loosen_thanks", YukkuriAction.BOW, "teary_smile", "rule_grateful", SHORT, false));
        }
        return List.of(shy);
    }

    private static List<Beat> tightened(double drive, int obedience, int fear, Temperament temper) {
        if (fear >= AFRAID) {
            return List.of(new Beat("tighten_cower", YukkuriAction.THUNDER_COWER, "afraid", "rule_cower", HELD, false));
        }
        if (drive >= PRESSING && obedience < OBEDIENT) {
            return List.of(
                    new Beat("tighten_puff", YukkuriAction.PUFF, "cheek_puff", "rule_sulk", SHORT, false),
                    new Beat("tighten_yield", YukkuriAction.DROOP, "ashamed", "rule_yield", HELD, false));
        }
        if (drive < NOTICES) {
            String face = obedience >= OBEDIENT || temper.attached() >= .5 ? "ashamed" : "confused_small";
            return List.of(new Beat("tighten_nod", YukkuriAction.BOW, face, "rule_tighten", SHORT, false));
        }
        if (temper.clever() >= SHARP_CLINGING && temper.attached() >= SHARP_CLINGING) {
            return List.of(new Beat("tighten_accept", YukkuriAction.BOW, "ashamed", "rule_tighten", HELD, false));
        }
        if (obedience >= OBEDIENT) {
            return List.of(new Beat("tighten_droop", YukkuriAction.DROOP, drive >= .5 ? "side_teary" : "sad", "rule_tighten_sad", HELD, false));
        }
        return List.of(new Beat("tighten_nod", YukkuriAction.BOW, "confused_small", "rule_tighten", SHORT, false));
    }

    /**
     * Reaction of another owned pet who saw or heard the owner change a housemate's rule. Returns empty when this witness is
     * indifferent (it only glances silently at the housemate without stealing the spotlight).
     */
    public static List<Beat> witness(boolean loosened, boolean witnessHasRule, double urge, int obedience, int fear, Temperament temper) {
        double drive = Temperament.ramp(urge * 100, DRIVE_FROM, DRIVE_TO);
        if (loosened) {
            if (witnessHasRule && drive >= .45) {
                boolean bold = 1 - temper.timid() >= BOLD || temper.selfish() >= .5;
                return List.of(new Beat("witness_envy", bold ? YukkuriAction.PUFF : YukkuriAction.DROOP,
                        bold ? "hmph" : "side_teary", "rule_witness_envy", SHORT, true));
            }
            return List.of();
        }
        if (fear >= WARY_FEAR || temper.timid() >= .65) {
            return List.of(new Beat("witness_wary", fear >= AFRAID ? YukkuriAction.TREMBLE : YukkuriAction.SHY,
                    fear >= AFRAID ? "afraid" : "confused_small", "rule_witness_wary", SHORT, false));
        }
        if (witnessHasRule && drive >= .5) {
            return List.of(new Beat("witness_sympathy", YukkuriAction.DROOP, "sad", "rule_witness_wary", SHORT, true));
        }
        return List.of();
    }

    /**
     * Expressive restraint beat when a pet holds itself back from breaking a house rule ({@code Choice.HOLD}).
     */
    public static Beat hold(HouseRule rule, double pressure, int obedience, int fear, Temperament temper) {
        String event = switch (rule) {
            case NO_SINGING -> pressure >= .35 ? "rule_hold_song" : "rule_hold";
            case NO_EATING_UNASKED -> "rule_hold_eat";
            case NO_LEAVING_YARD -> "rule_hold_yard";
            case NO_BULLYING -> "rule_hold_bully";
            default -> "rule_hold";
        };
        if (fear >= WARY_FEAR || (temper.timid() >= .65 && pressure >= .5)) {
            return new Beat("hold_wary", YukkuriAction.SHY, fear >= AFRAID ? "afraid" : "confused_small", event, HOLD_TICKS, false);
        }
        if (pressure >= .6 && obedience < OBEDIENT) {
            return new Beat("hold_reluctant", YukkuriAction.DROOP, "down_angry", event, HOLD_TICKS, false);
        }
        if (pressure >= .6) {
            return new Beat("hold_sad", YukkuriAction.DROOP, pressure >= .75 ? "side_teary" : "sad", event, HOLD_TICKS, false);
        }
        if (temper.attached() >= .5 || obedience >= OBEDIENT) {
            return new Beat("hold_meek", YukkuriAction.BOW, "ashamed", event, HOLD_TICKS, false);
        }
        return new Beat("hold_shy", YukkuriAction.DROOP, "shy_peek", event, HOLD_TICKS, false);
    }
}
