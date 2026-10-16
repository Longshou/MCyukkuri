package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Finite physical attempts to recover one's own hat; immersion damage remains entirely in YukkuriLife. */
public final class AquaticSelfRescue {
    public enum Phase { IDLE, REMOVE_HAT, DEPLOY_HAT, APPROACH, GRIP, RIGHT_HAT, CLIMB, SLIPPED, SAFE }
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
    private Vec3 braidTip,previousBraidTip,braidJoint,previousBraidJoint;
    private long poseTick=Long.MIN_VALUE;
    private float poseYaw=Float.NaN,previousPoseYaw=Float.NaN;
    public AquaticSelfRescue(YukkuriEntity actor){this.actor=actor;}
    private ServerLevel level(){return (ServerLevel)actor.level();}
    private long now(){return level().getGameTime();}
    public Phase phase(){return phase;}
    public long phaseStart(){return started;}
    public boolean active(){return phase!=Phase.IDLE;}
    public int targetHatId(){var hat=hat();return hat==null?-1:hat.getId();}
    public float gripProgress(){return phase==Phase.RIGHT_HAT?Math.clamp(rightTicks/28f,0,1):Math.clamp(contact/8f,0,1);}
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
    private void set(Phase next){if(phase!=next){phase=next;started=now();}}
    private void pose(SelfRescueHatGeometry.Pose next){
        if(poseTick!=now()){previousCarriedPose=carriedPose;previousBraidTip=braidTip;previousBraidJoint=braidJoint;previousPoseYaw=poseYaw;poseTick=now();}
        poseYaw=actor.yBodyRot;
        carriedPose=next;if(next!=null){braidTip=next.tip();braidJoint=next.joint();}
    }
    private void gripPose(Vec3 tip){
        if(poseTick!=now()){previousBraidTip=braidTip;previousBraidJoint=braidJoint;previousPoseYaw=poseYaw;poseTick=now();}
        poseYaw=actor.yBodyRot;
        var root=SelfRescueHatGeometry.root(actor);
        if(tip==null||!io.github.longshou.mcyukkuri.family.BraidRescueGeometry.reachable(actor,root,tip)){braidTip=braidJoint=null;return;}
        braidTip=tip;var hint=actor.position().add(new Vec3(0,actor.getBbHeight()*.35,0));
        braidJoint=io.github.longshou.mcyukkuri.family.BraidRescueGeometry.joint(actor,root,tip,hint);
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
            // Before contact the hat never left the head. After contact it is already a real drifting vessel.
            if(now()-started>=20){stop();nextAttempt=Math.max(nextAttempt,now()+12);}return;
        }
        if(active()&&phase!=Phase.SLIPPED&&!capable()){slip();return;}
        if(!actor.isInWaterOrBubble()||actor.isPassenger()){stop();return;}
        if(active()&&phase!=Phase.REMOVE_HAT&&phase!=Phase.SLIPPED&&(current==null||!current.ownsPhysicalHat(actor))){stop();nextAttempt=now()+20;}
    }
    /** Called by WaterDistress's existing escape goal after impact; it never suppresses the water clock or damage. */
    public boolean escapeTick(){
        if(!enabled()||!capable()||!actor.isInWaterOrBubble()||actor.isPassenger()||actor.family().flying())return false;
        if(lastEscape==now())return active();lastEscape=now();
        if(actor.family().selfClimbActive()){set(Phase.CLIMB);return true;}
        if(phase==Phase.IDLE){
            if(now()<nextAttempt||actor.life().energy()<8)return false;
            var own=HatBoatEntity.owned(actor);
            if(own!=null&&own.ownsPhysicalHat(actor)&&actor.distanceToSqr(own)<=64){hatId=own.getUUID();set(Phase.APPROACH);}
            else if(actor.life().hasAccessory()&&actor.subspecies().boatId()==null){
                deployment=HatBoatEntity.planSelfRescueDeployment(actor);
                if(deployment==null){nextAttempt=now()+20;return false;}
                hatId=null;removalProgress=0;carriedPose=previousCarriedPose=null;set(Phase.REMOVE_HAT);
            }
            else return false;
            attemptTicks=contact=rightTicks=0;lastGrip=lockedGripLocal=null;actor.showReaction("teary","self_rescue_hat",36);
        }
        if(++attemptTicks>240){slip();nextAttempt=now()+40;return false;}
        actor.life().wakeInHat();actor.getNavigation().stop();
        if(phase==Phase.SLIPPED){
            if(now()-started>=20){set(Phase.IDLE);nextAttempt=Math.max(nextAttempt,now()+12);}return false;
        }
        if(phase==Phase.REMOVE_HAT){
            var held=hat();
            if(deployment==null||held==null&&!actor.life().hasAccessory()
                    ||held!=null&&(!held.ownsPhysicalHat(actor)||!held.selfRescueHeld())){slip();return false;}
            float elapsed=Math.min(SelfRescueHatGeometry.REMOVE_TICKS,now()-started);
            removalProgress=elapsed/SelfRescueHatGeometry.REMOVE_TICKS;
            // The selected action has a stable neutral root. Water buoyancy/damage are never suspended.
            actor.yBodyRot=deployment.path().bodyYaw();actor.setYRot(actor.yBodyRot);actor.setYHeadRot(actor.yBodyRot);
            var velocity=actor.getDeltaMovement();actor.setDeltaMovement(velocity.x*.6,velocity.y,velocity.z*.6);
            var next=SelfRescueHatGeometry.pose(actor,deployment.path(),elapsed);
            if(!SelfRescueHatGeometry.valid(actor,deployment.path(),next,carriedPose,elapsed>=SelfRescueHatGeometry.REACH)){slip();return false;}
            pose(next);
            if(elapsed>=SelfRescueHatGeometry.REACH+SelfRescueHatGeometry.CONTACT){
                if(held==null){
                    if(!actor.life().spendEnergy(.35f)){slip();return false;}
                    held=HatBoatEntity.deployForSelfRescue(actor,deployment,next,elapsed);
                    if(held==null||!held.selfRescueHeld()){slip();return false;}
                    hatId=held.getUUID();nextGripCheck=now()+8;
                }else if(!actor.life().spendEnergy(.012f)||!held.setSelfRescueCarryPose(actor,next,elapsed)){slip();return false;}
                if(now()>=nextGripCheck){nextGripCheck=now()+8;if(actor.getRandom().nextFloat()<slipRisk(held)){slip();return false;}}
            }
            if(elapsed<SelfRescueHatGeometry.REMOVE_TICKS)return true;
            if(held==null){slip();return false;}
            set(Phase.DEPLOY_HAT);return true;
        }
        var current=hat();
        if(current==null||!current.ownsPhysicalHat(actor)||actor.distanceToSqr(current)>64){stop();nextAttempt=now()+20;return false;}
        if(phase==Phase.DEPLOY_HAT){
            // The same actual grip holds the upright hat for six settling ticks, then lets water/inertia take over.
            if(deployment!=null&&now()-started<6){
                var settled=SelfRescueHatGeometry.pose(actor,deployment.path(),SelfRescueHatGeometry.REMOVE_TICKS);
                if(!SelfRescueHatGeometry.valid(actor,deployment.path(),settled,carriedPose,true)
                        ||!current.setSelfRescueCarryPose(actor,settled,SelfRescueHatGeometry.REMOVE_TICKS)){slip();return false;}
                pose(settled);return true;
            }
            current.finishSelfRescueCarry(actor);carriedPose=previousCarriedPose=null;
            braidTip=braidJoint=null;set(Phase.APPROACH);
        }
        if(current.capsizing()||current.state()==HatBoatEntity.State.BEACHED||current.towed())return false;
        var target=current.ownGripApproach(actor);if(target==null){slip();return false;}
        // Present the real right braid to the rim. Swimming direction remains independent of this bounded turn.
        SurfaceApproach.face(actor,SurfaceApproach.yaw(actor.position(),current.position())-90);
        var grip=(phase==Phase.GRIP||phase==Phase.RIGHT_HAT)&&lockedGripLocal!=null
                ?current.selfRescueGrip(actor,lockedGripLocal):current.selfRescueGrip(actor);
        if(grip==null){
            braidTip=braidJoint=null;contact=0;rightTicks=Math.max(0,rightTicks-2);lastGrip=lockedGripLocal=null;set(Phase.APPROACH);
            swimToward(current,target);return true;
        }
        if(lastGrip!=null&&lastGrip.distanceToSqr(grip)>.10*.10){slip();return false;}
        lastGrip=grip;
        gripPose(grip);
        if(braidTip==null){swimToward(current,target);return true;}
        // Finite effort and drift control the outcome. A fast moving/turning brim cannot grant instant grip.
        if(current.getDeltaMovement().subtract(actor.getDeltaMovement()).horizontalDistanceSqr()>.10*.10){slip();return false;}
        if(phase==Phase.APPROACH){
            lockedGripLocal=current.selfRescueGripLocal(actor);if(lockedGripLocal==null)return true;
            set(Phase.GRIP);contact=0;nextGripCheck=now()+8;
        }
        if((phase==Phase.GRIP||phase==Phase.RIGHT_HAT)&&now()>=nextGripCheck){
            nextGripCheck=now()+8;
            if(actor.getRandom().nextFloat()<slipRisk(current)){slip();return false;}
        }
        if(!actor.life().spendEnergy(.025f)){slip();return false;}
        var velocity=actor.getDeltaMovement();actor.setDeltaMovement(velocity.x*.65,velocity.y,velocity.z*.65);
        if(phase==Phase.GRIP){
            if(++contact<8)return true;
            actor.speak("self_rescue_grip");
            if(current.state()==HatBoatEntity.State.CAPSIZED){rightTicks=0;set(Phase.RIGHT_HAT);}
            else if(current.isAfloat()&&!current.unsteadyMotion()){
                var water=BlockPos.containing(current.position().add(0,-.12,0));
                if(HatBoatRoute.calmSurface(level(),water)&&Math.abs(current.getY()-HatBoatRoute.surface(level(),water).y)<.02
                        &&Math.abs(current.getDeltaMovement().y)<.015)return startClimb(current);
            }
            return true;
        }
        if(phase==Phase.RIGHT_HAT){
            if(!actor.life().spendEnergy(.035f)||!current.setSelfRighting(actor,++rightTicks/28f,lockedGripLocal)){slip();return false;}
            if(rightTicks>=28){
                if(current.finishSelfRighting(actor)){contact=rightTicks=0;lastGrip=lockedGripLocal=null;set(Phase.APPROACH);return true;}
                slip();return false;
            }
        }
        return true;
    }
    private boolean startClimb(HatBoatEntity current){
        if(actor.family().climbOwnHat(current)){set(Phase.CLIMB);actor.speak("self_rescue_climb");return true;}
        slip();return false;
    }
    /** One grip check per eight contact ticks, never a per-tick failure roll or an instant success roll. */
    private float slipRisk(HatBoatEntity current){
        var at=BlockPos.containing(current.position().add(0,-.12,0));var fluid=level().getFluidState(at);
        double flow=Math.min(1,fluid.getFlow(level(),at).horizontalDistance()/.12);
        double tired=1-Math.clamp(actor.life().energy()/100.0,0,1);
        double fatigue=Math.min(1,actor.waterDistress().immersionTicks()/240.0);
        double novice=!actor.family().adult()&&!actor.aquaticLife().firstVoyage().completed()?.03:0;
        return (float)Math.clamp(.03+.09*tired+.06*fatigue+.08*flow+novice,.03,.28);
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
        var current=hat();if(current!=null){current.releaseSelfRescueCarry();if(!actor.family().selfClimbActive())current.releaseOwnBoardingSeat(actor);}
        if(phase!=Phase.SLIPPED)actor.showReaction("teary","self_rescue_slip",24);
        carriedPose=previousCarriedPose=null;braidTip=braidJoint=null;
        contact=rightTicks=attemptTicks=0;lastGrip=lockedGripLocal=null;set(Phase.SLIPPED);
    }
    public void stop(){
        var current=hat();if(current!=null){current.releaseSelfRescueCarry();if(!actor.family().selfClimbActive())current.releaseOwnBoardingSeat(actor);}
        phase=Phase.IDLE;hatId=null;contact=rightTicks=attemptTicks=0;lastGrip=lockedGripLocal=null;deployment=null;removalProgress=0;
        carriedPose=previousCarriedPose=null;braidTip=previousBraidTip=braidJoint=previousBraidJoint=null;poseTick=Long.MIN_VALUE;
        poseYaw=previousPoseYaw=Float.NaN;
    }
    public CompoundTag syncState(){
        var tag=new CompoundTag();tag.putString("Phase",phase.name());tag.putLong("Start",started);tag.putInt("HatId",targetHatId());
        tag.putFloat("Grip",gripProgress());tag.putString("Activity",activity());
        tag.putBoolean("Planned",deployment!=null);
        tag.putFloat("RemovalProgress",removalProgress);tag.putInt("RemoveTicks",SelfRescueHatGeometry.REMOVE_TICKS);
        if(deployment!=null){
            tag.putDouble("DeployX",deployment.position().x);tag.putDouble("DeployY",deployment.position().y);tag.putDouble("DeployZ",deployment.position().z);
            tag.putFloat("DeployYaw",deployment.yaw());
            tag.putFloat("RemovalYaw",deployment.path().bodyYaw());
            vector(tag,"RemovalFeet",deployment.path().feet());vector(tag,"RemovalRim",deployment.path().localRim());
            vector(tag,"FirstGrip",deployment.path().firstGrip());vector(tag,"FinalGrip",deployment.path().finalGrip());
        }
        if(carriedPose!=null&&actor.life().hasAccessory()&&(phase==Phase.REMOVE_HAT||phase==Phase.SLIPPED)){
            tag.putBoolean("HatPose",true);hatPose(tag,"Hat",carriedPose);hatPose(tag,"PrevHat",previousCarriedPose!=null?previousCarriedPose:carriedPose);
        }
        if(braidTip!=null&&braidJoint!=null&&active()&&phase!=Phase.CLIMB&&phase!=Phase.SAFE){
            tag.putBoolean("BraidContact",true);vector(tag,"Tip",braidTip);vector(tag,"PrevTip",previousBraidTip!=null?previousBraidTip:braidTip);
            vector(tag,"Joint",braidJoint);vector(tag,"PrevJoint",previousBraidJoint!=null?previousBraidJoint:braidJoint);
            tag.putFloat("BodyYaw",Float.isFinite(poseYaw)?poseYaw:actor.yBodyRot);
            tag.putFloat("PrevBodyYaw",Float.isFinite(previousPoseYaw)?previousPoseYaw:actor.yBodyRot);
            tag.putFloat("RootBlend",0);
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
        carriedPose=previousCarriedPose=null;braidTip=previousBraidTip=braidJoint=previousBraidJoint=null;poseTick=Long.MIN_VALUE;
        poseYaw=previousPoseYaw=Float.NaN;
        nextAttempt=Math.max(now()+12,tag.getLong("RetryAt"));
    }
}
