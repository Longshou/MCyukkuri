package io.github.longshou.mcyukkuri.human.mixin;

import io.github.longshou.mcyukkuri.human.VillageBin;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
abstract class LitterMergeMixin {
    @Inject(method = "tryToMerge", at = @At("HEAD"), cancellable = true)
    private void mcyukkuri$keepLitterSeparate(ItemEntity other, CallbackInfo callback) {
        var item = (ItemEntity) (Object) this;
        if (VillageBin.isLitter(item) != VillageBin.isLitter(other)
                || VillageBin.isLitter(item) && item.getOwner() != other.getOwner()) callback.cancel();
    }
}
