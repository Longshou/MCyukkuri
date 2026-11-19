package io.github.longshou.mcyukkuri.mind;

import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;

/** The same disposition drives the outward response and the feeling after being wronged. */
public final class Ousting {
    public enum Answer { CURSE, FAREWELL, SHRUG }

    private Ousting() {}

    public static double temper(Temperament t) {
        return (6 * Math.clamp(t.boldness() - 30, 0, 40) + 4 * Math.clamp(70 - t.selfControl(), 0, 40)) / 400.0;
    }

    public static boolean fumes(Temperament t) {
        return temper(t) >= .5 || t.is(Gate.PROTESTS_EXILE);
    }

    public static Answer answer(Temperament t) {
        if (fumes(t)) return Answer.CURSE;
        return t.is(Gate.ALOOF) ? Answer.SHRUG : Answer.FAREWELL;
    }
}
