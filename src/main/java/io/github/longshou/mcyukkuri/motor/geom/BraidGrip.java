package io.github.longshou.mcyukkuri.motor.geom;

import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Reason;
import java.util.Locale;

/** Contact measured at the feet supported through the pickup turn. */
public record BraidGrip(boolean supported, double upright, double low) {
    public double gap() { return Math.min(upright, low); }
    public boolean lean() { return upright > 0; }
    public boolean ready() { return supported && gap() <= 0; }

    public Feasibility check() {
        if (!supported) return Feasibility.of(Reason.NO_SUPPORT, 0, "unstable_grip_stand");
        if (ready()) return Feasibility.pass();
        return Feasibility.of(Reason.NO_CONTACT, gap(), String.format(Locale.ROOT, "braid_gap=%.2f", gap()));
    }
}
