package io.github.longshou.mcyukkuri.mind;

import java.util.SplittableRandom;

/** Pure leak roll and presentation beat for a spike in emotion. */
public final class ExcitedLeak {
    public static final String STARTLE_FACE = "leak_startle", FLOW_FACE = "leak_flow";
    public static final String PUDDLE = "excited_leak";
    public static final float STARTLED_FALL_DISTANCE = 1.5f;
    public static final int TRACE_LIFETIME = 800, TRACE_FADE_TICKS = 200;
    public static final float STREAM_LIFT = .01f;

    public enum Stimulus {
        SCARE(false), SCOLD(false), LOUD_NOISE(false), JOY(false),
        SUDDEN_LIFT(true), DROP(true), PREDATOR(true), DYING(true);

        private final boolean strong;
        Stimulus(boolean strong) { this.strong = strong; }
    }

    public record Outcome(float roll, float chance, Beat beat) {}

    public record Beat(long start, long flowAt, long puddleAt, long endAt, float width, float amount, boolean joy) {
        boolean cooling(long now) { return now < start + 1200; }

        public String face(long now) {
            if (now < flowAt) return STARTLE_FACE;
            if (now < puddleAt) return FLOW_FACE;
            if (now < endAt) return joy ? "satisfied" : "side_teary";
            return "";
        }
    }

    public static Outcome roll(String stage, int control, Stimulus stimulus, long seed, long now,
                               float pressure, float bodyWidth) {
        var profile = LeakProfile.valueOf(stage);
        float chance = profile.chance(stimulus.strong, control, pressure);
        var random = new SplittableRandom(seed);
        float roll = random.nextFloat();
        if (!new LeakProfile.Draw(roll, chance).wins()) return new Outcome(roll, chance, null);
        float amount = profile.portion(control, pressure);
        long flowAt = now + random.nextInt(6, 11), puddleAt = flowAt + profile.flow(control);
        return new Outcome(roll, chance, new Beat(now, flowAt, puddleAt, puddleAt + random.nextInt(60, 101),
                profile.width(control, bodyWidth, amount), amount, stimulus == Stimulus.JOY));
    }

    public static int opacity(long ticksSincePuddle) {
        return Math.round(255 * Math.clamp((TRACE_LIFETIME - ticksSincePuddle) / (float) TRACE_FADE_TICKS, 0f, 1f));
    }

    public static double puddleReach(float width) { return .2 * width; }

    private ExcitedLeak() {}
}
