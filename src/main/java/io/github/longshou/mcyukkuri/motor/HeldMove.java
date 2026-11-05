package io.github.longshou.mcyukkuri.motor;

import net.minecraft.world.phys.Vec3;

/** A running beat that moves the held stack ({@code motor/action/Stow}): its timing, and where in her set-down hat it lays it. */
public interface HeldMove {
    Held.Beat beat();
    /** The hat-local spot and short-end direction of a move into her hat; null for any other destination. */
    Vec3 spot();
    Vec3 dir();
}
