package io.github.longshou.mcyukkuri.body;

/** Actual injury mechanism, kept separate from the treatment/leaking category. IDs match the authored art. */
public enum WoundShape {
    ABRASION("scratch", WoundKind.SCRATCH), CUT("tear", WoundKind.TEAR), BLUNT("dent", WoundKind.DENT),
    PUNCTURE("puncture", WoundKind.TEAR), FINE_PRICK("thorn", WoundKind.SCRATCH), CONE("impale", WoundKind.TEAR),
    BITE("bite", WoundKind.TEAR), BLAST("blast", WoundKind.TEAR),
    SOLE_BURN("sole", WoundKind.SCORCH), WHOLE_BURN("whole", WoundKind.SCORCH), NONE("none", null);

    private final String id;
    private final WoundKind kind;
    WoundShape(String id, WoundKind kind) { this.id = id; this.kind = kind; }
    public String id() { return id; }
    public WoundKind kind() { return kind; }
    public boolean thermal() { return this == SOLE_BURN || this == WHOLE_BURN; }
    /** Narrow points and shallow contacts never become a broad hole to display the core. */
    public boolean supportsCore() { return this == CUT || this == BLUNT || this == BITE || this == BLAST; }
    public static WoundShape defaultFor(WoundKind kind) {
        return switch (kind) {
            case SCRATCH -> ABRASION;
            case TEAR -> CUT;
            case DENT -> BLUNT;
            case SCORCH, CHAR -> SOLE_BURN;
            default -> NONE;
        };
    }
    public static WoundShape parse(String id, WoundKind kind) {
        var shape=parse(id);return shape==null?defaultFor(kind):shape;
    }
    public static WoundShape parse(String id) {
        for (var shape : values()) if (shape.id.equals(id)) return shape;
        return null;
    }
}
