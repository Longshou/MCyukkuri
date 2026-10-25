package io.github.longshou.mcyukkuri.subspecies.crew;

/** The answer to "may this body come aboard for this purpose": a seat, or exactly one finite reason why not. */
public record Admission(boolean ok, int slot, String slotId, Reason reason, String detail, double loadBefore, double loadAfter) {
    public enum Reason { OK, UNITS_FULL, NO_SLOT_FOR_CLASS, CONFLICT_WITH, HULL_UNSTABLE, NOT_ADMITTED }

    static Admission ok(int slot, String slotId, double before, double after) {
        return new Admission(true, slot, slotId, Reason.OK, "", before, after);
    }
    static Admission no(Reason reason, String detail, double before, double after) {
        return new Admission(false, DeckLayout.NONE, "", reason, detail, before, after);
    }
    /** The loggable form: {@code OK}, {@code UNITS_FULL}, {@code CONFLICT_WITH:bow_perch_9} and so on. */
    public String text() {
        return ok ? "OK" : reason == Reason.CONFLICT_WITH ? "CONFLICT_WITH:" + detail : reason.name();
    }
}
