package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;

/** A guardian weighs the visible danger against its attachment to this child. */
public final class MoldVerdict {
    public record Inputs(Temperament t, int tier, boolean knows, int bond, boolean siblings) {}
    public enum Verdict { KEEP, DRIVE_TEARFUL, DRIVE_COLD }
    private static final double[] SEVERITY = {0, .15, .35, .55, .65};

    private MoldVerdict() {}

    public static double resolve(Inputs in) {
        var t = in.t();
        return SEVERITY[in.tier()] * (in.knows() ? 1 : .5) + .40 * t.selfish() + .35 * t.patient()
                - .45 * t.attached() - .30 * Temperament.ramp(in.bond(), 0, 60) + (in.siblings() ? .15 * t.caring() : 0);
    }

    public static Verdict decide(Inputs in) {
        return decide(in.t(), resolve(in));
    }

    static Verdict decide(Temperament t, double resolve) {
        if (resolve < .40) return Verdict.KEEP;
        return !t.is(Gate.ATTACHED) && t.is(Gate.HARSH) ? Verdict.DRIVE_COLD : Verdict.DRIVE_TEARFUL;
    }
}
