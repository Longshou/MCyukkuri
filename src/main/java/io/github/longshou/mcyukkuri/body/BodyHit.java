package io.github.longshou.mcyukkuri.body;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;

/** Immutable pre-hit state. Each synchronous damage call owns its own context, including nested calls. */
public record BodyHit(Kind kind, DamageSource source, float healthBefore, float incoming, InjuryModel.Strike strike,
                      boolean coreExposedAtHit, boolean coreVisibleAtHit, boolean dyingBefore) {
    public enum Kind { ATTACK, BITE, BITE_WOUND, BOUT, INTERNAL, TERMINAL }

    public boolean physical() { return kind != Kind.INTERNAL && kind != Kind.TERMINAL && !source.is(DamageTypes.STARVE); }
    public BodyPart part() { return strike == null ? null : strike.part(); }
}
