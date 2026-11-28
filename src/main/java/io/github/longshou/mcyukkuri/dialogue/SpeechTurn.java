package io.github.longshou.mcyukkuri.dialogue;

/**
 * Local turn-taking for performance lines: prevents nearby speakers from talking over each
 * other on the same tick while bounding wait time so dropped crowd echoes never go stale.
 */
public final class SpeechTurn {
    public static final int TURN_RADIUS = 10;
    public static final int TURN_BREATH = 10;
    public static final int MAX_WAIT = 110;

    public enum Action { SPEAK_NOW, DEFER, DROP }

    public record Decision(Action action, long dueTick) {
        public boolean speakNow() { return action == Action.SPEAK_NOW; }
        public boolean defer() { return action == Action.DEFER; }
        public boolean drop() { return action == Action.DROP; }
    }

    /** First tick this speaker may start relative to the latest busy neighbor within turn radius. */
    public static long nextSlot(long now, long neighborBusyUntil) {
        if (neighborBusyUntil <= 0 || now >= neighborBusyUntil + TURN_BREATH) return now;
        return neighborBusyUntil + TURN_BREATH;
    }

    /** Decide whether to speak immediately, defer to {@code nextSlot}, or drop when waiting past {@code expiresAt}. */
    public static Decision evaluate(long now, long neighborBusyUntil, long expiresAt) {
        long slot = nextSlot(now, neighborBusyUntil);
        if (slot <= now) return new Decision(Action.SPEAK_NOW, now);
        if (slot <= expiresAt) return new Decision(Action.DEFER, slot);
        return new Decision(Action.DROP, -1);
    }

    private SpeechTurn() {}
}
