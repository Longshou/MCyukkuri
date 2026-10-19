package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.body.BodyFood;
import net.minecraft.client.renderer.MultiBufferSource;

/** Renders a committed non-item bite at the lip; portions are visual only and never become an inventory stack. */
final class BodyFoodMouthRenderer {
    static void render(BodyFood.Bite bite, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (bite == null || bite.isEmpty()) return;
        int lumps = Math.clamp(Math.round(bite.portions() * 4), 1, 4);
        float tasteScale = switch (bite.taste()) {
            case "stale" -> .9f;
            case "foul" -> .78f;
            default -> 1f;
        };
        pose.pushPose();
        pose.translate(0, -.018, -.025);
        pose.scale(.095f * tasteScale, .095f * tasteScale, .095f * tasteScale);
        PropModels.render("leaked_filling", lumps, pose, buffers, light, overlay);
        pose.popPose();
    }

    private BodyFoodMouthRenderer() {}
}
