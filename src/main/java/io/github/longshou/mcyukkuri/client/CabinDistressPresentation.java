package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;

/** Samples only synchronized server phases; this layer grants no movement, flotation, effort or rescue. */
final class CabinDistressPresentation {
    record Motion(String clip,float seconds){}
    static Motion motion(YukkuriEntity entity,float partial){
        if(entity.getVehicle() instanceof HatBoatEntity boat){
            if(boat.capsizing())return new Motion(entity.getUUID().equals(boat.ownerYukkuri())?"boat_steady":"hat_stumble",
                    Math.max(0,(entity.level().getGameTime()-entity.getBehaviorStartTick()+partial)/20f));
            String state=boat.crowdingState(entity);
            String clip=switch(state){
                case "hat_stumble","hat_slipping"->"hat_stumble";
                case "hat_jostle","hat_scramble"->"hat_jostle";
                case "boat_crowd_guard"->"boat_reassure";
                default->null;
            };
            if(clip!=null)return new Motion(clip,Math.max(0,(entity.level().getGameTime()-boat.crowdingPhaseStart(entity)+partial)/20f)
                    %(clip.equals("hat_jostle")?1f:clip.equals("boat_reassure")?2f:1.2f));
            if(boat.rocking())return new Motion("boat_steady",Math.max(0,(entity.level().getGameTime()-entity.getBehaviorStartTick()+partial)/20f)%1.5f);
        }
        String phase=entity.waterDistressPhase();
        float seconds=Math.max(0,(entity.level().getGameTime()-entity.waterDistressStartTick()+partial)/20f);
        switch(phase){
            case "FALLING":return new Motion("hat_stumble",Math.min(.6f,seconds));
            case "IMPACT":return new Motion("water_plunge",Math.min(.6f,seconds));
            case "STRUGGLE":return new Motion(entity.waterDistressEffort()?"water_struggle":"water_gasp",seconds%1.6f);
            case "EXHAUSTED":return new Motion("water_gasp",seconds%1.6f);
            case "RECOVER":return new Motion("water_recover",Math.min(2.8f,seconds));
            default:break;
        }
        return null;
    }
    /** Shared vessel roll was already applied about the boat centre; this correction pivots on this rider's real support. */
    static void balance(PoseStack pose,YukkuriEntity entity,HatBoatEntity boat,float partial,float renderedBodyYaw){
        float frame=renderedBodyYaw-HatBoatMotion.yaw(boat,partial);
        pose.mulPose(Axis.YP.rotationDegrees(frame));
        pose.mulPose(Axis.ZP.rotationDegrees(boat.riderBalanceRoll(entity,partial)));
        pose.mulPose(Axis.XP.rotationDegrees(boat.riderBalancePitch(entity,partial)));
        pose.mulPose(Axis.YP.rotationDegrees(-frame));
    }
    private CabinDistressPresentation(){}
}
