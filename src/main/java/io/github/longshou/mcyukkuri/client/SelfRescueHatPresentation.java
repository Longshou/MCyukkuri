package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.SelfRescueHatGeometry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Displays the server's single hat and the two actual braid segments; it never invents a transfer path. */
final class SelfRescueHatPresentation {
    static void configure(YukkuriEntity entity,YukkuriModel model,float partial,float bodyYaw){
        model.setSelfHatPose(null);
        var state=entity.aquaticSelfRescueState();
        if(!entity.getBehaviorId().startsWith("water_hat_"))return;
        var feet=entity.getPosition(partial);
        if(state.getBoolean("BraidContact")){
            if(!entity.boardingState().getBoolean("Active"))model.setBoardingPose(0,0);
            model.setBraidRescueContact(modelPoint(interpolate(state,"PrevTip","Tip",partial),feet,entity,bodyYaw));
            model.setBraidRescueJoint(modelPoint(interpolate(state,"PrevJoint","Joint",partial),feet,entity,bodyYaw));
        }
        if(!entity.hasVisibleAccessory()||!state.getBoolean("HatPose"))return;
        var rotation=quaternion(state,"PrevHat").slerp(quaternion(state,"Hat"),partial);
        float squash=net.minecraft.util.Mth.lerp(partial,state.getFloat("PrevHatSquash"),state.getFloat("HatSquash"));
        var pose=new SelfRescueHatGeometry.Pose(interpolate(state,"PrevHat","Hat",partial),rotation,squash,Vec3.ZERO,Vec3.ZERO,null);
        model.setSelfHatPose(pose.wornMatrix(feet,bodyYaw,SelfRescueHatGeometry.bodyUnit(entity),SelfRescueHatGeometry.hatUnit(entity)));
    }
    private static Quaternionf quaternion(CompoundTag state,String key){
        return new Quaternionf(state.getFloat(key+"QX"),state.getFloat(key+"QY"),state.getFloat(key+"QZ"),state.getFloat(key+"QW")).normalize();
    }
    private static Vec3 vector(CompoundTag state,String key){return new Vec3(state.getDouble(key+"X"),state.getDouble(key+"Y"),state.getDouble(key+"Z"));}
    private static Vec3 interpolate(CompoundTag state,String before,String current,float partial){return vector(state,before).lerp(vector(state,current),partial);}
    private static Vector3f modelPoint(Vec3 point,Vec3 feet,YukkuriEntity actor,float bodyYaw){
        var p=point.subtract(feet);return new Vector3f((float)p.x,(float)p.y,(float)p.z)
                .rotateY((float)Math.toRadians(bodyYaw-180)).mul((float)(1/SelfRescueHatGeometry.bodyUnit(actor)));
    }
    private SelfRescueHatPresentation(){}
}
