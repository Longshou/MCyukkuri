package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;

/**
 * The response of one parent to an unauthorized brood, chosen at conception.
 * Ordinary pets never talk back rudely to their owner: {@link #BOAST} is a bold, self-centred, low-obedience pet that does
 * not see its fault (or expects the owner to be "yukkuri" and praise its babies) until the owner's answer resolves it.
 */
public enum ArcStyle {
    NAIVE, CONFESS, BOAST, LIE, HIDE;

    public static ArcStyle choose(Temperament traits, int obedience) {
        if (traits.clever() < Temperament.LOW35) return NAIVE;
        if (traits.is(Gate.CLEVER) && (traits.is(Gate.ATTACHED) || obedience >= 60)) return CONFESS;
        if (traits.is(Gate.BOLD) && traits.is(Gate.SELFISH) && obedience < 40) return BOAST;
        if (traits.is(Gate.CLEVER) && traits.is(Gate.HARSH)) return LIE;
        return HIDE;
    }
}
