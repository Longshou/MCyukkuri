package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.human.*;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.resources.ResourceLocation;

public final class FactoryTrayRenderer extends EntityRenderer<FactoryTray> {
    private final BlockRenderDispatcher blocks;
    public FactoryTrayRenderer(EntityRendererProvider.Context context) { super(context); blocks=context.getBlockRenderDispatcher(); shadowRadius=.5f; }
    @Override public ResourceLocation getTextureLocation(FactoryTray entity) { return TextureAtlas.LOCATION_BLOCKS; }
    @Override public void render(FactoryTray entity, float yaw, float partial, PoseStack pose, MultiBufferSource buffer, int light) {
        pose.pushPose(); pose.translate(-.5, 0, -.5);
        blocks.renderSingleBlock(HumanWorld.TRANSFER_TRAY.get().defaultBlockState(), pose, buffer, light, OverlayTexture.NO_OVERLAY);
        pose.popPose(); super.render(entity, yaw, partial, pose, buffer, light);
    }
}
