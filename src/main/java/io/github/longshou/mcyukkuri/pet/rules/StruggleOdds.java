package io.github.longshou.mcyukkuri.pet.rules;

import static io.github.longshou.mcyukkuri.social.Temperament.ramp;

/**
 * A pet paying penance sees the owner punish its own young: the pull to guard the child against the pull to trust that the owner
 * is right. Pure scores over ramp ranges (no thresholds); the strongest one is the way the parent ends its struggle. Fear is how
 * obedience is taught, not a penalty on the owner: it weakens the guard, never the owner's standing.
 */
public final class StruggleOdds {
    public enum Choice { SHIELD, PLEAD, SUBMIT, ESCAPE }

    /** The inputs are the parent's own numbers; {@code mobile} is false for a body that cannot step (weak, leashed, off the ground). */
    public record Scores(double guard, double trust, double shield, double plead, double submit, double escape) {
        public Choice choice() {
            var best = Choice.SHIELD;
            double top = shield;
            if (plead > top) { top = plead; best = Choice.PLEAD; }
            if (submit > top) { top = submit; best = Choice.SUBMIT; }
            if (escape > top) best = Choice.ESCAPE;
            return best;
        }

        /** Sixty to a hundred and twenty ticks: the closer the two pulls, the longer the parent hesitates. */
        public int struggleTicks() { return 60 + (int) Math.round(60 * (1 - Math.abs(guard - trust))); }
    }

    private StruggleOdds() {}

    /** @param closeness the parent's bond to the punished child; {@code fear} its fear of the owner; {@code guilt} 0..1 as the penance scores it */
    public static Scores score(int attachment, int boldness, int closeness, int fear, int obedience, double guilt, boolean mobile) {
        double attached = ramp(attachment, 30, 80), bold = ramp(boldness, 35, 80), near = ramp(closeness, 10, 65);
        double afraid = ramp(fear, 15, 50), obey = ramp(obedience, 20, 80);
        double guard = attached * (.6 + .3 * near + .1 * bold) * (1 - .5 * afraid);
        double trust = obey * (.5 + .5 * guilt);
        // Stepping in front needs a body that can step: the weak and the leashed plead instead, with the same pull.
        double shield = mobile ? guard * (.3 + .7 * bold) : 0;
        double plead = (guard + trust) / 2 * (1 - .5 * bold) + (mobile ? 0 : .7 * guard * bold);
        // Only a frightened parent that does not cling to the child and was never taught to obey runs: today's flight, made rare.
        double escape = mobile ? afraid * (1 - attached) * (1 - .5 * obey) : 0;
        return new Scores(guard, trust, shield, plead, trust, escape);
    }
}
