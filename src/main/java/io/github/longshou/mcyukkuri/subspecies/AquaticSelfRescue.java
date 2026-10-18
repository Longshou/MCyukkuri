package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.BraidRescueGeometry;
import io.github.longshou.mcyukkuri.family.HatBoarding;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Finite physical attempts to recover one's own hat; immersion damage remains entirely in YukkuriLife.
 *  The hat is shaken off the head, bitten at its rim and, if upside down, pushed over with the body three times (project-original, not canon). */
public final class AquaticSelfRescue {
    public enum Phase { IDLE, REMOVE_HAT, DEPLOY_HAT, APPROACH, GRIP, RIGHT_HAT, CLIMB, SLIPPED, SAFE }
    /** Three pushes, each 12 ticks of pushing and 8 of rest; progress .33 / .66 / 1 of the existing hat righting. */
    public static final int PUSHES=3, PUSH_ON=12, PUSH_TICKS=20, BITE_TICKS=8, MAX_ATTEMPT_TICKS=320;
    private final YukkuriEntity actor;
    private Phase phase=Phase.IDLE;
    private UUID hatId;
    private long started,lastEscape=Long.MIN_VALUE,nextAttempt,nextGripCheck;
    private int contact,rightTicks,attemptTicks;
    private Vec3 lastGrip;
    private Vec3 lockedGripLocal;
    private HatBoatEntity.SelfRescueDeployment deployment;
    private float removalProgress;
    private SelfRescueHatGeometry.Pose carriedPose,previousCarriedPose;
    private long poseTick=Long.MIN_VALUE;
    private float poseYaw=Float.NaN,previousPoseYaw=Float.NaN;
    public AquaticSelfRescue(YukkuriEntity actor){this.actor=actor;}
    private ServerLevel level(){return (ServerLevel)actor.level();}
    private long now(){return level().getGameTime();}
    public Phase phase(){return phase;}
    public long phaseStart(){return started;}
    public boolean active(){return phase!=Phase.IDLE;}
    public int targetHatId(){var hat=hat();return hat==null?-1:hat.getId();}
    public int pushIndex(){return Math.min(PUSHES-1,rightTicks/PUSH_TICKS);}
    public float gripProgress(){return phase==Phase.RIGHT_HAT?Math.clamp(rightTicks/(float)(PUSHES*PUSH_TICKS),0,1):Math.clamp(contact/(float)BITE_TICKS,0,1);}
    /** Kept stable for the presentation, which maps the clip from the phase. */
    public String activity(){return switch(phase){
        case IDLE->"idle";case REMOVE_HAT->"water_hat_remove";case DEPLOY_HAT,RIGHT_HAT->"water_hat_right";
        case APPROACH->"water_hat_reach";case GRIP->"water_hat_grip";case CLIMB->"water_hat_climb";
        case SLIPPED->"water_hat_slip";case SAFE->"water_hat_safe";
    };}
    private HatBoatEntity hat(){return hatId!=null&&level().getEntity(hatId) instanceof HatBoatEntity h&&!h.isRemoved()?h:null;}
    private boolean enabled(){return actor.isAlive()&&HatVessel.aquatic(actor)&&!actor.isNoAi()&&!actor.isCommandPlaying()
            &&!actor.operations().active()&&!actor.isOnFire()&&!actor.family().braidRescueActive();}
    private boolean capable(){return actor.family().stage()!=io.github.longshou.mcyukkuri.family.FamilyLife.Stage.BABY
            &&actor.childhood().canJump()&&actor.life().energy()>=8
            &&(actor.family().adult()||actor.aquaticLife().firstVoyage().readySize());}
    private void set(Phase next){if(phase!=next){AquaDiag.log(actor,"self.phase."+actor.getId(),0,phase+" -> "+next+" hp="+actor.getHealth()+" at="+AquaDiag.at(actor.position()));phase=next;started=now();}}
    private void trackYaw(){
        if(poseTick!=now()){previousPoseYaw=poseYaw;poseTick=now();}
        poseYaw=actor.yBodyRot;
    }
    private void pose(SelfRescueHatGeometry.Pose next){
        previousCarriedPose=carriedPose;carriedPose=next;
    }
    public void clock(){
        if(actor.level().isClientSide)return;
        if(!enabled()){stop();return;}
        var current=hat();
        if(active()&&current!=null&&actor.getVehicle()==current&&current.protects(actor)){
            if(phase!=Phase.SAFE){set(Phase.SAFE);current.releaseOwnBoardingSeat(actor);actor.showReaction("sad","self_rescue_safe",40);}
            if(now()-started>=40){phase=Phase.IDLE;hatId=null;deployment=null;removalProgress=0;}return;
        }
        if(phase==Phase.CLIMB){
            if(actor.family().selfClimbActive())return;
            slip();return;
        }
        if(phase==Phase.SLIPPED){
            // Before the shake the hat never left the head. After it, the hat is already a real drifting vessel.
            if(now()-started>=20){stop();nextAttempt=Math.max(nextAttempt,now()+12);}return;
        }
        if(active()&&phase!=Phase.SLIPPED&&!capable()){slip();return;}
        if(!actor.isInWaterOrBubble()||actor.isPassenger()){stop();return;}
        if(active()&&phase!=Phase.REMOVE_HAT&&phase!=Phase.SLIPPED&&(current==null||!current.ownsPhysicalHat(actor))){stop();nextAttempt=now()+20;}
    }
    /** Called by WaterDistress's existing escape goal after impact; it never suppresses the water clock or damage. */
    public boolean escapeTick(){
        if(!enabled()||!capable()||!actor.isInWaterOrBubble()||actor.isPassenger()||actor.family().flying()){
            AquaDiag.log(actor,"self.skip."+actor.getId(),40,"enabled="+enabled()+" capable="+capable()+" water="+actor.isInWaterOrBubble()+" passenger="+actor.isPassenger()+" flying="+actor.family().flying());
            return false;
        }
        if(lastEscape==now())return active();lastEscape=now();
        if(actor.family().selfClimbActive()){set(Phase.CLIMB);return true;}
        if(phase==Phase.IDLE){
            if(now()<nextAttempt||actor.life().energy()<8)return false;
            var own=HatBoatEntity.owned(actor);
            if(own!=null&&own.ownsPhysicalHat(actor)&&actor.distanceToSqr(own)<=64){hatId=own.getUUID();set(Phase.APPROACH);}
            else if(actor.life().hasAccessory()&&actor.subspecies().boatId()==null){
                deployment=HatBoatEntity.planSelfRescueDeployment(actor);
                if(deployment==null){AquaDiag.log(actor,"self.plan."+actor.getId(),40,"no deployment spot at "+AquaDiag.at(actor.position())+" yaw="+actor.yBodyRot);nextAttempt=now()+20;return false;}
                hatId=null;removalProgress=0;carriedPose=previousCarriedPose=null;set(Phase.REMOVE_HAT);
            }
            else{AquaDiag.log(actor,"self.nohat."+actor.getId(),40,"no hat state accessory="+actor.life().hasAccessory()+" boat="+actor.subspecies().boatId());return false;}
            AquaDiag.log(actor,"self.start."+actor.getId(),0,"phase="+phase);
            attemptTicks=contact=rightTicks=0;lastGrip=lockedGripLocal=null;actor.showReaction("teary","self_rescue_hat",36);
        }
        if(++attemptTicks>MAX_ATTEMPT_TICKS){slip();nextAttempt=now()+40;return false;}
        actor.life().wakeInHat();actor.getNavigation().stop();trackYaw();
        if(phase==Phase.SLIPPED){
            if(now()-started>=20){set(Phase.IDLE);nextAttempt=Math.max(nextAttempt,now()+12);}return false;
        }
        if(phase==Phase.REMOVE_HAT){
            var held=hat();
            if(deployment==null||held==null&&!actor.life().hasAccessory()
                    ||held!=null&&(!held.ownsPhysicalHat(actor)||!held.selfRescueHeld())){slip();return false;}
            float elapsed=Math.min(SelfRescueHatGeometry.REMOVE_TICKS,now()-started);
            removalProgress=elapsed/SelfRescueHatGeometry.REMOVE_TICKS;
            // Shaking the head loose: the body keeps its heading and stays afloat; water buoyancy/damage are never suspended.
            actor.yBodyRot=deployment.path().bodyYaw();actor.setYRot(actor.yBodyRot);actor.setYHeadRot(actor.yBodyRot);
            var velocity=actor.getDeltaMovement();actor.setDeltaMovement(velocity.x*.6,velocity.y,velocity.z*.6);
            var next=SelfRescueHatGeometry.pose(actor,deployment.path(),elapsed);
            if(!SelfRescueHatGeometry.valid(actor,deployment.path(),next,carriedPose)){slip();return false;}
            pose(next);
            if(elapsed>=SelfRescueHatGeometry.SHAKE){
                // Once clear of the head the same unique hat becomes the real vessel and slides to the planned spot.
                if(held==null){
                    if(!actor.life().spendEnergy(.35f)){slip();return false;}
                    held=HatBoatEntity.deployForSelfRescue(actor,deployment,next,elapsed);
                    if(held==null||!held.selfRescueHeld()){slip();return false;}
                    hatId=held.getUUID();
                }else if(!actor.life().spendEnergy(.012f)||!held.setSelfRescueCarryPose(actor,next,elapsed)){slip();return false;}
            }
            if(elapsed<SelfRescueHatGeometry.REMOVE_TICKS)return true;
            if(held==null){slip();return false;}
            set(Phase.DEPLOY_HAT);return true;
        }
        var current=hat();
        if(current==null||!current.ownsPhysicalHat(actor)||actor.distanceToSqr(current)>64){stop();nextAttempt=now()+20;return false;}
        if(phase==Phase.DEPLOY_HAT){
            // The settled upright pose is held for six ticks, then lets water/inertia take over.
            if(deployment!=null&&now()-started<6){
                var settled=SelfRescueHatGeometry.pose(actor,deployment.path(),SelfRescueHatGeometry.REMOVE_TICKS);
                if(!SelfRescueHatGeometry.valid(actor,deployment.path(),settled,carriedPose)
                        ||!current.setSelfRescueCarryPose(actor,settled,SelfRescueHatGeometry.REMOVE_TICKS)){slip();return false;}
                pose(settled);return true;
            }
            current.finishSelfRescueCarry(actor);carriedPose=previousCarriedPose=null;set(Phase.APPROACH);
        }
        if(current.capsizing()||current.state()==HatBoatEntity.State.BEACHED||current.towed())return false;
        var target=current.ownGripApproach(actor);if(target==null){slip();return false;}
        boolean holding=(phase==Phase.GRIP||phase==Phase.RIGHT_HAT)&&lockedGripLocal!=null;
        var grip=holding?current.selfRescueGrip(actor,lockedGripLocal):current.selfRescueGrip(actor);
        if(grip==null){
            contact=0;rightTicks=Math.max(0,rightTicks-2);lastGrip=lockedGripLocal=null;set(Phase.APPROACH);
            SurfaceApproach.face(actor,SurfaceApproach.yaw(actor.position(),current.position()));
            swimToward(current,target);return true;
        }
        if(holding&&lastGrip!=null&&lastGrip.distanceToSqr(grip)>.10*.10){slip();return false;}
        lastGrip=grip;
        // The mouth, not the braid, meets the rim: face it and close the last distance a little at a time.
        SurfaceApproach.face(actor,SurfaceApproach.yaw(actor.position(),grip));
        // Finite effort and drift control the outcome. A fast moving/turning brim cannot grant instant grip.
        if(current.getDeltaMovement().subtract(actor.getDeltaMovement()).horizontalDistanceSqr()>.10*.10){slip();return false;}
        if(phase==Phase.APPROACH){
            lockedGripLocal=current.selfRescueGripLocal(actor);if(lockedGripLocal==null){swimToward(current,target);return true;}
            set(Phase.GRIP);contact=0;nextGripCheck=now()+8;
        }
        if(phase==Phase.GRIP&&now()>=nextGripCheck){
            nextGripCheck=now()+8;
            if(actor.getRandom().nextFloat()<slipRisk(current)){slip();return false;}
        }
        if(!actor.life().spendEnergy(.025f)){slip();return false;}
        var velocity=actor.getDeltaMovement();actor.setDeltaMovement(velocity.x*.65,velocity.y,velocity.z*.65);
        if(phase==Phase.GRIP){
            double wall=Math.max(.01*Math.min(1,actor.growthScale()*actor.getScale()),grip.y-current.getY())+BraidRescueGeometry.biteAllowance(actor);
            closeOnRim(grip,wall);
            contact=BraidRescueGeometry.bites(actor,BraidRescueGeometry.mouthPoint(actor,actor.position(),actor.yBodyRot),grip,wall)?contact+1:0;
            if(contact<BITE_TICKS)return true;
            if(contact==BITE_TICKS)actor.speak("self_rescue_grip");
            if(current.state()==HatBoatEntity.State.CAPSIZED){rightTicks=0;set(Phase.RIGHT_HAT);}
            else if(current.isAfloat()&&!current.unsteadyMotion()){
                var water=BlockPos.containing(current.position().add(0,-.12,0));
                if(HatBoatRoute.calmSurface(level(),water)&&Math.abs(current.getY()-HatBoatRoute.surface(level(),water).y)<.02
                        &&Math.abs(current.getDeltaMovement().y)<.015)return startClimb(current);
            }
            return true;
        }
        if(phase==Phase.RIGHT_HAT){
            // Three body pushes from below the rim: push, then rest. Each costs effort and may let the rim slip away.
            int slot=pushIndex(),within=rightTicks%PUSH_TICKS;
            if(within==0){
                if(!actor.life().spendEnergy(.035f)||actor.getRandom().nextFloat()<slipRisk(current)){slip();return false;}
                if(slot==0)actor.speak("self_rescue_climb");
            }
            double progress=(slot+(within<PUSH_ON?HatBoarding.smooth((double)within/PUSH_ON):1))/PUSHES;
            if(!current.setSelfRighting(actor,(float)progress,lockedGripLocal)){slip();return false;}
            if(++rightTicks>=PUSHES*PUSH_TICKS){
                if(current.finishSelfRighting(actor)){contact=rightTicks=0;lastGrip=lockedGripLocal=null;set(Phase.APPROACH);return true;}
                slip();return false;
            }
        }
        return true;
    }
    /** Nudge the body, never through a block, until the mouth is on the rim wall (from the waterline up to the lip). */
    private void closeOnRim(Vec3 grip,double wall){
        var mouth=BraidRescueGeometry.mouthPoint(actor,actor.position(),actor.yBodyRot);
        double y=Math.clamp(mouth.y,grip.y-wall,grip.y);
        // The body rests just outside the wall: its collision box reaches a little past the mouth, so the mouth stops a
        // fraction of the contact reach short of the lip and the box never overlaps the hull's rim.
        double rest=Math.clamp(actor.getBbWidth()*.5-Math.hypot(mouth.x-actor.getX(),mouth.z-actor.getZ())+.04*actor.getBbWidth(),.01,.9*BraidRescueGeometry.biteReach(actor));
        var outward=new Vec3(actor.getX()-grip.x,0,actor.getZ()-grip.z);
        outward=outward.lengthSqr()<1e-8?Vec3.ZERO:outward.normalize().scale(rest);
        var gap=new Vec3(grip.x+outward.x-mouth.x,y-mouth.y,grip.z+outward.z-mouth.z);double length=gap.length();
        if(length<=.004)return;
        var step=gap.scale(Math.min(1,.02/length));
        if(level().getBlockCollisions(actor,actor.getBoundingBox().move(step)).iterator().hasNext())return;
        var next=actor.position().add(step);actor.setPos(next.x,next.y,next.z);actor.hasImpulse=true;
    }
    private boolean startClimb(HatBoatEntity current){
        if(actor.family().climbOwnHat(current)){set(Phase.CLIMB);actor.speak("self_rescue_climb");return true;}
        AquaDiag.log(actor,"self.climb."+actor.getId(),0,"climb refused: "+actor.family().hatBoardingDebug()+" hat="+AquaDiag.at(current.position())+" self="+AquaDiag.at(actor.position()));
        slip();return false;
    }
    /** One grip check per eight contact ticks, never a per-tick failure roll or an instant success roll. */
    private float slipRisk(HatBoatEntity current){
        var at=BlockPos.containing(current.position().add(0,-.12,0));var fluid=level().getFluidState(at);
        double flow=Math.min(1,fluid.getFlow(level(),at).horizontalDistance()/.12);
        double tired=1-Math.clamp(actor.life().energy()/100.0,0,1);
        double fatigue=Math.min(1,actor.waterDistress().immersionTicks()/240.0);
        double novice=!actor.family().adult()&&!actor.aquaticLife().firstVoyage().completed()?.02:0;
        return (float)Math.clamp(.01+.09*tired+.06*fatigue+.08*flow+novice,.01,.28);
    }
    private void swimToward(HatBoatEntity current,Vec3 target){
        if(!actor.life().spendEnergy(.018f))return;
        var direction=target.subtract(actor.position()).multiply(1,0,1).normalize();
        var step=direction.scale(.10);var box=actor.getBoundingBox().move(step);
        if(!level().noCollision(actor,box)){
            var radial=actor.position().subtract(current.position()).multiply(1,0,1).normalize();
            direction=new Vec3(-radial.z,0,radial.x).scale((actor.getUUID().hashCode()&1)==0?1:-1);
        }
        var at=actor.position().add(direction.scale(.15));var water=BlockPos.containing(at.x,actor.getY(),at.z);
        if(!level().hasChunkAt(water)||!level().getWorldBorder().isWithinBounds(water))return;
        var velocity=actor.getDeltaMovement();
        if(velocity.horizontalDistanceSqr()<.08*.08){actor.setDeltaMovement(velocity.add(direction.scale(.010)));actor.hasImpulse=true;}
    }
    private void slip(){
        AquaDiag.log(actor,"self.slip."+actor.getId(),0,"phase="+phase+" line="+StackWalker.getInstance().walk(f->f.skip(1).findFirst().map(StackWalker.StackFrame::getLineNumber).orElse(-1)));
        var current=hat();if(current!=null){current.releaseSelfRescueCarry();if(!actor.family().selfClimbActive())current.releaseOwnBoardingSeat(actor);}
        if(phase!=Phase.SLIPPED)actor.showReaction("teary","self_rescue_slip",24);
        carriedPose=previousCarriedPose=null;
        contact=rightTicks=attemptTicks=0;lastGrip=lockedGripLocal=null;set(Phase.SLIPPED);
    }
    public void stop(){
        var current=hat();if(current!=null){current.releaseSelfRescueCarry();if(!actor.family().selfClimbActive())current.releaseOwnBoardingSeat(actor);}
        phase=Phase.IDLE;hatId=null;contact=rightTicks=attemptTicks=0;lastGrip=lockedGripLocal=null;deployment=null;removalProgress=0;
        carriedPose=previousCarriedPose=null;poseTick=Long.MIN_VALUE;
        poseYaw=previousPoseYaw=Float.NaN;
    }
    public CompoundTag syncState(){
        var tag=new CompoundTag();tag.putString("Phase",phase.name());tag.putLong("Start",started);tag.putInt("HatId",targetHatId());
        tag.putFloat("Grip",gripProgress());tag.putString("Activity",activity());
        tag.putBoolean("Planned",deployment!=null);
        tag.putFloat("RemovalProgress",removalProgress);tag.putInt("RemoveTicks",SelfRescueHatGeometry.REMOVE_TICKS);
        // Index of the current body push (0-2) while the upside-down hat is being righted; Pushing is false during the rest.
        tag.putInt("PushIndex",phase==Phase.RIGHT_HAT?pushIndex():0);tag.putBoolean("Pushing",phase==Phase.RIGHT_HAT&&rightTicks%PUSH_TICKS<PUSH_ON);
        tag.putBoolean("Bite",phase==Phase.GRIP&&contact>0||phase==Phase.RIGHT_HAT);
        if(deployment!=null){
            tag.putDouble("DeployX",deployment.position().x);tag.putDouble("DeployY",deployment.position().y);tag.putDouble("DeployZ",deployment.position().z);
            tag.putFloat("DeployYaw",deployment.yaw());
            tag.putFloat("RemovalYaw",deployment.path().bodyYaw());
            vector(tag,"RemovalFeet",deployment.path().feet());
        }
        if(carriedPose!=null&&actor.life().hasAccessory()&&(phase==Phase.REMOVE_HAT||phase==Phase.SLIPPED)){
            tag.putBoolean("HatPose",true);hatPose(tag,"Hat",carriedPose);hatPose(tag,"PrevHat",previousCarriedPose!=null?previousCarriedPose:carriedPose);
        }
        if(active()&&phase!=Phase.CLIMB&&phase!=Phase.SAFE){
            tag.putFloat("BodyYaw",Float.isFinite(poseYaw)?poseYaw:actor.yBodyRot);
            tag.putFloat("PrevBodyYaw",Float.isFinite(previousPoseYaw)?previousPoseYaw:actor.yBodyRot);
        }
        if(lockedGripLocal!=null){
            tag.putBoolean("Locked",true);tag.putDouble("GripLocalX",lockedGripLocal.x);tag.putDouble("GripLocalY",lockedGripLocal.y);tag.putDouble("GripLocalZ",lockedGripLocal.z);
        }
        return tag;
    }
    private static void vector(CompoundTag tag,String key,Vec3 at){tag.putDouble(key+"X",at.x);tag.putDouble(key+"Y",at.y);tag.putDouble(key+"Z",at.z);}
    private static void hatPose(CompoundTag tag,String key,SelfRescueHatGeometry.Pose pose){
        vector(tag,key,pose.origin());var q=pose.rotation();tag.putFloat(key+"QX",q.x);tag.putFloat(key+"QY",q.y);tag.putFloat(key+"QZ",q.z);tag.putFloat(key+"QW",q.w);tag.putFloat(key+"Squash",pose.squash());
    }
    public CompoundTag save(){var tag=new CompoundTag();if(hatId!=null)tag.putUUID("Hat",hatId);tag.putLong("RetryAt",nextAttempt);return tag;}
    public void load(CompoundTag tag){
        // Reload never reconstructs a grip or replays removal; the persisted physical hat/token remain authoritative.
        phase=Phase.IDLE;hatId=null;contact=rightTicks=attemptTicks=0;lastGrip=lockedGripLocal=null;lastEscape=Long.MIN_VALUE;
        deployment=null;removalProgress=0;
        carriedPose=previousCarriedPose=null;poseTick=Long.MIN_VALUE;
        poseYaw=previousPoseYaw=Float.NaN;
        nextAttempt=Math.max(now()+12,tag.getLong("RetryAt"));
    }
}
