package io.github.longshou.mcyukkuri.perception;

/** A sound a body can hear: loudness is its open-air carry in blocks. A cue is added with the first caller that makes it. */
public enum Cue {
    POUR_BUCKET(8, Need.WATER), POUR_BOTTLE(6, Need.WATER), FOOD_IN_BOWL(6, Need.FOOD);

    /** The need a cue speaks to: a body feeling it hears and sees such cues farther. */
    public enum Need { WATER, FOOD }

    public final double loudness;
    public final Need need;

    Cue(double loudness, Need need) { this.loudness = loudness; this.need = need; }
}
