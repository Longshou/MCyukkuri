package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.subspecies.HatBoatView;
import net.minecraft.client.renderer.MultiBufferSource;

/** Presentation of the server's real stroke and current, without adding motion or an item entity. */
final class AquaticMotionPresentation {
    static CabinDistressPresentation.Motion motion(YukkuriView entity,float partial){
        var distress=CabinDistressPresentation.motion(entity,partial);
        var boat=entity.vehicleHat();if(boat==null)return distress;
        if(boat.visibleTransporting(entity.getUUID())&&!boat.rocking()&&!boat.capsizing())
            return new CabinDistressPresentation.Motion(boat.visibleCarryActivity(entity.getUUID()),boat.visibleCarrySeconds(partial));
        // Contact, rescue, crowding and capsize poses keep priority over rowing.
        boolean alarmOnly=distress!=null&&switch(distress.clip()){
            case Behaviors.BOAT_ALARM_WAIT,Behaviors.BOAT_ALARM_CALL,Behaviors.BOAT_ANSWER_CALL,Behaviors.BOAT_CALM->true;
            default->false;
        };
        if(distress!=null&&!alarmOnly&&!distress.clip().equals("boat_paddle"))return distress;
        boolean captain=entity.getUUID().equals(boat.ownerYukkuri());
        if(captain&&boat.paddling()&&boat.visiblePaddle()){
            // The clip's power_start..power_end markers carry the stick aft through the water; map that onto the server
            // power half and the rest of the clip onto the recovery. Alarm/care clocks never restart this cycle.
            return new CabinDistressPresentation.Motion("boat_paddle",strokeSeconds(YukkuriModel.get(entity.assetId()),boat.paddleCycle(partial)));
        }
        if(alarmOnly)return distress;
        if(boat.visibleDrifting()||captain&&entity.getAnimationId().equals("boat_paddle")){
            float seconds=(entity.level().getGameTime()%60+partial)/20f;
            return new CabinDistressPresentation.Motion(!entity.isBaby()?"boat_steady":"hat_cradle",seconds);
        }
        return distress;
    }

    /** Both the captain and hull renderer choose one visible location for the same synchronized paddle. */
    static boolean paddleInGrip(YukkuriView entity,HatBoatView boat,float partial){
        if(entity.vehicleHat()==null||entity.vehicleHat().getId()!=boat.getId()
                ||!entity.getUUID().equals(boat.ownerYukkuri())||!boat.visiblePaddle())return false;
        String contact=entity.contactPose();
        if(!contact.equals("NONE")&&!contact.equals("SLIDE"))return false;
        String action=entity.getAnimationId();
        if(boat.rocking()||boat.capsizing())action="boat_steady";
        var displayed=motion(entity,partial);
        if(displayed!=null&&YukkuriModel.get(entity.assetId()).hasClip(displayed.clip()))action=displayed.clip();
        return YukkuriModel.get(entity.assetId()).clipHasProp(action,PADDLE_SLOT);
    }
    /** Clip seconds for a server stroke cycle: [0, .5) is the power half between the clip's power markers. */
    static float strokeSeconds(YukkuriModel model,double cycle){
        float duration=model.clipDuration("boat_paddle");if(!(duration>0))return 0;
        float start=model.clipMarker("boat_paddle","power_start",0),end=model.clipMarker("boat_paddle","power_end",duration/2);
        double c=cycle-Math.floor(cycle);
        double seconds=c<.5?start+(end-start)*c*2:end+(start+duration-end)*(c-.5)*2;
        return (float)(seconds%duration);
    }

    /**
     * The real stick in the braid tuft, placed by the clip's own props.paddle keys (the same keys the previews read):
     * the tuft wraps its grip, the long end swings outside the brim into the water in the power half.
     */
    static void renderHeldStick(YukkuriModel model,String clip,float seconds,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        var socket=model.propPose(clip,PADDLE_SLOT,seconds);
        if(socket==null||!PropModels.has(STICK))return;
        pose.pushPose();pose.mulPose(socket);
        var grip=PropModels.socket(STICK);pose.translate(-grip.x,-grip.y,-grip.z);
        PropModels.render(STICK,8,pose,buffers,light,overlay);
        pose.popPose();
    }
    static final String STICK="paddle_stick",PADDLE_SLOT="paddle";

    private AquaticMotionPresentation(){}
}
