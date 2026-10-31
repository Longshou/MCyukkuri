package io.github.longshou.mcyukkuri.intent;

/** The scoring and switching rule without Minecraft types: {@code score = clamp(desire, 0, 100) * weight - cost}, and a switch needs the held commitment's margin ten times running. */
public final class DriveMath {
    public static final double SWITCH_FACTOR = 1.15, SWITCH_SLACK = 5, SOFT_FACTOR = 2, DESIRE_MAX = 100;
    /** Consecutive evaluations the margin must hold (evaluations, not ticks); ticks between two evaluations. */
    public static final int SUSTAIN_EVALUATIONS = 10, EVALUATE_EVERY = 10;

    private DriveMath() {}

    /** A raw desire on 0-100; a non-finite one counts as none. */
    public static double desire(double raw) { return Double.isNaN(raw) ? 0 : Math.min(Math.max(raw, 0), DESIRE_MAX); }

    /** {@code clamp(desire, 0, 100) * weight - cost}; weight and cost are floored at 0. */
    public static double score(double desire, double weight, double costEstimate) {
        double w = Double.isFinite(weight) ? Math.max(0, weight) : 0, c = Double.isFinite(costEstimate) ? Math.max(0, costEstimate) : 0;
        return desire(desire) * w - c;
    }

    /** Due once the next body-staggered interval since the last evaluation has elapsed, even if its tick was not polled. */
    public static boolean due(long gameTime, long evaluatedAt, int entityId) {
        return gameTime - evaluatedAt >= EVALUATE_EVERY - Math.floorMod(evaluatedAt - entityId, EVALUATE_EVERY);
    }

    /** Whether {@code candidate} may take the body from an intent held with {@code commitment}: NONE needs {@code current * 1.15 + 5}, SOFT {@code * 2 + 5}, HARD a danger desire. */
    public static boolean switchable(double candidate, double current, Commitment commitment, boolean danger) {
        Commitment held = commitment == null ? Commitment.NONE : commitment;
        if (held == Commitment.HARD) return danger;
        double factor = danger ? 1 : held == Commitment.SOFT ? SOFT_FACTOR : SWITCH_FACTOR;
        return candidate >= current * factor + (danger ? 0 : SWITCH_SLACK);
    }

    /** A danger desire preempts on the evaluation that notices it; the sustain is for ordinary desires. */
    public static boolean needsSustain(boolean danger) { return !danger; }

    /** The ten-evaluation sustain: which candidate has beaten the current intent, for how many evaluations running. */
    public static final class Sustain {
        private String candidate;
        private int streak;

        /** Counts one evaluation; a different candidate or a failing one starts over. True once the streak reaches ten. */
        public boolean observe(String candidateId, boolean holds) {
            if (!holds) { reset(); return false; }
            streak = candidateId != null && candidateId.equals(candidate) ? streak + 1 : 1;
            candidate = candidateId;
            return streak >= SUSTAIN_EVALUATIONS;
        }

        public String candidate() { return candidate; }
        public int streak() { return streak; }
        public void reset() { candidate = null; streak = 0; }
    }
}
