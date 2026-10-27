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
import net.minecraft.world.phys.Vec3;

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
                var arc=FamilyGeometry.arc(stem.count(),1,1,stem.variation(),stem.source());
                Vec3 mouth=arc.getFirst().add(offset),onward=arc.get(1).subtract(arc.getFirst()).normalize();
                var material=buffers.getBuffer(RenderType.entityCutoutNoCull(ResourceLocation.withDefaultNamespace("textures/block/white_concrete.png")));
                double radius=FamilyGeometry.radius(stem.count(),0,stem.variation());
                int[] color=!stem.bottled()&&stem.born()==(1<<stem.count())-1?new int[]{157,135,79}:new int[]{66,186,57};
                // Rise straight from the cut end, then turn smoothly into the stalk's own first direction (no crease at the join).
                double bend=mouth.subtract(bottom).length()*.45;
                FamilyRenderer.sweep(pose,material,FamilyRenderer.bezier(bottom,bottom.add(0,bend,0),mouth.subtract(onward.scale(bend)),mouth,12),
                        radius,radius,light,color);
            }
            pose.translate(offset.x,offset.y,offset.z);
            // A planted or bottled stem stands still: its fruit hang plumb without sway.
            FamilyRenderer.renderStem(stem.count(),stem.progress(),1,stem.variation(),stem.source(),stem.born(),stem.types(),
                    stem.born()==(1<<stem.count())-1,0,Vec3.ZERO,0,stem::birthChildScale,pose,buffers,light);
        }
        pose.popPose();
    }
    @Override public boolean shouldRenderOffScreen(DetachedStemBlockEntity stem){return true;}
}
