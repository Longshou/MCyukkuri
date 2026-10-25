package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.subspecies.HatBoatView;
import io.github.longshou.mcyukkuri.subspecies.SelfRescueHatGeometry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

/** Samples only synchronized server phases; this layer grants no movement, flotation, effort or rescue. */
final class CabinDistressPresentation {
    record Motion(String clip,float seconds){}
    static boolean alarmSelected(YukkuriView entity){
        String alarm=entity.aquaticAlarmActivity(),behavior=entity.getBehaviorId();
        return !alarm.equals(Behaviors.IDLE)&&behavior.equals(alarm);
    }
    static Motion motion(YukkuriView entity,float partial){
        var rescue=BraidRescuePresentation.motion(entity,partial);if(rescue!=null)return rescue;
        var boarding=boarding(entity,partial);if(boarding!=null)return boarding;
        var boat=entity.vehicleHat();
        if(boat!=null){
            if(boat.capsizing())return new Motion(entity.getUUID().equals(boat.ownerYukkuri())?"boat_steady":"hat_stumble",
                    Math.max(0,(entity.level().getGameTime()-entity.getBehaviorStartTick()+partial)/20f));
            String state=boat.visibleCrowdingState(entity);
            String clip=switch(state){
                case Behaviors.HAT_STUMBLE,Behaviors.HAT_SLIPPING->"hat_stumble";
                case Behaviors.HAT_JOSTLE,Behaviors.HAT_SCRAMBLE->"hat_jostle";
                case Behaviors.BOAT_CROWD_GUARD->"boat_reassure";
                default->null;
            };
            if(clip!=null)return new Motion(clip,Math.max(0,(entity.level().getGameTime()-boat.visibleCrowdingPhaseStart(entity.getUUID())+partial)/20f)
                    %(clip.equals("hat_jostle")?1f:clip.equals("boat_reassure")?2f:1.2f));
            if(boat.rocking()){
                if(entity.getUUID().equals(boat.ownerYukkuri())&&alarmSelected(entity)&&entity.aquaticAlarmActivity().equals(Behaviors.BOAT_ALARM_CALL))
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
            case Behaviors.BOAT_ALARM_CALL->new Motion("boat_alarm_call",Math.min(1.2f,alarmTime));
            case Behaviors.BOAT_ALARM_WAIT->new Motion("boat_alarm_wait",alarmTime%2);
            case Behaviors.BOAT_ANSWER_CALL->new Motion("boat_answer_call",Math.min(1,alarmTime));
            case Behaviors.BOAT_CALM->new Motion("boat_calm",Math.min(4,alarmTime));
            case Behaviors.BOAT_FLEE,Behaviors.BOAT_RETURN_CALLED->new Motion(entity.getAnimationId().equals("boat_paddle")?"boat_paddle":"boat_alarm_wait",alarmTime%2);
            default->null;
        };
    }
    private static Motion selfRescue(YukkuriView entity,float partial){
        var motion=SelfRescueHatGeometry.motion(entity,partial);
        return motion==null?null:new Motion(motion.clip(),motion.seconds());
    }
    private static Motion boarding(YukkuriView entity,float partial){
        var state=entity.boardingState();String behavior=entity.getBehaviorId();
        if(!state.getBoolean("Active")||!(behavior.equals(Behaviors.BOAT_BOARD)||behavior.startsWith(Behaviors.HAT_BOARD_PREFIX)
                ||behavior.startsWith(Behaviors.WATER_HAT_PREFIX)))return null;
        float elapsed=Math.max(0,(entity.level().getGameTime()-state.getLong("Start")+partial)/20f);
        float q=Mth.clamp(elapsed/(Math.max(1,state.getInt("Duration"))/20f),0,1);
        // Every root position belongs to the crew transfer; these clips add pressure and breathing only.
        // GRIP bites the rim, LIFT surges twice up the dipped rim, SETTLE slides over it, HOP is the small hop (project original).
        return switch(state.getString("Phase")){
            case "ALIGN"->new Motion("water_hat_grip",0);
            case "GRIP"->new Motion("water_mouth_grip",elapsed%.8f);
            case "HOP"->new Motion("hat_hop_up",.3f+.5f*q);
            case "LIFT"->new Motion("water_bob_lunge",.7f*((q*2)%1f));
            case "SETTLE"->new Motion("hat_slide_over",1.4f*q);
            default->null;
        };
    }
    /** Keep the actual distal braid on the held rim while the server moves the body in effort pulses. */
    static void configureBoardingContact(YukkuriView entity,YukkuriModel model,float partial,float renderedYaw){
        var state=entity.boardingState();
        if(!state.getBoolean("Active"))return;
        if(state.contains("RootPitch")){
            float pitch=(float)state.getDouble("RootPitch"),roll=(float)state.getDouble("RootRoll");
            float squash=state.contains("RootSquash")?state.getFloat("RootSquash"):1;
            if(state.contains("PrevRootSquash"))squash=Mth.lerp(partial,state.getFloat("PrevRootSquash"),squash);
            float q=Mth.clamp((entity.level().getGameTime()-state.getLong("Start")+partial)/Math.max(1,state.getInt("Duration")),0,1);
            // The slide's yaw wriggle shows only after the bite has let go (second half of SETTLE).
            float wriggle=state.getString("Phase").equals("SETTLE")?Mth.clamp((q-.45f)/.2f,0,1):0;
            model.setBoardingPose(state.contains("PrevRootPitch")?Mth.lerp(partial,(float)state.getDouble("PrevRootPitch"),pitch):pitch,
                    state.contains("PrevRootRoll")?Mth.lerp(partial,(float)state.getDouble("PrevRootRoll"),roll):roll,squash,wriggle);
        }
        if(!state.contains("TipX")||!state.contains("JointX"))return;
        // Joint, tip and body use the same server-tick interpolation interval.
        model.setBraidRescueContact(boardingPoint(entity,state,"Tip",partial,renderedYaw));
        model.setBraidRescueJoint(boardingPoint(entity,state,"Joint",partial,renderedYaw));
    }
    private static Vector3f boardingPoint(YukkuriView entity,CompoundTag state,String key,float partial,float yaw){
        double x=state.getDouble(key+"X"),y=state.getDouble(key+"Y"),z=state.getDouble(key+"Z");
        if(state.contains("Prev"+key+"X")){
            x=Mth.lerp(partial,state.getDouble("Prev"+key+"X"),x);
            y=Mth.lerp(partial,state.getDouble("Prev"+key+"Y"),y);
            z=Mth.lerp(partial,state.getDouble("Prev"+key+"Z"),z);
        }
        var at=entity.getPosition(partial);
        return new Vector3f((float)(x-at.x),(float)(y-at.y),(float)(z-at.z))
                .rotateY((yaw-180)*Mth.DEG_TO_RAD).mul(16/Math.max(.001f,entity.getScale()*entity.growthScale()));
    }
    /** Shared vessel roll was already applied about the boat centre; this correction pivots on this rider's real support. */
    static void balance(PoseStack pose,YukkuriView entity,HatBoatView boat,float partial,float renderedBodyYaw){
        float frame=renderedBodyYaw-HatBoatMotion.yaw(boat,partial);
        pose.mulPose(Axis.YP.rotationDegrees(frame));
        pose.mulPose(Axis.ZP.rotationDegrees(boat.visibleBalanceRoll(entity,partial)));
        pose.mulPose(Axis.XP.rotationDegrees(boat.visibleBalancePitch(entity,partial)));
        pose.mulPose(Axis.YP.rotationDegrees(-frame));
    }
    private CabinDistressPresentation(){}
}
