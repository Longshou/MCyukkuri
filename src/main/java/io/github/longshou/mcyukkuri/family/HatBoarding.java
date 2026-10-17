package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Finite, server-owned boarding from actual feet, or a bite-and-lunge climb at a near brim that the hat's weight shift has lowered.
 *  The mouth, not the braid, takes hold (project-original, not canon); the braid never bears weight. */
public final class HatBoarding {
    public enum Phase { IDLE, ALIGN, HOP, GRIP, LIFT, SETTLE }
    private final YukkuriEntity actor;
    private Phase phase=Phase.IDLE;
    private UUID sourceId,hatId,abandonedHat;
    private Vec3 seat,from,to,grip,crest,belly,liftFrom,biteFeet,alignedFrom,hopTo;
    private double gripSupportY=Double.NaN;
    private boolean stagedClimb;
    private boolean releasedGrip,landing,shoreLanding;
    private List<Vec3> shorePath=List.of();
    private int shoreStep;
    private double shoreFallSpeed;
    private UUID shoreHelper;
    public static final double BELLY_SUPPORT_PROGRESS=.42;
    public static final double GRIP_RELEASE_PROGRESS=.50;
    public static final int GRIP_TICKS=12, BITE_TICKS=8, LIFT_TICKS=28, MIN_SETTLE_TICKS=30;
    private boolean waterAlign;
    private float boardingYaw;
    private float bellyYaw,previousBodyYaw;
    private int settleTicks=MIN_SETTLE_TICKS,biteTicks;
    private double previousPitch,previousRoll,previousSquash=1,wall;
    /** The weight shift the plan assumed (and keeps asking the hull for); NONE when the hull cannot or need not dip. */
    private HatBoatEntity.Dip dip=HatBoatEntity.Dip.NONE;
    private double hump;
    private long start,lastTick=Long.MIN_VALUE;
    private int duration,hopTicks,steadyTicks,totalTicks;
    private float lastHatYaw,lastSourceYaw;
    private Vec3 lastVelocity=Vec3.ZERO;
    public HatBoarding(YukkuriEntity actor){this.actor=actor;}
    private ServerLevel level(){return (ServerLevel)actor.level();}
    private HatBoatEntity boat(UUID id){return id!=null&&level().getEntity(id) instanceof HatBoatEntity h?h:null;}
    public boolean active(){return phase!=Phase.IDLE;}
    /** Only these two hollow hulls use the measured rim geometry instead of their coarse entity box. */
    public boolean traverses(HatBoatEntity hat){return active()&&hat!=null&&(hat.getUUID().equals(hatId)||hat.getUUID().equals(sourceId));}
    public boolean landingAshore(){return active()&&shoreLanding;}
    public boolean targets(HatBoatEntity hat){return active()&&hat!=null&&hat.getUUID().equals(hatId);}
    public boolean climbing(){return phase==Phase.ALIGN&&waterAlign||phase==Phase.GRIP||phase==Phase.LIFT||phase==Phase.SETTLE;}
    /** The teeth are on the rim: through the bite and both lunges, released as the belly slides over. */
    public boolean gripped(){return phase==Phase.GRIP||phase==Phase.LIFT;}
    private boolean capable(){return actor.isAlive()&&!actor.isNoAi()&&!actor.isCommandPlaying()&&!actor.operations().active()
            &&!actor.isOnFire()&&!actor.family().braidRescueActive()&&actor.family().stage()!=FamilyLife.Stage.BABY&&actor.childhood().canJump()
            &&actor.life().energy()>=8&&!actor.life().isSleeping();}
    public static boolean stable(HatBoatEntity hat){
        return hat!=null&&!hat.isRemoved()&&(hat.isAfloat()||hat.catches())&&!hat.unsteadyMotion()
                &&!hat.towed()&&hat.flooding()==0;
    }
    /** A tiny step-over arc, whose clearance is measured from this body's scale, not a minimum airtime. */
    public static Vec3 hopPoint(Vec3 from,Vec3 to,int ticks,double elapsed){return hopPoint(from,to,ticks,elapsed,Math.min(.04,from.distanceTo(to)*.08));}
    public static Vec3 hopPoint(Vec3 from,Vec3 to,int ticks,double elapsed,double height){
        double q=Mth.clamp(elapsed/Math.max(1,ticks),0,1);return from.lerp(to,q).add(0,4*height*q*(1-q),0);
    }
    public static double hopHeight(YukkuriEntity actor,Vec3 from,Vec3 to){
        double scale=actor.growthScale()*actor.getScale();return Math.min(.08*scale,Math.max(.01*scale,from.subtract(to).horizontalDistance()*.08));
    }
    public static double smooth(double q){q=Mth.clamp(q,0,1);return q*q*(3-2*q);}
    /** Two muscle pulls separated by a real pause; renderer and preview sample this same progress. */
    public static double pullProgress(double q){return .58*smooth((q-.15)/.23)+.42*smooth((q-.62)/.23);}
    public static double pullEffort(double q){
        return Math.max(pulse((q-.15)/.23),pulse((q-.62)/.23));
    }
    private static double pulse(double q){return q>0&&q<1?Math.pow(Math.sin(Math.PI*q),2):0;}
    /** Whole-body lean stays within -8..+6 degrees; effort is shown by squash and stretch instead. */
    public static double boardRootPitch(String phase,double q){return switch(phase){
        case "GRIP"->4*smooth(q);case "LIFT"->-4-4*pullProgress(q);
        case "SETTLE"->-8+8*smooth((q-.42)/.58);default->0;};}
    public static double boardRootRoll(String phase,double q){return switch(phase){
        case "GRIP"->-2*pulse(q);case "LIFT"->3*(pulse((q-.15)/.23)-pulse((q-.62)/.23));
        case "SETTLE"->-3*pulse((q-.08)/.34);default->0;};}
    /** Height squash of the root (1 = none): crouched before each lunge, stretched through it, flat while sliding over. */
    public static double boardRootSquash(String phase,double q){return switch(phase){
        case "GRIP"->1-.06*smooth(q);case "LIFT"->.90+.08*pullEffort(q);
        case "SETTLE"->.88+.12*smooth((q-.5)/.5);default->1;};}
    private static double drop(HatBoatEntity hat,HatBoatEntity.Dip dip,Vec3 local){
        return dip==null?hat.rimLift(local):HatBoatEntity.rimLift(local,dip,hat.unit());
    }
    public static Vec3 settlePoint(Vec3 crest,Vec3 belly,Vec3 to,double q){
        return q<.55?climbPoint(crest,belly,smooth((q-.08)/.34)):climbPoint(belly,to,smooth((q-.58)/.34));
    }
    public static float settleYaw(float fromYaw,float toYaw,double q){
        return fromYaw+Mth.wrapDegrees(toYaw-fromYaw)*(float)smooth((q-.08)/.34);
    }
    /** The belly slides from the crest over the lip, never lower than the measured surface under the posed body. */
    public static Vec3 slidePoint(YukkuriEntity actor,HatBoatEntity hat,Vec3 crest,Vec3 belly,Vec3 to,double q,float fromYaw,float toYaw,HatBoatEntity.Dip dip){
        float yaw=settleYaw(fromYaw,toYaw,q);
        if(q>=BELLY_SUPPORT_PROGRESS)return settleSupport(actor,hat,settlePoint(crest,belly,to,q),q,yaw,dip);
        var at=climbPoint(crest,belly,smooth((q-.08)/.34));
        var low=at.subtract(0,1,0);
        double rest=BraidRescueGeometry.poseClearance(actor,low,boardRootPitch("SETTLE",q),boardRootRoll("SETTLE",q),yaw,hat,dip);
        return new Vec3(at.x,Math.max(at.y,rest),at.z);
    }
    /** A real source surface holds the underside while the body leans, so the feet rise continuously. */
    public static Vec3 gripPoint(YukkuriEntity actor,Vec3 origin,double supportY,double q,float yaw){
        if(!Double.isFinite(supportY))return origin;
        var body=BraidRescueGeometry.boardingEnvelope(actor,origin,boardRootPitch("GRIP",q),boardRootRoll("GRIP",q),yaw).getFirst();
        return new Vec3(origin.x,supportY-(body.minY-origin.y),origin.z);
    }
    /** The first lunge still bears on the real source brim until the posed body has cleared it; a null dip means the hull's current one. */
    public static Vec3 liftPoint(YukkuriEntity actor,HatBoatEntity source,HatBoatEntity target,Vec3 from,Vec3 crest,double q,float yaw,HatBoatEntity.Dip dip){
        var nominal=from.lerp(crest,pullProgress(q));
        double pitch=boardRootPitch("LIFT",q),roll=boardRootRoll("LIFT",q),supported=nominal.y;
        for(var vessel:new HatBoatEntity[]{source,target})if(vessel!=null&&vessel.isAfloat()&&!vessel.unsteadyMotion())
            supported=Math.max(supported,BraidRescueGeometry.poseClearance(actor,nominal,pitch,roll,yaw,vessel,vessel==target?dip:null));
        return new Vec3(nominal.x,Math.max(nominal.y,supported),nominal.z);
    }
    public static Vec3 climbPoint(Vec3 from,Vec3 to,double progress){
        double t=Mth.clamp(progress,0,1);return from.lerp(to,t*t*(3-2*t));
    }
    public static double maximumGap(YukkuriEntity actor){return .45*Math.min(1,actor.growthScale()*actor.getScale())+2*HatVessel.radius(actor.getBbWidth());}
    public static int hopDuration(YukkuriEntity actor,HatBoatEntity source,HatBoatEntity hat,Vec3 from,Vec3 to){return hopDuration(actor,source,hat,from,to,null);}
    /** The target is where the body really lands, i.e. already lowered with a dipped near rim. */
    public static int hopDuration(YukkuriEntity actor,HatBoatEntity source,HatBoatEntity hat,Vec3 from,Vec3 to,HatBoatEntity.Dip dip){
        double scale=Math.min(1,actor.growthScale()*actor.getScale()),distance=from.subtract(to).horizontalDistance();
        if(distance>maximumGap(actor)||to.y-from.y>.10*scale||from.y-to.y>.25*scale)return 0;
        for(int ticks=3;ticks<=12;ticks++){
            if(distance/ticks>.012+.09*scale)continue;
            boolean clear=true;Vec3 previous=from;
            for(int step=1;step<=ticks*4;step++){
                var at=hopPoint(from,to,ticks,step*.25,hopHeight(actor,from,to));
                if(!sweepClear(actor,source,hat,previous,at,dip)){clear=false;break;}previous=at;
            }
            if(clear)return ticks;
        }
        return 0;
    }
    private boolean dippable(HatBoatEntity hat){return hat.dippable(actor);}
    /** The grip as it will stand once the hull has leaned the planned amount. */
    private static Vec3 assumedGrip(HatBoatEntity hat,Vec3 grip,HatBoatEntity.Dip plan){
        if(grip==null||plan==null||plan.degrees()<=0)return grip;
        var local=hat.toLocal(grip);
        return grip.subtract(0,Math.max(0,HatBoatEntity.rimLift(local,plan,hat.unit())-hat.rimLift(local)),0);
    }
    public boolean begin(HatBoatEntity source,HatBoatEntity hat,Vec3 localSeat){
        if(active())return hat.getUUID().equals(hatId);
        if(!capable()||!stable(hat)||actor.isInWaterOrBubble()||localSeat==null
                ||source!=null&&(!stable(source)||actor.getVehicle()!=source||!source.protects(actor)||source.changingSeat(actor))
                ||source==null&&(actor.isPassenger()||!actor.onGround()||!actor.life().dryStand(actor.blockPosition())))return false;
        var origin=actor.position();var destination=hat.toWorld(localSeat);
        var plan=dippable(hat)?hat.planDip(origin,HatBoatEntity.MAX_DIP):HatBoatEntity.Dip.NONE;
        // At a reachable rim the mouth takes hold first; only a genuinely low step may bypass the climb.
        var landingSpot=destination.subtract(0,HatBoatEntity.rimLift(localSeat,plan,hat.unit()),0);
        boolean climbing=planClimb(source,hat,origin,destination,assumedGrip(hat,hat.boardingGrip(actor),plan),plan);
        int ticks=climbing?0:hopDuration(actor,source,hat,origin,landingSpot,plan);
        if(!climbing&&ticks==0)return false;
        sourceId=source==null?null:source.getUUID();hatId=hat.getUUID();seat=localSeat;dip=plan;
        from=origin;to=destination;stagedClimb=climbing;waterAlign=false;releasedGrip=landing=false;biteTicks=0;if(!climbing)grip=null;
        hopTo=landingSpot;hump=hopHeight(actor,origin,landingSpot);hopTicks=ticks;steadyTicks=totalTicks=0;
        lastHatYaw=hat.getYRot();lastSourceYaw=source==null?0:source.getYRot();
        lastVelocity=Vec3.ZERO;set(Phase.ALIGN,6);hold(source,hat);return true;
    }
    public boolean climb(HatBoatEntity hat,Vec3 localSeat,Vec3 actualGrip){
        if(active())return climbing()&&hat.getUUID().equals(hatId);
        if(!capable()||!stable(hat)||actor.isPassenger()||!actor.isInWaterOrBubble()||localSeat==null||actualGrip==null)return false;
        var destination=hat.toWorld(localSeat);var origin=actor.position();
        // The climber's own weight on the near rim is what leans the hull; the planner assumes exactly that lean.
        var plan=dippable(hat)?hat.planDip(origin,HatBoatEntity.MAX_DIP):HatBoatEntity.Dip.NONE;
        if(!planClimb(null,hat,origin,destination,assumedGrip(hat,actualGrip,plan),plan))return false;
        if(!actor.life().spendEnergy(.5f))return false;
        sourceId=null;hatId=hat.getUUID();seat=localSeat;dip=plan;from=origin;to=destination;stagedClimb=true;hump=0;releasedGrip=landing=false;biteTicks=0;
        steadyTicks=totalTicks=0;lastVelocity=Vec3.ZERO;waterAlign=true;set(Phase.ALIGN,6);hat.holdPosition();return true;
    }
    /** A supported slide onto a touching low bank. A remote/high bank is never a dismount target. */
    public boolean startLanding(HatBoatEntity source,Vec3 dryFeet){
        if(active())return shoreLanding&&source!=null&&source.getUUID().equals(sourceId);
        if(actor.level().isClientSide||source==null||dryFeet==null||!stable(source)||actor.getVehicle()!=source
                ||!source.protects(actor)||source.changingSeat(actor)||!actor.isAlive()||actor.isNoAi()
                ||actor.isCommandPlaying()||actor.operations().active()||actor.family().braidRescueActive()
                ||actor.position().distanceToSqr(dryFeet)>2.25||!actor.life().dryStand(BlockPos.containing(dryFeet)))return false;
        YukkuriEntity helper=null;
        if(!capable()){
            for(var rider:source.getPassengers())if(rider instanceof YukkuriEntity parent&&parent!=actor
                    &&parent.family().caresFor(actor)&&parent.isAlive()&&!parent.life().isSleeping()
                    &&parent.distanceTo(actor)<(parent.getBbWidth()+actor.getBbWidth())*.5+.12){helper=parent;break;}
            if(helper==null)return false;
        }
        var origin=actor.position();var points=new ArrayList<Vec3>();
        int samples=Math.max(1,(int)Math.ceil(origin.subtract(dryFeet).horizontalDistance()/.02));
        Vec3 previous=origin;
        for(int i=1;i<=samples;i++){
            var wanted=origin.lerp(dryFeet,(double)i/samples);
            var supported=shoreSupport(source,new Vec3(wanted.x,previous.y,wanted.z));
            if(supported==null||supported.y-previous.y>.08||previous.y-supported.y>.18
                    ||!sweepClear(actor,source,null,previous,supported))return false;
            points.add(supported);previous=supported;
        }
        if(previous.distanceToSqr(dryFeet)>.01)return false;
        // An infant is accepted only when the assisting parent's real contact covers the entire slide.
        if(helper!=null)for(var point:points)
            if(helper.position().distanceTo(point)>(helper.getBbWidth()+actor.getBbWidth())*.5+.18)return false;
        from=origin;to=dryFeet;sourceId=hatId=source.getUUID();seat=source.toLocal(origin);
        shorePath=points;shoreStep=0;shoreFallSpeed=0;shoreLanding=true;shoreHelper=helper==null?null:helper.getUUID();
        grip=crest=belly=null;gripSupportY=Double.NaN;dip=HatBoatEntity.Dip.NONE;stagedClimb=false;landing=releasedGrip=false;hump=0;
        boardingYaw=(float)(Math.toDegrees(Math.atan2(dryFeet.z-origin.z,dryFeet.x-origin.x))-90);
        bellyYaw=boardingYaw;totalTicks=0;lastVelocity=Vec3.ZERO;
        set(Phase.SETTLE,Math.max(MIN_SETTLE_TICKS,points.size()));source.holdPosition();
        actor.stopRiding();actor.setDeltaMovement(Vec3.ZERO);return true;
    }
    private Vec3 shoreSupport(HatBoatEntity source,Vec3 wanted){
        double support=Double.NEGATIVE_INFINITY;
        var local=source.toLocal(wanted);
        double floor=HatVessel.footprint(source.mode(),local.x,local.z,HatVessel.radius(actor.getBbWidth())/source.unit());
        if(Double.isFinite(floor))support=BraidRescueGeometry.poseClearance(actor,wanted,0,0,actor.yBodyRot,source,null);
        var hit=level().clip(new ClipContext(wanted.add(0,.09,0),wanted.add(0,-.20,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,actor));
        if(hit.getType()==HitResult.Type.BLOCK)support=Math.max(support,hit.getLocation().y);
        return Double.isFinite(support)&&support-wanted.y<=.09&&wanted.y-support<=.18?new Vec3(wanted.x,support,wanted.z):null;
    }
    private void tickLanding(){
        long now=level().getGameTime();if(lastTick==now)return;lastTick=now;
        var source=boat(sourceId);
        if(!stable(source)||!actor.isAlive()||actor.isPassenger()||actor.isCommandPlaying()||actor.operations().active()
                ||actor.isInWaterOrBubble()||++totalTicks>200){cancel();return;}
        source.holdPosition();actor.getNavigation().stop();
        if(shoreHelper!=null){
            var helper=level().getEntity(shoreHelper);
            if(!(helper instanceof YukkuriEntity parent)||!parent.isAlive()||parent.life().isSleeping()
                    ||parent.distanceTo(actor)>(parent.getBbWidth()+actor.getBbWidth())*.5+.18){cancel();return;}
            parent.getLookControl().setLookAt(actor,30,30);parent.getNavigation().stop();
        }
        actor.setYRot(Mth.approachDegrees(actor.getYRot(),boardingYaw,8));actor.yBodyRot=actor.getYRot();
        Vec3 wanted=shorePath.get(shoreStep);var support=shoreSupport(source,wanted);
        if(support==null||support.distanceToSqr(wanted)>.0004||!sweepClear(actor,source,null,actor.position(),wanted)){cancel();return;}
        var step=wanted.subtract(actor.position());
        if(step.y<-.035||shoreFallSpeed>0){
            // A normal bank is slightly below the floating brim. Once support ends,
            // gravity supplies this short descent; no upward impulse or remote landing is used.
            shoreFallSpeed=Math.min(.12,shoreFallSpeed+.025);
            double horizontal=step.horizontalDistance(),fraction=horizontal>.025?.025/horizontal:1;
            step=new Vec3(step.x*fraction,Math.max(step.y,-shoreFallSpeed),step.z*fraction);
        }else if(step.length()>.025)step=step.normalize().scale(.025);
        var expected=actor.position().add(step);
        if(!sweepClear(actor,source,null,actor.position(),expected)){cancel();return;}
        actor.move(MoverType.SELF,step);actor.setDeltaMovement(Vec3.ZERO);actor.hasImpulse=true;actor.fallDistance=0;lastVelocity=step;
        if(actor.position().distanceToSqr(expected)>.0001){cancel();return;}
        if(actor.position().distanceToSqr(wanted)>.0001)return;
        shoreFallSpeed=0;
        if(++shoreStep<shorePath.size())return;
        if(!actor.life().dryStand(actor.blockPosition())){cancel();return;}
        phase=Phase.IDLE;shoreLanding=false;sourceId=hatId=null;shorePath=List.of();shoreHelper=null;lastVelocity=Vec3.ZERO;
    }
    private boolean planClimb(HatBoatEntity source,HatBoatEntity hat,Vec3 origin,Vec3 destination,Vec3 actualGrip,HatBoatEntity.Dip dip){
        if(actualGrip==null||actor.getVariant()!=io.github.longshou.mcyukkuri.entity.YukkuriVariant.MARISA)return false;
        double scale=Math.min(1,actor.growthScale()*actor.getScale());
        var seatLocal=hat.toLocal(destination);
        var lowDestination=destination.subtract(0,drop(hat,dip,seatLocal),0);
        if(origin.subtract(destination).horizontalDistance()>.22+.7*scale||lowDestination.y-origin.y>.18+.55*scale)return false;
        var lip=hat.toWorld(new Vec3(0,HatVessel.top(hat.mode()),0));
        // The near rim is the one crossed, so it is the dipped rim's height that must be cleared.
        double lipY=lip.y-drop(hat,dip,hat.toLocal(actualGrip));
        double sourceSupport=Double.NaN;
        if(source!=null){
            var local=source.toLocal(origin);double floor=HatVessel.footprint(source.mode(),local.x,local.z,HatVessel.radius(actor.getBbWidth())/source.unit());
            if(!Double.isFinite(floor))return false;
            sourceSupport=source.toWorld(new Vec3(local.x,floor,local.z)).y;
            lipY=Math.max(lipY,source.toWorld(new Vec3(0,HatVessel.top(source.mode()),0)).y);
        }else if(actor.onGround()&&!actor.isInWaterOrBubble())sourceSupport=origin.y;
        // The rim wall the mouth may take: from the waterline up to the (lowered) lip.
        double rimWall=Math.max(.01*scale,actualGrip.y-hat.getY());
        double nudge=actor.isInWaterOrBubble()?.14:.05;
        var direction=actualGrip.subtract(origin);float toward=(float)(Math.toDegrees(Math.atan2(direction.z,direction.x))-90);
        for(float side:new float[]{0,25,-25,50,-50}){
            float yaw=toward+side;
            var mouthOffset=BraidRescueGeometry.mouthPoint(actor,Vec3.ZERO,boardRootPitch("GRIP",1),boardRootRoll("GRIP",1),yaw);
            // Where the feet must stand so the mouth is at the rim wall; the body is nudged there before the bite.
            double low=actualGrip.y-rimWall-mouthOffset.y,high=actualGrip.y-mouthOffset.y;
            var bite=new Vec3(actualGrip.x-mouthOffset.x,Math.clamp(origin.y,low,high),actualGrip.z-mouthOffset.z);
            if(bite.subtract(origin).length()>nudge)continue;
            var liftedFrom=gripPoint(actor,bite,sourceSupport,1,yaw);
            double drop=0;
            for(var shape:BraidRescueGeometry.boardingEnvelope(actor,bite,boardRootPitch("LIFT",1),boardRootRoll("LIFT",1),yaw))
                drop=Math.max(drop,bite.y-shape.minY);
            boolean biting=true;
            for(int i=0;i<=24&&biting;i++){
                double q=i/24.0;
                biting=climbSampleClear(actor,source,hat,gripPoint(actor,bite,sourceSupport,q,yaw),actualGrip,rimWall,"GRIP",q,yaw,dip);
            }
            if(!biting||!BraidRescueGeometry.bites(actor,BraidRescueGeometry.mouthPoint(actor,liftedFrom,boardRootPitch("GRIP",1),boardRootRoll("GRIP",1),yaw),actualGrip,rimWall))continue;
            double radians=Math.toRadians(yaw);var ahead=new Vec3(-Math.sin(radians),0,Math.cos(radians));
            // A low forward-up path: the crest clears the dipped lip by a hair, with at most a short reach inboard.
            double crestY=Math.max(Math.max(liftedFrom.y,lowDestination.y),lipY+drop+.008*scale);
            for(double reach:new double[]{0,.15*scale,.30*scale}){
                var candidateTop=new Vec3(bite.x+ahead.x*reach,crestY,bite.z+ahead.z*reach);
                boolean clear=true;
                for(int i=0;i<=32&&clear;i++){
                    double q=i/32.0;
                    clear=climbSampleClear(actor,source,hat,liftPoint(actor,source,hat,liftedFrom,candidateTop,q,yaw,dip),actualGrip,rimWall,"LIFT",q,yaw,dip);
                }
                if(!clear)continue;
                for(float turn:new float[]{0,45,-45,90,-90,180}){
                    float finalYaw=yaw+turn;
                    // The nearest inboard patch that fully carries the belly; small turns and short slides are tried first.
                    Vec3 supportedBelly=null;
                    for(double inward=0;inward<=.9*(.3+scale)&&supportedBelly==null;inward+=.015)
                        supportedBelly=settleSupport(actor,hat,new Vec3(candidateTop.x+ahead.x*inward,candidateTop.y,candidateTop.z+ahead.z*inward),BELLY_SUPPORT_PROGRESS,finalYaw,dip);
                    if(supportedBelly==null)continue;
                    clear=true;int samples=64+(int)Math.ceil(Math.abs(turn)/4);
                    for(int i=0;i<=samples&&clear;i++){
                        double q=(double)i/samples;float atYaw=settleYaw(yaw,finalYaw,q);
                        var at=slidePoint(actor,hat,candidateTop,supportedBelly,destination,q,yaw,finalYaw,dip);
                        clear=at!=null&&climbSampleClear(actor,source,hat,at,actualGrip,rimWall,"SETTLE",q,atYaw,dip);
                    }
                    var releaseAt=settleSupport(actor,hat,supportedBelly,GRIP_RELEASE_PROGRESS,finalYaw,dip);
                    clear=clear&&releaseAt!=null&&climbSampleClear(actor,source,hat,releaseAt,actualGrip,rimWall,"SETTLE",GRIP_RELEASE_PROGRESS,finalYaw,dip)
                            &&hasBellySupport(actor,hat,releaseAt,GRIP_RELEASE_PROGRESS,finalYaw,dip);
                    if(clear){
                        grip=actualGrip;crest=candidateTop;belly=supportedBelly;liftFrom=liftedFrom;gripSupportY=sourceSupport;biteFeet=bite;wall=rimWall;
                        boardingYaw=yaw;bellyYaw=finalYaw;
                        // Smoothstep's peak derivative is 1.5; cap the real turn at eight degrees per tick.
                        settleTicks=Math.max(MIN_SETTLE_TICKS,(int)Math.ceil(1.5*Math.abs(turn)/(.34*8)));
                        return true;
                    }
                }
            }
        }
        return false;
    }
    private static boolean climbSampleClear(YukkuriEntity actor,HatBoatEntity source,HatBoatEntity hat,Vec3 feet,Vec3 grip,double wall,String phase,double q,float yaw,HatBoatEntity.Dip dip){
        double pitch=boardRootPitch(phase,q),roll=boardRootRoll(phase,q);
        boolean teeth=phase.equals("GRIP")||phase.equals("LIFT");
        return (!teeth||BraidRescueGeometry.holds(actor,BraidRescueGeometry.mouthPoint(actor,feet,pitch,roll,yaw),grip,wall))
                &&clearAt(actor,source,hat,feet,pitch,roll,yaw,dip);
    }
    /** Keep the real underside on the measured brim while the supported body slides over. */
    public static Vec3 settleSupport(YukkuriEntity actor,HatBoatEntity hat,Vec3 wanted,double q,float yaw,HatBoatEntity.Dip dip){
        var local=hat.toLocal(wanted);
        double radius=HatVessel.radius(actor.getBbWidth())/hat.unit();
        double floor=HatVessel.footprint(hat.mode(),local.x,local.z,radius);
        if(!Double.isFinite(floor))return null;
        // footprint() alone returns the highest *partial* overlap. Releasing needs
        // an actual supported belly patch, including its centre and whole small support disc.
        for(int i=0;i<=8;i++){
            double x=local.x+(i==8?0:radius*Math.cos(i*Math.PI/4)),z=local.z+(i==8?0:radius*Math.sin(i*Math.PI/4));
            if(!Double.isFinite(HatVessel.surface(hat.mode(),x,z)))return null;
        }
        var body=BraidRescueGeometry.boardingEnvelope(actor,wanted,boardRootPitch("SETTLE",q),boardRootRoll("SETTLE",q),yaw).getFirst();
        double support=hat.toWorld(new Vec3(local.x,floor,local.z)).y-drop(hat,dip,local);
        return new Vec3(wanted.x,support-(body.minY-wanted.y),wanted.z);
    }
    private static boolean hasBellySupport(YukkuriEntity actor,HatBoatEntity hat,Vec3 feet,double q,float yaw,HatBoatEntity.Dip dip){
        var supported=settleSupport(actor,hat,feet,q,yaw,dip);
        return supported!=null&&Math.abs(supported.y-feet.y)<=.002*actor.growthScale()*actor.getScale();
    }
    private void set(Phase next,int ticks){phase=next;duration=ticks;start=level().getGameTime();}
    private void hold(HatBoatEntity source,HatBoatEntity hat){
        actor.getNavigation().stop();hat.holdPosition();
        // Only actual feet on the departure hat can brace it; after lifting off it is loose in the current.
        if(source!=null&&(phase==Phase.ALIGN||phase==Phase.GRIP))source.holdPosition();
    }
    /** The visitor's weight keeps the near rim down until the belly has support; then the hull levels again. */
    private void leanHull(HatBoatEntity hat){
        if(dip.degrees()<=0)return;
        if(landing||phase==Phase.SETTLE&&releasedGrip)hat.releaseDip(10);
        else hat.dipToward(actor.position(),dip.degrees(),14);
    }
    public void tick(){
        if(actor.level().isClientSide)return;
        if(abandonedHat!=null)for(var loaded:level().getServer().getAllLevels()){
            if(loaded.getEntity(abandonedHat) instanceof HatBoatEntity old){
                old.cancelIncoming(actor);
                if(actor.getUUID().equals(old.ownerYukkuri()))old.releaseOwnBoardingSeat(actor);
                abandonedHat=null;break;
            }
        }
        if(!active())return;
        if(shoreLanding){tickLanding();return;}
        long now=level().getGameTime();if(lastTick==now)return;lastTick=now;
        previousBodyYaw=actor.yBodyRot;
        double previousQ=Mth.clamp((double)(now-1-start)/Math.max(1,duration),0,1);
        previousPitch=boardRootPitch(phase.name(),previousQ);previousRoll=boardRootRoll(phase.name(),previousQ);previousSquash=boardRootSquash(phase.name(),previousQ);
        var hat=boat(hatId);var source=boat(sourceId);
        if(!capable()||!stable(hat)||sourceId!=null&&(phase==Phase.ALIGN||phase==Phase.GRIP)&&!stable(source)||++totalTicks>Math.max(220,settleTicks+120)
                ||hat.toWorld(seat).distanceToSqr(to)>.0004){cancel();return;}
        if(!actor.getUUID().equals(hat.ownerYukkuri())){
            var reserved=hat.peekSeat(actor);
            if(reserved==null||reserved.distanceToSqr(seat)>.000001){cancel();return;}
        }
        hold(source,hat);leanHull(hat);int elapsed=(int)(now-start);
        if(phase==Phase.ALIGN){
            if(source!=null&&(actor.getVehicle()!=source||source.changingSeat(actor))
                    ||source==null&&!waterAlign&&(!actor.onGround()||actor.isInWaterOrBubble())
                    ||waterAlign&&!actor.isInWaterOrBubble()
                    ||actor.position().distanceToSqr(from)>(waterAlign?.0256:.0036)){cancel();return;}
            var delta=to.subtract(actor.position());float yaw=(float)(Math.toDegrees(Math.atan2(delta.z,delta.x))-90);
            if(stagedClimb)yaw=boardingYaw;
            actor.setYRot(Mth.approachDegrees(actor.getYRot(),yaw,12));actor.yBodyRot=actor.getYRot();actor.setYHeadRot(actor.getYRot());
            // Close the last gap to the bite spot a little at a time, never through a block.
            boolean placed=true;
            if(stagedClimb&&biteFeet!=null){
                var gap=biteFeet.subtract(actor.position());double length=gap.length();
                if(length>.01){
                    placed=false;var step=gap.scale(Math.min(1,.02/length));
                    if(!level().getBlockCollisions(actor,actor.getBoundingBox().move(step)).iterator().hasNext()){
                        actor.move(MoverType.SELF,step);actor.setDeltaMovement(Vec3.ZERO);actor.hasImpulse=true;
                    }
                }
            }
            boolean still=hat.getDeltaMovement().horizontalDistanceSqr()<.000225&&Math.abs(Mth.wrapDegrees(hat.getYRot()-lastHatYaw))<2
                    &&(source==null||source.getDeltaMovement().horizontalDistanceSqr()<.000225&&Math.abs(Mth.wrapDegrees(source.getYRot()-lastSourceYaw))<2);
            boolean leaned=dip.degrees()<=0||hat.dipDegrees(0)>=dip.degrees()-.35f;
            lastHatYaw=hat.getYRot();lastSourceYaw=source==null?0:source.getYRot();
            steadyTicks=still&&placed&&leaned&&Math.abs(Mth.wrapDegrees(yaw-actor.getYRot()))<12?steadyTicks+1:0;
            if(steadyTicks<6)return;
            from=actor.position();
            if(stagedClimb){
                if(!planClimb(source,hat,from,to,hat.boardingGrip(actor),null)){cancel();return;}
            }else{
                hopTo=to.subtract(0,hat.rimLift(seat),0);hump=hopHeight(actor,from,hopTo);
                if((hopTicks=hopDuration(actor,source,hat,from,hopTo,null))==0){cancel();return;}
            }
            if(!actor.life().spendEnergy(.25f)){cancel();return;}
            if(source!=null)actor.stopRiding();actor.setDeltaMovement(Vec3.ZERO);
            if(stagedClimb){actor.setYRot(boardingYaw);actor.yBodyRot=boardingYaw;alignedFrom=from;biteTicks=0;}
            set(stagedClimb?Phase.GRIP:Phase.HOP,stagedClimb?GRIP_TICKS:hopTicks);return;
        }
        if(actor.isPassenger()){cancel();return;}
        double q=Mth.clamp((double)elapsed/duration,0,1);
        float activeYaw=phase==Phase.SETTLE?settleYaw(boardingYaw,bellyYaw,q):boardingYaw;
        if(climbing()){
            actor.setYRot(Mth.approachDegrees(actor.getYRot(),activeYaw,8));actor.yBodyRot=actor.getYRot();
            activeYaw=actor.yBodyRot;
        }
        Vec3 at=phase==Phase.HOP?(landing?to.subtract(0,hat.rimLift(seat),0):hopPoint(from,hopTo,hopTicks,Math.min(elapsed,hopTicks),hump))
                :phase==Phase.GRIP?gripPoint(actor,alignedFrom.lerp(biteFeet,smooth(elapsed/4.0)),gripSupportY,q,boardingYaw)
                :phase==Phase.LIFT?liftPoint(actor,source,hat,liftFrom,crest,q,boardingYaw,null)
                :slidePoint(actor,hat,crest,belly,to,q,boardingYaw,bellyYaw,null);
        if(at==null){cancel();return;}
        double pitch=boardRootPitch(phase.name(),q),roll=boardRootRoll(phase.name(),q);
        boolean releasing=phase==Phase.SETTLE&&!releasedGrip&&q>=GRIP_RELEASE_PROGRESS;
        if(releasing&&!hasBellySupport(actor,hat,actor.position(),Math.max(BELLY_SUPPORT_PROGRESS,q-1.0/duration),previousBodyYaw,null)){cancel();return;}
        if(gripped()){
            var mouth=BraidRescueGeometry.mouthPoint(actor,at,pitch,roll,activeYaw);
            if(phase==Phase.GRIP)biteTicks=BraidRescueGeometry.bites(actor,mouth,grip,wall)?biteTicks+1:0;
            if(!actor.life().spendEnergy(.025f)||!BraidRescueGeometry.holds(actor,mouth,grip,wall)){cancel();return;}
        }
        var delta=at.subtract(actor.position());
        if(delta.lengthSqr()>.45*.45||!sweepClear(actor,source,hat,actor.position(),at)
                ||stagedClimb&&!clearAt(actor,source,hat,at,pitch,roll,actor.yBodyRot,null)){cancel();return;}
        // A bounded physical boarding trajectory replaces only the two hollow hats' coarse AABBs.
        // Its endpoint is fixed at takeoff; a moving boat cannot pull a flying child after itself.
        actor.move(MoverType.SELF,delta);actor.setDeltaMovement(Vec3.ZERO);actor.hasImpulse=true;
        if(actor.position().distanceToSqr(at)>.0004){cancel();return;}
        actor.fallDistance=0;lastVelocity=delta;
        if(releasing){
            if(!hasBellySupport(actor,hat,actor.position(),q,activeYaw,null)){cancel();return;}
            releasedGrip=true;hat.releaseDip(12);
        }
        if(elapsed<duration)return;
        if(phase==Phase.GRIP){if(biteTicks>=BITE_TICKS)set(Phase.LIFT,LIFT_TICKS);return;}
        if(phase==Phase.LIFT){set(Phase.SETTLE,settleTicks);return;}
        // Landed: the hull must be level again so the seat the hat gives is the one the body already rests on.
        if(hat.rimLift(seat)>.003*Math.max(.25,Math.min(1,actor.growthScale()*actor.getScale()))){landing=true;hat.releaseDip(8);return;}
        boolean landed=actor.getUUID().equals(hat.ownerYukkuri())?hat.board(actor):hat.boardYoung(actor);
        if(!landed){cancel();return;}
        phase=Phase.IDLE;hat.cancelIncoming(actor);hat.releaseDip(12);
        if(actor.getUUID().equals(hat.ownerYukkuri()))hat.releaseOwnBoardingSeat(actor);
        hatId=sourceId=null;lastVelocity=Vec3.ZERO;
    }
    public static boolean sweepClear(YukkuriEntity actor,HatBoatEntity source,HatBoatEntity hat,Vec3 from,Vec3 to){return sweepClear(actor,source,hat,from,to,null);}
    private static boolean sweepClear(YukkuriEntity actor,HatBoatEntity source,HatBoatEntity hat,Vec3 from,Vec3 to,HatBoatEntity.Dip dip){
        int steps=Math.max(1,(int)Math.ceil(from.distanceTo(to)/.025));
        for(int i=1;i<=steps;i++)if(!clearAt(actor,source,hat,from.lerp(to,(double)i/steps),0,0,actor.yBodyRot,dip))return false;
        return true;
    }
    private static boolean clearAt(YukkuriEntity actor,HatBoatEntity source,HatBoatEntity hat,Vec3 feet,double pitch,double roll,float yaw,HatBoatEntity.Dip dip){
        var level=actor.level();
        var boxes=BraidRescueGeometry.boardingEnvelope(actor,feet,pitch,roll,yaw);
        if(!level.getWorldBorder().isWithinBounds(BlockPos.containing(feet)))return false;
        for(var shape:boxes){
            var box=shape.deflate(.002);
            if(level.getBlockCollisions(actor,box).iterator().hasNext())return false;
            for(var other:level.getEntities(actor,box)){
                if(other==source||other==hat||!other.isAlive()||other.isSpectator())continue;
                if(other instanceof YukkuriEntity y){
                    for(var part:BraidRescueGeometry.envelope(y,y.position()))if(box.intersects(part))return false;
                }else if(other.canBeCollidedWith()&&box.intersects(other.getBoundingBox()))return false;
            }
        }
        for(var vessel:new HatBoatEntity[]{source,hat})if(vessel!=null){
            if(BraidRescueGeometry.poseClearance(actor,feet,pitch,roll,yaw,vessel,vessel==hat?dip:null)>feet.y+.002)return false;
        }
        return true;
    }
    public void cancel(){
        if(!active())return;
        var hat=boat(hatId);boolean airborne=phase!=Phase.ALIGN;
        if(hat!=null){hat.cancelIncoming(actor);hat.releaseDip(14);if(actor.getUUID().equals(hat.ownerYukkuri()))hat.releaseOwnBoardingSeat(actor);}
        else abandonedHat=hatId;
        phase=Phase.IDLE;hatId=sourceId=null;shoreLanding=false;shorePath=List.of();shoreHelper=null;
        if(airborne&&!actor.isPassenger()){
            actor.setDeltaMovement(lastVelocity.x,Math.min(-.02,lastVelocity.y),lastVelocity.z);actor.hasImpulse=true;
        }
    }
    public CompoundTag state(){
        var tag=new CompoundTag();if(!active())return tag;
        var hat=boat(hatId);if(hat==null)return tag;
        tag.putBoolean("ShoreLanding",shoreLanding);if(shoreHelper!=null)tag.putUUID("HelpingParent",shoreHelper);
        tag.putBoolean("Active",true);tag.putString("Phase",phase.name());tag.putLong("Start",start);tag.putInt("Duration",duration);
        tag.putInt("HatId",hat.getId());tag.putUUID("Hat",hatId);
        var source=boat(sourceId);if(source!=null){tag.putInt("SourceHatId",source.getId());tag.putUUID("SourceHat",source.getUUID());}
        double q=Mth.clamp((double)(level().getGameTime()-start)/Math.max(1,duration),0,1);
        double overDistance=crest==null||belly==null?0:crest.distanceTo(belly),inDistance=belly==null||to==null?0:belly.distanceTo(to);
        double settling=(overDistance*smooth((q-.08)/.34)+inDistance*smooth((q-.58)/.34))/Math.max(.0001,overDistance+inDistance);
        tag.putDouble("Progress",phase==Phase.LIFT?pullProgress(q):phase==Phase.SETTLE?settling:q);
        tag.putDouble("Effort",phase==Phase.LIFT?pullEffort(q):phase==Phase.SETTLE?Math.max(pulse((q-.08)/.34),pulse((q-.58)/.34)):0);
        tag.putDouble("Brace",phase==Phase.GRIP?smooth(q):phase==Phase.LIFT?1-pullProgress(q):phase==Phase.SETTLE?1-smooth((q-.4)/.6):0);
        tag.putDouble("Hump",hump);
        if(Double.isFinite(gripSupportY))tag.putDouble("SourceSupportY",gripSupportY);
        tag.putDouble("ReleaseProgress",GRIP_RELEASE_PROGRESS);tag.putDouble("BellySupportProgress",BELLY_SUPPORT_PROGRESS);
        double pitch=shoreLanding?0:boardRootPitch(phase.name(),q),roll=shoreLanding?0:boardRootRoll(phase.name(),q),squash=shoreLanding?1:boardRootSquash(phase.name(),q);
        tag.putDouble("RootPitch",pitch);tag.putDouble("RootRoll",roll);
        // Height squash of the root (1 = none); the renderer stretches and presses instead of leaning further.
        tag.putFloat("RootSquash",(float)squash);tag.putFloat("PrevRootSquash",(float)previousSquash);
        tag.putFloat("BodyYaw",actor.yBodyRot);tag.putFloat("PrevBodyYaw",previousBodyYaw);
        tag.putFloat("FromYaw",boardingYaw);tag.putFloat("BellyYaw",bellyYaw);
        tag.putDouble("PrevRootPitch",previousPitch);tag.putDouble("PrevRootRoll",previousRoll);
        tag.putBoolean("Gripped",gripped());tag.putBoolean("Bite",phase==Phase.GRIP&&biteTicks>=BITE_TICKS||phase==Phase.LIFT);
        tag.putInt("BiteTicks",biteTicks);tag.putBoolean("Landing",landing);tag.putFloat("Dip",dip.degrees());
        if(gripped()&&grip!=null){
            // The mouth, on the actual rim point; the braid only keeps its balance pose.
            put(tag,"Mouth",BraidRescueGeometry.mouthPoint(actor,actor.position(),pitch,roll,actor.yBodyRot));put(tag,"Rim",grip);
        }
        put(tag,"From",phase==Phase.SETTLE?crest:phase==Phase.LIFT?liftFrom:from);put(tag,"To",phase==Phase.LIFT?crest:to);put(tag,"Grip",grip);
        put(tag,"Crest",crest);put(tag,"Belly",belly);return tag;
    }
    public CompoundTag save(){var tag=new CompoundTag();if(hatId!=null)tag.putUUID("ReservedHat",hatId);else if(abandonedHat!=null)tag.putUUID("ReservedHat",abandonedHat);return tag;}
    public void load(CompoundTag tag){
        // An unloaded child resumes at its actual saved body position, never a replayed remote jump.
        phase=Phase.IDLE;hatId=sourceId=null;shoreLanding=false;shorePath=List.of();shoreHelper=null;abandonedHat=tag.hasUUID("ReservedHat")?tag.getUUID("ReservedHat"):null;
        lastTick=Long.MIN_VALUE;lastVelocity=Vec3.ZERO;
    }
    private static void put(CompoundTag tag,String key,Vec3 point){if(point!=null){tag.putDouble(key+"X",point.x);tag.putDouble(key+"Y",point.y);tag.putDouble(key+"Z",point.z);}}
}
