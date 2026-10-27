package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.family.FamilyGeometry;
import io.github.longshou.mcyukkuri.family.GrowthSize;
import java.util.function.IntFunction;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Draws unborn children only. Detached children are real server entities and are never drawn twice. */
final class FamilyRenderer {
    private static final ResourceLocation WHITE=ResourceLocation.withDefaultNamespace("textures/block/white_concrete.png");
    // Hanging fruit swing like pendulums about their sockets (project design), only from real motion: a sway that grows
    // with the mother's actual travel (walking, hopping) and a lag opposite it. A still mother's fruit hang plumb; there is
    // no idle sway. Display only; the fruit's rest point is FamilyGeometry.childBase.
    private static final float SWAY_PERIOD_TICKS=46,SWAY_FORE=.13f,SWAY_SIDE=.05f,TRAVEL_LAG=1.6f,MAX_SWING=.32f;
    static void render(YukkuriView mother,float partialTick,PoseStack pose,MultiBufferSource buffers,int light) {
        int count=mother.broodCount();if(!mother.isAlive()||count<1||mother.profile().houseBody())return;
        float progress=mother.broodProgress(),scale=mother.growthScale(),variation=mother.broodVariation();
        int types=0;for(int i=0;i<count;i++)types|=mother.broodVariant(i).ordinal()<<(i*4);
        Vec3 travel=mother.position().subtract(mother.getPosition(0));
        renderStem(count,progress,scale,variation,mother.getVariant(),mother.broodBornMask(),types,mother.hasSpentStem(),
                mother.ageTicks()+partialTick,travel,1,mother::birthChildScale,pose,buffers,light);
    }
    /** {@code sway} scales the motion pendulum (0 keeps a planted stem plumb); {@code travel} is the mother's last tick of movement. */
    static void renderStem(int count,float progress,float scale,float variation,YukkuriVariant parent,int born,int types,boolean spent,
                           float ticks,Vec3 travel,float sway,IntFunction<Float> birthSizes,PoseStack pose,MultiBufferSource buffers,int light){
        if(count<1||!Float.isFinite(progress)||!spent&&progress<=0)return;
        // The visible stem grows only from actual gestation progress, never from a fresh reservation.
        float growth=spent?1:Math.clamp(progress/.15f,0,1);growth=growth*growth*(3-2*growth);
        var arc=FamilyGeometry.arc(count,growth,scale,variation,parent);
        int[] color=spent&&born==(1<<count)-1?new int[]{157,135,79}:new int[]{66,186,57};
        var stem=buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE));
        // A spline through the authored centerline, swept with continuous rings: the stalk never shows polyline creases.
        sweep(pose,stem,smooth(arc,STALK_SUBDIVISIONS),FamilyGeometry.radius(count,0,variation)*scale,FamilyGeometry.radius(count,1,variation)*scale,light,color);
        if(progress<.15f)return;
        var linear=pose.last().pose().get3x3(new Matrix3f());
        for(int i=0;i<count;i++) {
            if((born&(1<<i))!=0)continue;
            var variants=YukkuriVariant.values();
            var child=variants[Math.min(variants.length-1,(types>>(i*4))&15)];float birthScale=birthSizes.apply(i),childScale=GrowthSize.fruit(progress,birthScale);
            Vec3 socket=FamilyGeometry.socket(count,i,scale,variation,parent),anchor=FamilyGeometry.hangingAnchor(progress,child,birthScale);
            Vec3 hangPoint=socket.add(FamilyGeometry.hangOffset(count,i,scale));
            // The lower part hangs along world gravity (swung by sway and lag), whatever tilt the mother's root carries.
            Vector3f down=hang(linear,ticks,travel,i,sway);double length=FamilyGeometry.pedicel(progress,scale,child,birthScale);
            Vec3 hanging=new Vec3(down.x,down.y,down.z),head=hangPoint.add(hanging.scale(length));
            double radius=Math.min(.0075*scale,FamilyGeometry.radius(count,(i+.5)/count,variation)*scale*.6);
            // Keep the twig visibly load-bearing: never thinner than a third of the stalk where it leaves.
            int nearest=0;double best=Double.MAX_VALUE;
            for(int k=0;k<arc.size();k++){double d=arc.get(k).distanceToSqr(socket);if(d<best){best=d;nearest=k;}}
            double stalk=FamilyGeometry.radius(count,nearest/(double)(arc.size()-1),variation)*scale;
            // One continuous twig: it leaves the stalk outward and a little downward, then bends smoothly into the drop.
            double drop=FamilyGeometry.branchDrop();Vec3 heading=FamilyGeometry.branchHeading(count,i);
            Vec3 leave=heading.scale(Math.cos(drop)).add(0,-Math.sin(drop),0);
            double reach=hangPoint.subtract(socket).horizontalDistance();
            sweep(pose,buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE)),
                    bezier(socket,socket.add(leave.scale(reach*.75)),head.subtract(hanging.scale(length*.6)),head,PEDICEL_SEGMENTS),
                    Math.max(radius*1.15,stalk*.55),Math.max(radius*.72,stalk/3),light,color);
            pose.pushPose();pose.translate(head.x,head.y,head.z);pose.mulPose(new Quaternionf().rotationTo(0,-1,0,down.x,down.y,down.z));
            pose.translate(-anchor.x,-anchor.y,-anchor.z);pose.scale(childScale,childScale,childScale);
            var skin=buffers.getBuffer(RenderType.entityCutoutNoCull(MCYukkuri.id("textures/entity/"+child.assetId()+"/sleep.png")));
            YukkuriModel.get(child.assetId()).render("idle",0,pose,skin,light,OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
    }
    /** Local direction of a hanging pedicel: world down, swung by the motion sway and the travel lag. */
    private static Vector3f hang(Matrix3f linear,float ticks,Vec3 travel,int index,float sway) {
        if(Math.abs(linear.determinant())<1e-9f)return new Vector3f(0,-1,0);
        Vector3f forward=linear.transform(new Vector3f(0,0,-1)).mul(1,0,1),right=linear.transform(new Vector3f(1,0,0)).mul(1,0,1);
        if(forward.lengthSquared()>1e-8f)forward.normalize();
        if(right.lengthSquared()>1e-8f)right.normalize();
        float speed=(float)Math.min(.25,travel.length()),phase=(ticks/SWAY_PERIOD_TICKS+index*.31f)*Mth.TWO_PI,amount=sway*Math.min(1,speed*10);
        Vector3f swing=forward.mul(SWAY_FORE*amount*Mth.sin(phase)).add(right.mul(SWAY_SIDE*amount*Mth.sin(phase*.63f+index)))
                .add((float)-travel.x*TRAVEL_LAG,0,(float)-travel.z*TRAVEL_LAG);
        float horizontal=(float)Math.sqrt(swing.x*swing.x+swing.z*swing.z);
        if(horizontal>MAX_SWING)swing.mul(MAX_SWING/horizontal);
        return new Matrix3f(linear).invert().transform(new Vector3f(swing.x,-1,swing.z)).normalize();
    }
    private static final int PEDICEL_SEGMENTS=10,STALK_SUBDIVISIONS=4;
    /** Uniform Catmull-Rom spline through the points (ends clamped), {@code subdivisions} samples per span. */
    static List<Vec3> smooth(List<Vec3> points,int subdivisions) {
        int n=points.size();if(n<3)return points;
        List<Vec3> result=new ArrayList<>((n-1)*subdivisions+1);
        for(int k=0;k<n-1;k++){
            Vec3 p0=points.get(Math.max(0,k-1)),p1=points.get(k),p2=points.get(k+1),p3=points.get(Math.min(n-1,k+2));
            for(int s=0;s<subdivisions;s++){
                double t=s/(double)subdivisions,t2=t*t,t3=t2*t;
                result.add(p1.scale(2).add(p2.subtract(p0).scale(t)).add(p0.scale(2).subtract(p1.scale(5)).add(p2.scale(4)).subtract(p3).scale(t2))
                        .add(p1.scale(3).subtract(p0).subtract(p2.scale(3)).add(p3).scale(t3)).scale(.5));
            }
        }
        result.add(points.get(n-1));
        return result;
    }
    static List<Vec3> bezier(Vec3 p0,Vec3 p1,Vec3 p2,Vec3 p3,int segments) {
        List<Vec3> points=new ArrayList<>(segments+1);
        for(int k=0;k<=segments;k++){
            double t=k/(double)segments,s=1-t;
            points.add(p0.scale(s*s*s).add(p1.scale(3*s*s*t)).add(p2.scale(3*s*t*t)).add(p3.scale(t*t*t)));
        }
        return points;
    }
    /** A continuous tapering tube along a polyline, with rings carried by parallel transport so bends never crease. */
    static void sweep(PoseStack pose,VertexConsumer buffer,List<Vec3> points,double r0,double r1,int light,int[] color) {
        int n=points.size();if(n<2)return;
        Vec3[] tangents=new Vec3[n];
        for(int k=0;k<n;k++){
            Vec3 t=points.get(Math.min(n-1,k+1)).subtract(points.get(Math.max(0,k-1)));
            tangents[k]=t.lengthSqr()<1e-14?(k>0?tangents[k-1]:new Vec3(0,-1,0)):t.normalize();
        }
        Vec3 reference=Math.abs(tangents[0].z)<.9?new Vec3(0,0,1):new Vec3(1,0,0);
        Vec3 u=tangents[0].cross(reference).normalize();
        Vec3[][] rings=new Vec3[n][8];Vec3[][] normals=new Vec3[n][8];
        for(int k=0;k<n;k++){
            if(k>0){Vec3 t=tangents[k];Vec3 projected=u.subtract(t.scale(u.dot(t)));if(projected.lengthSqr()>1e-12)u=projected.normalize();}
            Vec3 v=tangents[k].cross(u).normalize();double r=r0+(r1-r0)*k/(n-1);
            for(int j=0;j<8;j++){double a=j*Math.PI/4;normals[k][j]=u.scale(Math.cos(a)).add(v.scale(Math.sin(a)));rings[k][j]=points.get(k).add(normals[k][j].scale(r));}
        }
        for(int k=1;k<n;k++)for(int j=0;j<8;j++){
            int m=(j+1)%8;
            vertex(pose,buffer,rings[k-1][j],normals[k-1][j],0,0,light,color);
            vertex(pose,buffer,rings[k][j],normals[k][j],0,1,light,color);
            vertex(pose,buffer,rings[k][m],normals[k][m],1,1,light,color);
            vertex(pose,buffer,rings[k-1][m],normals[k-1][m],1,0,light,color);
        }
        // Closed ends keep the narrow twig solid from close viewpoints.
        for(int j=0;j<8;j++){
            int m=(j+1)%8;Vec3 back=tangents[0].scale(-1),front=tangents[n-1];
            vertex(pose,buffer,points.get(0),back,.5f,.5f,light,color);
            vertex(pose,buffer,rings[0][m],back,1,0,light,color);
            vertex(pose,buffer,rings[0][j],back,0,0,light,color);
            vertex(pose,buffer,points.get(0),back,.5f,.5f,light,color);
            vertex(pose,buffer,points.get(n-1),front,.5f,.5f,light,color);
            vertex(pose,buffer,rings[n-1][j],front,0,0,light,color);
            vertex(pose,buffer,rings[n-1][m],front,1,0,light,color);
            vertex(pose,buffer,points.get(n-1),front,.5f,.5f,light,color);
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
