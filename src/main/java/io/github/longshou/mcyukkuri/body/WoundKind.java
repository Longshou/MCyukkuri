package io.github.longshou.mcyukkuri.body;

import java.util.Objects;

/** Stable, case-sensitive save IDs, including the mark kinds accepted by older wound lists. */
public enum WoundKind {
    SCRATCH("scratch"), TEAR("tear"), DENT("dent"), SCORCH("scorch"),
    DIRT("dirt"), SCAR("scar"), MOLD("mold"), CHAR("char"), PASTE("paste"), CORE("core");

    private final String id;

    WoundKind(String id) { this.id = id; }

    public String id() { return id; }

    /** A skin injury that orange juice treats and a lick can mend; the mark kinds are not injuries. */
    public boolean injury() { return this == SCRATCH || this == TEAR || this == DENT || this == SCORCH; }

    public static WoundKind parse(String id) {
        Objects.requireNonNull(id);
        for (var kind : values()) if (kind.id.equals(id)) return kind;
        return SCRATCH;
    }
}
