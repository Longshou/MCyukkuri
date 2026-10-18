package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.subspecies.HatBoatView;
import net.minecraft.util.Mth;

/** One server-timed rigid transform for the hat, its cargo and its still-attached passengers. */
final class HatBoatMotion {
    static float yaw(HatBoatView boat,float partial){return Mth.rotLerp(partial,boat.previousYaw(),boat.getYRot());}
    /** In the hat's yaw-aligned frame: roll around local Z, then pitch around local X. */
    static void local(PoseStack pose,HatBoatView boat,float partial){
        pose.mulPose(Axis.ZP.rotationDegrees(boat.visibleMotionRoll(partial)));
        pose.mulPose(Axis.XP.rotationDegrees(boat.visibleMotionPitch(partial)));
    }
    /** The incoming pose already sits at the rider. Move the pivot to the real boat centre before rotating. */
    static void rider(PoseStack pose,YukkuriView rider,HatBoatView boat,float partial){
        var relative=rider.getPosition(partial).subtract(boat.getPosition(partial));
        float frame=180-yaw(boat,partial);
        pose.translate(-relative.x,-relative.y,-relative.z);
        pose.mulPose(Axis.YP.rotationDegrees(frame));
        local(pose,boat,partial);
        pose.mulPose(Axis.YP.rotationDegrees(-frame));
        pose.translate(relative.x,relative.y,relative.z);
    }
    private HatBoatMotion(){}
}
