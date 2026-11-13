package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.body.WoundSurface;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Draws the real support connection in world coordinates, independently of the body's facing. */
public final class OperationAttachments {
    static void pin(YukkuriView entity,WoundRenderer.Contact contact,PoseStack pose,MultiBufferSource buffers,int light){
        var point=contact.point();
        var rotation=new Quaternionf().rotationTo(new Vector3f(0,1,0),contact.normal());
        float scale=(entity.profile().houseBody()?.22f:.65f)*.6f;
        pose.pushPose();
        pose.translate(point.x,point.y,point.z);
        pose.mulPose(rotation);
        pose.scale(scale,scale,scale);
        PropModels.render("pin_tool",1,pose,buffers,light,OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    static void render(YukkuriView entity,WoundRenderer.Contact contact,Matrix4f entityFrame,PoseStack pose,MultiBufferSource buffers,int light,float partial){
        var support=entity.contactAnchor();if(support==null||entity.isInvisible())return;
        var to=Vec3.atBottomCenterOf(support).subtract(entity.getPosition(partial)).toVector3f();
        new Matrix4f(pose.last().pose()).invert().mul(entityFrame).transformPosition(to);
        Vector3f from;
        if(entity.contactPose().equals("HOOK")){
            if(contact==null)return;
            var tip=contact.point();float scale=entity.profile().operationTipScale();
            var localTip=new Vector3f(-2.6f/16,3.1f/16,0);var localEye=new Vector3f(0,9f/16,0);
            // The shank is on the outward side of the barb. Aim its tangent towards the support.
            var outward=contact.normal();
            var up=new Vector3f(to).sub(tip);
            up.fma(-up.dot(outward),outward);
            if(up.lengthSquared()<1e-10f)up=WoundSurface.tangent(outward);
            else up.normalize();
            var rotation=new Quaternionf().setFromNormalized(new Matrix3f(outward,up,new Vector3f(outward).cross(up)));
            pose.pushPose();pose.translate(tip.x,tip.y,tip.z);pose.mulPose(rotation);pose.scale(scale,scale,scale);pose.translate(-localTip.x,-localTip.y,-localTip.z);
            PropModels.render("hanging_hook",1,pose,buffers,light,OverlayTexture.NO_OVERLAY);pose.popPose();
            from=new Vector3f(localEye).sub(localTip).mul(scale).rotate(rotation).add(tip);
        }else{var socket=PropModels.socket("wrap_sling_"+entity.assetId()+(entity.hasVisibleAccessory()?"":"_bare"));from=new Vector3f((float)socket.x,(float)socket.y,(float)socket.z);}
        float width=.012f;
        var out=buffers.getBuffer(RenderType.leash());var matrix=pose.last().pose();
        for(int axis=0;axis<2;axis++){
            float x=axis==0?width:0,z=axis==1?width:0;
            out.addVertex(matrix,from.x-x,from.y,from.z-z).setColor(110,84,51,255).setLight(light);
            out.addVertex(matrix,to.x-x,to.y,to.z-z).setColor(110,84,51,255).setLight(light);
            out.addVertex(matrix,to.x+x,to.y,to.z+z).setColor(110,84,51,255).setLight(light);
            out.addVertex(matrix,from.x+x,from.y,from.z+z).setColor(110,84,51,255).setLight(light);
        }
    }
    private OperationAttachments(){}
}
