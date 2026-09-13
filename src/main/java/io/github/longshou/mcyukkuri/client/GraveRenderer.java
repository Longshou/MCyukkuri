package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.body.GraveBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

public final class GraveRenderer implements BlockEntityRenderer<GraveBlockEntity>{
    public GraveRenderer(BlockEntityRendererProvider.Context context){}
    @Override public void render(GraveBlockEntity grave,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        pose.pushPose();pose.translate(.5,0,.5);float scale=grave.sourceScale();pose.scale(scale,scale,scale);
        PropModels.render("burial_mound",8,pose,buffers,light,overlay);pose.popPose();
    }
}
