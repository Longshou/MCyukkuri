package io.github.longshou.mcyukkuri.entity.presentation;

import java.util.Set;

/**
 * The one order in which the sources of a yukkuri's face compete; PresentationArbiter.face walks it top to bottom:
 * operation, clip, reaction, dying, leak, pain, lingering line face, emotion course, activity, aftermath, weakness, rest.
 * <p>
 * Reactions come in two classes (see {@link #reactionOffers}). A SIGNAL reaction is the body's answer to something that
 * happened to it (pain, fear, being caught, a parting, a performance beat) and holds the REACTION rank. A DECORATIVE reaction
 * is an ambient flourish (a learned-skill sparkle, a meal's face, a passing blink): it is shown only while no ACTIVITY face is
 * declared, otherwise it is dropped. Only the call that publishes a decorative reaction names it so
 * (YukkuriEntity.showAmbient); every other reaction is a signal.
 */
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
    /** The face of a reaction whose line is still being spoken: a beat's face holds until its line has finished. */
    LINGER,
    /** The published emotion course or mind face (grief, rage, joy, frenzy, withering). */
    COURSE,
    /** The authored face of what the body is doing right now (a hunt phase); the hunger and tiredness that drive it never repaint it. */
    ACTIVITY,
    /** The after-effect drain face (teary or neutral) of a finished scene. */
    AFTERMATH,
    /** A weak or dejected body shows teary; low energy shows sleepy. */
    WEAKNESS,
    /** The resting face of the current moment or behavior. */
    REST;

    /** Whether a reaction of this class may show its face now: a signal always, a decorative one only without a declared activity face. */
    public static boolean reactionOffers(boolean decorative, boolean activityDeclared) {
        return !decorative || !activityDeclared;
    }

    /** The highest-ranked source among those currently offering a face, or REST. */
    public static FacePrecedence winner(Set<FacePrecedence> offering) {
        for (var rank : values()) if (offering.contains(rank)) return rank;
        return REST;
    }
}
