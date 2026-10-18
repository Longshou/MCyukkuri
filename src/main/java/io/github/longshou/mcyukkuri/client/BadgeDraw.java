package io.github.longshou.mcyukkuri.client;

import static io.github.longshou.mcyukkuri.client.YukkuriModel.floats;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.MCYukkuri;
import org.joml.Vector3f;

/** Attached and detached badge geometry, using the model's current skin and shared triangle buffer. */
final class BadgeDraw {
    private final YukkuriModel o;
    BadgeDraw(YukkuriModel owner){o=owner;}

    void renderBadge(int tier, PoseStack stack, net.minecraft.client.renderer.MultiBufferSource buffers, int light, int overlay) {
        var badgeMount=o.badgeMount();var triangle=o.triangle();var vertices=o.vertices();
        if (tier < 1 || badgeMount == null) return;
        int first = badgeMount.get("triangle").getAsInt();
        float[] weights = floats(badgeMount.getAsJsonArray("weights"));
        var center = new Vector3f();
        for (int i = 0; i < 3; i++) { o.skin(first+i,triangle[i]); center.fma(weights[i]/16,triangle[i]); }
        var outward = new Vector3f(triangle[1]).sub(triangle[0]).cross(new Vector3f(triangle[2]).sub(triangle[0]));
        if (outward.lengthSquared() < 1e-10f) return;
        outward.normalize();
        // Recover a tangent from the deformed triangle, so tilting the hat also tilts its badge.
        float[] restNormal = floats(badgeMount.getAsJsonArray("rest_normal"));
        var restRight = new Vector3f(0,1,0).cross(new Vector3f(restNormal));
        if (restRight.lengthSquared() < 1e-10f) return; restRight.normalize();
        var a = new Vector3f(vertices[(first+1)*8]-vertices[first*8],vertices[(first+1)*8+1]-vertices[first*8+1],vertices[(first+1)*8+2]-vertices[first*8+2]);
        var b = new Vector3f(vertices[(first+2)*8]-vertices[first*8],vertices[(first+2)*8+1]-vertices[first*8+1],vertices[(first+2)*8+2]-vertices[first*8+2]);
        float aa=a.dot(a),ab=a.dot(b),bb=b.dot(b),det=aa*bb-ab*ab;
        if (Math.abs(det)<1e-10f) return;
        float u=(restRight.dot(a)*bb-restRight.dot(b)*ab)/det,v=(restRight.dot(b)*aa-restRight.dot(a)*ab)/det;
        var right = new Vector3f(triangle[1]).sub(triangle[0]).mul(u).fma(v,new Vector3f(triangle[2]).sub(triangle[0])).normalize();
        var up = new Vector3f(outward).cross(right).normalize();
        float half = badgeMount.get("diameter").getAsFloat()*.5f;
        emitBadge(tier,center,right,up,outward,half,stack,buffers,light,overlay);
    }

    static void renderDetachedBadge(String name,int tier,boolean cut,PoseStack stack,net.minecraft.client.renderer.MultiBufferSource buffers,int light,int overlay) {
        var mount=YukkuriModel.detachedBadge(name);if(mount==null||tier<1||tier>3)return;
        var center=new Vector3f(floats(mount.getAsJsonArray("rest_point"))).div(16);
        var outward=new Vector3f(floats(mount.getAsJsonArray("rest_normal"))).normalize();
        var right=new Vector3f(0,1,0).cross(outward).normalize();var up=new Vector3f(outward).cross(right).normalize();
        stack.pushPose();
        if(cut){int side=center.x<0?-1:1;stack.translate(side*.1,0,side*.035);stack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(side*12));}
        emitBadge(tier,center,right,up,outward,mount.get("diameter").getAsFloat()*.5f,stack,buffers,light,overlay);
        stack.popPose();
    }

    private static void emitBadge(int tier,Vector3f center,Vector3f right,Vector3f up,Vector3f outward,float half,
            PoseStack stack,net.minecraft.client.renderer.MultiBufferSource buffers,int light,int overlay) {
        center.fma(Math.max(.0007f,half*.025f),outward);
        var buffer=buffers.getBuffer(net.minecraft.client.renderer.RenderType.entityCutoutNoCull(MCYukkuri.id("textures/item/"+(tier>=3?"gold":tier==2?"silver":"bronze")+"_yukkuri_badge.png")));
        float[][] corners={{-1,-1,0,1},{1,-1,1,1},{1,1,1,0},{-1,1,0,0}};
        for(var corner:corners){
            var point=new Vector3f(center).fma(corner[0]*half,right).fma(corner[1]*half,up);
            buffer.addVertex(stack.last().pose(),point.x,point.y,point.z).setColor(255,255,255,255).setUv(corner[2],corner[3])
                    .setOverlay(overlay).setLight(light).setNormal(stack.last(),outward.x,outward.y,outward.z);
        }
    }
}
