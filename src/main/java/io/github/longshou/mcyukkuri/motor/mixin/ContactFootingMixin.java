package io.github.longshou.mcyukkuri.motor.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Entity.class)
abstract class ContactFootingMixin {
    @Shadow private Vec3 collide(Vec3 motion) { throw new AssertionError(); }

    /** The final axes and step-up height are known here, before vanilla moves the body. */
    @ModifyExpressionValue(method = "move", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 mcyukkuri$checkContactFooting(Vec3 resolved, MoverType type, Vec3 motion) {
        if (type == MoverType.SELF && (Object)this instanceof YukkuriEntity body && !body.level().isClientSide
                && !body.motor().contact.permitsResolved(resolved))
            return collide(new Vec3(0, motion.y, 0));
        return resolved;
    }
}
