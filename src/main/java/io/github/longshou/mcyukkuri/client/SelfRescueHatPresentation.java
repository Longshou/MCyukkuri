package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.SelfRescueHatGeometry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** Displays the server's single shaken-off hat pose; it never invents a transfer path and the braid takes no part. */
final class SelfRescueHatPresentation {
    static void configure(YukkuriEntity entity,YukkuriModel model,float partial,float bodyYaw){
        model.setSelfHatPose(null);
        var state=entity.aquaticSelfRescueState();
        if(!entity.getBehaviorId().startsWith("water_hat_"))return;
        var feet=entity.getPosition(partial);
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
    private SelfRescueHatPresentation(){}
}
