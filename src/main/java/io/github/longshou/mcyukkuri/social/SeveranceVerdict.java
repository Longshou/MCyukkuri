package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.social.Temperament.Gate;

/** A guardian weighs a visible danger to the household (its severity) against its attachment to this child. */
public final class SeveranceVerdict {
    public record Inputs(Temperament t, double severity, int bond, boolean siblings) {}
    public enum Verdict { KEEP, DRIVE_TEARFUL, DRIVE_COLD }
    private static final double[] MOLD_SEVERITY = {0, .15, .35, .55, .65};
    /** The largest severity any danger reaches: past it the temperament, not the danger, decides. */
    private static final double MAX_SEVERITY = .65;

    private SeveranceVerdict() {}

    /** The severity of mould on the child: its stage, halved while the guardian does not know what it is. */
    public static double mold(int tier, boolean knows) {
        return MOLD_SEVERITY[tier] * (knows ? 1 : .5);
    }

    /** The severity of a hull carrying {@code ratio} times its capacity: 1.25x is .2, 2x is .5. */
    public static double overload(double ratio) {
        return Math.clamp(1 - 1 / ratio, 0, MAX_SEVERITY);
    }

    public static double resolve(Inputs in) {
        var t = in.t();
        return in.severity() + .40 * t.selfish() + .35 * t.patient()
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
