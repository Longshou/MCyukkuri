package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.YukkuriComponents;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.LearnedFear;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/** Kin make one bounded approach to the actual hook and free it by contact. */
public final class HookRescueGoal extends Goal {
    // The scan repeats inside the 10 ticks the struck child keeps hurtTime and well before a startled parent has run out of RANGE.
    private static final int RANGE=8, WARNING_TICKS=20, BUDGET=600, SCAN_TICKS=5;
    private final YukkuriEntity helper;
    private YukkuriEntity target;
    private UUID wound;
    private final Map<YukkuriEntity,UUID> attemptedWounds = new HashMap<>();
    private Vec3 contact, stand;
    private int ticks, contactTicks;
    private long nextScan;
    private String result;

    public HookRescueGoal(YukkuriEntity helper) {
        this.helper=helper;
        setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK));
    }

    private boolean available() {
        var body=helper.body();
        return LearnedFear.eligible(helper) && helper.childhood().canLiveIndependently() && !helper.isLeashed()
                && !helper.isPanicking() && !helper.isOnFire() && !helper.life().hasUrgentNeed()
                && !helper.operations().active() && !helper.family().birthDuty() && !helper.family().laboring()
                && helper.family().brood()==null && !helper.pet().busy() && helper.life().mealTicks()==0
                && !helper.isVehicle() && !helper.isCommandPlaying()
                && body.wound()==0 && body.burns()==0 && body.infection()==0 && body.mold()==0
                && helper.component(YukkuriComponents.MOUTH).purpose()==null;
    }

    @Override public boolean canUse() {
        if(!available())return false;
        if(!sameHook()){
            target=null;wound=null;
        }else if(seesKin(target)&&willing(target)){
            return true;
        }
        return findHook();
    }

    private boolean sameHook() {
        return target!=null && target.isAlive() && target.level()==helper.level()
                && wound.equals(target.operations().hookWound());
    }

    private boolean seesKin(YukkuriEntity kin) {
        return kin.subspecies().kind().aquatic()==helper.subspecies().kind().aquatic()
                && helper.hasLineOfSight(kin) && helper.life().canRecognize(kin)
                && (Bonds.directParentChild(helper,kin)||Bonds.eitherMate(helper,kin));
    }

    private boolean findHook() {
        long now=helper.level().getGameTime();
        if(now<nextScan)return false;
        nextScan=now+SCAN_TICKS;
        double best=RANGE*RANGE;
        boolean ready=false;
        var nearby=helper.level().getEntitiesOfClass(YukkuriEntity.class,helper.getBoundingBox().inflate(RANGE),
                kin->kin.operations().hookWound()!=null);
        attemptedWounds.entrySet().removeIf(entry->!entry.getKey().isAlive()
                ||!entry.getValue().equals(entry.getKey().operations().hookWound()));
        for(var kin:nearby){
            var candidate=kin.operations().hookWound();
            double distance=helper.distanceToSqr(kin);
            if(kin==helper||candidate.equals(attemptedWounds.get(kin))||distance>best||!kin.isAlive()||!seesKin(kin))continue;
            if(willing(kin)){
                target=kin;wound=candidate;best=distance;ready=true;
            }else if(target==null){
                target=kin;wound=candidate;
            }
        }
        return ready;
    }

    private boolean willing(YukkuriEntity kin) {
        var threat=helper.status().reactions().threat();
        var operator=kin.operations().loadedOperator();
        var danger=threat.source();
        if(operator==null||kin.hurtTime>0||helper.hurtTime>0||threat.holdingStance()
                ||threat.seesThreat()||danger!=null&&danger!=operator)return false;
        var personality=helper.life().personality();
        var temperament=personality.temperament();
        int fear=personality.fear(operator.getUUID());
        double drive=(temperament.caring()+temperament.attached())*.5;
        // Judge both ends of the approach. Stepping away alone cannot make a guard at the hook less threatening.
        double distance=Math.min(helper.distanceTo(operator),kin.distanceTo(operator));
        double proximity=1-SocialDisposition.ramp(distance,1,personality.threatDistance(fear));
        double pressure=proximity*(.5+.5*SocialDisposition.ramp(fear,0,65));
        return drive>pressure;
    }

    @Override public void start() {
        ticks=contactTicks=0;result=null;
        attemptedWounds.put(target,wound);
        helper.showReaction("teary","afraid",60);
        var personality=helper.life().personality();
        GameplayLog.event(helper,"social","hook rescue cue target={} wound={} fear={} attachment={} boldness={} friendliness={}",
                target.getUUID(),wound,personality.fear(target.operations().operator()),personality.attachment(),
                personality.boldness(),personality.friendliness());
    }

    @Override public boolean canContinueToUse() {
        if(ticks>=BUDGET||result!=null||!available()||!sameHook())return false;
        if(willing(target))return true;
        result="danger";
        return false;
    }

    @Override public boolean requiresUpdateEveryTick() {return true;}

    @Override public void tick() {
        var point=target.operations().hookContact();
        if(point==null){result="hook_gone";return;}
        helper.getLookControl().setLookAt(point.x,point.y,point.z);
        if(++ticks<WARNING_TICKS||result!=null)return;
        if(contact==null||contact.distanceToSqr(point)>.04){
            contact=point;
            contactTicks=0;
            double distance=helper.getBbWidth()*.5+ContactGeometry.GRAB_MARGIN*.5;
            var ground=new Vec3(point.x,helper.getY(),point.z);
            stand=SurfaceApproach.beside(helper,ground,distance,target,
                    at->ContactGeometry.mouthReachesPoint(helper,at,SurfaceApproach.yaw(at,point),point));
            if(stand==null){
                // Even with a high hook, the helper can walk to its foot and visibly try from below.
                double clearance=(helper.getBbWidth()+target.getBbWidth())*.5+ContactGeometry.GRAB_MARGIN;
                var below=new Vec3(target.getX(),helper.getY(),target.getZ());
                stand=SurfaceApproach.beside(helper,below,clearance,target,at->true);
                if(stand==null){result="blocked";return;}
            }
        }
        var move=SurfaceApproach.walkResult(helper,stand,target,1.1);
        if(move==SurfaceApproach.Result.BLOCKED){result="blocked";return;}
        // The walk layer asks for an exact stand (.02); a body that has dithered to within a fifth of a block of it is at the hook.
        if(move!=SurfaceApproach.Result.ARRIVED){
            if(helper.position().subtract(stand).horizontalDistanceSqr()>.04)return;
            SurfaceApproach.stopWalking(helper);
        }
        Motor.face(helper,SurfaceApproach.yaw(helper.position(),point));
        if(++contactTicks==1){
            helper.forceReaction("surprise","",WARNING_TICKS);
            GameplayLog.event(helper,"social","hook rescue attempt target={} wound={} stand={} hook={}",
                    target.getUUID(),wound,GameplayLog.at(stand),GameplayLog.at(point));
        }
        if(contactTicks<WARNING_TICKS)return;
        if(target.operations().releaseHook(helper,wound))result="released";
        else{
            helper.forceReaction("teary","afraid",60);
            result="no_reach";
        }
    }

    @Override public void stop() {
        GameplayLog.event(helper,"social","hook rescue end target={} wound={} result={} ticks={}",
                target.getUUID(),wound,result==null?(ticks>=BUDGET?"timeout":"interrupted"):result,ticks);
        SurfaceApproach.halt(helper);
        target=null;wound=null;stand=null;contact=null;
    }
}
