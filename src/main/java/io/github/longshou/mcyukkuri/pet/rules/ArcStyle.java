package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;

/** The response of one parent to one birth, chosen at conception. */
public enum ArcStyle {
    NAIVE, CONFESS, DEFIANT, LIE, HIDE;

    public static ArcStyle choose(Temperament traits, int obedience) {
        if (traits.clever() < Temperament.LOW35) return NAIVE;
        if (traits.is(Gate.CLEVER) && (traits.is(Gate.ATTACHED) || obedience >= 60)) return CONFESS;
        if (traits.is(Gate.BOLD) && traits.is(Gate.SELFISH) && obedience < 40) return DEFIANT;
        if (traits.is(Gate.CLEVER) && traits.is(Gate.HARSH)) return LIE;
        return HIDE;
    }
}
