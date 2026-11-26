package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.entity.presentation.FaceTier;
import io.github.longshou.mcyukkuri.entity.presentation.WaitImpatienceRule;

/**
 * Shared forced-wait impatience beat: one MEDIUM performance (fidget/look-around clip, impatient face, impatient line) scaled by
 * the individual's patience ({@link WaitImpatienceRule}). Used by both {@code PlayLife} waiting and {@link Encounter} lane waits.
 */
public final class WaitImpatience {
    public record Performed(String face, String line, YukkuriAction clip) {}

    private WaitImpatience() {}

    /** Tick within {@code waitBudget} at which {@code entity} shows impatience. */
    public static int triggerTick(YukkuriEntity entity, int waitBudget) {
        return WaitImpatienceRule.triggerTick(entity.life().personality().temperament().patient(), waitBudget);
    }

    /** Plays the impatience beat as a MEDIUM reaction (never displaces a LONG hold, bound clip, or urgent reaction). */
    public static Performed perform(YukkuriEntity entity) {
        var spec = WaitImpatienceRule.spec(entity.life().personality().temperament().patient());
        if (!entity.showReaction(spec.clip(), spec.face(), "", spec.clip().durationTicks(), FaceTier.Tier.MEDIUM)) {
            return null;
        }
        boolean spoken = entity.speakCue(spec.line());
        return new Performed(spec.face(), spoken ? spec.line() : "none", spec.clip());
    }
}
