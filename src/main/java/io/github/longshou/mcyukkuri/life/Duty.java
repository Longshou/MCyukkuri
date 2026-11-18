package io.github.longshou.mcyukkuri.life;

public final class Duty {
    /** A later kind may replace an earlier one. */
    public enum Kind { WATCH, SEARCH, SEAL }
    public enum End { DONE, FAILED, DEADLINE, EMERGENCY, YIELDED, REPLACED }

    /** Five seconds for danger at the edge of sight to settle before reclaiming. */
    static final int EMERGENCY_SETTLE = 100;

    private Duty() {}

    static boolean mayReplace(Kind held, Kind wanted) {
        return held == null || wanted.ordinal() >= held.ordinal();
    }

    static End lapse(long now, long deadline, boolean emergency) {
        return emergency ? End.EMERGENCY : now >= deadline ? End.DEADLINE : null;
    }

    static long coolUntil(End end, long now, int failCooldown) {
        return switch (end) {
            case FAILED, DEADLINE -> now + failCooldown;
            case EMERGENCY -> now + EMERGENCY_SETTLE;
            default -> now;
        };
    }
}
