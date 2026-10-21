package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.BraidRescueGeometry;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.GripRim;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.SelfRescueDeployment;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.State;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import static io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.CAPSIZE_RELEASE_TICK;

/** Physical grips, righting and one-shot capsize release; shared seat freezing stays on the entity. */
final class HatBoatCapsize {
    private final HatBoatEntity boat;
    boolean capsizeReleased;

    HatBoatCapsize(HatBoatEntity boat) { this.boat = boat; }

    boolean ownsPhysicalHat(YukkuriEntity actor){return actor!=null&&actor.getUUID().equals(boat.ownerYukkuri())
            &&actor.life().matchesAccessory(boat.ownerYukkuri(),boat.launchToken());}

    Vec3 rimPoint(Vec3 local){return tiltedWorld(local).subtract(0,boat.rimLift(local),0);}

    Vec3 nearestGrip(YukkuriEntity actor){
        if(actor==null||!(ownsPhysicalHat(actor)||boat.young(actor))||!(boat.isAfloat()||boat.state()==State.CAPSIZED)||boat.capsizing()||boat.deploying())return null;
        Vec3 best=null;double score=Double.POSITIVE_INFINITY;
        for(var local:GripRim.points(boat.mode())){
            var point=rimPoint(local);double gap=actor.getBoundingBox().getCenter().distanceToSqr(point);
            if(gap<score){score=gap;best=point;}
        }
        return best;
    }

    Vec3 selfRescueGripLocal(YukkuriEntity actor){
        var world=selfRescueGrip(actor);if(world==null)return null;
        for(var local:GripRim.points(boat.mode()))if(rimPoint(local).distanceToSqr(world)<1e-8)return local;
        return null;
    }

    Vec3 selfRescueGrip(YukkuriEntity actor,Vec3 localGrip){
        if(localGrip==null||GripRim.points(boat.mode()).stream().noneMatch(p->p.distanceToSqr(localGrip)<1e-8)
                ||!(ownsPhysicalHat(actor)||boat.young(actor))||boat.capsizing()||boat.deploying())return null;
        return reachableGrip(actor,rimPoint(localGrip));
    }

    Vec3 selfRescueGrip(YukkuriEntity actor){
        if(nearestGrip(actor)==null)return null;
        Vec3 best=null;double score=Double.POSITIVE_INFINITY;
        var mouth=BraidRescueGeometry.mouthPoint(actor,actor.position(),actor.yBodyRot);
        for(var local:GripRim.points(boat.mode())){
            var grip=rimPoint(local);
            if(reachableGrip(actor,grip)==null)continue;
            double distance=mouth.distanceToSqr(grip);if(distance<score){best=grip;score=distance;}
        }
        return best;
    }

    Vec3 reachableGrip(YukkuriEntity actor,Vec3 grip){
        if(grip==null||!actor.getBoundingBox().inflate(.12).contains(grip))return null;
        var hit=boat.level().clip(new ClipContext(actor.getEyePosition(),grip,
                ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,actor));
        return hit.getType()==HitResult.Type.MISS?grip:null;
    }

    Vec3 ownGripApproach(YukkuriEntity actor){
        var grip=nearestGrip(actor);if(grip==null)return null;
        var out=actor.position().subtract(grip).multiply(1,0,1).normalize();
        if(out.horizontalDistanceSqr()<.01)out=grip.subtract(boat.position()).multiply(1,0,1).normalize();
        return new Vec3(grip.x+out.x*(actor.getBbWidth()*.5+.03),actor.getY(),grip.z+out.z*(actor.getBbWidth()*.5+.03));
    }

    boolean setSelfRighting(YukkuriEntity actor,float progress,Vec3 localGrip){
        if(boat.level().isClientSide||!ownsPhysicalHat(actor)||boat.state()!=State.CAPSIZED||!capsizeReleased||boat.isVehicle()||boat.hasIncoming()
                ||selfRescueGrip(actor,localGrip)==null||!HatBoatRoute.calmSurface(boat.level(),BlockPos.containing(boat.position().add(0,-.12,0))))return false;
        var m=boat.motionData().copy();float previous=m.getFloat("RightProgress");
        if(!m.contains("RightGripX")){
            HatBoatEntity.point(m,"RightGrip",localGrip);HatBoatEntity.point(m,"RightAnchor",tiltedWorld(localGrip));
            // A previously dropped hat has finished its free tumble before a new real grip is accepted.
            m.remove("LooseAt");m.remove("LoosePitch");m.remove("LooseSquash");
        }else if(HatBoatEntity.point(m,"RightGrip").distanceToSqr(localGrip)>1e-8)return false;
        m.putFloat("RightPrevious",previous);m.putFloat("RightProgress",Math.clamp(progress,0,1));m.putLong("RightTouched",boat.level().getGameTime());
        var before=boat.motionData();boat.syncMotion(m);
        var offset=tiltedWorld(localGrip).subtract(boat.position());var centre=HatBoatEntity.point(m,"RightAnchor").subtract(offset);
        var movement=centre.subtract(boat.position());
        if(movement.lengthSqr()>.12*.12||!boat.level().noCollision(boat,boat.getBoundingBox().move(movement))){boat.syncMotion(before);return false;}
        // Rotating around the actually held rim translates the same boat centre. Clients apply
        // motionRoll/Pitch at that synced centre, yielding exactly this world-space grasp pivot.
        boat.setPos(centre);boat.setDeltaMovement(Vec3.ZERO);boat.stopPaddling();return true;
    }

    boolean finishSelfRighting(YukkuriEntity actor){
        if(boat.level().isClientSide||!ownsPhysicalHat(actor)||!boat.selfRighting()||boat.motionData().getFloat("RightProgress")<1
                ||selfRescueGrip(actor,HatBoatEntity.point(boat.motionData(),"RightGrip"))==null)return false;
        boat.state(State.AFLOAT);boat.flooding=0;boat.syncMotion(new CompoundTag());capsizeReleased=true;boat.stopPaddling();
        GameplayLog.event(boat,"aquatic","hat righted actor={} state=AFLOAT flooding=0",actor.getUUID());return true;
    }

    void tickSelfRighting(){
        var m=boat.motionData();if(!boat.selfRighting()||boat.level().getGameTime()-m.getLong("RightTouched")<=1)return;
        var copy=m.copy();float progress=m.getFloat("RightProgress");copy.putFloat("RightPrevious",progress);
        copy.putFloat("RightProgress",Math.max(0,progress-.04f));
        if(progress<=.04){
            copy.remove("RightProgress");copy.remove("RightPrevious");copy.remove("RightTouched");
            for(var axis:new String[]{"X","Y","Z"}){copy.remove("RightGrip"+axis);copy.remove("RightAnchor"+axis);}
        }
        boat.syncMotion(copy);
    }

    static SelfRescueDeployment planSelfRescueDeployment(YukkuriEntity actor){
        if(!(actor.level() instanceof ServerLevel level)||!actor.profile().aquatic()||!actor.isAlive()||!actor.isInWaterOrBubble()
                ||actor.isPassenger()||actor.subspecies().boatId()!=null||!actor.life().hasAccessory())return null;
        var water=BlockPos.containing(actor.position());
        if(!HatBoatRoute.calmSurface(level,water)){AquaDiag.log(actor,"self.surface."+actor.getId(),40,"not a calm surface at "+water);return null;}
        var path=SelfRescueHatGeometry.plan(actor,HatBoatRoute.surface(level,water).y,
                candidate->selfRescueDeploymentReachable(actor,new SelfRescueDeployment(candidate.finalCentre(),candidate.boatYaw(),candidate)));
        return path==null?null:new SelfRescueDeployment(path.finalCentre(),path.boatYaw(),path);
    }

    static boolean selfRescueDeploymentReachable(YukkuriEntity actor,SelfRescueDeployment plan){
        if(plan==null||!(actor.level() instanceof ServerLevel level))return false;
        if(plan.path()==null)return false;
        var boat=ModEntities.HAT_BOAT.get().create(level);if(boat==null)return false;
        boat.syncHullScale(HatVessel.hatScale(actor)*actor.getScale());boat.setPos(plan.position());boat.setYRot(plan.yaw());
        var water=BlockPos.containing(plan.position().add(0,-.12,0));
        boolean calm=HatBoatRoute.calmSurface(level,water),inside=level.getWorldBorder().isWithinBounds(water);
        boolean level_=Math.abs(HatBoatRoute.surface(level,water).y-plan.position().y)<.08,free=level.noCollision(boat,boat.getBoundingBox());
        if(!(calm&&inside&&level_&&free))AquaDiag.log(actor,"self.spot."+actor.getId(),40,"hat spot "+AquaDiag.at(plan.position())+" calm="+calm+" bounds="+
                inside+" level="+level_+" free="+free+" box="+boat.getBoundingBox()
                +" blocks="+level.getBlockCollisions(boat,boat.getBoundingBox()).iterator().hasNext()+" entities="+level.getEntityCollisions(boat,boat.getBoundingBox()).size()
                +" near="+level.getEntities(boat,boat.getBoundingBox().inflate(.3)).stream().map(e->e.getType().toShortString()+"@"+AquaDiag.at(e.position())+
                        "/"+e.getBoundingBox().getXsize()).toList());
        return calm&&inside&&level_&&free;
    }

    static HatBoatEntity deployForSelfRescue(YukkuriEntity actor,SelfRescueDeployment plan){
        return deployForSelfRescue(actor,plan,null,SelfRescueHatGeometry.REMOVE_TICKS);
    }

    static HatBoatEntity deployForSelfRescue(YukkuriEntity actor,SelfRescueDeployment plan,SelfRescueHatGeometry.Pose heldPose,float elapsed){
        if(!(actor.level() instanceof ServerLevel level)||!actor.profile().aquatic()||!actor.isAlive()||!actor.isInWaterOrBubble()
                ||actor.isPassenger()||actor.subspecies().boatId()!=null||!actor.life().hasAccessory()||!selfRescueDeploymentReachable(actor,plan))return null;
        var water=BlockPos.containing(plan.position().add(0,-.12,0));
        if(!HatBoatRoute.calmSurface(level,water)||!level.getWorldBorder().isWithinBounds(water)
                ||Math.abs(HatBoatRoute.surface(level,water).y-plan.position().y)>.08)return null;
        var boat=ModEntities.HAT_BOAT.get().create(level);if(boat==null)return null;
        boat.syncHullScale(HatVessel.hatScale(actor)*actor.getScale());boat.launchRole(HatVessel.Role.SELF);
        boat.setPos(plan.position());boat.setYRot(plan.yaw());
        if(!level.noCollision(boat,boat.getBoundingBox()))return null;
        boat.syncOwner(Optional.of(actor.getUUID()));boat.syncAccessoryIdentity(actor);
        if(!level.addFreshEntity(boat))return null;
        var retained=actor.aquaticLife().firstVoyage().retainedHatToken();boat.launchToken(actor.life().stowAccessory(retained, boat.getUUID()));
        if(boat.launchToken()==null){boat.discard();return null;}
        actor.subspecies().boatId(boat.getUUID());actor.setPersistenceRequired();
        actor.aquaticLife().firstVoyage().restoreHatCargo(boat);actor.aquaticLife().equip(boat);
        // The held head hat has already turned and lowered to this exact upright pose. Never flip it a second time.
        boat.syncMotion(new CompoundTag());
        boat.setDeltaMovement(actor.getDeltaMovement().multiply(.65,0,.65));
        if(heldPose!=null&&!boat.setSelfRescueCarryPose(actor,heldPose,elapsed))boat.releaseSelfRescueCarry();
        return boat;
    }

    boolean setSelfRescueCarryPose(YukkuriEntity actor,SelfRescueHatGeometry.Pose pose,float elapsed){
        if(boat.level().isClientSide||!ownsPhysicalHat(actor)||boat.isVehicle()||boat.hasIncoming()||pose==null)return false;
        double q=Math.clamp((elapsed-SelfRescueHatGeometry.SHAKE)/SelfRescueHatGeometry.TURN,0,1);q=q*q*(3-2*q);
        var offset=pose.rotation().transform(new Vector3f(0,HatVessel.freeboard(boat.mode())*boat.unit(),0));
        var centre=pose.origin().subtract(new Vec3(offset.x,offset.y,offset.z));
        var m=boat.motionData().copy();
        m.putFloat("PreviousPitch",m.getBoolean("SelfHeld")?m.getFloat("HeldPitch"):(float)(-180*(1-q)));
        m.putFloat("PreviousSquash",m.getBoolean("SelfHeld")?m.getFloat("HeldSquash"):pose.squash());
        m.putBoolean("SelfHeld",true);m.putFloat("HeldPitch",(float)(-180*(1-q)));m.putFloat("HeldSquash",pose.squash());m.putLong("HeldTouched",boat.level().getGameTime());
        HatBoatEntity.point(m,"PreviousCentre",m.contains("HeldCentreX")?HatBoatEntity.point(m,"HeldCentre"):centre);HatBoatEntity.point(m,"HeldCentre",centre);
        boat.setPos(centre);boat.syncMotion(m);boat.setDeltaMovement(Vec3.ZERO);boat.stopPaddling();return true;
    }

    void finishSelfRescueCarry(YukkuriEntity actor){
        if(boat.level().isClientSide||!ownsPhysicalHat(actor)||!boat.selfRescueHeld())return;
        boat.syncMotion(new CompoundTag());boat.heldTicks=0;boat.setDeltaMovement(actor.getDeltaMovement().multiply(.65,0,.65));
    }

    void releaseSelfRescueCarry(){
        if(boat.level().isClientSide||!boat.selfRescueHeld())return;
        float pitch=boat.motionPitch(1),squash=boat.hullSquash(1);boat.syncMotion(new CompoundTag());
        startCapsize(1);var m=boat.motionData().copy();m.putLong("LooseAt",boat.level().getGameTime());
        HatBoatEntity.point(m,"HeldCentre",boat.position());HatBoatEntity.point(m,"PreviousCentre",boat.position());
        m.putFloat("LoosePitch",pitch);m.putFloat("LooseSquash",squash);boat.syncMotion(m);
    }

    void capsize() {
        int side=boat.motionData().contains("Side")?boat.motionData().getInt("Side"):((boat.getId()&1)==0?1:-1);
        startCapsize(side);
    }

    void startCapsize(int side){
        if(boat.level().isClientSide||boat.state()==State.CAPSIZED||boat.state()==State.RESCUED||boat.state()==State.REMOVED||boat.catches())return;
        boolean wasHeld=boat.selfRescueHeld();float heldPitch=wasHeld?boat.motionPitch(1):0,heldSquash=wasHeld?boat.hullSquash(1):(float)HatVessel.squash(boat.mode());
        boat.freezeSeatsForCapsize();
        var m=new CompoundTag();m.putLong("CapAt",boat.level().getGameTime());m.putInt("Side",side<0?-1:1);
        if(wasHeld){
            m.putLong("LooseAt",boat.level().getGameTime());m.putFloat("LoosePitch",heldPitch);m.putFloat("LooseSquash",heldSquash);
            HatBoatEntity.point(m,"HeldCentre",boat.position());HatBoatEntity.point(m,"PreviousCentre",boat.position());
        }
        boat.syncMotion(m);capsizeReleased=false;boat.rescuePair.rescueOwnerHeld=false;
        boat.state(State.CAPSIZED);boat.flooding=100;boat.stopPaddling();boat.heldTicks=0;boat.clearTowTicks();boat.pullTarget=null;
        boat.setDeltaMovement(boat.getDeltaMovement().scale(.25));
        GameplayLog.event(boat,"aquatic","capsize started owner={} side={} passengers={} cargo={} paddle={}",
                boat.ownerYukkuri(),side,boat.getPassengers().size(),boat.cargo().count(),boat.cargo().paddle());
    }

    Vec3 tiltedWorld(Vec3 local){
        float current=boat.selfRighting()?1:0;
        double roll=Math.toRadians(boat.motionRoll(current)),pitch=Math.toRadians(boat.motionPitch(current));
        var p=local.scale(boat.unit());
        double py=p.y*Math.cos(pitch)-p.z*Math.sin(pitch),pz=p.y*Math.sin(pitch)+p.z*Math.cos(pitch);
        return boat.position().add(new Vec3(p.x*Math.cos(roll)-py*Math.sin(roll),p.x*Math.sin(roll)+py*Math.cos(roll),pz).yRot(boat.turn()));
    }

    void tickCapsize(){
        if(boat.state()!=State.CAPSIZED||capsizeReleased||!boat.motionData().contains("CapAt")
                ||boat.motionAge(boat.motionData(),"CapAt",0)<CAPSIZE_RELEASE_TICK)return;
        // Mark before mutating/spawning: re-entry and subsequent damage cannot spill these items twice.
        capsizeReleased=true;
        int side=boat.motionData().getInt("Side")<0?-1:1;
        var downhill=new Vec3(-side,0,0).yRot(boat.turn());var along=new Vec3(0,0,1).yRot(boat.turn());
        var passengers=List.copyOf(boat.getPassengers());var positions=new ArrayList<Vec3>();
        var releasedYoung=new ArrayList<UUID>();
        for(var p:passengers)positions.add(tiltedWorld(boat.toLocal(p.position())));
        for(int i=0;i<passengers.size();i++){
            var p=passengers.get(i);var at=positions.get(i).add(downhill.scale(.035+i*.008));
            p.stopRiding();p.setPos(at.x,at.y,at.z);
            p.setDeltaMovement(boat.getDeltaMovement().add(downhill.scale(.07+i*.013))
                    .add(along.scale((i-(passengers.size()-1)*.5)*.018)).add(0,.025-i*.006,0));
            p.hasImpulse=true;
            if(p instanceof YukkuriEntity y){
                y.waterDistress().plunge(boat.getUUID(),null);
                if(boat.young(y))releasedYoung.add(y.getUUID());
            }
            if(boat.level() instanceof ServerLevel server)server.sendParticles(ParticleTypes.SPLASH,
                    at.x,at.y,at.z,Math.max(4,(int)(p.getBbWidth()*12)),p.getBbWidth()*.3,.04,p.getBbWidth()*.3,.08);
        }
        var owner=boat.owner();
        if(owner!=null&&!releasedYoung.isEmpty())owner.aquaticLife().selfRescue().onFamilyCapsize(boat,releasedYoung);
        var spillZones=boat.capsizeSpillZones();
        var drops=new ArrayList<ItemStack>(boat.cargo().portions());
        var paddle=boat.cargo().paddleItem();
        if(!paddle.isEmpty())drops.add(paddle);
        int paddleDrop=drops.size()-1;
        boat.cargo().load(new CompoundTag());boat.syncCargo(boat.cargo().save());
        for(int i=0;i<drops.size();i++){
            var local=spillZones.isEmpty()?new Vec3(0,HatVessel.ownerY(boat.mode()),0):spillZones.get(i%spillZones.size()).position();
            var at=tiltedWorld(local).add(downhill.scale(.025));
            var item=new ItemEntity(boat.level(),at.x,at.y,at.z,drops.get(i));
            item.setDeltaMovement(boat.getDeltaMovement().add(downhill.scale(.08+i*.008))
                    .add(along.scale((i-(drops.size()-1)*.5)*.014)).add(0,.03,0));
            item.setDefaultPickUpDelay();boat.level().addFreshEntity(item);
            if(!paddle.isEmpty()&&i==paddleDrop)boat.cargo().notePaddleDrop(item);
        }
        GameplayLog.event(boat,"aquatic","capsize released owner={} passengers={} young={} cargoDropAttempts={} paddle={} cargoNow={}",
                boat.ownerYukkuri(),passengers.size(),releasedYoung,drops.size(),!paddle.isEmpty(),boat.cargo().count());
    }
}
