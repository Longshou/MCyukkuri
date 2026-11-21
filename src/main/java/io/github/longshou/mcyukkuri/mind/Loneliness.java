package io.github.longshou.mcyukkuri.mind;

import io.github.longshou.mcyukkuri.social.Temperament;

/**
 * Being left alone (docs/FEATURES.md 情绪链): a baby that has seen no living yukkuri, player or human NPC for a whole
 * game day starts feeling it. How hard depends on how much company it wants and how little nerve it has, so a detached
 * or bold baby holds its ground under the ordinary ease relief while an attached, timid one climbs to the 非油症 chain.
 */
public final class Loneliness {
    /** A whole game day in sight of nobody before being alone presses at all. */
    public static final int GRACE = 24000;
    /** Any living yukkuri, player or human NPC closer than this keeps a baby company. */
    public static final double SIGHT = 16;
    /** Unease per minute at the least and the most needing end of the range. */
    public static final float LEAST = .5f, MOST = 3.5f;

    private Loneliness() {}

    /** Attached and timid babies need company most; a bold or detached one barely minds. */
    public static float ratePerMinute(Temperament temperament) {
        return LEAST + (MOST - LEAST) * (float) (temperament.attached() * temperament.timid());
    }
}
