package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.body.GraveBlock;
import io.github.longshou.mcyukkuri.body.GraveBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/** The mound sized to the buried body, turned so its head end (with the stone marker) is away from the burier. */
public final class GraveRenderer implements BlockEntityRenderer<GraveBlockEntity>{
    public GraveRenderer(BlockEntityRendererProvider.Context context){}
    @Override public void render(GraveBlockEntity grave,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        float scale=grave.sourceScale(),marker=GraveBlock.markerScale(scale);
        pose.pushPose();pose.translate(.5,0,.5);pose.mulPose(Axis.YP.rotationDegrees(GraveBlock.headYaw(grave.getBlockState())));
        pose.pushPose();pose.scale(scale,scale,scale);PropModels.render("burial_mound",8,pose,buffers,light,overlay);pose.popPose();
        pose.translate(GraveBlock.markerOffset(scale)/16,0,0);pose.scale(marker,marker,marker);
        PropModels.render("grave_marker",8,pose,buffers,light,overlay);
        pose.popPose();
    }
}
