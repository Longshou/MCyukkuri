package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.family.FamilyGeometry;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Draws unborn children only. Detached children are real server entities and are never drawn twice. */
final class FamilyRenderer {
    private static final ResourceLocation WHITE=ResourceLocation.withDefaultNamespace("textures/block/white_concrete.png");
    // Hanging fruit swing like pendulums about their sockets (project design): a slow sway, plus a lag
    // opposite the mother's actual travel. Display only; the fruit's rest point is FamilyGeometry.childBase.
    private static final float SWAY_PERIOD_TICKS=46,SWAY_FORE=.13f,SWAY_SIDE=.05f,TRAVEL_LAG=1.6f,MAX_SWING=.32f;
    static void render(YukkuriView mother,float partialTick,PoseStack pose,MultiBufferSource buffers,int light) {
        int count=mother.broodCount();if(!mother.isAlive()||count<1||mother.profile().houseBody())return;
        float progress=mother.broodProgress(),scale=mother.growthScale(),variation=mother.broodVariation();
        int types=0;for(int i=0;i<count;i++)types|=mother.broodVariant(i).ordinal()<<(i*4);
        Vec3 travel=mother.position().subtract(mother.getPosition(0));
        renderStem(count,progress,scale,variation,mother.getVariant(),mother.broodBornMask(),types,mother.hasSpentStem(),
                mother.ageTicks()+partialTick,travel,1,pose,buffers,light);
    }
    /** {@code sway} scales the idle pendulum (0 keeps a resting stem plumb); {@code travel} is the mother's last tick of movement. */
    static void renderStem(int count,float progress,float scale,float variation,YukkuriVariant parent,int born,int types,boolean spent,
                           float ticks,Vec3 travel,float sway,PoseStack pose,MultiBufferSource buffers,int light){
        if(count<1||!Float.isFinite(progress)||!spent&&progress<=0)return;
        // The visible stem grows only from actual gestation progress, never from a fresh reservation.
        float growth=spent?1:Math.clamp(progress/.15f,0,1);growth=growth*growth*(3-2*growth);
        var arc=FamilyGeometry.arc(count,growth,scale,variation,parent);
        int[] color=spent&&born==(1<<count)-1?new int[]{157,135,79}:new int[]{66,186,57};
        var stem=buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE));
        for(int i=1;i<arc.size();i++)tube(pose,stem,arc.get(i-1),arc.get(i),
                FamilyGeometry.radius(count,(i-1)/(double)(arc.size()-1),variation)*scale,
                FamilyGeometry.radius(count,i/(double)(arc.size()-1),variation)*scale,light,color);
        if(progress<.15f)return;
        var linear=pose.last().pose().get3x3(new Matrix3f());
        for(int i=0;i<count;i++) {
            if((born&(1<<i))!=0)continue;
            var variants=YukkuriVariant.values();
            var child=variants[Math.min(variants.length-1,(types>>(i*4))&15)];float childScale=FamilyGeometry.fruitScale(progress);
            Vec3 socket=FamilyGeometry.socket(count,i,scale,variation,parent),anchor=FamilyGeometry.hangingAnchor(progress,child);
            Vec3 origin=socket.add(FamilyGeometry.spur(count,i,scale));
            // The pedicel hangs along world gravity from its spur, whatever tilt the mother's root carries.
            Vector3f down=hang(linear,ticks,travel,i,sway);double length=FamilyGeometry.pedicel(progress,scale,child);
            Vec3 head=origin.add(down.x*length,down.y*length,down.z*length);
            double radius=Math.min(.0075*scale,FamilyGeometry.radius(count,(i+.5)/count,variation)*scale*.6);
            if(origin.distanceToSqr(socket)>1e-10)tube(pose,buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE)),socket,origin,radius,radius,light,color);
            tube(pose,buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE)),origin,head,radius,radius*.78,light,color);
            pose.pushPose();pose.translate(head.x,head.y,head.z);pose.mulPose(new Quaternionf().rotationTo(0,-1,0,down.x,down.y,down.z));
            pose.translate(-anchor.x,-anchor.y,-anchor.z);pose.scale(childScale,childScale,childScale);
            var skin=buffers.getBuffer(RenderType.entityCutoutNoCull(MCYukkuri.id("textures/entity/"+child.assetId()+"/sleep.png")));
            YukkuriModel.get(child.assetId()).render("idle",0,pose,skin,light,OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
    }
    /** Local direction of a hanging pedicel: world down, swung by the idle sway and the travel lag. */
    private static Vector3f hang(Matrix3f linear,float ticks,Vec3 travel,int index,float sway) {
        if(Math.abs(linear.determinant())<1e-9f)return new Vector3f(0,-1,0);
        Vector3f forward=linear.transform(new Vector3f(0,0,-1)).mul(1,0,1),right=linear.transform(new Vector3f(1,0,0)).mul(1,0,1);
        if(forward.lengthSquared()>1e-8f)forward.normalize();
        if(right.lengthSquared()>1e-8f)right.normalize();
        float speed=(float)Math.min(.25,travel.horizontalDistance()),phase=(ticks/SWAY_PERIOD_TICKS+index*.31f)*Mth.TWO_PI,amount=sway*(1+3*speed);
        Vector3f swing=forward.mul(SWAY_FORE*amount*Mth.sin(phase)).add(right.mul(SWAY_SIDE*amount*Mth.sin(phase*.63f+index)))
                .add((float)-travel.x*TRAVEL_LAG,0,(float)-travel.z*TRAVEL_LAG);
        float horizontal=(float)Math.sqrt(swing.x*swing.x+swing.z*swing.z);
        if(horizontal>MAX_SWING)swing.mul(MAX_SWING/horizontal);
        return new Matrix3f(linear).invert().transform(new Vector3f(swing.x,-1,swing.z)).normalize();
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
