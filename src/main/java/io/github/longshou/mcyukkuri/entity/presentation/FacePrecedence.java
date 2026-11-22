package io.github.longshou.mcyukkuri.entity.presentation;

import java.util.Set;

/** The one order in which the sources of a yukkuri's face compete; PresentationArbiter.face walks it top to bottom. */
public enum FacePrecedence {
    /** A physical operation (hat removal, pin, grip ...) owns the face. */
    OPERATION,
    /** Authored faces of a playing clip: gasp, sukkiri, cough, meal, sniff, home claim, Alice watch. */
    CLIP,
    /** The face of a live reaction event (fear, relief, newborn cry). */
    REACTION,
    /** A dying body shows blank_cry. */
    DYING,
    /** A short excited leak beat (startle, flow, side tears) shown over everything below, never over the ranks above. */
    LEAK,
    /** Pain and spicy food show cry. */
    PAIN,
    /** The published emotion course or mind face (grief, rage, joy, frenzy, withering). */
    COURSE,
    /** The after-effect drain face (teary or neutral) of a finished scene. */
    AFTERMATH,
    /** A weak or dejected body shows teary; low energy shows sleepy. */
    WEAKNESS,
    /** The resting face of the current moment or behavior. */
    REST;

    /** The highest-ranked source among those currently offering a face, or REST. */
    public static FacePrecedence winner(Set<FacePrecedence> offering) {
        for (var rank : values()) if (offering.contains(rank)) return rank;
        return REST;
    }
}
