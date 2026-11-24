package io.github.longshou.mcyukkuri.perception;

/**
 * A sound a body can hear: loudness is its open-air carry in blocks. A cue is added with the first caller that makes it.
 * A cue may speak to a need (a body feeling it hears and sees such cues farther) or to none; a loud cue still reaches a sleeper
 * (at a fraction of its reach), a mild one does not.
 */
public enum Cue {
    POUR_BUCKET(8, Need.WATER, false), POUR_BOTTLE(6, Need.WATER, false), FOOD_IN_BOWL(6, Need.FOOD, false),
    DEPARTURE_CRY(12, null, false), PICKUP_ITEM(5, null, false), INSULT(12, null, false);

    public enum Need { WATER, FOOD, PLAY }

    public final double loudness;
    /** The need the cue answers, or null. */
    public final Need need;
    /** Whether a sleeping body can be woken by it. */
    public final boolean loud;

    Cue(double loudness, Need need, boolean loud) { this.loudness = loudness; this.need = need; this.loud = loud; }
}
