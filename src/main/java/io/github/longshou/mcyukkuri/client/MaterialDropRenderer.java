package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.MaterialDrop;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

public final class MaterialDropRenderer extends EntityRenderer<MaterialDrop>{
    public MaterialDropRenderer(EntityRendererProvider.Context context){super(context);shadowRadius=.08f;}
    @Override public ResourceLocation getTextureLocation(MaterialDrop entity){return MCYukkuri.id("textures/props/leaked_filling.png");}
    @Override public void render(MaterialDrop entity,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light){
        int remaining=entity.getItem().getCount();
        pose.pushPose();pose.mulPose(Axis.YP.rotation(entity.bobOffs));
        var data=io.github.longshou.mcyukkuri.body.FillingResource.data(entity.getItem());
        float scale=data.contains("SourceScale")?data.getFloat("SourceScale"):1;
        scale=Float.isFinite(scale)?Math.clamp(scale,.05f,4):1;pose.scale(scale,scale,scale);
        if(io.github.longshou.mcyukkuri.body.FillingResource.isCore(entity.getItem())){
            // The centre filling keeps its own compact lump (art/props/body-marks/core_filling.py).
            for(int i=0;i<Math.min(4,remaining);i++){pose.pushPose();pose.translate(i*.16,0,0);PropModels.render("core_filling",1,pose,buffers,light,OverlayTexture.NO_OVERLAY);pose.popPose();}
            pose.popPose();super.render(entity,yaw,partial,pose,buffers,light);return;
        }
        for(int group=0;remaining>0;group++,remaining-=12){
            pose.pushPose();pose.translate((group%3)*.18,0,(group/3)*.16);
            PropModels.render("leaked_filling",Math.min(12,remaining),pose,buffers,light,OverlayTexture.NO_OVERLAY);pose.popPose();
        }
        pose.popPose();super.render(entity,yaw,partial,pose,buffers,light);
    }
}
