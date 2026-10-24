package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.body.CorpseFood;
import io.github.longshou.mcyukkuri.body.BodyWound;
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
    @Override public ResourceLocation getTextureLocation(RemainsEntity entity){return texture(entity.data());}
    private static ResourceLocation texture(CompoundTag data){String id=asset(data);
        return HairWoundAssets.texture(id,"sleep",HairWoundAssets.wholeBurn(BodyWound.read(data.getCompound("Body")),id.startsWith("house_")));}
    @Override public void render(RemainsEntity entity,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light){
        shadowRadius=entity.getBbWidth()*.4f;
        pose.pushPose();pose.mulPose(Axis.YP.rotationDegrees(180-yaw));
        var data=entity.data();float scale=renderScale(data);
        int rot=RemainsRot.stage(data);
        // A corpse keeps its original capacity, so eating it changes its silhouette without ever regenerating it.
        float capacity=CorpseFood.capacity(data);
        float fullness=capacity<=0?0:Math.clamp(CorpseFood.remaining(data)/capacity,0,1);
        float fullnessHeight=fullness>2f/3f?1:fullness>1f/3f?.9f:fullness>0?.78f:.65f;
        float fullnessSpread=fullness>2f/3f?1:fullness>1f/3f?1.04f:fullness>0?1.08f:1.12f;
        // Rotting remains slump as an independent, cumulative change: stale a little, rotten flat and spread out.
        float rotSlump=rot==2?.72f:rot==1?.88f:1,rotSpread=rot==2?1.14f:rot==1?1.05f:1;
        pose.scale(scale*fullnessSpread*rotSpread,scale*.65f*fullnessHeight*rotSlump,scale*fullnessSpread*rotSpread);
        draw(entity,data,pose,buffers,light,OverlayTexture.NO_OVERLAY);pose.popPose();
        super.render(entity,yaw,partial,pose,buffers,light);
    }
    static void draw(Object key,CompoundTag data,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        var buffer=buffers.getBuffer(RenderType.entityCutoutNoCull(texture(data)));
        YukkuriModel.get(asset(data)).renderRemains(key,data,pose,buffer,buffers,light,overlay);
    }
}
