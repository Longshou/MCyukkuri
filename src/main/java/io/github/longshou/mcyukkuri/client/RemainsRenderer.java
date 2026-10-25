package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.entity.RemainsEntity;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import java.util.Map;
import java.util.WeakHashMap;

public final class RemainsRenderer extends EntityRenderer<RemainsEntity>{
    public RemainsRenderer(EntityRendererProvider.Context context){super(context);shadowRadius=.25f;}
    /** Everything a frame derives from one synced snapshot; the synced stack is replaced on every change, so its identity is the change signal. */
    private record Cached(ItemStack source,CompoundTag data,RemainsAssets.Look look,YukkuriModel.RemainsInputs inputs){}
    private static final Map<RemainsEntity,Cached> CACHE=new WeakHashMap<>();
    static void reload(){CACHE.clear();}
    private static Cached cached(RemainsEntity entity){
        var source=entity.syncedRemains();var hit=CACHE.get(entity);
        if(hit!=null&&hit.source()==source)return hit;
        var data=entity.data();
        hit=new Cached(source,data,RemainsAssets.resolve(asset(data),data),YukkuriModel.get(asset(data)).remainsInputs(data));
        CACHE.put(entity,hit);return hit;
    }
    static String asset(CompoundTag data){String id=data.getString("Model");if(id.isEmpty())id=data.getString("Variant");return id.isEmpty()?"reimu":id;}
    static float renderScale(CompoundTag data){return RemainsEntity.dimension(data,"RenderScale",asset(data).startsWith("house_")?
            RemainsEntity.dimension(data,"SourceScale",.32f)/.32f:RemainsEntity.dimension(data,"SourceScale",1));}
    @Override public ResourceLocation getTextureLocation(RemainsEntity entity){return cached(entity).look().texture();}
    @Override public void render(RemainsEntity entity,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light){
        shadowRadius=entity.getBbWidth()*.4f;
        pose.pushPose();pose.mulPose(Axis.YP.rotationDegrees(180-yaw));
        var cached=cached(entity);var data=cached.data();float scale=renderScale(data);
        // The death cause and the water stage choose the form: face, squash, tilt, skin overlay and ground decal (RemainsAssets).
        var look=cached.look();
        int rot=cached.inputs().rotStage();
        // A corpse keeps its original capacity, so eating it changes its silhouette without ever regenerating it.
        float fullness=cached.inputs().fullness();
        float fullnessHeight=fullness>2f/3f?1:fullness>1f/3f?.9f:fullness>0?.78f:.65f;
        float fullnessSpread=fullness>2f/3f?1:fullness>1f/3f?1.04f:fullness>0?1.08f:1.12f;
        // Rotting remains slump as an independent, cumulative change: stale a little, rotten flat and spread out.
        float rotSlump=rot==2?.72f:rot==1?.88f:1,rotSpread=rot==2?1.14f:rot==1?1.05f:1;
        if(look.decal()!=null)decal(pose,buffers.getBuffer(RenderType.entityCutoutNoCull(look.decal())),look.decalRadius()*scale*fullnessSpread*rotSpread,light);
        // The tilt pivots on the ground contact; the source budget keeps it within the body's 12-degree limit.
        if(look.roll()!=0)pose.mulPose(Axis.ZP.rotationDegrees(look.roll()));
        float spread=scale*fullnessSpread*rotSpread;
        pose.scale(spread*look.sx(),scale*Math.max(.15f,look.sy()*fullnessHeight*rotSlump),spread*look.sz());
        draw(entity,data,look,cached.inputs(),pose,buffers,light,OverlayTexture.NO_OVERLAY);pose.popPose();
        super.render(entity,yaw,partial,pose,buffers,light);
    }
    /** A flat square of filling or residue on the ground beneath the body. */
    private static void decal(PoseStack pose,VertexConsumer buffer,float radius,int light){
        var last=pose.last();float y=.02f;
        float[][] corners={{-radius,-radius,0,0},{-radius,radius,0,1},{radius,radius,1,1},{radius,-radius,1,0}};
        for(var c:corners)buffer.addVertex(last.pose(),c[0],y,c[1]).setColor(255,255,255,255).setUv(c[2],c[3])
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(last,0,1,0);
    }
    static void draw(Object key,CompoundTag data,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        var look=RemainsAssets.resolve(asset(data),data);
        draw(key,data,look,YukkuriModel.get(asset(data)).remainsInputs(data),pose,buffers,light,overlay);
    }
    private static void draw(Object key,CompoundTag data,RemainsAssets.Look look,YukkuriModel.RemainsInputs inputs,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        var buffer=buffers.getBuffer(RenderType.entityCutoutNoCull(look.texture()));
        YukkuriModel.get(asset(data)).renderRemains(key,inputs,look,pose,buffer,buffers,light,overlay);
    }
}
