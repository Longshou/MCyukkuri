package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.play.PlayBlockEntity;
import io.github.longshou.mcyukkuri.play.PlayGeometry;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;

public final class PlaygroundRenderer implements BlockEntityRenderer<PlayBlockEntity> {
    public PlaygroundRenderer(BlockEntityRendererProvider.Context context){}
    @Override public void render(PlayBlockEntity e,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        if(e.getLevel()!=null)light=LevelRenderer.getLightColor(e.getLevel(),e.getBlockPos().above(2));
        pose.pushPose();pose.translate(.5,0,.5);pose.mulPose(Axis.YP.rotationDegrees(-90*PlayGeometry.turns(e.facing())));
        PropModels.render(e.kind().id,Integer.MAX_VALUE,pose,buffers,light,overlay);pose.popPose();
    }
    @Override public AABB getRenderBoundingBox(PlayBlockEntity e){return new AABB(e.getBlockPos()).inflate(2,0,2).expandTowards(0,2,0);}
    @Override public boolean shouldRenderOffScreen(PlayBlockEntity e){return true;}
}
