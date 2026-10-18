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
    /** A short kinematic step/hop between two supports (a hull deck, the bank, another hull): see startStep. */
    private boolean stepping;
    private Vec3 stepFrom,stepTo;
    private UUID stepHat;
    private Vec3 stepSeat;
    private int stepTicks;
    private double stepHump;
    private List<Vec3> stepDownPath=List.of();
    private List<Float> stepDownYaws=List.of();
    private String lastPlan="none";
    private String lastStepPlan="none";
    private String lastCancel="none";
    private String lastClimbPlan="none",lastClimbSample="none";
    /** Per-candidate first refusal of the last climb plan; only built while /yukkuri aqdiag is on. */
    private StringBuilder climbTrace;
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
    public String diagnostic(){return phase.name()+": "+lastPlan+" step="+lastStepPlan+" climb="+lastClimbPlan+" cancel="+lastCancel
            +(actor.getVehicle() instanceof HatBoatEntity riding?" departure="+riding.departureDebug():"");}
    /** Only these two hollow hulls use the measured rim geometry instead of their coarse entity box. */
    public boolean traverses(HatBoatEntity hat){return active()&&hat!=null&&(hat.getUUID().equals(hatId)||hat.getUUID().equals(sourceId));}
    public boolean landingAshore(){return active()&&shoreLanding;}
    /** Jumping capacity of this body: infants are lifted by their parent's braid instead. */
    public boolean canStep(){return capable();}
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
        case "GRIP"->4*smooth(q);
        // Keep the supported bite pose while pulling upward; lean over only after clearing the lip.
        // Starting LIFT at -4 would rotate the lower body through the wall before it has risen.
        case "LIFT"->4-12*smooth((pullProgress(q)-.9)/.1);
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
        return slidePoint(actor,hat,crest,belly,to,q,fromYaw,toYaw,dip,0);
    }
    /** {@code margin} (blocks) widens the required belly disc: a plan keeps clear of the outline's very edge so the
     *  executed slide still rests on the hull after the body has grown a hair or the hull has eased a little. */
    private static Vec3 slidePoint(YukkuriEntity actor,HatBoatEntity hat,Vec3 crest,Vec3 belly,Vec3 to,double q,float fromYaw,float toYaw,HatBoatEntity.Dip dip,double margin){
        float yaw=settleYaw(fromYaw,toYaw,q);
        if(q>=BELLY_SUPPORT_PROGRESS)return settleSupport(actor,hat,settlePoint(crest,belly,to,q),q,yaw,dip,margin);
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
    /** A seat can fit the small hat yet leave the body inside its larger departure hull. */
    public boolean landingClear(HatBoatEntity source,HatBoatEntity target,Vec3 localSeat){
        var at=target.toWorld(localSeat);var delta=at.subtract(actor.position());
        float yaw=(float)(Math.toDegrees(Math.atan2(delta.z,delta.x))-90);
        // Mounting an owned hat turns its captain to the hull heading. Reserve enough room for
        // that actual supported turn, rather than repeatedly choosing a closer unusable patch.
        return turnClear(actor,source,target,at,yaw,target.getYRot())
                ||turnClear(actor,source,target,at,actor.yBodyRot,target.getYRot());
    }
    /** Walk over touching support, then let gravity lower the body onto the smaller hat.
     *  A down-step is not a scaled jump: the large hat's freeboard does not shrink with its child.
     *  Every horizontal and vertical segment still clears both complete hulls and all other bodies. */
    private List<Vec3> planStepDown(HatBoatEntity source,HatBoatEntity target,Vec3 origin,Vec3 destination,float yaw){
        var direct=planStepDown(source,target,origin,destination,yaw,null);
        if(!direct.isEmpty())return direct;
        // Leave the crowded brim along an exact axis before crossing to the landing patch.
        // Keep one gravity integration: a corner is a horizontal waypoint, not a suspended seat.
        for(var corner:List.of(new Vec3(origin.x,destination.y,destination.z),new Vec3(destination.x,destination.y,origin.z))){
            if(corner.subtract(origin).horizontalDistance()<1e-6||corner.subtract(destination).horizontalDistance()<1e-6)continue;
            var path=planStepDown(source,target,origin,destination,yaw,corner);
            if(!path.isEmpty())return path;
        }
        return List.of();
    }
    private List<Vec3> planStepDown(HatBoatEntity source,HatBoatEntity target,Vec3 origin,Vec3 destination,float yaw,Vec3 corner){
        double fall=origin.y-destination.y;
        if(source==null||!stable(source)||!stable(target)||!target.ownsPhysicalHat(actor))return refuseStep("unstable or unowned hull");
        if(fall<=0||fall>actor.getBbHeight())return refuseStep("drop="+fall+" bodyHeight="+actor.getBbHeight());
        if(Math.abs(source.getY()-target.getY())>.01)return refuseStep("different water levels");
        if(origin.subtract(destination).horizontalDistance()>maximumGap(actor))return refuseStep("gap exceeds "+maximumGap(actor));
        double speed=.012+.09*Math.min(1,actor.growthScale()*actor.getScale());
        var points=new ArrayList<Vec3>();var previous=origin;double fallSpeed=0;boolean passedCorner=corner==null;
        for(int tick=0;tick<32;tick++){
            if(!passedCorner&&corner.subtract(previous).horizontalDistance()<1e-6)passedCorner=true;
            var delta=(passedCorner?destination:corner).subtract(previous);double distance=delta.horizontalDistance();
            double fraction=distance>speed?speed/distance:1;
            var across=new Vec3(previous.x+delta.x*fraction,previous.y,previous.z+delta.z*fraction);
            if(!sweepClear(actor,source,target,previous,across,HatBoatEntity.Dip.NONE,yaw))
                return blockedStep("horizontal",tick,source,target,previous,across,yaw);
            double support=Math.max(stepSupport(source,across,yaw),stepSupport(target,across,yaw));
            // No unsupported water gap or upward pull; the existing hop/climb handles those cases.
            if(!Double.isFinite(support)||support>previous.y+.002)
                return refuseStep("support tick="+tick+" at="+across+" floor="+support+" yaw="+yaw);
            points.add(across);
            if(support<previous.y-1e-6){
                fallSpeed=Math.min(.12,fallSpeed+.025);
                var below=new Vec3(across.x,Math.max(Math.max(destination.y,support),previous.y-fallSpeed),across.z);
                if(!sweepClear(actor,source,target,across,below,HatBoatEntity.Dip.NONE,yaw))
                    return blockedStep("vertical",tick,source,target,across,below,yaw);
                points.add(below);previous=below;
            }else{fallSpeed=0;previous=across;}
            if(previous.distanceToSqr(destination)<1e-10){lastStepPlan="clear points="+points.size()+" yaw="+yaw;return List.copyOf(points);}
        }
        return refuseStep("32-step limit last="+previous+" target="+destination+" yaw="+yaw);
    }
    private List<Vec3> refuseStep(String reason){lastStepPlan=reason;return List.of();}
    /** Keep a supported side-step at its real heading when turning among siblings is obstructed. */
    private List<Vec3> planSupportedStep(HatBoatEntity source,HatBoatEntity target,Vec3 origin,Vec3 destination,float toward){
        stepDownYaws=List.of();
        for(float yaw:new float[]{toward,actor.yBodyRot}){
            if(!turnClear(actor,source,target,origin,actor.yBodyRot,yaw)){lastStepPlan="departure turn blocked yaw="+yaw;continue;}
            var path=planStepDown(source,target,origin,destination,yaw);if(path.isEmpty())continue;
            float endYaw=target.getYRot();
            if(!turnClear(actor,source,target,destination,yaw,endYaw)){lastStepPlan="landing turn blocked yaw="+yaw+" to="+endYaw+" at="+destination;continue;}
            var points=new ArrayList<>(path);var headings=new ArrayList<Float>();
            for(int i=0;i<path.size();i++)headings.add(yaw);
            float turn=Mth.wrapDegrees(endYaw-yaw);int ticks=(int)Math.ceil(Math.abs(turn)/8);
            for(int i=1;i<=ticks;i++){points.add(destination);headings.add(yaw+turn*i/ticks);}
            boardingYaw=yaw;stepDownYaws=List.copyOf(headings);
            lastStepPlan="clear points="+points.size()+" yaw="+yaw+" landingYaw="+endYaw;
            return List.copyOf(points);
        }
        return List.of();
    }
    private boolean turnClear(YukkuriEntity actor,HatBoatEntity source,HatBoatEntity target,Vec3 feet,float from,float to){
        float turn=Mth.wrapDegrees(to-from);int samples=Math.max(1,(int)Math.ceil(Math.abs(turn)/2));
        for(int i=0;i<=samples;i++){
            float yaw=from+turn*i/samples;
            if(!clearAt(actor,source,target,feet,0,0,yaw,HatBoatEntity.Dip.NONE))return false;
            if(Math.abs(turn)>.001){
                double support=Math.max(source==null?Double.NEGATIVE_INFINITY:stepSupport(source,feet,yaw),stepSupport(target,feet,yaw));
                if(!Double.isFinite(support)||Math.abs(support-feet.y)>.002)return false;
            }
        }
        return true;
    }
    private List<Vec3> blockedStep(String segment,int tick,HatBoatEntity source,HatBoatEntity target,Vec3 from,Vec3 to,float yaw){
        int steps=Math.max(1,(int)Math.ceil(from.distanceTo(to)/.025));
        for(int i=1;i<=steps;i++){
            var at=from.lerp(to,(double)i/steps);
            if(!clearAt(actor,source,target,at,0,0,yaw,HatBoatEntity.Dip.NONE))
                return refuseStep(segment+" tick="+tick+" sample="+i+"/"+steps+" at="+at+" yaw="+yaw
                        +" sourceFloor="+stepSupport(source,at,yaw)+" targetFloor="+stepSupport(target,at,yaw)
                        +" rider="+blockingRider(source,target,at,yaw));
        }
        return refuseStep(segment+" changed during planning tick="+tick);
    }
    private String blockingRider(HatBoatEntity source,HatBoatEntity target,Vec3 feet,float yaw){
        for(var boat:new HatBoatEntity[]{source,target})if(boat!=null)for(var passenger:boat.getPassengers())
            if(passenger instanceof YukkuriEntity other&&other!=actor)
                for(var body:BraidRescueGeometry.boardingEnvelope(actor,feet,0,0,yaw))
                    for(var part:BraidRescueGeometry.envelope(other,other.position()))
                        if(body.deflate(.002).intersects(part))return other.getUUID()+" at="+other.position()+" yaw="+other.yBodyRot;
        return "none";
    }
    private double stepSupport(HatBoatEntity hat,Vec3 feet,float yaw){
        // poseClearance never lowers its argument; probe below the surfaces to obtain their actual height.
        // A turned body's rear corner can remain on the high hull after its smaller soft contact circle
        // has left it. Use the same whole posed footprint as sweepClear before allowing any descent.
        var probe=feet.subtract(0,1,0);
        double support=BraidRescueGeometry.poseClearance(actor,probe,0,0,yaw,hat,HatBoatEntity.Dip.NONE);
        return support>probe.y+1e-8?support:Double.NEGATIVE_INFINITY;
    }
    public boolean begin(HatBoatEntity source,HatBoatEntity hat,Vec3 localSeat){
        if(active())return hat.getUUID().equals(hatId);
        lastPlan="preconditions";
        lastStepPlan="not requested";
        if(!capable()||!stable(hat)||actor.isInWaterOrBubble()||localSeat==null
                ||source!=null&&(!stable(source)||actor.getVehicle()!=source||!source.protects(actor)||source.changingSeat(actor))
                ||source==null&&(actor.isPassenger()||!actor.onGround()||!actor.life().dryStand(actor.blockPosition())))return false;
        var origin=actor.position();var destination=hat.toWorld(localSeat);
        var plan=dippable(hat)?hat.planDip(origin,HatBoatEntity.MAX_DIP):HatBoatEntity.Dip.NONE;
        // At a reachable rim the mouth takes hold first; only a genuinely low step may bypass the climb.
        var actualGrip=hat.boardingGrip(actor);
        boolean climbing=false;
        // A child already supported by another hat may have its mouth above a maximally dipped lip.
        // Try the currently level wall before lowering it; every candidate uses the same contact/sweep solver.
        if(source!=null&&plan.degrees()>0&&hat.dipDegrees(0)<=.01f){
            climbing=planClimb(source,hat,origin,destination,actualGrip,HatBoatEntity.Dip.NONE);
            if(climbing)plan=HatBoatEntity.Dip.NONE;
        }
        if(!climbing)climbing=planClimb(source,hat,origin,destination,assumedGrip(hat,actualGrip,plan),plan);
        var landingSpot=destination.subtract(0,HatBoatEntity.rimLift(localSeat,plan,hat.unit()),0);
        int ticks=climbing?0:hopDuration(actor,source,hat,origin,landingSpot,plan);
        var direction=destination.subtract(origin);
        float approachYaw=(float)(Math.toDegrees(Math.atan2(direction.z,direction.x))-90);
        stepDownPath=!climbing&&ticks==0?planSupportedStep(source,hat,origin,destination,approachYaw):List.of();
        lastPlan="seat="+localSeat+" gap="+origin.subtract(destination).horizontalDistance()+" drop="+(origin.y-destination.y)
                +" climb="+climbing+" hop="+ticks+" stepDown="+stepDownPath.size();
        if(!climbing&&ticks==0&&stepDownPath.isEmpty())return false;
        if(!stepDownPath.isEmpty()){plan=HatBoatEntity.Dip.NONE;landingSpot=destination;ticks=stepDownPath.size();}
        sourceId=source==null?null:source.getUUID();hatId=hat.getUUID();seat=localSeat;dip=plan;
        from=origin;to=destination;stagedClimb=climbing;waterAlign=false;releasedGrip=landing=false;biteTicks=0;if(!climbing)grip=null;
        hopTo=landingSpot;hump=stepDownPath.isEmpty()?hopHeight(actor,origin,landingSpot):0;hopTicks=ticks;steadyTicks=totalTicks=0;
        lastHatYaw=hat.getYRot();lastSourceYaw=source==null?0:source.getYRot();
        lastVelocity=Vec3.ZERO;lastCancel="none";set(Phase.ALIGN,6);hold(source,hat);return true;
    }
    public boolean climb(HatBoatEntity hat,Vec3 localSeat,Vec3 actualGrip){
        if(active())return climbing()&&hat.getUUID().equals(hatId);
        if(!capable()||!stable(hat)||actor.isPassenger()||!actor.isInWaterOrBubble()||localSeat==null||actualGrip==null)return false;
        var destination=hat.toWorld(localSeat);var origin=actor.position();
        // The climber's own weight on the near rim is what leans the hull; the planner assumes exactly that lean.
        var plan=dippable(hat)?hat.planDip(origin,HatBoatEntity.MAX_DIP):HatBoatEntity.Dip.NONE;
        if(!planClimb(null,hat,origin,destination,assumedGrip(hat,actualGrip,plan),plan)){
            localSeat=supportedOwnSeat(hat,origin,localSeat,assumedGrip(hat,actualGrip,plan),plan);
            if(localSeat==null)return false;
            destination=hat.toWorld(localSeat);
        }
        if(!actor.life().spendEnergy(.5f))return false;
        sourceId=null;hatId=hat.getUUID();seat=localSeat;dip=plan;from=origin;to=destination;stagedClimb=true;hump=0;releasedGrip=landing=false;biteTicks=0;
        steadyTicks=totalTicks=0;lastVelocity=Vec3.ZERO;waterAlign=true;set(Phase.ALIGN,6);hat.holdPosition();return true;
    }
    /** The owner's nearest free patch may overhang the hull's outline (a seat only needs its centre and walking square on the hat),
     *  where the sliding belly could never come to rest. Try the nearest few patches whose whole belly disc the hat carries;
     *  the same climb planner must accept one, and the hull keeps it reserved. Null (nothing reserved) when none works. */
    private Vec3 supportedOwnSeat(HatBoatEntity hat,Vec3 origin,Vec3 refused,Vec3 grip,HatBoatEntity.Dip plan){
        if(!actor.getUUID().equals(hat.ownerYukkuri()))return null;
        var tried=new ArrayList<Vec3>();tried.add(refused);
        for(int attempt=0;attempt<4;attempt++){
            var next=hat.reserveOwnBoardingSeat(actor,origin,at->tried.stream().noneMatch(t->t.distanceToSqr(at)<1e-8)
                    &&settleSupport(actor,hat,hat.toWorld(at),1,actor.yBodyRot,HatBoatEntity.Dip.NONE,planMargin(actor))!=null);
            if(next==null)break;
            tried.add(next);
            if(planClimb(null,hat,origin,hat.toWorld(next),grip,plan)){
                io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(actor,"climb.seat."+actor.getId(),0,"seat "+refused+" overhangs; climbing to "+next+" after "+attempt+" retries");
                return next;
            }
        }
        hat.releaseOwnBoardingSeat(actor);
        return null;
    }
    /** A supported slide onto a touching low bank. A remote/high bank is never a dismount target. */
    public boolean startLanding(HatBoatEntity source,Vec3 dryFeet){
        return source!=null&&dryFeet!=null&&startStep(source,null,null,dryFeet);
    }
    /** Maximum horizontal reach of one hop for this body. */
    public static double hopReach(YukkuriEntity actor){
        double size=Math.min(1,actor.growthScale()*actor.getScale());
        return .5*size+1.5*actor.getBbWidth();
    }
    /**
     * One short arc between two supports, from the present feet to dry ground ({@code dryFeet}) or to a reserved seat of a
     * hull ({@code targetHat}, {@code seatLocal}). The tolerance is the body's own reach, not an exact stopping point: any
     * start within reach whose arc is free of blocks succeeds, from a deck, from a bank, or between two floating hulls.
     * The rider moves along a parabola at hop speed, never faster, and is placed exactly on the destination at the end.
     */
    public boolean startStep(HatBoatEntity source,HatBoatEntity targetHat,Vec3 seatLocal,Vec3 dryFeet){
        if(active())return stepping&&(targetHat!=null?targetHat.getUUID().equals(stepHat):source!=null&&source.getUUID().equals(sourceId));
        if(actor.level().isClientSide)return false;
        String why=null;
        if(!actor.isAlive()||actor.isNoAi()||actor.isCommandPlaying()||actor.operations().active()||actor.family().braidRescueActive())why="busy";
        else if(!capable())why="cannot jump";
        else if(source!=null&&(!stable(source)||actor.getVehicle()!=source||!source.protects(actor)||source.changingSeat(actor)))why="source not steady/riding";
        else if(source==null&&(actor.isPassenger()||!actor.onGround()||actor.isInWaterOrBubble()))why="not standing";
        else if(targetHat!=null&&(!stable(targetHat)||seatLocal==null))why="target hull not steady";
        else if(targetHat==null&&dryFeet==null)why="no destination";
        if(why!=null){landingRefusal=why;return false;}
        var from=actor.position();
        var to=targetHat!=null?targetHat.toWorld(seatLocal):dryFeet;
        double size=Math.min(1,actor.growthScale()*actor.getScale());
        double gap=from.subtract(to).horizontalDistance(),rise=to.y-from.y;
        if(gap>hopReach(actor)||rise>.45*size+.12||-rise>1.2){
            landingRefusal="out of reach gap="+gap+"/"+hopReach(actor)+" rise="+rise;return false;
        }
        int ticks=Math.clamp((int)Math.ceil(gap/(.075+.05*size)),7,18);
        double arch=Math.max(.05*size,Math.min(.32*size,.35*gap+Math.max(0,rise)));
        // Only solid blocks can stop the arc: the hull being left (or entered) is exactly what the body is crossing.
        for(int i=1;i<=ticks*2;i++){
            double q=i/(2.0*ticks);var at=stepPoint(from,to,arch,q);
            var box=actor.getBoundingBox().move(at.subtract(actor.position())).deflate(.004);
            if(actor.level().getBlockCollisions(actor,box).iterator().hasNext()){landingRefusal="arc blocked at q="+q+" "+at;return false;}
        }
        if(targetHat==null&&!io.github.longshou.mcyukkuri.subspecies.HatDock.standable(actor,to)){landingRefusal="destination not standable "+to;return false;}
        if(targetHat!=null&&!actor.getUUID().equals(targetHat.ownerYukkuri())){
            var reserved=targetHat.peekSeat(actor);
            if(reserved==null||reserved.distanceToSqr(seatLocal)>1e-6){landingRefusal="seat not reserved";return false;}
        }
        sourceId=source==null?null:source.getUUID();stepHat=targetHat==null?null:targetHat.getUUID();hatId=targetHat!=null?stepHat:sourceId;
        seat=seatLocal;stepSeat=seatLocal;stepFrom=from;stepTo=to;stepTicks=ticks;stepHump=arch;stepping=true;shoreLanding=targetHat==null;
        grip=crest=belly=null;gripSupportY=Double.NaN;dip=HatBoatEntity.Dip.NONE;stagedClimb=false;landing=releasedGrip=false;hump=arch;
        boardingYaw=(float)(Math.toDegrees(Math.atan2(to.z-from.z,to.x-from.x))-90);bellyYaw=boardingYaw;
        totalTicks=0;lastVelocity=Vec3.ZERO;hopTicks=ticks;
        set(Phase.HOP,ticks);
        if(source!=null){
            actor.stopRiding();
            if(actor.position().distanceToSqr(from)>1e-8)actor.setPos(from.x,from.y,from.z);
            source.holdPosition();
        }
        if(targetHat!=null)targetHat.holdPosition();
        actor.getNavigation().stop();actor.setDeltaMovement(Vec3.ZERO);actor.hasImpulse=true;
        landingRefusal="started "+(targetHat!=null?"onto hull":"ashore")+" gap="+gap+" ticks="+ticks;
        io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(actor,"step.start."+actor.getId(),0,landingRefusal+" from="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(from)+" to="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(to));
        return true;
    }
    private static Vec3 stepPoint(Vec3 from,Vec3 to,double arch,double q){
        double s=smooth(q);
        return new Vec3(from.x+(to.x-from.x)*s,from.y+(to.y-from.y)*s+4*arch*q*(1-q),from.z+(to.z-from.z)*s);
    }
    private String landingRefusal="none";
    public String landingRefusal(){return landingRefusal;}
    private void tickStep(){
        long now=level().getGameTime();if(lastTick==now)return;lastTick=now;
        var source=boat(sourceId);var target=boat(stepHat);
        if(!actor.isAlive()||actor.isNoAi()||actor.isCommandPlaying()||actor.operations().active()||actor.isPassenger()||++totalTicks>80){cancel("step aborted");return;}
        if(target!=null){
            if(!stable(target)){cancel("target hull unsteady");return;}
            target.holdPosition();
        }
        if(source!=null&&!source.isRemoved())source.holdPosition();
        int elapsed=(int)Math.min(stepTicks,now-start);
        double q=Mth.clamp(elapsed/(double)Math.max(1,stepTicks),0,1);
        var to=target!=null?target.toWorld(stepSeat):stepTo;
        var at=stepPoint(stepFrom,to,stepHump,q);
        float yaw=target!=null&&actor.getUUID().equals(target.ownerYukkuri())?target.getYRot():(float)(Math.toDegrees(Math.atan2(to.z-stepFrom.z,to.x-stepFrom.x))-90);
        actor.setYRot(Mth.approachDegrees(actor.getYRot(),yaw,16));actor.yBodyRot=actor.getYRot();actor.setYHeadRot(actor.getYRot());
        var box=actor.getBoundingBox().move(at.subtract(actor.position())).deflate(.004);
        if(elapsed<stepTicks&&actor.level().getBlockCollisions(actor,box).iterator().hasNext()){cancel("arc blocked in flight at "+at);return;}
        lastVelocity=at.subtract(actor.position());
        actor.setPos(at.x,at.y,at.z);actor.setDeltaMovement(Vec3.ZERO);actor.hasImpulse=true;actor.fallDistance=0;
        if(elapsed<stepTicks)return;
        if(target!=null){
            boolean landed=actor.getUUID().equals(target.ownerYukkuri())?target.board(actor):target.boardYoung(actor);
            if(!landed){cancel("seat rejected the landing");return;}
            target.cancelIncoming(actor);target.releaseDip(12);
            if(actor.getUUID().equals(target.ownerYukkuri()))target.releaseOwnBoardingSeat(actor);
        }else{
            actor.setPos(to.x,to.y,to.z);actor.setOnGround(true);
        }
        io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(actor,"step.done."+actor.getId(),0,"at="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(actor.position())+" ground="+actor.onGround());
        phase=Phase.IDLE;stepping=false;shoreLanding=false;sourceId=hatId=stepHat=null;lastVelocity=Vec3.ZERO;
    }
    private boolean planClimb(HatBoatEntity source,HatBoatEntity hat,Vec3 origin,Vec3 destination,Vec3 actualGrip,HatBoatEntity.Dip dip){
        lastClimbSample="none";
        climbTrace=io.github.longshou.mcyukkuri.subspecies.AquaDiag.enabled()?new StringBuilder():null;
        if(actualGrip==null||actor.getVariant()!=io.github.longshou.mcyukkuri.entity.YukkuriVariant.MARISA){lastClimbPlan="no reachable grip/model";return false;}
        double scale=Math.min(1,actor.growthScale()*actor.getScale());
        var seatLocal=hat.toLocal(destination);
        var lowDestination=destination.subtract(0,drop(hat,dip,seatLocal),0);
        if(origin.subtract(destination).horizontalDistance()>.22+.7*scale||lowDestination.y-origin.y>.18+.55*scale){lastClimbPlan="landing beyond climb reach";return false;}
        var lip=hat.toWorld(new Vec3(0,HatVessel.top(hat.mode()),0));
        // The near rim is the one crossed, so it is the dipped rim's height that must be cleared.
        double lipY=lip.y-drop(hat,dip,hat.toLocal(actualGrip));
        double sourceSupport=Double.NaN;
        if(source!=null){
            var local=source.toLocal(origin);double floor=HatVessel.footprint(source.mode(),local.x,local.z,HatVessel.radius(actor.getBbWidth())/source.unit());
            if(!Double.isFinite(floor)){lastClimbPlan="no departure support";return false;}
            sourceSupport=source.toWorld(new Vec3(local.x,floor,local.z)).y;
            lipY=Math.max(lipY,source.toWorld(new Vec3(0,HatVessel.top(source.mode()),0)).y);
        }else if(actor.onGround()&&!actor.isInWaterOrBubble())sourceSupport=origin.y;
        // The rim wall the mouth may take: from the waterline up to the (lowered) lip.
        double rimWall=Math.max(.01*scale,actualGrip.y-hat.getY())+(actor.isInWaterOrBubble()?BraidRescueGeometry.biteAllowance(actor):0);
        double nudge=actor.isInWaterOrBubble()?.14:.05;
        var direction=actualGrip.subtract(origin);float toward=(float)(Math.toDegrees(Math.atan2(direction.z,direction.x))-90);
        int nudges=0,bites=0,lifts=0,bellies=0;double minimumNudge=Double.POSITIVE_INFINITY;
        for(float side:new float[]{0,25,-25,50,-50}){
            float yaw=toward+side;
            var mouthOffset=BraidRescueGeometry.mouthPoint(actor,Vec3.ZERO,boardRootPitch("GRIP",1),boardRootRoll("GRIP",1),yaw);
            // Where the feet must stand so the mouth is at the rim wall; the body is nudged there before the bite.
            double low=actualGrip.y-rimWall-mouthOffset.y,high=actualGrip.y-mouthOffset.y;
            // The body rests just outside the wall: its collision box reaches a little further than the mouth, so the bite
            // place is moved out by a fraction of the contact reach and the mouth still touches.
            double margin=Math.clamp(actor.getBbWidth()*.5-Math.hypot(mouthOffset.x,mouthOffset.z)+.04*actor.getBbWidth(),.01,.9*BraidRescueGeometry.biteReach(actor));
            var outward=new Vec3(origin.x-actualGrip.x,0,origin.z-actualGrip.z);
            outward=outward.lengthSqr()<1e-8?Vec3.ZERO:outward.normalize().scale(margin);
            var bite=new Vec3(actualGrip.x-mouthOffset.x+outward.x,Math.clamp(origin.y,low,high),actualGrip.z-mouthOffset.z+outward.z);
            double distance=bite.subtract(origin).length();minimumNudge=Math.min(minimumNudge,distance);
            if(distance>nudge){trace("side="+side+" nudge="+fmt(distance));continue;}
            nudges++;
            var liftedFrom=gripPoint(actor,bite,sourceSupport,1,yaw);
            double drop=0;
            for(var shape:BraidRescueGeometry.boardingEnvelope(actor,bite,boardRootPitch("LIFT",1),boardRootRoll("LIFT",1),yaw))
                drop=Math.max(drop,bite.y-shape.minY);
            boolean biting=true;
            for(int i=0;i<=24&&biting;i++){
                double q=i/24.0;
                var approaching=origin.lerp(bite,smooth(q*GRIP_TICKS/4.0));
                biting=climbSampleClear(actor,source,hat,gripPoint(actor,approaching,sourceSupport,q,yaw),actualGrip,rimWall,"GRIP",q,yaw,dip);
            }
            if(!biting||!BraidRescueGeometry.bites(actor,BraidRescueGeometry.mouthPoint(actor,liftedFrom,boardRootPitch("GRIP",1),boardRootRoll("GRIP",1),yaw),actualGrip,rimWall)){
                trace("side="+side+" bite "+(biting?"mouth off rim":lastClimbSample));continue;
            }
            bites++;
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
                if(!clear){trace("side="+side+" reach="+fmt(reach)+" crestY="+fmt(crestY)+" "+lastClimbSample);continue;}
                lifts++;
                for(float turn:new float[]{0,45,-45,90,-90,180}){
                    float finalYaw=yaw+turn;
                    // The nearest inboard patch that fully carries the belly; small turns and short slides are tried first.
                    Vec3 supportedBelly=null;
                    for(double inward=0;inward<=.9*(.3+scale)&&supportedBelly==null;inward+=.015)
                        supportedBelly=settleSupport(actor,hat,new Vec3(candidateTop.x+ahead.x*inward,candidateTop.y,candidateTop.z+ahead.z*inward),BELLY_SUPPORT_PROGRESS,finalYaw,dip,planMargin(actor));
                    if(supportedBelly==null){trace("side="+side+" reach="+fmt(reach)+" turn="+turn+" no belly");continue;}
                    bellies++;
                    clear=true;int samples=64+(int)Math.ceil(Math.abs(turn)/4);
                    for(int i=0;i<=samples&&clear;i++){
                        double q=(double)i/samples;float atYaw=settleYaw(yaw,finalYaw,q);
                        var at=slidePoint(actor,hat,candidateTop,supportedBelly,destination,q,yaw,finalYaw,dip,planMargin(actor));
                        if(at==null)lastClimbSample="SETTLE q="+fmt(q)+" unsupported belly disc";
                        clear=at!=null&&climbSampleClear(actor,source,hat,at,actualGrip,rimWall,"SETTLE",q,atYaw,dip);
                    }
                    var releaseAt=settleSupport(actor,hat,supportedBelly,GRIP_RELEASE_PROGRESS,finalYaw,dip);
                    if(clear&&releaseAt==null)lastClimbSample="release unsupported";
                    clear=clear&&releaseAt!=null&&climbSampleClear(actor,source,hat,releaseAt,actualGrip,rimWall,"SETTLE",GRIP_RELEASE_PROGRESS,finalYaw,dip)
                            &&hasBellySupport(actor,hat,releaseAt,GRIP_RELEASE_PROGRESS,finalYaw,dip);
                    if(!clear)trace("side="+side+" reach="+fmt(reach)+" turn="+turn+" belly="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(supportedBelly)+" "+lastClimbSample);
                    if(clear){
                        grip=actualGrip;crest=candidateTop;belly=supportedBelly;liftFrom=liftedFrom;gripSupportY=sourceSupport;biteFeet=bite;wall=rimWall;
                        boardingYaw=yaw;bellyYaw=finalYaw;
                        // Smoothstep's peak derivative is 1.5; cap the real turn at eight degrees per tick.
                        settleTicks=Math.max(MIN_SETTLE_TICKS,(int)Math.ceil(1.5*Math.abs(turn)/(.34*8)));
                        // Each half of the slide is a smoothstep over .34 of the phase; keep its peak a belly's crawl, not a dash.
                        double crawl=.02+.05*scale,longest=Math.max(candidateTop.distanceTo(supportedBelly),supportedBelly.distanceTo(destination));
                        settleTicks=Math.max(settleTicks,(int)Math.ceil(1.5*longest/(.34*crawl)));
                        lastClimbPlan="clear yaw="+yaw+" grip="+actualGrip+" nudge="+distance;
                        return true;
                    }
                }
            }
        }
        lastClimbPlan="rejected grip="+actualGrip+" minNudge="+minimumNudge+"/"+nudge+" nudges="+nudges
                +" bites="+bites+" lifts="+lifts+" bellies="+bellies+" sample="+lastClimbSample;
        if(climbTrace!=null)io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(actor,"climb.plan."+actor.getId(),0,lastClimbPlan
                +" | in: feet="+origin+" seat="+destination+" seatLocal="+seatLocal+" low="+lowDestination+" lipY="+fmt(lipY)+" wall="+fmt(rimWall)
                +" scale="+fmt(scale)+" bb="+fmt(actor.getBbWidth())+"x"+fmt(actor.getBbHeight())+" hat="+hat.position()+" hatYaw="+hat.getYRot()
                +" unit="+hat.unit()+" dip="+dip+" hatDip="+hat.dip()+" | "+climbTrace);
        return false;
    }
    private boolean climbSampleClear(YukkuriEntity actor,HatBoatEntity source,HatBoatEntity hat,Vec3 feet,Vec3 grip,double wall,String phase,double q,float yaw,HatBoatEntity.Dip dip){
        double pitch=boardRootPitch(phase,q),roll=boardRootRoll(phase,q);
        boolean teeth=phase.equals("GRIP")||phase.equals("LIFT");
        boolean held=!teeth||BraidRescueGeometry.holds(actor,BraidRescueGeometry.mouthPoint(actor,feet,pitch,roll,yaw),grip,wall);
        boolean clear=clearAt(actor,source,hat,feet,pitch,roll,yaw,dip);
        if(!held||!clear)lastClimbSample=phase+" q="+fmt(q)+" held="+held+" clear="+clear+(clear?"":"("+blockedBy(actor,source,hat,feet,pitch,roll,yaw,dip)+")")+" feet="+feet+" yaw="+yaw
                +" sourceFloor="+(source==null?"none":BraidRescueGeometry.poseClearance(actor,feet,pitch,roll,yaw,source,null))
                +" targetFloor="+BraidRescueGeometry.poseClearance(actor,feet,pitch,roll,yaw,hat,dip);
        return held&&clear;
    }
    private void trace(String entry){if(climbTrace!=null&&climbTrace.length()<6000)climbTrace.append(entry).append("; ");}
    private static String fmt(double v){return String.format(java.util.Locale.ROOT,"%.4f",v);}
    /** Diagnostics only: which obstacle clearAt refuses, and by how much a hull's measured surface overlaps the posed body. */
    private static String blockedBy(YukkuriEntity actor,HatBoatEntity source,HatBoatEntity hat,Vec3 feet,double pitch,double roll,float yaw,HatBoatEntity.Dip dip){
        var level=actor.level();var out=new StringBuilder();
        for(var shape:BraidRescueGeometry.boardingEnvelope(actor,feet,pitch,roll,yaw)){
            var box=shape.deflate(.002);
            if(level.getBlockCollisions(actor,box).iterator().hasNext())out.append("block ");
            for(var other:level.getEntities(actor,box))if(other!=source&&other!=hat&&other.isAlive()&&!other.isSpectator()
                    &&(other instanceof YukkuriEntity||other.canBeCollidedWith()))out.append("entity:").append(other.getType().toShortString()).append(' ');
        }
        for(var vessel:new HatBoatEntity[]{source,hat})if(vessel!=null){
            double need=BraidRescueGeometry.poseClearance(actor,feet,pitch,roll,yaw,vessel,vessel==hat?dip:null)-feet.y;
            if(need>.002)out.append(vessel==hat?"hat+":"source+").append(fmt(need)).append(' ');
        }
        return out.toString().trim();
    }
    /** Keep the real underside on the measured brim while the supported body slides over. */
    public static Vec3 settleSupport(YukkuriEntity actor,HatBoatEntity hat,Vec3 wanted,double q,float yaw,HatBoatEntity.Dip dip){
        return settleSupport(actor,hat,wanted,q,yaw,dip,0);
    }
    /** Planning margin for the belly disc, in blocks: about a centimetre for a child. */
    private static double planMargin(YukkuriEntity actor){return .004+.02*Math.min(1,actor.growthScale()*actor.getScale());}
    private static Vec3 settleSupport(YukkuriEntity actor,HatBoatEntity hat,Vec3 wanted,double q,float yaw,HatBoatEntity.Dip dip,double margin){
        var local=hat.toLocal(wanted);
        double radius=HatVessel.radius(actor.getBbWidth())/hat.unit(),carried=radius+margin/hat.unit();
        double floor=HatVessel.footprint(hat.mode(),local.x,local.z,radius);
        if(!Double.isFinite(floor))return null;
        // footprint() alone returns the highest *partial* overlap. Releasing needs
        // an actual supported belly patch, including its centre and whole small support disc.
        for(int i=0;i<=8;i++){
            double x=local.x+(i==8?0:carried*Math.cos(i*Math.PI/4)),z=local.z+(i==8?0:carried*Math.sin(i*Math.PI/4));
            if(!Double.isFinite(HatVessel.surface(hat.mode(),x,z)))return null;
        }
        double pitch=boardRootPitch("SETTLE",q),roll=boardRootRoll("SETTLE",q);
        var body=BraidRescueGeometry.boardingEnvelope(actor,wanted,pitch,roll,yaw).getFirst();
        double support=hat.toWorld(new Vec3(local.x,floor,local.z)).y-drop(hat,dip,local);
        // A dipped brim is lowered most at the near rim: the belly's centre may sit on a lower part of the tilt than the
        // posed body's farther underside. It rests on whichever real contact is highest, exactly as clearAt measures it.
        double contact=BraidRescueGeometry.poseClearance(actor,new Vec3(wanted.x,wanted.y-1,wanted.z),pitch,roll,yaw,hat,dip);
        return new Vec3(wanted.x,Math.max(support-(body.minY-wanted.y),contact),wanted.z);
    }
    private static boolean hasBellySupport(YukkuriEntity actor,HatBoatEntity hat,Vec3 feet,double q,float yaw,HatBoatEntity.Dip dip){
        var supported=settleSupport(actor,hat,feet,q,yaw,dip);
        return supported!=null&&Math.abs(supported.y-feet.y)<=.002*actor.growthScale()*actor.getScale();
    }
    private void set(Phase next,int ticks){
        if(stagedClimb)io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(actor,"climb.phase."+actor.getId(),0,phase+" -> "+next+" ticks="+ticks+" at="+actor.position());
        phase=next;duration=ticks;start=level().getGameTime();
    }
    private void hold(HatBoatEntity source,HatBoatEntity hat){
        actor.getNavigation().stop();hat.holdPosition();
        // A supported down-step keeps the paired hats steady until the feet have settled on the lower rim.
        if(source!=null&&(phase==Phase.ALIGN||phase==Phase.GRIP||!stepDownPath.isEmpty()))source.holdPosition();
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
        if(stepping){tickStep();return;}
        long now=level().getGameTime();if(lastTick==now)return;lastTick=now;
        previousBodyYaw=actor.yBodyRot;
        double previousQ=Mth.clamp((double)(now-1-start)/Math.max(1,duration),0,1);
        previousPitch=boardRootPitch(phase.name(),previousQ);previousRoll=boardRootRoll(phase.name(),previousQ);previousSquash=boardRootSquash(phase.name(),previousQ);
        var hat=boat(hatId);var source=boat(sourceId);
        if(!capable()){cancel("not capable");return;}
        if(!stable(hat)){cancel("target unstable");return;}
        if(sourceId!=null&&(phase==Phase.ALIGN||phase==Phase.GRIP)&&!stable(source)){cancel("source unstable");return;}
        if(++totalTicks>Math.max(220,settleTicks+120)){cancel("boarding timeout");return;}
        if(hat.toWorld(seat).distanceToSqr(to)>.0004){cancel("target moved from="+to+" to="+hat.toWorld(seat));return;}
        if(!actor.getUUID().equals(hat.ownerYukkuri())){
            var reserved=hat.peekSeat(actor);
            if(reserved==null||reserved.distanceToSqr(seat)>.000001){cancel();return;}
        }
        hold(source,hat);leanHull(hat);int elapsed=(int)(now-start);
        if(phase==Phase.ALIGN){
            if(source!=null&&(actor.getVehicle()!=source||source.changingSeat(actor))
                    ||source==null&&!waterAlign&&(!actor.onGround()||actor.isInWaterOrBubble())
                    ||waterAlign&&!actor.isInWaterOrBubble()
                    ||actor.position().distanceToSqr(from)>(waterAlign?.0256:.0036)){cancel("alignment source/feet changed from="+from);return;}
            var delta=to.subtract(actor.position());float yaw=(float)(Math.toDegrees(Math.atan2(delta.z,delta.x))-90);
            if(stagedClimb||!stepDownPath.isEmpty())yaw=boardingYaw;
            actor.setYRot(Mth.approachDegrees(actor.getYRot(),yaw,12));actor.yBodyRot=actor.getYRot();actor.setYHeadRot(actor.getYRot());
            // Close the last gap to the bite spot a little at a time, never through a block.
            boolean placed=true;
            // A passenger's seat would undo a pre-dismount nudge. Its checked first four GRIP ticks
            // perform the same approach sampled by planClimb, after releasing that seat.
            if(stagedClimb&&biteFeet!=null&&source==null){
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
            }else if(!stepDownPath.isEmpty()){
                stepDownPath=planSupportedStep(source,hat,from,to,boardingYaw);
                if(stepDownPath.isEmpty()){cancel();return;}
                hopTo=to;hump=0;hopTicks=stepDownPath.size();
            }else{
                hopTo=to.subtract(0,hat.rimLift(seat),0);hump=hopHeight(actor,from,hopTo);
                if((hopTicks=hopDuration(actor,source,hat,from,hopTo,null))==0){cancel();return;}
            }
            if(!actor.life().spendEnergy(.25f)){cancel();return;}
            if(source!=null){
                actor.stopRiding();
                if(actor.position().distanceToSqr(from)>1e-8){cancel("dismount relocated from="+from);return;}
            }
            actor.setDeltaMovement(Vec3.ZERO);
            if(stagedClimb){actor.setYRot(boardingYaw);actor.yBodyRot=boardingYaw;alignedFrom=from;biteTicks=0;}
            set(stagedClimb?Phase.GRIP:Phase.HOP,stagedClimb?GRIP_TICKS:hopTicks);return;
        }
        if(actor.isPassenger()){cancel("unexpected passenger");return;}
        if(!stepDownPath.isEmpty()){
            float yaw=stepDownYaws.get(Math.clamp(elapsed-1,0,stepDownYaws.size()-1));
            if(!turnClear(actor,source,hat,actor.position(),actor.yBodyRot,yaw)){cancel("supported turn obstructed");return;}
            actor.setYRot(yaw);actor.yBodyRot=yaw;actor.setYHeadRot(yaw);
        }
        double q=Mth.clamp((double)elapsed/duration,0,1);
        float activeYaw=phase==Phase.SETTLE?settleYaw(boardingYaw,bellyYaw,q):boardingYaw;
        if(climbing()){
            actor.setYRot(Mth.approachDegrees(actor.getYRot(),activeYaw,8));actor.yBodyRot=actor.getYRot();
            activeYaw=actor.yBodyRot;
        }
        Vec3 at=phase==Phase.HOP?(!stepDownPath.isEmpty()?stepDownPath.get(Math.clamp(elapsed-1,0,stepDownPath.size()-1))
                :landing?to.subtract(0,hat.rimLift(seat),0):hopPoint(from,hopTo,hopTicks,Math.min(elapsed,hopTicks),hump))
                :phase==Phase.GRIP?gripPoint(actor,alignedFrom.lerp(biteFeet,smooth(elapsed/4.0)),gripSupportY,q,boardingYaw)
                :phase==Phase.LIFT?liftPoint(actor,source,hat,liftFrom,crest,q,boardingYaw,null)
                :slidePoint(actor,hat,crest,belly,to,q,boardingYaw,bellyYaw,null);
        if(at==null){cancel("no supported trajectory point belly="+(belly==null?"none":hat.toLocal(belly))+" hat="+hat.position()+"/"+hat.getYRot());return;}
        double pitch=boardRootPitch(phase.name(),q),roll=boardRootRoll(phase.name(),q);
        boolean releasing=phase==Phase.SETTLE&&!releasedGrip&&q>=GRIP_RELEASE_PROGRESS;
        if(releasing&&!hasBellySupport(actor,hat,actor.position(),Math.max(BELLY_SUPPORT_PROGRESS,q-1.0/duration),previousBodyYaw,null)){cancel();return;}
        if(gripped()){
            var mouth=BraidRescueGeometry.mouthPoint(actor,at,pitch,roll,activeYaw);
            if(phase==Phase.GRIP)biteTicks=BraidRescueGeometry.bites(actor,mouth,grip,wall)?biteTicks+1:0;
            if(!actor.life().spendEnergy(.025f)||!BraidRescueGeometry.holds(actor,mouth,grip,wall)){cancel();return;}
        }
        var delta=at.subtract(actor.position());
        if(delta.lengthSqr()>.45*.45){cancel("trajectory gap target="+at);return;}
        if(!sweepClear(actor,source,hat,actor.position(),at)
                ||stagedClimb&&!clearAt(actor,source,hat,at,pitch,roll,actor.yBodyRot,null)){
            cancel("trajectory collision target="+at+" yaw="+actor.yBodyRot);return;
        }
        // A bounded physical boarding trajectory replaces only the two hollow hats' coarse AABBs.
        // Its endpoint is fixed at takeoff; a moving boat cannot pull a flying child after itself.
        actor.move(MoverType.SELF,delta);actor.setDeltaMovement(Vec3.ZERO);actor.hasImpulse=true;
        if(actor.position().distanceToSqr(at)>.0004){cancel("engine move clipped target="+at);return;}
        actor.fallDistance=0;lastVelocity=delta;
        if(stagedClimb)io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(actor,"climb.at."+actor.getId(),0,phase+" q="+fmt(q)+" step="+fmt(delta.length())+" at="+actor.position()+" yaw="+actor.yBodyRot+" hat="+hat.position()+"/"+hat.getYRot()+" dip="+hat.dip());
        if(releasing){
            if(!hasBellySupport(actor,hat,actor.position(),q,activeYaw,null)){cancel();return;}
            releasedGrip=true;hat.releaseDip(12);
        }
        if(elapsed<duration)return;
        if(phase==Phase.GRIP){if(biteTicks>=BITE_TICKS)set(Phase.LIFT,LIFT_TICKS);return;}
        if(phase==Phase.LIFT){set(Phase.SETTLE,settleTicks);return;}
        // Landed: the hull must be level again so the seat the hat gives is the one the body already rests on.
        if(hat.rimLift(seat)>.003*Math.max(.25,Math.min(1,actor.growthScale()*actor.getScale()))){landing=true;hat.releaseDip(8);return;}
        var landedAt=hat.toLocal(actor.position());
        boolean landed=actor.getUUID().equals(hat.ownerYukkuri())?hat.board(actor):hat.boardYoung(actor);
        if(!landed){cancel("landing mount rejected");return;}
        if(stagedClimb)io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(actor,"climb.board."+actor.getId(),0,"landed local="+landedAt+" seat="+seat+" now="+hat.toLocal(actor.position()));
        phase=Phase.IDLE;stepDownPath=List.of();hat.cancelIncoming(actor);hat.releaseDip(12);
        if(actor.getUUID().equals(hat.ownerYukkuri()))hat.releaseOwnBoardingSeat(actor);
        hatId=sourceId=null;lastVelocity=Vec3.ZERO;
    }
    public static boolean sweepClear(YukkuriEntity actor,HatBoatEntity source,HatBoatEntity hat,Vec3 from,Vec3 to){return sweepClear(actor,source,hat,from,to,null);}
    private static boolean sweepClear(YukkuriEntity actor,HatBoatEntity source,HatBoatEntity hat,Vec3 from,Vec3 to,HatBoatEntity.Dip dip){
        return sweepClear(actor,source,hat,from,to,dip,actor.yBodyRot);
    }
    private static boolean sweepClear(YukkuriEntity actor,HatBoatEntity source,HatBoatEntity hat,Vec3 from,Vec3 to,HatBoatEntity.Dip dip,float yaw){
        int steps=Math.max(1,(int)Math.ceil(from.distanceTo(to)/.025));
        for(int i=1;i<=steps;i++)if(!clearAt(actor,source,hat,from.lerp(to,(double)i/steps),0,0,yaw,dip))return false;
        return true;
    }
    private static boolean clearAt(YukkuriEntity actor,HatBoatEntity source,HatBoatEntity hat,Vec3 feet,double pitch,double roll,float yaw,HatBoatEntity.Dip dip){
        var level=actor.level();
        var boxes=BraidRescueGeometry.boardingEnvelope(actor,feet,pitch,roll,yaw);
        if(!level.getWorldBorder().isWithinBounds(BlockPos.containing(feet)))return false;
        for(var shape:boxes){
            var box=shape.deflate(.002);
            if(level.getBlockCollisions(actor,box).iterator().hasNext())return false;
            var nearby=new java.util.HashSet<net.minecraft.world.entity.Entity>(level.getEntities(actor,box));
            // Passenger hats extend beyond their vanilla body boxes; query the complete paired crews.
            if(source!=null)nearby.addAll(source.getPassengers());
            if(hat!=null)nearby.addAll(hat.getPassengers());
            for(var other:nearby){
                if(other==actor||other==source||other==hat||!other.isAlive()||other.isSpectator())continue;
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
    public void cancel(){cancel("external or contact lost");}
    private void cancel(String reason){
        if(!active())return;
        lastCancel=phase.name()+" tick="+totalTicks+" "+reason+" at="+actor.position();
        io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(actor,"boarding.cancel."+actor.getId(),0,lastCancel+" line="+StackWalker.getInstance().walk(f->f.skip(1).findFirst().map(StackWalker.StackFrame::getLineNumber).orElse(-1)));
        var hat=boat(hatId);boolean airborne=phase!=Phase.ALIGN;
        if(hat!=null){hat.cancelIncoming(actor);hat.releaseDip(14);if(actor.getUUID().equals(hat.ownerYukkuri()))hat.releaseOwnBoardingSeat(actor);}
        else abandonedHat=hatId;
        phase=Phase.IDLE;hatId=sourceId=stepHat=null;shoreLanding=stepping=false;stepDownPath=List.of();
        if(airborne&&!actor.isPassenger()){
            actor.setDeltaMovement(lastVelocity.x,Math.min(-.02,lastVelocity.y),lastVelocity.z);actor.hasImpulse=true;
        }
    }
    public CompoundTag state(){
        var tag=new CompoundTag();if(!active())return tag;
        var hat=boat(hatId);if(hat==null)return tag;
        tag.putBoolean("ShoreLanding",shoreLanding);
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
        tag.putBoolean("StepDown",!stepDownPath.isEmpty());
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
        phase=Phase.IDLE;hatId=sourceId=stepHat=null;shoreLanding=stepping=false;stepDownPath=List.of();abandonedHat=tag.hasUUID("ReservedHat")?tag.getUUID("ReservedHat"):null;
        lastTick=Long.MIN_VALUE;lastVelocity=Vec3.ZERO;
    }
    private static void put(CompoundTag tag,String key,Vec3 point){if(point!=null){tag.putDouble(key+"X",point.x);tag.putDouble(key+"Y",point.y);tag.putDouble(key+"Z",point.z);}}
}
