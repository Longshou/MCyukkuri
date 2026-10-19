package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.subspecies.HatBoatView;

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
            // The authored stroke travels aft from .4 to 1.2 seconds; map that onto the server power half.
            // Alarm/care animation clocks must never restart or desynchronise this cycle.
            return new CabinDistressPresentation.Motion("boat_paddle",(float)((boat.paddleCycle(partial)*1.6+.4)%1.6));
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
        return action.equals("boat_paddle")||action.equals("hat_brim_offer")||action.equals("boat_nudge_young");
    }

    /** Lift the real twig around its braid grip during the return, without detaching the grip. */
    static void recoverPaddle(PoseStack pose,HatBoatView boat,float partial){
        float lift=(float)Math.max(0,-Math.sin(boat.paddleCycle(partial)*Math.PI*2));
        pose.mulPose(Axis.XP.rotationDegrees(105*lift));
    }

    private AquaticMotionPresentation(){}
}
