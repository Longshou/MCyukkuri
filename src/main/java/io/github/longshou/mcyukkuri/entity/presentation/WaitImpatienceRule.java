package io.github.longshou.mcyukkuri.entity.presentation;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.social.Temperament;

/**
 * Pure stat-driven rule for the single mid-wait impatience beat shared by playground waiting ({@code PlayLife}) and forced
 * lane waiting ({@code Encounter}). No Minecraft or entity types: unit-tested directly.
 */
public final class WaitImpatienceRule {
    /** Earliest and latest share of the individual's wait budget at which the impatience beat fires (0.50..0.75, ~0.625 average). */
    public static final double EARLY_SHARE = 0.50;
    public static final double SHARE_SPAN = 0.25;

    public record Spec(String face, String line, YukkuriAction clip) {}

    private WaitImpatienceRule() {}

    /** Tick within {@code waitBudget} at which the impatience beat fires: earlier for an impatient body, later for a patient one. */
    public static int triggerTick(double patient, int waitBudget) {
        double share = EARLY_SHARE + SHARE_SPAN * Math.clamp(patient, 0.0, 1.0);
        return Math.max(1, (int) Math.round(Math.max(1, waitBudget) * share));
    }

    /** Face, dialogue event and fidget/look-around clip for the impatience beat, scaled in sharpness by {@code patient} (0..1); never escalates to puff. */
    public static Spec spec(double patient) {
        if (patient < Temperament.LOW35) {
            return new Spec("down_angry", "wait_impatient", YukkuriAction.MOOD_FLICK);
        }
        if (patient < Temperament.TOP35) {
            return new Spec("hmph", "wait_impatient", YukkuriAction.LOOK_AROUND);
        }
        return new Spec("pursed", "wait_quiet", YukkuriAction.LOOK_AROUND);
    }
}
