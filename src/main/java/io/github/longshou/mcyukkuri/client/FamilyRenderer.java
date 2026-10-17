package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyGeometry;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Draws unborn children only. Detached children are real server entities and are never drawn twice. */
final class FamilyRenderer {
    private static final ResourceLocation WHITE=ResourceLocation.withDefaultNamespace("textures/block/white_concrete.png");
    static void render(YukkuriEntity mother,PoseStack pose,MultiBufferSource buffers,int light) {
        int count=mother.broodCount();if(count<1||mother.getVariant().isHouse())return;
        float progress=mother.broodProgress(),scale=mother.growthScale(),variation=mother.broodVariation();
        int types=0;for(int i=0;i<count;i++)types|=mother.broodVariant(i).ordinal()<<(i*4);
        renderStem(count,progress,scale,variation,mother.getVariant(),mother.broodBornMask(),types,mother.hasSpentStem(),pose,buffers,light);
    }
    static void renderStem(int count,float progress,float scale,float variation,io.github.longshou.mcyukkuri.entity.YukkuriVariant parent,int born,int types,boolean spent,PoseStack pose,MultiBufferSource buffers,int light){
        if(count<1)return;
        float growth=spent?1:Math.min(1,progress/.15f);growth=growth*growth*(3-2*growth);
        // A fresh pregnancy already shows a visible green sprout instead of a stem of zero length.
        if(!spent)growth=Math.max(.4f,growth);
        var arc=FamilyGeometry.arc(count,growth,scale,variation,parent);
        int[] color=spent&&born==(1<<count)-1?new int[]{157,135,79}:new int[]{66,186,57};
        var stem=buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE));
        for(int i=1;i<arc.size();i++)tube(pose,stem,arc.get(i-1),arc.get(i),
                FamilyGeometry.radius(count,(i-1)/(double)(arc.size()-1),variation)*scale,
                FamilyGeometry.radius(count,i/(double)(arc.size()-1),variation)*scale,light,color);
        if(progress<.15f)return;
        for(int i=0;i<count;i++) {
            if((born&(1<<i))!=0)continue;
            var variants=io.github.longshou.mcyukkuri.entity.YukkuriVariant.values();
            var child=variants[Math.min(variants.length-1,(types>>(i*4))&15)];float childScale=FamilyGeometry.fruitScale(progress);
            Vec3 base=FamilyGeometry.childBase(count,i,progress,scale,variation,parent,child);
            Vec3 origin=FamilyGeometry.socket(count,i,scale,variation,parent);
            var shape=FamilyGeometry.DATA.getAsJsonObject("variants").getAsJsonObject(child.assetId());
            var anchor=shape.getAsJsonArray("hanging_anchor");
            Vec3 head=anchor==null?base.add(0,shape.get("crown_height").getAsDouble()*childScale/16,0)
                    :base.add(anchor.get(0).getAsDouble()*childScale/16,anchor.get(1).getAsDouble()*childScale/16,anchor.get(2).getAsDouble()*childScale/16);
            double radius=Math.min(.0075*scale,FamilyGeometry.radius(count,(i+.5)/count,variation)*scale*.6);
            tube(pose,buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE)),origin,head,radius,radius*.78,light,color);
            pose.pushPose();pose.translate(base.x,base.y,base.z);pose.scale(childScale,childScale,childScale);
            var skin=buffers.getBuffer(RenderType.entityCutoutNoCull(MCYukkuri.id("textures/entity/"+child.assetId()+"/sleep.png")));
            YukkuriModel.get(child.assetId()).render("idle",0,pose,skin,light,OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
    }
    static void tube(PoseStack pose,VertexConsumer buffer,Vec3 from,Vec3 to,double r0,double r1,int light,int[] color) {
        Vec3 axis=to.subtract(from);if(axis.lengthSqr()<1e-10)return;axis=axis.normalize();
        Vec3 reference=Math.abs(axis.z)<.9?new Vec3(0,0,1):new Vec3(1,0,0);
        Vec3 u=axis.cross(reference).normalize(),v=axis.cross(u).normalize();
        for(int i=0;i<8;i++) {
            double a=i*Math.PI/4,b=(i+1)*Math.PI/4;
            Vec3 na=u.scale(Math.cos(a)).add(v.scale(Math.sin(a))),nb=u.scale(Math.cos(b)).add(v.scale(Math.sin(b)));
            vertex(pose,buffer,from.add(na.scale(r0)),na,0,0,light,color);
            vertex(pose,buffer,to.add(na.scale(r1)),na,0,1,light,color);
            vertex(pose,buffer,to.add(nb.scale(r1)),nb,1,1,light,color);
            vertex(pose,buffer,from.add(nb.scale(r0)),nb,1,0,light,color);
            // Closed ends keep the narrow connector solid from close viewpoints.
            vertex(pose,buffer,from,axis.scale(-1),.5f,.5f,light,color);
            vertex(pose,buffer,from.add(nb.scale(r0)),axis.scale(-1),1,0,light,color);
            vertex(pose,buffer,from.add(na.scale(r0)),axis.scale(-1),0,0,light,color);
            vertex(pose,buffer,from,axis.scale(-1),.5f,.5f,light,color);
            vertex(pose,buffer,to,axis,.5f,.5f,light,color);
            vertex(pose,buffer,to.add(na.scale(r1)),axis,0,0,light,color);
            vertex(pose,buffer,to.add(nb.scale(r1)),axis,1,0,light,color);
            vertex(pose,buffer,to,axis,.5f,.5f,light,color);
        }
    }
    private static void vertex(PoseStack pose,VertexConsumer buffer,Vec3 position,Vec3 normal,float u,float v,int light,int[] color) {
        buffer.addVertex(pose.last().pose(),(float)position.x,(float)position.y,(float)position.z)
                .setColor(color[0],color[1],color[2],255).setUv(u,v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                .setNormal(pose.last(),(float)normal.x,(float)normal.y,(float)normal.z);
    }
    private FamilyRenderer() {}
}
