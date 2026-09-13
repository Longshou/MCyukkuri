package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;

/** Draws the real support connection in world coordinates, independently of the body's facing. */
public final class OperationAttachments {
    public static void render(YukkuriEntity entity,YukkuriModel model,PoseStack pose,MultiBufferSource buffers,int light,float angle,float lift,float partial){
        var support=entity.contactAnchor();if(support==null||entity.isInvisible())return;
        var delta=Vec3.atBottomCenterOf(support).subtract(entity.getPosition(partial));
        var to=new org.joml.Vector3f((float)delta.x,(float)delta.y,(float)delta.z).div(entity.getScale()*entity.growthScale())
                .rotateY((float)Math.toRadians(net.minecraft.util.Mth.rotLerp(partial,entity.yBodyRotO,entity.yBodyRot)-180));to.y-=lift;to.rotateX((float)Math.toRadians(-angle));
        org.joml.Vector3f from;
        if(entity.contactPose().equals("HOOK")){
            // The tip, shaft orientation and rope eye all share exactly one model transform.
            var tip=model.surfacePoint();float scale=entity.getVariant().isHouse()?.25f:.65f;
            var localTip=new org.joml.Vector3f(-2.6f/16,3.1f/16,0);var localEye=new org.joml.Vector3f(0,9f/16,0);
            var rotation=new org.joml.Quaternionf().rotationTo(new org.joml.Vector3f(localEye).sub(localTip).normalize(),new org.joml.Vector3f(to).sub(tip).normalize());
            pose.pushPose();pose.translate(tip.x,tip.y,tip.z);pose.mulPose(rotation);pose.scale(scale,scale,scale);pose.translate(-localTip.x,-localTip.y,-localTip.z);
            PropModels.render("hanging_hook",1,pose,buffers,light,net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);pose.popPose();
            from=new org.joml.Vector3f(localEye).sub(localTip).mul(scale).rotate(rotation).add(tip);
        }else{var socket=PropModels.socket("wrap_sling_"+entity.subspecies().assetId()+(entity.hasVisibleAccessory()?"":"_bare"));from=new org.joml.Vector3f((float)socket.x,(float)socket.y,(float)socket.z);}
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
