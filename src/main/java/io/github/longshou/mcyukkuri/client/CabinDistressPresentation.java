package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;

/** Samples only synchronized server phases; this layer grants no movement, flotation, effort or rescue. */
final class CabinDistressPresentation {
    record Motion(String clip,float seconds){}
    static boolean alarmSelected(YukkuriEntity entity){
        String alarm=entity.aquaticAlarmActivity(),behavior=entity.getBehaviorId();
        return !alarm.equals("idle")&&behavior.equals(alarm);
    }
    static Motion motion(YukkuriEntity entity,float partial){
        var rescue=BraidRescuePresentation.motion(entity,partial);if(rescue!=null)return rescue;
        var boarding=boarding(entity,partial);if(boarding!=null)return boarding;
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
            if(boat.rocking()){
                if(entity.getUUID().equals(boat.ownerYukkuri())&&alarmSelected(entity)&&entity.aquaticAlarmActivity().equals("boat_alarm_call"))
                    return new Motion("boat_alarm_call",Math.min(1.2f,Math.max(0,(entity.level().getGameTime()-entity.aquaticAlarmStartTick()+partial)/20f)));
                return new Motion("boat_steady",Math.max(0,(entity.level().getGameTime()-entity.getBehaviorStartTick()+partial)/20f)%1.5f);
            }
        }
        var selfRescue=selfRescue(entity,partial);if(selfRescue!=null)return selfRescue;
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
        if(!alarmSelected(entity))return null;
        String alarm=entity.aquaticAlarmActivity();
        float alarmTime=Math.max(0,(entity.level().getGameTime()-entity.aquaticAlarmStartTick()+partial)/20f);
        return switch(alarm){
            case "boat_alarm_call"->new Motion("boat_alarm_call",Math.min(1.2f,alarmTime));
            case "boat_alarm_wait"->new Motion("boat_alarm_wait",alarmTime%2);
            case "boat_answer_call"->new Motion("boat_answer_call",Math.min(1,alarmTime));
            case "boat_calm"->new Motion("boat_calm",Math.min(4,alarmTime));
            case "boat_flee","boat_return_called"->new Motion(entity.getAnimationId().equals("boat_paddle")?"boat_paddle":"boat_alarm_wait",alarmTime%2);
            default->null;
        };
    }
    private static Motion selfRescue(YukkuriEntity entity,float partial){
        var state=entity.aquaticSelfRescueState();
        if(!entity.getBehaviorId().startsWith("water_hat_")||state.getString("Phase").isEmpty())return null;
        float elapsed=Math.max(0,(entity.level().getGameTime()-state.getLong("Start")+partial)/20f);
        return switch(state.getString("Phase")){
            case "REMOVE_HAT"->new Motion("water_hat_flip",.8f*net.minecraft.util.Mth.clamp(elapsed/.9f,0,1));
            case "DEPLOY_HAT"->new Motion("water_hat_flip",.35f+.45f*net.minecraft.util.Mth.clamp(elapsed,0,1));
            case "GRIP","RIGHT_HAT"->new Motion("water_hat_grip",elapsed%1f);
            case "CLIMB"->new Motion("water_hat_climb",1.2f*net.minecraft.util.Mth.clamp(elapsed/2f,0,1));
            case "SLIPPED"->entity.hasVisibleAccessory()&&state.getBoolean("Planned")&&state.getFloat("RemovalProgress")>0
                    ?new Motion("water_hat_flip",.8f*state.getFloat("RemovalProgress")*(1-net.minecraft.util.Mth.clamp(elapsed,0,1)))
                    :new Motion("water_gasp",elapsed%1.6f);
            case "SAFE"->new Motion("water_recover",Math.min(2.8f,elapsed));
            default->null; // Approach/failure remain the actual immersed struggle and breath.
        };
    }
    private static Motion boarding(YukkuriEntity entity,float partial){
        var state=entity.boardingState();String behavior=entity.getBehaviorId();
        if(!state.getBoolean("Active")||!(behavior.equals("boat_board")||behavior.startsWith("hat_board_")
                ||behavior.startsWith("water_hat_")))return null;
        float elapsed=Math.max(0,(entity.level().getGameTime()-state.getLong("Start")+partial)/20f);
        float q=net.minecraft.util.Mth.clamp(elapsed/(Math.max(1,state.getInt("Duration"))/20f),0,1);
        // Every root position belongs to HatBoarding; these clips add pressure and breathing only.
        return switch(state.getString("Phase")){
            case "ALIGN","GRIP"->new Motion("water_hat_grip",elapsed%1f);
            case "HOP"->new Motion("water_hat_climb",.35f+.3f*q);
            case "LIFT"->new Motion("water_hat_climb",.65f*q);
            case "SETTLE"->new Motion("water_hat_climb",.65f+.55f*q);
            default->null;
        };
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
