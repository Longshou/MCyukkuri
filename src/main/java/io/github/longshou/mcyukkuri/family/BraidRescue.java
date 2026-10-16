package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatRoute;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** One child's continuous contact with one real parent's existing braid. No free-flight rescue. */
public final class BraidRescue {
    public enum Phase { IDLE, REACH, CONTACT, GRIP, LIFT, PLACE, SETTLE, RELEASE }
    private final YukkuriEntity child;
    private Phase phase=Phase.IDLE;
    private UUID parentId, hatId, abandonedHat;
    private Vec3 seat, pickup, placement, tip, previousTip, joint, previousJoint, gripOffset, fromDirection, carryDirection;
    private Vec3 contactTip,liftTip,carryOffset,transferFrom,transferTo,releaseTip;
    private double transferSide,rootBlend,previousRootBlend;
    private TransferPlan planned;
    private double contactHeight,carryHeight,settleHeight;
    private Vec3 lastVelocity=Vec3.ZERO;
    private long start, lastTick=Long.MIN_VALUE;
    private float parentYaw;
    private int contactTicks, totalTicks, placeTicks, motionTicks;
    private boolean placing;
    private record RescuePair(Vec3 youngSeat,Vec3 parentStand,double cost){}
    private record TransferPlan(double height,Vec3 lift,Vec3 offset,Vec3 from,Vec3 to,double side){}
    public BraidRescue(YukkuriEntity child){this.child=child;}
    private ServerLevel level(){return (ServerLevel)child.level();}
    private YukkuriEntity parent(){return parentId!=null&&level().getEntity(parentId) instanceof YukkuriEntity y?y:null;}
    private HatBoatEntity hat(){return hatId!=null&&level().getEntity(hatId) instanceof HatBoatEntity h?h:null;}
    public boolean active(){return phase!=Phase.IDLE;}
    public boolean held(){return phase==Phase.GRIP||phase==Phase.LIFT||phase==Phase.PLACE||phase==Phase.SETTLE||phase==Phase.RELEASE;}
    public UUID parentId(){return parentId;}
    public UUID childId(){return child.getUUID();}
    private void phase(Phase next){phase=next;start=level().getGameTime();}
    private boolean eligible(YukkuriEntity parent,HatBoatEntity hat){
        return parent!=null&&parent.isAlive()&&child.isAlive()&&parent.level()==child.level()
                &&!parent.isNoAi()&&!parent.isCommandPlaying()&&!parent.operations().active()&&!parent.isOnFire()
                &&parent.life().mealTicks()==0&&!parent.life().isSleeping()&&!parent.family().laboring()
                &&!child.isCommandPlaying()&&!child.operations().active()&&!child.isOnFire()
                &&hat!=null&&hat.isAfloat()&&!hat.unsteadyMotion()&&!hat.towed()&&hat.flooding()==0
                &&hat.young(child)&&hat.protects(parent)&&BraidRescueGeometry.canLift(parent,child);
    }
    public boolean start(HatBoatEntity home){
        if(child.level().isClientSide)return false;
        if(active())return home.getUUID().equals(hatId);
        var parent=level().getEntity(home.ownerYukkuri()) instanceof YukkuriEntity y?y:null;
        if(!eligible(parent,home)||child.isPassenger()||child.family().flying()||!child.waterDistress().canBeLifted()
                ||!child.isInWaterOrBubble()&&!child.onGround()||!parent.hasLineOfSight(child)
                ||parent.family().braidRescueActive())return false;
        var grip=BraidRescueGeometry.grip(child);var candidates=new ArrayList<>(home.rescueOwnerCandidates(parent));
        candidates.addFirst(home.toLocal(parent.position()));
        var stands=new ArrayList<Vec3>();
        for(var candidate:candidates){var refined=BraidRescueGeometry.stand(parent,home,grip,candidate);if(refined!=null)stands.add(refined);}
        stands.sort(Comparator.comparingDouble(at->home.toWorld(at).distanceToSqr(parent.position())));
        Vec3 selected=null;
        for(var at:stands.stream().limit(16).toList())if(home.moveRescueOwner(parent,at)){selected=at;break;}
        if(selected==null)return false;
        var pair=reservePlacement(parent,home,grip.subtract(child.position()));
        if(pair==null){home.releaseRescueOwner(parent);return false;}
        if(!parent.life().spendEnergy(.15f)){home.cancelIncoming(child);home.releaseRescueOwner(parent);return false;}
        parentId=parent.getUUID();hatId=home.getUUID();seat=pair.youngSeat();pickup=selected;placement=pair.parentStand();
        parentYaw=parent.yBodyRot;parent.family().rescueTarget(child.getUUID());
        rootBlend=previousRootBlend=0;tip=previousTip=BraidRescueGeometry.restTip(parent,0);gripOffset=null;
        fromDirection=tip.subtract(BraidRescueGeometry.root(parent,0)).normalize();carryDirection=null;
        updateJoint(parent);previousJoint=joint;
        contactTicks=totalTicks=placeTicks=0;placing=false;lastVelocity=Vec3.ZERO;
        child.life().wakeInHat();parent.life().wakeInHat();home.holdPosition();phase(Phase.REACH);
        parent.speak("braid_rescue_offer");return true;
    }
    /** Actual boat-centre target from finite braid reach and measured supported parent stands. */
    public Vec3 approach(HatBoatEntity home){
        if(child.level().isClientSide)return null;
        var parent=level().getEntity(home.ownerYukkuri()) instanceof YukkuriEntity y?y:null;
        if(!eligible(parent,home)||child.isPassenger())return null;
        var candidates=home.rescueOwnerCandidates(parent);if(candidates.isEmpty())return null;
        var centers=new ArrayList<Vec3>();centers.add(home.position());
        double radius=home.getBbWidth()*.5+child.getBbWidth()*.5+.03;
        for(int ring=0;ring<3;ring++)for(int i=0;i<16;i++){
            double angle=i*Math.PI/8,reach=radius+ring*.10;
            centers.add(new Vec3(child.getX()+Math.cos(angle)*reach,home.getY(),child.getZ()+Math.sin(angle)*reach));
        }
        centers.sort(Comparator.comparingDouble(at->at.distanceToSqr(home.position())));
        var grip=BraidRescueGeometry.grip(child);
        for(var center:centers){
            var cell=BlockPos.containing(center.x,center.y-.12,center.z);
            if(!HatBoatRoute.calmSurface(level(),cell)||!level().getWorldBorder().isWithinBounds(cell))continue;
            var delta=center.subtract(home.position());
            if(!level().noCollision(home,home.getBoundingBox().move(delta)))continue;
            for(var candidate:candidates)if(BraidRescueGeometry.stand(parent,home,grip.subtract(delta),candidate)!=null)return center;
        }
        return null;
    }
    public void tick(){
        if(child.level().isClientSide)return;
        cleanupAbandoned();
        long now=level().getGameTime();if(lastTick==now)return;lastTick=now;
        if(!active())return;
        var parent=parent();var home=hat();
        if(!eligible(parent,home)||!child.getUUID().equals(parent.family().rescueTarget())
                ||child.isPassenger()&&!(phase==Phase.RELEASE&&child.getVehicle()==home)
                ||child.family().flying()||++totalTicks>260){cancel();return;}
        parent.setYRot(parentYaw);parent.yBodyRot=parentYaw;
        parent.getLookControl().setLookAt(child,35,35);parent.getNavigation().stop();home.holdPosition();
        previousTip=tip;previousJoint=joint;previousRootBlend=rootBlend;long elapsed=now-start;
        if(phase!=Phase.RELEASE)rootBlend=Math.min(1,rootBlend+1.0/12);
        if(held()&&!parent.life().spendEnergy(.035f)){cancel();return;}
        if(phase==Phase.REACH||phase==Phase.CONTACT){
            var target=BraidRescueGeometry.grip(child);
            var root=BraidRescueGeometry.root(parent,rootBlend);
            var direction=target.subtract(root).normalize();
            var rest=BraidRescueGeometry.restTip(parent,rootBlend);
            tip=rest.lerp(target,HatBoarding.smooth((double)elapsed/BraidRescueGeometry.ticks("REACH")));
            tip=constrainReach(parent,root,tip);updateJoint(parent);
            if(!braidClear(parent,tip)||!parent.hasLineOfSight(child)){cancel();return;}
            boolean touching=rootBlend>=1&&home.rescueOwnerReady(parent)&&BraidRescueGeometry.touches(child,tip);
            if(phase==Phase.REACH){
                if(touching){fromDirection=direction;phase(Phase.CONTACT);contactTicks=0;}
            }else if(!touching){fromDirection=tip.subtract(BraidRescueGeometry.root(parent)).normalize();phase(Phase.REACH);contactTicks=0;}
            else if(++contactTicks>=BraidRescueGeometry.ticks("CONTACT")){
                gripOffset=tip.subtract(child.position());
                // The real grip may differ slightly after the child floated during reach.
                // Re-select both bodies together, rather than forcing yesterday's nearest seat.
                var pair=reservePlacement(parent,home,gripOffset);
                if(pair==null){cancel();return;}
                seat=pair.youngSeat();placement=pair.parentStand();
                child.waterDistress().lifted();child.getNavigation().stop();
                fromDirection=tip.subtract(BraidRescueGeometry.root(parent)).normalize();
                contactTip=tip;contactHeight=tip.y;carryHeight=planned.height();liftTip=planned.lift();
                carryOffset=planned.offset();transferFrom=planned.from();transferTo=planned.to();transferSide=planned.side();
                phase(Phase.GRIP);child.speak("braid_rescue_grip");
            }
            return;
        }
        if(phase==Phase.GRIP){
            tip=contactTip;updateJoint(parent);
            if(!moveHeld(parent,home,tip)){cancel();return;}
            if(elapsed>=BraidRescueGeometry.ticks("GRIP")){
                motionTicks=Math.max(BraidRescueGeometry.ticks("LIFT"),(int)Math.ceil(Math.abs(carryHeight-contactHeight)/.015));phase(Phase.LIFT);
            }
        }else if(phase==Phase.LIFT){
            tip=contactTip.lerp(liftTip,BraidRescueGeometry.effortProgress((double)elapsed/motionTicks));updateJoint(parent);
            if(!moveHeld(parent,home,tip)){cancel();return;}
            if(elapsed>=motionTicks){
                if(!home.moveRescueOwner(parent,placement)){cancel();return;}
                placing=false;placeTicks=0;phase(Phase.PLACE);
            }
        }else if(phase==Phase.PLACE){
            var destination=home.toWorld(seat).add(gripOffset);
            if(!placing&&home.rescueOwnerReady(parent)){
                placing=true;placeTicks=0;start=now;
                transferFrom=tip;transferTo=new Vec3(destination.x,carryHeight,destination.z);
                motionTicks=Math.max(BraidRescueGeometry.ticks("PLACE"),(int)Math.ceil((transferFrom.distanceTo(transferTo)+Math.abs(transferSide)*2)/.02));
            }
            tip=placing?BraidRescueGeometry.transferPoint(transferFrom,transferTo,transferSide,carryHeight,
                    BraidRescueGeometry.effortProgress((double)++placeTicks/motionTicks)):parent.position().add(carryOffset);
            updateJoint(parent);
            if(!moveHeld(parent,home,tip)){cancel();return;}
            if(placing&&placeTicks>=motionTicks){
                releaseTip=tip;settleHeight=destination.y;
                motionTicks=Math.max(BraidRescueGeometry.ticks("SETTLE"),(int)Math.ceil(Math.abs(carryHeight-settleHeight)/.025));phase(Phase.SETTLE);
            }
        }else if(phase==Phase.SETTLE){
            var destination=home.toWorld(seat).add(gripOffset);
            tip=releaseTip.lerp(destination,HatBoarding.smooth(((double)elapsed/motionTicks-.15)/.85));updateJoint(parent);
            if(!moveHeld(parent,home,tip)){cancel();return;}
            if(elapsed>=motionTicks){
                var reserved=home.peekSeat(child);
                if(reserved==null||reserved.distanceToSqr(seat)>.000001||child.position().distanceToSqr(home.toWorld(seat))>.0001
                        ||!home.boardYoung(child)){cancel();return;}
                phase(Phase.RELEASE);
                releaseTip=tip;
            }
        }else if(phase==Phase.RELEASE){
            if(!home.protects(child)){cancel();return;}
            double q=Math.clamp((double)elapsed/BraidRescueGeometry.ticks("RELEASE"),0,1);
            rootBlend=1-HatBoarding.smooth((q-.5)*2);
            tip=q<=.5?child.position().add(gripOffset):constrainReach(parent,BraidRescueGeometry.root(parent,rootBlend),
                    releaseTip.lerp(BraidRescueGeometry.restTip(parent,rootBlend),HatBoarding.smooth((q-.5)*2)));
            updateJoint(parent);
            if(elapsed>=BraidRescueGeometry.ticks("RELEASE")){
                parent.speak("braid_rescue_safe");finish(parent,home);
            }
        }
    }
    private RescuePair reservePlacement(YukkuriEntity parent,HatBoatEntity home,Vec3 offset){
        var youngSeats=home.rescueYoungCandidates(child);if(youngSeats.isEmpty())return null;
        var ownerStands=home.rescueOwnerCandidates(parent,child);var pairs=new ArrayList<RescuePair>();
        ownerStands=new ArrayList<>(ownerStands);ownerStands.addFirst(home.toLocal(parent.position()));
        youngSeats=new ArrayList<>(youngSeats);
        youngSeats.sort(Comparator.comparingDouble(at->home.toWorld(at).distanceToSqr(child.position())));
        // Cheap fixed-length and body tests first. A bounded shortlist then pays for
        // real walking routes; a geometrically free but unreachable default is not final.
        int feasibleSeats=0;
        for(var youngSeat:youngSeats){
            var seatPairs=new ArrayList<RescuePair>();
            var target=home.toWorld(youngSeat).add(offset);
            double clearance=BraidRescueGeometry.rimClearance(child,home,home.toWorld(youngSeat))+offset.y;
            var childBox=child.getBoundingBox().move(home.toWorld(youngSeat).subtract(child.position()));
            for(var candidate:ownerStands){
                var refined=BraidRescueGeometry.stand(parent,home,target,candidate);if(refined==null)continue;
                var parentBox=BraidRescueGeometry.parentBody(parent,home.toWorld(refined));
                if(parentBox.deflate(.005).intersects(childBox)||!home.canPlaceRescuePair(child,parent,youngSeat,refined))continue;
                if(Math.abs(clearance-BraidRescueGeometry.rootAt(parent,home.toWorld(refined)).y)>=BraidRescueGeometry.length(parent))continue;
                boolean duplicate=seatPairs.stream().anyMatch(pair->pair.parentStand().distanceToSqr(refined)<.000001);
                if(duplicate)continue;
                double cost=home.toWorld(youngSeat).distanceToSqr(child.position())
                        +Math.max(0,clearance-child.getY()-offset.y)*2+home.toWorld(refined).distanceToSqr(parent.position())*.5;
                seatPairs.add(new RescuePair(youngSeat,refined,cost));
            }
            // Preserve alternatives for several child seats, rather than letting
            // many stances around the first seat consume every route attempt.
            seatPairs.sort(Comparator.comparingDouble(RescuePair::cost));
            pairs.addAll(seatPairs.stream().limit(2).toList());
            if(!seatPairs.isEmpty()&&++feasibleSeats>=8)break;
        }
        pairs.sort(Comparator.comparingDouble(RescuePair::cost));
        for(var pair:pairs.stream().limit(16).toList()){
            var plan=phase==Phase.CONTACT?planTransfer(parent,home,pair,offset):null;
            if(phase==Phase.CONTACT&&plan==null)continue;
            if(home.reserveRescuePair(child,parent,pair.youngSeat(),pair.parentStand())){planned=plan;return pair;}
        }
        return null;
    }
    /** Plan the whole supported carry before establishing custody, including the child's real worn hat. */
    private TransferPlan planTransfer(YukkuriEntity parent,HatBoatEntity home,RescuePair pair,Vec3 offset){
        var route=home.rescueOwnerRoute(parent,pair.parentStand(),child,pair.youngSeat());
        var endParent=home.toWorld(pair.parentStand());
        if(route.isEmpty()&&endParent.distanceToSqr(parent.position())>.0004)return null;
        var destination=home.toWorld(pair.youngSeat()).add(offset);var initial=child.position().add(offset);
        var displacement=endParent.subtract(parent.position());
        double width=Math.max(child.getBbWidth(),HatVessel.BRIM[0]*HatVessel.hatScale(child)*child.getScale()/8);
        for(double side:new double[]{0,width*.55,-width*.55}){
            double height=Math.max(initial.y,destination.y);
            for(int i=0;i<=24;i++){
                var p=BraidRescueGeometry.transferPoint(initial.add(displacement),destination,side,height,i/24.0);
                height=Math.max(height,BraidRescueGeometry.envelopeClearance(child,home,p.subtract(offset))+offset.y);
            }
            height+=.008*child.growthScale()*child.getScale();
            var lifted=new Vec3(initial.x,height,initial.z);var carry=lifted.subtract(parent.position());
            var transferStart=endParent.add(carry);var transferEnd=new Vec3(destination.x,height,destination.z);
            boolean clear=true;
            for(int i=0;i<=24&&clear;i++){
                var liftedAt=initial.lerp(lifted,BraidRescueGeometry.effortProgress(i/24.0));
                var transferAt=BraidRescueGeometry.transferPoint(transferStart,transferEnd,side,height,BraidRescueGeometry.effortProgress(i/24.0));
                var settleAt=transferEnd.lerp(destination,HatBoarding.smooth(i/24.0));
                clear=plannedContactClear(parent,home,liftedAt,offset,parent.position())
                        &&plannedContactClear(parent,home,transferAt,offset,endParent)
                        &&plannedContactClear(parent,home,settleAt,offset,endParent);
            }
            Vec3 previous=parent.position();
            for(var local:route){
                var next=home.toWorld(local);int steps=Math.max(1,(int)Math.ceil(previous.distanceTo(next)/.025));
                for(int i=1;i<=steps&&clear;i++){
                    var feet=previous.lerp(next,(double)i/steps);
                    clear=plannedContactClear(parent,home,feet.add(carry),offset,feet);
                }
                if(!clear)break;previous=next;
            }
            if(clear)return new TransferPlan(height,lifted,carry,transferStart,transferEnd,side);
        }
        return null;
    }
    private boolean plannedContactClear(YukkuriEntity parent,HatBoatEntity home,Vec3 at,Vec3 offset,Vec3 parentFeet){
        var root=BraidRescueGeometry.rootAt(parent,parentFeet);
        if(!BraidRescueGeometry.reachable(parent,root,at))return false;
        var hint=BraidRescueGeometry.restJoint(parent,1).add(parentFeet.subtract(parent.position()));
        var bend=BraidRescueGeometry.joint(parent,root,at,hint);
        return bend!=null&&segmentClear(parent,root,bend)&&segmentClear(parent,bend,at)
                &&bodyClear(home,at.subtract(offset),parent,parentFeet);
    }
    private Vec3 constrainReach(YukkuriEntity parent,Vec3 root,Vec3 wanted){
        var delta=wanted.subtract(root);if(delta.lengthSqr()<1e-10)return BraidRescueGeometry.restTip(parent,rootBlend);
        return root.add(delta.normalize().scale(Math.clamp(delta.length(),BraidRescueGeometry.minimumReach(parent),BraidRescueGeometry.maximumReach(parent))));
    }
    private void updateJoint(YukkuriEntity parent){
        var root=BraidRescueGeometry.root(parent,rootBlend);var rest=BraidRescueGeometry.restJoint(parent,rootBlend);
        var hint=previousJoint==null?rest:previousJoint.lerp(rest,.2);
        joint=BraidRescueGeometry.joint(parent,root,tip,hint);
    }
    private boolean braidClear(YukkuriEntity parent,Vec3 at){
        var origin=BraidRescueGeometry.root(parent,rootBlend);
        return joint!=null&&segmentClear(parent,origin,joint)&&segmentClear(parent,joint,at);
    }
    private boolean segmentClear(YukkuriEntity parent,Vec3 from,Vec3 to){
        var hit=level().clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,parent));
        return hit.getType()==HitResult.Type.MISS||hit.getLocation().distanceToSqr(to)<.0004;
    }
    private boolean moveHeld(YukkuriEntity parent,HatBoatEntity home,Vec3 at){
        if(at==null)return false;
        var wanted=at.subtract(gripOffset);var delta=wanted.subtract(child.position());
        if(delta.lengthSqr()>.12*.12||!BraidRescueGeometry.reachable(parent,BraidRescueGeometry.root(parent,rootBlend),at)||!braidClear(parent,at))return false;
        int steps=Math.max(1,(int)Math.ceil(delta.length()/.025));
        for(int i=1;i<=steps;i++)if(!bodyClear(home,child.position().lerp(wanted,(double)i/steps)))return false;
        // The hollow destination hat's coarse solid entity AABB is replaced here by its
        // measured vessel surfaces. All blocks and other bodies still constrain this sweep.
        // This bounded contact constraint follows the real tip, never a destination teleport.
        child.setPos(wanted.x,wanted.y,wanted.z);child.setDeltaMovement(Vec3.ZERO);
        child.getNavigation().stop();child.fallDistance=0;child.hasImpulse=true;lastVelocity=delta;return true;
    }
    private boolean bodyClear(HatBoatEntity home,Vec3 feet){return bodyClear(home,feet,parent(),parent().position());}
    private boolean bodyClear(HatBoatEntity home,Vec3 feet,YukkuriEntity parent,Vec3 parentFeet){
        var boxes=BraidRescueGeometry.envelope(child,feet);
        var parentBox=BraidRescueGeometry.parentBody(parent,parentFeet);
        if(!level().getWorldBorder().isWithinBounds(BlockPos.containing(feet))
                ||BraidRescueGeometry.envelopeClearance(child,home,feet)>feet.y+.002)return false;
        for(var box:boxes){
            if(box.deflate(.002).intersects(parentBox)||level().getBlockCollisions(child,box.deflate(.002)).iterator().hasNext())return false;
            for(var other:level().getEntities(child,box)){
                if(other==home||other==parent||!other.isAlive()||other.isSpectator())continue;
                if(other instanceof YukkuriEntity y){
                    for(var shape:BraidRescueGeometry.envelope(y,y.position()))if(box.deflate(.002).intersects(shape))return false;
                }else if(other.canBeCollidedWith()&&box.deflate(.002).intersects(other.getBoundingBox()))return false;
            }
        }
        return true;
    }
    private void finish(YukkuriEntity parent,HatBoatEntity home){
        phase=Phase.IDLE;home.cancelIncoming(child);parent.family().rescueTarget(null);home.releaseRescueOwner(parent);
        parentId=hatId=null;gripOffset=null;lastVelocity=Vec3.ZERO;
    }
    public void cancel(){
        if(!active())return;
        var parent=parent();var home=hat();boolean wasHeld=held();
        phase=Phase.IDLE;
        if(home!=null){home.cancelIncoming(child);if(parent!=null)home.releaseRescueOwner(parent);}else abandonedHat=hatId;
        if(parent!=null&&child.getUUID().equals(parent.family().rescueTarget()))parent.family().rescueTarget(null);
        if(wasHeld&&!child.isPassenger()){
            child.setDeltaMovement(lastVelocity.add(0,-.02,0));child.hasImpulse=true;child.waterDistress().plunge(hatId,null);
        }
        parentId=hatId=null;gripOffset=null;lastVelocity=Vec3.ZERO;
    }
    private void cleanupAbandoned(){
        if(abandonedHat==null)return;
        for(var loaded:level().getServer().getAllLevels())if(loaded.getEntity(abandonedHat) instanceof HatBoatEntity home){home.cancelIncoming(child);abandonedHat=null;break;}
    }
    public CompoundTag state(){
        var tag=new CompoundTag();if(!active())return tag;
        var parent=parent();var home=hat();if(parent==null||home==null)return tag;
        tag.putString("Phase",phase.name());tag.putLong("Start",start);tag.putUUID("Parent",parentId);tag.putUUID("Child",child.getUUID());tag.putUUID("Hat",hatId);
        tag.putInt("ParentId",parent.getId());tag.putInt("ChildId",child.getId());tag.putInt("HatId",home.getId());tag.putFloat("Yaw",parentYaw);
        if(phase==Phase.LIFT||phase==Phase.PLACE||phase==Phase.SETTLE)tag.putInt("Duration",motionTicks);
        tag.putBoolean("Travelling",phase==Phase.PLACE&&!placing);
        tag.putDouble("RootBlend",rootBlend);tag.putDouble("PrevRootBlend",previousRootBlend);
        int phaseDuration=phase==Phase.LIFT||phase==Phase.PLACE||phase==Phase.SETTLE?Math.max(1,motionTicks):BraidRescueGeometry.ticks(phase.name());
        double q=Math.clamp((double)(level().getGameTime()-start)/phaseDuration,0,1);
        tag.putDouble("Progress",phase==Phase.LIFT||phase==Phase.PLACE?BraidRescueGeometry.effortProgress(q):q);
        tag.putDouble("Effort",phase==Phase.LIFT||phase==Phase.PLACE&&placing?HatBoarding.pullEffort(q):0);
        tag.putBoolean("Gripped",held()&&(phase!=Phase.RELEASE||level().getGameTime()-start<=BraidRescueGeometry.ticks("RELEASE")/2));
        put(tag,"Tip",tip);put(tag,"PrevTip",previousTip);put(tag,"Joint",joint);put(tag,"PrevJoint",previousJoint);put(tag,"Grip",gripOffset);return tag;
    }
    private static void put(CompoundTag tag,String name,Vec3 at){if(at!=null){tag.putDouble(name+"X",at.x);tag.putDouble(name+"Y",at.y);tag.putDouble(name+"Z",at.z);}}
    public CompoundTag save(){var tag=new CompoundTag();if(hatId!=null)tag.putUUID("ReservedHat",hatId);else if(abandonedHat!=null)tag.putUUID("ReservedHat",abandonedHat);return tag;}
    public void load(CompoundTag tag){
        // Loading never recreates a grip or moves either actor. The last physical position falls normally.
        phase=Phase.IDLE;parentId=hatId=null;abandonedHat=tag.hasUUID("ReservedHat")?tag.getUUID("ReservedHat"):null;
        lastTick=Long.MIN_VALUE;gripOffset=null;lastVelocity=Vec3.ZERO;
    }
}
