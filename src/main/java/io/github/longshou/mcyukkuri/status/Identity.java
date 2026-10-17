package io.github.longshou.mcyukkuri.status;

/** What an individual is to the world. Derived on demand from the owning components, never stored twice. */
public enum Identity {
    /** House yukkuri (the small kind): their own ecology. */
    HOUSE,
    /** Owned and kept as a companion. */
    PET,
    /** Owned for another use (shop stock, factory worker, captive). */
    OWNED,
    /** Member of a settlement group. */
    SETTLER,
    /** Free individual without any home: lost, abandoned or wandering. */
    STRAY,
    /** Free individual with a home or territory. */
    WILD;

    public boolean free() { return this == STRAY || this == WILD || this == SETTLER; }
}
