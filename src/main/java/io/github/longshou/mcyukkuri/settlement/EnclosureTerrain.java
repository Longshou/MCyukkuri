package io.github.longshou.mcyukkuri.settlement;

/** Globally ordered versions of the block sections read by a burrow. */
public interface EnclosureTerrain {
    long mcyukkuri$enclosureVersion();
    void mcyukkuri$invalidateEnclosure();
}
