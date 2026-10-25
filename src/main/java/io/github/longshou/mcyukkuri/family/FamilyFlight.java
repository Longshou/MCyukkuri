package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife.Stage;
import io.github.longshou.mcyukkuri.subspecies.AquaDiag;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.crew.CrewTransfers;
import java.util.Comparator;
import java.util.HashSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Physical launch integration: the newborn's launch arc, its catch by a hat and the falling stem fruit. Boarding is a crew transfer. */
final class FamilyFlight {
    private final FamilyLife o;
    private record Flight(UUID hat,Vec3 from,Vec3 seat,Vec3 to,int ticks){}
    private Flight flight;
    private int flightTick;
    private boolean newbornLaunch,pendingNewbornLaunch;
    /** A land birth whose push was randomized: when the flight ends off its seat the kit goes on under plain physics. */
    private boolean landLaunch,pendingLandLaunch;
    private double pendingPush=1,pendingYaw;
    private String sweepWhy="";

    FamilyFlight(FamilyLife o) { this.o=o; }
    boolean hasArc() { return flight!=null; }
    boolean flying() { return o.flying(); }
    boolean targets(HatBoatEntity hat) { return flight!=null&&hat!=null&&hat.getUUID().equals(flight.hat()); }
    void save(CompoundTag tag) {
        if (flight != null) {
            var f = new CompoundTag(); if (flight.hat()!=null) f.putUUID("Hat",flight.hat());
            putVector(f,"From",flight.from()); putVector(f,"Seat",flight.seat()); putVector(f,"To",flight.to());
            f.putBoolean("Physical",true);f.putInt("Ticks",flight.ticks()); f.putInt("Elapsed",flightTick); tag.put("HatFlight",f);
        }
    }
    void load(CompoundTag tag) {
        flight = null;
        if (tag.contains("HatFlight")) {
            var f=tag.getCompound("HatFlight"); var from=vector(f,"From"); var seat=vector(f,"Seat"); var to=vector(f,"To");
            int ticks=Math.clamp(f.getInt("Ticks"),1,HatVessel.TMAX);
            if (f.getBoolean("Physical") && from!=null && to!=null && (!f.hasUUID("Hat")||seat!=null)) {
                flight=new Flight(f.hasUUID("Hat")?f.getUUID("Hat"):null,from,seat,to,ticks);
                flightTick=Math.clamp(f.getInt("Elapsed"),0,ticks-1);
            }
        }
    }

    void launchInto(HatBoatEntity hat,int tmin,Vec3 prefer){launchInto(hat,tmin,prefer,1.25);}

    void flightLog(String why){if(FamilyLife.FLIGHT_DEBUG)MCYukkuri.LOGGER.info("[flight-debug] {} {}",o.entity().getId(),why);}

    void launchInto(HatBoatEntity hat,int tmin,Vec3 prefer,double reach){launchInto(hat,tmin,prefer,reach,1,0,false);}

    /** A land birth: the nominal arc to the seat is scaled by {@code push} and turned by {@code yawDeg} before it is released. */
    void launchInto(HatBoatEntity hat,int tmin,Vec3 prefer,double reach,double push,double yawDeg,boolean land){
        flightLog("launch awaiting="+o.awaitingLanding()+" onGround="+o.entity().onGround()+" inWater="+o.entity().isInWaterOrBubble()+" at="+o.entity().position()+" hat="
                +(hat==null?null:hat.position()));
        if(hat==null||flying()||hat.unsteadyMotion())return;
        // Only the actual birth push is not a voluntary jump. Landed children must
        // approach on real ground; swimming infants wait for their parent's dipped rim.
        if(!o.awaitingLanding()||o.entity().onGround()||o.entity().isInWaterOrBubble()){
            CrewTransfers.boardFromShore(o.entity(),hat,false);return;
        }
        if(!hat.makeRoom(o.entity(),false)){flightLog("room_pending");return;}
        var seat=hat.reserve(o.entity(),prefer);if(seat==null){flightLog("no_seat");return;}
        double r=HatVessel.radius(o.entity().getBbWidth());
        var from=o.entity().position();var to=hat.toWorld(seat);
        int duration=HatVessel.solve(from,to,hat,r,tmin);
        if(from.subtract(to).horizontalDistance()>reach||!birthPathClear(hat,from,to,duration)){flightLog("refused dist="+from.subtract(to).horizontalDistance()+" clear="
                +birthPathClear(hat,from,to,duration)+" from="+from+" to="+to);hat.cancelIncoming(o.entity());return;}
        flightLog("flight "+duration+" ticks to "+to);
        pendingNewbornLaunch=o.awaitingLanding();
        pendingLandLaunch=land&&pendingNewbornLaunch;pendingPush=push;pendingYaw=yawDeg;
        beginFlight(new Flight(hat.getUUID(),from,seat,to,duration));
    }

    boolean newbornLaunching(){return newbornLaunch;}
    /** Where the current flight is aimed (the reserved seat), null without a flight. */
    Vec3 destination(){return flight==null?null:flight.to();}

    boolean birthSweepClear(HatBoatEntity hat,Vec3 from,Vec3 to){return birthSweepClear(hat,from,to,false);}
    /** {@code dropping}: a stem fruit falling into the hat set under it; the catcher holding the far brim is no obstacle either. */
    boolean birthSweepClear(HatBoatEntity hat,Vec3 from,Vec3 to,boolean dropping){
        sweepWhy="";
        var mother=o.entity().family().mother()!=null&&o.level().getEntity(o.entity().family().mother()) instanceof YukkuriEntity m?m:null;
        int steps=Math.max(1,(int)Math.ceil(from.distanceTo(to)/.025));
        for(int i=1;i<=steps;i++){
            var feet=from.lerp(to,(double)i/steps);
            if(!o.level().getWorldBorder().isWithinBounds(BlockPos.containing(feet))){sweepWhy="border";return false;}
            for(var shape:BraidRescueGeometry.boardingEnvelope(o.entity(),feet,0,0,o.entity().yBodyRot)){
                var box=shape.deflate(.002);
                if(o.level().getBlockCollisions(o.entity(),box).iterator().hasNext()){sweepWhy="block at "+feet;return false;}
                var nearby=new HashSet<Entity>(o.level().getEntities(o.entity(),box));
                for(var other:nearby){
                    // The mother (whose outlet this is) and the siblings already seated in the hat are not obstacles: the seat planner has
                    // kept the seats apart, and the strict boarding envelope of a worn hat would refuse every newborn after the first.
                    if(other==o.entity()||other==mother||other==hat||!other.isAlive()||other.isSpectator())continue;
                    if(dropping&&hat!=null&&other.getUUID().equals(hat.ownerYukkuri()))continue;
                    if(other instanceof YukkuriEntity y){
                        if(hat!=null&&y.getVehicle()==hat)continue;
                        for(var part:BraidRescueGeometry.envelope(y,y.position()))if(box.intersects(part)){sweepWhy="yukkuri "+y.getId();return false;}
                    }else if(other.canBeCollidedWith()&&box.intersects(other.getBoundingBox())){sweepWhy="entity "+other;return false;}
                }
            }
            if(hat!=null){
                double clearance=BraidRescueGeometry.poseClearance(o.entity(),feet,0,0,o.entity().yBodyRot,hat,null);
                if(clearance>feet.y+.002){sweepWhy="hull clearance "+clearance+" > y "+feet.y;return false;}
            }
        }
        return true;
    }

    /** Pushed too hard: on along the same line past the hat's far brim, down where it comes to the ground or the water. */
    void launchPast(HatBoatEntity hat){
        var from=o.entity().position();double r=HatVessel.radius(o.entity().getBbWidth());
        var seat=hat.peekSeat(o.entity());
        var far=HatVessel.overshoot(from,seat==null?hat.position():hat.toWorld(seat),hat,r);
        var hit=o.level().clip(new ClipContext(far.add(0,1.5,0),far.add(0,-3,0),ClipContext.Block.COLLIDER,
                ClipContext.Fluid.ANY,o.entity()));
        var to=hit.getType()==HitResult.Type.MISS?new Vec3(far.x,hat.getY(),far.z):hit.getLocation();
        beginFlight(new Flight(null,from,null,to,HatVessel.solve(from,to,hat,r,HatVessel.TMIN)));
    }

    boolean birthPathClear(HatBoatEntity hat,Vec3 from,Vec3 to,int ticks){
        Vec3 previous=from;
        for(int i=1;i<=ticks*4;i++){
            var at=HatVessel.arc(from,to,ticks,i/4.0);
            if(!birthSweepClear(hat,previous,at))return false;
            previous=at;
        }
        return true;
    }

    void beginFlight(Flight next){
        newbornLaunch=pendingNewbornLaunch;pendingNewbornLaunch=false;
        landLaunch=pendingLandLaunch;pendingLandLaunch=false;
        double push=pendingPush,yaw=pendingYaw;pendingPush=1;pendingYaw=0;
        flight=next;flightTick=0;
        // A single impulse. The subsequent path is velocity integration with real collision,
        // never a series of absolute positions or corrections toward a moving seat.
        var impulse=HatVessel.arc(next.from(),next.to(),next.ticks(),1).subtract(next.from());
        if(push!=1||yaw!=0){
            var across=new Vec3(impulse.x,0,impulse.z).scale(push).yRot((float)Math.toRadians(yaw));
            impulse=new Vec3(across.x,impulse.y,across.z);
        }
        o.entity().setDeltaMovement(impulse);
        o.entity().getNavigation().stop();o.entity().hasImpulse=true;
    }

    /** Called from travel while a checked boarding trajectory or gravity-driven launch owns movement. */
    boolean travelFlight(){
        // No persistent gravity flag: completion/cancellation immediately returns control to vanilla.
        if(flight==null)return false;
        var hat=flight.hat()!=null&&o.level().getEntity(flight.hat()) instanceof HatBoatEntity h&&!h.isRemoved()?h:null;
        if(flight.seat()!=null&&(hat==null||hat.capsizing()||!hat.isAfloat()&&!hat.catches())){
            if(hat!=null)hat.cancelIncoming(o.entity());
            flightLog("hat_gone_or_capsizing");
            flight=null;return false; // The committed leap falls physically; never restart it in midair.
        }
        var f=flight;var to=f.to();
        // A moving-seat-only flight is released instead of resuming a remote pull.
        // A floating hat bobs and drifts a little while a newborn crosses to it: that is followed (see the contact test), not a reason to give up.
        if(to==null||hat!=null&&hat.toWorld(f.seat()).distanceToSqr(to)>(newbornLaunch?.16:.0025)){
            if(hat!=null)hat.cancelIncoming(o.entity());flightLog("seat_moved "+(hat==null||f.seat()==null?"":String.valueOf(Math.sqrt(hat.toWorld(f.seat()).distanceToSqr(to)))));
                    flight=null;return false;
        }
        var velocity=o.entity().getDeltaMovement();var before=o.entity().position();var wanted=before.add(velocity);
        if(!birthSweepClear(hat,before,wanted)){
            if(hat!=null)hat.cancelIncoming(o.entity());flightLog("sweep_blocked at tick "+flightTick+" from "+before+" to "+wanted+" because "+sweepWhy);flight=null;
            // A swept body contact stops this step. Returning to vanilla travel here would
            // immediately repeat the rejected move through a non-solid sibling.
            o.entity().setDeltaMovement(-velocity.x*.15,Math.max(.02,-velocity.y*.15),-velocity.z*.15);
            o.entity().hasImpulse=true;return true;
        }
        o.entity().move(MoverType.SELF,velocity);
        o.entity().getNavigation().stop();o.entity().hasImpulse=true;flightTick++;
        o.entity().setDeltaMovement(velocity.add(0,-HatVessel.GRAVITY/16,0));
        if(o.entity().position().distanceToSqr(wanted)>.000001){
            if(hat!=null)hat.cancelIncoming(o.entity());flightLog("moved_short at tick "+flightTick+" wanted "+wanted+" got "+o.entity().position());flight=null;return true;
        }
        if(flightTick<f.ticks())return true;
        flight=null;boolean newborn=newbornLaunch,land=landLaunch;newbornLaunch=false;landLaunch=false;
        if(hat!=null) {
            if(hat.unsteadyMotion()){hat.cancelIncoming(o.entity());o.entity().setDeltaMovement(0,-.03,0);return true;}
            // Only actual contact with the reserved seat permits boarding. External impulses,
            // collision or a displaced hat can turn a planned catch into a real miss.
            // A newborn leaves a body it overlaps and is pushed aside by it on the way; it counts as home when it is at the (moving) seat.
            boolean contact=newborn?o.entity().position().distanceToSqr(hat.toWorld(f.seat()))<=.36&&velocity.y<=0
                    :o.entity().position().distanceToSqr(to)<=.0025&&velocity.y<=0;
            boolean landed=contact&&(o.entity().getUUID().equals(hat.ownerYukkuri())?hat.board(o.entity()):hat.boardYoung(o.entity()));
            if(!landed)flightLog("not_landed contact="+contact+" dist="+Math.sqrt(o.entity().position().distanceToSqr(to))+" vy="+velocity.y+" to="+to+" at="+o.entity().position());
            if(!landed&&land){
                // The push missed its seat: whatever velocity is left carries on under ordinary physics, and contact decides the outcome.
                hat.cancelIncoming(o.entity());o.entity().hasImpulse=true;
            }else if(!landed){hat.cancelIncoming(o.entity());o.entity().waterDistress().plunge(hat.getUUID(),null);o.entity().setDeltaMovement(0,-.03,0);}
        }
        return true;
    }

    /** The own parents' hat (set down to catch, or afloat) with room for this young, within reach. */
    HatBoatEntity nearHat(double reach){
        return o.level().getEntitiesOfClass(HatBoatEntity.class,o.entity().getBoundingBox().inflate(reach,2,reach),b->!b.unsteadyMotion()&&(b.catches()||b.isAfloat())&&b.young(o.entity())
                &&b.canMakeRoom(o.entity(),false))
                .stream().min(Comparator.comparingDouble(b->b.distanceToSqr(o.entity()))).orElse(null);
    }

    /** A falling stem fruit meets the hat: on the floor it settles there (or bounces to its place), off the brim it
     *  bounces in. */
    boolean catchFalling(){
        if(flight!=null||o.entity().getDeltaMovement().y>0)return false;
        double r=HatVessel.radius(o.entity().getBbWidth());
        String miss=null;HatBoatEntity missed=null;
        for(var hat:o.level().getEntitiesOfClass(HatBoatEntity.class,o.entity().getBoundingBox().inflate(3,4,3),b->!b.unsteadyMotion()&&(b.catches()||b.isAfloat())&&b.young(o.entity())
                &&b.canMakeRoom(o.entity(),false))){
            var l=hat.toLocal(o.entity().position());double h=HatVessel.footprint(hat.mode(),l.x,l.z,r/hat.unit());
            if(Double.isNaN(h)){
                if(o.entity().onGround()&&miss==null){miss="outside_footprint";missed=hat;}
                continue;
            }
            // Swept: the floor height was crossed (or touched) between the last position and this one while the centre is over the hat.
            var before=hat.toLocal(new Vec3(o.entity().xo,o.entity().yo,o.entity().zo));
            boolean crossed=l.y<=h+1e-3&&before.y>=h-1e-3&&l.y>h-3/hat.unit();
            if(!crossed&&(l.y>h+1e-3||l.y<h-.12/hat.unit()))continue;
            if(!hat.makeRoom(o.entity(),false)){miss="no_room";missed=hat;continue;}
            var seat=hat.reserve(o.entity(),l);  // where it came down, if free
            if(seat==null){miss="no_seat";missed=hat;continue;}
            var contact=hat.toWorld(new Vec3(l.x,h,l.z));
            // Only blocks and the hat's own shape stop a falling fruit: the mother, the catcher at the far brim and the
            // siblings already seated do not (the strict boarding envelope refused every fruit near them).
            if(!birthSweepClear(hat,o.entity().position(),contact,true)){miss="sweep_blocked "+sweepWhy;missed=hat;hat.cancelIncoming(o.entity());continue;}
            o.entity().move(MoverType.SELF,contact.subtract(o.entity().position()));
            if(o.entity().position().distanceToSqr(contact)>.000001){miss="move_blocked";missed=hat;hat.cancelIncoming(o.entity());continue;}
            o.entity().setDeltaMovement(Vec3.ZERO);
            if(h<HatVessel.top(hat.mode())-1e-6&&Math.hypot(seat.x-l.x,seat.z-l.z)<=HatVessel.SETTLE)hat.boardYoung(o.entity());
            else{
                // An actual falling catch may settle only along a short, collision-free path.
                var to=hat.toWorld(seat);int ticks=HatVessel.solve(contact,to,hat,r,HatVessel.HOP_TMIN);
                if(contact.subtract(to).horizontalDistance()<=.5&&birthPathClear(hat,contact,to,ticks)){
                    beginFlight(new Flight(hat.getUUID(),contact,seat,to,ticks));
                }else{hat.cancelIncoming(o.entity());miss="settle_path "+String.format(java.util.Locale.ROOT,"%.2f",contact.subtract(to).horizontalDistance());missed=hat;continue;}
            }
            return true;
        }
        if(miss!=null&&o.entity().family().awaitingLanding()){
            var l=missed.toLocal(o.entity().position());
            GameplayLog.limited(o.entity(),"family","catch_fall_miss",40,"falling newborn not caught hat={} reason={} local=({}, {}, {}) hatAt={}",
                    missed.getUUID(),miss,String.format(java.util.Locale.ROOT,"%.1f",l.x),String.format(java.util.Locale.ROOT,"%.1f",l.y),
                    String.format(java.util.Locale.ROOT,"%.1f",l.z),missed.position());
        }
        return false;
    }

    static void putVector(CompoundTag tag,String key,Vec3 value) {
        if(value==null)return; var v=new CompoundTag();v.putDouble("X",value.x);v.putDouble("Y",value.y);v.putDouble("Z",value.z);tag.put(key,v);
    }

    static Vec3 vector(CompoundTag tag,String key) {
        if(!tag.contains(key))return null;var v=tag.getCompound(key);
        double x=v.getDouble("X"),y=v.getDouble("Y"),z=v.getDouble("Z");
        return Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z)?new Vec3(x,y,z):null;
    }
}
