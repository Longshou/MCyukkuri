package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** A land parent's braid lifts the existing newborn out of the resting catch hat; in boat mode the captain, riding her own
 * hull, lifts a child who cannot jump over the near brim onto the bank (startBoatLift).
 * The short, supported transfer is planned against the whole child and both real braid lengths.
 * No entity copy, passenger repositioning, gravity flag or persisted airborne custody is used. */
public final class CradlePickup {
    private enum Phase { IDLE, APPROACH, REACH, GRIP, LIFT, PLACE, LOWER, RELEASE }
    private final YukkuriEntity parent;
    private Phase phase=Phase.IDLE;
    private UUID childId,hatId;
    private Vec3 stand,endStand,from,hatAt,reachFrom,raised,across,destination,tip,joint,prevTip,prevJoint;
    private Vec3 lastVelocity=Vec3.ZERO;
    private float yaw;
    private long start,lastTick=Long.MIN_VALUE,retryAt;
    private int total;
    /** Boat mode: the parent rides the hull; her deck patch is hull-local so it follows the hull. */
    private boolean boatMode;
    private Vec3 ownerLocal;
    /** Boat mode: the lift ends on a reserved brim seat of the same hull (hull-local), not on the ground. */
    private Vec3 hullSeat;
    private record BoatPlan(Vec3 ownerLocal,float yaw,Vec3 raised,Vec3 across,Vec3 destination){}
    private record Plan(Vec3 stand,Vec3 endStand,float yaw,Vec3 raised,Vec3 across,Vec3 destination) {}

    public CradlePickup(YukkuriEntity parent){this.parent=parent;}
    private ServerLevel level(){return (ServerLevel)parent.level();}
    private YukkuriEntity child(){return childId!=null&&level().getEntity(childId) instanceof YukkuriEntity y?y:null;}
    private HatBoatEntity hat(){return hatId!=null&&level().getEntity(hatId) instanceof HatBoatEntity h?h:null;}
    public UUID childId(){return childId;}
    public boolean active(){return phase!=Phase.IDLE;}
    public boolean held(){return phase==Phase.LIFT||phase==Phase.PLACE||phase==Phase.LOWER;}
    /** False while the last refused plan is still being waited out. */
    public boolean ready(){return !active()&&level().getGameTime()>=retryAt;}

    public boolean start(HatBoatEntity home,YukkuriEntity young,Vec3 preferred){
        if(parent.level().isClientSide||active()||home==null||young==null||!home.catches()||level().getGameTime()<retryAt
                ||!parent.isAlive()||parent.isPassenger()||interrupted(parent)||interrupted(young)||home.unsteadyMotion()||parent.getVariant()!=YukkuriVariant.MARISA
                ||young.getVehicle()!=home||!young.isAlive()||young.family().adult()||!home.young(young)||!related(young)
                ||!BraidRescueGeometry.canLift(parent,young)||parent.distanceToSqr(home)>16)return false;
        retryAt=level().getGameTime()+40;
        var plan=plan(home,young,preferred);if(plan==null)return false;
        childId=young.getUUID();hatId=home.getUUID();stand=plan.stand();endStand=plan.endStand();yaw=plan.yaw();
        from=young.position();hatAt=home.position();raised=plan.raised();across=plan.across();destination=plan.destination();
        tip=prevTip=BraidRescueGeometry.restTip(parent,0);joint=prevJoint=BraidRescueGeometry.restJoint(parent,0);
        total=0;lastVelocity=Vec3.ZERO;young.family().cradleHolder(parent.getUUID());phase(Phase.APPROACH);return true;
    }
    /**
     * The captain lifts a child who cannot jump over the near brim onto dry ground at {@code landing}. She walks to the deck
     * patch (and turns to the heading) from which her fixed-length braid reaches the child, every point of the lift and the
     * landing; the child is held by the braid the whole way and set down on its feet.
     */
    public boolean startBoatLift(HatBoatEntity home,YukkuriEntity young,Vec3 landing){
        return startBoatLift(home,young,landing,null);
    }
    /** As above; with {@code seatLocal} the child is set down on that reserved brim seat of the same hull (a first stage toward the bank). */
    public boolean startBoatLift(HatBoatEntity home,YukkuriEntity young,Vec3 landing,Vec3 seatLocal){
        if(parent.level().isClientSide||active()||home==null||young==null||landing==null||level().getGameTime()<retryAt
                ||!parent.isAlive()||parent.getVehicle()!=home||interrupted(parent)||interrupted(young)||home.unsteadyMotion()||!home.isAfloat()
                ||young.getVehicle()!=home||!young.isAlive()||young.family().adult()||!home.young(young)||!related(young)
                ||!BraidRescueGeometry.canLift(parent,young))return false;
        retryAt=level().getGameTime()+30;
        var plan=planBoat(home,young,landing);
        if(plan==null){
            io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(parent,"lift.noplan."+young.getId(),40,"no deck patch reaches child="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(young.position())+" landing="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(landing));
            return false;
        }
        if(!home.moveRescueOwner(parent,plan.ownerLocal())){
            io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(parent,"lift.walkrefused."+young.getId(),40,"owner cannot walk to "+plan.ownerLocal());
            return false;
        }
        boatMode=true;hullSeat=seatLocal;ownerLocal=plan.ownerLocal();childId=young.getUUID();hatId=home.getUUID();yaw=plan.yaw();
        stand=home.toWorld(ownerLocal);endStand=stand;from=young.position();hatAt=home.position();raised=plan.raised();across=plan.across();destination=plan.destination();
        tip=prevTip=BraidRescueGeometry.restTip(parent,0);joint=prevJoint=BraidRescueGeometry.restJoint(parent,0);
        total=0;lastVelocity=Vec3.ZERO;young.family().cradleHolder(parent.getUUID());phase(Phase.APPROACH);
        io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(parent,"lift.start."+young.getId(),0,"child="+young.getUUID()+" owner="+ownerLocal+" yaw="+yaw+" from="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(from)+" to="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(destination));
        return true;
    }
    private static Vec3 gripOf(YukkuriEntity young,Vec3 feet){return feet.add(0,young.getBbHeight()*BraidRescueGeometry.gripHeight(),0);}
    /** Boat mode: the braid takes hold of the nearest point of the child's body; land mode keeps its fixed grip height. */
    private Vec3 contactPoint(YukkuriEntity young,Vec3 root){
        return boatMode?BraidRescueGeometry.rescueContact(young,young.position(),root):BraidRescueGeometry.grip(young);
    }
    private BoatPlan planBoat(HatBoatEntity home,YukkuriEntity young,Vec3 landing){
        var candidates=new java.util.ArrayList<Vec3>(home.rescueOwnerCandidates(parent,young));
        var here=home.toLocal(parent.position());
        candidates.add(0,here);
        candidates.sort(java.util.Comparator.comparingDouble(at->at.distanceToSqr(here)));
        double height=Math.max(Math.max(young.getY(),landing.y),BraidRescueGeometry.rimClearance(young,home,landing))+.012;
        var up=new Vec3(young.getX(),height,young.getZ());var over=new Vec3(landing.x,height,landing.z);
        var legs=java.util.List.of(new Vec3[]{young.position(),up},new Vec3[]{up,over},new Vec3[]{over,landing});
        float oldYaw=parent.yBodyRot;
        BoatPlan best=null;double bestScore=Double.MAX_VALUE;
        int reachFails=0,blockFails=0,tested=0;double closest=Double.MAX_VALUE;
        try{
            int tried=0;
            for(var local:candidates){
                if(++tried>140)break;
                var feet=home.toWorld(local);
                if(!local.equals(here)&&feet.distanceToSqr(parent.position())>6.25)continue;
                for(int i=0;i<16;i++){
                    float facing=i*22.5f;
                    var root=BraidRescueGeometry.rootAtYaw(parent,feet,facing);
                    boolean ok=true;
                    for(var leg:legs){
                        int count=Math.max(1,(int)Math.ceil(leg[0].distanceTo(leg[1])/.03));
                        for(int k=0;k<=count&&ok;k++){
                            var p=leg[0].lerp(leg[1],(double)k/count);
                            var touch=BraidRescueGeometry.rescueContact(young,p,root);
                            if(!BraidRescueGeometry.reachable(parent,root,touch)){
                                ok=false;reachFails++;
                                double d=root.distanceTo(touch);
                                double miss=Math.max(BraidRescueGeometry.minimumReach(parent)-d,d-BraidRescueGeometry.maximumReach(parent));
                                if(miss<closest)closest=miss;
                            }
                            else if(level().getBlockCollisions(young,young.getBoundingBox().move(p.subtract(young.position())).deflate(.004)).iterator().hasNext())ok=false;
                        }
                        if(!ok)break;
                    }
                    tested++;
                    if(!ok)continue;
                    double score=feet.distanceToSqr(parent.position())+Math.abs(net.minecraft.util.Mth.wrapDegrees(facing-oldYaw))/360.0;
                    if(score<bestScore){bestScore=score;best=new BoatPlan(local,facing,up,over,landing);}
                }
                // The nearest patches are tried first; the first reachable cluster is good enough.
                if(best!=null&&tried>12)break;
            }
        }finally{parent.yBodyRot=oldYaw;}
        if(best==null)io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(parent,"lift.stats."+young.getId(),40,"candidates="+candidates.size()+" tested="+tested+" reachFails="+reachFails+" closestMiss="+closest
                +" reach=["+BraidRescueGeometry.minimumReach(parent)+","+BraidRescueGeometry.maximumReach(parent)+"] from="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(young.position())+" to="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(landing));
        return best;
    }
    private boolean related(YukkuriEntity young){return parent.getUUID().equals(young.family().mother())||parent.getUUID().equals(young.family().father());}
    private static boolean interrupted(YukkuriEntity actor){return actor.isNoAi()||actor.isCommandPlaying()||actor.operations().active()||actor.isOnFire()||actor.isPanicking();}

    /** Search only a nearby landing on the same side; a child cannot be carried through the adult or across the hat. */
    private Plan plan(HatBoatEntity home,YukkuriEntity young,Vec3 preferred){
        Plan best=null;double score=Double.MAX_VALUE;float oldYaw=parent.yBodyRot;
        try{
            for(int side=0;side<16;side++){
                double angle=side*Math.PI/8;var direction=new Vec3(Math.cos(angle),0,Math.sin(angle));
                for(double radius:new double[]{.76,.80,.82,.84,.86,.90,.96}){
                    var candidate=ground(parent,young.position().add(direction.scale(radius*parent.growthScale()*parent.getScale())));
                    if(candidate==null||candidate.distanceToSqr(parent.position())>9)continue;
                    // The right braid faces the child while the body's front remains tangent to the hat.
                    var modelRoot=BraidRescueGeometry.modelRoot();
                    float facing=(float)Math.toDegrees(Math.atan2(direction.z,direction.x)-Math.atan2(modelRoot.z,modelRoot.x));
                    parent.yBodyRot=facing;
                    if(!bodyClear(parent,candidate,home,young,null)||!SurfaceApproach.supported(parent,candidate))continue;
                    var root=rootAt(candidate,facing);var grip=BraidRescueGeometry.grip(young);
                    if(!BraidRescueGeometry.reachable(parent,root,grip))continue;
                    for(double outward:new double[]{.34,.46,.58,.70}){
                        var spot=ground(young,young.position().add(direction.scale(outward)));
                        if(spot==null)continue;
                        var back=candidate.add(direction.scale(outward*.60));
                        if(!supportedPath(candidate,back))continue;
                        double height=Math.max(fromHeight(young,spot),BraidRescueGeometry.rimClearance(young,home,spot))+.012;
                        var up=new Vec3(young.getX(),height,young.getZ());var over=new Vec3(spot.x,height,spot.z);
                        if(!supportedOutside(home,young,spot)||!pathClear(home,young,candidate,candidate,facing,young.position(),up)
                                ||!pathClear(home,young,candidate,back,facing,up,over)
                                ||!pathClear(home,young,back,back,facing,over,spot))continue;
                        double cost=candidate.distanceToSqr(parent.position())+spot.distanceToSqr(young.position())
                                +(preferred==null?0:spot.distanceToSqr(preferred)*.15);
                        if(cost<score){score=cost;best=new Plan(candidate,back,facing,up,over,spot);}
                    }
                }
            }
        }finally{parent.yBodyRot=oldYaw;}
        return best;
    }
    private boolean supportedPath(Vec3 a,Vec3 b){
        int steps=Math.max(1,(int)Math.ceil(a.distanceTo(b)/.025));
        for(int i=0;i<=steps;i++)if(!SurfaceApproach.supported(parent,a.lerp(b,(double)i/steps)))return false;
        return true;
    }
    private static double fromHeight(YukkuriEntity young,Vec3 spot){return Math.max(young.getY(),spot.y);}
    private Vec3 rootAt(Vec3 feet,float facing){return feet.add(BraidRescueGeometry.modelRoot()
            .scale(parent.growthScale()*parent.getScale()/16).yRot((float)Math.toRadians(180-facing)));}
    private Vec3 ground(YukkuriEntity actor,Vec3 point){
        var hit=level().clip(new ClipContext(point.add(0,.8,0),point.add(0,-1.2,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.ANY,actor));
        return hit.getType()==HitResult.Type.BLOCK&&level().getFluidState(hit.getBlockPos()).isEmpty()?hit.getLocation():null;
    }
    private boolean supportedOutside(HatBoatEntity home,YukkuriEntity young,Vec3 point){
        return SurfaceApproach.supported(young,point)&&BraidRescueGeometry.envelopeClearance(young,home,point)<=point.y+.002;
    }
    private boolean pathClear(HatBoatEntity home,YukkuriEntity young,Vec3 parentA,Vec3 parentB,float facing,Vec3 a,Vec3 b){
        int count=Math.max(1,(int)Math.ceil(Math.max(a.distanceTo(b),parentA.distanceTo(parentB))/.02));
        for(int i=0;i<=count;i++){
            var feet=a.lerp(b,(double)i/count);var parentFeet=parentA.lerp(parentB,(double)i/count);
            var root=rootAt(parentFeet,facing);
            var contact=boatMode?BraidRescueGeometry.rescueContact(young,feet,root):feet.add(0,young.getBbHeight()*BraidRescueGeometry.gripHeight(),0);
            if(!BraidRescueGeometry.reachable(parent,root,contact)||!bodyClear(parent,parentFeet,home,young,null)
                    ||!bodyClear(young,feet,home,parent,BraidRescueGeometry.envelope(parent,parentFeet)))return false;
        }
        return true;
    }
    private boolean bodyClear(YukkuriEntity actor,Vec3 feet,HatBoatEntity home,YukkuriEntity excluded,List<AABB> excludedBoxes){
        if(!level().hasChunkAt(BlockPos.containing(feet))||!level().getWorldBorder().isWithinBounds(BlockPos.containing(feet))
                ||BraidRescueGeometry.envelopeClearance(actor,home,feet)>feet.y+.003)return false;
        for(var shape:BraidRescueGeometry.envelope(actor,feet)){
            var box=shape.deflate(.002);
            if(level().getBlockCollisions(actor,box).iterator().hasNext())return false;
            if(excludedBoxes!=null)for(var other:excludedBoxes)if(box.intersects(other.deflate(.002)))return false;
            for(var other:level().getEntities(actor,box)){
                if(other==home||other==excluded||!other.isAlive()||other.isSpectator())continue;
                if(other instanceof YukkuriEntity y){for(var full:BraidRescueGeometry.envelope(y,y.position()))if(box.intersects(full.deflate(.002)))return false;}
                else if(other.canBeCollidedWith()&&box.intersects(other.getBoundingBox()))return false;
            }
        }
        return true;
    }

    public void tick(){
        if(parent.level().isClientSide||!active())return;
        long now=level().getGameTime();if(lastTick==now)return;lastTick=now;
        var young=child();var home=hat();
        if(boatMode&&home!=null)stand=home.toWorld(ownerLocal);
        if(young==null||home==null||!young.isAlive()||!parent.isAlive()||(boatMode?parent.getVehicle()!=home:parent.isPassenger())||interrupted(parent)||interrupted(young)||home.unsteadyMotion()
                ||(boatMode?!home.isAfloat():!home.catches())||home.position().distanceToSqr(hatAt)>(boatMode?.09:.0025)||!home.young(young)||!related(young)
                ||++total>(boatMode?420:360)||!BraidRescueGeometry.canLift(parent,young)){
            io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(parent,"lift.invalid",0,"phase="+phase+" boat="+boatMode+" total="+total+" young="+(young==null?"null":"ok")+" home="+(home==null?"null":"ok"));
            cancel();return;
        }
        if((!held()&&phase!=Phase.RELEASE&&young.getVehicle()!=home)||(held()&&young.isPassenger())){cancel();return;}
        if((phase==Phase.REACH||phase==Phase.GRIP)&&young.position().distanceToSqr(from)>.0004){cancel();return;}
        home.holdPosition();prevTip=tip;prevJoint=joint;
        if(phase==Phase.APPROACH&&boatMode){
            // The captain walks along the deck to her patch (a real seat walk), then turns so the braid faces the child.
            var rootNow=BraidRescueGeometry.root(parent,0);
            tip=BraidRescueGeometry.restTip(parent,0).add(0,.25*parent.growthScale()*parent.getScale(),0);
            joint=BraidRescueGeometry.joint(parent,rootNow,tip,BraidRescueGeometry.restJoint(parent,0));
            if(!home.rescueOwnerReady(parent))return;
            SurfaceApproach.halt(parent);SurfaceApproach.face(parent,yaw);
            if(Math.abs(net.minecraft.util.Mth.wrapDegrees(parent.yBodyRot-yaw))>1)return;
            from=young.position();stand=home.toWorld(ownerLocal);
            var rootAt=BraidRescueGeometry.root(parent,0);
            if(!BraidRescueGeometry.reachable(parent,rootAt,contactPoint(young,rootAt))){
                io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(parent,"lift.unreachable",0,"child out of braid reach after the walk root="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(rootAt)+" touch="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(contactPoint(young,rootAt)));
                cancel();return;
            }
            reachFrom=tip;prevTip=tip;prevJoint=joint;phase(Phase.REACH);
        }else if(phase==Phase.APPROACH){
            // Fold the free tip clear of the rim before the parent takes the final steps beside it.
            var root=BraidRescueGeometry.root(parent,0);
            tip=BraidRescueGeometry.restTip(parent,0).add(0,.25*parent.growthScale()*parent.getScale(),0);
            joint=BraidRescueGeometry.joint(parent,root,tip,BraidRescueGeometry.restJoint(parent,0));
            if(!SurfaceApproach.walk(parent,stand,.65))return;
            SurfaceApproach.halt(parent);SurfaceApproach.face(parent,yaw);
            if(Math.abs(net.minecraft.util.Mth.wrapDegrees(parent.yBodyRot-yaw))>1)return;
            // The navigation arrival tolerance is wider than the rim clearance. Take the last real step,
            // still on supported ground, before using the planned grip; never snap to the stand.
            var last=stand.subtract(parent.position());
            if(last.lengthSqr()>.000001){
                var next=parent.position().add(last.scale(Math.min(1,.02/last.length())));
                if(!supportedPath(parent.position(),next)||!bodyClear(parent,next,home,young,null)){cancel();return;}
                parent.move(MoverType.SELF,next.subtract(parent.position()));parent.hasImpulse=true;return;
            }
            endStand=endStand.add(parent.position().subtract(stand));stand=parent.position();from=young.position();
            if(!pathClear(home,young,stand,stand,parent.yBodyRot,from,raised)
                    ||!pathClear(home,young,stand,endStand,parent.yBodyRot,raised,across)
                    ||!pathClear(home,young,endStand,endStand,parent.yBodyRot,across,destination)){cancel();return;}
            reachFrom=tip;prevTip=tip;prevJoint=joint;phase(Phase.REACH);
        }else{
            SurfaceApproach.halt(parent);SurfaceApproach.face(parent,yaw);
            if(phase!=Phase.PLACE&&parent.position().distanceToSqr(stand)>(boatMode?.01:.0016)){
                io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(parent,"lift.drift",0,"parent left her patch phase="+phase+" by "+Math.sqrt(parent.position().distanceToSqr(stand)));
                cancel();return;
            }
        }
        long elapsed=now-start;var root=BraidRescueGeometry.root(parent,0);var contact=contactPoint(young,root);
        if(phase==Phase.REACH){
            tip=reachFrom.lerp(contact,smooth(elapsed/20.0));
            // An unreachable visual reach never takes custody.
            if(elapsed>=20){if(!BraidRescueGeometry.reachable(parent,root,contact)||!BraidRescueGeometry.touches(young,tip)){cancel();return;}phase(Phase.GRIP);}
        }else if(phase==Phase.GRIP){
            tip=contact;
            if(!BraidRescueGeometry.reachable(parent,root,tip)||!BraidRescueGeometry.touches(young,tip)){cancel();return;}
            if(elapsed>=8){
                phase(Phase.LIFT);var before=young.position();young.stopRiding();
                if(young.position().distanceToSqr(before)>.000001){cancel();return;}
                young.getNavigation().stop();young.setDeltaMovement(Vec3.ZERO);
                if(boatMode&&hullSeat!=null&&!home.holdIncoming(young,hullSeat)){cancel();return;}
            }
        }else if(held()){
            if(!parent.life().spendEnergy(.025f)){cancel();return;}
            var target=phase==Phase.LIFT?raised:phase==Phase.PLACE?across:destination;
            if(!moveYoung(home,young,target)){cancel();return;}
            root=BraidRescueGeometry.root(parent,0);
            tip=contactPoint(young,root);
            if(young.position().distanceToSqr(target)<.000001){
                if(phase==Phase.LIFT)phase(Phase.PLACE);else if(phase==Phase.PLACE){if(!boatMode)stand=parent.position();phase(Phase.LOWER);}
                else {
                    if(boatMode&&hullSeat!=null){
                        // Set down on the hull again: the reserved brim seat takes the child back as its rider.
                        if(!home.boardYoung(young)){cancel();return;}
                    }else if(!supportedOutside(home,young,young.position())){cancel();return;}
                    young.setDeltaMovement(Vec3.ZERO);phase(Phase.RELEASE);
                }
            }
        }else if(phase==Phase.RELEASE){
            tip=contact.lerp(BraidRescueGeometry.restTip(parent,0),smooth(elapsed/12.0));
            if(elapsed>=12){cancel();return;}
        }
        // Exact two-link contact is shared with rendering, including during the approach of the tip.
        if(!BraidRescueGeometry.reachable(parent,root,tip)){
            var delta=tip.subtract(root);tip=root.add(delta.normalize().scale(Math.clamp(delta.length(),
                    BraidRescueGeometry.minimumReach(parent),BraidRescueGeometry.maximumReach(parent))));
        }
        joint=BraidRescueGeometry.joint(parent,root,tip,BraidRescueGeometry.restJoint(parent,0));
    }
    private boolean moveYoung(HatBoatEntity home,YukkuriEntity young,Vec3 target){
        var delta=target.subtract(young.position());double length=delta.length();if(length>.025)delta=delta.scale(.025/length);
        var wanted=young.position().add(delta);
        var parentWanted=parent.position();
        if(phase==Phase.PLACE&&!boatMode){
            double progress=raised.distanceTo(across)<1e-8?1:Math.clamp(raised.distanceTo(wanted)/raised.distanceTo(across),0,1);
            parentWanted=stand.lerp(endStand,progress);
            if(parentWanted.distanceToSqr(parent.position())>.04*.04||!supportedPath(parent.position(),parentWanted))return false;
        }
        if(!pathClear(home,young,parent.position(),parentWanted,parent.yBodyRot,young.position(),wanted))return false;
        if(phase==Phase.PLACE&&!boatMode){parent.move(MoverType.SELF,parentWanted.subtract(parent.position()));parent.setDeltaMovement(Vec3.ZERO);parent.hasImpulse=true;
            if(parent.position().distanceToSqr(parentWanted)>.000001)return false;}
        var before=young.position();young.move(MoverType.SELF,delta);
        lastVelocity=young.position().subtract(before);young.setDeltaMovement(Vec3.ZERO);young.fallDistance=0;young.hasImpulse=true;
        return young.position().distanceToSqr(wanted)<.000001&&BraidRescueGeometry.touches(young,BraidRescueGeometry.grip(young));
    }
    private void phase(Phase next){phase=next;start=level().getGameTime();}
    private static double smooth(double value){double q=Math.clamp(value,0,1);return q*q*(3-2*q);}
    public void cancel(){
        if(parent.level().isClientSide||!active())return;
        var young=child();var home=hat();boolean wasHeld=held();var was=phase;phase=Phase.IDLE;
        if(boatMode)io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(parent,"lift.end",0,"phase="+was+" held="+wasHeld+" line="+StackWalker.getInstance().walk(f->f.skip(1).findFirst().map(StackWalker.StackFrame::getLineNumber).orElse(-1)));
        if(young!=null){
            young.family().cradleHolder(null);
            if(wasHeld&&!young.isPassenger()){
                // A lift that stops in the air returns the child to the deck it left, never to the water below.
                if(boatMode&&home!=null&&was!=Phase.LOWER&&home.boardYoung(young)){young.setDeltaMovement(Vec3.ZERO);}
                else{young.setDeltaMovement(lastVelocity.add(0,-.02,0));young.hasImpulse=true;}
            }
        }
        if(boatMode&&home!=null)home.releaseRescueOwner(parent);
        boatMode=false;ownerLocal=null;hullSeat=null;
        childId=hatId=null;tip=joint=prevTip=prevJoint=null;lastVelocity=Vec3.ZERO;SurfaceApproach.halt(parent);
    }
    public CompoundTag state(){
        var tag=new CompoundTag();if(!active())return tag;var young=child();var home=hat();if(young==null||home==null)return tag;
        tag.putString("Phase","CRADLE_"+phase.name());tag.putLong("Start",start);tag.putInt("Duration",phase==Phase.REACH?20:phase==Phase.GRIP?8:12);
        tag.putInt("ParentId",parent.getId());tag.putInt("ChildId",young.getId());tag.putInt("HatId",home.getId());tag.putFloat("Yaw",parent.yBodyRot);
        tag.putBoolean("Gripped",held());put(tag,"Tip",tip);put(tag,"Joint",joint);put(tag,"PrevTip",prevTip);put(tag,"PrevJoint",prevJoint);return tag;
    }
    private static void put(CompoundTag tag,String key,Vec3 value){if(value!=null){tag.putDouble(key+"X",value.x);tag.putDouble(key+"Y",value.y);tag.putDouble(key+"Z",value.z);}}
}
