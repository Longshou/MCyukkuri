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

    /**
     * Pose the real twig in the braid-tip frame: gripped near its upper end a little outboard of the tip,
     * leaning out past the brim so its lower end dips beside the hull. The braid's own sweep carries the stroke; while rowing the twig
     * sweeps a little further aft in the power half and is raised outward out of the water on the return.
     */
    static void holdPaddle(PoseStack pose,HatBoatView boat,float partial,boolean stroking){
        double cycle=stroking?boat.paddleCycle(partial):0;
        float lift=stroking?(float)Math.max(0,-Math.sin(cycle*Math.PI*2)):0,sweep=stroking?(float)Math.cos(cycle*Math.PI*2):0;
        pose.translate(PADDLE_REACH,0,0);
        pose.mulPose(Axis.ZP.rotationDegrees(PADDLE_OUTWARD+PADDLE_LIFT*lift));
        pose.mulPose(Axis.XP.rotationDegrees(PADDLE_SWEEP*sweep));
        pose.translate(0,-PADDLE_GRIP_OFFSET,0);
        // FIXED item display turns the sprite 180 degrees about Y, mirroring its diagonal; -45 stands it on the grip axis.
        pose.mulPose(Axis.ZP.rotationDegrees(-45));pose.scale(.85f,.85f,.85f);
    }
    private static final float PADDLE_OUTWARD=30,PADDLE_LIFT=30,PADDLE_SWEEP=10,PADDLE_GRIP_OFFSET=.26f,PADDLE_REACH=.05f;

    private AquaticMotionPresentation(){}
}
