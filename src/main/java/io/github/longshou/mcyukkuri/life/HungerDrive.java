package io.github.longshou.mcyukkuri.life;

import java.util.HashMap;
import java.util.UUID;

/**
 * How hunger widens what a creature tries: the nutrition below which it wakes, how much farther and how many more sweep
 * destinations it tries, and the memory of food it already found unreachable so the search moves on instead of fixating.
 */
public final class HungerDrive {
    private HungerDrive() {}

    /** Below this nutrition the plan forages, begs and scavenges (the same line as {@code hasUrgentNeed}). */
    public static final float URGENT = 30;
    /** A food sighting that could not be walked to is left alone this long unless the creature has moved on. */
    static final int REJECT_TICKS = 600;
    /** Moving this far from where the sighting was refused is a changed condition: the route is asked again. */
    static final double REJECT_MOVED = 8;
    /** Most sightings kept; the oldest are dropped first. */
    static final int REJECT_CAP = 16;

    /** Nutrition below which a sleeper gets up to look for food: 20 for the timid up to 30 for the bold. */
    public static float wakeLevel(int boldness) { return 20 + Math.clamp(boldness, 0, 100) / 10f; }

    /** 0 when fed, 1 at nutrition 0: how far into hunger the body is below the urgent line. */
    static float pressure(float nutrition) { return Math.clamp((URGENT - nutrition) / URGENT, 0f, 1f); }

    /** Food search reach multiplier: unchanged until urgent, doubled when starving (the path range still caps it). */
    public static double reachScale(float nutrition) { return 1 + pressure(nutrition); }

    /** Candidate sweep destinations tried per leg: three when fed, eight when starving. */
    public static int sweepAttempts(float nutrition) { return 3 + Math.round(5 * pressure(nutrition)); }

    /** Food items refused as unreachable, with where and when; not saved. */
    public static final class Rejections {
        private record Seen(long until, double x, double z) {}
        private final HashMap<UUID, Seen> seen = new HashMap<>();

        public void reject(UUID item, long now, double x, double z) {
            if (seen.size() >= REJECT_CAP) seen.values().removeIf(s -> s.until <= now);
            if (seen.size() >= REJECT_CAP) seen.remove(seen.keySet().iterator().next());
            seen.put(item, new Seen(now + REJECT_TICKS, x, z));
        }

        /** True while the refusal stands: not expired and the creature is still near where it was refused. */
        public boolean rejected(UUID item, long now, double x, double z) {
            var s = seen.get(item);
            if (s == null) return false;
            if (now >= s.until || Math.hypot(x - s.x, z - s.z) > REJECT_MOVED) { seen.remove(item); return false; }
            return true;
        }
    }
}
