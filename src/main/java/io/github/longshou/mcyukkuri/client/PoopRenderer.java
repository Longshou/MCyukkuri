package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.life.PoopBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

public final class PoopRenderer implements BlockEntityRenderer<PoopBlockEntity> {
    public PoopRenderer(BlockEntityRendererProvider.Context context) {}
    @Override public void render(PoopBlockEntity heap,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        pose.pushPose();pose.translate(.5,0,.5);
        WastePresentation.render(heap.count(),pose,buffers,light,overlay);pose.popPose();
    }
}
