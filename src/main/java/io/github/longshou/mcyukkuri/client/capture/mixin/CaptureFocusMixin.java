package io.github.longshou.mcyukkuri.client.capture.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Minecraft initially assumes focus even for an invisible window; prevent mouse grabbing. */
@Mixin(Minecraft.class)
abstract class CaptureFocusMixin {
    @ModifyVariable(method = "setWindowActive", at = @At("HEAD"), argsOnly = true)
    private boolean captureFocus(boolean active) {
        return !Boolean.getBoolean("mcyukkuri.capture") && active;
    }
}
