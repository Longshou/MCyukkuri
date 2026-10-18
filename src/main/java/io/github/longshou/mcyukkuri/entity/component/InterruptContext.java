package io.github.longshou.mcyukkuri.entity.component;

import java.util.Objects;
import net.minecraft.world.damagesource.DamageSource;

/** Death flags are captured before family.died clears the pregnancy. No persistence or damage math. */
public record InterruptContext(Interrupt reason, DamageSource source,
        boolean stemDepletion, boolean sukkiriExhaustion) {
    public InterruptContext {
        Objects.requireNonNull(reason);
        if (reason == Interrupt.HURT || reason == Interrupt.DEATH) Objects.requireNonNull(source);
    }

    public static InterruptContext of(Interrupt reason) {
        return new InterruptContext(reason, null, false, false);
    }
    public static InterruptContext hurt(DamageSource source) {
        return new InterruptContext(Interrupt.HURT, source, false, false);
    }
    public static InterruptContext death(DamageSource source, boolean stemDepletion, boolean sukkiriExhaustion) {
        return new InterruptContext(Interrupt.DEATH, source, stemDepletion, sukkiriExhaustion);
    }
}
