package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.settlement.ForagePileBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/** The dry-leaf base is always drawn; cut grass, wheat, fruit and nuts lie heaped in four stages with the real stock. */
public final class ForagePileRenderer implements BlockEntityRenderer<ForagePileBlockEntity> {
    public ForagePileRenderer(BlockEntityRendererProvider.Context context) {}
    @Override public void render(ForagePileBlockEntity pile, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        pose.pushPose();
        pose.translate(.5, 0, .5);
        PropModels.render("forage_pile", pile.stage(), pose, buffers, light, overlay);
        pose.popPose();
    }
}
