package io.github.longshou.mcyukkuri.motor;

/**
 * Why a body could not do something. The class of the failure is fixed here and cannot be recorded as
 * something else by a caller (spec 9.2); only the shortfall is measured at the call site.
 */
public enum Reason {
    TOO_HIGH(Kind.BLOCKED),
    TOO_FAR(Kind.BLOCKED),
    NO_PATH(Kind.BLOCKED),
    NO_SUPPORT(Kind.BLOCKED),
    NO_ROOM(Kind.BLOCKED),
    NO_CONTACT(Kind.BLOCKED),
    SLIPPED(Kind.BLOCKED),
    DROPPED(Kind.BLOCKED),
    TARGET_MOVED(Kind.BLOCKED),
    NO_PROGRESS(Kind.BLOCKED),
    TOO_HEAVY(Kind.INCAPABLE),
    NO_TOOL(Kind.INCAPABLE),
    EXHAUSTED(Kind.INCAPABLE),
    TARGET_GONE(Kind.LOST);

    public enum Kind { BLOCKED, INCAPABLE, LOST }

    private final Kind kind;

    Reason(Kind kind) { this.kind = kind; }

    public Kind kind() { return kind; }
}
