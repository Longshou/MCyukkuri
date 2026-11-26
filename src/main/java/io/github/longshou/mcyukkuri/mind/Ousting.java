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
        return fumes(t, 0);
    }

    /** {@code anger} is the temper a hurt of its own adds (0 to 1): the range of those who flare up shifts with it. */
    public static boolean fumes(Temperament t, double anger) {
        return temper(t) + anger >= .5 || t.is(Gate.PROTESTS_EXILE);
    }

    public static Answer answer(Temperament t) {
        return answer(t, 0);
    }

    public static Answer answer(Temperament t, double anger) {
        if (fumes(t, anger)) return Answer.CURSE;
        return t.is(Gate.ALOOF) ? Answer.SHRUG : Answer.FAREWELL;
    }
}
