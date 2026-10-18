package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatRoute;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** One real child is brought across a dipped rim by its own bite or a parent's finite, contact-checked braid. */
public final class BraidRescue {
    public enum Phase { IDLE, APPROACH, OFFER, REACH, GRIP, BITE, PUSH, SLIDE, SETTLE, RELEASE }
    private static final double GAP_TARGET=.025, GAP_LIMIT=.06, PUSH_FORWARD=.06, PUSH_UP=.04, MAX_STEP=.12;
    private static final int REACH_LIMIT=80;
    private static final int APPROACH_TICKS=12, OFFER_TICKS=14, BITE_TICKS=8, PUSH_TICKS=20, SLIDE_TICKS=24, SETTLE_TICKS=16, RELEASE_TICKS=12, LIMIT=340;
    private final YukkuriEntity child;
    private Phase phase=Phase.IDLE;
    private UUID parentId, hatId, abandonedHat;
    private Vec3 seat, approachStand, offerStand, dipTarget, pushFrom, pushDirection=Vec3.ZERO, slideFrom, lastVelocity=Vec3.ZERO;
    private long start, lastTick=Long.MIN_VALUE, retryAt, leanStart;
    private float parentYaw,braidTurn=65;
    private int totalTicks, biteMode, biteTicks, stalled, lastSide=-1;
    private boolean spoke, braidGrip;
    private Vec3 braidTip, braidJoint, previousTip, previousJoint, reachFrom;
    /** Set only while a straight-up draw from this point is being checked and made (see PUSH). */
    private Vec3 riseFrom;
    private double slideQ;
    private final Map<Integer,Long> failedSides=new HashMap<>();
    private record Rim(double x,double z,double gap,Vec3 world,double ux,double uz){}
    private record Plan(Vec3 seat,Vec3 approach,Vec3 offer,boolean braid,float turn){}
    /** Headings (degrees turned away from facing the child) that bring the right braid toward it, tried in this order. */
    private static final float[] BRAID_TURNS={65,50,80,35,95,20,110};
    private record Berth(Vec3 center,double cost){}
    public BraidRescue(YukkuriEntity child){this.child=child;}
    private ServerLevel level(){return (ServerLevel)child.level();}
    private YukkuriEntity parent(){return parentId!=null&&level().getEntity(parentId) instanceof YukkuriEntity y?y:null;}
    private HatBoatEntity hat(){return hatId!=null&&level().getEntity(hatId) instanceof HatBoatEntity h?h:null;}
    public boolean active(){return phase!=Phase.IDLE;}
    /** This controller checks the detailed hull rather than the vessel's coarse entity AABB. */
    public boolean transferringThrough(HatBoatEntity home){return active()&&home!=null&&home.getUUID().equals(hatId);}
    /** The child's position is driven by this controller (nudge, slide, settle); water bursts and buoyancy stand aside. */
    public boolean held(){return phase==Phase.PUSH||phase==Phase.SLIDE||phase==Phase.SETTLE||phase==Phase.RELEASE||phase==Phase.GRIP&&braidGrip;}
    public UUID parentId(){return parentId;}
    public UUID childId(){return child.getUUID();}
    private void phase(Phase next){
        phase=next;start=level().getGameTime();
        var parent=parent();
        if(parent!=null&&io.github.longshou.mcyukkuri.subspecies.AquaDiag.enabled()){
            var root=braidRoot(parent);
            io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(child,"rescue.phase",0,"-> "+next+" braid="+braidGrip+" child="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(child.position())
                    +" riding="+child.isPassenger()+" parent="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(parent.position())+" yaw="+parent.yBodyRot+" lean="+lean()
                    +" reach="+root.distanceTo(BraidRescueGeometry.rescueContact(child,child.position(),root)));
        }
    }
    private static double smooth(double q){q=Math.clamp(q,0,1);return q*q*(3-2*q);}
    /** How far the parent has sunk her braid side toward the child (0..1): eased in from REACH, out again in SETTLE. */
    private double lean(){
        if(!braidGrip)return 0;
        long now=level().getGameTime();double ticks=BraidRescueGeometry.leanTicks();
        return switch(phase){
            case REACH,GRIP,PUSH,SLIDE->smooth((now-leanStart)/ticks);
            case SETTLE->smooth((ticks-(now-start))/ticks);
            default->0;
        };
    }
    /** The braid root as the parent is actually posed now (the same lean the client plays). */
    private Vec3 braidRoot(YukkuriEntity parent){return BraidRescueGeometry.leanRootAtYaw(parent,parent.position(),parent.yBodyRot,lean());}
    private boolean eligible(YukkuriEntity parent,HatBoatEntity hat){
        return parent!=null&&parent.isAlive()&&child.isAlive()&&parent.level()==child.level()
                &&!parent.isNoAi()&&!parent.isCommandPlaying()&&!parent.operations().active()&&!parent.isOnFire()
                &&parent.life().mealTicks()==0&&!parent.life().isSleeping()&&!parent.family().tiedToLabor()
                &&!child.isCommandPlaying()&&!child.operations().active()&&!child.isOnFire()
                &&hat!=null&&hat.isAfloat()&&!hat.unsteadyMotion()&&!hat.towed()&&hat.flooding()==0
                &&hat.young(child)&&hat.protects(parent)&&BraidRescueGeometry.canLift(parent,child);
    }
    private boolean canBite(){return child.childhood().canJump()&&child.family().stage()!=FamilyLife.Stage.BABY;}
    /** Hat-local distance from a local point inward to the first point on the hat; null when no hat lies that way. */
    private static double[] edge(HatBoatEntity home,double lx,double lz){
        double length=Math.hypot(lx,lz);if(length<1e-6)return null;
        if(Double.isFinite(HatVessel.surface(home.mode(),lx,lz)))return new double[]{lx,lz,0};
        double dx=-lx/length,dz=-lz/length,previous=0;
        for(double t=.1;t<=length+.1;t+=.1){
            if(Double.isFinite(HatVessel.surface(home.mode(),lx+dx*t,lz+dz*t))){
                double low=previous,high=t;
                for(int i=0;i<6;i++){double mid=(low+high)*.5;if(Double.isFinite(HatVessel.surface(home.mode(),lx+dx*mid,lz+dz*mid)))high=mid;else low=mid;}
                return new double[]{lx+dx*high,lz+dz*high,high};
            }
            previous=t;
        }
        return null;
    }
    /** Hat units from the hat centre outward along (ux, uz) to the rim. */
    private static double reach(HatBoatEntity home,double ux,double uz){
        double low=0,high=0;
        for(double t=.1;t<=40;t+=.1){if(!Double.isFinite(HatVessel.surface(home.mode(),ux*t,uz*t))){high=t;break;}low=t;}
        if(high==0)return low;
        for(int i=0;i<6;i++){double mid=(low+high)*.5;if(Double.isFinite(HatVessel.surface(home.mode(),ux*mid,uz*mid)))low=mid;else high=mid;}
        return low;
    }
    private static int side(double ux,double uz){return Math.floorMod((int)Math.round(Math.atan2(uz,ux)/(Math.PI/8)),16);}
    /** The child's body to the nearest real brim edge, along the line to the hat centre. */
    private Rim rim(HatBoatEntity home){
        var local=home.toLocal(child.position());double length=Math.hypot(local.x,local.z);
        var edge=length<1e-6?null:edge(home,local.x,local.z);if(edge==null)return null;
        double gap=Math.max(0,edge[2]*home.unit()-child.getBbWidth()*.5);
        return new Rim(edge[0],edge[1],gap,home.toWorld(new Vec3(edge[0],0,edge[1])),local.x/length,local.z/length);
    }
    /** Nothing solid or alive between the child's body and the rim (the hat and its parent aside). */
    private boolean between(HatBoatEntity home,YukkuriEntity parent,Rim rim){
        if(rim.gap()<1e-4)return true;
        var dir=new Vec3(rim.world().x-child.getX(),0,rim.world().z-child.getZ());if(dir.lengthSqr()<1e-8)return true;
        dir=dir.normalize();var from=child.position().add(dir.scale(child.getBbWidth()*.5));
        var corridor=new AABB(from.x,child.getY(),from.z,rim.world().x,child.getY()+child.getBbHeight(),rim.world().z).inflate(.01);
        if(level().getBlockCollisions(child,corridor).iterator().hasNext())return false;
        for(var other:level().getEntities(child,corridor)){
            if(other==home||other==parent||!other.isAlive()||other.isSpectator())continue;
            if(other instanceof YukkuriEntity||other.canBeCollidedWith())return false;
        }
        return true;
    }
    public boolean start(HatBoatEntity home){
        if(child.level().isClientSide)return false;
        if(active())return home.getUUID().equals(hatId);
        long now=level().getGameTime();
        if(now<retryAt){io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(child,"rescue.wait."+child.getId(),40,"retry in "+(retryAt-now));return false;}
        var parent=level().getEntity(home.ownerYukkuri()) instanceof YukkuriEntity y?y:null;
        if(!eligible(parent,home)||child.isPassenger()||child.family().flying()||!child.waterDistress().canBeLifted()
                ||!child.isInWaterOrBubble()&&!child.onGround()||child.distanceToSqr(home)>9||!parent.hasLineOfSight(child)
                ||parent.family().braidRescueActive()){
            io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(child,"rescue.refuse."+child.getId(),40,"preconditions eligible="+eligible(parent,home)+" passenger="+child.isPassenger()
                    +" lifted="+child.waterDistress().canBeLifted()+" dist="+Math.sqrt(child.distanceToSqr(home))+" los="+(parent!=null&&parent.hasLineOfSight(child))
                    +" parentBusy="+(parent!=null&&parent.family().braidRescueActive()));
            return false;
        }
        var rim=rim(home);
        if(rim==null||rim.gap()>GAP_LIMIT||!between(home,parent,rim)){
            io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(child,"rescue.refuse."+child.getId(),40,"rim "+(rim==null?"null":"gap="+rim.gap()+" between="+between(home,parent,rim))
                    +" child="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(child.position())+" boat="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(home.position())+" yaw="+home.getYRot());
            return false;
        }
        retryAt=now+20;
        var plan=plan(parent,home,rim);
        if(plan==null){
            io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(child,"rescue.refuse."+child.getId(),40,"plan null gap="+rim.gap());
            failedSides.put(side(rim.ux(),rim.uz()),now+200);return false;
        }
        io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(child,"rescue.start",0,"child="+child.getUUID()+" gap="+rim.gap()+" braid="+plan.braid());
        if(!home.moveRescueOwner(parent,plan.approach())){home.cancelIncoming(child);return false;}
        if(!parent.life().spendEnergy(.15f)){home.cancelIncoming(child);home.releaseRescueOwner(parent);return false;}
        parentId=parent.getUUID();hatId=home.getUUID();seat=plan.seat();approachStand=plan.approach();offerStand=plan.offer();
        dipTarget=child.position();lastSide=side(rim.ux(),rim.uz());parentYaw=parent.yBodyRot;parent.family().rescueTarget(child.getUUID());
        totalTicks=biteMode=biteTicks=stalled=0;spoke=false;braidGrip=plan.braid();braidTurn=plan.turn();braidTip=braidJoint=previousTip=previousJoint=reachFrom=null;slideQ=0;lastVelocity=Vec3.ZERO;
        child.life().wakeInHat();parent.life().wakeInHat();home.holdPosition();phase(Phase.APPROACH);
        return true;
    }
    /** Boat-centre berth where the near rim lies about .03 block from the child's body, with a supported stand for the parent. */
    public Vec3 approach(HatBoatEntity home){
        if(child.level().isClientSide)return null;
        var parent=level().getEntity(home.ownerYukkuri()) instanceof YukkuriEntity y?y:null;
        if(!eligible(parent,home)||child.isPassenger()||home.rescueOwnerCandidates(parent).isEmpty())return null;
        long now=level().getGameTime();double unit=home.unit(),radius=child.getBbWidth()*.5,rp=HatVessel.radius(parent.getBbWidth())/unit;
        var berths=new ArrayList<Berth>();
        for(int i=0;i<16;i++){
            double angle=i*Math.PI/8;var world=new Vec3(Math.cos(angle),0,Math.sin(angle));
            var local=home.toLocal(home.position().add(world));double length=Math.hypot(local.x,local.z);if(length<1e-8)continue;
            double ux=local.x/length,uz=local.z/length;
            if(failedSides.getOrDefault(side(ux,uz),0L)>now)continue;
            double reach=reach(home,ux,uz),inner=reach-rp*1.2;
            if(!Double.isFinite(HatVessel.footprint(home.mode(),ux*inner,uz*inner,rp)))continue;
            double distance=reach*unit+radius+GAP_TARGET;
            var center=new Vec3(child.getX()-world.x*distance,home.getY(),child.getZ()-world.z*distance);
            var cell=BlockPos.containing(center.x,center.y-.12,center.z);
            if(!HatBoatRoute.calmSurface(level(),cell)||!level().getWorldBorder().isWithinBounds(cell))continue;
            var delta=center.subtract(home.position());
            if(!level().noCollision(home,home.getBoundingBox().move(delta)))continue;
            // A child that cannot bite needs the braid, whose root stands over the ledge near the hull centre: berth with a
            // near brim side (the hull's narrow sides) toward it rather than a far end.
            berths.add(new Berth(center,delta.lengthSqr()+(canBite()?0:1.5*reach*unit)));
        }
        berths.sort(Comparator.comparingDouble(Berth::cost));
        return berths.isEmpty()?null:berths.getFirst().center();
    }
    /** Choose the child's seat and the parent's rim stands together, with the whole slide pre-swept against both bodies. */
    private Plan plan(YukkuriEntity parent,HatBoatEntity home,Rim rim){
        double unit=home.unit(),rp=HatVessel.radius(parent.getBbWidth())/unit,rc=HatVessel.radius(child.getBbWidth())/unit;
        double tx=-rim.uz(),tz=rim.ux(),lateral=rp+rc+.4,pull=.03/unit;
        var seats=new ArrayList<>(home.rescueYoungCandidates(child));
        seats.sort(Comparator.comparingDouble(at->home.toWorld(at).distanceToSqr(child.position())));
        for(boolean requireBraid:new boolean[]{true,false}){
        if(!requireBraid&&!canBite())break;
        int checked=0,scanned=0,noBraid=0,noPlace=0,noSlide=0,noReserve=0,offers=0;
        for(var young:seats){
            if(++scanned>100||checked>=24)break;
            var at=home.toWorld(young);
            if(BraidRescueGeometry.envelopeClearance(child,home,at)>at.y+.002)continue;
            checked++;
            for(double depth:new double[]{1.2,1.5,2.0,2.5,3.0})for(double offset:new double[]{0,lateral*.3,-lateral*.3,lateral*.6,-lateral*.6,lateral,-lateral}){
                double ox=rim.x()-rim.ux()*rp*depth+tx*offset,oz=rim.z()-rim.uz()*rp*depth+tz*offset;
                double oy=HatVessel.footprint(home.mode(),ox,oz,rp);
                if(!Double.isFinite(oy)||!Double.isFinite(HatVessel.surface(home.mode(),ox,oz)))continue;
                var offer=new Vec3(ox,oy,oz);
                double ax=ox-rim.ux()*pull,az=oz-rim.uz()*pull,ay=HatVessel.footprint(home.mode(),ax,az,rp);
                var approach=Double.isFinite(ay)&&Double.isFinite(HatVessel.surface(home.mode(),ax,az))?new Vec3(ax,ay,az):offer;
                var turn=braidPath(parent,home,young,offer);boolean braid=turn!=null;offers++;
                if(braid!=requireBraid){if(requireBraid)noBraid++;continue;}
                if(!home.canPlaceRescuePair(child,parent,young,offer)||!home.canPlaceRescuePair(child,parent,young,approach)){noPlace++;continue;}
                if(!slideClear(parent,home,young,offer)){noSlide++;continue;}
                if(home.reserveRescuePair(child,parent,young,approach))return new Plan(young,approach,offer,braid,braid?turn:0);
                noReserve++;
            }
        }
        io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(child,"rescue.plan."+child.getId()+"."+requireBraid,40,"braid="+requireBraid+" seats="+seats.size()+" scanned="+scanned+" checked="+checked+" offers="+offers
                +" noBraid="+noBraid+" noPlace="+noPlace+" noSlide="+noSlide+" noReserve="+noReserve);
        }
        return null;
    }
    /**
     * A short supported transfer must remain inside the two unchanged braid segment lengths, starting from where the child
     * really floats: the braid has to touch it in the water before anything lifts it. Along the way the body only climbs the
     * dipped rim gradually, so each later point is checked both at the height that clears the hull there and at the water
     * height it may still be at. Returns the heading offset that works (see BRAID_TURNS), or null.
     */
    private Float braidPath(YukkuriEntity parent,HatBoatEntity home,Vec3 young,Vec3 stand){
        var feet=home.toWorld(stand);var target=home.toWorld(young);
        // The swimmer bobs and drifts a little before the touch: keep a small margin inside the reach at the start.
        double margin=.02;
        for(float turn:BRAID_TURNS){
            float yaw=SurfaceApproach.yaw(feet,child.position())-turn;
            // The reach is planned with her full lean toward the child (braid_reach_low), the pose she holds while gripping.
            var root=BraidRescueGeometry.leanRootAtYaw(parent,feet,yaw,1);
            var touch=BraidRescueGeometry.rescueContact(child,child.position(),root);double d=root.distanceTo(touch);
            if(d<BraidRescueGeometry.minimumReach(parent)+margin||d>BraidRescueGeometry.maximumReach(parent)-margin)continue;
            boolean ok=true;
            for(int i=1;i<=8&&ok;i++){
                var at=child.position().lerp(target,i/8.0);
                var lifted=new Vec3(at.x,BraidRescueGeometry.envelopeClearance(child,home,at),at.z);
                var low=new Vec3(at.x,Math.min(lifted.y,child.getY()+(lifted.y-child.getY())*i/8.0),at.z);
                ok=BraidRescueGeometry.reachable(parent,root,BraidRescueGeometry.rescueContact(child,lifted,root))
                        &&BraidRescueGeometry.reachable(parent,root,BraidRescueGeometry.rescueContact(child,low,root));
            }
            if(ok)return turn;
        }
        return null;
    }
    private boolean slideClear(YukkuriEntity parent,HatBoatEntity home,Vec3 young,Vec3 stand){
        var target=home.toWorld(young);var box=parent.getBoundingBox().move(home.toWorld(stand).subtract(parent.position()));
        var from=child.position();
        for(int i=0;i<=12;i++){
            var p=from.lerp(target,smooth(i/12.0));
            if(!bodyClear(home,new Vec3(p.x,BraidRescueGeometry.envelopeClearance(child,home,p),p.z),parent,box))return false;
        }
        return true;
    }
    public void tick(){
        if(child.level().isClientSide)return;
        cleanupAbandoned();
        long now=level().getGameTime();if(lastTick==now)return;lastTick=now;
        if(!active())return;
        var parent=parent();var home=hat();
        if(!eligible(parent,home)||!child.getUUID().equals(parent.family().rescueTarget())
                ||child.isPassenger()&&!(phase==Phase.RELEASE&&child.getVehicle()==home)
                ||child.family().flying()||++totalTicks>LIMIT){cancel();return;}
        if(phase==Phase.APPROACH||phase==Phase.OFFER){
            SurfaceApproach.face(parent,SurfaceApproach.yaw(parent.position(),child.position())-(braidGrip?braidTurn:0));parentYaw=parent.yBodyRot;
        }else SurfaceApproach.face(parent,parentYaw);
        parent.getLookControl().setLookAt(child,35,35);parent.getNavigation().stop();home.holdPosition();
        // The swimmer clings to the parent's rim from the offer on: its struggling no longer carries it out of the braid's reach.
        if(!held()&&phase!=Phase.RELEASE){var v=child.getDeltaMovement();child.setDeltaMovement(v.x*.1,v.y,v.z*.1);}
        long elapsed=now-start;
        if(held()&&!parent.life().spendEnergy(.035f)){cancel();return;}
        if(phase!=Phase.APPROACH&&phase!=Phase.RELEASE&&totalTicks%5==0)home.dipToward(dipTarget,5f,OFFER_TICKS);
        if(phase==Phase.OFFER||phase==Phase.REACH||phase==Phase.GRIP){
            // The hull follows the clinging child's drift so the rim stays where the braid was planned.
            var berth=approach(home);
            if(berth!=null&&berth.subtract(home.position()).horizontalDistanceSqr()<.25)home.driveToward(berth,home.getYRot(),.03,0f);
        }
        if(phase==Phase.REACH||phase==Phase.GRIP){
            var root=braidRoot(parent);var target=BraidRescueGeometry.rescueContact(child,child.position(),root);
            if(phase==Phase.REACH){
                // The tip walks through reachable configurations toward the child's nearest point; force starts only after a
                // real touch. A floundering infant bobs in and out of reach, so she keeps reaching (the tip stays on the braid's
                // natural length) until it is touched, and gives up only after REACH_LIMIT ticks.
                var tip=reachFrom.lerp(target,smooth(elapsed/20.0));
                double reach=Math.clamp(root.distanceTo(tip),BraidRescueGeometry.minimumReach(parent),BraidRescueGeometry.maximumReach(parent));
                var direction=tip.subtract(root);
                if(direction.lengthSqr()>1e-8)poseBraid(parent,root.add(direction.normalize().scale(reach)));
                if(elapsed>=20&&braidTip!=null&&BraidRescueGeometry.reachable(parent,root,target)&&BraidRescueGeometry.touches(child,braidTip)){biteTicks=0;phase(Phase.GRIP);}
                else if(elapsed>=REACH_LIMIT){
                    io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(child,"rescue.reach."+child.getId(),0,"no touch in "+elapsed+" ticks d="+root.distanceTo(target)+" range=["+BraidRescueGeometry.minimumReach(parent)+","+BraidRescueGeometry.maximumReach(parent)+"] child="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(child.position())+" root="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(root));
                    cancel();return;
                }
            }else{
                // Gripped: the braid holds the child still (its buoyancy and bursts stand aside, see held()).
                if(!BraidRescueGeometry.reachable(parent,root,target)||!poseBraid(parent,target)||!BraidRescueGeometry.touches(child,braidTip)){
                    io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(child,"rescue.reach."+child.getId(),0,"grip lost d="+root.distanceTo(target)+" child="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(child.position()));
                    cancel();return;
                }
                child.setDeltaMovement(Vec3.ZERO);
                if(++biteTicks>=BITE_TICKS){var edge=rim(home);if(edge==null){cancel();return;}beginPush(home,edge);}
            }
            return;
        }
        if(held()&&braidGrip&&phase!=Phase.RELEASE){
            var target=BraidRescueGeometry.rescueContact(child,child.position(),braidRoot(parent));
            if(!poseBraid(parent,target)||!BraidRescueGeometry.touches(child,braidTip)){cancel();return;}
        }
        if(phase==Phase.APPROACH||phase==Phase.OFFER||phase==Phase.BITE){
            var rim=rim(home);
            if(rim==null||rim.gap()>GAP_LIMIT*2||elapsed>=60){
                io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(child,"rescue.stall."+child.getId(),0,"phase="+phase+" elapsed="+elapsed+" rim="+(rim==null?"null":"gap="+rim.gap())
                        +" ownerReady="+home.rescueOwnerReady(parent)+" owner="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(parent.position())+" child="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(child.position())
                        +" approachStand="+approachStand+" offerStand="+offerStand+" biteTicks="+biteTicks+" mask="+biteContact(home,parent));
                cancel();return;
            }
            child.getNavigation().stop();SurfaceApproach.face(child,SurfaceApproach.yaw(child.position(),rim.world()));
            if(phase==Phase.APPROACH){
                if(home.rescueOwnerReady(parent)){
                    if(!offerStand.equals(approachStand)&&!home.moveRescueOwner(parent,offerStand)){cancel();return;}
                    phase(Phase.OFFER);parent.speak("braid_rescue_offer");home.dipToward(dipTarget,5f,OFFER_TICKS);
                }
            }else if(phase==Phase.OFFER){
                // The braid side must actually face the child (she turns at a body's pace) before it reaches out.
                boolean facing=!braidGrip||Math.abs(net.minecraft.util.Mth.wrapDegrees(parent.yBodyRot-(SurfaceApproach.yaw(parent.position(),child.position())-braidTurn)))<=2;
                if(elapsed>=OFFER_TICKS&&home.rescueOwnerReady(parent)&&facing){
                    if(braidGrip){reachFrom=BraidRescueGeometry.restTip(parent);leanStart=now;phase(Phase.REACH);}
                    else if(canBite()){biteMode=biteTicks=0;phase(Phase.BITE);}else cancel();
                }
            }else{
                int mask=biteContact(home,parent);
                int mode=biteMode!=0&&(mask&biteMode)!=0?biteMode:(mask&1)!=0?1:(mask&2)!=0?2:0;
                if(mode!=0){
                    biteMode=mode;biteTicks++;
                    if(biteTicks==1&&!spoke){child.speak("braid_rescue_grip");spoke=true;}
                }else{biteMode=biteTicks=0;nudgeHull(home);}
                if(biteTicks>=BITE_TICKS)beginPush(home,rim);
            }
            return;
        }
        var box=parent.getBoundingBox();
        if(phase==Phase.PUSH){
            SurfaceApproach.face(child,SurfaceApproach.yaw(child.position(),home.position()));
            double s=smooth((double)elapsed/PUSH_TICKS);Vec3 wanted=null;
            for(double h:new double[]{1,.75,.5,.25,0}){
                var at=pushFrom.add(pushDirection.scale(PUSH_FORWARD*s*h)).add(0,PUSH_UP*s,0);
                if(bodyClear(home,at,parent,box)&&sweepClear(home,parent,box,at)){wanted=at;break;}
            }
            boolean moved=false;
            if(wanted==null&&braidGrip){
                // A braid-held child pressed against the wall (its worn hat already over the rim) is first drawn straight up
                // the wall, a few centimetres a tick: rising can only clear the hull's height-field, never cut into it.
                riseFrom=child.position();
                try{
                    for(double up:new double[]{.04,.03,.02,.01}){
                        var at=child.position().add(0,up,0);
                        if(bodyClear(home,at,parent,box)&&sweepClear(home,parent,box,at)&&moveChild(home,parent,box,at)){pushFrom=pushFrom.add(0,up,0);moved=true;break;}
                    }
                }finally{riseFrom=null;}
            }
            if(!moved&&(wanted==null||!moveChild(home,parent,box,wanted))){
                io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(child,"rescue.push."+child.getId(),0,"push stopped: wanted="+(wanted==null?"none":"moveChild refused")+" why="+clearWhy+" elapsed="+elapsed+" child="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(child.position()));
                cancel();return;
            }
            // The slide starts once the push is done and the child's whole envelope rests clear of the hull where it is.
            if(elapsed>=PUSH_TICKS&&bodyClear(home,child.position(),parent,box)){slideFrom=child.position();slideQ=0;stalled=0;phase(Phase.SLIDE);}
        }else if(phase==Phase.SLIDE){
            SurfaceApproach.face(child,SurfaceApproach.yaw(child.position(),home.position()));
            if(elapsed>=SLIDE_TICKS*3){cancel();return;}
            var target=home.toWorld(seat);double dq=1.0/SLIDE_TICKS,ahead=child.getY()-.04;
            // The feet climb the dipped rim just ahead of the sideways travel and never below real support.
            for(int i=0;i<=4;i++)ahead=Math.max(ahead,BraidRescueGeometry.envelopeClearance(child,home,slideFrom.lerp(target,smooth(Math.min(1,slideQ+i*dq)))));
            double y=Math.clamp(ahead,child.getY()-.04,child.getY()+.04);
            Vec3 wanted=null;double reached=slideQ;
            for(double f:new double[]{1,.75,.5,.25,0}){
                double q=Math.min(1,slideQ+dq*f);var p=slideFrom.lerp(target,smooth(q));var at=new Vec3(p.x,y,p.z);
                if(bodyClear(home,at,parent,box)&&sweepClear(home,parent,box,at)){wanted=at;reached=q;break;}
            }
            if(wanted==null||!moveChild(home,parent,box,wanted)){
                io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(child,"rescue.slide."+child.getId(),0,"slide stopped: wanted="+(wanted==null?"none":"moveChild refused")+" why="+clearWhy+" q="+slideQ+" child="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(child.position()));
                cancel();return;
            }
            stalled=reached>slideQ+1e-9||reached>=1?0:stalled+1;slideQ=reached;
            if(stalled>16){cancel();return;}
            if(slideQ>=1&&Math.abs(child.getY()-target.y)<.0006){
                if(!moveChild(home,parent,box,target)){cancel();return;}
                phase(Phase.SETTLE);
            }
        }else if(phase==Phase.SETTLE){
            var target=home.toWorld(seat);
            if(!moveChild(home,parent,box,target)){cancel();return;}
            if(elapsed>=SETTLE_TICKS){
                var reserved=home.peekSeat(child);
                if(reserved==null||reserved.distanceToSqr(seat)>.000001||child.position().distanceToSqr(target)>.0001
                        ||!home.boardYoung(child)){cancel();return;}
                phase(Phase.RELEASE);home.releaseDip(RELEASE_TICKS);
            }
        }else if(phase==Phase.RELEASE){
            if(!home.protects(child)){cancel();return;}
            if(elapsed>=RELEASE_TICKS){parent.speak("braid_rescue_safe");finish(parent,home);}
        }
    }
    private void beginPush(HatBoatEntity home,Rim rim){
        var toward=new Vec3(rim.world().x-child.getX(),0,rim.world().z-child.getZ());
        if(toward.lengthSqr()<1e-8)toward=new Vec3(home.getX()-child.getX(),0,home.getZ()-child.getZ());
        pushDirection=toward.lengthSqr()<1e-8?Vec3.ZERO:toward.normalize();pushFrom=child.position();
        child.waterDistress().lifted();child.getNavigation().stop();
        if(!spoke){child.speak("braid_rescue_grip");spoke=true;}
        phase(Phase.PUSH);
    }
    /** Bit 1: the child's mouth on the parent's rim. Bit 2: the parent's mouth on the child's small hat brim. */
    private int biteContact(HatBoatEntity home,YukkuriEntity parent){
        int mask=0;
        var mouth=BraidRescueGeometry.mouthPoint(child,child.position(),child.yBodyRot);
        var face=rimFace(home,mouth);
        if(mouth!=null&&face!=null&&mouth.distanceTo(face)<=BraidRescueGeometry.biteReach(child))mask|=1;
        if(child.life().hasAccessory()){
            var boxes=BraidRescueGeometry.envelope(child,child.position());
            var jaw=BraidRescueGeometry.mouthPoint(parent,parent.position(),parent.yBodyRot);
            if(boxes.size()>1&&jaw!=null&&distance(boxes.get(1),jaw)<=BraidRescueGeometry.biteReach(parent))mask|=2;
        }
        return mask;
    }
    /** Nearest point on the dipped rim's outer face (waterline up to the lowered brim top) to a world point. */
    private static Vec3 rimFace(HatBoatEntity home,Vec3 at){
        if(at==null)return null;
        var local=home.toLocal(at);var edge=edge(home,local.x,local.z);if(edge==null)return null;
        var top=new Vec3(edge[0],HatVessel.top(home.mode()),edge[1]);
        // A swimmer floats with its mouth a hand below the keel line; the bowl's outer wall reaches down to where it can be bitten.
        double high=home.toWorld(top).y-home.rimLift(top),low=home.getY()-Math.max(.8*home.unit(),.3);
        var base=home.toWorld(new Vec3(edge[0],0,edge[1]));
        return new Vec3(base.x,Math.clamp(at.y,Math.min(low,high),Math.max(low,high)),base.z);
    }
    private static double distance(AABB box,Vec3 p){
        double dx=Math.max(Math.max(box.minX-p.x,0),p.x-box.maxX),dy=Math.max(Math.max(box.minY-p.y,0),p.y-box.maxY),dz=Math.max(Math.max(box.minZ-p.z,0),p.z-box.maxZ);
        return Math.sqrt(dx*dx+dy*dy+dz*dz);
    }
    /** The hull eases the last centimetres toward the child's mouth, so the bite contact is never left to chance. */
    private void nudgeHull(HatBoatEntity home){
        var mouth=BraidRescueGeometry.mouthPoint(child,child.position(),child.yBodyRot);
        var face=rimFace(home,mouth);
        if(mouth==null||face==null){io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(child,"rescue.nudge."+child.getId(),20,"no rim face mouth="+mouth+" face="+face);return;}
        var gap=new Vec3(mouth.x-face.x,0,mouth.z-face.z);double length=gap.horizontalDistance();
        io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(child,"rescue.nudge."+child.getId(),20,"mouth="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(mouth)+" face="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(face)
                +" horizontal="+length+" vertical="+(mouth.y-face.y)+" reach="+BraidRescueGeometry.biteReach(child)+" hull="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(home.position()));
        if(length<1e-4)return;
        var drive=home.driveToward(home.position().add(gap.scale(Math.min(length,.02)/length)),home.getYRot(),.02,0f);
        if(drive==HatBoatEntity.Drive.BLOCKED)io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(child,"rescue.nudge.blocked."+child.getId(),20,"hull drive blocked");
    }
    /** A small lunge across the last bit of water; the child's own bounded effort, not a carry. */
    private void lunge(Rim rim){
        var toward=new Vec3(rim.world().x-child.getX(),0,rim.world().z-child.getZ());if(toward.lengthSqr()<1e-8)return;
        var push=toward.normalize().scale(.008);var v=child.getDeltaMovement();
        if(v.horizontalDistanceSqr()<.03*.03)child.setDeltaMovement(v.x+push.x,v.y,v.z+push.z);
        child.hasImpulse=true;
    }
    private String clearWhy="";
    private boolean bodyClear(HatBoatEntity home,Vec3 feet,YukkuriEntity parent,AABB parentBox){
        var boxes=BraidRescueGeometry.envelope(child,feet);
        if(!level().getWorldBorder().isWithinBounds(BlockPos.containing(feet))){clearWhy="world border";return false;}
        double required=BraidRescueGeometry.envelopeClearance(child,home,feet);
        boolean rising=riseFrom!=null&&Math.abs(feet.x-riseFrom.x)<1e-9&&Math.abs(feet.z-riseFrom.z)<1e-9&&feet.y>=riseFrom.y;
        if(required>feet.y+.002&&!rising){clearWhy="hull surface needs feet at "+required+" but feet "+feet.y+" (short by "+(required-feet.y)+") dip="+home.dipDegrees(0)+" source="+BraidRescueGeometry.clearanceWhy(boxes,home,feet);return false;}
        for(var box:boxes){
            if(box.deflate(.002).intersects(parentBox)){clearWhy="parent body";return false;}
            if(level().getBlockCollisions(child,box.deflate(.002)).iterator().hasNext()){clearWhy="block";return false;}
            for(var other:level().getEntities(child,box)){
                if(other==home||other==parent||!other.isAlive()||other.isSpectator())continue;
                if(other instanceof YukkuriEntity y){
                    for(var shape:BraidRescueGeometry.envelope(y,y.position()))if(box.deflate(.002).intersects(shape)){clearWhy="another yukkuri "+y.getUUID();return false;}
                }else if(other.canBeCollidedWith()&&box.deflate(.002).intersects(other.getBoundingBox())){clearWhy="entity "+other.getType().toShortString();return false;}
            }
        }
        return true;
    }
    /** The straight step from where the child is to {@code wanted} is clear all the way, not only at its end. */
    private boolean sweepClear(HatBoatEntity home,YukkuriEntity parent,AABB parentBox,Vec3 wanted){
        var delta=wanted.subtract(child.position());
        if(delta.lengthSqr()>MAX_STEP*MAX_STEP)return false;
        int steps=Math.max(1,(int)Math.ceil(delta.length()/.025));
        for(int i=1;i<=steps;i++)if(!bodyClear(home,child.position().lerp(wanted,(double)i/steps),parent,parentBox))return false;
        return true;
    }
    /** Bounded, volume-swept step of the child's whole body; never a jump to the destination. */
    private boolean moveChild(HatBoatEntity home,YukkuriEntity parent,AABB parentBox,Vec3 wanted){
        if(wanted==null)return false;
        var delta=wanted.subtract(child.position());
        if(delta.lengthSqr()>MAX_STEP*MAX_STEP)return false;
        int steps=Math.max(1,(int)Math.ceil(delta.length()/.025));
        for(int i=1;i<=steps;i++)if(!bodyClear(home,child.position().lerp(wanted,(double)i/steps),parent,parentBox))return false;
        if(braidGrip){
            var root=braidRoot(parent);var tip=BraidRescueGeometry.rescueContact(child,wanted,root);
            if(!BraidRescueGeometry.reachable(parent,root,tip))return false;
        }
        // Actual collision-resolved movement. A blocked child stays blocked; no position correction follows.
        var before=child.position();child.move(MoverType.SELF,delta);child.setDeltaMovement(Vec3.ZERO);
        if(child.position().distanceToSqr(wanted)>.000004)return false;
        delta=child.position().subtract(before);
        if(braidGrip&&!poseBraid(parent,BraidRescueGeometry.rescueContact(child,child.position(),braidRoot(parent))))return false;
        child.getNavigation().stop();child.fallDistance=0;child.hasImpulse=true;lastVelocity=delta;return true;
    }
    private boolean poseBraid(YukkuriEntity parent,Vec3 tip){
        var root=braidRoot(parent);
        if(!BraidRescueGeometry.reachable(parent,root,tip)){
            io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(child,"rescue.pose."+child.getId(),20,"unreachable phase="+phase+" d="+root.distanceTo(tip)+" range=["+BraidRescueGeometry.minimumReach(parent)+","+BraidRescueGeometry.maximumReach(parent)+"] root="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(root)+" tip="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(tip));
            return false;
        }
        var joint=BraidRescueGeometry.joint(parent,root,tip,BraidRescueGeometry.restJoint(parent,0));
        if(joint==null||!clearBraidSegment(parent,root,joint)||!clearBraidSegment(parent,joint,tip)){
            io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(child,"rescue.pose."+child.getId(),20,"braid blocked phase="+phase+" joint="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(joint)+" seg1="+(joint!=null&&clearBraidSegment(parent,root,joint))+" seg2="+(joint!=null&&clearBraidSegment(parent,joint,tip)));
            return false;
        }
        previousTip=braidTip==null?tip:braidTip;previousJoint=braidJoint==null?joint:braidJoint;
        braidTip=tip;braidJoint=joint;return true;
    }
    private boolean clearBraidSegment(YukkuriEntity parent,Vec3 from,Vec3 to){
        var hit=level().clip(new net.minecraft.world.level.ClipContext(from,to,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,parent));
        if(hit.getType()!=net.minecraft.world.phys.HitResult.Type.MISS)return false;
        for(var other:level().getEntities(parent,new AABB(from,to).inflate(.008))){
            if(other==child||other==hat()||!other.isAlive()||other.isSpectator())continue;
            if((other instanceof YukkuriEntity||other.canBeCollidedWith())&&other.getBoundingBox().inflate(.008).clip(from,to).isPresent())return false;
        }
        return true;
    }
    private void finish(YukkuriEntity parent,HatBoatEntity home){
        io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(child,"rescue.finish",0,"child="+child.getUUID()+" riding="+(child.getVehicle()==home));
        phase=Phase.IDLE;lastSide=-1;home.cancelIncoming(child);parent.family().rescueTarget(null);home.releaseRescueOwner(parent);
        // The safe child is nuzzled by real mouth contact; this neither cleans nor heals.
        parent.aquaticLife().care().expectNuzzle(child);
        parentId=hatId=null;lastVelocity=Vec3.ZERO;
    }
    public void cancel(){
        if(!active())return;
        io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(child,"rescue.cancel."+child.getId(),0,"phase="+phase+" line="+StackWalker.getInstance().walk(f->f.skip(1).findFirst().map(StackWalker.StackFrame::getLineNumber).orElse(-1))
                +" child="+io.github.longshou.mcyukkuri.subspecies.AquaDiag.at(child.position()));
        var parent=parent();var home=hat();boolean wasHeld=held();
        phase=Phase.IDLE;
        // A failed side is not retried at once; the boat may berth against another side of its brim.
        if(lastSide>=0){failedSides.put(lastSide,level().getGameTime()+160);retryAt=level().getGameTime()+40;lastSide=-1;}
        if(home!=null){home.releaseDip(10);home.cancelIncoming(child);if(parent!=null)home.releaseRescueOwner(parent);}else abandonedHat=hatId;
        if(parent!=null&&child.getUUID().equals(parent.family().rescueTarget()))parent.family().rescueTarget(null);
        // A failed rescue lets go from the child's current position; it returns to its own water struggle.
        if(!child.isPassenger()&&(wasHeld||child.isInWaterOrBubble())){
            if(wasHeld){child.setDeltaMovement(lastVelocity.add(0,-.02,0));child.hasImpulse=true;}
            child.waterDistress().plunge(hatId,null);
        }
        parentId=hatId=null;lastVelocity=Vec3.ZERO;
    }
    private void cleanupAbandoned(){
        if(abandonedHat==null)return;
        for(var loaded:level().getServer().getAllLevels())if(loaded.getEntity(abandonedHat) instanceof HatBoatEntity home){home.cancelIncoming(child);abandonedHat=null;break;}
    }
    private int duration(){
        return switch(phase){case APPROACH->APPROACH_TICKS;case OFFER->OFFER_TICKS;case REACH->20;case GRIP->BITE_TICKS;case BITE->BITE_TICKS;case PUSH->PUSH_TICKS;case SLIDE->SLIDE_TICKS;case SETTLE->SETTLE_TICKS;case RELEASE->RELEASE_TICKS;default->1;};
    }
    /** Client-sync tag. Phase, Duration (nominal ticks), Progress (0-1), Bite (0 none, 1 child mouth on rim, 2 parent mouth on
     *  child's hat), Braid (parent uses a real two-segment grip), Gripped, Skip (infant without a bite). */
    public CompoundTag state(){
        var tag=new CompoundTag();if(!active())return tag;
        var parent=parent();var home=hat();if(parent==null||home==null)return tag;
        tag.putString("Phase",phase.name());tag.putLong("Start",start);tag.putUUID("Parent",parentId);tag.putUUID("Child",child.getUUID());tag.putUUID("Hat",hatId);
        tag.putInt("ParentId",parent.getId());tag.putInt("ChildId",child.getId());tag.putInt("HatId",home.getId());tag.putFloat("Yaw",parentYaw);
        int duration=duration();tag.putInt("Duration",duration);
        double q=Math.clamp((double)(level().getGameTime()-start)/duration,0,1);
        tag.putDouble("Progress",phase==Phase.SLIDE?slideQ:phase==Phase.BITE?Math.min(1,biteTicks/(double)BITE_TICKS):q);
        tag.putInt("Bite",biteMode);tag.putInt("BiteTicks",biteTicks);tag.putBoolean("Skip",!canBite());
        tag.putBoolean("Gripped",held()||phase==Phase.BITE&&biteTicks>0||phase==Phase.GRIP&&biteTicks>0);
        tag.putBoolean("Braid",braidGrip);tag.putLong("LeanStart",leanStart);tag.putFloat("Lean",(float)lean());
        if(braidGrip&&braidTip!=null&&braidJoint!=null){
            point(tag,"Tip",braidTip);point(tag,"Joint",braidJoint);point(tag,"PrevTip",previousTip);point(tag,"PrevJoint",previousJoint);
        }
        return tag;
    }
    private static void point(CompoundTag tag,String key,Vec3 point){tag.putDouble(key+"X",point.x);tag.putDouble(key+"Y",point.y);tag.putDouble(key+"Z",point.z);}
    public CompoundTag save(){var tag=new CompoundTag();if(hatId!=null)tag.putUUID("ReservedHat",hatId);else if(abandonedHat!=null)tag.putUUID("ReservedHat",abandonedHat);return tag;}
    public void load(CompoundTag tag){
        // Loading never recreates a rescue or moves either actor. Every saved phase loads as IDLE.
        phase=Phase.IDLE;lastSide=-1;parentId=hatId=null;abandonedHat=tag.hasUUID("ReservedHat")?tag.getUUID("ReservedHat"):null;
        lastTick=Long.MIN_VALUE;retryAt=0;failedSides.clear();lastVelocity=Vec3.ZERO;
    }
}
