package io.github.longshou.mcyukkuri.entity.component;

import io.github.longshou.mcyukkuri.family.RemainsStem;
import java.util.Objects;
import net.minecraft.world.damagesource.DamageSource;

/** Death flags are captured before family.died clears the pregnancy. No persistence or damage math. */
public record InterruptContext(Interrupt reason, DamageSource source,
        boolean stemDepletion, boolean sukkiriExhaustion, RemainsStem stem) {
    public InterruptContext {
        Objects.requireNonNull(reason);
        if (reason == Interrupt.HURT || reason == Interrupt.DEATH) Objects.requireNonNull(source);
    }

    public static InterruptContext of(Interrupt reason) {
        return new InterruptContext(reason, null, false, false, null);
    }
    public static InterruptContext hurt(DamageSource source) {
        return new InterruptContext(Interrupt.HURT, source, false, false, null);
    }
    public static InterruptContext death(DamageSource source, boolean stemDepletion, boolean sukkiriExhaustion) {
        return death(source,stemDepletion,sukkiriExhaustion,null);
    }
    public static InterruptContext death(DamageSource source, boolean stemDepletion, boolean sukkiriExhaustion, RemainsStem stem) {
        return new InterruptContext(Interrupt.DEATH, source, stemDepletion, sukkiriExhaustion, stem);
    }
}
