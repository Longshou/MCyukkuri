package io.github.longshou.mcyukkuri.entity.component;

import io.github.longshou.mcyukkuri.family.RemainsStem;
import java.util.Objects;
import net.minecraft.world.damagesource.DamageSource;

/** Death flags are captured before family.died clears the pregnancy. {@code noRemains}: the body leaves no corpse (BodyLife#died). No persistence or damage math. */
public record InterruptContext(Interrupt reason, DamageSource source,
        boolean stemDepletion, boolean sukkiriExhaustion, RemainsStem stem, boolean noRemains) {
    public InterruptContext {
        Objects.requireNonNull(reason);
        if (reason == Interrupt.HURT || reason == Interrupt.DEATH) Objects.requireNonNull(source);
    }

    public static InterruptContext of(Interrupt reason) {
        return new InterruptContext(reason, null, false, false, null, false);
    }
    public static InterruptContext hurt(DamageSource source) {
        return new InterruptContext(Interrupt.HURT, source, false, false, null, false);
    }
    public static InterruptContext death(DamageSource source, boolean stemDepletion, boolean sukkiriExhaustion) {
        return death(source,stemDepletion,sukkiriExhaustion,null);
    }
    public static InterruptContext death(DamageSource source, boolean stemDepletion, boolean sukkiriExhaustion, RemainsStem stem) {
        return death(source, stemDepletion, sukkiriExhaustion, stem, false);
    }
    public static InterruptContext death(DamageSource source, boolean stemDepletion, boolean sukkiriExhaustion, RemainsStem stem, boolean noRemains) {
        return new InterruptContext(Interrupt.DEATH, source, stemDepletion, sukkiriExhaustion, stem, noRemains);
    }
}
