package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.YukkuriComponents;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.LearnedFear;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/** Kin make one bounded approach to the actual hook and free it by contact. */
public final class HookRescueGoal extends Goal {
    private static final int RANGE=8, WARNING_TICKS=20, BUDGET=600;
    private final YukkuriEntity helper;
    private YukkuriEntity target;
    private UUID wound;
    private final Set<UUID> attemptedWounds = new HashSet<>();
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
        long now=helper.level().getGameTime();
        if(now<nextScan||!available())return false;
        nextScan=now+WARNING_TICKS;
        double best=RANGE*RANGE;
        var nearby=helper.level().getEntitiesOfClass(YukkuriEntity.class,helper.getBoundingBox().inflate(RANGE),
                kin->kin.operations().hookWound()!=null);
        attemptedWounds.retainAll(nearby.stream().map(kin->kin.operations().hookWound()).toList());
        for(var kin:nearby){
            var candidate=kin.operations().hookWound();
            double distance=helper.distanceToSqr(kin);
            if(kin==helper||attemptedWounds.contains(candidate)||distance>best||!kin.isAlive()
                    ||kin.subspecies().kind().aquatic()!=helper.subspecies().kind().aquatic()
                    ||!helper.hasLineOfSight(kin)||!helper.life().canRecognize(kin)
                    ||!(Bonds.directParentChild(helper,kin)||Bonds.eitherMate(helper,kin)))continue;
            target=kin;wound=candidate;best=distance;
        }
        return target!=null;
    }

    @Override public void start() {
        ticks=contactTicks=0;result=null;attemptedWounds.add(wound);
        helper.showReaction("teary","afraid",60);
        var personality=helper.life().personality();
        int fear=personality.fear(target.operations().operator());
        double courage=SocialDisposition.ramp(personality.boldness()-fear,20,65);
        double chance=courage*personality.helpProbability(true,true);
        double roll=helper.getRandom().nextDouble();
        if(roll>=chance)result="stood_off";
        GameplayLog.event(helper,"social","hook rescue cue target={} wound={} fear={} chance={} roll={}",
                target.getUUID(),wound,fear,chance,roll);
    }

    @Override public boolean canContinueToUse() {
        return ticks<BUDGET && (result==null||ticks<WARNING_TICKS) && available() && target.isAlive()
                && target.level()==helper.level() && wound.equals(target.operations().hookWound());
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
        if(move!=SurfaceApproach.Result.ARRIVED)return;
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
