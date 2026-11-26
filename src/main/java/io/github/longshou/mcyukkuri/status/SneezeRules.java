package io.github.longshou.mcyukkuri.status;

/**
 * Pure sneeze chance, intensity and cooldown math (no Minecraft dependencies, unit-tested in CI).
 * Ranges shift smoothly with the individual's age, frailty and health, with a hard cooldown floor
 * so a sneeze can never repeat in a loop.
 */
public final class SneezeRules {
    public enum Trigger {
        DUST("dust"),
        COLD("cold");

        private final String id;

        Trigger(String id) { this.id = id; }

        public String id() { return id; }
    }

    /** Cooldown bounds for a healthy adult (12–24 s), scaled down slightly for frailer or younger bodies, never under 8 s. */
    public static final int BASE_MIN_COOLDOWN = 240;
    public static final int BASE_MAX_COOLDOWN = 480;
    public static final int MIN_COOLDOWN = 160;
    public static final double MIN_SUSCEPTIBILITY = 1.0;
    public static final double MAX_SUSCEPTIBILITY = 2.2;
    /** Base per-check probabilities at full intensity (intensity = 1, susceptibility = 1) and hard caps. */
    public static final double DUST_BASE_CHANCE = 0.08;
    public static final double DUST_MAX_CHANCE = 0.35;
    public static final double COLD_BASE_CHANCE = 0.025;
    public static final double COLD_MAX_CHANCE = 0.20;
    /** Wetness needed to chill a body even slightly above the cold line. */
    public static final double CHILLED_WETNESS = 30.0;
    public static final double CHILLED_TEMP_MARGIN = 0.25;

    /**
     * Individual susceptibility multiplier in [{@link #MIN_SUSCEPTIBILITY}, {@link #MAX_SUSCEPTIBILITY}].
     * Younger, frailer, weak, injured or canonically delicate (Patchouli) bodies sneeze more easily.
     */
    public static double susceptibility(boolean baby, boolean juvenile, boolean frail, boolean weak,
                                        boolean patchouli, double healthRatio) {
        double factor = 1.0;
        if (baby) factor += 0.45;
        else if (juvenile) factor += 0.20;
        if (weak) factor += 0.35;
        if (frail) factor += 0.25;
        if (patchouli) factor += 0.25;
        double clampedHealth = Math.clamp(healthRatio, 0.0, 1.0);
        factor += (1.0 - clampedHealth) * 0.25;
        return Math.clamp(factor, MIN_SUSCEPTIBILITY, MAX_SUSCEPTIBILITY);
    }

    /** Per-check sneeze probability for a trigger and stimulus intensity in [0, 1]. */
    public static double chance(Trigger trigger, double intensity, double susceptibility) {
        if (trigger == null || intensity <= 0.0 || !Double.isFinite(intensity) || !Double.isFinite(susceptibility)) {
            return 0.0;
        }
        double clampedIntensity = Math.clamp(intensity, 0.0, 1.0);
        double clampedSusceptibility = Math.clamp(susceptibility, MIN_SUSCEPTIBILITY, MAX_SUSCEPTIBILITY);
        return switch (trigger) {
            case DUST -> Math.clamp(DUST_BASE_CHANCE * clampedIntensity * clampedSusceptibility, 0.0, DUST_MAX_CHANCE);
            case COLD -> Math.clamp(COLD_BASE_CHANCE * clampedIntensity * clampedSusceptibility, 0.0, COLD_MAX_CHANCE);
        };
    }

    /**
     * Cooldown in ticks before the next sneeze may start.
     * {@code roll} is a uniform random draw in [0, 1]. Always at least {@link #MIN_COOLDOWN}.
     */
    public static int cooldownTicks(double susceptibility, double roll) {
        double clampedSusceptibility = Math.clamp(susceptibility, MIN_SUSCEPTIBILITY, MAX_SUSCEPTIBILITY);
        double clampedRoll = Math.clamp(roll, 0.0, 1.0);
        double base = BASE_MIN_COOLDOWN + (BASE_MAX_COOLDOWN - BASE_MIN_COOLDOWN) * clampedRoll;
        int scaled = (int) Math.round(base / Math.sqrt(clampedSusceptibility));
        return Math.max(MIN_COOLDOWN, scaled);
    }

    /** Whether the cooldown has elapsed at {@code now}. */
    public static boolean ready(long now, long nextAllowedTick) {
        return now >= nextAllowedTick;
    }

    /**
     * Dust intensity in [0, 1] from real indoor dust ticks and region lint count.
     * Clean rooms (low dust ticks and zero lint) return 0.
     */
    public static double dustLevel(int dustTicks, int dustCap, int regionLint, int arrivalLint) {
        if (dustCap <= 0 || arrivalLint <= 0) return 0.0;
        int clampedDust = Math.max(0, dustTicks);
        int clampedLint = Math.max(0, regionLint);
        if (clampedLint == 0 && clampedDust * 3 < dustCap) return 0.0;
        double dustRatio = Math.clamp(clampedDust / (double) dustCap, 0.0, 1.0);
        double lintRatio = Math.clamp(clampedLint / (double) arrivalLint, 0.0, 1.0);
        return Math.max(dustRatio * 0.6, lintRatio);
    }

    /**
     * Cold stimulus intensity in [0, 1] from felt temperature, cold threshold and wetness.
     * Covers both being below the cold line and being wet-and-chilled just above it.
     */
    public static double coldIntensity(boolean cold, double coldDeficit, double felt, double coldLimit, double wetness) {
        double clampedWet = Math.clamp(wetness, 0.0, 100.0);
        if (cold) {
            return Math.clamp(0.35 + Math.max(0.0, coldDeficit) / 0.6 + (clampedWet / 100.0) * 0.4, 0.0, 1.0);
        }
        if (clampedWet >= CHILLED_WETNESS && felt < coldLimit + CHILLED_TEMP_MARGIN) {
            return Math.clamp((clampedWet / 100.0) * 0.55, 0.0, 1.0);
        }
        return 0.0;
    }

    private SneezeRules() {}
}
