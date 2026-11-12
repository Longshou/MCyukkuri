package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.human.Custody;
import java.util.Locale;

/** What an individual is to the world. Derived on demand from the owning components, never stored twice. */
public enum Identity {
    HOUSE,
    PET,
    NPC_COMPANION,
    OWNED,
    STOCK,
    STRAY,
    WILD_SETTLEMENT,
    WILD_FAMILY;

    public static Identity resolve(Custody.View custody, boolean houseBody, boolean townRoots, boolean settlement) {
        if (custody.role() == Custody.Role.STOCK) return STOCK;
        if (custody.ownerKind() == Custody.OwnerKind.PLAYER) return PET;
        if (custody.owner() != null) {
            if (custody.role() != Custody.Role.COMPANION) return OWNED;
            return custody.ownerKind() == Custody.OwnerKind.NPC ? NPC_COMPANION : PET;
        }
        if (custody.hasPendingStaffRole()) return OWNED;
        if (houseBody) return HOUSE;
        if (townRoots) return STRAY;
        return settlement ? WILD_SETTLEMENT : WILD_FAMILY;
    }

    public boolean companion() { return this == PET || this == NPC_COMPANION; }
    public boolean canConceive() { return this != STOCK; }
    public boolean wild() { return this == WILD_FAMILY || this == WILD_SETTLEMENT; }
    public boolean free() { return this == STRAY || wild(); }
    public String id() { return name().toLowerCase(Locale.ROOT); }
    public String translationKey() { return "identity.mcyukkuri." + id(); }
}
