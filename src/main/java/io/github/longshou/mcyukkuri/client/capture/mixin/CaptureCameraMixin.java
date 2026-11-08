package io.github.longshou.mcyukkuri.client.capture.mixin;

import io.github.longshou.mcyukkuri.client.capture.MuseumCapture;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only the capture camera moves; world entities and gameplay remain untouched. */
@Mixin(Camera.class)
abstract class CaptureCameraMixin {
    @Shadow protected abstract void setPosition(double x,double y,double z);
    @Shadow protected abstract void setRotation(float yaw,float pitch);
    @Inject(method="setup",at=@At("TAIL"))
    private void captureView(BlockGetter level,Entity entity,boolean detached,boolean mirrored,float partial,CallbackInfo ci) {
        var view=MuseumCapture.camera((Camera)(Object)this,partial);
        if(view!=null){setPosition(view[0],view[1],view[2]);setRotation((float)view[3],(float)view[4]);}
    }
}
