package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.body.RemainsRot;
import io.github.longshou.mcyukkuri.entity.RemainsEntity;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

public final class RemainsRenderer extends EntityRenderer<RemainsEntity>{
    public RemainsRenderer(EntityRendererProvider.Context context){super(context);shadowRadius=.25f;}
    static String asset(CompoundTag data){String id=data.getString("Model");if(id.isEmpty())id=data.getString("Variant");return id.isEmpty()?"reimu":id;}
    static float renderScale(CompoundTag data){return RemainsEntity.dimension(data,"RenderScale",asset(data).startsWith("house_")?
            RemainsEntity.dimension(data,"SourceScale",.32f)/.32f:RemainsEntity.dimension(data,"SourceScale",1));}
    @Override public ResourceLocation getTextureLocation(RemainsEntity entity){return MCYukkuri.id("textures/entity/"+asset(entity.data())+"/sleep.png");}
    @Override public void render(RemainsEntity entity,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light){
        shadowRadius=entity.getBbWidth()*.4f;
        pose.pushPose();pose.mulPose(Axis.YP.rotationDegrees(180-yaw));
        var data=entity.data();float scale=renderScale(data);
        int rot=RemainsRot.stage(data);
        // Rotting remains slump: stale a little, rotten flat and spread out.
        float slump=rot==2?.72f:rot==1?.88f:1,spread=rot==2?1.14f:rot==1?1.05f:1;
        pose.scale(scale*spread,scale*.65f*slump,scale*spread);draw(entity,data,pose,buffers,light,OverlayTexture.NO_OVERLAY);pose.popPose();
        super.render(entity,yaw,partial,pose,buffers,light);
    }
    static void draw(Object key,CompoundTag data,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        var buffer=buffers.getBuffer(RenderType.entityCutoutNoCull(MCYukkuri.id("textures/entity/"+asset(data)+"/sleep.png")));
        YukkuriModel.get(asset(data)).renderRemains(key,data,pose,buffer,buffers,light,overlay);
    }
}
