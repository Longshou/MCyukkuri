package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.family.DetachedStemBlockEntity;
import io.github.longshou.mcyukkuri.family.DetachedStemGeometry;
import io.github.longshou.mcyukkuri.family.FamilyGeometry;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

public final class DetachedStemRenderer implements BlockEntityRenderer<DetachedStemBlockEntity> {
    public DetachedStemRenderer(BlockEntityRendererProvider.Context context){}
    @Override public void render(DetachedStemBlockEntity stem,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        pose.pushPose();pose.translate(.5,0,.5);
        if(stem.bottled())PropModels.render("brood_bottle",1,pose,buffers,light,OverlayTexture.NO_OVERLAY);
        if(stem.count()>0){
            // Rebase the existing stem at its real attachment point, preserving its branch/child layout.
            var offset=DetachedStemGeometry.offset(stem.count(),stem.variation(),stem.source(),stem.bottled());
            {
                var bottom=stem.bottled()?DetachedStemGeometry.BOTTLE_ROOT:DetachedStemGeometry.GROUND_ROOT;
                var mouth=FamilyGeometry.arc(stem.count(),1,1,stem.variation(),stem.source()).getFirst().add(offset);
                var material=buffers.getBuffer(RenderType.entityCutoutNoCull(ResourceLocation.withDefaultNamespace("textures/block/white_concrete.png")));
                double radius=FamilyGeometry.radius(stem.count(),0,stem.variation());
                int[] color=!stem.bottled()&&stem.born()==(1<<stem.count())-1?new int[]{157,135,79}:new int[]{66,186,57};
                FamilyRenderer.tube(pose,material,bottom,mouth,radius,radius,light,color);
            }
            pose.translate(offset.x,offset.y,offset.z);
            FamilyRenderer.renderStem(stem.count(),stem.progress(),1,stem.variation(),stem.source(),stem.born(),stem.types(),
                    stem.born()==(1<<stem.count())-1,pose,buffers,light);
        }
        pose.popPose();
    }
    @Override public boolean shouldRenderOffScreen(DetachedStemBlockEntity stem){return true;}
}
