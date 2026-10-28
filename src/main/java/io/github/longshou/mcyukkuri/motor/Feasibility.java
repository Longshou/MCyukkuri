package io.github.longshou.mcyukkuri.motor;

/**
 * The answer of {@link Action#check} (pure, no side effects). {@code shortfall} is measured in the unit the
 * {@link Reason} names (spec 9.2) and is 0 where there is no distance to measure.
 */
public record Feasibility(boolean ok, Reason reason, double shortfall, String detail) {
    private static final Feasibility OK = new Feasibility(true, null, 0, "");

    public Feasibility {
        if (ok && reason != null) throw new IllegalArgumentException("A feasible action has no reason");
        if (!ok && reason == null) throw new IllegalArgumentException("A refusal needs a reason");
        detail = detail == null ? "" : detail;
    }

    /** The feasible answer; every action that has no reason to refuse returns this. */
    public static Feasibility pass() { return OK; }

    /** A refusal; the class of the eventual {@link Outcome} follows {@code reason.kind()}. */
    public static Feasibility of(Reason reason, double shortfall, String detail) {
        return new Feasibility(false, reason, shortfall, detail);
    }
}
