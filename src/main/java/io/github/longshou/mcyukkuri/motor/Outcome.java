package io.github.longshou.mcyukkuri.motor;

/**
 * The end of an action. The failure classes are fixed by {@link Reason#kind()} and can only be built through
 * {@link #of}, so a caller can never record a blocked action as incapable, or the reverse (spec 9.2 / 12.5).
 */
public sealed interface Outcome extends Progress
        permits Outcome.Done, Outcome.Blocked, Outcome.Incapable, Outcome.Interrupted, Outcome.Lost {

    record Done(String detail) implements Outcome {
        public Done { detail = detail == null ? "" : detail; }
    }

    /** Retryable with another method: the body was blocked, and by how much. */
    record Blocked(Reason reason, double shortfall, String detail) implements Outcome {
        public Blocked {
            requireBlocked(reason);
            detail = detail == null ? "" : detail;
        }
    }

    /** Not within this body's ability, whatever method is tried. */
    record Incapable(Reason reason, String detail) implements Outcome {
        public Incapable {
            requireIncapable(reason);
            detail = detail == null ? "" : detail;
        }
    }

    /** An outside cause ended the action; it is not retried by the same intent. */
    record Interrupted(String why) implements Outcome {
        public Interrupted { why = why == null ? "" : why; }
    }

    /** The target no longer exists; the intent ends. */
    record Lost(Reason reason, String detail) implements Outcome {
        public Lost {
            requireLost(reason);
            detail = detail == null ? "" : detail;
        }
    }

    /** The one way to build a failure: {@code reason.kind()} picks the record. */
    static Outcome of(Reason reason, double shortfall, String detail) {
        if (reason == null) throw new IllegalArgumentException("An outcome needs a reason");
        return switch (reason.kind()) {
            case BLOCKED -> new Blocked(reason, shortfall, detail);
            case INCAPABLE -> new Incapable(reason, detail);
            case LOST -> new Lost(reason, detail);
        };
    }

    /** The upper-case class name used in {@code [action] end} and {@code body.last}. */
    default String label() {
        if (this instanceof Done) return "DONE";
        if (this instanceof Blocked) return "BLOCKED";
        if (this instanceof Incapable) return "INCAPABLE";
        if (this instanceof Interrupted) return "INTERRUPTED";
        return "LOST";
    }

    /** The reason of a failure, or null for {@link Done} / {@link Interrupted}. */
    default Reason reason() {
        if (this instanceof Blocked blocked) return blocked.reason();
        if (this instanceof Incapable incapable) return incapable.reason();
        if (this instanceof Lost lost) return lost.reason();
        return null;
    }

    /** The measured shortfall (0 when the reason has no unit and for non-blocked outcomes). */
    default double shortfall() {
        return this instanceof Blocked blocked ? blocked.shortfall() : 0;
    }

    private static void requireBlocked(Reason reason) {
        if (reason == null || reason.kind() != Reason.Kind.BLOCKED) {
            throw new IllegalArgumentException("Not a blocked reason: " + reason);
        }
    }

    private static void requireIncapable(Reason reason) {
        if (reason == null || reason.kind() != Reason.Kind.INCAPABLE) {
            throw new IllegalArgumentException("Not an incapable reason: " + reason);
        }
    }

    private static void requireLost(Reason reason) {
        if (reason == null || reason.kind() != Reason.Kind.LOST) {
            throw new IllegalArgumentException("Not a lost reason: " + reason);
        }
    }
}
