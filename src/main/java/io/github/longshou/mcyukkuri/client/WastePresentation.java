package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;

/** One existing stool mesh; its linear dimensions are the cube root of its volume. */
final class WastePresentation {
    static float scale(double volume) { return .5f*(float)Math.cbrt(volume); }
    static void render(double volume, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        render(volume,true,pose,buffers,light,overlay);
    }
    static void render(double volume, boolean onFloor, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if(volume<=0)return;
        pose.pushPose();float scale=scale(volume);pose.scale(scale,scale,scale);
        PropModels.center("yukkuri_waste",pose,onFloor);
        PropModels.render("yukkuri_waste",8,pose,buffers,light,overlay);pose.popPose();
    }
    private WastePresentation() {}
}
