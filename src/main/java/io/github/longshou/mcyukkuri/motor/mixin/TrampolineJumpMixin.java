package io.github.longshou.mcyukkuri.motor.mixin;

import io.github.longshou.mcyukkuri.motor.geom.TrampolineSpring;
import io.github.longshou.mcyukkuri.play.PlayBlock;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(LivingEntity.class)
abstract class TrampolineJumpMixin {
    // Vanilla replaces Y speed at this call; preserve the mat's upward motion when the player jumps on contact.
    @ModifyArg(method = "jumpFromGround", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;setDeltaMovement(DDD)V"), index = 1)
    private double mcyukkuri$addTrampolineRebound(double jumpSpeed) {
        if ((Object) this instanceof Player player && !player.isSuppressingBounce() && PlayBlock.onTrampolineBed(player)) {
            return TrampolineSpring.jump(jumpSpeed, player.getDeltaMovement().y);
        }
        return jumpSpeed;
    }
}
