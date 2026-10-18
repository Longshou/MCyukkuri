package io.github.longshou.mcyukkuri.entity.presentation;

/** Actual travel across super.aiStep(), not velocity or a second movement sample. */
public record Motion(double travelledX, double travelledZ) {
    public double distanceSquared() { return travelledX * travelledX + travelledZ * travelledZ; }
}
